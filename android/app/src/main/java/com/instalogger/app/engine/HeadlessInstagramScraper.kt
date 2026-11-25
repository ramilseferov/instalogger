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
    // Separate pools so profile checks, highlight scraping, post pagination, and comment scraping never block one another
    private val postScrapeMutex = Mutex()
    private val storySemaphore = Semaphore(2)
    private val profileSemaphore = Semaphore(2)
    private val commentSemaphore = Semaphore(1)

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
     * Loads a profile page in an off-screen authenticated Chromium WebView and intercepts
     * Instagram's own live GraphQL/REST & SSR payloads strictly matching [username].
     * Works reliably even when anonymous SSR hits HTTP 302 (is_from_rle) rate-limit walls.
     */
    suspend fun scrapeProfile(
        username: String,
        cookieString: String?
    ): InstagramProfileSnapshot? {
        val cleanUser = username.trim().trimStart('@').lowercase()
        if (cleanUser.isBlank()) return null
        AppLogger.i(TAG, "WebView profile intercept start: @$cleanUser")
        return profileSemaphore.withPermit {
            withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
                scrapeProfileOnce(cleanUser, cookieString)
            } ?: run {
                AppLogger.w(TAG, "WebView profile intercept timed out for @$cleanUser")
                null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun scrapeProfileOnce(
        username: String,
        cookieString: String?
    ): InstagramProfileSnapshot? = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        val scheduledCallbacks = mutableListOf<Runnable>()
        var webViewRef: WebView? = null
        var bestSnapshot: InstagramProfileSnapshot? = null

        fun destroyWebViewOnMain() {
            scheduledCallbacks.forEach { mainHandler.removeCallbacks(it) }
            scheduledCallbacks.clear()
            val wv = webViewRef ?: return
            webViewRef = null
            try {
                wv.stopLoading()
                wv.removeJavascriptInterface(PROFILE_BRIDGE_NAME)
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            } catch (_: Exception) {
            }
        }

        fun finish(result: InstagramProfileSnapshot?, reason: String) {
            if (!finished.compareAndSet(false, true)) return
            AppLogger.i(TAG, "WebView profile finish (@$username): ok=${result != null} pk=${result?.instagramId ?: "-"} followers=${result?.followersCount ?: 0} posts=${result?.mediaCount ?: 0} [$reason]")
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
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = MOBILE_CHROME_UA
                }

                val hookJs = buildProfileNetworkHookJs(username)
                val extractJs = buildProfileExtractJs(username)

                val bridge = object {
                    @JavascriptInterface
                    fun onProfilePayload(jsonPayload: String) {
                        if (finished.get()) return
                        try {
                            val obj = gson.fromJson(jsonPayload, JsonObject::class.java) ?: return
                            val status = obj.get("status")?.asString.orEmpty()
                            if (status == "login_wall") {
                                finish(null, "login_wall")
                                return
                            }
                            if (status != "ok") return
                            val returnedUser = obj.get("username")?.asString?.trim()?.lowercase().orEmpty()
                            if (returnedUser != username) return

                            val pk = obj.get("pk")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotBlank() }
                            val fullName = obj.get("fullName")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
                            val bio = obj.get("biography")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
                            val isPrivate = obj.get("isPrivate")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                            val followers = obj.get("followers")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            val following = obj.get("following")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            val mediaCount = obj.get("mediaCount")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            val picUrl = obj.get("picUrl")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.startsWith("http") }
                            val latestReelSec = obj.get("latestStorySec")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            val latestStoryAt = if (latestReelSec > 10_000_000_000L) latestReelSec else if (latestReelSec > 0L) latestReelSec * 1000L else 0L

                            val snap = InstagramProfileSnapshot(
                                username = username,
                                instagramId = pk,
                                fullName = fullName,
                                biography = bio,
                                isPrivate = isPrivate,
                                followersCount = followers,
                                followingCount = following,
                                mediaCount = mediaCount,
                                profilePicUrl = picUrl,
                                latestStoryAt = latestStoryAt
                            )
                            bestSnapshot = snap
                            val hasStats = followers > 0L || following > 0L || mediaCount > 0L || isPrivate
                            if (!pk.isNullOrBlank() && hasStats) {
                                finish(snap, obj.get("source")?.asString ?: "network_json")
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Profile bridge parse error: ${e.message}")
                        }
                    }
                }

                wv.addJavascriptInterface(bridge, PROFILE_BRIDGE_NAME)

                var pollsScheduled = false
                fun schedulePolls() {
                    if (pollsScheduled || finished.get()) return
                    pollsScheduled = true
                    val delays = longArrayOf(900L, 2200L, 4200L, 6500L)
                    delays.forEach { d ->
                        val r = Runnable {
                            if (!finished.get()) {
                                webViewRef?.evaluateJavascript(extractJs, null)
                            }
                        }
                        scheduledCallbacks.add(r)
                        mainHandler.postDelayed(r, d)
                    }
                    val finalTimeout = Runnable {
                        finish(bestSnapshot, if (bestSnapshot != null) "best_partial" else "timeout_empty")
                    }
                    scheduledCallbacks.add(finalTimeout)
                    mainHandler.postDelayed(finalTimeout, 9000L)
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase() ?: return false
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(hookJs, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (finished.get()) return
                        if (url != null && url.contains("/accounts/login")) {
                            finish(null, "redirected_to_login")
                            return
                        }
                        view?.evaluateJavascript(hookJs, null)
                        schedulePolls()
                    }
                }

                wv.loadUrl("https://www.instagram.com/$username/")
            } catch (e: Exception) {
                AppLogger.e(TAG, "WebView profile init failed for @$username", e)
                finish(null, "init_exception")
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

    /**
     * Headless WebView Post/Reel Comments & Threaded Replies Network Interceptor.
     * Captures Instagram's live GraphQL/REST comment + child_comment payloads while automatically
     * scrolling the comment list and expanding "View replies" buttons.
     */
    suspend fun scrapePostComments(
        shortcode: String,
        cookieString: String?,
        maxComments: Int = 500,
        onBatch: ((List<InstagramCommentItem>) -> Unit)? = null
    ): List<InstagramCommentItem> {
        val cleanCode = shortcode.trim().substringBefore('/').substringBefore('?')
        if (cleanCode.isBlank()) return emptyList()
        return commentSemaphore.withPermit {
            withTimeoutOrNull(COMMENTS_TIMEOUT_MS) {
                scrapePostCommentsOnce(cleanCode, cookieString, maxComments, onBatch)
            } ?: emptyList()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun scrapePostCommentsOnce(
        shortcode: String,
        cookieString: String?,
        maxComments: Int,
        onBatch: ((List<InstagramCommentItem>) -> Unit)?
    ): List<InstagramCommentItem> = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        val scheduledCallbacks = mutableListOf<Runnable>()
        val collected = java.util.Collections.synchronizedMap(LinkedHashMap<String, InstagramCommentItem>())
        var webViewRef: WebView? = null
        var scrollStarted = false
        var lastCount = 0
        var staleSteps = 0

        fun destroyWebViewOnMain() {
            scheduledCallbacks.forEach { mainHandler.removeCallbacks(it) }
            scheduledCallbacks.clear()
            val wv = webViewRef ?: return
            webViewRef = null
            try {
                wv.stopLoading()
                wv.removeJavascriptInterface(COMMENTS_BRIDGE_NAME)
                wv.webViewClient = WebViewClient()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying comments WebView: ${e.message}")
            }
        }

        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            val result = synchronized(collected) { collected.values.toList() }
            AppLogger.i(TAG, "WebView comments finish ($shortcode): ${result.size} comments [$reason]")
            mainHandler.post { destroyWebViewOnMain() }
            if (cont.isActive) cont.resume(result)
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
                if (!cookieString.isNullOrBlank()) {
                    cookieString.split(";").forEach { rawPair ->
                        val pair = rawPair.trim()
                        if (pair.contains("=")) {
                            cm.setCookie("https://www.instagram.com", "$pair; Domain=.instagram.com; Path=/; Secure")
                        }
                    }
                    cm.flush()
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = MOBILE_CHROME_UA
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onCommentBatch(jsonPayload: String) {
                        if (finished.get()) return
                        try {
                            val root = gson.fromJson(jsonPayload, JsonObject::class.java) ?: return
                            val status = root.get("status")?.asString ?: ""
                            if (status == "login_wall") {
                                finish("login_wall")
                                return
                            }
                            val arr = root.getAsJsonArray("items") ?: return
                            val newlyAdded = mutableListOf<InstagramCommentItem>()
                            synchronized(collected) {
                                for (el in arr) {
                                    if (collected.size >= maxComments) break
                                    val o = el.asJsonObject ?: continue
                                    val cid = o.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                                    val text = o.get("text")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
                                    val author = o.get("authorUsername")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                                    if (cid.isBlank() || (text.isBlank() && author.isBlank())) continue
                                    val parentId = o.get("parentCommentId")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
                                    val authorId = o.get("authorId")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
                                    val fullName = o.get("authorFullName")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
                                    val avatar = o.get("authorAvatarUrl")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
                                    val verified = o.get("isVerified")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    val likes = o.get("likeCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    val replyCnt = o.get("replyCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    val createdSec = o.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                                    val createdMs = when {
                                        createdSec > 10_000_000_000L -> createdSec
                                        createdSec > 0L -> createdSec * 1000L
                                        else -> System.currentTimeMillis()
                                    }
                                    val hasMedia = o.get("hasGifOrMedia")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    val item = InstagramCommentItem(
                                        id = cid,
                                        parentCommentId = parentId,
                                        authorUsername = author.ifBlank { "instagram_user" },
                                        authorId = authorId,
                                        authorFullName = fullName,
                                        authorAvatarUrl = avatar,
                                        isAuthorVerified = verified,
                                        text = text,
                                        likeCount = likes,
                                        replyCount = replyCnt,
                                        createdAt = createdMs,
                                        hasGifOrMedia = hasMedia
                                    )
                                    val prev = collected[cid]
                                    if (prev == null) {
                                        collected[cid] = item
                                        newlyAdded.add(item)
                                    } else if (
                                        item.likeCount > prev.likeCount ||
                                        item.replyCount > prev.replyCount ||
                                        (prev.parentCommentId == null && item.parentCommentId != null)
                                    ) {
                                        val merged = prev.copy(
                                            parentCommentId = prev.parentCommentId ?: item.parentCommentId,
                                            likeCount = maxOf(prev.likeCount, item.likeCount),
                                            replyCount = maxOf(prev.replyCount, item.replyCount),
                                            authorAvatarUrl = prev.authorAvatarUrl ?: item.authorAvatarUrl,
                                            isAuthorVerified = prev.isAuthorVerified || item.isAuthorVerified
                                        )
                                        collected[cid] = merged
                                        newlyAdded.add(merged)
                                    }
                                }
                            }
                            if (newlyAdded.isNotEmpty()) {
                                staleSteps = 0
                                onBatch?.invoke(newlyAdded)
                                if (collected.size >= maxComments) {
                                    finish("reached_max_$maxComments")
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                wv.addJavascriptInterface(bridge, COMMENTS_BRIDGE_NAME)
                AppLogger.i(TAG, "WebView comments intercept start: $shortcode (max=$maxComments)")

                fun startExpandLoop() {
                    if (scrollStarted || finished.get()) return
                    scrollStarted = true
                    val stepRunnable = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            val curCount = collected.size
                            if (curCount >= maxComments) {
                                finish("reached_max_$maxComments")
                                return
                            }
                            if (curCount == lastCount) {
                                staleSteps++
                            } else {
                                lastCount = curCount
                                staleSteps = 0
                            }
                            if (staleSteps >= 5) {
                                finish("end_of_comments")
                                return
                            }
                            webViewRef?.evaluateJavascript(COMMENTS_SCROLL_AND_EXPAND_JS, null)
                            mainHandler.postDelayed(this, 1100L)
                        }
                    }
                    scheduledCallbacks.add(stepRunnable)
                    mainHandler.postDelayed(stepRunnable, 1200L)
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase() ?: return false
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(COMMENTS_NETWORK_HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (finished.get()) return
                        if (url != null && url.contains("/accounts/login")) {
                            finish("redirected_to_login")
                            return
                        }
                        view?.evaluateJavascript(COMMENTS_NETWORK_HOOK_JS, null)
                        startExpandLoop()
                    }
                }

                wv.loadUrl("https://www.instagram.com/p/$shortcode/")
            } catch (e: Exception) {
                AppLogger.e(TAG, "WebView comments init failed for $shortcode", e)
                finish("init_exception")
            }
        }
    }

    companion object {
        private const val TAG = "HeadlessIGScraper"
        private const val BRIDGE_NAME = "AndroidBridge"
        private const val STORY_BRIDGE_NAME = "StoryBridge"
        private const val PAGINATED_BRIDGE_NAME = "PaginatedPostsBridge"
        private const val PROFILE_BRIDGE_NAME = "ProfileBridge"
        private const val COMMENTS_BRIDGE_NAME = "CommentsBridge"
        private const val TOTAL_TIMEOUT_MS = 22_000L
        private const val STORY_TIMEOUT_MS = 16_000L
        private const val PROFILE_TIMEOUT_MS = 15_000L
        private const val PAGINATED_TIMEOUT_MS = 95_000L
        private const val COMMENTS_TIMEOUT_MS = 38_000L

        private fun buildProfileNetworkHookJs(targetUsername: String): String {
            val safeTarget = targetUsername.replace("'", "").lowercase()
            return """
                (function() {
                    if (window.__igProfileHookInstalled) return;
                    window.__igProfileHookInstalled = true;
                    const TARGET = '$safeTarget';

                    function scoreUser(o) {
                        let s = 0;
                        if (o.pk && /^\d+$/.test(String(o.pk))) s += 4;
                        if (o.follower_count !== undefined || (o.edge_followed_by && o.edge_followed_by.count !== undefined)) s += 5;
                        if (o.following_count !== undefined || (o.edge_follow && o.edge_follow.count !== undefined)) s += 3;
                        if (o.media_count !== undefined || (o.edge_owner_to_timeline_media && o.edge_owner_to_timeline_media.count !== undefined)) s += 2;
                        if (o.hd_profile_pic_url_info || o.profile_pic_url_hd || o.profile_pic_url) s += 2;
                        if (o.full_name !== undefined) s += 1;
                        return s;
                    }

                    function inspectJsonText(text, source) {
                        if (!text || text.length < 40 || text.toLowerCase().indexOf(TARGET) === -1) return;
                        try {
                            const root = JSON.parse(text);
                            let best = null;
                            let bestScore = -1;
                            function walk(n) {
                                if (!n || typeof n !== 'object') return;
                                if (Array.isArray(n)) { n.forEach(walk); return; }
                                if (typeof n.username === 'string' && n.username.trim().toLowerCase() === TARGET) {
                                    const sc = scoreUser(n);
                                    if (sc > bestScore) { bestScore = sc; best = n; }
                                }
                                Object.keys(n).forEach(function(k) { walk(n[k]); });
                            }
                            walk(root);
                            if (best && bestScore >= 4 && window.ProfileBridge) {
                                const pk = (best.pk && /^\d+$/.test(String(best.pk))) ? String(best.pk) : ((best.id && /^\d+$/.test(String(best.id))) ? String(best.id) : '');
                                const followers = Number(best.follower_count !== undefined ? best.follower_count : (best.edge_followed_by ? best.edge_followed_by.count : 0)) || 0;
                                const following = Number(best.following_count !== undefined ? best.following_count : (best.edge_follow ? best.edge_follow.count : 0)) || 0;
                                const mediaCount = Number(best.media_count !== undefined ? best.media_count : (best.edge_owner_to_timeline_media ? best.edge_owner_to_timeline_media.count : 0)) || 0;
                                const picUrl = (best.hd_profile_pic_url_info && best.hd_profile_pic_url_info.url) || best.profile_pic_url_hd || best.profile_pic_url || '';
                                window.ProfileBridge.onProfilePayload(JSON.stringify({
                                    status: 'ok',
                                    source: source,
                                    username: TARGET,
                                    pk: pk,
                                    fullName: best.full_name || '',
                                    biography: best.biography || '',
                                    isPrivate: !!best.is_private,
                                    followers: followers,
                                    following: following,
                                    mediaCount: mediaCount,
                                    picUrl: picUrl,
                                    latestStorySec: Number(best.latest_reel_media || 0) || 0
                                }));
                            }
                        } catch (_) {}
                    }

                    window.__igInspectProfileJson = inspectJsonText;

                    const origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            const url = (arguments[0] && typeof arguments[0] === 'string') ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                            return origFetch.apply(this, arguments).then(function(resp) {
                                try {
                                    if (url.indexOf('graphql') !== -1 || url.indexOf('web_profile_info') !== -1 || url.indexOf('users/') !== -1) {
                                        resp.clone().text().then(function(t) { inspectJsonText(t, 'fetch_json'); }).catch(function(){});
                                    }
                                } catch (_) {}
                                return resp;
                            });
                        };
                    }

                    const origOpen = XMLHttpRequest.prototype.open;
                    const origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__igProfUrl = String(url || '');
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try {
                                const u = this.__igProfUrl || '';
                                if (u.indexOf('graphql') !== -1 || u.indexOf('web_profile_info') !== -1 || u.indexOf('users/') !== -1) {
                                    inspectJsonText(this.responseText || '', 'xhr_json');
                                }
                            } catch (_) {}
                        });
                        return origSend.apply(this, arguments);
                    };
                })();
            """.trimIndent()
        }

        private fun buildProfileExtractJs(targetUsername: String): String {
            val safeTarget = targetUsername.replace("'", "").lowercase()
            return """
                (function() {
                    try {
                        const href = window.location.href || '';
                        if (href.indexOf('/accounts/login') !== -1) {
                            if (window.ProfileBridge) window.ProfileBridge.onProfilePayload(JSON.stringify({ status: 'login_wall' }));
                            return;
                        }
                        if (typeof window.__igInspectProfileJson === 'function') {
                            document.querySelectorAll('script[type="application/json"]').forEach(function(s) {
                                const t = s.textContent || '';
                                if (t.toLowerCase().indexOf('$safeTarget') !== -1) {
                                    window.__igInspectProfileJson(t, 'ssr_script');
                                }
                            });
                        }
                    } catch (_) {}
                })();
            """.trimIndent()
        }
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

        private val COMMENTS_NETWORK_HOOK_JS = """
            (function() {
                if (window.__igCommentsHookInstalled) return;
                window.__igCommentsHookInstalled = true;

                function pushNode(out, seen, node, fallbackParentId) {
                    if (!node || typeof node !== 'object') return;
                    const id = String(node.pk || node.id || '');
                    const txt = typeof node.text === 'string' ? node.text : '';
                    const user = node.user || node.owner || null;
                    const uname = user && user.username ? String(user.username) : '';
                    if (!id || (!txt && !uname)) return;
                    if (!seen.has(id)) {
                        seen.add(id);
                        const parentId = node.parent_comment_id
                            ? String(node.parent_comment_id)
                            : (fallbackParentId || '');
                        const likes = Number(
                            node.comment_like_count !== undefined
                                ? node.comment_like_count
                                : (node.edge_liked_by && node.edge_liked_by.count !== undefined ? node.edge_liked_by.count : 0)
                        ) || 0;
                        const replyCnt = Number(
                            node.child_comment_count !== undefined
                                ? node.child_comment_count
                                : (node.edge_threaded_comments && node.edge_threaded_comments.count !== undefined ? node.edge_threaded_comments.count : 0)
                        ) || 0;
                        const created = Number(node.created_at_utc || node.created_at || 0) || 0;
                        out.push({
                            id: id,
                            parentCommentId: parentId,
                            authorUsername: uname,
                            authorId: user ? String(user.pk || user.id || '') : '',
                            authorFullName: user && user.full_name ? String(user.full_name) : '',
                            authorAvatarUrl: user && user.profile_pic_url ? String(user.profile_pic_url) : '',
                            isVerified: !!(user && user.is_verified),
                            text: txt,
                            likeCount: likes,
                            replyCount: replyCnt,
                            createdAt: created,
                            hasGifOrMedia: !!(node.giphy_media_info || node.gif_media_id)
                        });
                    }
                    // Extract inline preview_child_comments / child_comments / edge_threaded_comments
                    const previewChildren = node.preview_child_comments || node.child_comments;
                    if (Array.isArray(previewChildren)) {
                        previewChildren.forEach(function(ch) { pushNode(out, seen, ch, id); });
                    }
                    if (node.edge_threaded_comments && Array.isArray(node.edge_threaded_comments.edges)) {
                        node.edge_threaded_comments.edges.forEach(function(e) {
                            if (e && e.node) pushNode(out, seen, e.node, id);
                        });
                    }
                }

                function parseCommentsJson(text, urlHint) {
                    if (!text || text.length < 30) return;
                    if (text.indexOf('comment_like_count') === -1 &&
                        text.indexOf('child_comment_count') === -1 &&
                        text.indexOf('child_comments') === -1 &&
                        text.indexOf('edge_media_to_parent_comment') === -1 &&
                        text.indexOf('edge_threaded_comments') === -1 &&
                        text.indexOf('comments__connection') === -1) return;
                    try {
                        const root = JSON.parse(text);
                        const out = [];
                        const seen = new Set();
                        let urlParent = '';
                        const pm = String(urlHint || '').match(/comments\/(\d+)\/child_comments/);
                        if (pm) urlParent = pm[1];

                        function walk(n, inheritedParent) {
                            if (!n || typeof n !== 'object') return;
                            if (Array.isArray(n)) { n.forEach(function(x) { walk(x, inheritedParent); }); return; }

                            if (Array.isArray(n.comments)) {
                                n.comments.forEach(function(c) { pushNode(out, seen, c, inheritedParent); });
                            }
                            if (Array.isArray(n.child_comments)) {
                                const p = n.parent_comment && (n.parent_comment.pk || n.parent_comment.id)
                                    ? String(n.parent_comment.pk || n.parent_comment.id)
                                    : (n.pk || n.id ? String(n.pk || n.id) : (urlParent || inheritedParent));
                                n.child_comments.forEach(function(c) { pushNode(out, seen, c, p); });
                            }
                            const connKeys = [
                                'xdt_api__v1__media__media_id__comments__connection',
                                'xdt_api__v1__media__media_id__comments__parent_comment_id__child_comments__connection',
                                'edge_media_to_parent_comment',
                                'edge_media_to_comment'
                            ];
                            connKeys.forEach(function(k) {
                                const c = n[k];
                                if (c && Array.isArray(c.edges)) {
                                    c.edges.forEach(function(e) {
                                        if (e && e.node) pushNode(out, seen, e.node, inheritedParent);
                                    });
                                }
                            });
                            Object.keys(n).forEach(function(k) { walk(n[k], inheritedParent); });
                        }
                        walk(root, urlParent);
                        if (out.length > 0 && window.CommentsBridge) {
                            window.CommentsBridge.onCommentBatch(JSON.stringify({ status: 'ok', items: out }));
                        }
                    } catch (_) {}
                }

                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        const url = (arguments[0] && typeof arguments[0] === 'string') ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                if (url.indexOf('graphql') !== -1 || url.indexOf('comments') !== -1) {
                                    resp.clone().text().then(function(t) { parseCommentsJson(t, url); }).catch(function(){});
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
                            if (u.indexOf('graphql') !== -1 || u.indexOf('comments') !== -1) {
                                parseCommentsJson(this.responseText || '', u);
                            }
                        } catch (_) {}
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """.trimIndent()

        private val COMMENTS_SCROLL_AND_EXPAND_JS = """
            (function() {
                try {
                    const href = window.location.href || '';
                    if (href.indexOf('/accounts/login') !== -1) {
                        if (window.CommentsBridge) window.CommentsBridge.onCommentBatch(JSON.stringify({ status: 'login_wall', items: [] }));
                        return;
                    }
                    // Parse embedded SSR JSON scripts once
                    if (!window.__igCommentsSsrScanned) {
                        window.__igCommentsSsrScanned = true;
                        document.querySelectorAll('script[type="application/json"]').forEach(function(s) {
                            const txt = s.textContent || '';
                            if (txt.indexOf('comments__connection') !== -1 || txt.indexOf('edge_media_to_parent_comment') !== -1 || txt.indexOf('comment_like_count') !== -1) {
                                try {
                                    const root = JSON.parse(txt);
                                    const out = [];
                                    const seen = new Set();
                                    function addNode(node, parentId) {
                                        if (!node || typeof node !== 'object') return;
                                        const id = String(node.pk || node.id || '');
                                        const t = typeof node.text === 'string' ? node.text : '';
                                        const u = node.user || node.owner || null;
                                        const un = u && u.username ? String(u.username) : '';
                                        if (!id || (!t && !un) || seen.has(id)) return;
                                        seen.add(id);
                                        out.push({
                                            id: id,
                                            parentCommentId: node.parent_comment_id ? String(node.parent_comment_id) : (parentId || ''),
                                            authorUsername: un,
                                            authorId: u ? String(u.pk || u.id || '') : '',
                                            authorFullName: u && u.full_name ? String(u.full_name) : '',
                                            authorAvatarUrl: u && u.profile_pic_url ? String(u.profile_pic_url) : '',
                                            isVerified: !!(u && u.is_verified),
                                            text: t,
                                            likeCount: Number(node.comment_like_count || (node.edge_liked_by && node.edge_liked_by.count) || 0) || 0,
                                            replyCount: Number(node.child_comment_count || (node.edge_threaded_comments && node.edge_threaded_comments.count) || 0) || 0,
                                            createdAt: Number(node.created_at_utc || node.created_at || 0) || 0,
                                            hasGifOrMedia: !!(node.giphy_media_info || node.gif_media_id)
                                        });
                                        (node.preview_child_comments || node.child_comments || []).forEach(function(ch) { addNode(ch, id); });
                                        if (node.edge_threaded_comments && Array.isArray(node.edge_threaded_comments.edges)) {
                                            node.edge_threaded_comments.edges.forEach(function(e) { if (e && e.node) addNode(e.node, id); });
                                        }
                                    }
                                    function walk(n) {
                                        if (!n || typeof n !== 'object') return;
                                        if (Array.isArray(n)) { n.forEach(walk); return; }
                                        ['xdt_api__v1__media__media_id__comments__connection', 'edge_media_to_parent_comment', 'edge_media_to_comment'].forEach(function(k) {
                                            if (n[k] && Array.isArray(n[k].edges)) {
                                                n[k].edges.forEach(function(e) { if (e && e.node) addNode(e.node, ''); });
                                            }
                                        });
                                        Object.keys(n).forEach(function(k) { walk(n[k]); });
                                    }
                                    walk(root);
                                    if (out.length > 0 && window.CommentsBridge) {
                                        window.CommentsBridge.onCommentBatch(JSON.stringify({ status: 'ok', items: out }));
                                    }
                                } catch (_) {}
                            }
                        });
                    }

                    // Click "Load more comments" or "View replies" buttons (up to 4 clicks per tick)
                    let clicks = 0;
                    const btns = document.querySelectorAll('button, div[role="button"], span[role="button"]');
                    for (let i = 0; i < btns.length && clicks < 4; i++) {
                        const b = btns[i];
                        if (b.__igClicked) continue;
                        const label = ((b.innerText || '') + ' ' + (b.getAttribute('aria-label') || '')).trim().toLowerCase();
                        if (label.indexOf('view replies') !== -1 ||
                            label.indexOf('view all') !== -1 && label.indexOf('repl') !== -1 ||
                            label.indexOf('load more comments') !== -1 ||
                            label.indexOf('cavabları göstər') !== -1 ||
                            label.indexOf('daha çox şərh') !== -1) {
                            b.__igClicked = true;
                            b.click();
                            clicks++;
                        }
                    }

                    // Scroll any scrollable comment containers + main window
                    document.querySelectorAll('ul, div').forEach(function(el) {
                        if (el.scrollHeight > el.clientHeight + 60 && el.clientHeight > 180) {
                            el.scrollTop = el.scrollHeight;
                        }
                    });
                    window.scrollTo(0, document.body.scrollHeight);
                } catch (_) {}
            })();
        """.trimIndent()
    }
}
