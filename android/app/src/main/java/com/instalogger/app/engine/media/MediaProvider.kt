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
        val detail: String? = null
    ) : PostDownloadState()
    object Merging : PostDownloadState()
    object Encrypting : PostDownloadState()
    data class Completed(val providerId: String) : PostDownloadState()
    object Cancelled : PostDownloadState()
    data class Failed(val message: String) : PostDownloadState()

    val isActive: Boolean
        get() = this is Queued || this is Extracting || this is Downloading || this is Merging || this is Encrypting
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
