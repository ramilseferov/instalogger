package com.instalogger.app.update

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.instalogger.app.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class FetchReleaseResult(
    val release: ReleaseInfo? = null,
    val notModified: Boolean = false,
    val etag: String? = null,
    val source: String = "none",
    val error: String? = null
)

class GitHubReleaseClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val gson = Gson()

    fun fetchLatestRelease(
        channel: AppUpdateChannel,
        cachedEtag: String? = null
    ): FetchReleaseResult {
        // Tier 1 (Stable): GitHub Fastly CDN update-manifest.json (zero API rate-limit)
        if (channel == AppUpdateChannel.STABLE) {
            val cdnResult = fetchManifestFromCdn()
            if (cdnResult != null) {
                return FetchReleaseResult(
                    release = cdnResult,
                    notModified = false,
                    etag = cachedEtag,
                    source = "github_cdn_manifest"
                )
            }
        }

        // Tier 2: GitHub REST API with If-None-Match ETag (HTTP 304 consumes 0 rate-limit quota)
        val apiUrl = if (channel == AppUpdateChannel.BETA) API_RELEASES_LIST_URL else API_LATEST_RELEASE_URL
        val reqBuilder = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
        if (!cachedEtag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", cachedEtag)
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    return FetchReleaseResult(
                        release = null,
                        notModified = true,
                        etag = cachedEtag,
                        source = "github_api_304"
                    )
                }
                if (resp.code == 403 || resp.code == 429) {
                    return FetchReleaseResult(
                        error = "GitHub API rate limit (HTTP ${resp.code})"
                    )
                }
                if (!resp.isSuccessful) {
                    return FetchReleaseResult(
                        error = "GitHub HTTP ${resp.code}"
                    )
                }
                val newEtag = resp.header("ETag") ?: cachedEtag
                val body = resp.body?.string().orEmpty().trim()
                if (body.isBlank()) {
                    return FetchReleaseResult(error = "Empty response from GitHub")
                }

                val parsed = if (channel == AppUpdateChannel.BETA && body.startsWith("[")) {
                    val arr = gson.fromJson(body, JsonArray::class.java)
                    arr?.asSequence()
                        ?.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
                        ?.filter { obj -> obj.get("draft")?.takeIf { !it.isJsonNull }?.asBoolean != true }
                        ?.mapNotNull { parseGitHubApiRelease(it) }
                        ?.firstOrNull()
                } else {
                    val obj = gson.fromJson(body, JsonObject::class.java)
                    obj?.let { parseGitHubApiRelease(it) }
                }

                if (parsed == null) {
                    FetchReleaseResult(error = "No valid APK release found")
                } else {
                    FetchReleaseResult(
                        release = parsed,
                        notModified = false,
                        etag = newEtag,
                        source = "github_api"
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub API release fetch failed: ${e.message}")
            FetchReleaseResult(error = e.message ?: "Network error")
        }
    }

    private fun fetchManifestFromCdn(): ReleaseInfo? {
        return try {
            val req = Request.Builder()
                .url(CDN_MANIFEST_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.trim().orEmpty()
                if (!body.startsWith("{")) return null
                parseUpdateManifestJson(body)
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "CDN update-manifest.json fallback: ${e.message}")
            null
        }
    }

    internal fun parseUpdateManifestJson(json: String): ReleaseInfo? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java) ?: return null
            val tag = root.get("tag")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val versionName = root.get("versionName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val versionCode = root.get("versionCode")?.takeIf { !it.isJsonNull }?.asInt ?: parseVersionCodeFromTag(tag)
            val apkObj = root.getAsJsonObject("apk") ?: return null
            val apkUrl = apkObj.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            if (apkUrl.isBlank() || !isAllowedDownloadUrl(apkUrl) || versionCode <= 0) return null

            val apkName = apkObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "InstaLogger.apk" }
            val sizeBytes = apkObj.get("sizeBytes")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val sha256 = apkObj.get("sha256")?.takeIf { !it.isJsonNull }?.asString?.trim()?.lowercase()?.takeIf { it.length == 64 }

            val chObj = root.getAsJsonObject("changelog")
            val chAz = chObj?.get("az")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val chEn = chObj?.get("en")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val chRu = chObj?.get("ru")?.takeIf { !it.isJsonNull }?.asString.orEmpty()

            ReleaseInfo(
                tag = tag.ifBlank { "v$versionName-b$versionCode" },
                versionName = versionName.ifBlank { parseVersionNameFromTag(tag) },
                versionCode = versionCode,
                channel = AppUpdateChannel.fromKey(root.get("channel")?.takeIf { !it.isJsonNull }?.asString),
                publishedAt = root.get("publishedAt")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                minSdk = root.get("minSdk")?.takeIf { !it.isJsonNull }?.asInt ?: 26,
                minSupportedVersionCode = root.get("minSupportedVersionCode")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
                forceUpdate = root.get("forceUpdate")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                apkName = apkName,
                apkUrl = apkUrl,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                changelogAz = chAz,
                changelogEn = chEn,
                changelogRu = chRu,
                releaseUrl = root.get("releaseUrl")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    .ifBlank { "https://github.com/$REPO/releases/tag/$tag" }
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse update-manifest.json: ${e.message}")
            null
        }
    }

    internal fun parseGitHubApiRelease(obj: JsonObject): ReleaseInfo? {
        val tag = obj.get("tag_name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (tag.isBlank()) return null

        val assets = obj.getAsJsonArray("assets") ?: return null
        // Check if this release also carries an update-manifest.json asset (great for Beta releases!)
        var manifestAssetUrl: String? = null
        var apkAsset: JsonObject? = null
        for (el in assets) {
            val a = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = a.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (name.equals("update-manifest.json", ignoreCase = true)) {
                manifestAssetUrl = a.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString
            } else if (name.endsWith(".apk", ignoreCase = true)) {
                apkAsset = a
            }
        }

        if (!manifestAssetUrl.isNullOrBlank() && isAllowedDownloadUrl(manifestAssetUrl)) {
            try {
                val mReq = Request.Builder()
                    .url(manifestAssetUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(mReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val mBody = resp.body?.string()?.trim().orEmpty()
                        parseUpdateManifestJson(mBody)?.let { return it }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val apkObj = apkAsset ?: return null
        val apkUrl = apkObj.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (apkUrl.isBlank() || !isAllowedDownloadUrl(apkUrl)) return null

        val apkName = apkObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "InstaLogger.apk" }
        val sizeBytes = apkObj.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val body = obj.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val isPrerelease = obj.get("prerelease")?.takeIf { !it.isJsonNull }?.asBoolean == true
        val publishedAt = obj.get("published_at")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val htmlUrl = obj.get("html_url")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            .ifBlank { "https://github.com/$REPO/releases/tag/$tag" }

        val versionCode = parseVersionCodeFromTag(tag)
        val versionName = parseVersionNameFromTag(tag)
        val sha256 = extractSha256FromBody(body)
        val cleanedNotes = cleanReleaseNotesBody(body)

        return ReleaseInfo(
            tag = tag,
            versionName = versionName,
            versionCode = versionCode,
            channel = if (isPrerelease) AppUpdateChannel.BETA else AppUpdateChannel.STABLE,
            publishedAt = publishedAt,
            minSdk = 26,
            minSupportedVersionCode = 1,
            forceUpdate = false,
            apkName = apkName,
            apkUrl = apkUrl,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            changelogAz = cleanedNotes,
            changelogEn = cleanedNotes,
            changelogRu = cleanedNotes,
            releaseUrl = htmlUrl
        )
    }

    companion object {
        private const val TAG = "GitHubReleaseClient"
        const val REPO = "ramilseferov/instalogger"
        const val CDN_MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/update-manifest.json"
        const val API_LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val API_RELEASES_LIST_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
        private const val USER_AGENT = "InstaLogger-Android-Updater/1.0"

        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
        )

        private val TAG_BUILD_PATTERN = Pattern.compile("-b(\\d+)", Pattern.CASE_INSENSITIVE)
        private val TAG_SEMVER_PATTERN = Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)")
        private val SHA256_PATTERN = Pattern.compile("SHA-256[^a-fA-F0-9]*([a-fA-F0-9]{64})", Pattern.CASE_INSENSITIVE)

        fun isAllowedDownloadUrl(url: String): Boolean {
            return try {
                val uri = URI(url.trim())
                if (!uri.scheme.equals("https", ignoreCase = true)) return false
                val host = uri.host?.lowercase() ?: return false
                host in ALLOWED_HOSTS || host.endsWith(".githubusercontent.com")
            } catch (_: Exception) {
                false
            }
        }

        fun parseVersionCodeFromTag(tag: String): Int {
            val m = TAG_BUILD_PATTERN.matcher(tag)
            if (m.find()) {
                return m.group(1)?.toIntOrNull() ?: 0
            }
            return 0
        }

        fun parseVersionNameFromTag(tag: String): String {
            val m = TAG_SEMVER_PATTERN.matcher(tag)
            return if (m.find()) m.group(1) ?: tag.removePrefix("v") else tag.removePrefix("v")
        }

        fun extractSha256FromBody(body: String): String? {
            val m = SHA256_PATTERN.matcher(body)
            return if (m.find()) m.group(1)?.lowercase() else null
        }

        fun cleanReleaseNotesBody(body: String): String {
            val lines = body.lines()
            val out = mutableListOf<String>()
            var skippingApkSection = false
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("### ") && (trimmed.contains("APK", ignoreCase = true) || trimmed.contains("Quraşdırma", ignoreCase = true) || trimmed.contains("установоч", ignoreCase = true))) {
                    skippingApkSection = true
                    continue
                }
                if (skippingApkSection) continue
                if (trimmed.startsWith("## ") && trimmed.contains("InstaLogger", ignoreCase = true)) continue
                if (trimmed.startsWith("### ")) continue
                if (trimmed.isNotBlank()) out.add(trimmed)
            }
            return out.joinToString("\n").trim()
        }

        fun compareSemVer(a: String, b: String): Int {
            val pa = a.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
            val pb = b.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(pa.size, pb.size)
            for (i in 0 until maxLen) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }

        fun isRemoteNewer(
            remote: ReleaseInfo,
            installedVersionCode: Int,
            installedVersionName: String
        ): Boolean {
            if (remote.versionCode > 0 && installedVersionCode > 0) {
                if (remote.versionCode != installedVersionCode) {
                    return remote.versionCode > installedVersionCode
                }
            }
            return compareSemVer(remote.versionName, installedVersionName) > 0
        }
    }
}
