package com.instalogger.app.update

import android.os.StatFs
import com.instalogger.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long
)

class ResumableApkDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    suspend fun downloadWithResume(
        url: String,
        destApkFile: File,
        expectedSizeBytes: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> {
        if (!GitHubReleaseClient.isAllowedDownloadUrl(url)) {
            return Result.failure(SecurityException("Blocked untrusted update URL host"))
        }

        val parentDir = destApkFile.parentFile ?: return Result.failure(IllegalArgumentException("Invalid destination"))
        if (!parentDir.exists()) parentDir.mkdirs()

        // Pre-flight disk space check: require at least 2x expected size (or 120 MB minimum)
        val requiredBytes = maxOf(expectedSizeBytes * 2L, 120L * 1024L * 1024L)
        val availableBytes = runCatching { StatFs(parentDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (availableBytes < requiredBytes) {
            val reqMb = requiredBytes / (1024L * 1024L)
            return Result.failure(IllegalStateException("INSUFFICIENT_STORAGE:$reqMb"))
        }

        val partFile = File(parentDir, "${destApkFile.name}.part")
        var existingBytes = if (partFile.exists()) partFile.length() else 0L
        if (expectedSizeBytes > 0L && existingBytes > expectedSizeBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "InstaLogger-Android-Updater/1.0")
            .header("Accept", "*/*")
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        return try {
            val call = client.newCall(reqBuilder.build())
            call.execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (!GitHubReleaseClient.isAllowedDownloadUrl(finalUrl)) {
                    return Result.failure(SecurityException("Blocked redirect to untrusted host"))
                }

                // If server rejected Range (416), restart cleanly from byte 0
                if (response.code == 416 && existingBytes > 0L) {
                    partFile.delete()
                    return downloadWithResume(url, destApkFile, expectedSizeBytes, onProgress)
                }

                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${response.code}"))
                }

                val isPartial = response.code == 206
                val appendMode = isPartial && existingBytes > 0L
                val startOffset = if (appendMode) existingBytes else 0L
                val body = response.body ?: return Result.failure(IllegalStateException("Empty APK stream"))
                val contentLength = body.contentLength()
                val totalBytes = when {
                    contentLength > 0L -> startOffset + contentLength
                    expectedSizeBytes > 0L -> expectedSizeBytes
                    else -> 0L
                }

                var downloaded = startOffset
                val startTimeMs = System.currentTimeMillis()
                var lastReportMs = startTimeMs
                var lastReportedBytes = downloaded
                val buffer = ByteArray(32 * 1024)

                onProgress(DownloadProgress(downloaded, totalBytes, 0L, -1L))

                body.byteStream().use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            val deltaMs = now - lastReportMs
                            if (deltaMs >= 150L) {
                                val deltaBytes = downloaded - lastReportedBytes
                                val instantSpeed = if (deltaMs > 0L) (deltaBytes * 1000L) / deltaMs else 0L
                                val elapsedMs = (now - startTimeMs).coerceAtLeast(1L)
                                val avgSpeed = ((downloaded - startOffset) * 1000L) / elapsedMs
                                val smoothedSpeed = if (instantSpeed > 0L) (instantSpeed * 7L + avgSpeed * 3L) / 10L else avgSpeed
                                val etaSec = if (totalBytes > downloaded && smoothedSpeed > 0L) {
                                    ((totalBytes - downloaded) / smoothedSpeed).coerceAtLeast(0L)
                                } else -1L
                                onProgress(DownloadProgress(downloaded, totalBytes, smoothedSpeed, etaSec))
                                lastReportMs = now
                                lastReportedBytes = downloaded
                            }
                        }
                        output.flush()
                    }
                }

                val finalTotal = if (totalBytes > 0L) totalBytes else downloaded
                onProgress(DownloadProgress(downloaded, finalTotal, 0L, 0L))

                if (destApkFile.exists()) destApkFile.delete()
                if (!partFile.renameTo(destApkFile)) {
                    partFile.copyTo(destApkFile, overwrite = true)
                    partFile.delete()
                }
                AppLogger.i(TAG, "APK downloaded successfully: ${destApkFile.name} (${destApkFile.length()} bytes)")
                Result.success(destApkFile)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.w(TAG, "APK download failed: ${e.message}")
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ResumableApkDownloader"
    }
}
