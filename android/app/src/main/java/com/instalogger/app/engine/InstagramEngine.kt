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
    val profilePicUrl: String? = null
)

class InstagramEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = Gson()

    fun fetchProfile(username: String, cookieString: String?): Result<InstagramProfileSnapshot> {
        val cleanUsername = username.trim().trimStart('@')

        // 1. First attempt: Instagram Web Profile Info API
        try {
            val apiSnapshot = fetchViaWebApi(cleanUsername, cookieString)
            if (apiSnapshot != null) {
                return Result.success(apiSnapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Web API attempt failed for @$cleanUsername: ${e.message}")
        }

        // 2. Second attempt: Direct Profile HTML Scraping
        try {
            val htmlSnapshot = fetchViaHtml(cleanUsername, cookieString)
            if (htmlSnapshot != null) {
                return Result.success(htmlSnapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML attempt failed for @$cleanUsername: ${e.message}")
        }

        return Result.failure(Exception("Profil məlumatları alına bilmədi. İnternet əlaqəsini və ya Instagram sessiyasını yoxlayın."))
    }

    private fun fetchViaWebApi(username: String, cookieString: String?): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/$username/")
            .header("Accept", "*/*")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
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

    private fun fetchViaHtml(username: String, cookieString: String?): InstagramProfileSnapshot? {
        val url = "https://www.instagram.com/$username/"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

        if (!cookieString.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookieString)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null

            // Regex parsing of meta tags
            var followers = 0L
            var following = 0L
            var posts = 0L
            var fullName = ""
            var picUrl: String? = null

            // og:description regex: "X Followers, Y Following, Z Posts"
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

            // og:title regex: "Name (@username)"
            val titlePattern = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"")
            val titleMatcher = titlePattern.matcher(html)
            if (titleMatcher.find()) {
                val title = titleMatcher.group(1) ?: ""
                fullName = title.substringBefore("(").trim()
            }

            // Profile pic url regex
            val picPattern = Pattern.compile("\"profile_pic_url_hd\":\"([^\"]+)\"|\"profile_pic_url\":\"([^\"]+)\"")
            val picMatcher = picPattern.matcher(html)
            if (picMatcher.find()) {
                val rawUrl = picMatcher.group(1) ?: picMatcher.group(2)
                picUrl = rawUrl?.replace("\\u0026", "&")?.replace("\\/", "/")
            }

            val isPrivate = html.contains("\"is_private\":true")

            return InstagramProfileSnapshot(
                username = username,
                instagramId = null,
                fullName = fullName,
                biography = "",
                isPrivate = isPrivate,
                followersCount = followers,
                followingCount = following,
                mediaCount = posts,
                profilePicUrl = picUrl
            )
        }
    }

    private fun parseFormattedNumber(text: String): Long {
        val clean = text.replace(",", "").replace(".", "").trim()
        return clean.toLongOrNull() ?: 0L
    }

    fun downloadImage(url: String, destFile: File): Long {
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        client.newCall(request).execute().use { response ->
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
