package com.instalogger.app.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.instalogger.app.engine.media.CarouselSlideHint
import com.instalogger.app.engine.media.ExtractedPostMetadata
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.cleanDisplayText
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class InstagramProfileSnapshot(
    val username: String,
    val instagramId: String? = null,
    val fullName: String = "",
    val biography: String = "",
    val isPrivate: Boolean = false,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val mediaCount: Long = 0,
    val profilePicUrl: String? = null,
    val avatarHash: String? = null,
    val profileHash: String? = null,
    val latestStoryAt: Long = 0L
)

data class InstagramUserSummary(
    val id: String,
    val username: String,
    val fullName: String = "",
    val profilePicUrl: String? = null,
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false
)

data class InstagramHighlight(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val mediaCount: Int = 0
)

data class InstagramHighlightItem(
    val id: String,
    val highlightId: String,
    val mediaType: String, // "image" or "video"
    val mediaUrl: String,
    val takenAt: Long = 0L
)

data class InstagramPostItem(
    val id: String,
    val shortcode: String,
    val mediaType: String, // "image", "video", or "carousel"
    val displayUrl: String,
    val videoUrl: String? = null,
    val caption: String = "",
    val timestamp: Long = 0L,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val slides: List<CarouselSlideHint> = emptyList()
)

data class PostMediaDetails(
    val slides: List<CarouselSlideHint>,
    val metadata: ExtractedPostMetadata? = null
)

sealed class PostFetchResult {
    data class Success(val posts: List<InstagramPostItem>) : PostFetchResult()
    object RequiresSession : PostFetchResult()
    object RateLimited : PostFetchResult()
    data class Error(val message: String) : PostFetchResult()
}

data class InstagramCommentItem(
    val id: String,
    val parentCommentId: String? = null,
    val authorUsername: String,
    val authorId: String? = null,
    val authorFullName: String = "",
    val authorAvatarUrl: String? = null,
    val isAuthorVerified: Boolean = false,
    val text: String,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val hasGifOrMedia: Boolean = false
)

data class CommentFetchResult(
    val comments: List<InstagramCommentItem>,
    val mediaId: String? = null,
    val collectionStatus: String = "complete", // "complete" or "partial"
    val source: String = "none",
    val rateLimited: Boolean = false,
    val errorMessage: String? = null
)

class InstagramEngine(context: Context? = null) {

    private val headlessScraper: HeadlessInstagramScraper? = context?.let { HeadlessInstagramScraper(it) }
    private val guestCookieLock = Any()
    @Volatile
    private var cachedGuestCookies: String? = null
    @Volatile
    private var guestCookiesFetchedAt: Long = 0L
    @Volatile
    private var reelsMediaApiCooldownUntilMs: Long = 0L
    @Volatile
    private var anonymousSsrRleUntilMs: Long = 0L
    @Volatile
    private var mobileApiBlockedUntilMs: Long = 0L
    @Volatile
    private var highlightsGraphqlDeadUntilMs: Long = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val clientWithRedirects = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = Gson()

    private fun extractCsrfToken(cookieString: String?): String? {
        if (cookieString.isNullOrBlank()) return null
        val matcher = Pattern.compile("(?:^|;\\s*)csrftoken=([^;\\s]+)").matcher(cookieString)
        return if (matcher.find()) matcher.group(1) else null
    }

    internal fun extractDsUserId(cookieString: String?): String? {
        if (cookieString.isNullOrBlank()) return null
        val matcher = Pattern.compile("(?:^|;\\s*)ds_user_id=(\\d+)").matcher(cookieString)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun applyAuthenticatedApiHeaders(
        builder: Request.Builder,
        cookieString: String,
        referer: String,
        userAgent: String = DESKTOP_CHROME_UA
    ): Request.Builder {
        builder
            .header("User-Agent", userAgent)
            .header("X-IG-App-ID", APP_ID)
            .header("X-ASBD-ID", ASBD_ID)
            .header("X-IG-WWW-Claim", "0")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Cookie", cookieString)
        extractCsrfToken(cookieString)?.let { builder.header("X-CSRFToken", it) }
        return builder
    }

    private fun getOrFetchGuestCookies(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedGuestCookies
        if (!cached.isNullOrBlank() && (now - guestCookiesFetchedAt) < GUEST_COOKIE_TTL_MS) {
            return cached
        }
        synchronized(guestCookieLock) {
            val rechecked = cachedGuestCookies
            if (!rechecked.isNullOrBlank() && (System.currentTimeMillis() - guestCookiesFetchedAt) < GUEST_COOKIE_TTL_MS) {
                return rechecked
            }
            return try {
                val req = Request.Builder()
                    .url("https://www.instagram.com/")
                    .header("User-Agent", DESKTOP_CHROME_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .build()

                clientWithRedirects.newCall(req).execute().use { response ->
                    val cookieMap = linkedMapOf<String, String>()
                    for (setCookie in response.headers("Set-Cookie")) {
                        val pair = setCookie.substringBefore(";").trim()
                        val eqIdx = pair.indexOf('=')
                        if (eqIdx > 0) {
                            val name = pair.substring(0, eqIdx).trim()
                            val value = pair.substring(eqIdx + 1).trim()
                            if (value.isNotBlank() && value != "\"\"") {
                                cookieMap[name] = value
                            }
                        }
                    }
                    val html = response.body?.string().orEmpty()
                    if (!cookieMap.containsKey("csrftoken")) {
                        val csrfMatcher = Pattern.compile("\"csrf_token\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                        if (csrfMatcher.find()) {
                            csrfMatcher.group(1)?.takeIf { it.isNotBlank() }?.let {
                                cookieMap["csrftoken"] = it
                            }
                        }
                    }
                    if (!cookieMap.containsKey("ig_did")) {
                        val didMatcher = Pattern.compile("\"device_id\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                        if (didMatcher.find()) {
                            didMatcher.group(1)?.takeIf { it.isNotBlank() }?.let {
                                cookieMap["ig_did"] = it
                            }
                        }
                    }
                    val joined = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    if (joined.isNotBlank()) {
                        cachedGuestCookies = joined
                        guestCookiesFetchedAt = System.currentTimeMillis()
                        joined
                    } else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Guest cookie handshake failed: ${e.message}")
                null
            }
        }
    }

    fun fetchProfile(username: String, cookieString: String?): Result<InstagramProfileSnapshot> {
        val cleanUsername = username.trim().trimStart('@')
        val hasSession = !cookieString.isNullOrBlank()
        val nowMs = System.currentTimeMillis()

        // 1. First attempt: ZERO-LOGIN Anonymous Desktop SSR HTML (NEVER sends session cookie,
        AppLogger.i(TAG, "fetchProfile(@$cleanUsername) start (hasSession=$hasSession)")
        var bestSnapshot: InstagramProfileSnapshot? = null
        val skipAnonSsr = hasSession && nowMs < anonymousSsrRleUntilMs
        if (!skipAnonSsr) {
            try {
                val htmlSnapshot = fetchViaHtml(cleanUsername)
                if (htmlSnapshot != null) {
                    anonymousSsrRleUntilMs = 0L
                    bestSnapshot = htmlSnapshot
                    AppLogger.i(
                        TAG,
                        "fetchProfile(@$cleanUsername) Tier1-ZeroLoginSSR OK: pk=${htmlSnapshot.instagramId} followers=${htmlSnapshot.followersCount} following=${htmlSnapshot.followingCount} posts=${htmlSnapshot.mediaCount} story=${htmlSnapshot.latestStoryAt > 0L} pic=${!htmlSnapshot.profilePicUrl.isNullOrBlank()}"
                    )
                    if (!htmlSnapshot.instagramId.isNullOrBlank() &&
                        (htmlSnapshot.followersCount > 0L || htmlSnapshot.followingCount > 0L || htmlSnapshot.fullName.isNotBlank())
                    ) {
                        return Result.success(htmlSnapshot)
                    }
                } else {
                    AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier1-ZeroLoginSSR returned null")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier1-ZeroLoginSSR error: ${e.message}")
            }
        } else {
            AppLogger.d(TAG, "fetchProfile(@$cleanUsername) skipping Tier1-ZeroLoginSSR (is_from_rle cooldown active)")
        }

        if (hasSession && nowMs >= mobileApiBlockedUntilMs) {
            try {
                val mobileSnap = fetchViaMobileUsernameInfo(cleanUsername, cookieString!!)
                if (mobileSnap != null) {
                    mobileApiBlockedUntilMs = 0L
                    AppLogger.i(TAG, "fetchProfile(@$cleanUsername) Tier2-MobileApi OK: pk=${mobileSnap.instagramId} followers=${mobileSnap.followersCount} posts=${mobileSnap.mediaCount}")
                    return Result.success(mobileSnap)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier2-MobileApi error: ${e.message}")
            }
        }

        if (hasSession && headlessScraper != null) {
            try {
                val wvSnap = runBlocking { headlessScraper.scrapeProfile(cleanUsername, cookieString) }
                if (wvSnap != null) {
                    AppLogger.i(TAG, "fetchProfile(@$cleanUsername) Tier3-WebView OK: pk=${wvSnap.instagramId} followers=${wvSnap.followersCount} posts=${wvSnap.mediaCount}")
                    return Result.success(wvSnap)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier3-WebView error: ${e.message}")
            }
        }

        // 4. Fallback: Web Profile Info API (guest + auth) & GraphQL Hash
        try {
            val guestApiSnapshot = fetchViaWebApi(cleanUsername, cookieString = null)
            if (guestApiSnapshot != null) {
                AppLogger.i(TAG, "fetchProfile(@$cleanUsername) Tier4-GuestWebApi OK: pk=${guestApiSnapshot.instagramId} followers=${guestApiSnapshot.followersCount}")
                return Result.success(guestApiSnapshot)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier4-GuestWebApi error: ${e.message}")
        }

        if (hasSession) {
            try {
                val authApiSnapshot = fetchViaWebApi(cleanUsername, cookieString = cookieString)
                if (authApiSnapshot != null) {
                    AppLogger.i(TAG, "fetchProfile(@$cleanUsername) Tier4-AuthWebApi OK: pk=${authApiSnapshot.instagramId} followers=${authApiSnapshot.followersCount}")
                    return Result.success(authApiSnapshot)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier4-AuthWebApi error: ${e.message}")
            }
        }

        try {
            val hashSnapshot = fetchViaGraphQLHash(cleanUsername, cookieString)
            if (hashSnapshot != null) {
                AppLogger.i(TAG, "fetchProfile(@$cleanUsername) Tier5-GraphQL OK: pk=${hashSnapshot.instagramId}")
                return Result.success(hashSnapshot)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "fetchProfile(@$cleanUsername) Tier5-GraphQL error: ${e.message}")
        }

        if (bestSnapshot != null) {
            return Result.success(bestSnapshot)
        }

        AppLogger.e(TAG, "fetchProfile(@$cleanUsername) FAILED: all tiers exhausted")
        return Result.failure(Exception("Profil məlumatları alına bilmədi. İnternet əlaqəsini və ya Instagram limitini yoxlayın."))
    }

    private fun fetchViaMobileUsernameInfo(username: String, cookieString: String): InstagramProfileSnapshot? {
        val url = "https://i.instagram.com/api/v1/users/$username/usernameinfo/"
        val reqBuilder = applyAuthenticatedApiHeaders(
            builder = Request.Builder().url(url),
            cookieString = cookieString,
            referer = "https://www.instagram.com/$username/",
            userAgent = IG_ANDROID_APP_UA
        )
        clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 || resp.code == 401) {
                mobileApiBlockedUntilMs = System.currentTimeMillis() + 30L * 60L * 1000L
                AppLogger.w(TAG, "fetchViaMobileUsernameInfo(@$username) HTTP ${resp.code}; cooling down Mobile API for 30m")
                return null
            }
            if (!resp.isSuccessful || !body.trimStart().startsWith("{")) {
                AppLogger.w(TAG, "fetchViaMobileUsernameInfo(@$username) HTTP ${resp.code} body=${body.take(100)}")
                return null
            }
            val root = gson.fromJson(body, JsonObject::class.java) ?: return null
            val user = root.getAsJsonObject("user") ?: return null
            val returnedUsername = user.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim()
            if (!returnedUsername.isNullOrBlank() && !returnedUsername.equals(username, ignoreCase = true)) {
                AppLogger.w(TAG, "usernameinfo mismatch: requested=@$username got=@$returnedUsername")
                return null
            }
            val pk = user.get("pk")?.takeIf { !it.isJsonNull }?.asString
                ?: user.get("id")?.takeIf { !it.isJsonNull }?.asString
            val fullName = user.get("full_name")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
            val bio = user.get("biography")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
            val isPrivate = user.get("is_private")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val followers = user.get("follower_count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val following = user.get("following_count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val mediaCount = user.get("media_count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val picUrl = user.getAsJsonObject("hd_profile_pic_url_info")?.get("url")?.takeIf { !it.isJsonNull }?.asString
                ?: user.get("profile_pic_url")?.takeIf { !it.isJsonNull }?.asString
            val latestReelSec = user.get("latest_reel_media")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            return InstagramProfileSnapshot(
                username = username,
                instagramId = pk,
                fullName = fullName,
                biography = bio,
                isPrivate = isPrivate,
                followersCount = followers,
                followingCount = following,
                mediaCount = mediaCount,
                profilePicUrl = picUrl,
                latestStoryAt = if (latestReelSec > 0L) latestReelSec * 1000L else 0L
            )
        }
    }

    internal fun parseProfileFromSsrHtml(html: String, targetUsername: String): InstagramProfileSnapshot? {
        val cleanTarget = targetUsername.trim().trimStart('@').lowercase()
        var matchedUser: JsonObject? = null
        var bestScore = -1

        fun scoreUserObj(obj: JsonObject): Int {
            var score = 0
            if (obj.has("pk")) score += 4
            if (obj.has("follower_count") || obj.has("edge_followed_by")) score += 4
            if (obj.has("following_count") || obj.has("edge_follow")) score += 2
            if (obj.has("full_name")) score += 2
            if (obj.has("biography")) score += 2
            if (obj.has("profile_pic_url_hd") || obj.has("hd_profile_pic_url_info") || obj.has("profile_pic_url")) score += 2
            if (obj.has("latest_reel_media")) score += 2
            if (obj.has("media_count") || obj.has("edge_owner_to_timeline_media")) score += 1
            return score
        }

        fun walk(elem: JsonElement?) {
            if (elem == null) return
            when {
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    val uName = obj.get("username")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()?.lowercase()
                    if (uName == cleanTarget) {
                        val s = scoreUserObj(obj)
                        if (s > bestScore) {
                            bestScore = s
                            matchedUser = obj
                        }
                    }
                    for ((_, v) in obj.entrySet()) {
                        walk(v)
                    }
                }
                elem.isJsonArray -> {
                    for (child in elem.asJsonArray) {
                        walk(child)
                    }
                }
            }
        }

        val scriptPattern = Pattern.compile("<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            if (!raw.contains(cleanTarget, ignoreCase = true)) continue
            try {
                walk(gson.fromJson(raw, JsonElement::class.java))
            } catch (_: Exception) {
            }
        }

        val user = matchedUser ?: return null
        val pk = user.get("pk")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it.all(Char::isDigit) }
        val fallbackId = user.get("id")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it.all(Char::isDigit) }
        val resolvedId = pk ?: fallbackId

        val fullName = user.get("full_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.cleanDisplayText().orEmpty()
        val bio = user.get("biography")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.cleanDisplayText().orEmpty()
        val isPrivate = user.get("is_private")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
        val followers = user.get("follower_count")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong
            ?: user.getAsJsonObject("edge_followed_by")?.get("count")?.takeIf { !it.isJsonNull }?.asLong
            ?: 0L
        val following = user.get("following_count")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong
            ?: user.getAsJsonObject("edge_follow")?.get("count")?.takeIf { !it.isJsonNull }?.asLong
            ?: 0L
        val mediaCount = user.get("media_count")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong
            ?: user.getAsJsonObject("edge_owner_to_timeline_media")?.get("count")?.takeIf { !it.isJsonNull }?.asLong
            ?: 0L
        val picUrl = user.getAsJsonObject("hd_profile_pic_url_info")?.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: user.get("profile_pic_url_hd")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            ?: user.get("profile_pic_url")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        val latestReelSec = user.get("latest_reel_media")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong ?: 0L

        return InstagramProfileSnapshot(
            username = targetUsername,
            instagramId = resolvedId,
            fullName = fullName,
            biography = bio,
            isPrivate = isPrivate,
            followersCount = followers,
            followingCount = following,
            mediaCount = mediaCount,
            profilePicUrl = picUrl,
            latestStoryAt = if (latestReelSec > 0L) latestReelSec * 1000L else 0L
        )
    }

    private fun fetchViaHtml(username: String): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/$username/"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")

        // Strictly zero-login: NEVER attach logged-in user cookies to HTML profile scraping
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code != 200) {
                val loc = response.header("Location") ?: "-"
                if (response.code == 302 && (loc.contains("is_from_rle") || loc.contains("/accounts/login"))) {
                    anonymousSsrRleUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                }
                AppLogger.w(TAG, "fetchViaHtml(@$username) HTTP ${response.code} location=$loc")
                return null
            }
            val html = response.body?.string() ?: return null

            // Prefer exact structured SSR user JSON matched strictly by target username
            val structured = parseProfileFromSsrHtml(html, username)

            var followers = structured?.followersCount ?: 0L
            var following = structured?.followingCount ?: 0L
            var posts = structured?.mediaCount ?: 0L
            var id: String? = structured?.instagramId
            var fullName = structured?.fullName.orEmpty()
            var bio = structured?.biography.orEmpty()
            var isPrivate = structured?.isPrivate ?: false
            var picUrl: String? = structured?.profilePicUrl
            var latestStoryAt = structured?.latestStoryAt ?: 0L

            if (structured == null) {
                // 1. Extract follower_count
                val followerMatcher = Pattern.compile("\"follower_count\":\\s*(\\d+)").matcher(html)
                if (followerMatcher.find()) {
                    followers = followerMatcher.group(1)?.toLongOrNull() ?: 0L
                }

                // 2. Extract following_count
                val followingMatcher = Pattern.compile("\"following_count\":\\s*(\\d+)").matcher(html)
                if (followingMatcher.find()) {
                    following = followingMatcher.group(1)?.toLongOrNull() ?: 0L
                }

                // 3. Extract pk / ID (multi-pattern fallback)
                val pkPatterns = listOf(
                    Pattern.compile("\"pk\":\\s*\"?(\\d+)\"?"),
                    Pattern.compile("\"profile_id\":\\s*\"?(\\d+)\"?"),
                    Pattern.compile("<meta\\s+property=\"instapp:owner_user_id\"\\s+content=\"(\\d+)\""),
                    Pattern.compile("<meta\\s+property=\"al:ios:url\"\\s+content=\"instagram://user\\?id=(\\d+)\""),
                    Pattern.compile("instagram://user\\?id=(\\d+)"),
                    Pattern.compile("\"owner\":\\s*\\{\\s*\"id\":\\s*\"?(\\d+)\"?")
                )
                for (p in pkPatterns) {
                    val m = p.matcher(html)
                    if (m.find()) {
                        id = m.group(1)
                        if (!id.isNullOrBlank()) break
                    }
                }

                // 4. Extract is_private
                val privateMatcher = Pattern.compile("\"is_private\":\\s*(true|false)").matcher(html)
                if (privateMatcher.find()) {
                    isPrivate = privateMatcher.group(1).equals("true", ignoreCase = true)
                }

                // 5. Extract full_name
                val nameMatcher = Pattern.compile("\"full_name\":\\s*\"([^\"]*)\"").matcher(html)
                if (nameMatcher.find()) {
                    fullName = unescapeJson(nameMatcher.group(1) ?: "")
                }

                // 6. Extract biography
                val bioMatcher = Pattern.compile("\"biography\":\\s*\"([^\"]*)\"").matcher(html)
                if (bioMatcher.find()) {
                    bio = unescapeJson(bioMatcher.group(1) ?: "")
                }

                // 7. Extract profile_pic_url
                val picMatcher = Pattern.compile("\"profile_pic_url_hd\":\"([^\"]+)\"|\"profile_pic_url\":\"([^\"]+)\"").matcher(html)
                if (picMatcher.find()) {
                    val rawUrl = picMatcher.group(1) ?: picMatcher.group(2)
                    if (rawUrl != null) {
                        picUrl = unescapeJson(rawUrl)
                    }
                }
            }

            // 8. Extract media / post count if still missing
            if (posts == 0L) {
                val mediaPatterns = listOf(
                    Pattern.compile("\"edge_owner_to_timeline_media\":\\s*\\{\\s*\"count\":\\s*(\\d+)"),
                    Pattern.compile("\"media_count\":\\s*(\\d+)"),
                    Pattern.compile("\"posts_count\":\\s*(\\d+)"),
                    Pattern.compile("\"edge_felix_video_timeline\":\\s*\\{\\s*\"count\":\\s*(\\d+)")
                )
                for (p in mediaPatterns) {
                    val m = p.matcher(html)
                    if (m.find()) {
                        val countVal = m.group(1)?.toLongOrNull() ?: 0L
                        if (countVal > 0L) {
                            posts = countVal
                            break
                        }
                    }
                }
            }

            // 9. Fallback og:description if posts or followers are missing
            if (posts == 0L || (followers == 0L && following == 0L)) {
                val descPatterns = listOf(
                    Pattern.compile("<meta\\s+(?:name|property)=\"(?:description|og:description)\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\"description\":\\s*\"([^\"]+)\"")
                )
                for (dp in descPatterns) {
                    val descMatcher = dp.matcher(html)
                    if (descMatcher.find()) {
                        val desc = descMatcher.group(1) ?: ""
                        val countPattern = Pattern.compile("([\\d,.]+[KkMmBb]?)\\s+(?:Followers|Takipçi|Подписчик|İzləyici)[^,;]*[,;]\\s*([\\d,.]+[KkMmBb]?)\\s+(?:Following|Takip|Подписк|İzlənilən)[^,;]*[,;]\\s*([\\d,.]+[KkMmBb]?)\\s+(?:Posts|Gönderi|Публикац|Paylaşım)", Pattern.CASE_INSENSITIVE)
                        val countMatcher = countPattern.matcher(desc)
                        if (countMatcher.find()) {
                            if (followers == 0L) followers = parseFormattedNumber(countMatcher.group(1) ?: "0")
                            if (following == 0L) following = parseFormattedNumber(countMatcher.group(2) ?: "0")
                            if (posts == 0L) posts = parseFormattedNumber(countMatcher.group(3) ?: "0")
                            break
                        } else {
                            val genericPattern = Pattern.compile("([\\d,.]+[KkMmBb]?)\\s+[^,;]+[,;]\\s*([\\d,.]+[KkMmBb]?)\\s+[^,;]+[,;]\\s*([\\d,.]+[KkMmBb]?)\\s+", Pattern.CASE_INSENSITIVE)
                            val gm = genericPattern.matcher(desc)
                            if (gm.find()) {
                                if (followers == 0L) followers = parseFormattedNumber(gm.group(1) ?: "0")
                                if (following == 0L) following = parseFormattedNumber(gm.group(2) ?: "0")
                                if (posts == 0L) posts = parseFormattedNumber(gm.group(3) ?: "0")
                                break
                            }
                        }
                    }
                }
            }

            if (fullName.isBlank()) {
                val titleMatcher = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"").matcher(html)
                if (titleMatcher.find()) {
                    val title = titleMatcher.group(1) ?: ""
                    fullName = title.substringBefore("(").trim()
                }
            }

            // 10. Extract active 24h story signal if structured payload omitted it
            if (latestStoryAt == 0L && structured == null) {
                val storyMatcher = Pattern.compile("\"latest_reel_media\"\\s*:\\s*(\\d+)").matcher(html)
                if (storyMatcher.find()) {
                    val sec = storyMatcher.group(1)?.toLongOrNull() ?: 0L
                    if (sec > 0L) {
                        latestStoryAt = sec * 1000L
                    }
                }
            }

            return InstagramProfileSnapshot(
                username = username,
                instagramId = id,
                fullName = fullName,
                biography = bio,
                isPrivate = isPrivate,
                followersCount = followers,
                followingCount = following,
                mediaCount = posts,
                profilePicUrl = picUrl,
                latestStoryAt = latestStoryAt
            )
        }
    }

