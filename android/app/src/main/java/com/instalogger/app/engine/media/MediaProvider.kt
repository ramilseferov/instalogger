package com.instalogger.app.engine.media

import java.io.File

enum class ProviderCapability {
    PHOTO_POST,
    VIDEO_POST,
    REEL,
    CAROUSEL,
    METADATA,
    SELF_UPDATABLE
}

sealed class YtDlpEngineState {
    object Uninitialized : YtDlpEngineState()
    object Initializing : YtDlpEngineState()
    data class Ready(val version: String) : YtDlpEngineState()
    object Updating : YtDlpEngineState()
    data class Failed(val error: String) : YtDlpEngineState()
}

enum class YtDlpQualityPreset(val key: String, val formatSelector: String) {
    BEST(
        key = "BEST",
        formatSelector = "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/best"
    ),
    HD_1080(
        key = "HD_1080",
        formatSelector = "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/best[height<=1080]/best"
    ),
    HD_720(
        key = "HD_720",
        formatSelector = "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/best[height<=720]/best"
    ),
    DATA_SAVER_480(
        key = "DATA_SAVER_480",
        formatSelector = "bv*[height<=480][ext=mp4]+ba[ext=m4a]/b[height<=480][ext=mp4]/best[height<=480]/best"
    );

    companion object {
        fun fromKey(raw: String?): YtDlpQualityPreset {
            return entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: BEST
        }
    }
}

enum class YtDlpUpdateChannel(val key: String) {
    STABLE("STABLE"),
    NIGHTLY("NIGHTLY");

    companion object {
        fun fromKey(raw: String?): YtDlpUpdateChannel {
            return entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: STABLE
        }
    }
}

sealed class PostDownloadState {
    object Idle : PostDownloadState()
    object Queued : PostDownloadState()
    object Extracting : PostDownloadState()
    data class Downloading(
        val progress: Float,
        val etaSeconds: Long = -1L,
        val detail: String? = null,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBytesPerSec: Long = 0L
    ) : PostDownloadState()
    object Merging : PostDownloadState()
    object Encrypting : PostDownloadState()
    object Paused : PostDownloadState()
    data class Completed(val providerId: String) : PostDownloadState()
    object Cancelled : PostDownloadState()
    data class Failed(val message: String) : PostDownloadState()

    val isActive: Boolean
        get() = this is Queued || this is Extracting || this is Downloading || this is Merging || this is Encrypting

    val isPaused: Boolean
        get() = this is Paused

    val isTerminal: Boolean
        get() = this is Completed || this is Cancelled || this is Failed
}

data class DownloadQueueItem(
    val postId: String,
    val username: String,
    val mediaType: String,
    val thumbnailUrl: String,
    val slideCount: Int = 1,
    val state: PostDownloadState = PostDownloadState.Queued,
    val providerName: String? = null,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val progress: Float = 0f,
    val detail: String? = null
)

data class ParsedYtDlpTelemetry(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val detail: String? = null
)

object DownloadTelemetryFormatter {
    private val sizeRegex = Regex("""of\s+~?([\d.]+)\s*([KMG]?i?B)""", RegexOption.IGNORE_CASE)
    private val speedRegex = Regex("""at\s+([\d.]+)\s*([KMG]?i?B)/s""", RegexOption.IGNORE_CASE)

    fun parseBytes(amount: Double, unit: String): Long {
        val factor = when (unit.uppercase()) {
            "B" -> 1.0
            "KB" -> 1_000.0
            "KIB" -> 1_024.0
            "MB" -> 1_000_000.0
            "MIB" -> 1_048_576.0
            "GB" -> 1_000_000_000.0
            "GIB" -> 1_073_741_824.0
            else -> 1.0
        }
        return (amount * factor).toLong().coerceAtLeast(0L)
    }

    fun parseYtDlpLine(line: String, progressPercent: Float): ParsedYtDlpTelemetry {
        val speedMatch = speedRegex.find(line)
        val sizeMatch = sizeRegex.find(line)

        val speedBytes = speedMatch?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
            parseBytes(v, m.groupValues[2])
        } ?: 0L

        val totalBytes = sizeMatch?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
            parseBytes(v, m.groupValues[2])
        } ?: -1L

        val downloadedBytes = if (totalBytes > 0L && progressPercent >= 0f) {
            ((totalBytes.toDouble() * progressPercent.coerceIn(0f, 100f)) / 100.0).toLong()
        } else {
            0L
        }

        val detail = speedMatch?.let { "${it.groupValues[1]} ${it.groupValues[2]}/s" }
        return ParsedYtDlpTelemetry(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speedBytes,
            detail = detail
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "--"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatEta(etaSeconds: Long): String {
        if (etaSeconds < 0L) return "--"
        if (etaSeconds < 60L) return "${etaSeconds}s"
        val mins = etaSeconds / 60L
        val secs = etaSeconds % 60L
        if (mins < 60L) return "${mins}m ${secs}s"
        val hrs = mins / 60L
        val remMins = mins % 60L
        return "${hrs}h ${remMins}m"
    }
}

data class StagedMediaAsset(
    val position: Int,
    val mediaType: String, // "image" or "video"
    val mediaFile: File,
    val thumbFile: File? = null,
    val remoteUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Int? = null
)

data class ExtractedPostMetadata(
    val caption: String? = null,
    val likeCount: Int? = null,
    val commentCount: Int? = null,
    val postedAtMillis: Long? = null,
    val durationSeconds: Int? = null,
    val displayUrl: String? = null
)

data class CarouselSlideHint(
    val position: Int,
    val mediaType: String, // "image" or "video"
    val displayUrl: String,
    val videoUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0
)

data class MediaDownloadRequest(
    val processId: String,
    val username: String,
    val shortcode: String,
    val mediaTypeHint: String, // "image", "video", "carousel"
    val displayUrl: String = "",
    val videoUrl: String? = null,
    val cookieString: String? = null,
    val qualityPreset: YtDlpQualityPreset = YtDlpQualityPreset.BEST,
    val slideHints: List<CarouselSlideHint> = emptyList(),
    val userInitiated: Boolean = false
)

sealed class ProviderOutcome {
    data class Success(
        val assets: List<StagedMediaAsset>,
        val metadata: ExtractedPostMetadata? = null,
        val providerId: String
    ) : ProviderOutcome()

    data class UnsupportedMedia(val reason: String) : ProviderOutcome()
    data class RateLimited(val retryAfterMs: Long, val detail: String) : ProviderOutcome()
    data class LoginRequired(val detail: String) : ProviderOutcome()
    object Cancelled : ProviderOutcome()
    data class Failure(val message: String, val isTransient: Boolean = true) : ProviderOutcome()
}

interface InstagramMediaProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ProviderCapability>

    suspend fun isAvailable(): Boolean

    suspend fun downloadPost(
        request: MediaDownloadRequest,
        stagingDir: File,
        onProgress: (PostDownloadState) -> Unit
    ): ProviderOutcome

    fun cancel(processId: String)
}
