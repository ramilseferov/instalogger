package com.instalogger.app.engine.media

import com.instalogger.app.engine.InstagramEngine
import com.instalogger.app.engine.InstagramPostItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Native Kotlin/OkHttp Instagram Provider.
class NativeGraphProvider(
    private val instagramEngine: InstagramEngine
) : InstagramMediaProvider {

    private val cancelledProcesses = ConcurrentHashMap.newKeySet<String>()

    override val id: String = PROVIDER_ID
    override val displayName: String = "Native Graph & Embed"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.PHOTO_POST,
        ProviderCapability.VIDEO_POST,
        ProviderCapability.CAROUSEL,
        ProviderCapability.METADATA
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun downloadPost(
        request: MediaDownloadRequest,
        stagingDir: File,
        onProgress: (PostDownloadState) -> Unit
    ): ProviderOutcome = withContext(Dispatchers.IO) {
        cancelledProcesses.remove(request.processId)
        stagingDir.mkdirs()
        onProgress(PostDownloadState.Extracting)

        if (cancelledProcesses.contains(request.processId)) {
            return@withContext ProviderOutcome.Cancelled
        }

        // Resolve slides
        var slides = request.slideHints
        var extractedMetadata: ExtractedPostMetadata? = null

        if (slides.isEmpty() || request.mediaTypeHint == "carousel") {
            val detail = instagramEngine.fetchPostMediaDetails(
                shortcode = request.shortcode,
                cookieString = request.cookieString,
                fallbackDisplayUrl = request.displayUrl,
                fallbackVideoUrl = request.videoUrl,
                isVideoHint = request.mediaTypeHint == "video"
            )
            if (detail != null) {
                if (detail.slides.isNotEmpty()) {
                    slides = detail.slides
                }
                extractedMetadata = detail.metadata
            }
        }

        // 2. Fallback to Embed/OEmbed enrichment if still single item needing HD URL
        if (slides.isEmpty()) {
            val baseItem = InstagramPostItem(
                id = request.shortcode,
                shortcode = request.shortcode,
                mediaType = if (request.mediaTypeHint == "video") "video" else "image",
                displayUrl = request.displayUrl,
                videoUrl = request.videoUrl
            )
            val enriched = if (request.displayUrl.isBlank() ||
                request.displayUrl.contains("/media/?size=") ||
                (request.mediaTypeHint == "video" && request.videoUrl.isNullOrBlank())
            ) {
                instagramEngine.enrichPostViaEmbed(baseItem)
            } else {
                baseItem
            }

            if (enriched.displayUrl.isNotBlank() || !enriched.videoUrl.isNullOrBlank()) {
                slides = listOf(
                    CarouselSlideHint(
                        position = 0,
                        mediaType = enriched.mediaType,
                        displayUrl = enriched.displayUrl,
                        videoUrl = enriched.videoUrl
                    )
                )
                extractedMetadata = ExtractedPostMetadata(
                    caption = enriched.caption.ifBlank { null },
                    likeCount = enriched.likeCount.takeIf { it > 0 },
                    commentCount = enriched.commentCount.takeIf { it > 0 },
                    postedAtMillis = enriched.timestamp.takeIf { it > 0L },
                    displayUrl = enriched.displayUrl.ifBlank { null }
                )
            }
        }

        if (slides.isEmpty()) {
            return@withContext ProviderOutcome.Failure("No media URLs discovered for ${request.shortcode}")
        }

        val stagedAssets = mutableListOf<StagedMediaAsset>()
        val total = slides.size

        for ((index, slide) in slides.withIndex()) {
            if (cancelledProcesses.contains(request.processId)) {
                return@withContext ProviderOutcome.Cancelled
            }

            val basePct = (index.toFloat() / total.coerceAtLeast(1)) * 100f
            val slideSlice = 100f / total.coerceAtLeast(1)
            onProgress(PostDownloadState.Downloading(progress = basePct, detail = "${index + 1}/$total"))

            val isVideoSlide = slide.mediaType.equals("video", ignoreCase = true) && !slide.videoUrl.isNullOrBlank()
            val primaryUrl = if (isVideoSlide) slide.videoUrl!! else slide.displayUrl
            if (!MediaUrlGuard.isAllowedMediaUrl(primaryUrl)) continue

            val ext = if (isVideoSlide) "mp4" else "jpg"
            val targetFile = File(stagingDir, "${request.shortcode}_${index}.$ext")
            val downloadedBytes = instagramEngine.downloadMedia(
                url = primaryUrl,
                destFile = targetFile,
                isCancelled = { cancelledProcesses.contains(request.processId) },
                onByteProgress = { dlBytes, totBytes, speedBps, etaSec ->
                    val slideFrac = if (totBytes > 0L) (dlBytes.toFloat() / totBytes.toFloat()).coerceIn(0f, 1f) else 0.5f
                    val overallPct = (basePct + slideSlice * slideFrac).coerceIn(0f, 100f)
                    val speedStr = if (speedBps > 0L) DownloadTelemetryFormatter.formatSpeed(speedBps) else null
                    val detailStr = if (total > 1) {
                        if (speedStr != null) "${index + 1}/$total · $speedStr" else "${index + 1}/$total"
                    } else speedStr
                    onProgress(
                        PostDownloadState.Downloading(
                            progress = overallPct,
                            etaSeconds = etaSec,
                            detail = detailStr,
                            downloadedBytes = dlBytes,
                            totalBytes = totBytes,
                            speedBytesPerSec = speedBps
                        )
                    )
                }
            )
            if (downloadedBytes < 0L || cancelledProcesses.contains(request.processId)) {
                return@withContext ProviderOutcome.Cancelled
            }

            var thumbFile: File? = null
            if (isVideoSlide && slide.displayUrl.isNotBlank() && MediaUrlGuard.isAllowedMediaUrl(slide.displayUrl)) {
                val tf = File(stagingDir, "${request.shortcode}_${index}_thumb.jpg")
                if (instagramEngine.downloadMedia(slide.displayUrl, tf) > 0L && tf.exists() && tf.length() > 0L) {
                    thumbFile = tf
                }
            }

            if (downloadedBytes > 0L && targetFile.exists() && targetFile.length() > 0L) {
                stagedAssets.add(
                    StagedMediaAsset(
                        position = slide.position,
                        mediaType = if (isVideoSlide) "video" else "image",
                        mediaFile = targetFile,
                        thumbFile = thumbFile,
                        remoteUrl = primaryUrl,
                        width = slide.width,
                        height = slide.height
                    )
                )
            }
        }

        onProgress(PostDownloadState.Downloading(progress = 100f))

        return@withContext if (stagedAssets.isNotEmpty()) {
            ProviderOutcome.Success(
                assets = stagedAssets,
                metadata = extractedMetadata,
                providerId = PROVIDER_ID
            )
        } else {
            ProviderOutcome.Failure("Failed to download media streams for ${request.shortcode}")
        }
    }

    override fun cancel(processId: String) {
        cancelledProcesses.add(processId)
    }

    companion object {
        const val PROVIDER_ID = "native-graph"
    }
}
