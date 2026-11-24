package com.instalogger.app.engine.media

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.instalogger.app.settings.AppSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Optional Bridge Provider connecting Android to the local/remote InstaLogger Daemon
 * (which can execute Python gallery-dl / yt-dlp / Instaloader providers).
 * Automatically bypassed when daemonSyncEnabled == false.
 */
class DaemonBridgeProvider(
    private val settingsManager: AppSettingsManager
) : InstagramMediaProvider {

    private val gson = Gson()
    private val cancelledProcesses = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    override val id: String = PROVIDER_ID
    override val displayName: String = "Daemon Bridge (gallery-dl / yt-dlp)"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.PHOTO_POST,
        ProviderCapability.VIDEO_POST,
        ProviderCapability.REEL,
        ProviderCapability.CAROUSEL,
        ProviderCapability.METADATA
    )

    override suspend fun isAvailable(): Boolean {
        return settingsManager.daemonSyncEnabled.value && settingsManager.daemonHostUrl.value.isNotBlank()
    }

    override suspend fun downloadPost(
        request: MediaDownloadRequest,
        stagingDir: File,
        onProgress: (PostDownloadState) -> Unit
    ): ProviderOutcome = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ProviderOutcome.UnsupportedMedia("Daemon sync disabled")
        }

        cancelledProcesses.remove(request.processId)
        stagingDir.mkdirs()
        onProgress(PostDownloadState.Extracting)

        val baseUrl = settingsManager.daemonHostUrl.value.trimEnd('/')
        return@withContext try {
            val targetsReq = Request.Builder().url("$baseUrl/api/targets").get().build()
            val targetId = client.newCall(targetsReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = gson.fromJson(resp.body?.string().orEmpty(), JsonArray::class.java) ?: return@use null
                arr.firstOrNull { elem ->
                    elem.asJsonObject?.get("username")?.asString.equals(request.username, ignoreCase = true)
                }?.asJsonObject?.get("id")?.asString
            } ?: return@withContext ProviderOutcome.Failure("Target not synced on daemon")

            val mediaReq = Request.Builder().url("$baseUrl/api/targets/$targetId/media").get().build()
            val mediaItems = client.newCall(mediaReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<JsonObject>()
                val arr = gson.fromJson(resp.body?.string().orEmpty(), JsonArray::class.java) ?: return@use emptyList()
                arr.mapNotNull { it.asJsonObject }
                    .filter { it.get("shortcode")?.asString == request.shortcode }
            }

            if (mediaItems.isEmpty()) {
                return@withContext ProviderOutcome.Failure("Post ${request.shortcode} not yet available on daemon")
            }

            val assets = mutableListOf<StagedMediaAsset>()
            mediaItems.forEachIndexed { idx, item ->
                if (cancelledProcesses.contains(request.processId)) {
                    return@withContext ProviderOutcome.Cancelled
                }

                val relPath = item.get("relative_file_path")?.asString ?: return@forEachIndexed
                val assetUrl = "$baseUrl/${relPath.trimStart('/')}"
                if (!MediaUrlGuard.isAllowedMediaUrl(assetUrl, allowLocalHost = true)) return@forEachIndexed

                val isVideo = relPath.endsWith(".mp4", ignoreCase = true) ||
                    item.get("media_type")?.asString.equals("reel", ignoreCase = true)
                val outFile = File(stagingDir, "${request.shortcode}_$idx.${if (isVideo) "mp4" else "jpg"}")

                onProgress(PostDownloadState.Downloading(((idx + 1).toFloat() / mediaItems.size) * 100f))

                val fileReq = Request.Builder().url(assetUrl).get().build()
                client.newCall(fileReq).execute().use { fResp ->
                    if (fResp.isSuccessful) {
                        val body = fResp.body ?: return@use
                        FileOutputStream(outFile).use { out ->
                            body.byteStream().copyTo(out)
                        }
                    }
                }

                if (outFile.exists() && outFile.length() > 0L) {
                    assets.add(
                        StagedMediaAsset(
                            position = idx,
                            mediaType = if (isVideo) "video" else "image",
                            mediaFile = outFile,
                            remoteUrl = assetUrl
                        )
                    )
                }
            }

            if (assets.isNotEmpty()) {
                ProviderOutcome.Success(assets = assets, providerId = PROVIDER_ID)
            } else {
                ProviderOutcome.Failure("Daemon returned no downloadable media")
            }
        } catch (e: Exception) {
            ProviderOutcome.Failure(LogSanitizer.redact(e.message ?: "Daemon bridge error"))
        }
    }

    override fun cancel(processId: String) {
        cancelledProcesses.add(processId)
    }

    companion object {
        const val PROVIDER_ID = "daemon-bridge"
    }
}
