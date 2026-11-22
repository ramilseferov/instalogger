package com.instalogger.app.engine

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
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

            // 3. Extract pk / ID
            val pkPattern = Pattern.compile("\"pk\":\\s*\"?(\\d+)\"?")
            val pkMatcher = pkPattern.matcher(html)
            if (pkMatcher.find()) {
                id = pkMatcher.group(1)
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
                if (next == 'u' && i + 5 < len) {
                    try {
                        val hex = input.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 6
                        continue
                    } catch (e: Exception) {
                        sb.append(c)
                    }
                } else if (next == '/') {
                    sb.append('/')
                    i += 2
                    continue
                } else if (next == '\\') {
                    sb.append('\\')
                    i += 2
                    continue
                } else if (next == '\"') {
                    sb.append('\"')
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString().replace("&amp;", "&")
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

    companion object {
        private const val TAG = "InstagramEngine"
        private const val APP_ID = "936619743392459"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