    private fun fetchViaWebApi(username: String, cookieString: String?): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
        val effectiveCookie = if (!cookieString.isNullOrBlank()) cookieString else getOrFetchGuestCookies()
        val requestBuilder = Request.Builder().url(url)

        if (!effectiveCookie.isNullOrBlank()) {
            applyAuthenticatedApiHeaders(
                builder = requestBuilder,
                cookieString = effectiveCookie,
                referer = "https://www.instagram.com/$username/"
            )
        } else {
            requestBuilder
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("X-IG-App-ID", APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://www.instagram.com/$username/")
                .header("Accept", "*/*")
        }

        clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                AppLogger.d(TAG, "fetchViaWebApi(@$username, auth=${!cookieString.isNullOrBlank()}) HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            if (!body.startsWith("{")) {
                AppLogger.d(TAG, "fetchViaWebApi(@$username, auth=${!cookieString.isNullOrBlank()}) non-JSON (len=${body.length})")
                return null
            }

            val root = gson.fromJson(body, JsonObject::class.java) ?: return null
            val data = root.getAsJsonObject("data") ?: return null
            val user = data.getAsJsonObject("user") ?: return null

            // Strict identity guard: reject any response whose username does not match requested target
            val returnedUsername = user.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim()
            if (!returnedUsername.isNullOrBlank() && !returnedUsername.equals(username, ignoreCase = true)) {
                Log.w(TAG, "web_profile_info username mismatch: requested=@$username got=@$returnedUsername; discarding")
                return null
            }

            val id = user.get("id")?.takeIf { !it.isJsonNull }?.asString
            val fullName = user.get("full_name")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText() ?: ""
            val bio = user.get("biography")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText() ?: ""
            val isPrivate = user.get("is_private")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val followers = user.getAsJsonObject("edge_followed_by")?.get("count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val following = user.getAsJsonObject("edge_follow")?.get("count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val mediaCount = user.getAsJsonObject("edge_owner_to_timeline_media")?.get("count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val picUrl = user.get("profile_pic_url_hd")?.takeIf { !it.isJsonNull }?.asString
                ?: user.get("profile_pic_url")?.takeIf { !it.isJsonNull }?.asString
            val latestReelSec = user.get("latest_reel_media")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val latestStoryAt = if (latestReelSec > 0L) latestReelSec * 1000L else 0L

            return InstagramProfileSnapshot(
                username = username,
                instagramId = id,
                fullName = fullName,
                biography = bio,
                isPrivate = isPrivate,
                followersCount = followers,
                followingCount = following,
                mediaCount = mediaCount,
                profilePicUrl = picUrl,
                latestStoryAt = latestStoryAt
            )
        }
    }

    private fun fetchViaGraphQLHash(username: String, cookieString: String?): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/graphql/query/?query_hash=69cba40317214236af40e7efa697781d&variables=%7B%22user_name%22%3A%22$username%22%7D"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .header("X-IG-App-ID", APP_ID)
            .header("Referer", "https://www.instagram.com/$username/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        return try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trimStart() ?: return null
                if (!body.startsWith("{")) return null
                val root = gson.fromJson(body, JsonObject::class.java) ?: return null
                val data = root.getAsJsonObject("data") ?: return null
                val user = data.getAsJsonObject("user") ?: return null

                val returnedUsername = user.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim()
                if (!returnedUsername.isNullOrBlank() && !returnedUsername.equals(username, ignoreCase = true)) {
                    return null
                }

                val id = user.get("id")?.asString
                val fullName = user.get("full_name")?.asString ?: ""
                val bio = user.get("biography")?.asString ?: ""
                val isPrivate = user.get("is_private")?.asBoolean ?: false
                val followers = user.getAsJsonObject("edge_followed_by")?.get("count")?.asLong ?: 0L
                val following = user.getAsJsonObject("edge_follow")?.get("count")?.asLong ?: 0L
                val mediaCount = user.getAsJsonObject("edge_owner_to_timeline_media")?.get("count")?.asLong ?: 0L
                val picUrl = user.get("profile_pic_url_hd")?.asString ?: user.get("profile_pic_url")?.asString

                InstagramProfileSnapshot(
                    username = username,
                    instagramId = id,
                    fullName = fullName,
                    biography = bio,
                    isPrivate = isPrivate,
                    followersCount = followers,
                    followingCount = following,
                    mediaCount = mediaCount,
                    profilePicUrl = picUrl
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL hash query failed for @$username: ${e.message}")
            null
        }
    }

    private fun unescapeJson(input: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = input.length
        while (i < len) {
            val c = input[i]
            if (c == '\\' && i + 1 < len) {
                val next = input[i + 1]
                when (next) {
                    'u' -> {
                        if (i + 5 < len) {
                            try {
                                val hex = input.substring(i + 2, i + 6)
                                sb.append(hex.toInt(16).toChar())
                                i += 6
                                continue
                            } catch (_: Exception) {
                                sb.append(c)
                            }
                        }
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                        continue
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                        continue
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                        continue
                    }
                    'b' -> {
                        sb.append('\b')
                        i += 2
                        continue
                    }
                    'f' -> {
                        sb.append('\u000c')
                        i += 2
                        continue
                    }
                    '/' -> {
                        sb.append('/')
                        i += 2
                        continue
                    }
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                        continue
                    }
                    '\"' -> {
                        sb.append('\"')
                        i += 2
                        continue
                    }
                    '\'' -> {
                        sb.append('\'')
                        i += 2
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString().cleanDisplayText()
    }

    private fun parseFormattedNumber(text: String): Long {
        val raw = text.trim()
        if (raw.isBlank()) return 0L
        return try {
            val upper = raw.uppercase(Locale.ROOT)
            when {
                upper.endsWith("K") -> {
                    val num = upper.removeSuffix("K").replace(",", ".").trim().toDoubleOrNull() ?: 0.0
                    (num * 1_000.0).toLong()
                }
                upper.endsWith("M") -> {
                    val num = upper.removeSuffix("M").replace(",", ".").trim().toDoubleOrNull() ?: 0.0
                    (num * 1_000_000.0).toLong()
                }
                upper.endsWith("B") -> {
                    val num = upper.removeSuffix("B").replace(",", ".").trim().toDoubleOrNull() ?: 0.0
                    (num * 1_000_000_000.0).toLong()
                }
                else -> {
                    val clean = raw.replace(",", "").replace(".", "").trim()
                    clean.toLongOrNull() ?: 0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun downloadImage(url: String, destFile: File): Long {
        destFile.parentFile?.mkdirs()
        val attempts = listOf(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Referer", "https://www.instagram.com/")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build(),
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "image/*,*/*;q=0.8")
                .build()
        )
        for ((idx, request) in attempts.withIndex()) {
            try {
                clientWithRedirects.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLogger.w(TAG, "downloadImage attempt#${idx + 1} HTTP ${response.code} for ${destFile.name}")
                        return@use
                    }
                    val bytes = response.body?.bytes() ?: return@use
                    if (bytes.isEmpty()) return@use
                    FileOutputStream(destFile).use { it.write(bytes) }
                    AppLogger.d(TAG, "downloadImage OK (${bytes.size} B) -> ${destFile.name}")
                    return bytes.size.toLong()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "downloadImage attempt#${idx + 1} error for ${destFile.name}: ${e.message}")
            }
        }
        AppLogger.e(TAG, "downloadImage FAILED for ${destFile.name}")
        return 0L
    }

    fun fetchFollowersList(userId: String, cookieString: String?, limit: Int = 50): List<InstagramUserSummary> {
        if (userId.isBlank()) return emptyList()
        val list = mutableListOf<InstagramUserSummary>()

        // 1. Try Mobile/Web friendship API
        val url = "https://www.instagram.com/api/v1/friendships/$userId/followers/?count=$limit&search_surface=follow_list_page"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        list.addAll(parseUsersList(body))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Friendship followers query failed: ${e.message}")
        }

        if (list.isNotEmpty()) return list

        // 2. Fallback to GraphQL query hash
        val gqlUrl = "https://www.instagram.com/graphql/query/?query_hash=5aedd8a53048e9663c401fa0098046e7&variables=%7B%22id%22%3A%22$userId%22%2C%22first%22%3A$limit%7D"
        val gqlReq = Request.Builder()
            .url(gqlUrl)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            gqlReq.header("Cookie", cookieString)
        }

        try {
            clientWithRedirects.newCall(gqlReq.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        list.addAll(parseGraphQLUsersList(body, "edge_followed_by"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL followers query failed: ${e.message}")
        }

        return list
    }

    fun fetchFollowingList(userId: String, cookieString: String?, limit: Int = 50): List<InstagramUserSummary> {
        if (userId.isBlank()) return emptyList()
        val list = mutableListOf<InstagramUserSummary>()

        // 1. Try Mobile/Web friendship API
        val url = "https://www.instagram.com/api/v1/friendships/$userId/following/?count=$limit"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        list.addAll(parseUsersList(body))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Friendship following query failed: ${e.message}")
        }

        if (list.isNotEmpty()) return list

        // 2. Fallback to GraphQL query hash
        val gqlUrl = "https://www.instagram.com/graphql/query/?query_hash=d04b0a864b4b54837c0d870b0e77e076&variables=%7B%22id%22%3A%22$userId%22%2C%22first%22%3A$limit%7D"
        val gqlReq = Request.Builder()
            .url(gqlUrl)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            gqlReq.header("Cookie", cookieString)
        }

        try {
            clientWithRedirects.newCall(gqlReq.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        list.addAll(parseGraphQLUsersList(body, "edge_follow"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL following query failed: ${e.message}")
        }

        return list
    }

    private fun parseGraphQLUsersList(jsonBody: String, edgeKey: String): List<InstagramUserSummary> {
        val list = mutableListOf<InstagramUserSummary>()
        try {
            val root = gson.fromJson(jsonBody, JsonObject::class.java) ?: return emptyList()
            val data = root.getAsJsonObject("data") ?: return emptyList()
            val user = data.getAsJsonObject("user") ?: return emptyList()
            val edge = user.getAsJsonObject(edgeKey) ?: return emptyList()
            val edges = edge.getAsJsonArray("edges") ?: return emptyList()
            for (item in edges) {
                val node = item.asJsonObject?.getAsJsonObject("node") ?: continue
                val id = node.get("id")?.asString ?: continue
                val username = node.get("username")?.asString ?: continue
                val fullName = node.get("full_name")?.asString ?: ""
                val picUrl = node.get("profile_pic_url")?.asString
                val isPrivate = node.get("is_private")?.asBoolean ?: false
                val isVerified = node.get("is_verified")?.asBoolean ?: false
                list.add(
                    InstagramUserSummary(
                        id = id,
                        username = username,
                        fullName = fullName,
                        profilePicUrl = picUrl,
                        isPrivate = isPrivate,
                        isVerified = isVerified
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing GraphQL users list: ${e.message}")
        }
        return list
    }

    private fun parseUsersList(jsonBody: String): List<InstagramUserSummary> {
        val list = mutableListOf<InstagramUserSummary>()
        try {
            val root = gson.fromJson(jsonBody, JsonObject::class.java) ?: return emptyList()
            val users = root.getAsJsonArray("users") ?: return emptyList()
            for (u in users) {
                val obj = u.asJsonObject ?: continue
                val id = obj.get("pk")?.asString ?: obj.get("id")?.asString ?: continue
                val username = obj.get("username")?.asString ?: continue
                val fullName = obj.get("full_name")?.asString ?: ""
                val picUrl = obj.get("profile_pic_url")?.asString
                val isPrivate = obj.get("is_private")?.asBoolean ?: false
                val isVerified = obj.get("is_verified")?.asBoolean ?: false
                list.add(
                    InstagramUserSummary(
                        id = id,
                        username = username,
                        fullName = fullName,
                        profilePicUrl = picUrl,
                        isPrivate = isPrivate,
                        isVerified = isVerified
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing users list: ${e.message}")
        }
        return list
    }

    fun downloadMedia(
        url: String,
        destFile: File,
        isCancelled: () -> Boolean = { false },
        onByteProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, etaSeconds: Long) -> Unit = { _, _, _, _ -> }
    ): Long {
        destFile.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")
            .build()

        clientWithRedirects.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return 0L
            val body = response.body ?: return 0L
            val totalBytes = body.contentLength()
            val buffer = ByteArray(16 * 1024)
            var downloaded = 0L
            val startMs = System.currentTimeMillis()
            var lastReportMs = startMs
            var lastWindowBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        if (isCancelled()) {
                            return -1L
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val deltaMs = now - lastReportMs
                        if (deltaMs >= 120L) {
                            val windowBytes = downloaded - lastWindowBytes
                            val instantSpeed = if (deltaMs > 0L) (windowBytes * 1000L) / deltaMs else 0L
                            val avgElapsed = (now - startMs).coerceAtLeast(1L)
                            val avgSpeed = (downloaded * 1000L) / avgElapsed
                            val smoothedSpeed = if (instantSpeed > 0L) (instantSpeed * 7L + avgSpeed * 3L) / 10L else avgSpeed
                            val etaSec = if (totalBytes > downloaded && smoothedSpeed > 0L) {
                                ((totalBytes - downloaded) / smoothedSpeed).coerceAtLeast(0L)
                            } else -1L
                            onByteProgress(downloaded, totalBytes, smoothedSpeed, etaSec)
                            lastReportMs = now
                            lastWindowBytes = downloaded
                        }
                    }
                    output.flush()
                }
            }
            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val finalSpeed = (downloaded * 1000L) / elapsedMs
            onByteProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded, finalSpeed, 0L)
            return downloaded
        }
    }

    fun fetchUserHighlights(
        userId: String?,
        cookieString: String?,
        username: String? = null,
        isPrivate: Boolean = false
    ): List<InstagramHighlight> {
        val cleanUser = username?.trim()?.trimStart('@')?.lowercase().orEmpty()
        val hasSession = !cookieString.isNullOrBlank()

        // Tier 1 (Public Zero-Login First): Desktop SSR Polaris lox_highlights_connection
        // Strictly WITHOUT session cookie so Instagram returns SSR highlight trays reliably
        val nowMs = System.currentTimeMillis()
        if (!isPrivate && cleanUser.isNotBlank() && !(hasSession && nowMs < anonymousSsrRleUntilMs)) {
            try {
                val profileUrl = "https://www.instagram.com/$cleanUser/"
                val reqBuilder = Request.Builder()
                    .url(profileUrl)
                    .header("User-Agent", DESKTOP_CHROME_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")

                val html = clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                }
                if (html.isNotBlank()) {
                    val ssrHighlights = parseHighlightsFromSsrHtml(html)
                    if (ssrHighlights.isNotEmpty()) return ssrHighlights
                }
            } catch (e: Exception) {
                Log.w(TAG, "Zero-login Desktop SSR highlights extraction failed for @$cleanUser: ${e.message}")
            }
        }

        // Tier 2: Authenticated Instagram v1 REST Highlights Tray API
        if (!userId.isNullOrBlank() && hasSession) {
            val trayUrl = "https://www.instagram.com/api/v1/highlights/$userId/highlights_tray/"
            val referer = if (cleanUser.isNotBlank()) "https://www.instagram.com/$cleanUser/" else "https://www.instagram.com/"
            try {
                val reqBuilder = applyAuthenticatedApiHeaders(
                    builder = Request.Builder().url(trayUrl),
                    cookieString = cookieString!!,
                    referer = referer,
                    userAgent = DESKTOP_CHROME_UA
                )
                clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val root = gson.fromJson(body, JsonObject::class.java)
                            val tray = root?.getAsJsonArray("tray")
                            if (tray != null) {
                                if (tray.size() == 0) {
                                    AppLogger.d(TAG, "v1 highlights_tray confirmed 0 highlights for user $userId")
                                    return emptyList()
                                }
                                val results = mutableListOf<InstagramHighlight>()
                                for (el in tray) {
                                    val obj = el.asJsonObject ?: continue
                                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText()?.ifBlank { "Highlight" } ?: "Highlight"
                                    val coverMedia = obj.getAsJsonObject("cover_media")
                                    val coverUrl = coverMedia?.getAsJsonObject("cropped_image_version")?.get("url")?.takeIf { !it.isJsonNull }?.asString
                                        ?: coverMedia?.getAsJsonObject("full_image_version")?.get("url")?.takeIf { !it.isJsonNull }?.asString
                                    val count = obj.get("media_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    results.add(
                                        InstagramHighlight(
                                            id = id,
                                            title = title,
                                            coverUrl = coverUrl,
                                            mediaCount = count
                                        )
                                    )
                                }
                                if (results.isNotEmpty()) return results
                            }
                        }
                    } else {
                        AppLogger.d(TAG, "v1 highlights_tray HTTP ${resp.code} for user $userId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "v1 highlights tray failed for user $userId: ${e.message}")
            }
        }

        if (userId.isNullOrBlank() || System.currentTimeMillis() < highlightsGraphqlDeadUntilMs) return emptyList()
        val url = "https://www.instagram.com/graphql/query/?query_hash=d4d88dc1500312af6f937f7b804c68c3&variables=%7B%22user_id%22%3A%22$userId%22%2C%22include_highlight_reels%22%3Atrue%7D"
        val requestBuilder = Request.Builder().url(url)
        if (hasSession) {
            applyAuthenticatedApiHeaders(requestBuilder, cookieString!!, "https://www.instagram.com/")
        } else {
            requestBuilder
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("X-IG-App-ID", APP_ID)
                .header("Referer", "https://www.instagram.com/")
                .header("Accept", "*/*")
        }

        val list = mutableListOf<InstagramHighlight>()
        try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trimStart() ?: return emptyList()
                    if (!body.startsWith("{")) {
                        highlightsGraphqlDeadUntilMs = System.currentTimeMillis() + 60L * 60L * 1000L
                        AppLogger.w(TAG, "Highlights GraphQL non-JSON response for user $userId (len=${body.length}); cooling down legacy GraphQL for 60m")
                        return emptyList()
                    }
                    val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
                    val edges = root.getAsJsonObject("data")
                        ?.getAsJsonObject("user")
                        ?.getAsJsonObject("edge_highlight_reels")
                        ?.getAsJsonArray("edges") ?: return emptyList()

                    for (edge in edges) {
                        val node = edge.asJsonObject?.getAsJsonObject("node") ?: continue
                        val id = node.get("id")?.asString ?: continue
                        val title = node.get("title")?.asString?.cleanDisplayText()?.ifBlank { "Highlight" } ?: "Highlight"
                        val coverUrl = node.getAsJsonObject("cover_media")?.get("thumbnail_src")?.asString
                            ?: node.getAsJsonObject("cover_media_cropped_thumbnail")?.get("url")?.asString
                        val count = node.get("media_count")?.asInt ?: 0
                        list.add(
                            InstagramHighlight(
                                id = id,
                                title = title,
                                coverUrl = coverUrl,
                                mediaCount = count
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Highlights GraphQL query failed for user $userId: ${e.message}")
        }
        return list
    }

    internal fun parseHighlightsFromSsrHtml(html: String): List<InstagramHighlight> {
        val list = mutableListOf<InstagramHighlight>()
        val seenIds = hashSetOf<String>()

        fun walk(elem: JsonElement?) {
            if (elem == null) return
            when {
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    val conn = obj.getAsJsonObject("lox_highlights_connection")
                        ?: obj.getAsJsonObject("edge_highlight_reels")
                    val edges = conn?.getAsJsonArray("edges")
                    if (edges != null) {
                        for (edgeEl in edges) {
                            val node = edgeEl.asJsonObject?.getAsJsonObject("node") ?: continue
                            val rawId = node.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
                            if (rawId.isBlank()) continue
                            val normalizedId = if (rawId.startsWith("highlight:")) rawId else "highlight:$rawId"
                            if (!seenIds.add(normalizedId)) continue

                            val title = node.get("title")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText()?.ifBlank { "Highlight" } ?: "Highlight"
                            val coverUrl = node.get("cover_media_cropped_thumbnail_url")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.getAsJsonObject("cover_media")?.get("thumbnail_src")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.getAsJsonObject("cover_media")?.getAsJsonObject("cropped_image_version")?.get("url")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.getAsJsonObject("cover_media_cropped_thumbnail")?.get("url")?.takeIf { !it.isJsonNull }?.asString
                            val count = node.get("media_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                            list.add(
                                InstagramHighlight(
                                    id = normalizedId,
                                    title = title,
                                    coverUrl = coverUrl,
                                    mediaCount = count
                                )
                            )
                        }
                    }
                    for ((_, v) in obj.entrySet()) {
                        walk(v)
                    }
                }
                elem.isJsonArray -> {
                    for (child in elem.asJsonArray) {
                        walk(child)
                    }
                }
            }
        }

        val scriptPattern = Pattern.compile("<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            if (!raw.contains("lox_highlights_connection") && !raw.contains("edge_highlight_reels")) continue
            try {
                val parsed = gson.fromJson(raw, JsonElement::class.java)
                walk(parsed)
            } catch (_: Exception) {
            }
        }

        return list
    }

    fun fetchHighlightStories(highlightId: String, cookieString: String?): List<InstagramHighlightItem> {
        val numericId = highlightId.removePrefix("highlight:").trim()
        if (numericId.isBlank()) return emptyList()
        val reelKey = "highlight:$numericId"
        val hasSession = !cookieString.isNullOrBlank()

        AppLogger.i(TAG, "fetchHighlightStories($highlightId) start (hasSession=$hasSession)")

        // Tier 1: Mobile Private API (i.instagram.com) + Web v1 REST reels_media
        val nowMs = System.currentTimeMillis()
        val tryRestTier1 = !hasSession || headlessScraper == null || nowMs >= reelsMediaApiCooldownUntilMs
        if (tryRestTier1) {
            val encodedReelId = URLEncoder.encode(reelKey, "UTF-8")
            val referer = "https://www.instagram.com/stories/highlights/$numericId/"
            val v1Candidates = if (hasSession) {
                listOf(
                    "https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=$encodedReelId" to IG_ANDROID_APP_UA,
                    "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$encodedReelId" to DESKTOP_CHROME_UA
                )
            } else {
                listOf("https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$encodedReelId" to DESKTOP_CHROME_UA)
            }

            var sawApiBroken = false
            for ((v1Url, ua) in v1Candidates) {
                try {
                    val reqBuilder = Request.Builder().url(v1Url)
                    if (hasSession) {
                        applyAuthenticatedApiHeaders(reqBuilder, cookieString!!, referer, ua)
                    } else {
                        reqBuilder
                            .header("User-Agent", ua)
                            .header("X-IG-App-ID", APP_ID)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Referer", referer)
                            .header("Accept", "*/*")
                    }

                    clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful && body.trimStart().startsWith("{")) {
                            val parsed = parseReelsMediaJson(body, highlightId, reelKey)
                            if (parsed.isNotEmpty()) {
                                reelsMediaApiCooldownUntilMs = 0L
                                AppLogger.i(TAG, "fetchHighlightStories($highlightId) Tier1 OK (${parsed.size} items via ${v1Url.substringBefore("?")})")
                                return parsed
                            }
                            sawApiBroken = true
                            AppLogger.w(TAG, "fetchHighlightStories($highlightId) Tier1 HTTP 200 but 0 items: ${body.take(140)}")
                        } else {
                            if (resp.code >= 500) sawApiBroken = true
                            AppLogger.w(TAG, "fetchHighlightStories($highlightId) Tier1 HTTP ${resp.code} (${v1Url.substringBefore("?")}) body=${body.take(120)}")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "fetchHighlightStories($highlightId) Tier1 error: ${e.message}")
                }
            }
            if (sawApiBroken && hasSession && headlessScraper != null) {
                reelsMediaApiCooldownUntilMs = System.currentTimeMillis() + 30L * 60L * 1000L
                AppLogger.i(TAG, "reels_media REST returned 500/empty — routing directly to WebView interceptor for 30m")
            }
        }

        if (hasSession && headlessScraper != null) {
            try {
                val wvItems = runBlocking {
                    headlessScraper.scrapeStoriesOrHighlight(
                        targetUrl = "https://www.instagram.com/stories/highlights/$numericId/",
                        highlightId = highlightId,
                        cookieString = cookieString
                    )
                }
                if (wvItems.isNotEmpty()) {
                    AppLogger.i(TAG, "fetchHighlightStories($highlightId) Tier2-WebView OK (${wvItems.size} items)")
                    return wvItems
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchHighlightStories($highlightId) Tier2-WebView error: ${e.message}")
            }
        }

        // Tier 2: Desktop SSR on /stories/highlights/<numericId>/
        try {
            val ssrUrl = "https://www.instagram.com/stories/highlights/$numericId/"
            val reqBuilder = Request.Builder()
                .url(ssrUrl)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")

            if (hasSession) {
                reqBuilder.header("Cookie", cookieString!!)
            }

            val html = clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (html.isNotBlank()) {
                val parsed = parseHighlightStoriesFromSsrHtml(html, highlightId)
                if (parsed.isNotEmpty()) return parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Desktop SSR highlight stories failed for $highlightId: ${e.message}")
        }

        // Tier 3: Legacy GraphQL query_hash fallback
        if (System.currentTimeMillis() < highlightsGraphqlDeadUntilMs) return emptyList()
        val encodedId = URLEncoder.encode("[\"$reelKey\"]", "UTF-8")
        val url = "https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables=%7B%22highlight_reel_ids%22%3A$encodedId%7D"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("X-IG-App-ID", APP_ID)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (hasSession) {
            requestBuilder.header("Cookie", cookieString!!)
            extractCsrfToken(cookieString)?.let { requestBuilder.header("X-CSRFToken", it) }
        }

        val list = mutableListOf<InstagramHighlightItem>()
        try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trimStart() ?: return emptyList()
                    if (!body.startsWith("{")) {
                        highlightsGraphqlDeadUntilMs = System.currentTimeMillis() + 60L * 60L * 1000L
                        AppLogger.w(TAG, "Highlight stories GraphQL non-JSON response for $highlightId (len=${body.length}); cooling down legacy GraphQL for 60m")
                        return emptyList()
                    }
                    val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
                    val reelsMedia = root.getAsJsonObject("data")?.getAsJsonArray("reels_media") ?: return emptyList()
                    for (reel in reelsMedia) {
                        val items = reel.asJsonObject?.getAsJsonArray("items") ?: continue
                        for (item in items) {
                            val obj = item.asJsonObject ?: continue
                            parseSingleStoryNode(obj, highlightId)?.let { list.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Highlight stories GraphQL query failed for $highlightId: ${e.message}")
        }
        return list
    }

    internal fun parseReelsMediaJson(body: String, highlightId: String, reelKey: String): List<InstagramHighlightItem> {
        val list = mutableListOf<InstagramHighlightItem>()
        val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()

        // Format A: { "reels": { "highlight:123": { "items": [...] } } }
        val reelsObj = root.getAsJsonObject("reels")
        val targetReel = reelsObj?.getAsJsonObject(reelKey)
            ?: reelsObj?.entrySet()?.firstOrNull()?.value?.asJsonObject
        targetReel?.getAsJsonArray("items")?.forEach { el ->
            el.asJsonObject?.let { parseSingleStoryNode(it, highlightId)?.let(list::add) }
        }
        if (list.isNotEmpty()) return list

        // Format B: { "reels_media": [ { "items": [...] } ] }
        root.getAsJsonArray("reels_media")?.forEach { reelEl ->
            reelEl.asJsonObject?.getAsJsonArray("items")?.forEach { el ->
                el.asJsonObject?.let { parseSingleStoryNode(it, highlightId)?.let(list::add) }
            }
        }
        if (list.isNotEmpty()) return list

        // Format C: { "reel": { "items": [...] } }
        root.getAsJsonObject("reel")?.getAsJsonArray("items")?.forEach { el ->
            el.asJsonObject?.let { parseSingleStoryNode(it, highlightId)?.let(list::add) }
        }
        return list
    }

    internal fun parseHighlightStoriesFromSsrHtml(html: String, highlightId: String): List<InstagramHighlightItem> {
        val list = mutableListOf<InstagramHighlightItem>()
        val seenIds = hashSetOf<String>()

        fun walk(elem: JsonElement?) {
            if (elem == null) return
            when {
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    val conn = obj.getAsJsonObject("xdt_api__v1__feed__reels_media__connection")
                    conn?.getAsJsonArray("edges")?.forEach { edgeEl ->
                        val items = edgeEl.asJsonObject?.getAsJsonObject("node")?.getAsJsonArray("items")
                        items?.forEach { itemEl ->
                            val itemObj = itemEl.asJsonObject ?: return@forEach
                            val parsed = parseSingleStoryNode(itemObj, highlightId)
                            if (parsed != null && seenIds.add(parsed.id)) {
                                list.add(parsed)
                            }
                        }
                    }
                    for ((_, v) in obj.entrySet()) {
                        walk(v)
                    }
                }
                elem.isJsonArray -> {
                    for (child in elem.asJsonArray) {
                        walk(child)
                    }
                }
            }
        }

        val scriptPattern = Pattern.compile("<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            if (!raw.contains("xdt_api__v1__feed__reels_media__connection")) continue
            try {
                walk(gson.fromJson(raw, JsonElement::class.java))
            } catch (_: Exception) {
            }
        }
        return list
    }

    private fun parseSingleStoryNode(obj: JsonObject, highlightId: String): InstagramHighlightItem? {
        val id = obj.get("pk")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("id")?.takeIf { !it.isJsonNull }?.asString
            ?: return null

        val vidUrl = obj.getAsJsonArray("video_versions")?.firstOrNull()?.asJsonObject?.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.getAsJsonArray("video_resources")?.firstOrNull()?.asJsonObject?.get("src")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("video_url")?.takeIf { !it.isJsonNull }?.asString

        val imgUrl = obj.getAsJsonObject("image_versions2")?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("display_url")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("display_uri")?.takeIf { !it.isJsonNull }?.asString

        val isVideo = !vidUrl.isNullOrBlank() ||
            (obj.get("is_video")?.takeIf { !it.isJsonNull }?.asBoolean == true) ||
            (obj.get("media_type")?.takeIf { !it.isJsonNull }?.asInt == 2)

        val cdnUrl = if (isVideo && !vidUrl.isNullOrBlank()) vidUrl else (imgUrl ?: "")
        if (cdnUrl.isBlank()) return null

        val takenAtSec = obj.get("taken_at")?.takeIf { !it.isJsonNull }?.asLong
            ?: obj.get("taken_at_timestamp")?.takeIf { !it.isJsonNull }?.asLong
            ?: (System.currentTimeMillis() / 1000L)

        return InstagramHighlightItem(
            id = id,
            highlightId = highlightId,
            mediaType = if (isVideo) "video" else "image",
            mediaUrl = cdnUrl,
            takenAt = takenAtSec * 1000L
        )
    }

    fun fetchActiveUserStories(
        userId: String?,
        username: String,
        cookieString: String?
    ): List<InstagramHighlightItem> {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val virtualHighlightId = "active_story:$cleanUser"
        val hasSession = !cookieString.isNullOrBlank()
        val viewerDsUserId = extractDsUserId(cookieString)

        // Ensure userId is valid and NOT poisoned by the logged-in viewer's own ds_user_id
        var resolvedUserId = userId?.trim()?.takeIf { it.isNotBlank() }
        if (resolvedUserId.isNullOrBlank() || (viewerDsUserId != null && resolvedUserId == viewerDsUserId)) {
            val freshSnap = try { fetchViaHtml(cleanUser) } catch (_: Exception) { null }
                ?: try { fetchViaWebApi(cleanUser, cookieString = null) } catch (_: Exception) { null }
            if (!freshSnap?.instagramId.isNullOrBlank()) {
                resolvedUserId = freshSnap!!.instagramId
            }
        }

        AppLogger.i(TAG, "fetchActiveUserStories(@$cleanUser, uid=$resolvedUserId) start (hasSession=$hasSession)")

        // Tier 1A & 1B: Mobile API (i.instagram.com) + Web v1 REST endpoints
        val tryStoryRest = headlessScraper == null || System.currentTimeMillis() >= reelsMediaApiCooldownUntilMs
        if (!resolvedUserId.isNullOrBlank() && hasSession && tryStoryRest) {
            val encodedId = URLEncoder.encode(resolvedUserId, "UTF-8")
            val storyCandidates = listOf(
                "https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=$encodedId" to IG_ANDROID_APP_UA,
                "https://i.instagram.com/api/v1/feed/user/$encodedId/story/" to IG_ANDROID_APP_UA,
                "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$encodedId" to DESKTOP_CHROME_UA,
                "https://www.instagram.com/api/v1/feed/user/$encodedId/story/" to DESKTOP_CHROME_UA
            )
            val referer = "https://www.instagram.com/stories/$cleanUser/"
            for ((v1Url, ua) in storyCandidates) {
                try {
                    val reqBuilder = applyAuthenticatedApiHeaders(
                        builder = Request.Builder().url(v1Url),
                        cookieString = cookieString!!,
                        referer = referer,
                        userAgent = ua
                    )
                    clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful && body.trimStart().startsWith("{")) {
                            val parsed = parseReelsMediaJson(body, virtualHighlightId, resolvedUserId)
                            if (parsed.isNotEmpty()) {
                                AppLogger.i(TAG, "fetchActiveUserStories(@$cleanUser) Tier1 OK (${parsed.size} items)")
                                return parsed
                            }
                            AppLogger.w(TAG, "fetchActiveUserStories(@$cleanUser) HTTP 200 but 0 items: ${body.take(140)}")
                        } else {
                            AppLogger.w(TAG, "fetchActiveUserStories(@$cleanUser) HTTP ${resp.code} (${v1Url.substringBefore("?")}) body=${body.take(120)}")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "fetchActiveUserStories(@$cleanUser) Tier1 error: ${e.message}")
                }
            }
        }

        // Tier 2: executes Instagram's live web player
        if (hasSession && headlessScraper != null) {
            try {
                val wvItems = runBlocking {
                    headlessScraper.scrapeStoriesOrHighlight(
                        targetUrl = "https://www.instagram.com/stories/$cleanUser/",
                        highlightId = virtualHighlightId,
                        cookieString = cookieString
                    )
                }
                if (wvItems.isNotEmpty()) {
                    AppLogger.i(TAG, "fetchActiveUserStories(@$cleanUser) Tier2-WebView OK (${wvItems.size} items)")
                    return wvItems
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchActiveUserStories(@$cleanUser) Tier2-WebView error: ${e.message}")
            }
        }

        // Tier 2: Desktop SSR on /stories/<username>/
        try {
            val ssrUrl = "https://www.instagram.com/stories/$cleanUser/"
            val reqBuilder = Request.Builder()
                .url(ssrUrl)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")

            if (hasSession) {
                reqBuilder.header("Cookie", cookieString!!)
            }

            val html = clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (html.isNotBlank()) {
                val parsed = parseHighlightStoriesFromSsrHtml(html, virtualHighlightId)
                if (parsed.isNotEmpty()) return parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Desktop SSR active stories failed for @$cleanUser: ${e.message}")
        }

        // Tier 3: Legacy GraphQL query_hash fallback
        if (!resolvedUserId.isNullOrBlank() && hasSession) {
            try {
                val encodedIds = URLEncoder.encode("[\"$resolvedUserId\"]", "UTF-8")
                val gqlUrl = "https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables=%7B%22reel_ids%22%3A$encodedIds%2C%22precomposed_overlay%22%3Afalse%7D"
                val reqBuilder = applyAuthenticatedApiHeaders(
                    builder = Request.Builder().url(gqlUrl),
                    cookieString = cookieString!!,
                    referer = "https://www.instagram.com/stories/$cleanUser/"
                )

                clientWithRedirects.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val reelsMedia = root?.getAsJsonObject("data")?.getAsJsonArray("reels_media")
                        val items = mutableListOf<InstagramHighlightItem>()
                        reelsMedia?.forEach { reelEl ->
                            reelEl.asJsonObject?.getAsJsonArray("items")?.forEach { el ->
                                el.asJsonObject?.let { parseSingleStoryNode(it, virtualHighlightId)?.let(items::add) }
                            }
                        }
                        if (items.isNotEmpty()) return items
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GraphQL active stories fallback failed for @$cleanUser: ${e.message}")
            }
        }

        return emptyList()
    }

    suspend fun fetchRecentPostsDetailed(
        userId: String?,
        username: String,
        cookieString: String?,
        limit: Int = 24,
        isPrivate: Boolean = false
    ): PostFetchResult {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val hasRealSession = !cookieString.isNullOrBlank()

        // Private accounts require a valid user session to view timeline posts
        if (isPrivate && !hasRealSession) {
            return PostFetchResult.RequiresSession
        }

        val list = mutableListOf<InstagramPostItem>()
        var sawRateLimit = false

        // Public Zero-Login First: Desktop SSR Relay JSON Extraction
        if (!isPrivate) {
            try {
                val ssrPosts = fetchPostsViaDesktopSsr(cleanUser, cookieString = null, limit = limit)
                if (ssrPosts.isNotEmpty()) {
                    return PostFetchResult.Success(ssrPosts)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Zero-login Desktop SSR post extraction failed for @$cleanUser: ${e.message}")
            }
        }

        val webApiCookies = if (!isPrivate) {
            listOfNotNull(getOrFetchGuestCookies(), cookieString?.takeIf { it.isNotBlank() }).distinct()
        } else {
            listOfNotNull(cookieString?.takeIf { it.isNotBlank() })
        }
        for (effectiveCookie in webApiCookies) {
            try {
                val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$cleanUser"
                val req = applyAuthenticatedApiHeaders(
                    builder = Request.Builder().url(url),
                    cookieString = effectiveCookie,
                    referer = "https://www.instagram.com/$cleanUser/"
                )

                clientWithRedirects.newCall(req.build()).execute().use { response ->
                    when (response.code) {
                        429 -> sawRateLimit = true
                        401, 403 -> {
                            if (isPrivate && !hasRealSession) return PostFetchResult.RequiresSession
                        }
                        200 -> {
                            val body = response.body?.string()
                            if (body != null && body.startsWith("{")) {
                                val needsLogin = body.contains("/accounts/login/") || body.contains("login_required")
                                if (!needsLogin) {
                                    val root = gson.fromJson(body, JsonObject::class.java)
                                    val userObj = root?.getAsJsonObject("data")?.getAsJsonObject("user")
                                    val returnedUsername = userObj?.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim()
                                    if (!returnedUsername.isNullOrBlank() && !returnedUsername.equals(cleanUser, ignoreCase = true)) {
                                        return@use
                                    }
                                    val apiPrivate = userObj?.get("is_private")?.asBoolean ?: isPrivate
                                    if (apiPrivate && !hasRealSession) {
                                        return PostFetchResult.RequiresSession
                                    }
                                    val edges = userObj
                                        ?.getAsJsonObject("edge_owner_to_timeline_media")
                                        ?.getAsJsonArray("edges")
                                    if (edges != null && edges.size() > 0) {
                                        for (edge in edges) {
                                            if (list.size >= limit) break
                                            val node = edge.asJsonObject?.getAsJsonObject("node") ?: continue
                                            val id = node.get("id")?.asString ?: continue
                                            val shortcode = node.get("shortcode")?.asString ?: id
                                            val isVideo = node.get("is_video")?.asBoolean ?: false
                                            val displayUrl = node.get("display_url")?.asString ?: ""
                                            val videoUrl = if (isVideo) node.get("video_url")?.asString else null
                                            val slides = extractSidecarSlides(node)
                                            val resolvedMediaType = when {
                                                slides.size > 1 -> "carousel"
                                                isVideo -> "video"
                                                else -> "image"
                                            }
                                            val captionEdges = node.getAsJsonObject("edge_media_to_caption")?.getAsJsonArray("edges")
                                            val caption = if (captionEdges != null && captionEdges.size() > 0) {
                                                captionEdges[0].asJsonObject?.getAsJsonObject("node")?.get("text")?.asString?.cleanDisplayText() ?: ""
                                            } else ""
                                            val likes = node.getAsJsonObject("edge_media_preview_like")?.get("count")?.asInt
                                                ?: node.getAsJsonObject("edge_liked_by")?.get("count")?.asInt ?: 0
                                            val comments = node.getAsJsonObject("edge_media_to_comment")?.get("count")?.asInt ?: 0
                                            val timestamp = node.get("taken_at_timestamp")?.asLong ?: (System.currentTimeMillis() / 1000L)

                                            list.add(
                                                InstagramPostItem(
                                                    id = shortcode,
                                                    shortcode = shortcode,
                                                    mediaType = resolvedMediaType,
                                                    displayUrl = displayUrl,
                                                    videoUrl = videoUrl,
                                                    caption = caption.cleanDisplayText(),
                                                    timestamp = timestamp * 1000L,
                                                    likeCount = likes,
                                                    commentCount = comments,
                                                    slides = slides
                                                )
                                            )
                                        }
                                        return PostFetchResult.Success(list)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Web profile info posts query failed: ${e.message}")
            }
        }

        if (hasRealSession) {
            try {
                val feedUrl = "https://www.instagram.com/api/v1/feed/user/$cleanUser/username/?count=$limit"
                val req = applyAuthenticatedApiHeaders(
                    builder = Request.Builder().url(feedUrl),
                    cookieString = cookieString!!,
                    referer = "https://www.instagram.com/$cleanUser/"
                )
                clientWithRedirects.newCall(req.build()).execute().use { response ->
                    if (response.code == 429) sawRateLimit = true
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val root = gson.fromJson(body, JsonObject::class.java)
                            val items = root?.getAsJsonArray("items")
                            if (items != null && items.size() > 0) {
                                for (itemEl in items) {
                                    if (list.size >= limit) break
                                    val node = itemEl.asJsonObject ?: continue
                                    if (isSponsoredOrForeignPostNode(node, cleanUser)) continue
                                    val code = node.get("code")?.takeIf { !it.isJsonNull }?.asString ?: continue
                                    val mediaTypeInt = node.get("media_type")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                                    val slides = extractPolarisCarouselSlides(node)
                                    val isCarousel = mediaTypeInt == 8 || slides.size > 1
                                    val isVideo = mediaTypeInt == 2
                                    val resolvedType = when {
                                        isCarousel -> "carousel"
                                        isVideo -> "video"
                                        else -> "image"
                                    }
                                    val displayUrl = node.getAsJsonObject("image_versions2")
                                        ?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                        ?: slides.firstOrNull()?.displayUrl
                                        ?: ""
                                    val videoUrl = node.getAsJsonArray("video_versions")
                                        ?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                    val caption = node.getAsJsonObject("caption")?.get("text")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
                                    val likes = node.get("like_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    val comments = node.get("comment_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    val takenAtSec = node.get("taken_at")?.takeIf { !it.isJsonNull }?.asLong ?: (System.currentTimeMillis() / 1000L)
                                    list.add(
                                        InstagramPostItem(
                                            id = code,
                                            shortcode = code,
                                            mediaType = resolvedType,
                                            displayUrl = displayUrl.ifBlank { "https://www.instagram.com/p/$code/media/?size=l" },
                                            videoUrl = videoUrl,
                                            caption = caption,
                                            timestamp = takenAtSec * 1000L,
                                            likeCount = likes,
                                            commentCount = comments,
                                            slides = slides
                                        )
                                    )
                                }
                                if (list.isNotEmpty()) return PostFetchResult.Success(list)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "v1 feed/user/username failed for @$cleanUser: ${e.message}")
            }
        }

        // 2. Tier B: GraphQL query hash if user_id is provided and real session cookie exists
        if (!userId.isNullOrBlank() && hasRealSession) {
            try {
                val gqlUrl = "https://www.instagram.com/graphql/query/?query_hash=e7465275e4878740f5b475ab0877917b&variables=%7B%22id%22%3A%22$userId%22%2C%22first%22%3A$limit%7D"
                val req = Request.Builder()
                    .url(gqlUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("X-IG-App-ID", APP_ID)
                    .header("Referer", "https://www.instagram.com/")
                    .header("Accept", "*/*")
                    .header("Cookie", cookieString!!)

                clientWithRedirects.newCall(req.build()).execute().use { response ->
                    if (response.code == 429) sawRateLimit = true
                    if (response.code in listOf(401, 403) && isPrivate) return PostFetchResult.RequiresSession
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val edges = root?.getAsJsonObject("data")
                            ?.getAsJsonObject("user")
                            ?.getAsJsonObject("edge_owner_to_timeline_media")
                            ?.getAsJsonArray("edges")
                        if (edges != null && edges.size() > 0) {
                            for (edge in edges) {
                                if (list.size >= limit) break
                                val node = edge.asJsonObject?.getAsJsonObject("node") ?: continue
                                val id = node.get("id")?.asString ?: continue
                                val shortcode = node.get("shortcode")?.asString ?: id
                                val isVideo = node.get("is_video")?.asBoolean ?: false
                                val displayUrl = node.get("display_url")?.asString ?: ""
                                val videoUrl = if (isVideo) node.get("video_url")?.asString else null
                                val slides = extractSidecarSlides(node)
                                val resolvedMediaType = when {
                                    slides.size > 1 -> "carousel"
                                    isVideo -> "video"
                                    else -> "image"
                                }
                                val captionEdges = node.getAsJsonObject("edge_media_to_caption")?.getAsJsonArray("edges")
                                val caption = if (captionEdges != null && captionEdges.size() > 0) {
                                    captionEdges[0].asJsonObject?.getAsJsonObject("node")?.get("text")?.asString?.cleanDisplayText() ?: ""
                                } else ""
                                val likes = node.getAsJsonObject("edge_media_preview_like")?.get("count")?.asInt ?: 0
                                val comments = node.getAsJsonObject("edge_media_to_comment")?.get("count")?.asInt ?: 0
                                val timestamp = node.get("taken_at_timestamp")?.asLong ?: (System.currentTimeMillis() / 1000L)

                                list.add(
                                    InstagramPostItem(
                                        id = shortcode,
                                        shortcode = shortcode,
                                        mediaType = resolvedMediaType,
                                        displayUrl = displayUrl,
                                        videoUrl = videoUrl,
                                        caption = caption.cleanDisplayText(),
                                        timestamp = timestamp * 1000L,
                                        likeCount = likes,
                                        commentCount = comments,
                                        slides = slides
                                    )
                                )
                            }
                            return PostFetchResult.Success(list)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GraphQL timeline posts query failed: ${e.message}")
            }
        }

        if (!isPrivate && !(hasRealSession && System.currentTimeMillis() < anonymousSsrRleUntilMs)) {
            try {
                val htmlUrl = "https://www.instagram.com/$cleanUser/"
                val htmlReq = Request.Builder()
                    .url(htmlUrl)
                    .header("User-Agent", DESKTOP_CHROME_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")

                clientWithRedirects.newCall(htmlReq.build()).execute().use { resp ->
                    if (resp.code == 429) {
                        sawRateLimit = true
                    } else if (resp.isSuccessful) {
                        val html = resp.body?.string() ?: ""
                        if (html.contains("\"is_private\":true") && !hasRealSession) {
                            return PostFetchResult.RequiresSession
                        }
                        val parsed = parsePostsFromSsrHtml(html, limit = limit, targetUsername = cleanUser)
                        if (parsed.isNotEmpty()) return PostFetchResult.Success(parsed)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTML post parsing fallback failed: ${e.message}")
            }
        }

        if (list.isNotEmpty()) return PostFetchResult.Success(list)
        if (isPrivate && !hasRealSession) return PostFetchResult.RequiresSession
        if (sawRateLimit) return PostFetchResult.RateLimited
        return PostFetchResult.Error("No posts returned")
    }

    private fun fetchPostsViaDesktopSsr(
        username: String,
        cookieString: String?,
        limit: Int
    ): List<InstagramPostItem> {
        val url = "https://www.instagram.com/$username/"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")

        if (!cookieString.isNullOrBlank()) {
            req.header("Cookie", cookieString)
        }

        val html = clientWithRedirects.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        if (html.isBlank()) return emptyList()

        return parsePostsFromSsrHtml(html, limit, targetUsername = username)
    }

    internal fun isSponsoredOrForeignPostNode(node: JsonObject, targetUsername: String?): Boolean {
        val adKeys = listOf(
            "ad_id", "ad_action", "is_ad", "injected", "dr_ad_type",
            "ad_link_type", "ad_metadata", "sponsored_label", "is_sponsored",
            "ad_header_style", "ad_expiry_timestamp_in_millis", "fb_ad_id",
            "campaign_id", "ad_cta", "cta_text", "link_text",
            "iab_autofill_optout_info", "click_id", "ad_intent_data", "boosted_status"
        )
        for (key in adKeys) {
            val el = node.get(key)
            if (el != null && !el.isJsonNull) {
                if (el.isJsonPrimitive) {
                    val p = el.asJsonPrimitive
                    if ((p.isBoolean && p.asBoolean) || (p.isString && p.asString.isNotBlank()) || (p.isNumber && p.asLong != 0L)) {
                        return true
                    }
                } else {
                    return true
                }
            }
        }
        val productType = node.get("product_type")?.takeIf { !it.isJsonNull }?.asString?.lowercase(Locale.ROOT).orEmpty()
        if (productType == "ad" || productType == "sponsored") return true

        val label = node.get("label")?.takeIf { !it.isJsonNull }?.asString?.lowercase(Locale.ROOT).orEmpty()
        if (label.contains("sponsored") || label.contains("reklam") || label.contains("реклама")) return true

        val cleanTarget = targetUsername?.trim()?.trimStart('@')?.lowercase(Locale.ROOT).orEmpty()
        if (cleanTarget.isNotBlank()) {
            val ownerObj = node.getAsJsonObject("user") ?: node.getAsJsonObject("owner")
            val ownerName = ownerObj?.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (ownerName.isNotBlank() && ownerName != cleanTarget) {
                val isCoAuthor = node.getAsJsonArray("coauthor_producers")?.any { coEl ->
                    coEl.asJsonObject?.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim()?.equals(cleanTarget, ignoreCase = true) == true
                } == true
                if (!isCoAuthor) return true
            }
        }
        return false
    }

    internal fun parsePostsFromSsrHtml(
        html: String,
        limit: Int = 24,
        targetUsername: String? = null
    ): List<InstagramPostItem> {
        val results = mutableListOf<InstagramPostItem>()
        val seenCodes = hashSetOf<String>()
        val now = System.currentTimeMillis()

        val scriptPattern = Pattern.compile("<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)

        fun collectEdgesFromJson(elem: JsonElement?) {
            if (elem == null || results.size >= limit) return
            when {
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    if (obj.has("ad_id") || obj.has("injected") || obj.has("explore_story")) return
                    val connectionKeys = listOf(
                        "polaris_ordered_timeline_connection",
                        "xdt_api__v1__feed__user_timeline_graphql_connection",
                        "edge_owner_to_timeline_media"
                    )
                    for (connKey in connectionKeys) {
                        val edges = obj.getAsJsonObject(connKey)?.getAsJsonArray("edges") ?: continue
                        for (edgeEl in edges) {
                            if (results.size >= limit) break
                            val node = edgeEl.asJsonObject?.getAsJsonObject("node") ?: continue
                            if (isSponsoredOrForeignPostNode(node, targetUsername)) continue
                            val code = node.get("code")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.get("shortcode")?.takeIf { !it.isJsonNull }?.asString
                                ?: continue
                            if (code.isBlank() || !seenCodes.add(code)) continue

                            val typename = node.get("__typename")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                            val mediaTypeInt = node.get("media_type")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                            val carouselCount = node.get("carousel_media_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                            val sidecarSlides = extractSidecarSlides(node)
                            val carouselSlides = extractPolarisCarouselSlides(node)
                            val slides = if (carouselSlides.isNotEmpty()) carouselSlides else sidecarSlides

                            val isCarousel = typename.contains("Carousel", ignoreCase = true) ||
                                typename.contains("Sidecar", ignoreCase = true) ||
                                mediaTypeInt == 8 ||
                                carouselCount > 1 ||
                                slides.size > 1
                            val isVideo = typename.contains("Video", ignoreCase = true) ||
                                mediaTypeInt == 2 ||
                                (node.get("is_video")?.takeIf { !it.isJsonNull }?.asBoolean == true)

                            val resolvedType = when {
                                isCarousel -> "carousel"
                                isVideo -> "video"
                                else -> "image"
                            }

                            val displayUrl = node.get("display_uri")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.get("display_url")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.getAsJsonObject("image_versions2")
                                    ?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                ?: ""
                            val videoUrl = node.get("video_url")?.takeIf { !it.isJsonNull }?.asString
                                ?: node.getAsJsonArray("video_versions")?.firstOrNull()?.asJsonObject?.get("url")?.asString

                            val caption = node.getAsJsonObject("caption")?.get("text")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText()
                                ?: node.getAsJsonObject("edge_media_to_caption")?.getAsJsonArray("edges")
                                    ?.firstOrNull()?.asJsonObject?.getAsJsonObject("node")?.get("text")?.asString?.cleanDisplayText()
                                ?: ""

                            val likes = node.get("like_count")?.takeIf { !it.isJsonNull }?.asInt
                                ?: node.getAsJsonObject("edge_media_preview_like")?.get("count")?.asInt
                                ?: 0
                            val comments = node.get("comment_count")?.takeIf { !it.isJsonNull }?.asInt
                                ?: node.getAsJsonObject("edge_media_to_comment")?.get("count")?.asInt
                                ?: 0
                            val takenAtSec = node.get("taken_at")?.takeIf { !it.isJsonNull }?.asLong
                                ?: node.get("taken_at_timestamp")?.takeIf { !it.isJsonNull }?.asLong
                            val timestampMs = if (takenAtSec != null && takenAtSec > 0L) {
                                takenAtSec * 1000L
                            } else {
                                now - (results.size * 1000L)
                            }

                            results.add(
                                InstagramPostItem(
                                    id = code,
                                    shortcode = code,
                                    mediaType = resolvedType,
                                    displayUrl = displayUrl.ifBlank { "https://www.instagram.com/p/$code/media/?size=l" },
                                    videoUrl = videoUrl,
                                    caption = caption,
                                    timestamp = timestampMs,
                                    likeCount = likes,
                                    commentCount = comments,
                                    slides = slides
                                )
                            )
                        }
                    }

                    for ((_, value) in obj.entrySet()) {
                        if (results.size >= limit) break
                        collectEdgesFromJson(value)
                    }
                }
                elem.isJsonArray -> {
                    for (child in elem.asJsonArray) {
                        if (results.size >= limit) break
                        collectEdgesFromJson(child)
                    }
                }
            }
        }

        while (matcher.find() && results.size < limit) {
            val rawJson = matcher.group(1) ?: continue
            if (!rawJson.contains("polaris_ordered_timeline_connection") &&
                !rawJson.contains("xdt_api__v1__feed__user_timeline_graphql_connection") &&
                !rawJson.contains("edge_owner_to_timeline_media")
            ) {
                continue
            }
            try {
                val parsed = gson.fromJson(rawJson, JsonElement::class.java)
                collectEdgesFromJson(parsed)
            } catch (_: Exception) {
                // Skip non-standard script payload
            }
        }

        return results
    }

    private fun extractPolarisCarouselSlides(node: JsonObject): List<CarouselSlideHint> {
        val arr = node.getAsJsonArray("carousel_media") ?: return emptyList()
        val slides = mutableListOf<CarouselSlideHint>()
        arr.forEachIndexed { idx, el ->
            val cObj = el.asJsonObject ?: return@forEachIndexed
            val vidUrl = cObj.getAsJsonArray("video_versions")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                ?: cObj.get("video_url")?.takeIf { !it.isJsonNull }?.asString
            val imgObj = cObj.getAsJsonObject("image_versions2")?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
            val imgUrl = imgObj?.get("url")?.asString
                ?: cObj.get("display_uri")?.takeIf { !it.isJsonNull }?.asString
                ?: cObj.get("display_url")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            val w = imgObj?.get("width")?.asInt ?: cObj.get("original_width")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val h = imgObj?.get("height")?.asInt ?: cObj.get("original_height")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val isVid = !vidUrl.isNullOrBlank() || (cObj.get("media_type")?.takeIf { !it.isJsonNull }?.asInt == 2)
            if (imgUrl.isNotBlank() || !vidUrl.isNullOrBlank()) {
                slides.add(
                    CarouselSlideHint(
                        position = idx,
                        mediaType = if (isVid) "video" else "image",
                        displayUrl = imgUrl,
                        videoUrl = vidUrl,
                        width = w,
                        height = h
                    )
                )
            }
        }
        return slides
    }

    internal fun parsePostDetailsFromSsrHtml(html: String, shortcode: String): PostMediaDetails? {
        val scriptPattern = Pattern.compile("<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)

        var foundNode: JsonObject? = null
        fun findMediaObject(elem: JsonElement?) {
            if (elem == null || foundNode != null) return
            when {
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    val code = obj.get("code")?.takeIf { !it.isJsonNull }?.asString
                    val hasPayload = obj.has("carousel_media") || obj.has("image_versions2") || obj.has("video_versions")
                    if (code.equals(shortcode, ignoreCase = true) && hasPayload) {
                        foundNode = obj
                        return
                    }
                    val polaris = obj.getAsJsonObject("xig_polaris_media")?.getAsJsonObject("if_not_gated_logged_out")
                    if (polaris != null) {
                        foundNode = polaris
                        return
                    }
                    for ((_, v) in obj.entrySet()) {
                        if (foundNode != null) return
                        findMediaObject(v)
                    }
                }
                elem.isJsonArray -> {
                    for (child in elem.asJsonArray) {
                        if (foundNode != null) return
                        findMediaObject(child)
                    }
                }
            }
        }

        while (matcher.find() && foundNode == null) {
            val raw = matcher.group(1) ?: continue
            if (!raw.contains("xig_polaris_media") && !raw.contains("carousel_media")) continue
            try {
                val parsed = gson.fromJson(raw, JsonElement::class.java)
                findMediaObject(parsed)
            } catch (_: Exception) {
            }
        }

        val mediaObj = foundNode ?: return null
        val carouselSlides = extractPolarisCarouselSlides(mediaObj)
        val slides = if (carouselSlides.isNotEmpty()) {
            carouselSlides
        } else {
            val vidUrl = mediaObj.getAsJsonArray("video_versions")?.firstOrNull()?.asJsonObject?.get("url")?.asString
            val imgObj = mediaObj.getAsJsonObject("image_versions2")?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
            val imgUrl = imgObj?.get("url")?.asString
                ?: mediaObj.get("display_uri")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            if (imgUrl.isNotBlank() || !vidUrl.isNullOrBlank()) {
                listOf(
                    CarouselSlideHint(
                        position = 0,
                        mediaType = if (!vidUrl.isNullOrBlank()) "video" else "image",
                        displayUrl = imgUrl,
                        videoUrl = vidUrl,
                        width = imgObj?.get("width")?.asInt ?: 0,
                        height = imgObj?.get("height")?.asInt ?: 0
                    )
                )
            } else emptyList()
        }

        if (slides.isEmpty()) return null

        val caption = mediaObj.getAsJsonObject("caption")?.get("text")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText()
        val likes = mediaObj.get("like_count")?.takeIf { !it.isJsonNull }?.asInt
        val comments = mediaObj.get("comment_count")?.takeIf { !it.isJsonNull }?.asInt
        val takenAt = mediaObj.get("taken_at")?.takeIf { !it.isJsonNull }?.asLong?.let { it * 1000L }
        val duration = mediaObj.get("video_duration")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()

        return PostMediaDetails(
            slides = slides,
            metadata = ExtractedPostMetadata(
                caption = caption?.ifBlank { null },
                likeCount = likes,
                commentCount = comments,
                postedAtMillis = takenAt,
                durationSeconds = duration,
                displayUrl = slides.first().displayUrl.ifBlank { null }
            )
        )
    }

    // Extracts ordered carousel slides from an Instagram timeline/post GraphQL node.
    private fun extractSidecarSlides(node: JsonObject): List<CarouselSlideHint> {
        val edges = node.getAsJsonObject("edge_sidecar_to_children")?.getAsJsonArray("edges") ?: return emptyList()
        val slides = mutableListOf<CarouselSlideHint>()
        edges.forEachIndexed { idx, elem ->
            val child = elem.asJsonObject?.getAsJsonObject("node") ?: return@forEachIndexed
            val isVid = child.get("is_video")?.asBoolean ?: false
            val disp = child.get("display_url")?.asString ?: ""
            val vid = if (isVid) child.get("video_url")?.asString else null
            val dims = child.getAsJsonObject("dimensions")
            val w = dims?.get("width")?.asInt ?: 0
            val h = dims?.get("height")?.asInt ?: 0
            if (disp.isNotBlank() || !vid.isNullOrBlank()) {
                slides.add(
                    CarouselSlideHint(
                        position = idx,
                        mediaType = if (isVid) "video" else "image",
                        displayUrl = disp,
                        videoUrl = vid,
                        width = w,
                        height = h
                    )
                )
            }
        }
        return slides
    }

    fun fetchPostMediaDetails(
        shortcode: String,
        cookieString: String?,
        fallbackDisplayUrl: String = "",
        fallbackVideoUrl: String? = null,
        isVideoHint: Boolean = false
    ): PostMediaDetails? {
        val cleanCode = shortcode.trim()

        // 0. Zero-Login First: Desktop SSR HTML on /p/<shortcode>/ (NEVER sends session cookie,
        // returns full Carousel slides, HD URLs, likes, comments & taken_at for public posts)
        try {
            val postPageUrl = "https://www.instagram.com/p/$cleanCode/"
            val req = Request.Builder()
                .url(postPageUrl)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")

            clientWithRedirects.newCall(req.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val parsed = parsePostDetailsFromSsrHtml(html, cleanCode)
                    if (parsed != null && parsed.slides.isNotEmpty()) {
                        return parsed
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Zero-login Post Desktop SSR fetch skipped for $cleanCode: ${e.message}")
        }

        val effectiveCookie = cookieString?.takeIf { it.isNotBlank() } ?: getOrFetchGuestCookies()

        // Try Instagram post JSON endpoint (?__a=1&__d=dis) — mainly for private posts / fallback
        try {
            val url = "https://www.instagram.com/p/$cleanCode/?__a=1&__d=dis"
            val req = Request.Builder().url(url)
            if (!effectiveCookie.isNullOrBlank()) {
                applyAuthenticatedApiHeaders(req, effectiveCookie, "https://www.instagram.com/p/$cleanCode/")
            } else {
                req.header("User-Agent", DESKTOP_CHROME_UA)
                    .header("X-IG-App-ID", APP_ID)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://www.instagram.com/p/$cleanCode/")
                    .header("Accept", "*/*")
            }

            clientWithRedirects.newCall(req.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val mediaNode = root?.getAsJsonObject("graphql")?.getAsJsonObject("shortcode_media")
                        if (mediaNode != null) {
                            val sidecar = extractSidecarSlides(mediaNode)
                            val isVid = mediaNode.get("is_video")?.asBoolean ?: false
                            val disp = mediaNode.get("display_url")?.asString ?: fallbackDisplayUrl
                            val vid = if (isVid) mediaNode.get("video_url")?.asString else null
                            val slides = if (sidecar.isNotEmpty()) {
                                sidecar
                            } else if (disp.isNotBlank() || !vid.isNullOrBlank()) {
                                listOf(
                                    CarouselSlideHint(
                                        position = 0,
                                        mediaType = if (isVid) "video" else "image",
                                        displayUrl = disp,
                                        videoUrl = vid
                                    )
                                )
                            } else emptyList()

                            val capEdges = mediaNode.getAsJsonObject("edge_media_to_caption")?.getAsJsonArray("edges")
                            val caption = capEdges?.firstOrNull()?.asJsonObject?.getAsJsonObject("node")?.get("text")?.asString?.cleanDisplayText()
                            val likes = mediaNode.getAsJsonObject("edge_media_preview_like")?.get("count")?.asInt
                            val comments = mediaNode.getAsJsonObject("edge_media_to_comment")?.get("count")?.asInt
                            val ts = mediaNode.get("taken_at_timestamp")?.asLong?.let { it * 1000L }

                            if (slides.isNotEmpty()) {
                                return PostMediaDetails(
                                    slides = slides,
                                    metadata = ExtractedPostMetadata(
                                        caption = caption?.ifBlank { null },
                                        likeCount = likes,
                                        commentCount = comments,
                                        postedAtMillis = ts,
                                        displayUrl = disp.ifBlank { null }
                                    )
                                )
                            }
                        }

                        // v2 items[] schema (carousel_media / image_versions2 / video_versions)
                        val item = root?.getAsJsonArray("items")?.firstOrNull()?.asJsonObject
                        if (item != null) {
                            val slides = mutableListOf<CarouselSlideHint>()
                            val carouselArr = item.getAsJsonArray("carousel_media")
                            if (carouselArr != null && carouselArr.size() > 0) {
                                carouselArr.forEachIndexed { idx, el ->
                                    val cObj = el.asJsonObject ?: return@forEachIndexed
                                    val vidUrl = cObj.getAsJsonArray("video_versions")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                    val imgObj = cObj.getAsJsonObject("image_versions2")?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                                    val imgUrl = imgObj?.get("url")?.asString ?: ""
                                    val w = imgObj?.get("width")?.asInt ?: 0
                                    val h = imgObj?.get("height")?.asInt ?: 0
                                    if (imgUrl.isNotBlank() || !vidUrl.isNullOrBlank()) {
                                        slides.add(
                                            CarouselSlideHint(
                                                position = idx,
                                                mediaType = if (!vidUrl.isNullOrBlank()) "video" else "image",
                                                displayUrl = imgUrl,
                                                videoUrl = vidUrl,
                                                width = w,
                                                height = h
                                            )
                                        )
                                    }
                                }
                            } else {
                                val vidUrl = item.getAsJsonArray("video_versions")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                val imgObj = item.getAsJsonObject("image_versions2")?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                                val imgUrl = imgObj?.get("url")?.asString ?: fallbackDisplayUrl
                                if (imgUrl.isNotBlank() || !vidUrl.isNullOrBlank()) {
                                    slides.add(
                                        CarouselSlideHint(
                                            position = 0,
                                            mediaType = if (!vidUrl.isNullOrBlank()) "video" else "image",
                                            displayUrl = imgUrl,
                                            videoUrl = vidUrl,
                                            width = imgObj?.get("width")?.asInt ?: 0,
                                            height = imgObj?.get("height")?.asInt ?: 0
                                        )
                                    )
                                }
                            }
                            if (slides.isNotEmpty()) {
                                val caption = item.getAsJsonObject("caption")?.get("text")?.asString?.cleanDisplayText()
                                val likes = item.get("like_count")?.asInt
                                val comments = item.get("comment_count")?.asInt
                                val ts = item.get("taken_at")?.asLong?.let { it * 1000L }
                                val duration = item.get("video_duration")?.asDouble?.toInt()
                                return PostMediaDetails(
                                    slides = slides,
                                    metadata = ExtractedPostMetadata(
                                        caption = caption?.ifBlank { null },
                                        likeCount = likes,
                                        commentCount = comments,
                                        postedAtMillis = ts,
                                        durationSeconds = duration,
                                        displayUrl = slides.first().displayUrl.ifBlank { null }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Post JSON detail fetch skipped for $cleanCode: ${e.message}")
        }

        try {
            val postPageUrl = "https://www.instagram.com/p/$cleanCode/"
            val req = Request.Builder()
                .url(postPageUrl)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")

            if (!effectiveCookie.isNullOrBlank()) {
                req.header("Cookie", effectiveCookie)
            }

            clientWithRedirects.newCall(req.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val parsed = parsePostDetailsFromSsrHtml(html, cleanCode)
                    if (parsed != null && parsed.slides.isNotEmpty()) {
                        return parsed
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Post Desktop SSR fetch skipped for $cleanCode: ${e.message}")
        }

        // 2. Fallback to Embed enrichment
        val enriched = enrichPostViaEmbed(
            InstagramPostItem(
                id = cleanCode,
                shortcode = cleanCode,
                mediaType = if (isVideoHint) "video" else "image",
                displayUrl = fallbackDisplayUrl,
                videoUrl = fallbackVideoUrl
            )
        )
        return if (enriched.displayUrl.isNotBlank() || !enriched.videoUrl.isNullOrBlank()) {
            PostMediaDetails(
                slides = listOf(
                    CarouselSlideHint(
                        position = 0,
                        mediaType = enriched.mediaType,
                        displayUrl = enriched.displayUrl,
                        videoUrl = enriched.videoUrl
                    )
                ),
                metadata = ExtractedPostMetadata(
                    caption = enriched.caption.ifBlank { null },
                    likeCount = enriched.likeCount.takeIf { it > 0 },
                    commentCount = enriched.commentCount.takeIf { it > 0 },
                    postedAtMillis = enriched.timestamp.takeIf { it > 0L },
                    displayUrl = enriched.displayUrl.ifBlank { null }
                )
            )
        } else null
    }

    fun enrichPostViaEmbed(post: InstagramPostItem): InstagramPostItem {
        var displayUrl = post.displayUrl
        var videoUrl = post.videoUrl
        var mediaType = post.mediaType
        var caption = post.caption
        var timestamp = post.timestamp
        var likeCount = post.likeCount
        var commentCount = post.commentCount

        val embedUrls = if (post.mediaType == "video") {
            listOf(
                "https://www.instagram.com/reel/${post.shortcode}/embed/captioned/",
                "https://www.instagram.com/p/${post.shortcode}/embed/captioned/"
            )
        } else {
            listOf("https://www.instagram.com/p/${post.shortcode}/embed/captioned/")
        }

        for (embedUrl in embedUrls) {
            try {
                val req = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", DESKTOP_CHROME_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.instagram.com/")
                    .build()

                clientWithRedirects.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val html = resp.body?.string() ?: return@use

                    // 1. High-res image URL
                    val imgPatterns = listOf(
                        Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\""),
                        Pattern.compile("class=\"EmbeddedMediaImage\"[^>]*?src=\"([^\"]+)\"")
                    )
                    for (p in imgPatterns) {
                        val m = p.matcher(html)
                        if (m.find()) {
                            val raw = m.group(1)
                            if (!raw.isNullOrBlank()) {
                                val cleaned = unescapeHtml(unescapeJson(raw))
                                if (cleaned.startsWith("http")) {
                                    displayUrl = cleaned
                                    break
                                }
                            }
                        }
                    }

                    // 2. Video URL
                    val vidPatterns = listOf(
                        Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\""),
                        Pattern.compile("<video[^>]*?src=\"([^\"]+)\"")
                    )
                    for (p in vidPatterns) {
                        val m = p.matcher(html)
                        if (m.find()) {
                            val raw = m.group(1)
                            if (!raw.isNullOrBlank()) {
                                val cleaned = unescapeHtml(unescapeJson(raw))
                                if (cleaned.startsWith("http")) {
                                    videoUrl = cleaned
                                    mediaType = "video"
                                    break
                                }
                            }
                        }
                    }

                    // 3. Full caption
                    val jsonCapMatcher = Pattern.compile(
                        "\"edge_media_to_caption\"\\s*:\\s*\\{\\s*\"edges\"\\s*:\\s*\\[\\s*\\{\\s*\"node\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
                    ).matcher(html)
                    if (jsonCapMatcher.find()) {
                        val extracted = unescapeJson(jsonCapMatcher.group(1) ?: "").cleanDisplayText()
                        if (extracted.isNotBlank()) caption = extracted
                    } else {
                        val divCapMatcher = Pattern.compile(
                            "<div[^>]*class=\"[^\"]*Caption[^\"]*\"[^>]*>(.*?)</div>",
                            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
                        ).matcher(html)
                        if (divCapMatcher.find()) {
                            val inner = divCapMatcher.group(1) ?: ""
                            val stripped = inner
                                .replace(Regex("<a[^>]*class=\"[^\"]*CaptionUsername[^\"]*\"[^>]*>.*?</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                                .replace(Regex("<div[^>]*class=\"[^\"]*CaptionComments[^\"]*\"[^>]*>.*?</div>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                                .replace(Regex("<[^>]+>"), " ")
                            val cleaned = unescapeHtml(stripped).replace(Regex("[ \\t]+"), " ").trim().cleanDisplayText()
                            if (cleaned.isNotBlank()) caption = cleaned
                        }
                    }

                    // 4. Timestamp
                    val tsMatcher = Pattern.compile("\"taken_at_timestamp\"\\s*:\\s*(\\d+)").matcher(html)
                    if (tsMatcher.find()) {
                        val sec = tsMatcher.group(1)?.toLongOrNull()
                        if (sec != null && sec > 0L) timestamp = sec * 1000L
                    } else {
                        val timeMatcher = Pattern.compile("<time[^>]*datetime=\"([^\"]+)\"").matcher(html)
                        if (timeMatcher.find()) {
                            parseIsoTimestamp(timeMatcher.group(1) ?: "")?.let { timestamp = it }
                        }
                    }

                    // 5. Likes & Comments
                    if (likeCount == 0) {
                        val likeMatcher = Pattern.compile("\"edge_media_preview_like\"\\s*:\\s*\\{\\s*\"count\"\\s*:\\s*(\\d+)").matcher(html)
                        if (likeMatcher.find()) {
                            likeCount = likeMatcher.group(1)?.toIntOrNull() ?: 0
                        }
                    }
                    if (commentCount == 0) {
                        val comMatcher = Pattern.compile("\"edge_media_to_comment\"\\s*:\\s*\\{\\s*\"count\"\\s*:\\s*(\\d+)").matcher(html)
                        if (comMatcher.find()) {
                            commentCount = comMatcher.group(1)?.toIntOrNull() ?: 0
                        }
                    }

                    return@use
                }
                if (caption.isNotBlank() || !videoUrl.isNullOrBlank()) break
            } catch (e: Exception) {
                Log.w(TAG, "Embed fetch failed for ${post.shortcode}: ${e.message}")
            }
        }

        if (caption.isBlank() || displayUrl.isBlank() || displayUrl.contains("/media/?size=")) {
            try {
                val encodedPostUrl = URLEncoder.encode("https://www.instagram.com/p/${post.shortcode}/", "UTF-8")
                val oembedUrls = listOf(
                    "https://api.instagram.com/oembed/?url=$encodedPostUrl",
                    "https://www.instagram.com/api/v1/oembed/?url=$encodedPostUrl"
                )
                for (oUrl in oembedUrls) {
                    val req = Request.Builder()
                        .url(oUrl)
                        .header("User-Agent", DESKTOP_CHROME_UA)
                        .header("Accept", "application/json")
                        .build()
                    clientWithRedirects.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: return@use
                            val json = gson.fromJson(body, JsonObject::class.java) ?: return@use
                            val thumb = json.get("thumbnail_url")?.asString
                            val title = json.get("title")?.asString?.cleanDisplayText()
                            if (!thumb.isNullOrBlank() && (displayUrl.isBlank() || displayUrl.contains("/media/?size="))) {
                                displayUrl = thumb
                            }
                            if (!title.isNullOrBlank() && caption.isBlank()) {
                                caption = title
                            }
                        }
                    }
                    if (caption.isNotBlank() && displayUrl.isNotBlank() && !displayUrl.contains("/media/?size=")) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "OEmbed fallback failed for ${post.shortcode}: ${e.message}")
            }
        }

        return post.copy(
            mediaType = mediaType,
            displayUrl = displayUrl,
            videoUrl = videoUrl,
            caption = caption,
            timestamp = timestamp,
            likeCount = likeCount,
            commentCount = commentCount
        )
    }

    private fun parseIsoTimestamp(raw: String): Long? {
        if (raw.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun unescapeHtml(input: String): String {
        var out = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        val hexEntity = Pattern.compile("&#x([0-9a-fA-F]+);")
        val hm = hexEntity.matcher(out)
        val sbHex = StringBuffer()
        while (hm.find()) {
            val code = hm.group(1)?.toIntOrNull(16)
            hm.appendReplacement(sbHex, if (code != null) String(Character.toChars(code)) else "")
        }
        hm.appendTail(sbHex)
        out = sbHex.toString()

        val decEntity = Pattern.compile("&#(\\d+);")
        val dm = decEntity.matcher(out)
        val sbDec = StringBuffer()
        while (dm.find()) {
            val code = dm.group(1)?.toIntOrNull()
            dm.appendReplacement(sbDec, if (code != null) String(Character.toChars(code)) else "")
        }
        dm.appendTail(sbDec)
        return sbDec.toString()
    }

    fun fetchRecentPosts(userId: String?, username: String, cookieString: String?, limit: Int = 24): List<InstagramPostItem> {
        return runBlocking {
            when (val res = fetchRecentPostsDetailed(userId, username, cookieString, limit)) {
                is PostFetchResult.Success -> res.posts
                else -> emptyList()
            }
        }
    }

    suspend fun fetchAllPostsPaginated(
        userId: String?,
        username: String,
        cookieString: String?,
        maxPosts: Int = 0, // 0 = Unlimited (fetch all)
        expectedTotal: Long = 0L,
        isPrivate: Boolean = false,
        pageDelayMs: Long = 250L,
        onPageDiscovered: suspend (List<InstagramPostItem>, Int) -> Unit = { _, _ -> }
    ): PostFetchResult {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val hasRealSession = !cookieString.isNullOrBlank()
        if (isPrivate && !hasRealSession) return PostFetchResult.RequiresSession

        val targetLimit = when {
            maxPosts > 0 -> maxPosts
            expectedTotal > 0L -> expectedTotal.coerceAtMost(2000L).toInt()
            else -> 1000
        }
        val allByCode = linkedMapOf<String, InstagramPostItem>()
        var sawRateLimit = false

        suspend fun emitNew(items: List<InstagramPostItem>) {
            val fresh = mutableListOf<InstagramPostItem>()
            for (item in items) {
                if (allByCode.size >= targetLimit) break
                val prev = allByCode[item.shortcode]
                if (prev == null) {
                    allByCode[item.shortcode] = item
                    fresh.add(item)
                } else if (prev.likeCount == 0 && item.likeCount > 0) {
                    val merged = prev.copy(
                        likeCount = item.likeCount,
                        commentCount = item.commentCount,
                        caption = item.caption.ifBlank { prev.caption },
                        timestamp = if (item.timestamp > 0L) item.timestamp else prev.timestamp,
                        slides = if (item.slides.isNotEmpty()) item.slides else prev.slides,
                        videoUrl = item.videoUrl ?: prev.videoUrl
                    )
                    allByCode[item.shortcode] = merged
                    fresh.add(merged)
                }
            }
            if (fresh.isNotEmpty()) {
                onPageDiscovered(fresh, allByCode.size)
            }
        }

        AppLogger.i(TAG, "fetchAllPostsPaginated(@$cleanUser) start: targetLimit=$targetLimit expected=$expectedTotal hasSession=$hasRealSession")

        // Stage 1: Instant Zero-Login Desktop SSR Page 1 (public profiles)
        if (!isPrivate && !(hasRealSession && System.currentTimeMillis() < anonymousSsrRleUntilMs)) {
            try {
                val page1 = fetchPostsViaDesktopSsr(cleanUser, cookieString = null, limit = 24)
                if (page1.isNotEmpty()) {
                    emitNew(page1)
                    AppLogger.i(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage1-SSR: ${page1.size} posts")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage1-SSR error: ${e.message}")
            }
        }

        // If we already captured all expected posts, stop immediately
        if (expectedTotal > 0L && allByCode.size.toLong() >= expectedTotal) {
            return PostFetchResult.Success(allByCode.values.toList())
        }

        // Stage 2: Fast Authenticated v1 Feed Cursor Pagination (33 posts/page via next_max_id)
        if (hasRealSession && allByCode.size < targetLimit) {
            val viewerDsId = extractDsUserId(cookieString)
            var resolvedUid = userId?.takeIf { it.isNotBlank() && it != viewerDsId }
            if (resolvedUid == null) {
                resolvedUid = try {
                    if (System.currentTimeMillis() >= mobileApiBlockedUntilMs) fetchViaMobileUsernameInfo(cleanUser, cookieString!!)?.instagramId else null
                } catch (_: Exception) { null }
                    ?: try { if (System.currentTimeMillis() >= anonymousSsrRleUntilMs) fetchViaHtml(cleanUser)?.instagramId else null } catch (_: Exception) { null }
            }

            var nextMaxId: String? = null
            var pageNum = 0
            val maxPages = ((targetLimit + 32) / 33).coerceIn(1, 65)

            while (pageNum < maxPages && allByCode.size < targetLimit) {
                pageNum++
                val cursorQuery = if (!nextMaxId.isNullOrBlank()) "&max_id=${URLEncoder.encode(nextMaxId, "UTF-8")}" else ""
                val candidates = buildList {
                    if (!resolvedUid.isNullOrBlank() && System.currentTimeMillis() >= mobileApiBlockedUntilMs) {
                        add("https://i.instagram.com/api/v1/feed/user/$resolvedUid/?count=33$cursorQuery" to IG_ANDROID_APP_UA)
                    }
                    add("https://www.instagram.com/api/v1/feed/user/$cleanUser/username/?count=33$cursorQuery" to DESKTOP_CHROME_UA)
                }

                var pageItems: List<InstagramPostItem> = emptyList()
                var pageMore = false
                var pageNextCursor: String? = null

                for ((feedUrl, ua) in candidates) {
                    try {
                        val req = applyAuthenticatedApiHeaders(
                            builder = Request.Builder().url(feedUrl),
                            cookieString = cookieString!!,
                            referer = "https://www.instagram.com/$cleanUser/",
                            userAgent = ua
                        )
                        clientWithRedirects.newCall(req.build()).execute().use { resp ->
                            if (resp.code == 429) {
                                sawRateLimit = true
                                AppLogger.w(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage2 page#$pageNum HTTP 429")
                                return@use
                            }
                            if (resp.isSuccessful) {
                                val body = resp.body?.string()?.trimStart().orEmpty()
                                if (body.startsWith("{")) {
                                    val root = gson.fromJson(body, JsonObject::class.java)
                                    val itemsArr = root?.getAsJsonArray("items")
                                    if (itemsArr != null && itemsArr.size() > 0) {
                                        val parsed = mutableListOf<InstagramPostItem>()
                                        for (itemEl in itemsArr) {
                                            val node = itemEl.asJsonObject ?: continue
                                            if (isSponsoredOrForeignPostNode(node, cleanUser)) continue
                                            val code = node.get("code")?.takeIf { !it.isJsonNull }?.asString ?: continue
                                            val mediaTypeInt = node.get("media_type")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                                            val slides = extractPolarisCarouselSlides(node)
                                            val isCarousel = mediaTypeInt == 8 || slides.size > 1
                                            val isVideo = mediaTypeInt == 2
                                            val displayUrl = node.getAsJsonObject("image_versions2")
                                                ?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                                ?: slides.firstOrNull()?.displayUrl
                                                ?: ""
                                            val videoUrl = node.getAsJsonArray("video_versions")
                                                ?.firstOrNull()?.asJsonObject?.get("url")?.asString
                                            val caption = node.getAsJsonObject("caption")?.get("text")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
                                            val likes = node.get("like_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                            val comments = node.get("comment_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                            val takenAtSec = node.get("taken_at")?.takeIf { !it.isJsonNull }?.asLong ?: (System.currentTimeMillis() / 1000L)
                                            parsed.add(
                                                InstagramPostItem(
                                                    id = code,
                                                    shortcode = code,
                                                    mediaType = when {
                                                        isCarousel -> "carousel"
                                                        isVideo -> "video"
                                                        else -> "image"
                                                    },
                                                    displayUrl = displayUrl.ifBlank { "https://www.instagram.com/p/$code/media/?size=l" },
                                                    videoUrl = videoUrl,
                                                    caption = caption,
                                                    timestamp = takenAtSec * 1000L,
                                                    likeCount = likes,
                                                    commentCount = comments,
                                                    slides = slides
                                                )
                                            )
                                        }
                                        pageItems = parsed
                                        pageMore = root.get("more_available")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                        pageNextCursor = root.get("next_max_id")?.takeIf { !it.isJsonNull }?.asString
                                    }
                                }
                            } else {
                                AppLogger.d(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage2 page#$pageNum HTTP ${resp.code}")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage2 page#$pageNum error: ${e.message}")
                    }
                    if (pageItems.isNotEmpty()) break
                }

                if (pageItems.isEmpty()) break
                emitNew(pageItems)
                AppLogger.i(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage2 page#$pageNum: +${pageItems.size} (total=${allByCode.size}/$targetLimit)")

                if (!pageMore || pageNextCursor.isNullOrBlank()) break
                nextMaxId = pageNextCursor
                if (pageDelayMs > 0L) {
                    kotlinx.coroutines.delay(pageDelayMs)
                }
            }
        }

        val stillMissingPages = allByCode.size < targetLimit &&
            (expectedTotal == 0L || allByCode.size.toLong() < expectedTotal) &&
            (allByCode.size <= 12 || expectedTotal > allByCode.size.toLong())
        if (stillMissingPages && headlessScraper != null && (!isPrivate || hasRealSession)) {
            try {
                val scrollDelay = (pageDelayMs * 3L).coerceIn(700L, 1800L)
                val scrolled = headlessScraper.scrapePaginatedPosts(
                    username = cleanUser,
                    maxPosts = targetLimit,
                    cookieString = if (hasRealSession) cookieString else null,
                    scrollIntervalMs = scrollDelay
                ) { batch ->
                    runBlocking { emitNew(batch) }
                }
                if (scrolled.isNotEmpty()) {
                    emitNew(scrolled)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchAllPostsPaginated(@$cleanUser) Stage3-WebView error: ${e.message}")
            }
        }

        // Stage 4: Fallback to existing single-page fetchRecentPostsDetailed if still empty
        if (allByCode.isEmpty()) {
            return fetchRecentPostsDetailed(userId, cleanUser, cookieString, limit = 24, isPrivate = isPrivate)
        }

        AppLogger.i(TAG, "fetchAllPostsPaginated(@$cleanUser) DONE: total=${allByCode.size} posts discovered")
        return PostFetchResult.Success(allByCode.values.toList())
    }

    suspend fun fetchPostComments(
        shortcode: String,
        cookieString: String?,
        maxComments: Int = 500,
        reportedTotalCount: Int = 0,
        pageDelayMs: Long = 280L,
        onBatchDiscovered: suspend (List<InstagramCommentItem>, Int) -> Unit = { _, _ -> }
    ): CommentFetchResult {
        val cleanCode = shortcode.trim().substringBefore('/').substringBefore('?')
        if (cleanCode.isBlank()) {
            return CommentFetchResult(emptyList(), errorMessage = "Empty shortcode")
        }
        val mediaId = com.instalogger.app.engine.comments.CommentTreeEngine.shortcodeToMediaId(cleanCode)
        val hasSession = !cookieString.isNullOrBlank()
        val byId = LinkedHashMap<String, InstagramCommentItem>()
        var sawRateLimit = false
        var wasTruncated = false
        var source = "none"

        suspend fun mergeBatch(items: List<InstagramCommentItem>) {
            val fresh = mutableListOf<InstagramCommentItem>()
            for (item in items) {
                if (byId.size >= maxComments && !byId.containsKey(item.id)) {
                    wasTruncated = true
                    break
                }
                val prev = byId[item.id]
                if (prev == null) {
                    byId[item.id] = item
                    fresh.add(item)
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
                    byId[item.id] = merged
                    fresh.add(merged)
                }
            }
            if (fresh.isNotEmpty()) {
                onBatchDiscovered(fresh, byId.size)
            }
        }

        AppLogger.i(TAG, "fetchPostComments($cleanCode, mediaId=$mediaId) start (hasSession=$hasSession, max=$maxComments)")

        // Tier 1: Authenticated v1 REST Comments + Child Replies pagination
        if (hasSession && !mediaId.isNullOrBlank()) {
            var nextMinId: String? = null
            var page = 0
            val maxPages = ((maxComments + 19) / 20).coerceIn(1, 35)
            val parentsNeedingReplies = mutableListOf<InstagramCommentItem>()

            while (page < maxPages && byId.size < maxComments) {
                page++
                val cursorParam = if (!nextMinId.isNullOrBlank()) {
                    "&min_id=${URLEncoder.encode(nextMinId, "UTF-8")}"
                } else ""
                val url = "https://www.instagram.com/api/v1/media/$mediaId/comments/?can_support_threading=true&permalink_enabled=false$cursorParam"
                try {
                    val req = applyAuthenticatedApiHeaders(
                        builder = Request.Builder().url(url),
                        cookieString = cookieString!!,
                        referer = "https://www.instagram.com/p/$cleanCode/"
                    )
                    var shouldStop = false
                    var retryAfterMs = 0L
                    clientWithRedirects.newCall(req.build()).execute().use { resp ->
                        if (resp.code == 429) {
                            sawRateLimit = true
                            val retrySec = resp.header("Retry-After")?.toLongOrNull() ?: 3L
                            retryAfterMs = (retrySec * 1000L).coerceIn(1500L, 6000L)
                            AppLogger.w(TAG, "fetchPostComments($cleanCode) Tier1 page#$page HTTP 429 (Retry-After=${retrySec}s)")
                            shouldStop = true
                            return@use
                        }
                        if (!resp.isSuccessful) {
                            AppLogger.d(TAG, "fetchPostComments($cleanCode) Tier1 page#$page HTTP ${resp.code}")
                            shouldStop = true
                            return@use
                        }
                        val body = resp.body?.string()?.trimStart().orEmpty()
                        if (!body.startsWith("{")) {
                            shouldStop = true
                            return@use
                        }
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val arr = root?.getAsJsonArray("comments")
                        if (arr == null || arr.size() == 0) {
                            shouldStop = true
                            return@use
                        }
                        val pageBatch = mutableListOf<InstagramCommentItem>()
                        for (el in arr) {
                            val obj = el.asJsonObject ?: continue
                            val parsed = parseCommentNode(obj, fallbackParentId = null) ?: continue
                            pageBatch.add(parsed)
                            val previewChildren = parseInlineChildComments(obj, parsed.id)
                            pageBatch.addAll(previewChildren)
                            if (parsed.replyCount > previewChildren.size) {
                                parentsNeedingReplies.add(parsed)
                            }
                        }
                        if (pageBatch.isNotEmpty()) {
                            source = "rest_v1"
                            runBlocking { mergeBatch(pageBatch) }
                        }
                        val hasMore = root.get("has_more_comments")?.takeIf { !it.isJsonNull }?.asBoolean
                            ?: root.get("has_more_headload_comments")?.takeIf { !it.isJsonNull }?.asBoolean
                            ?: false
                        val cursor = root.get("next_min_id")?.takeIf { !it.isJsonNull }?.asString
                            ?: root.get("next_max_id")?.takeIf { !it.isJsonNull }?.asString
                        if (!hasMore || cursor.isNullOrBlank()) {
                            shouldStop = true
                        } else {
                            nextMinId = cursor
                        }
                    }
                    if (retryAfterMs > 0L) {
                        kotlinx.coroutines.delay(retryAfterMs)
                    }
                    if (shouldStop) break
                    if (pageDelayMs > 0L) kotlinx.coroutines.delay(pageDelayMs)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "fetchPostComments($cleanCode) Tier1 page#$page error: ${e.message}")
                    break
                }
            }

            // Fetch full threaded replies for parent comments that have unexpanded child comments
            for (parent in parentsNeedingReplies) {
                if (byId.size >= maxComments || sawRateLimit) {
                    wasTruncated = true
                    break
                }
                var childCursor: String? = null
                var childPage = 0
                while (childPage < 10 && byId.size < maxComments) {
                    childPage++
                    val cursorQ = if (!childCursor.isNullOrBlank()) {
                        "?max_id=${URLEncoder.encode(childCursor, "UTF-8")}"
                    } else ""
                    val childUrl = "https://www.instagram.com/api/v1/media/$mediaId/comments/${parent.id}/child_comments/$cursorQ"
                    try {
                        val req = applyAuthenticatedApiHeaders(
                            builder = Request.Builder().url(childUrl),
                            cookieString = cookieString!!,
                            referer = "https://www.instagram.com/p/$cleanCode/"
                        )
                        var stopChildren = false
                        clientWithRedirects.newCall(req.build()).execute().use { resp ->
                            if (resp.code == 429) {
                                sawRateLimit = true
                                stopChildren = true
                                return@use
                            }
                            if (!resp.isSuccessful) {
                                stopChildren = true
                                return@use
                            }
                            val body = resp.body?.string()?.trimStart().orEmpty()
                            if (!body.startsWith("{")) {
                                stopChildren = true
                                return@use
                            }
                            val root = gson.fromJson(body, JsonObject::class.java)
                            val arr = root?.getAsJsonArray("child_comments")
                            if (arr == null || arr.size() == 0) {
                                stopChildren = true
                                return@use
                            }
                            val replies = mutableListOf<InstagramCommentItem>()
                            for (el in arr) {
                                el.asJsonObject?.let { parseCommentNode(it, fallbackParentId = parent.id)?.let(replies::add) }
                            }
                            if (replies.isNotEmpty()) {
                                runBlocking { mergeBatch(replies) }
                            }
                            val hasMoreChild = root.get("has_more_tail_child_comments")?.takeIf { !it.isJsonNull }?.asBoolean
                                ?: root.get("has_more_head_child_comments")?.takeIf { !it.isJsonNull }?.asBoolean
                                ?: false
                            val nextChild = root.get("next_max_child_cursor")?.takeIf { !it.isJsonNull }?.asString
                                ?: root.get("next_min_child_cursor")?.takeIf { !it.isJsonNull }?.asString
                            if (!hasMoreChild || nextChild.isNullOrBlank()) {
                                stopChildren = true
                            } else {
                                childCursor = nextChild
                            }
                        }
                        if (stopChildren) break
                        if (pageDelayMs > 0L) kotlinx.coroutines.delay(pageDelayMs)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "child_comments(${parent.id}) error: ${e.message}")
                        break
                    }
                }
            }
        }

        if (byId.isEmpty() && !(hasSession && System.currentTimeMillis() < anonymousSsrRleUntilMs)) {
            try {
                val req = Request.Builder()
                    .url("https://www.instagram.com/p/$cleanCode/")
                    .header("User-Agent", DESKTOP_CHROME_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                val html = clientWithRedirects.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                }
                if (html.isNotBlank()) {
                    val ssrComments = parseCommentsFromSsrHtml(html)
                    if (ssrComments.isNotEmpty()) {
                        source = "desktop_ssr"
                        mergeBatch(ssrComments)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchPostComments($cleanCode) Tier2-SSR error: ${e.message}")
            }
        }

        // Tier 3: Headless Chromium WebView Comment & Reply Interceptor
        val needWebViewFallback = byId.isEmpty() ||
            (reportedTotalCount > 0 && byId.size < minOf(reportedTotalCount, maxComments) && source != "rest_v1")
        if (needWebViewFallback && headlessScraper != null) {
            try {
                val wvComments = headlessScraper.scrapePostComments(
                    shortcode = cleanCode,
                    cookieString = cookieString,
                    maxComments = maxComments
                ) { batch ->
                    runBlocking { mergeBatch(batch) }
                }
                if (wvComments.isNotEmpty()) {
                    source = if (source == "none") "webview_graphql" else "$source+webview"
                    mergeBatch(wvComments)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchPostComments($cleanCode) Tier3-WebView error: ${e.message}")
            }
        }

        val finalList = byId.values.toList()
        val isPartial = sawRateLimit ||
            wasTruncated ||
            !hasSession ||
            (reportedTotalCount > 0 && finalList.size < reportedTotalCount)
        val status = if (isPartial) "partial" else "complete"

        AppLogger.i(TAG, "fetchPostComments($cleanCode) DONE: ${finalList.size} comments (status=$status, source=$source)")
        return CommentFetchResult(
            comments = finalList,
            mediaId = mediaId,
            collectionStatus = status,
            source = source,
            rateLimited = sawRateLimit
        )
    }

    internal fun parseCommentsFromSsrHtml(html: String): List<InstagramCommentItem> {
        val out = LinkedHashMap<String, InstagramCommentItem>()
        val scriptPattern = Pattern.compile("<script[^>]*type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)

        fun collect(node: JsonObject, fallbackParentId: String?) {
            val parsed = parseCommentNode(node, fallbackParentId) ?: return
            out.putIfAbsent(parsed.id, parsed)
            parseInlineChildComments(node, parsed.id).forEach { child ->
                out.putIfAbsent(child.id, child)
            }
        }

        fun walk(elem: JsonElement?) {
            if (elem == null) return
            when {
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    val connKeys = listOf(
                        "xdt_api__v1__media__media_id__comments__connection",
                        "edge_media_to_parent_comment",
                        "edge_media_to_comment"
                    )
                    for (k in connKeys) {
                        obj.getAsJsonObject(k)?.getAsJsonArray("edges")?.forEach { edgeEl ->
                            edgeEl.asJsonObject?.getAsJsonObject("node")?.let { collect(it, null) }
                        }
                    }
                    obj.getAsJsonArray("comments")?.forEach { cEl ->
                        cEl.asJsonObject?.let { collect(it, null) }
                    }
                    for ((_, v) in obj.entrySet()) walk(v)
                }
                elem.isJsonArray -> {
                    for (child in elem.asJsonArray) walk(child)
                }
            }
        }

        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            if (!raw.contains("comments__connection") &&
                !raw.contains("edge_media_to_parent_comment") &&
                !raw.contains("comment_like_count")
            ) continue
            try {
                walk(gson.fromJson(raw, JsonElement::class.java))
            } catch (_: Exception) {
            }
        }
        return out.values.toList()
    }

    private fun parseCommentNode(obj: JsonObject, fallbackParentId: String?): InstagramCommentItem? {
        val id = obj.get("pk")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("id")?.takeIf { !it.isJsonNull }?.asString
            ?: return null
        val text = obj.get("text")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
        val userObj = obj.getAsJsonObject("user") ?: obj.getAsJsonObject("owner")
        val authorUsername = userObj?.get("username")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (text.isBlank() && authorUsername.isBlank()) return null

        val parentId = obj.get("parent_comment_id")?.takeIf { !it.isJsonNull }?.asString?.trim()?.ifBlank { null }
            ?: fallbackParentId
        val authorId = userObj?.get("pk")?.takeIf { !it.isJsonNull }?.asString
            ?: userObj?.get("id")?.takeIf { !it.isJsonNull }?.asString
        val authorFullName = userObj?.get("full_name")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText().orEmpty()
        val authorAvatar = userObj?.get("profile_pic_url")?.takeIf { !it.isJsonNull }?.asString
        val isVerified = userObj?.get("is_verified")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val likes = obj.get("comment_like_count")?.takeIf { !it.isJsonNull }?.asInt
            ?: obj.getAsJsonObject("edge_liked_by")?.get("count")?.takeIf { !it.isJsonNull }?.asInt
            ?: 0
        val replyCount = obj.get("child_comment_count")?.takeIf { !it.isJsonNull }?.asInt
            ?: obj.getAsJsonObject("edge_threaded_comments")?.get("count")?.takeIf { !it.isJsonNull }?.asInt
            ?: 0
        val createdRaw = obj.get("created_at_utc")?.takeIf { !it.isJsonNull }?.asLong
            ?: obj.get("created_at")?.takeIf { !it.isJsonNull }?.asLong
            ?: 0L
        val createdAtMs = when {
            createdRaw > 10_000_000_000L -> createdRaw
            createdRaw > 0L -> createdRaw * 1000L
            else -> System.currentTimeMillis()
        }
        val hasGif = obj.has("giphy_media_info") && !obj.get("giphy_media_info").isJsonNull

        return InstagramCommentItem(
            id = id,
            parentCommentId = parentId,
            authorUsername = authorUsername.ifBlank { "instagram_user" },
            authorId = authorId,
            authorFullName = authorFullName,
            authorAvatarUrl = authorAvatar,
            isAuthorVerified = isVerified,
            text = text,
            likeCount = likes,
            replyCount = replyCount,
            createdAt = createdAtMs,
            hasGifOrMedia = hasGif
        )
    }

    private fun parseInlineChildComments(obj: JsonObject, parentId: String): List<InstagramCommentItem> {
        val children = mutableListOf<InstagramCommentItem>()
        val previewArr = obj.getAsJsonArray("preview_child_comments") ?: obj.getAsJsonArray("child_comments")
        previewArr?.forEach { el ->
            el.asJsonObject?.let { parseCommentNode(it, fallbackParentId = parentId)?.let(children::add) }
        }
        obj.getAsJsonObject("edge_threaded_comments")?.getAsJsonArray("edges")?.forEach { edgeEl ->
            edgeEl.asJsonObject?.getAsJsonObject("node")?.let { parseCommentNode(it, fallbackParentId = parentId)?.let(children::add) }
        }
        return children
    }

    companion object {
        private const val TAG = "InstagramEngine"
        private const val APP_ID = "936619743392459"
        private const val ASBD_ID = "129477"
        private const val GUEST_COOKIE_TTL_MS = 20 * 60 * 1000L
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val WEBVIEW_MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
        private const val IG_ANDROID_APP_UA = "Instagram 350.0.0.0.0 Android (34/14; 420dpi; 1080x2400; Google; Pixel 8; shiba; zuma; en_US; 634000000)"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
