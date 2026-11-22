package com.instalogger.app.engine

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.instalogger.app.util.cleanDisplayText
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
    val mediaType: String, // "image" or "video"
    val displayUrl: String,
    val videoUrl: String? = null,
    val caption: String = "",
    val timestamp: Long = 0L,
    val likeCount: Int = 0,
    val commentCount: Int = 0
)

class InstagramEngine {

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

            // 8. Extract media / post count
            val mediaPattern = Pattern.compile("\"edge_owner_to_timeline_media\":\\s*\\{\\s*\"count\":\\s*(\\d+)|\"media_count\":\\s*(\\d+)")
            val mediaMatcher = mediaPattern.matcher(html)
            if (mediaMatcher.find()) {
                val countStr = mediaMatcher.group(1) ?: mediaMatcher.group(2)
                posts = countStr?.toLongOrNull() ?: 0L
            }

            // 9. Fallback og:description if modern JSON was missing
            if (followers == 0L && following == 0L) {
                val descPattern = Pattern.compile("<meta\\s+property=\"og:description\"\\s+content=\"([^\"]+)\"")
                val descMatcher = descPattern.matcher(html)
                if (descMatcher.find()) {
                    val desc = descMatcher.group(1) ?: ""
                    val countPattern = Pattern.compile("([\\d,.]+)\\s+Followers,\\s+([\\d,.]+)\\s+Following,\\s+([\\d,.]+)\\s+Posts")
                    val countMatcher = countPattern.matcher(desc)
                    if (countMatcher.find()) {
                        followers = parseFormattedNumber(countMatcher.group(1) ?: "0")
                        following = parseFormattedNumber(countMatcher.group(2) ?: "0")
                        posts = parseFormattedNumber(countMatcher.group(3) ?: "0")
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
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/$username/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
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
        val clean = text.replace(",", "").replace(".", "").trim()
        return clean.toLongOrNull() ?: 0L
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

    fun fetchRecentPosts(userId: String?, username: String, cookieString: String?, limit: Int = 24): List<InstagramPostItem> {
        val list = mutableListOf<InstagramPostItem>()

        // 1. Try web profile info API first
        try {
            val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("X-IG-App-ID", APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://www.instagram.com/$username/")
                .header("Accept", "*/*")

            if (!cookieString.isNullOrBlank()) {
                req.header("Cookie", cookieString)
            }

            clientWithRedirects.newCall(req.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val edges = root?.getAsJsonObject("data")
                            ?.getAsJsonObject("user")
                            ?.getAsJsonObject("edge_owner_to_timeline_media")
                            ?.getAsJsonArray("edges")
                        if (edges != null && edges.size() > 0) {
                            for (edge in edges) {
                                val node = edge.asJsonObject?.getAsJsonObject("node") ?: continue
                                val id = node.get("id")?.asString ?: continue
                                val shortcode = node.get("shortcode")?.asString ?: id
                                val isVideo = node.get("is_video")?.asBoolean ?: false
                                val displayUrl = node.get("display_url")?.asString ?: ""
                                val videoUrl = if (isVideo) node.get("video_url")?.asString else null
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
                                        mediaType = if (isVideo) "video" else "image",
                                        displayUrl = displayUrl,
                                        videoUrl = videoUrl,
                                        caption = caption.cleanDisplayText(),
                                        timestamp = timestamp * 1000L,
                                        likeCount = likes,
                                        commentCount = comments
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Web profile info posts query failed: ${e.message}")
        }

        if (list.isNotEmpty()) return list

        // 2. Fallback to GraphQL query hash if user_id is provided
        if (!userId.isNullOrBlank()) {
            try {
                val gqlUrl = "https://www.instagram.com/graphql/query/?query_hash=e7465275e4878740f5b475ab0877917b&variables=%7B%22id%22%3A%22$userId%22%2C%22first%22%3A$limit%7D"
                val req = Request.Builder()
                    .url(gqlUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("X-IG-App-ID", APP_ID)
                    .header("Referer", "https://www.instagram.com/")
                    .header("Accept", "*/*")

                if (!cookieString.isNullOrBlank()) {
                    req.header("Cookie", cookieString)
                }

                clientWithRedirects.newCall(req.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return emptyList()
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val edges = root?.getAsJsonObject("data")
                            ?.getAsJsonObject("user")
                            ?.getAsJsonObject("edge_owner_to_timeline_media")
                            ?.getAsJsonArray("edges")
                        if (edges != null) {
                            for (edge in edges) {
                                val node = edge.asJsonObject?.getAsJsonObject("node") ?: continue
                                val id = node.get("id")?.asString ?: continue
                                val shortcode = node.get("shortcode")?.asString ?: id
                                val isVideo = node.get("is_video")?.asBoolean ?: false
                                val displayUrl = node.get("display_url")?.asString ?: ""
                                val videoUrl = if (isVideo) node.get("video_url")?.asString else null
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
                                        mediaType = if (isVideo) "video" else "image",
                                        displayUrl = displayUrl,
                                        videoUrl = videoUrl,
                                        caption = caption.cleanDisplayText(),
                                        timestamp = timestamp * 1000L,
                                        likeCount = likes,
                                        commentCount = comments
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GraphQL timeline posts query failed: ${e.message}")
            }
        }

        if (list.isNotEmpty()) return list

        // 3. Fallback: Parse posts embedded in public profile HTML
        try {
            val htmlUrl = "https://www.instagram.com/$username/"
            val htmlReq = Request.Builder()
                .url(htmlUrl)
                .header("User-Agent", USER_AGENT)
                .header("X-IG-App-ID", APP_ID)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")

            if (!cookieString.isNullOrBlank()) {
                htmlReq.header("Cookie", cookieString)
            }

            clientWithRedirects.newCall(htmlReq.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val scPattern = Pattern.compile("\"shortcode\":\\s*\"([A-Za-z0-9_-]+)\"")
                    val scMatcher = scPattern.matcher(html)
                    val shortcodes = mutableListOf<String>()
                    while (scMatcher.find() && shortcodes.size < limit) {
                        val sc = scMatcher.group(1)
                        if (!sc.isNullOrBlank() && !shortcodes.contains(sc)) {
                            shortcodes.add(sc)
                        }
                    }

                    for (sc in shortcodes) {
                        // Check if direct display_url exists near this shortcode
                        var directUrl: String? = null
                        val directPattern = Pattern.compile("\"shortcode\":\\s*\"" + Pattern.quote(sc) + "\"[^}]*?\"display_url\":\\s*\"([^\"]+)\"")
                        val directMatcher = directPattern.matcher(html)
                        if (directMatcher.find()) {
                            directUrl = unescapeJson(directMatcher.group(1) ?: "")
                        }

                        list.add(
                            InstagramPostItem(
                                id = sc,
                                shortcode = sc,
                                mediaType = "image",
                                displayUrl = directUrl ?: "https://instagram.com/p/$sc/media/?size=l",
                                videoUrl = null,
                                caption = "",
                                timestamp = System.currentTimeMillis(),
                                likeCount = 0,
                                commentCount = 0
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML post parsing fallback failed: ${e.message}")
        }

        return list
    }

    companion object {
        private const val TAG = "InstagramEngine"
        private const val APP_ID = "936619743392459"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
