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
    private val pausedPostIds = ConcurrentHashMap.newKeySet<String>()
    private val itemSpecs = ConcurrentHashMap<String, Pair<PostEntity, List<CarouselSlideHint>>>()
    private val queueMap = ConcurrentHashMap<String, DownloadQueueItem>()

    private val _downloadStates = MutableStateFlow<Map<String, PostDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, PostDownloadState>> = _downloadStates.asStateFlow()

    private val _queueItems = MutableStateFlow<List<DownloadQueueItem>>(emptyList())
    val queueItems: StateFlow<List<DownloadQueueItem>> = _queueItems.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    private val _totalSpeedBytesPerSec = MutableStateFlow(0L)
    val totalSpeedBytesPerSec: StateFlow<Long> = _totalSpeedBytesPerSec.asStateFlow()

    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    private val _queuedDownloadCount = MutableStateFlow(0)
    val queuedDownloadCount: StateFlow<Int> = _queuedDownloadCount.asStateFlow()

    private suspend fun <T> withDynamicSlot(postId: String, block: suspend () -> T): T {
        while (true) {
            if (_isQueuePaused.value || pausedPostIds.contains(postId)) {
                throw PausedSignalException()
            }
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

    private class PausedSignalException : CancellationException("Download paused by user")

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
        itemSpecs[postId] = post to slideHints
        val existingItem = queueMap[postId]
        val now = System.currentTimeMillis()
        queueMap[postId] = DownloadQueueItem(
            postId = postId,
            username = post.username,
            mediaType = post.mediaType.ifBlank { if (post.isVideo) "video" else "image" },
            thumbnailUrl = post.localThumbPath?.takeIf { File(it).exists() } ?: post.displayUrl,
            slideCount = post.slideCount.coerceAtLeast(1),
            state = if (!userInitiated && _isQueuePaused.value) PostDownloadState.Paused else PostDownloadState.Queued,
            providerName = existingItem?.providerName,
            enqueuedAt = existingItem?.enqueuedAt ?: now,
            updatedAt = now
        )

        if (userInitiated) {
            pausedPostIds.remove(postId)
            if (_isQueuePaused.value) {
                _isQueuePaused.value = false
            }
        } else if (_isQueuePaused.value || pausedPostIds.contains(postId)) {
            pausedPostIds.add(postId)
            updateState(postId, PostDownloadState.Paused)
            return
        }

        if (activeJobs[postId]?.isActive == true) {
            if (!forceRedownload) return
            cancelActiveProcessOnly(postId)
        }

        updateState(postId, PostDownloadState.Queued)
        _queuedDownloadCount.value = activeJobs.size + 1

        val job = scope.launch {
            val processId = "dl_${postId}_${UUID.randomUUID().toString().take(8)}"
            activeProcessIds[postId] = processId
            val stagingDir = File(appContext.cacheDir, "ytdlp_staging/$processId")

            try {
                withDynamicSlot(postId) {
                    executeJob(
                        post = post,
                        slideHints = slideHints,
                        processId = processId,
                        stagingDir = stagingDir,
                        userInitiated = userInitiated
                    )
                }
            } catch (_: PausedSignalException) {
                updateState(postId, PostDownloadState.Paused)
            } catch (_: CancellationException) {
                if (pausedPostIds.contains(postId) || _isQueuePaused.value) {
                    updateState(postId, PostDownloadState.Paused)
                } else {
                    updateState(postId, PostDownloadState.Cancelled)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (pausedPostIds.contains(postId) || _isQueuePaused.value) {
                    updateState(postId, PostDownloadState.Paused)
                } else {
                    val safeMsg = LogSanitizer.redact(e.message ?: e::class.java.simpleName)
                    AppLogger.e(TAG, "Orchestrator error for $postId: $safeMsg")
                    updateState(postId, PostDownloadState.Failed(safeMsg))
                }
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

    private fun cancelActiveProcessOnly(postId: String) {
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
    }

    fun pauseDownload(postId: String) {
        pausedPostIds.add(postId)
        cancelActiveProcessOnly(postId)
        updateState(postId, PostDownloadState.Paused)
        _queuedDownloadCount.value = activeJobs.size
    }

    fun resumeDownload(postId: String) {
        pausedPostIds.remove(postId)
        val spec = itemSpecs[postId]
        if (spec != null) {
            enqueuePost(
                post = spec.first,
                slideHints = spec.second,
                userInitiated = true,
                forceRedownload = true
            )
        } else {
            scope.launch {
                val post = database.postDao().getPostById(postId) ?: return@launch
                enqueuePost(post = post, userInitiated = true, forceRedownload = true)
            }
        }
    }

    fun pauseAll() {
        _isQueuePaused.value = true
        val idsToPause = (activeJobs.keys + queueMap.values.filter { it.state.isActive }.map { it.postId }).distinct()
        idsToPause.forEach { postId ->
            pausedPostIds.add(postId)
            cancelActiveProcessOnly(postId)
            updateState(postId, PostDownloadState.Paused)
        }
        _queuedDownloadCount.value = activeJobs.size
        publishQueueSnapshot()
    }

    fun resumeAll() {
        _isQueuePaused.value = false
        val idsToResume = (pausedPostIds.toList() + queueMap.values.filter { it.state is PostDownloadState.Paused }.map { it.postId }).distinct()
        pausedPostIds.clear()
        idsToResume.forEach { postId ->
            val spec = itemSpecs[postId]
            if (spec != null) {
                enqueuePost(
                    post = spec.first,
                    slideHints = spec.second,
                    userInitiated = false,
                    forceRedownload = true
                )
            } else {
                scope.launch {
                    val post = database.postDao().getPostById(postId) ?: return@launch
                    enqueuePost(post = post, userInitiated = false, forceRedownload = true)
                }
            }
        }
        publishQueueSnapshot()
    }

    fun cancelDownload(postId: String) {
        pausedPostIds.remove(postId)
        cancelActiveProcessOnly(postId)
        updateState(postId, PostDownloadState.Cancelled)
        _queuedDownloadCount.value = activeJobs.size
    }

    fun cancelAll() {
        _isQueuePaused.value = false
        val ids = (activeJobs.keys + pausedPostIds + queueMap.values.filter { it.state.isActive || it.state.isPaused }.map { it.postId }).distinct()
        pausedPostIds.clear()
        ids.forEach { cancelDownload(it) }
    }

    fun retryDownload(postId: String) {
        resumeDownload(postId)
    }

    fun dismissItem(postId: String) {
        if (activeJobs[postId]?.isActive == true) {
            cancelDownload(postId)
        }
        pausedPostIds.remove(postId)
        queueMap.remove(postId)
        itemSpecs.remove(postId)
        _downloadStates.value = _downloadStates.value - postId
        publishQueueSnapshot()
    }

    fun clearFinished() {
        val terminalIds = queueMap.values.filter { it.state.isTerminal }.map { it.postId }
        terminalIds.forEach { id ->
            queueMap.remove(id)
            itemSpecs.remove(id)
            _downloadStates.value = _downloadStates.value - id
        }
        publishQueueSnapshot()
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
                    if (pausedPostIds.contains(post.id) || _isQueuePaused.value) {
                        updateState(post.id, PostDownloadState.Paused)
                    } else {
                        updateState(post.id, PostDownloadState.Cancelled)
                    }
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
                val encFile = vaultStorageManager.saveEncryptedFile(
                    targetUsername = post.username,
                    category = "posts",
                    filename = "${post.id}_${staged.position}.${validated.extension}",
                    sourceFile = staged.mediaFile
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
                        sourceFile = thumbCandidate
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
        val now = System.currentTimeMillis()
        val prev = queueMap[postId]
        val spec = itemSpecs[postId]?.first
        val providerId = activeProviderMap[postId]?.id
            ?: (state as? PostDownloadState.Completed)?.providerId
            ?: prev?.providerName

        val dlState = state as? PostDownloadState.Downloading
        val updatedItem = (prev ?: DownloadQueueItem(
            postId = postId,
            username = spec?.username ?: "",
            mediaType = spec?.mediaType ?: "image",
            thumbnailUrl = spec?.displayUrl ?: "",
            slideCount = spec?.slideCount ?: 1,
            enqueuedAt = now
        )).copy(
            state = state,
            providerName = providerId,
            updatedAt = now,
            downloadedBytes = when {
                dlState != null && dlState.downloadedBytes > 0L -> dlState.downloadedBytes
                state is PostDownloadState.Completed && prev != null && prev.totalBytes > 0L -> prev.totalBytes
                else -> prev?.downloadedBytes ?: 0L
            },
            totalBytes = when {
                dlState != null && dlState.totalBytes > 0L -> dlState.totalBytes
                else -> prev?.totalBytes ?: -1L
            },
            speedBytesPerSec = dlState?.speedBytesPerSec ?: 0L,
            etaSeconds = dlState?.etaSeconds ?: -1L,
            progress = when (state) {
                is PostDownloadState.Downloading -> state.progress.coerceIn(0f, 100f)
                is PostDownloadState.Merging -> 96f
                is PostDownloadState.Encrypting -> 98f
                is PostDownloadState.Completed -> 100f
                is PostDownloadState.Queued -> 0f
                else -> prev?.progress ?: 0f
            },
            detail = when (state) {
                is PostDownloadState.Downloading -> state.detail
                is PostDownloadState.Failed -> state.message
                is PostDownloadState.Completed -> state.providerId
                else -> prev?.detail
            }
        )
        queueMap[postId] = updatedItem
        publishQueueSnapshot()
    }

    private fun publishQueueSnapshot() {
        // Prune oldest terminal items beyond 40 so queue stays lean
        val terminal = queueMap.values.filter { it.state.isTerminal }.sortedByDescending { it.updatedAt }
        if (terminal.size > 40) {
            terminal.drop(40).forEach { old ->
                queueMap.remove(old.postId)
                itemSpecs.remove(old.postId)
            }
        }

        val sorted = queueMap.values.sortedWith(
            compareBy<DownloadQueueItem> { item ->
                when (item.state) {
                    is PostDownloadState.Downloading,
                    is PostDownloadState.Merging,
                    is PostDownloadState.Encrypting,
                    is PostDownloadState.Extracting -> 0
                    is PostDownloadState.Queued -> 1
                    is PostDownloadState.Paused -> 2
                    is PostDownloadState.Failed -> 3
                    is PostDownloadState.Cancelled -> 4
                    is PostDownloadState.Completed -> 5
                    is PostDownloadState.Idle -> 6
                }
            }.thenByDescending { it.updatedAt }
        )
        _queueItems.value = sorted
        _totalSpeedBytesPerSec.value = sorted
            .filter { it.state is PostDownloadState.Downloading }
            .sumOf { it.speedBytesPerSec }
    }

    companion object {
        private const val TAG = "MediaOrchestrator"
        private const val MAX_CONCURRENT_DOWNLOADS = 2
    }
}
