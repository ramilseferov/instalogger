package com.instalogger.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.cleanDisplayText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed class HeadlessScrapeResult {
    data class Success(val posts: List<InstagramPostItem>) : HeadlessScrapeResult()
    object PrivateAccount : HeadlessScrapeResult()
    object LoginWall : HeadlessScrapeResult()
    object RateLimited : HeadlessScrapeResult()
    data class Failed(val reason: String) : HeadlessScrapeResult()
}

/**
 * On-device headless Chromium WebView scraper that extracts public Instagram profile posts
 * directly from the rendered DOM without requiring a user login session.
 */
class HeadlessInstagramScraper(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    // Separate pools so post pagination never waits behind highlight scraping, and 2 highlights run in parallel
    private val postScrapeMutex = Mutex()
    private val storySemaphore = Semaphore(2)

    suspend fun scrapePublicPosts(username: String, limit: Int = 24): HeadlessScrapeResult {
        val cleanUser = username.trim().trimStart('@').lowercase()
        if (cleanUser.isBlank()) return HeadlessScrapeResult.Failed("Empty username")

        return postScrapeMutex.withLock {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                scrapeOnce(cleanUser, limit)
            } ?: HeadlessScrapeResult.Failed("Headless WebView timeout")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun scrapeOnce(username: String, limit: Int): HeadlessScrapeResult =
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            val scheduledCallbacks = mutableListOf<Runnable>()
            var webViewRef: WebView? = null

            fun destroyWebViewOnMain() {
                scheduledCallbacks.forEach { mainHandler.removeCallbacks(it) }
                scheduledCallbacks.clear()
                val wv = webViewRef ?: return
                webViewRef = null
                try {
                    wv.stopLoading()
                    wv.removeJavascriptInterface(BRIDGE_NAME)
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying headless WebView: ${e.message}")
                }
            }

            fun finish(result: HeadlessScrapeResult) {
                if (!finished.compareAndSet(false, true)) return
                mainHandler.post { destroyWebViewOnMain() }
                if (cont.isActive) {
                    cont.resume(result)
                }
            }

            cont.invokeOnCancellation {
                finished.set(true)
                mainHandler.post { destroyWebViewOnMain() }
            }

            mainHandler.post {
                if (finished.get()) return@post
                try {
                    val wv = WebView(appContext).apply {
                        visibility = View.INVISIBLE
                        layoutParams = ViewGroup.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                        // Layout off-screen with realistic viewport bounds so React intersection observers load grid images
                        measure(
                            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
                        )
                        layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    }
                    webViewRef = wv

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        userAgentString = MOBILE_CHROME_UA
                    }

                    val bridge = object {
                        @JavascriptInterface
                        fun onExtractionResult(jsonPayload: String) {
                            if (finished.get()) return
                            try {
                                val obj = gson.fromJson(jsonPayload, JsonObject::class.java) ?: return
                                val status = obj.get("status")?.asString ?: "error"
                                when (status) {
                                    "private" -> finish(HeadlessScrapeResult.PrivateAccount)
                                    "login_wall" -> finish(HeadlessScrapeResult.LoginWall)
                                    "ok" -> {
                                        val arr = obj.getAsJsonArray("items")
                                        val posts = mutableListOf<InstagramPostItem>()
                                        val now = System.currentTimeMillis()
                                        if (arr != null) {
                                            for (el in arr) {
                                                if (posts.size >= limit) break
                                                val item = el.asJsonObject ?: continue
                                                val sc = item.get("shortcode")?.asString?.trim().orEmpty()
                                                if (sc.isBlank()) continue
                                                val rawUrl = item.get("displayUrl")?.asString?.trim().orEmpty()
                                                val caption = item.get("caption")?.asString?.cleanDisplayText().orEmpty()
                                                val isVideo = item.get("isVideo")?.asBoolean ?: false
                                                val resolvedDisplayUrl = rawUrl.ifBlank {
                                                    "https://www.instagram.com/p/$sc/media/?size=l"
                                                }
                                                posts.add(
                                                    InstagramPostItem(
                                                        id = sc,
                                                        shortcode = sc,
                                                        mediaType = if (isVideo) "video" else "image",
                                                        displayUrl = resolvedDisplayUrl,
                                                        videoUrl = null,
                                                        caption = caption,
                                                        timestamp = now - (posts.size * 1000L),
                                                        likeCount = 0,
                                                        commentCount = 0
                                                    )
                                                )
                                            }
                                        }
                                        if (posts.isNotEmpty()) {
                                            finish(HeadlessScrapeResult.Success(posts))
                                        }
                                    }
                                    "error" -> {
                                        val errMsg = obj.get("error")?.asString ?: "JS error"
                                        Log.w(TAG, "JS extraction error for @$username: $errMsg")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed parsing bridge payload: ${e.message}")
                            }
                        }
                    }

                    wv.addJavascriptInterface(bridge, BRIDGE_NAME)

                    var pollsScheduled = false
                    fun scheduleExtractionPolls() {
                        if (pollsScheduled || finished.get()) return
                        pollsScheduled = true
                        val delays = longArrayOf(900L, 2100L, 3800L, 6000L)
                        delays.forEach { delayMs ->
                            val r = Runnable {
                                if (!finished.get()) {
                                    webViewRef?.evaluateJavascript(EXTRACTION_JS, null)
                                }
                            }
                            scheduledCallbacks.add(r)
                            mainHandler.postDelayed(r, delayMs)
                        }
                        val finalTimeout = Runnable {
                            finish(HeadlessScrapeResult.Failed("No posts found in rendered DOM"))
                        }
                        scheduledCallbacks.add(finalTimeout)
                        mainHandler.postDelayed(finalTimeout, 8500L)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val scheme = request?.url?.scheme?.lowercase() ?: return false
                            return scheme != "http" && scheme != "https"
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request?.isForMainFrame == true && errorResponse?.statusCode == 429) {
                                finish(HeadlessScrapeResult.RateLimited)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                finish(HeadlessScrapeResult.Failed("WebView main frame error: ${error?.description}"))
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (finished.get()) return
                            if (url != null && url.contains("/accounts/login")) {
                                finish(HeadlessScrapeResult.LoginWall)
                                return
                            }
                            scheduleExtractionPolls()
                        }
                    }

                    wv.loadUrl("https://www.instagram.com/$username/")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Unable to initialize headless WebView for @$username", e)
                    finish(HeadlessScrapeResult.Failed(e.message ?: "WebView init failed"))
                }
            }
        }

    /**
     * Loads a story or highlight URL inside an off-screen authenticated Chromium WebView,
     * hooking Instagram's own live GraphQL/REST fetch/XHR calls (with real fb_dtsg, lsd, doc_id)
     * plus SSR JSON & rendered media DOM to extract full-resolution story photos and videos.
     */
    suspend fun scrapeStoriesOrHighlight(
        targetUrl: String,
        highlightId: String,
        cookieString: String?
    ): List<InstagramHighlightItem> {
        AppLogger.i(TAG, "WebView story/highlight intercept start: $targetUrl (id=$highlightId)")
        return storySemaphore.withPermit {
            withTimeoutOrNull(STORY_TIMEOUT_MS) {
                scrapeStoriesOnce(targetUrl, highlightId, cookieString)
            } ?: run {
                AppLogger.w(TAG, "WebView story/highlight intercept timed out for $highlightId")
                emptyList()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun scrapeStoriesOnce(
        targetUrl: String,
        highlightId: String,
        cookieString: String?
    ): List<InstagramHighlightItem> = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        val scheduledCallbacks = mutableListOf<Runnable>()
        val collected = linkedMapOf<String, InstagramHighlightItem>()
        var webViewRef: WebView? = null

        fun destroyWebViewOnMain() {
            scheduledCallbacks.forEach { mainHandler.removeCallbacks(it) }
            scheduledCallbacks.clear()
            val wv = webViewRef ?: return
            webViewRef = null
            try {
                wv.stopLoading()
                wv.removeJavascriptInterface(STORY_BRIDGE_NAME)
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Exception) {
            }
        }

        fun finish(items: List<InstagramHighlightItem>, reason: String) {
            if (!finished.compareAndSet(false, true)) return
            AppLogger.i(TAG, "WebView story/highlight finish ($highlightId): ${items.size} item(s) [$reason]")
            mainHandler.post { destroyWebViewOnMain() }
            if (cont.isActive) {
                cont.resume(items)
            }
        }

        cont.invokeOnCancellation {
            finished.set(true)
            mainHandler.post { destroyWebViewOnMain() }
        }

        mainHandler.post {
            if (finished.get()) return@post
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                if (!cookieString.isNullOrBlank()) {
                    for (pair in cookieString.split(";")) {
                        val trimmed = pair.trim()
                        if (trimmed.contains("=")) {
                            cm.setCookie("https://www.instagram.com", "$trimmed; Path=/; Domain=.instagram.com; Secure")
                        }
                    }
                    cm.flush()
                }

                val wv = WebView(appContext).apply {
                    visibility = View.INVISIBLE
                    layoutParams = ViewGroup.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    measure(
                        View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                }
                webViewRef = wv
                cm.setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = false // Intercepted via network_json -> skip off-screen image decode for 2x speed
                    blockNetworkImage = true
                    mediaPlaybackRequiresUserGesture = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = MOBILE_CHROME_UA
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onStoryPayload(jsonPayload: String) {
                        if (finished.get()) return
                        try {
                            val obj = gson.fromJson(jsonPayload, JsonObject::class.java) ?: return
                            val status = obj.get("status")?.asString.orEmpty()
                            if (status == "login_wall") {
                                finish(emptyList(), "login_wall")
                                return
                            }
                            val arr = obj.getAsJsonArray("items") ?: return
                            val now = System.currentTimeMillis()
                            for (el in arr) {
                                val item = el.asJsonObject ?: continue
                                val url = item.get("url")?.asString?.trim().orEmpty()
                                if (url.isBlank() || !url.startsWith("http")) continue
                                val id = item.get("id")?.asString?.trim().orEmpty().ifBlank { "wv_${url.hashCode()}" }
                                val type = item.get("type")?.asString?.trim().orEmpty().ifBlank { "image" }
                                val takenAtSec = item.get("takenAt")?.asLong ?: (now / 1000L)
                                val takenAtMs = if (takenAtSec > 10_000_000_000L) takenAtSec else takenAtSec * 1000L
                                if (!collected.containsKey(id)) {
                                    collected[id] = InstagramHighlightItem(
                                        id = id,
                                        highlightId = highlightId,
                                        mediaType = type,
                                        mediaUrl = url,
                                        takenAt = takenAtMs
                                    )
                                }
                            }
                            val source = obj.get("source")?.asString ?: "unknown"
                            if (collected.isNotEmpty() && source == "network_json") {
                                // Structured network JSON gives the entire reel at once — finish cleanly!
                                finish(collected.values.toList(), "network_json")
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Story bridge parse error: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun onDebugLog(msg: String) {
                        AppLogger.d(TAG, "WV[$highlightId]: $msg")
                    }
                }

                wv.addJavascriptInterface(bridge, STORY_BRIDGE_NAME)

                var pollsScheduled = false
                fun schedulePolls() {
                    if (pollsScheduled || finished.get()) return
                    pollsScheduled = true
                    val delays = longArrayOf(1200L, 2600L, 4500L, 6800L)
                    delays.forEach { delayMs ->
                        val r = Runnable {
                            if (!finished.get()) {
                                webViewRef?.evaluateJavascript(STORY_EXTRACT_JS, null)
                            }
                        }
                        scheduledCallbacks.add(r)
                        mainHandler.postDelayed(r, delayMs)
                    }
                    val finalTimeout = Runnable {
                        finish(collected.values.toList(), if (collected.isNotEmpty()) "dom_fallback" else "timeout_empty")
                    }
                    scheduledCallbacks.add(finalTimeout)
                    mainHandler.postDelayed(finalTimeout, 9500L)
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase() ?: return false
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(STORY_NETWORK_HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (finished.get()) return
                        if (url != null && url.contains("/accounts/login")) {
                            finish(emptyList(), "redirected_to_login")
                            return
                        }
                        view?.evaluateJavascript(STORY_NETWORK_HOOK_JS, null)
                        schedulePolls()
                    }
                }

                wv.loadUrl(targetUrl)
            } catch (e: Exception) {
                AppLogger.e(TAG, "WebView story/highlight init failed", e)
                finish(emptyList(), "init_exception")
            }
        }
    }

    /**
     * Scrolls through a profile in an off-screen Chromium WebView while intercepting Instagram's
     * live GraphQL/REST timeline pagination responses, streaming every page batch via [onBatch].
     * Enables loading 300+ posts even when Instagram rotates GraphQL doc_ids.
     */
    suspend fun scrapePaginatedPosts(
        username: String,
        maxPosts: Int,
        cookieString: String?,
        scrollIntervalMs: Long = 1000L,
        onBatch: (List<InstagramPostItem>) -> Unit = {}
    ): List<InstagramPostItem> {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val safeTarget = if (maxPosts <= 0) 1000 else maxPosts.coerceIn(12, 2000)
        AppLogger.i(TAG, "WebView infinite-scroll pagination start: @$cleanUser (targetMax=$safeTarget)")
        return postScrapeMutex.withLock {
            withTimeoutOrNull(PAGINATED_TIMEOUT_MS) {
                scrapePaginatedOnce(cleanUser, safeTarget, cookieString, scrollIntervalMs, onBatch)
            } ?: run {
                AppLogger.w(TAG, "WebView paginated scroll reached timeout for @$cleanUser")
                emptyList()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun scrapePaginatedOnce(
        username: String,
        maxPosts: Int,
        cookieString: String?,
        scrollIntervalMs: Long,
        onBatch: (List<InstagramPostItem>) -> Unit
    ): List<InstagramPostItem> = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        val scheduledCallbacks = mutableListOf<Runnable>()
        val collected = linkedMapOf<String, InstagramPostItem>()
        var webViewRef: WebView? = null
        var staleScrolls = 0
        var lastCount = 0

        fun destroyWebViewOnMain() {
            scheduledCallbacks.forEach { mainHandler.removeCallbacks(it) }
            scheduledCallbacks.clear()
            val wv = webViewRef ?: return
            webViewRef = null
            try {
                wv.stopLoading()
                wv.removeJavascriptInterface(PAGINATED_BRIDGE_NAME)
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Exception) {
            }
        }

        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            val result = synchronized(collected) { collected.values.toList() }
            AppLogger.i(TAG, "WebView pagination finish (@$username): ${result.size} posts [$reason]")
            mainHandler.post { destroyWebViewOnMain() }
            if (cont.isActive) {
                cont.resume(result)
            }
        }

        cont.invokeOnCancellation {
            finished.set(true)
            mainHandler.post { destroyWebViewOnMain() }
        }

        mainHandler.post {
            if (finished.get()) return@post
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                if (!cookieString.isNullOrBlank()) {
                    for (pair in cookieString.split(";")) {
                        val trimmed = pair.trim()
                        if (trimmed.contains("=")) {
                            cm.setCookie("https://www.instagram.com", "$trimmed; Path=/; Domain=.instagram.com; Secure")
                        }
                    }
                    cm.flush()
                }

                val wv = WebView(appContext).apply {
                    visibility = View.INVISIBLE
                    layoutParams = ViewGroup.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    measure(
                        View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                }
                webViewRef = wv
                cm.setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = false // Skip decoding images inside off-screen WebView -> 3x faster scroll!
                    blockNetworkImage = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = MOBILE_CHROME_UA
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onPostBatch(jsonPayload: String) {
                        if (finished.get()) return
                        try {
                            val obj = gson.fromJson(jsonPayload, JsonObject::class.java) ?: return
                            val status = obj.get("status")?.asString.orEmpty()
                            if (status == "login_wall") {
                                finish("login_wall")
                                return
                            }
                            val arr = obj.getAsJsonArray("items") ?: return
                            val newlyAdded = mutableListOf<InstagramPostItem>()
                            val now = System.currentTimeMillis()
                            synchronized(collected) {
                                for (el in arr) {
                                    if (collected.size >= maxPosts) break
                                    val it = el.asJsonObject ?: continue
                                    val sc = it.get("shortcode")?.asString?.trim().orEmpty()
                                    if (sc.isBlank()) continue
                                    val displayUrl = it.get("displayUrl")?.asString?.trim().orEmpty()
                                        .ifBlank { "https://www.instagram.com/p/$sc/media/?size=l" }
                                    val videoUrl = it.get("videoUrl")?.asString?.trim()?.takeIf { u -> u.isNotBlank() }
                                    val mediaType = it.get("mediaType")?.asString?.trim().orEmpty().ifBlank { "image" }
                                    val caption = it.get("caption")?.asString?.cleanDisplayText().orEmpty()
                                    val likes = it.get("likes")?.asInt ?: 0
                                    val comments = it.get("comments")?.asInt ?: 0
                                    val takenAtSec = it.get("takenAt")?.asLong ?: 0L
                                    val ts = if (takenAtSec > 0L) takenAtSec * 1000L else (now - collected.size * 1000L)

                                    val existing = collected[sc]
                                    if (existing == null) {
                                        val item = InstagramPostItem(
                                            id = sc,
                                            shortcode = sc,
                                            mediaType = mediaType,
                                            displayUrl = displayUrl,
                                            videoUrl = videoUrl,
                                            caption = caption,
                                            timestamp = ts,
                                            likeCount = likes,
                                            commentCount = comments
                                        )
                                        collected[sc] = item
                                        newlyAdded.add(item)
                                    } else if (existing.likeCount == 0 && likes > 0) {
                                        val enriched = existing.copy(
                                            likeCount = likes,
                                            commentCount = comments,
                                            caption = caption.ifBlank { existing.caption },
                                            timestamp = if (takenAtSec > 0L) ts else existing.timestamp,
                                            videoUrl = videoUrl ?: existing.videoUrl
                                        )
                                        collected[sc] = enriched
                                        newlyAdded.add(enriched)
                                    }
                                }
                            }
                            if (newlyAdded.isNotEmpty()) {
                                staleScrolls = 0
                                onBatch(newlyAdded)
                                if (collected.size >= maxPosts) {
                                    finish("reached_max_$maxPosts")
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Paginated batch parse error: ${e.message}")
                        }
                    }
                }

                wv.addJavascriptInterface(bridge, PAGINATED_BRIDGE_NAME)

                var scrollingStarted = false
                fun startScrollLoop() {
                    if (scrollingStarted || finished.get()) return
                    scrollingStarted = true
                    val stepRunnable = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            val curCount = synchronized(collected) { collected.size }
                            if (curCount >= maxPosts) {
                                finish("reached_max_$maxPosts")
                                return
                            }
                            if (curCount == lastCount) {
                                staleScrolls++
                            } else {
                                lastCount = curCount
                                staleScrolls = 0
                            }
                            if (staleScrolls >= 6) {
                                finish("end_of_feed")
                                return
                            }
                            webViewRef?.evaluateJavascript(POSTS_SCROLL_EXTRACT_JS, null)
                            mainHandler.postDelayed(this, scrollIntervalMs.coerceIn(600L, 2500L))
                        }
                    }
                    scheduledCallbacks.add(stepRunnable)
                    mainHandler.postDelayed(stepRunnable, 1100L)
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase() ?: return false
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(POSTS_NETWORK_HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (finished.get()) return
                        if (url != null && url.contains("/accounts/login")) {
                            finish("redirected_to_login")
                            return
                        }
                        view?.evaluateJavascript(POSTS_NETWORK_HOOK_JS, null)
                        startScrollLoop()
                    }
                }

                wv.loadUrl("https://www.instagram.com/$username/")
            } catch (e: Exception) {
                AppLogger.e(TAG, "WebView pagination init failed for @$username", e)
                finish("init_exception")
            }
        }
    }

    companion object {
        private const val TAG = "HeadlessIGScraper"
        private const val BRIDGE_NAME = "AndroidBridge"
        private const val STORY_BRIDGE_NAME = "StoryBridge"
        private const val PAGINATED_BRIDGE_NAME = "PaginatedPostsBridge"
        private const val TOTAL_TIMEOUT_MS = 22_000L
        private const val STORY_TIMEOUT_MS = 16_000L
        private const val PAGINATED_TIMEOUT_MS = 95_000L
        private const val VIEWPORT_WIDTH = 1080
        private const val VIEWPORT_HEIGHT = 1920
        private const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val POSTS_NETWORK_HOOK_JS = """
            (function() {
                if (window.__igPostsHookInstalled) return;
                window.__igPostsHookInstalled = true;

                function extractPostsFromText(text) {
                    if (!text || text.length < 60) return;
                    if (text.indexOf('polaris_ordered_timeline_connection') === -1 &&
                        text.indexOf('user_timeline_graphql_connection') === -1 &&
                        text.indexOf('edge_owner_to_timeline_media') === -1 &&
                        text.indexOf('"code"') === -1) return;
                    try {
                        const data = JSON.parse(text);
                        const batch = [];
                        const seen = new Set();
                        function pushNode(n) {
                            if (!n || typeof n !== 'object') return;
                            const sc = n.code || n.shortcode || '';
                            if (!sc || seen.has(sc)) return;
                            seen.add(sc);
                            const isCar = n.media_type === 8 || String(n.__typename || '').indexOf('Carousel') !== -1 || String(n.__typename || '').indexOf('Sidecar') !== -1 || (Array.isArray(n.carousel_media) && n.carousel_media.length > 1);
                            const isVid = n.media_type === 2 || n.is_video === true || String(n.__typename || '').indexOf('Video') !== -1;
                            const img = n.display_uri || n.display_url || (n.image_versions2 && Array.isArray(n.image_versions2.candidates) && n.image_versions2.candidates[0] ? n.image_versions2.candidates[0].url : '') || '';
                            const vid = n.video_url || (Array.isArray(n.video_versions) && n.video_versions[0] ? n.video_versions[0].url : '') || '';
                            const cap = (n.caption && n.caption.text) ? n.caption.text : (n.edge_media_to_caption && n.edge_media_to_caption.edges && n.edge_media_to_caption.edges[0] && n.edge_media_to_caption.edges[0].node ? n.edge_media_to_caption.edges[0].node.text : '');
                            const likes = Number(n.like_count || (n.edge_media_preview_like && n.edge_media_preview_like.count) || 0);
                            const comments = Number(n.comment_count || (n.edge_media_to_comment && n.edge_media_to_comment.count) || 0);
                            const ts = Number(n.taken_at || n.taken_at_timestamp || 0);
                            batch.push({
                                shortcode: sc,
                                mediaType: isCar ? 'carousel' : (isVid ? 'video' : 'image'),
                                displayUrl: img,
                                videoUrl: vid,
                                caption: cap || '',
                                likes: likes,
                                comments: comments,
                                takenAt: ts
                            });
                        }
                        function walk(node) {
                            if (!node || typeof node !== 'object') return;
                            if (Array.isArray(node)) { node.forEach(walk); return; }
                            const conn = node.polaris_ordered_timeline_connection || node.xdt_api__v1__feed__user_timeline_graphql_connection || node.edge_owner_to_timeline_media;
                            if (conn && Array.isArray(conn.edges)) {
                                conn.edges.forEach(function(e) { if (e && e.node) pushNode(e.node); });
                            }
                            if (Array.isArray(node.items)) {
                                node.items.forEach(function(it) { if (it && it.code) pushNode(it); });
                            }
                            Object.keys(node).forEach(function(k) { walk(node[k]); });
                        }
                        walk(data);
                        if (batch.length > 0 && window.PaginatedPostsBridge) {
                            window.PaginatedPostsBridge.onPostBatch(JSON.stringify({ status: 'ok', items: batch }));
                        }
                    } catch (_) {}
                }

                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        const url = (arguments[0] && typeof arguments[0] === 'string') ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                if (url.indexOf('graphql') !== -1 || url.indexOf('feed/user') !== -1 || url.indexOf('web_profile_info') !== -1) {
                                    resp.clone().text().then(extractPostsFromText).catch(function(){});
                                }
                            } catch (_) {}
                            return resp;
                        });
                    };
                }

                const origOpen = XMLHttpRequest.prototype.open;
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__igPostUrl = String(url || '');
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        try {
                            const u = this.__igPostUrl || '';
                            if (u.indexOf('graphql') !== -1 || u.indexOf('feed/user') !== -1 || u.indexOf('web_profile_info') !== -1) {
                                extractPostsFromText(this.responseText || '');
                            }
                        } catch (_) {}
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """.trimIndent()

        private val POSTS_SCROLL_EXTRACT_JS = """
            (function() {
                try {
                    const href = window.location.href || '';
                    if (href.indexOf('/accounts/login') !== -1) {
                        if (window.PaginatedPostsBridge) window.PaginatedPostsBridge.onPostBatch(JSON.stringify({ status: 'login_wall', items: [] }));
                        return;
                    }
                    // Dismiss login modal overlay if it blocks scrolling on guest mode
                    try {
                        const closeBtns = document.querySelectorAll('div[role="dialog"] button, svg[aria-label="Close"]');
                        closeBtns.forEach(function(el) {
                            const btn = el.closest('button') || el;
                            if (btn && btn.click) btn.click();
                        });
                    } catch (_) {}

                    const batch = [];
                    const seen = new Set();
                    document.querySelectorAll('main a[href*="/p/"], main a[href*="/reel/"], article a[href*="/p/"], article a[href*="/reel/"]').forEach(function(a) {
                        const h = a.getAttribute('href') || '';
                        const m = h.match(/\/(p|reel)\/([A-Za-z0-9_-]+)/);
                        if (!m) return;
                        const sc = m[2];
                        if (seen.has(sc)) return;
                        seen.add(sc);
                        const img = a.querySelector('img');
                        const src = img ? (img.currentSrc || img.src || '') : '';
                        const alt = img ? (img.alt || '') : '';
                        const isVid = h.indexOf('/reel/') !== -1 || a.querySelector('svg[aria-label*="Video"], svg[aria-label*="Reel"], svg[aria-label*="Clip"]') !== null;
                        const isCar = a.querySelector('svg[aria-label*="Carousel"]') !== null;
                        batch.push({
                            shortcode: sc,
                            mediaType: isCar ? 'carousel' : (isVid ? 'video' : 'image'),
                            displayUrl: src,
                            videoUrl: '',
                            caption: alt,
                            likes: 0,
                            comments: 0,
                            takenAt: 0
                        });
                    });
                    if (batch.length > 0 && window.PaginatedPostsBridge) {
                        window.PaginatedPostsBridge.onPostBatch(JSON.stringify({ status: 'ok', items: batch }));
                    }
                    window.scrollTo(0, document.body.scrollHeight);
                } catch (_) {}
            })();
        """.trimIndent()

        private val STORY_NETWORK_HOOK_JS = """
            (function() {
                if (window.__igStoryHookInstalled) return;
                window.__igStoryHookInstalled = true;

                function extractItemsFromJsonText(text, endpoint) {
                    if (!text || text.length < 40) return;
                    if (text.indexOf('video_versions') === -1 &&
                        text.indexOf('image_versions2') === -1 &&
                        text.indexOf('reels_media') === -1) return;
                    try {
                        const data = JSON.parse(text);
                        const found = [];
                        const seen = new Set();
                        function walk(node) {
                            if (!node || typeof node !== 'object') return;
                            if (Array.isArray(node)) { node.forEach(walk); return; }
                            if (Array.isArray(node.items)) {
                                node.items.forEach(function(it) {
                                    if (!it || typeof it !== 'object') return;
                                    const pk = String(it.pk || it.id || '');
                                    const vid = (Array.isArray(it.video_versions) && it.video_versions[0] && it.video_versions[0].url) ? it.video_versions[0].url : '';
                                    const img = (it.image_versions2 && Array.isArray(it.image_versions2.candidates) && it.image_versions2.candidates[0] && it.image_versions2.candidates[0].url) ? it.image_versions2.candidates[0].url : '';
                                    const isVid = !!vid || it.media_type === 2 || it.is_video === true;
                                    const url = isVid && vid ? vid : img;
                                    if (!url) return;
                                    const key = pk || url;
                                    if (seen.has(key)) return;
                                    seen.add(key);
                                    found.push({
                                        id: pk || ('item_' + found.length),
                                        type: isVid ? 'video' : 'image',
                                        url: url,
                                        takenAt: Number(it.taken_at || it.taken_at_timestamp || 0)
                                    });
                                });
                            }
                            Object.keys(node).forEach(function(k) { walk(node[k]); });
                        }
                        walk(data);
                        if (found.length > 0 && window.StoryBridge) {
                            window.StoryBridge.onDebugLog('Intercepted ' + found.length + ' items from ' + endpoint);
                            window.StoryBridge.onStoryPayload(JSON.stringify({ status: 'ok', source: 'network_json', items: found }));
                        }
                    } catch (_) {}
                }

                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        const url = (arguments[0] && typeof arguments[0] === 'string') ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                if (url.indexOf('graphql') !== -1 || url.indexOf('reels_media') !== -1 || url.indexOf('story') !== -1) {
                                    resp.clone().text().then(function(t) { extractItemsFromJsonText(t, url.slice(0, 80)); }).catch(function(){});
                                }
                            } catch (_) {}
                            return resp;
                        });
                    };
                }

                const origOpen = XMLHttpRequest.prototype.open;
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__igUrl = String(url || '');
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        try {
                            const u = this.__igUrl || '';
                            if (u.indexOf('graphql') !== -1 || u.indexOf('reels_media') !== -1 || u.indexOf('story') !== -1) {
                                extractItemsFromJsonText(this.responseText || '', u.slice(0, 80));
                            }
                        } catch (_) {}
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """.trimIndent()

        private val STORY_EXTRACT_JS = """
            (function() {
                try {
                    const href = window.location.href || '';
                    if (href.indexOf('/accounts/login') !== -1) {
                        if (window.StoryBridge) window.StoryBridge.onStoryPayload(JSON.stringify({ status: 'login_wall', items: [] }));
                        return;
                    }
                    // Click "View story" / "Hekayəyə bax" button if Instagram shows an interstitial gate
                    try {
                        const buttons = document.querySelectorAll('button, div[role="button"]');
                        for (let i = 0; i < buttons.length; i++) {
                            const txt = (buttons[i].innerText || '').trim().toLowerCase();
                            if (txt === 'view story' || txt === 'watch story' || txt === 'continue' || txt.indexOf('view story') !== -1) {
                                buttons[i].click();
                                break;
                            }
                        }
                    } catch (_) {}

                    const found = [];
                    const seen = new Set();

                    // 1. Scan embedded JSON scripts
                    const scripts = document.querySelectorAll('script[type="application/json"]');
                    function walk(node) {
                        if (!node || typeof node !== 'object') return;
                        if (Array.isArray(node)) { node.forEach(walk); return; }
                        if (Array.isArray(node.items)) {
                            node.items.forEach(function(it) {
                                if (!it || typeof it !== 'object') return;
                                const pk = String(it.pk || it.id || '');
                                const vid = (Array.isArray(it.video_versions) && it.video_versions[0] && it.video_versions[0].url) ? it.video_versions[0].url : '';
                                const img = (it.image_versions2 && Array.isArray(it.image_versions2.candidates) && it.image_versions2.candidates[0] && it.image_versions2.candidates[0].url) ? it.image_versions2.candidates[0].url : '';
                                const isVid = !!vid || it.media_type === 2 || it.is_video === true;
                                const url = isVid && vid ? vid : img;
                                if (!url) return;
                                const key = pk || url;
                                if (seen.has(key)) return;
                                seen.add(key);
                                found.push({ id: pk || ('ssr_' + found.length), type: isVid ? 'video' : 'image', url: url, takenAt: Number(it.taken_at || 0) });
                            });
                        }
                        Object.keys(node).forEach(function(k) { walk(node[k]); });
                    }
                    scripts.forEach(function(s) {
                        const txt = s.textContent || '';
                        if (txt.indexOf('reels_media') !== -1 || txt.indexOf('video_versions') !== -1 || txt.indexOf('image_versions2') !== -1) {
                            try { walk(JSON.parse(txt)); } catch (_) {}
                        }
                    });

                    if (found.length > 0 && window.StoryBridge) {
                        window.StoryBridge.onStoryPayload(JSON.stringify({ status: 'ok', source: 'network_json', items: found }));
                        return;
                    }

                    // 2. Scan rendered <video> and story <img> elements
                    const domItems = [];
                    document.querySelectorAll('video').forEach(function(v) {
                        const src = v.currentSrc || v.src || (v.querySelector('source') ? v.querySelector('source').src : '');
                        if (src && src.indexOf('http') === 0 && !seen.has(src)) {
                            seen.add(src);
                            domItems.push({ id: 'dom_v_' + domItems.length, type: 'video', url: src, takenAt: Math.floor(Date.now() / 1000) });
                        }
                    });
                    document.querySelectorAll('section img, main img').forEach(function(img) {
                        const src = img.currentSrc || img.src || '';
                        const rect = img.getBoundingClientRect();
                        if (!src || src.indexOf('http') !== 0) return;
                        if (src.indexOf('s150x150') !== -1 || src.indexOf('static.cdninstagram.com') !== -1) return;
                        if (rect.width < 180 || rect.height < 240) return;
                        if (!seen.has(src)) {
                            seen.add(src);
                            domItems.push({ id: 'dom_i_' + domItems.length, type: 'image', url: src, takenAt: Math.floor(Date.now() / 1000) });
                        }
                    });
                    if (domItems.length > 0 && window.StoryBridge) {
                        window.StoryBridge.onStoryPayload(JSON.stringify({ status: 'ok', source: 'dom_media', items: domItems }));
                    }
                } catch (e) {
                    if (window.StoryBridge) window.StoryBridge.onDebugLog('extract error: ' + String(e));
                }
            })();
        """.trimIndent()

        private val EXTRACTION_JS = """
            (function() {
                try {
                    const hrefNow = window.location.href || '';
                    if (hrefNow.includes('/accounts/login')) {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onExtractionResult(JSON.stringify({ status: 'login_wall', items: [] }));
                        }
                        return;
                    }
                    const bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
                    if (/this account is private/i.test(bodyText) || /bu hesab gizlidir/i.test(bodyText)) {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onExtractionResult(JSON.stringify({ status: 'private', items: [] }));
                        }
                        return;
                    }
                    const items = [];
                    const seen = new Set();

                    // 1. Parse embedded SSR Relay JSON scripts (polaris_ordered_timeline_connection)
                    try {
                        const scripts = document.querySelectorAll('script[type="application/json"]');
                        function walkJson(node) {
                            if (!node || typeof node !== 'object') return;
                            if (Array.isArray(node)) { node.forEach(walkJson); return; }
                            const conn = node.polaris_ordered_timeline_connection || node.edge_owner_to_timeline_media;
                            if (conn && Array.isArray(conn.edges)) {
                                conn.edges.forEach(function(e) {
                                    const n = e && e.node;
                                    if (!n) return;
                                    const sc = n.code || n.shortcode || '';
                                    if (!sc || seen.has(sc)) return;
                                    seen.add(sc);
                                    const isVid = n.media_type === 2 || n.is_video === true || String(n.__typename || '').indexOf('Video') !== -1;
                                    const cap = (n.caption && n.caption.text) ? n.caption.text : (n.accessibility_caption || '');
                                    items.push({
                                        shortcode: sc,
                                        displayUrl: n.display_uri || n.display_url || '',
                                        caption: cap,
                                        isVideo: isVid
                                    });
                                });
                            }
                            Object.keys(node).forEach(function(k) { walkJson(node[k]); });
                        }
                        scripts.forEach(function(s) {
                            const txt = s.textContent || '';
                            if (txt.indexOf('polaris_ordered_timeline_connection') !== -1 || txt.indexOf('edge_owner_to_timeline_media') !== -1) {
                                try { walkJson(JSON.parse(txt)); } catch (_) {}
                            }
                        });
                    } catch (_) {}

                    // 2. Also scan rendered DOM anchor links
                    const links = document.querySelectorAll('main a[href*="/p/"], main a[href*="/reel/"], article a[href*="/p/"], article a[href*="/reel/"]');
                    links.forEach(function(a) {
                        const href = a.getAttribute('href') || '';
                        const m = href.match(/\/(p|reel)\/([A-Za-z0-9_-]+)/);
                        if (!m) return;
                        const sc = m[2];
                        if (seen.has(sc)) return;
                        seen.add(sc);
                        const img = a.querySelector('img');
                        let src = '';
                        if (img) {
                            src = img.currentSrc || img.src || img.getAttribute('src') || '';
                            if (!src && img.srcset) {
                                const parts = img.srcset.split(',');
                                if (parts.length > 0) {
                                    src = parts[parts.length - 1].trim().split(' ')[0];
                                }
                            }
                        }
                        const alt = img ? (img.alt || '') : '';
                        const isVideo = href.includes('/reel/') ||
                            a.querySelector('svg[aria-label*="Video"], svg[aria-label*="Reel"], svg[aria-label*="Clip"]') !== null;
                        items.push({
                            shortcode: sc,
                            displayUrl: src,
                            caption: alt,
                            isVideo: isVideo
                        });
                    });
                    const status = items.length > 0 ? 'ok' : 'pending';
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onExtractionResult(JSON.stringify({ status: status, items: items }));
                    }
                } catch (e) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onExtractionResult(JSON.stringify({ status: 'error', error: String(e), items: [] }));
                    }
                }
            })();
        """.trimIndent()
    }
}
