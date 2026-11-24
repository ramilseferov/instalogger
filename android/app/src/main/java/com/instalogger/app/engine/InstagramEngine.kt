package com.instalogger.app.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.instalogger.app.engine.media.CarouselSlideHint
import com.instalogger.app.engine.media.ExtractedPostMetadata
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
    val profileHash: String? = null
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

class InstagramEngine(context: Context? = null) {

    private val headlessScraper: HeadlessInstagramScraper? = context?.let { HeadlessInstagramScraper(it) }
    private val guestCookieLock = Any()
    @Volatile
    private var cachedGuestCookies: String? = null
    @Volatile
    private var guestCookiesFetchedAt: Long = 0L

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

        // 1. First attempt: Direct Navigation HTML Scraping (Browser navigation mode extracts embedded GraphQL queries)
        try {
            val htmlSnapshot = fetchViaHtml(cleanUsername, cookieString)
            if (htmlSnapshot != null) {
                return Result.success(htmlSnapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML navigation attempt failed for @$cleanUsername: ${e.message}")
        }

        // 2. Second attempt: Instagram Web Profile Info API
        try {
            val apiSnapshot = fetchViaWebApi(cleanUsername, cookieString)
            if (apiSnapshot != null) {
                return Result.success(apiSnapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Web API attempt failed for @$cleanUsername: ${e.message}")
        }

        // 3. Third attempt: GraphQL Hash-based Query Fallback
        try {
            val hashSnapshot = fetchViaGraphQLHash(cleanUsername, cookieString)
            if (hashSnapshot != null) {
                return Result.success(hashSnapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL Hash attempt failed for @$cleanUsername: ${e.message}")
        }

        return Result.failure(Exception("Profil məlumatları alına bilmədi. İnternet əlaqəsini və ya Instagram sessiyasını yoxlayın."))
    }

    private fun fetchViaHtml(username: String, cookieString: String?): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/$username/"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code != 200) return null
            val html = response.body?.string() ?: return null

            var followers = 0L
            var following = 0L
            var posts = 0L
            var id: String? = null
            var fullName = ""
            var bio = ""
            var isPrivate = false
            var picUrl: String? = null

            // 1. Extract follower_count
            val followerPattern = Pattern.compile("\"follower_count\":\\s*(\\d+)")
            val followerMatcher = followerPattern.matcher(html)
            if (followerMatcher.find()) {
                followers = followerMatcher.group(1)?.toLongOrNull() ?: 0L
            }

            // 2. Extract following_count
            val followingPattern = Pattern.compile("\"following_count\":\\s*(\\d+)")
            val followingMatcher = followingPattern.matcher(html)
            if (followingMatcher.find()) {
                following = followingMatcher.group(1)?.toLongOrNull() ?: 0L
            }

            // 3. Extract pk / ID (multi-pattern fallback)
            val pkPatterns = listOf(
                Pattern.compile("\"pk\":\\s*\"?(\\d+)\"?"),
                Pattern.compile("\"profile_id\":\\s*\"?(\\d+)\"?"),
                Pattern.compile("\"id\":\\s*\"?(\\d{6,})\"?"),
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
            val privatePattern = Pattern.compile("\"is_private\":\\s*(true|false)")
            val privateMatcher = privatePattern.matcher(html)
            if (privateMatcher.find()) {
                isPrivate = privateMatcher.group(1).equals("true", ignoreCase = true)
            }

            // 5. Extract full_name
            val namePattern = Pattern.compile("\"full_name\":\\s*\"([^\"]*)\"")
            val nameMatcher = namePattern.matcher(html)
            if (nameMatcher.find()) {
                fullName = unescapeJson(nameMatcher.group(1) ?: "")
            }

            // 6. Extract biography
            val bioPattern = Pattern.compile("\"biography\":\\s*\"([^\"]*)\"")
            val bioMatcher = bioPattern.matcher(html)
            if (bioMatcher.find()) {
                bio = unescapeJson(bioMatcher.group(1) ?: "")
            }

            // 7. Extract profile_pic_url
            val picPattern = Pattern.compile("\"profile_pic_url_hd\":\"([^\"]+)\"|\"profile_pic_url\":\"([^\"]+)\"")
            val picMatcher = picPattern.matcher(html)
            if (picMatcher.find()) {
                val rawUrl = picMatcher.group(1) ?: picMatcher.group(2)
                if (rawUrl != null) {
                    picUrl = unescapeJson(rawUrl)
                }
            }

            // 8. Extract media / post count (multi-pattern fallback)
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
                        // 1) Match multilingual or standard count formats: "X Followers, Y Following, Z Posts"
                        val countPattern = Pattern.compile("([\\d,.]+[KkMmBb]?)\\s+(?:Followers|Takipçi|Подписчик|İzləyici)[^,;]*[,;]\\s*([\\d,.]+[KkMmBb]?)\\s+(?:Following|Takip|Подписк|İzlənilən)[^,;]*[,;]\\s*([\\d,.]+[KkMmBb]?)\\s+(?:Posts|Gönderi|Публикац|Paylaşım)", Pattern.CASE_INSENSITIVE)
                        val countMatcher = countPattern.matcher(desc)
                        if (countMatcher.find()) {
                            if (followers == 0L) followers = parseFormattedNumber(countMatcher.group(1) ?: "0")
                            if (following == 0L) following = parseFormattedNumber(countMatcher.group(2) ?: "0")
                            if (posts == 0L) posts = parseFormattedNumber(countMatcher.group(3) ?: "0")
                            break
                        } else {
                            // 2) Generic 3-part fallback: "X ..., Y ..., Z ..."
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
                val titlePattern = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"")
                val titleMatcher = titlePattern.matcher(html)
                if (titleMatcher.find()) {
                    val title = titleMatcher.group(1) ?: ""
                    fullName = title.substringBefore("(").trim()
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
                profilePicUrl = picUrl
            )
        }
    }

    private fun fetchViaWebApi(username: String, cookieString: String?): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
        val effectiveCookie = if (!cookieString.isNullOrBlank()) cookieString else getOrFetchGuestCookies()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_CHROME_UA)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/$username/")
            .header("Accept", "*/*")

        if (!effectiveCookie.isNullOrBlank()) {
            requestBuilder.header("Cookie", effectiveCookie)
            extractCsrfToken(effectiveCookie)?.let { requestBuilder.header("X-CSRFToken", it) }
        }

        clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val root = gson.fromJson(body, JsonObject::class.java) ?: return null
            val data = root.getAsJsonObject("data") ?: return null
            val user = data.getAsJsonObject("user") ?: return null

            val id = user.get("id")?.asString
            val fullName = user.get("full_name")?.asString ?: ""
            val bio = user.get("biography")?.asString ?: ""
            val isPrivate = user.get("is_private")?.asBoolean ?: false
            val followers = user.getAsJsonObject("edge_followed_by")?.get("count")?.asLong ?: 0L
            val following = user.getAsJsonObject("edge_follow")?.get("count")?.asLong ?: 0L
            val mediaCount = user.getAsJsonObject("edge_owner_to_timeline_media")?.get("count")?.asLong ?: 0L
            val picUrl = user.get("profile_pic_url_hd")?.asString ?: user.get("profile_pic_url")?.asString

            return InstagramProfileSnapshot(
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
                val body = response.body?.string() ?: return null
                val root = gson.fromJson(body, JsonObject::class.java) ?: return null
                val data = root.getAsJsonObject("data") ?: return null
                val user = data.getAsJsonObject("user") ?: return null

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
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()

        clientWithRedirects.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return 0L
            val body = response.body ?: return 0L
            val bytes = body.bytes()
            FileOutputStream(destFile).use { it.write(bytes) }
            return bytes.size.toLong()
        }
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

    fun downloadMedia(url: String, destFile: File): Long {
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
            val bytes = body.bytes()
            FileOutputStream(destFile).use { it.write(bytes) }
            return bytes.size.toLong()
        }
    }

    fun fetchUserHighlights(userId: String, cookieString: String?): List<InstagramHighlight> {
        if (userId.isBlank()) return emptyList()
        val url = "https://www.instagram.com/graphql/query/?query_hash=d4d88dc1500312af6f937f7b804c68c3&variables=%7B%22user_id%22%3A%22$userId%22%2C%22include_highlight_reels%22%3Atrue%7D"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        val list = mutableListOf<InstagramHighlight>()
        try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyList()
                    val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
                    val edges = root.getAsJsonObject("data")
                        ?.getAsJsonObject("user")
                        ?.getAsJsonObject("edge_highlight_reels")
                        ?.getAsJsonArray("edges") ?: return emptyList()

                    for (edge in edges) {
                        val node = edge.asJsonObject?.getAsJsonObject("node") ?: continue
                        val id = node.get("id")?.asString ?: continue
                        val title = node.get("title")?.asString ?: "Highlight"
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
            Log.w(TAG, "Highlights query failed for user $userId: ${e.message}")
        }
        return list
    }

    fun fetchHighlightStories(highlightId: String, cookieString: String?): List<InstagramHighlightItem> {
        val cleanId = if (highlightId.startsWith("highlight:")) highlightId else "highlight:$highlightId"
        val encodedId = java.net.URLEncoder.encode("[\"$cleanId\"]", "UTF-8")
        val url = "https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables=%7B%22highlight_reel_ids%22%3A$encodedId%7D"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        val list = mutableListOf<InstagramHighlightItem>()
        try {
            clientWithRedirects.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyList()
                    val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
                    val reelsMedia = root.getAsJsonObject("data")?.getAsJsonArray("reels_media") ?: return emptyList()
                    for (reel in reelsMedia) {
                        val items = reel.asJsonObject?.getAsJsonArray("items") ?: continue
                        for (item in items) {
                            val obj = item.asJsonObject ?: continue
                            val id = obj.get("id")?.asString ?: continue
                            val isVideo = obj.get("is_video")?.asBoolean ?: false
                            var cdnUrl = ""
                            if (isVideo) {
                                val videoResources = obj.getAsJsonArray("video_resources")
                                if (videoResources != null && videoResources.size() > 0) {
                                    cdnUrl = videoResources[0].asJsonObject.get("src")?.asString ?: ""
                                }
                                if (cdnUrl.isBlank()) {
                                    cdnUrl = obj.get("video_url")?.asString ?: ""
                                }
                            }
                            if (cdnUrl.isBlank()) {
                                cdnUrl = obj.get("display_url")?.asString ?: ""
                            }
                            val takenAt = obj.get("taken_at_timestamp")?.asLong ?: (System.currentTimeMillis() / 1000L)
                            list.add(
                                InstagramHighlightItem(
                                    id = id,
                                    highlightId = highlightId,
                                    mediaType = if (isVideo) "video" else "image",
                                    mediaUrl = cdnUrl,
                                    takenAt = takenAt * 1000L
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Highlight stories query failed for $highlightId: ${e.message}")
        }
        return list
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
        val effectiveCookie = if (hasRealSession) cookieString else getOrFetchGuestCookies()

        // 1. Tier A: Web Profile Info API (Session or Guest Cookie Handshake)
        try {
            val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$cleanUser"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("X-IG-App-ID", APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://www.instagram.com/$cleanUser/")
                .header("Accept", "*/*")

            if (!effectiveCookie.isNullOrBlank()) {
                req.header("Cookie", effectiveCookie)
                extractCsrfToken(effectiveCookie)?.let { req.header("X-CSRFToken", it) }
            }

            clientWithRedirects.newCall(req.build()).execute().use { response ->
                when (response.code) {
                    429 -> sawRateLimit = true
                    401, 403 -> {
                        if (isPrivate && !hasRealSession) return PostFetchResult.RequiresSession
                    }
                    200 -> {
                        val body = response.body?.string()
                        if (body != null) {
                            val needsLogin = body.contains("/accounts/login/") || body.contains("login_required")
                            if (!needsLogin) {
                                val root = gson.fromJson(body, JsonObject::class.java)
                                val userObj = root?.getAsJsonObject("data")?.getAsJsonObject("user")
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

        // 2.5. Tier B2: Desktop SSR Relay JSON Extraction (Zero-Login Public Profiles)
        // Parses Instagram's Polaris SSR <script type="application/json"> (polaris_ordered_timeline_connection)
        if (!isPrivate) {
            try {
                val ssrPosts = fetchPostsViaDesktopSsr(cleanUser, effectiveCookie, limit)
                if (ssrPosts.isNotEmpty()) {
                    return PostFetchResult.Success(ssrPosts)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Desktop SSR post extraction failed for @$cleanUser: ${e.message}")
            }
        }

        // 3. Tier C: On-Device Headless Chromium WebView Bridge (Zero-Login Public Extraction)
        if (!isPrivate && headlessScraper != null) {
            try {
                when (val scrape = headlessScraper.scrapePublicPosts(cleanUser, limit)) {
                    is HeadlessScrapeResult.Success -> {
                        if (scrape.posts.isNotEmpty()) {
                            return PostFetchResult.Success(scrape.posts)
                        }
                    }
                    is HeadlessScrapeResult.PrivateAccount -> {
                        if (!hasRealSession) return PostFetchResult.RequiresSession
                    }
                    is HeadlessScrapeResult.RateLimited -> {
                        sawRateLimit = true
                    }
                    is HeadlessScrapeResult.LoginWall, is HeadlessScrapeResult.Failed -> {
                        Log.d(TAG, "Headless WebView fallback exhausted for @$cleanUser")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Headless WebView scraping error for @$cleanUser: ${e.message}")
            }
        }

        // 4. Tier D: Direct Public Profile HTML Regex Fallback (Desktop UA)
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

            if (!effectiveCookie.isNullOrBlank()) {
                htmlReq.header("Cookie", effectiveCookie)
            }

            clientWithRedirects.newCall(htmlReq.build()).execute().use { resp ->
                if (resp.code == 429) {
                    sawRateLimit = true
                } else if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    if (html.contains("\"is_private\":true") && !hasRealSession) {
                        return PostFetchResult.RequiresSession
                    }
                    val scPattern = Pattern.compile("\"(?:shortcode|code)\":\\s*\"([A-Za-z0-9_-]{8,15})\"")
                    val scMatcher = scPattern.matcher(html)
                    val shortcodes = mutableListOf<String>()
                    while (scMatcher.find() && shortcodes.size < limit) {
                        val sc = scMatcher.group(1)
                        if (!sc.isNullOrBlank() && !shortcodes.contains(sc)) {
                            shortcodes.add(sc)
                        }
                    }

                    for (sc in shortcodes) {
                        var directUrl: String? = null
                        val directPattern = Pattern.compile("\"(?:shortcode|code)\":\\s*\"" + Pattern.quote(sc) + "\"[^}]*?\"(?:display_url|display_uri)\":\\s*\"([^\"]+)\"")
                        val directMatcher = directPattern.matcher(html)
                        if (directMatcher.find()) {
                            directUrl = unescapeJson(directMatcher.group(1) ?: "")
                        }

                        list.add(
                            InstagramPostItem(
                                id = sc,
                                shortcode = sc,
                                mediaType = "image",
                                displayUrl = directUrl ?: "https://www.instagram.com/p/$sc/media/?size=l",
                                videoUrl = null,
                                caption = "",
                                timestamp = System.currentTimeMillis() - (list.size * 1000L),
                                likeCount = 0,
                                commentCount = 0
                            )
                        )
                    }
                    if (list.isNotEmpty()) return PostFetchResult.Success(list)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML post parsing fallback failed: ${e.message}")
        }

        if (list.isNotEmpty()) return PostFetchResult.Success(list)
        if (isPrivate && !hasRealSession) return PostFetchResult.RequiresSession
        if (sawRateLimit) return PostFetchResult.RateLimited
        return PostFetchResult.Error("No posts returned")
    }

    /**
     * Parses Instagram's Desktop SSR Relay JSON payloads (<script type="application/json">)
     * to extract public profile posts (polaris_ordered_timeline_connection / xdt_api / edge_owner_to_timeline_media)
     * without requiring a login session.
     */
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

        return parsePostsFromSsrHtml(html, limit)
    }

    internal fun parsePostsFromSsrHtml(html: String, limit: Int = 24): List<InstagramPostItem> {
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

    /**
     * Parses a single post's Desktop SSR HTML (<script type="application/json">) for
     * xig_polaris_media (full multi-slide carousel_media, HD image_versions2, video_versions,
     * like_count, comment_count, taken_at).
     */
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

    /**
     * Extracts ordered carousel slides from an Instagram timeline/post GraphQL node.
     */
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

    /**
     * Fetches full post media details (including all Carousel slides and HD URLs)
     * for a given shortcode using Instagram JSON/GraphQL endpoints with Embed fallback.
     */
    fun fetchPostMediaDetails(
        shortcode: String,
        cookieString: String?,
        fallbackDisplayUrl: String = "",
        fallbackVideoUrl: String? = null,
        isVideoHint: Boolean = false
    ): PostMediaDetails? {
        val cleanCode = shortcode.trim()
        val effectiveCookie = cookieString?.takeIf { it.isNotBlank() } ?: getOrFetchGuestCookies()

        // 1. Try Instagram post JSON endpoint (?__a=1&__d=dis)
        try {
            val url = "https://www.instagram.com/p/$cleanCode/?__a=1&__d=dis"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_CHROME_UA)
                .header("X-IG-App-ID", APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://www.instagram.com/p/$cleanCode/")
                .header("Accept", "*/*")

            if (!effectiveCookie.isNullOrBlank()) {
                req.header("Cookie", effectiveCookie)
                extractCsrfToken(effectiveCookie)?.let { req.header("X-CSRFToken", it) }
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

        // 1.5. Try Desktop SSR HTML on /p/<shortcode>/ (Zero-login full Carousel slides & HD URLs!)
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

    /**
     * Enriches a newly discovered public post using official Instagram Embed / OEmbed endpoints
     * to recover HD media URLs, video stream URLs, full caption, timestamp, and engagement counts.
     */
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

        // Fallback to official OEmbed API if caption or displayUrl is still missing
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

    companion object {
        private const val TAG = "InstagramEngine"
        private const val APP_ID = "936619743392459"
        private const val GUEST_COOKIE_TTL_MS = 20 * 60 * 1000L
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
