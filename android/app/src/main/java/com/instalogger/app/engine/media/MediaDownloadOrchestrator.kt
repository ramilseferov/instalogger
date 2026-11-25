package com.instalogger.app.engine.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.instalogger.app.data.local.AppDatabase
import com.instalogger.app.data.local.MediaAssetEntity
import com.instalogger.app.data.local.PostEntity
import com.instalogger.app.engine.SessionManager
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.VaultStorageManager
import com.instalogger.app.util.cleanDisplayText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-grade Multi-Provider Media Download Orchestrator.
 * - Bounded concurrency (max 2 parallel workers) to avoid OOM/throttling
 * - Capability-aware routing (yt-dlp for Videos/Reels, NativeGraph for Photos/Carousels, automatic fallback)
 * - Circuit-breaker ProviderHealth tracking
 * - Strict Magic-Byte validation, SHA-256 deduplication, and atomic AES-256-GCM Vault ingestion
 * - Real-time StateFlow progress telemetry and deterministic process cancellation
 */
class MediaDownloadOrchestrator(
    context: Context,
    private val database: AppDatabase,
    private val sessionManager: SessionManager,
    private val settingsManager: AppSettingsManager,
    private val vaultStorageManager: VaultStorageManager,
    val ytDlpProvider: YtDlpEmbeddedProvider,
    val nativeGraphProvider: NativeGraphProvider,
    val daemonBridgeProvider: DaemonBridgeProvider,
    val healthTracker: ProviderHealthTracker
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSlotLock = Any()
    private val activeSlotCount = AtomicInteger(0)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeProcessIds = ConcurrentHashMap<String, String>()
    private val activeProviderMap = ConcurrentHashMap<String, InstagramMediaProvider>()

    private val _downloadStates = MutableStateFlow<Map<String, PostDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, PostDownloadState>> = _downloadStates.asStateFlow()

    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    private val _queuedDownloadCount = MutableStateFlow(0)
    val queuedDownloadCount: StateFlow<Int> = _queuedDownloadCount.asStateFlow()

    private suspend fun <T> withDynamicSlot(block: suspend () -> T): T {
        while (true) {
            val maxWorkers = settingsManager.archiverSpeedPreset.value.downloadWorkers.coerceIn(1, 12)
            var acquired = false
            synchronized(activeSlotLock) {
                if (activeSlotCount.get() < maxWorkers) {
                    activeSlotCount.incrementAndGet()
                    acquired = true
                }
            }
            if (acquired) {
                _activeDownloadCount.value = activeSlotCount.get()
                try {
                    return block()
                } finally {
                    val left = activeSlotCount.decrementAndGet().coerceAtLeast(0)
                    _activeDownloadCount.value = left
                }
            }
            delay(120L)
        }
    }

    /**
     * Resumes any unarchived/pending/failed posts in Room DB for a given user.
     */
    suspend fun enqueuePendingForUser(username: String): Int {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val posts = database.postDao().getPostsListForUser(cleanUser)
        val missing = posts.filter { post ->
            val fileOk = !post.localPath.isNullOrBlank() &&
                File(post.localPath).exists() &&
                File(post.localPath).length() > 0L
            !fileOk || post.downloadStatus != "complete"
        }
        if (missing.isEmpty()) return 0
        enqueueBatch(cleanUser, missing.map { it to emptyList() })
        return missing.size
    }

    fun getPostState(postId: String): PostDownloadState {
        return _downloadStates.value[postId] ?: PostDownloadState.Idle
    }

    /**
     * Enqueues a batch of posts discovered during profile sync.
     * Respects auto-download and Wi-Fi-only settings for background video downloads.
     */
    fun enqueueBatch(
        username: String,
        items: List<Pair<PostEntity, List<CarouselSlideHint>>>
    ) {
        val autoDownloadVideos = settingsManager.ytDlpAutoDownloadVideos.value
        val wifiOnly = settingsManager.ytDlpWifiOnly.value
        val onWifi = isOnUnmeteredWifi()

        for ((post, slideHints) in items) {
            val alreadyComplete = post.downloadStatus == "complete" &&
                !post.localPath.isNullOrBlank() &&
                File(post.localPath).exists() &&
                File(post.localPath).length() > 0L

            if (alreadyComplete) continue

            // Background video download guards (user-initiated clicks always bypass these)
            if (post.isVideo) {
                if (!autoDownloadVideos) continue
                if (wifiOnly && !onWifi) continue
            }

            enqueuePost(
                post = post,
                slideHints = slideHints,
                userInitiated = false,
                forceRedownload = false
            )
        }
    }

    /**
     * Enqueues a single post for download/redownload.
     */
    fun enqueuePost(
        post: PostEntity,
        slideHints: List<CarouselSlideHint> = emptyList(),
        userInitiated: Boolean = true,
        forceRedownload: Boolean = false
    ) {
        val postId = post.id
        if (activeJobs[postId]?.isActive == true) {
            if (!forceRedownload) return
            cancelDownload(postId)
        }

        updateState(postId, PostDownloadState.Queued)
        _queuedDownloadCount.value = activeJobs.size + 1

        val job = scope.launch {
            val processId = "dl_${postId}_${UUID.randomUUID().toString().take(8)}"
            activeProcessIds[postId] = processId
            val stagingDir = File(appContext.cacheDir, "ytdlp_staging/$processId")

            try {
                withDynamicSlot {
                    executeJob(
                        post = post,
                        slideHints = slideHints,
                        processId = processId,
                        stagingDir = stagingDir,
                        userInitiated = userInitiated
                    )
                }
            } catch (_: CancellationException) {
                updateState(postId, PostDownloadState.Cancelled)
            } catch (e: Exception) {
                val safeMsg = LogSanitizer.redact(e.message ?: "Download error")
                AppLogger.e(TAG, "Orchestrator error for $postId: $safeMsg")
                updateState(postId, PostDownloadState.Failed(safeMsg))
            } finally {
                activeProviderMap.remove(postId)
                activeProcessIds.remove(postId)
                activeJobs.remove(postId)
                _queuedDownloadCount.value = activeJobs.size
                try {
                    stagingDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
        }

        activeJobs[postId] = job
        _queuedDownloadCount.value = activeJobs.size
    }

    fun cancelDownload(postId: String) {
        val processId = activeProcessIds[postId]
        val provider = activeProviderMap[postId]
        if (processId != null) {
            provider?.cancel(processId)
            ytDlpProvider.cancel(processId)
            nativeGraphProvider.cancel(processId)
            daemonBridgeProvider.cancel(processId)
        }
        activeJobs[postId]?.cancel()
        activeJobs.remove(postId)
        updateState(postId, PostDownloadState.Cancelled)
    }

    fun cancelAll() {
        val ids = activeJobs.keys.toList()
        ids.forEach { cancelDownload(it) }
    }

    private suspend fun executeJob(
        post: PostEntity,
        slideHints: List<CarouselSlideHint>,
        processId: String,
        stagingDir: File,
        userInitiated: Boolean
    ) {
        val cookie = sessionManager.getCookieString().takeIf { it.isNotBlank() }
        val request = MediaDownloadRequest(
            processId = processId,
            username = post.username,
            shortcode = post.id,
            mediaTypeHint = post.mediaType.ifBlank { if (post.isVideo) "video" else "image" },
            displayUrl = post.displayUrl,
            cookieString = cookie,
            qualityPreset = settingsManager.ytDlpQualityPreset.value,
            slideHints = slideHints,
            userInitiated = userInitiated
        )

        val providerChain = buildProviderChain(request, userInitiated)
        var finalSuccess: ProviderOutcome.Success? = null
        var lastError = "No available media provider succeeded"

        for (provider in providerChain) {
            if (!provider.isAvailable()) continue
            if (!userInitiated && !healthTracker.canUseProvider(provider.id)) continue

            activeProviderMap[post.id] = provider
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            when (val outcome = provider.downloadPost(request, stagingDir) { state ->
                updateState(post.id, state)
            }) {
                is ProviderOutcome.Success -> {
                    healthTracker.recordSuccess(provider.id)
                    finalSuccess = outcome
                    break
                }
                is ProviderOutcome.UnsupportedMedia -> {
                    // Expected for yt-dlp on photo-only posts -> silently advance to NativeGraphProvider
                    Log.d(TAG, "${provider.id} skipped ${post.id}: ${outcome.reason}")
                }
                is ProviderOutcome.RateLimited -> {
                    healthTracker.recordRateLimit(provider.id, outcome.retryAfterMs, outcome.detail)
                    lastError = outcome.detail
                }
                is ProviderOutcome.LoginRequired -> {
                    healthTracker.recordLoginRequired(provider.id, outcome.detail)
                    lastError = outcome.detail
                }
                is ProviderOutcome.Cancelled -> {
                    updateState(post.id, PostDownloadState.Cancelled)
                    return
                }
                is ProviderOutcome.Failure -> {
                    healthTracker.recordFailure(provider.id, outcome.message)
                    lastError = outcome.message
                }
            }
        }

        if (finalSuccess == null) {
            database.postDao().getPostById(post.id)?.let { current ->
                database.postDao().insertPost(current.copy(downloadStatus = "failed"))
            }
            updateState(post.id, PostDownloadState.Failed(lastError))
            return
        }

        // Atomic validation, SHA-256 deduplication, and AES-256-GCM Vault ingestion
        updateState(post.id, PostDownloadState.Encrypting)
        val ingested = ingestIntoVault(post, finalSuccess)
        if (ingested) {
            updateState(post.id, PostDownloadState.Completed(finalSuccess.providerId))
        } else {
            database.postDao().getPostById(post.id)?.let { current ->
                database.postDao().insertPost(current.copy(downloadStatus = "failed"))
            }
            updateState(post.id, PostDownloadState.Failed("Downloaded media failed integrity validation"))
        }
    }

    internal fun buildProviderChain(
        request: MediaDownloadRequest,
        userInitiated: Boolean
    ): List<InstagramMediaProvider> {
        val chain = mutableListOf<InstagramMediaProvider>()
        val preferYtDlp = request.mediaTypeHint.equals("video", ignoreCase = true) ||
            request.mediaTypeHint.equals("reel", ignoreCase = true) ||
            (userInitiated && request.mediaTypeHint.equals("carousel", ignoreCase = true))

        if (preferYtDlp) {
            chain.add(ytDlpProvider)
            chain.add(nativeGraphProvider)
        } else {
            chain.add(nativeGraphProvider)
            // If user explicitly requests redownload on an unknown/photo post that may actually be a reel
            if (userInitiated) {
                chain.add(ytDlpProvider)
            }
        }

        if (settingsManager.daemonSyncEnabled.value) {
            chain.add(daemonBridgeProvider)
        }
        return chain
    }

    private suspend fun ingestIntoVault(
        post: PostEntity,
        success: ProviderOutcome.Success
    ): Boolean {
        val assetDao = database.mediaAssetDao()
        val postDao = database.postDao()
        val persistedAssets = mutableListOf<MediaAssetEntity>()
        val now = System.currentTimeMillis()

        for ((idx, staged) in success.assets.withIndex()) {
            val validated = MediaFileValidator.validateAndHash(staged.mediaFile) ?: continue

            // SHA-256 deduplication: reuse existing encrypted Vault file if identical binary already archived
            val existingDup = assetDao.findByHash(post.username, validated.sha256)
            val existingFile = existingDup?.localVaultPath?.let { File(it) }
            val vaultPath = if (existingFile != null && existingFile.exists() && existingFile.length() > 0L) {
                existingFile.absolutePath
            } else {
                val plainBytes = staged.mediaFile.readBytes()
                val encFile = vaultStorageManager.saveEncryptedFile(
                    targetUsername = post.username,
                    category = "posts",
                    filename = "${post.id}_${staged.position}.${validated.extension}",
                    plaintextBytes = plainBytes
                )
                encFile.absolutePath
            }

            // Encrypt thumbnail if present and valid
            var thumbVaultPath: String? = null
            val thumbCandidate = staged.thumbFile
            if (thumbCandidate != null && thumbCandidate.exists() && thumbCandidate.length() > 0L) {
                val validThumb = MediaFileValidator.validateAndHash(thumbCandidate)
                if (validThumb != null && !validThumb.isVideo) {
                    val thumbEnc = vaultStorageManager.saveEncryptedFile(
                        targetUsername = post.username,
                        category = "posts",
                        filename = "${post.id}_${staged.position}_thumb.${validThumb.extension}",
                        plaintextBytes = thumbCandidate.readBytes()
                    )
                    thumbVaultPath = thumbEnc.absolutePath
                }
            }

            persistedAssets.add(
                MediaAssetEntity(
                    id = "${post.id}_${staged.position}",
                    postId = post.id,
                    username = post.username,
                    position = if (staged.position >= 0) staged.position else idx,
                    mediaType = if (validated.isVideo) "video" else "image",
                    remoteUrl = staged.remoteUrl.ifBlank { post.displayUrl },
                    localVaultPath = vaultPath,
                    thumbVaultPath = thumbVaultPath,
                    mimeType = validated.mimeType,
                    fileSize = validated.fileSize,
                    sha256Hash = validated.sha256,
                    width = staged.width,
                    height = staged.height,
                    durationSeconds = staged.durationSeconds,
                    providerName = success.providerId,
                    archivedAt = now
                )
            )
        }

        if (persistedAssets.isEmpty()) return false

        assetDao.upsertAssets(persistedAssets)

        val primary = persistedAssets.first()
        val anyVideo = persistedAssets.any { it.mediaType == "video" }
        val resolvedMediaType = when {
            persistedAssets.size > 1 -> "carousel"
            anyVideo -> "video"
            else -> "image"
        }
        val firstThumb = persistedAssets.firstOrNull { !it.thumbVaultPath.isNullOrBlank() }?.thumbVaultPath
            ?: persistedAssets.firstOrNull { it.mediaType == "image" }?.localVaultPath

        val currentPost = postDao.getPostById(post.id) ?: post
        val meta = success.metadata
        val updatedPost = currentPost.copy(
            caption = meta?.caption?.cleanDisplayText()?.ifBlank { null } ?: currentPost.caption,
            displayUrl = meta?.displayUrl?.ifBlank { null } ?: currentPost.displayUrl,
            localPath = primary.localVaultPath ?: currentPost.localPath,
            localThumbPath = firstThumb ?: currentPost.localThumbPath,
            isVideo = anyVideo,
            mediaType = resolvedMediaType,
            slideCount = persistedAssets.size,
            durationSeconds = primary.durationSeconds ?: meta?.durationSeconds ?: currentPost.durationSeconds,
            likeCount = meta?.likeCount?.takeIf { it > 0 } ?: currentPost.likeCount,
            commentCount = meta?.commentCount?.takeIf { it > 0 } ?: currentPost.commentCount,
            postedAt = meta?.postedAtMillis?.takeIf { it > 0L } ?: currentPost.postedAt,
            downloadEngine = success.providerId,
            downloadStatus = "complete"
        )
        postDao.insertPost(updatedPost)
        return true
    }

    private fun isOnUnmeteredWifi(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun updateState(postId: String, state: PostDownloadState) {
        _downloadStates.value = _downloadStates.value + (postId to state)
    }

    companion object {
        private const val TAG = "MediaOrchestrator"
        private const val MAX_CONCURRENT_DOWNLOADS = 2
    }
}
