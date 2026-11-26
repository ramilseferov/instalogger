package com.instalogger.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.*
import com.instalogger.app.engine.media.CarouselSlideHint
import com.instalogger.app.engine.media.MediaDownloadOrchestrator
import com.instalogger.app.engine.pool.*
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.LanguageManager
import com.instalogger.app.util.VaultStorageManager
import com.instalogger.app.util.cleanDisplayText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

enum class PostSyncStatus {
    IDLE, SYNCING, COMPLETED, REQUIRES_SESSION, RATE_LIMITED, FAILED
}

class ProfileInspector(
    private val context: Context,
    private val database: AppDatabase,
    private val sessionManager: SessionManager,
    private val instagramEngine: InstagramEngine = InstagramEngine(context),
    private val mediaDownloadOrchestrator: MediaDownloadOrchestrator? = null,
    private val onAlertNotification: (title: String, message: String) -> Unit = { _, _ -> }
) {

    private val _postSyncState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, PostSyncStatus>>(emptyMap())
    val postSyncState: kotlinx.coroutines.flow.StateFlow<Map<String, PostSyncStatus>> = _postSyncState

    private val _postBackfillProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val postBackfillProgress: kotlinx.coroutines.flow.StateFlow<Map<String, Pair<Int, Int>>> = _postBackfillProgress

    private val _commentSyncProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val commentSyncProgress: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _commentSyncProgress

    private val commentSyncMutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val appBackgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val inFlightInspections = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<TargetEntity>>>()
    private val inspectionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val archiveMutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val lastHighlightTrayCheckMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastActiveStoriesCheckMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastFriendshipsCheckMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val unfollowedPrivateUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun getSettingsManager(): AppSettingsManager {
        return (context.applicationContext as? InstaLoggerApp)?.settingsManager
            ?: AppSettingsManager.getInstance(context)
    }

    private suspend fun dispatchAlert(
        targetUsername: String,
        eventType: String,
        title: String,
        message: String,
        timestamp: Long
    ) {
        val app = context.applicationContext as? InstaLoggerApp
        val settings = app?.settingsManager

        // Deduplication guard
        val latestNotif = database.notificationDao().getLatestNotificationForEvent(targetUsername, eventType)
        if (latestNotif != null && latestNotif.message == message && (timestamp - latestNotif.timestamp) < 60_000L) {
            return
        }

        var notifId = timestamp

        if (settings == null || settings.isInAppNotificationAllowed(eventType, targetUsername)) {
            notifId = database.notificationDao().insertNotification(
                NotificationEntity(
                    targetId = targetUsername,
                    username = targetUsername,
                    eventType = eventType,
                    title = title,
                    message = message,
                    timestamp = timestamp
                )
            )
        }

        InstaLoggerForegroundService.instance?.showAlertNotification(
            id = notifId,
            title = title,
            message = message,
            eventType = eventType,
            targetUsername = targetUsername
        )
        onAlertNotification(title, message)
    }

    suspend fun inspectTarget(username: String): Result<TargetEntity> {
        val cleanUser = username.trim().trimStart('@').lowercase()

        // Fast-path guard: Abort immediately if target has been deleted
        if (isTargetDeleted(cleanUser)) {
            return Result.failure(Exception("Target was deleted"))
        }

        val deferred = inFlightInspections.computeIfAbsent(cleanUser) {
            appBackgroundScope.async {
                try {
                    inspectTargetInternal(cleanUser)
                } finally {
                    inFlightInspections.remove(cleanUser)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun inspectTargetInternal(cleanUser: String): Result<TargetEntity> = withContext(Dispatchers.IO) {
        val targetMutex = inspectionLocks.computeIfAbsent(cleanUser) { Mutex() }
        targetMutex.lock()

        try {
            val targetDao = database.targetDao()
            val changeLogDao = database.changeLogDao()
            val avatarHistoryDao = database.avatarHistoryDao()
            val notificationDao = database.notificationDao()

        // 1. Live Instagram inspection (obtains live follower & following count)
        val cookie = sessionManager.getCookieString()
        var directSnapshot: InstagramProfileSnapshot? = null
        val engineResult = instagramEngine.fetchProfile(cleanUser, cookie)
        if (engineResult.isSuccess) {
            directSnapshot = engineResult.getOrNull()
        }

        // 2. Local daemon snapshot at http://127.0.0.1:3000
        val daemonSnapshot = fetchFromLocalDaemon(cleanUser)

        val snapshot = if (directSnapshot != null && (directSnapshot.followersCount > 0 || daemonSnapshot == null)) {
            if (daemonSnapshot != null) {
                directSnapshot.copy(
                    instagramId = directSnapshot.instagramId ?: daemonSnapshot.instagramId,
                    profilePicUrl = daemonSnapshot.profilePicUrl ?: directSnapshot.profilePicUrl
                )
            } else {
                directSnapshot
            }
        } else if (daemonSnapshot != null) {
            if (directSnapshot != null) {
                daemonSnapshot.copy(
                    followersCount = if (directSnapshot.followersCount > 0) directSnapshot.followersCount else daemonSnapshot.followersCount,
                    followingCount = if (directSnapshot.followingCount > 0) directSnapshot.followingCount else daemonSnapshot.followingCount,
                    mediaCount = if (directSnapshot.mediaCount > 0) directSnapshot.mediaCount else daemonSnapshot.mediaCount,
                    latestStoryAt = if (directSnapshot.latestStoryAt > 0L) directSnapshot.latestStoryAt else daemonSnapshot.latestStoryAt
                )
            } else {
                daemonSnapshot
            }
        } else {
            val reason = engineResult.exceptionOrNull()?.message ?: "Profil məlumatları alına bilmədi"
            AppLogger.e("ProfileInspector", "inspectTarget(@$cleanUser) FAILED: $reason")
            return@withContext Result.failure(engineResult.exceptionOrNull() ?: Exception(reason))
        }

        val existing = targetDao.getTarget(cleanUser)
        val now = System.currentTimeMillis()
        val vaultManager = VaultStorageManager.getInstance(context)
        var localAvatarPath = existing?.localAvatarPath
        var currentAvatarHash = existing?.avatarHash

        // Verify both internal Vault avatar AND localAvatarPath actually exist & decrypt cleanly
        val existingInternalVault = ArchiveDirectoryManager.getInternalAvatar(context, cleanUser)
        val hasValidInternalAvatar = existingInternalVault != null && vaultManager.readEncryptedFile(existingInternalVault) != null
        if (!hasValidInternalAvatar) {
            localAvatarPath = null
            currentAvatarHash = null
        } else if (localAvatarPath != null) {
            val fileCheck = File(localAvatarPath)
            if (!fileCheck.exists() || fileCheck.length() == 0L) {
                localAvatarPath = existingInternalVault!!.absolutePath
            }
        }

        // If existing record has local file but no stored hash, compute it lazily
        if (currentAvatarHash == null && localAvatarPath != null) {
            currentAvatarHash = computeFileSha256(File(localAvatarPath))
        }

        // Check avatar change
        val avatarUrl = snapshot.profilePicUrl
        if (!avatarUrl.isNullOrBlank()) {
            if (avatarUrl.startsWith("/")) {
                val f = File(avatarUrl)
                if (f.exists() && f.length() > 0L) {
                    val internal = ArchiveDirectoryManager.copyToInternalAvatar(context, cleanUser, f)
                    localAvatarPath = internal?.absolutePath ?: f.absolutePath
                    currentAvatarHash = computeFileSha256(File(localAvatarPath))
                }
            } else if (!hasValidInternalAvatar ||
                localAvatarPath == null ||
                currentAvatarHash == null ||
                extractAvatarAssetKey(avatarUrl) != extractAvatarAssetKey(existing?.avatarUrl)
            ) {
                AppLogger.i("ProfileInspector", "Downloading avatar for @$cleanUser (hasValidVault=$hasValidInternalAvatar)")
                val tempFile = downloadAvatarToTemp(cleanUser, avatarUrl)
                if (tempFile != null) {
                    val validated = com.instalogger.app.engine.media.MediaFileValidator.validateAndHash(tempFile)
                    if (validated == null || validated.isVideo) {
                        AppLogger.w("ProfileInspector", "Avatar validation rejected for @$cleanUser (non-image or empty response)")
                        tempFile.delete()
                    } else {
                        val plainBytes = try { tempFile.readBytes() } catch (_: Exception) { null }
                        val newHash = validated.sha256
                        val newDHash = computePerceptualDHash(tempFile)
                        tempFile.delete()

                        if (plainBytes != null && plainBytes.isNotEmpty()) {
                            val existingAvatars = avatarHistoryDao.getAvatarsListForTarget(cleanUser)
                            val isExactDup = (currentAvatarHash != null && newHash == currentAvatarHash) ||
                                (newHash.isNotBlank() && existingAvatars.any { it.fileHash == newHash })
                            val isVisualDup = newDHash != null && (
                                (localAvatarPath != null && isVisuallyDuplicateAvatar(newDHash, File(localAvatarPath))) ||
                                    existingAvatars.any { isVisuallyDuplicateAvatar(newDHash, File(it.localPath)) }
                                )
                            val isDuplicateAvatar = hasValidInternalAvatar && (isExactDup || isVisualDup)

                            if (isDuplicateAvatar && localAvatarPath != null && File(localAvatarPath).exists()) {
                                AppLogger.d("ProfileInspector", "Avatar for @$cleanUser unchanged (hash/dHash match)")
                            } else {
                                // Genuinely NEW or healed avatar -> encrypt directly into internal hardware Vault FIRST
                                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                                val timestamp = dateFormat.format(Date(now))
                                val fileName = "avatar_${timestamp}.jpg"

                                val vaultCurrent = vaultManager.saveEncryptedFile(cleanUser, "profile", "avatar_current.jpg", plainBytes)
                                val vaultHistory = vaultManager.saveEncryptedFile(cleanUser, "profile", fileName, plainBytes)

                                try {
                                    val targetDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$cleanUser/profile")
                                    targetDir.mkdirs()
                                    File(targetDir, fileName).writeBytes(plainBytes)
                                    File(targetDir, "avatar_current.jpg").writeBytes(plainBytes)
                                } catch (e: Exception) {
                                    AppLogger.d("ProfileInspector", "External avatar mirror skipped for @$cleanUser: ${e.message}")
                                }

                                val oldPath = localAvatarPath
                                localAvatarPath = vaultHistory.absolutePath
                                val oldHash = currentAvatarHash
                                currentAvatarHash = newHash

                                AppLogger.i("ProfileInspector", "Avatar saved to Vault for @$cleanUser (${plainBytes.size} B -> ${vaultCurrent.name})")

                                avatarHistoryDao.insertAvatar(
                                    AvatarHistoryEntity(
                                        username = cleanUser,
                                        localPath = vaultHistory.absolutePath,
                                        archivedAt = now,
                                        fileSize = plainBytes.size.toLong(),
                                        fileHash = newHash
                                    )
                                )

                                if (existing != null && oldPath != null && oldHash != null && newHash != oldHash) {
                                    val title = LanguageManager.getString(context, R.string.notif_avatar_changed_title, cleanUser)
                                    val desc = LanguageManager.getString(context, R.string.notif_avatar_changed_desc)
                                    changeLogDao.insertLog(
                                        ChangeLogEntity(
                                            targetUsername = cleanUser,
                                            eventType = "avatar_change",
                                            title = title,
                                            oldValue = oldHash,
                                            newValue = newHash,
                                            oldAvatarPath = oldPath,
                                            newAvatarPath = vaultHistory.absolutePath,
                                            timestamp = now
                                        )
                                    )
                                    dispatchAlert(
                                        targetUsername = cleanUser,
                                        eventType = "avatar_change",
                                        title = title,
                                        message = desc,
                                        timestamp = now
                                    )
                                }
                            }
                        }
                    }
                } else {
                    AppLogger.w("ProfileInspector", "Avatar temp download returned null for @$cleanUser")
                }
            }
        }

        // Also import existing archives if available on disk and deduplicate
        importLocalArchives(cleanUser, snapshot.instagramId)
        deduplicateAvatars(cleanUser)
        importLocalPostsAndHighlights(cleanUser, snapshot.instagramId)
        fetchMediaFromLocalDaemon(cleanUser, snapshot.instagramId)

        // 2. Check privacy change
        val wasPrivateToPublic = (existing != null && existing.isPrivate && !snapshot.isPrivate)
        if (existing != null && existing.isPrivate != snapshot.isPrivate) {
            val oldVal = if (existing.isPrivate) LanguageManager.getString(context, R.string.notif_privacy_private) else LanguageManager.getString(context, R.string.notif_privacy_public)
            val newVal = if (snapshot.isPrivate) LanguageManager.getString(context, R.string.notif_privacy_private) else LanguageManager.getString(context, R.string.notif_privacy_public)
            val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "privacy_change")
            if (latestLog?.newValue != newVal) {
                val title = LanguageManager.getString(context, R.string.notif_privacy_changed_title, cleanUser)
                val desc = LanguageManager.getString(context, R.string.notif_privacy_changed_desc, newVal)
                changeLogDao.insertLog(
                    ChangeLogEntity(
                        targetUsername = cleanUser,
                        eventType = "privacy_change",
                        title = title,
                        oldValue = oldVal,
                        newValue = newVal,
                        timestamp = now
                    )
                )
                dispatchAlert(
                    targetUsername = cleanUser,
                    eventType = "privacy_change",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            }
        }

        // 3. Check post count change
        val postCountDropped = existing != null && snapshot.mediaCount > 0 && snapshot.mediaCount < existing.mediaCount
        if (existing != null && existing.mediaCount != snapshot.mediaCount && snapshot.mediaCount > 0) {
            val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "post_count_change")
            if (latestLog?.newValue != snapshot.mediaCount.toString()) {
                val diff = snapshot.mediaCount - existing.mediaCount
                val sign = if (diff > 0) "+$diff" else "$diff"
                val title = LanguageManager.getString(context, R.string.notif_posts_changed_title, cleanUser, sign)
                val desc = LanguageManager.getString(context, R.string.notif_posts_changed_desc, existing.mediaCount.toString(), snapshot.mediaCount.toString(), sign)
                changeLogDao.insertLog(
                    ChangeLogEntity(
                        targetUsername = cleanUser,
                        eventType = "post_count_change",
                        title = title,
                        oldValue = existing.mediaCount.toString(),
                        newValue = snapshot.mediaCount.toString(),
                        timestamp = now
                    )
                )
                dispatchAlert(
                    targetUsername = cleanUser,
                    eventType = "post_count_change",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            }
        }

        // 4. Check biography change
        if (existing != null && snapshot.biography.isNotBlank() && existing.biography != snapshot.biography) {
            val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "bio_change")
            if (latestLog?.newValue != snapshot.biography) {
                val title = LanguageManager.getString(context, R.string.notif_bio_changed_title, cleanUser)
                val desc = snapshot.biography.take(80)
                changeLogDao.insertLog(
                    ChangeLogEntity(
                        targetUsername = cleanUser,
                        eventType = "bio_change",
                        title = title,
                        oldValue = existing.biography,
                        newValue = snapshot.biography,
                        timestamp = now
                    )
                )
            }
        }

        // Check followers count change via FollowerPoolEngine
        val settingsManager = (context.applicationContext as? InstaLoggerApp)?.settingsManager
            ?: AppSettingsManager.getInstance(context)
        val followerPolicy = settingsManager.resolvePoolPolicy(existing, isFollowing = false)
        var nextFollowerPoolState = existing?.followerPoolState() ?: PoolMetricState(
            baseline = snapshot.followersCount,
            startedAt = now
        )
        var keepPreviousFollowerCountForFlicker = false

        if (existing != null && existing.followersCount > 0 && snapshot.followersCount > 0) {
            val shouldEvaluateFollowers = existing.followersCount != snapshot.followersCount ||
                (followerPolicy.mode != FollowerPoolMode.OFF && followerPolicy.digestWindowMinutes > 0 &&
                    nextFollowerPoolState.baseline > 0L && nextFollowerPoolState.baseline != snapshot.followersCount)
            if (shouldEvaluateFollowers) {
                val eval = FollowerPoolEngine.evaluateChange(
                    state = nextFollowerPoolState,
                    previousCount = existing.followersCount,
                    newCount = snapshot.followersCount,
                    policy = followerPolicy,
                    nowMs = now
                )
                nextFollowerPoolState = eval.updatedState
                if (eval.action == PoolAction.SUPPRESSED_FLICKER && followerPolicy.mode == FollowerPoolMode.OFF) {
                    keepPreviousFollowerCountForFlicker = true
                }

                when (eval.action) {
                    PoolAction.INSTANT_ALERT -> {
                        val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "followers_change")
                        if (latestLog?.newValue != snapshot.followersCount.toString()) {
                            val sign = if (eval.netDelta > 0) "+${eval.netDelta}" else "${eval.netDelta}"
                            val title = LanguageManager.getString(context, R.string.notif_followers_changed_title, cleanUser, sign)
                            val desc = LanguageManager.getString(context, R.string.notif_followers_changed_desc, eval.baselineCount, eval.newCount, sign)
                            changeLogDao.insertLog(
                                ChangeLogEntity(
                                    targetUsername = cleanUser,
                                    eventType = "followers_change",
                                    title = title,
                                    oldValue = eval.baselineCount.toString(),
                                    newValue = eval.newCount.toString(),
                                    timestamp = now
                                )
                            )
                            dispatchAlert(cleanUser, "followers_change", title, desc, now)
                        }
                    }
                    PoolAction.FLUSH_THRESHOLD -> {
                        val sign = if (eval.netDelta > 0) "+${eval.netDelta}" else "${eval.netDelta}"
                        val title = LanguageManager.getString(context, R.string.notif_pool_flush_title, cleanUser, sign)
                        val desc = LanguageManager.getString(
                            context,
                            R.string.notif_pool_flush_desc,
                            eval.baselineCount,
                            eval.newCount,
                            sign,
                            eval.grossGain,
                            eval.grossLoss
                        )
                        changeLogDao.insertLog(
                            ChangeLogEntity(
                                targetUsername = cleanUser,
                                eventType = "follower_pool_flush",
                                title = title,
                                oldValue = eval.baselineCount.toString(),
                                newValue = eval.newCount.toString(),
                                timestamp = now
                            )
                        )
                        dispatchAlert(cleanUser, "follower_pool_flush", title, desc, now)
                    }
                    PoolAction.FLUSH_DIGEST -> {
                        val sign = if (eval.netDelta > 0) "+${eval.netDelta}" else "${eval.netDelta}"
                        val title = LanguageManager.getString(context, R.string.notif_pool_digest_title, cleanUser, sign)
                        val desc = LanguageManager.getString(
                            context,
                            R.string.notif_pool_flush_desc,
                            eval.baselineCount,
                            eval.newCount,
                            sign,
                            eval.grossGain,
                            eval.grossLoss
                        )
                        changeLogDao.insertLog(
                            ChangeLogEntity(
                                targetUsername = cleanUser,
                                eventType = "follower_pool_flush",
                                title = title,
                                oldValue = eval.baselineCount.toString(),
                                newValue = eval.newCount.toString(),
                                timestamp = now
                            )
                        )
                        dispatchAlert(cleanUser, "follower_pool_flush", title, desc, now)
                    }
                    PoolAction.FLUSH_SPIKE -> {
                        val sign = if (eval.netDelta > 0) "+${eval.netDelta}" else "${eval.netDelta}"
                        val title = LanguageManager.getString(context, R.string.notif_pool_spike_title, cleanUser, sign)
                        val desc = LanguageManager.getString(
                            context,
                            R.string.notif_pool_flush_desc,
                            eval.baselineCount,
                            eval.newCount,
                            sign,
                            eval.grossGain,
                            eval.grossLoss
                        )
                        changeLogDao.insertLog(
                            ChangeLogEntity(
                                targetUsername = cleanUser,
                                eventType = "follower_spike",
                                title = title,
                                oldValue = eval.baselineCount.toString(),
                                newValue = eval.newCount.toString(),
                                timestamp = now
                            )
                        )
                        dispatchAlert(cleanUser, "follower_spike", title, desc, now)
                    }
                    PoolAction.ACCUMULATE, PoolAction.SUPPRESSED_COOLDOWN -> {
                        if (eval.shouldWriteAuditLog && eval.tickDelta != 0L) {
                            val sign = if (eval.tickDelta > 0) "+${eval.tickDelta}" else "${eval.tickDelta}"
                            val title = LanguageManager.getString(context, R.string.notif_followers_changed_title, cleanUser, sign)
                            changeLogDao.insertLog(
                                ChangeLogEntity(
                                    targetUsername = cleanUser,
                                    eventType = "followers_change",
                                    title = title,
                                    oldValue = existing.followersCount.toString(),
                                    newValue = snapshot.followersCount.toString(),
                                    timestamp = now
                                )
                            )
                        }
                    }
                    PoolAction.NONE, PoolAction.SUPPRESSED_FLICKER -> Unit
                }
            }
        } else if (nextFollowerPoolState.baseline <= 0L && snapshot.followersCount > 0L) {
            nextFollowerPoolState = nextFollowerPoolState.copy(baseline = snapshot.followersCount, startedAt = now)
        }

        // Check following count change
        val followingPolicy = settingsManager.resolvePoolPolicy(existing, isFollowing = true)
        var nextFollowingPoolState = existing?.followingPoolState() ?: PoolMetricState(
            baseline = snapshot.followingCount,
            startedAt = now
        )

        if (existing != null && existing.followingCount > 0 && snapshot.followingCount > 0) {
            val shouldEvaluateFollowing = existing.followingCount != snapshot.followingCount ||
                (followingPolicy.mode != FollowerPoolMode.OFF && followingPolicy.digestWindowMinutes > 0 &&
                    nextFollowingPoolState.baseline > 0L && nextFollowingPoolState.baseline != snapshot.followingCount)
            if (shouldEvaluateFollowing) {
                val eval = FollowerPoolEngine.evaluateChange(
                    state = nextFollowingPoolState,
                    previousCount = existing.followingCount,
                    newCount = snapshot.followingCount,
                    policy = followingPolicy,
                    nowMs = now
                )
                nextFollowingPoolState = eval.updatedState
                when (eval.action) {
                    PoolAction.INSTANT_ALERT -> {
                        val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "following_change")
                        if (latestLog?.newValue != snapshot.followingCount.toString()) {
                            val sign = if (eval.netDelta > 0) "+${eval.netDelta}" else "${eval.netDelta}"
                            val title = LanguageManager.getString(context, R.string.notif_following_changed_title, cleanUser, sign)
                            val desc = LanguageManager.getString(context, R.string.notif_following_changed_desc, eval.baselineCount, eval.newCount, sign)
                            changeLogDao.insertLog(
                                ChangeLogEntity(
                                    targetUsername = cleanUser,
                                    eventType = "following_change",
                                    title = title,
                                    oldValue = eval.baselineCount.toString(),
                                    newValue = eval.newCount.toString(),
                                    timestamp = now
                                )
                            )
                            dispatchAlert(cleanUser, "following_change", title, desc, now)
                        }
                    }
                    PoolAction.FLUSH_THRESHOLD, PoolAction.FLUSH_DIGEST, PoolAction.FLUSH_SPIKE -> {
                        val sign = if (eval.netDelta > 0) "+${eval.netDelta}" else "${eval.netDelta}"
                        val title = LanguageManager.getString(context, R.string.notif_following_changed_title, cleanUser, sign)
                        val desc = LanguageManager.getString(context, R.string.notif_following_changed_desc, eval.baselineCount, eval.newCount, sign)
                        changeLogDao.insertLog(
                            ChangeLogEntity(
                                targetUsername = cleanUser,
                                eventType = "following_pool_flush",
                                title = title,
                                oldValue = eval.baselineCount.toString(),
                                newValue = eval.newCount.toString(),
                                timestamp = now
                            )
                        )
                        dispatchAlert(cleanUser, "following_pool_flush", title, desc, now)
                    }
                    PoolAction.ACCUMULATE, PoolAction.SUPPRESSED_COOLDOWN -> {
                        if (eval.shouldWriteAuditLog && eval.tickDelta != 0L) {
                            val sign = if (eval.tickDelta > 0) "+${eval.tickDelta}" else "${eval.tickDelta}"
                            val title = LanguageManager.getString(context, R.string.notif_following_changed_title, cleanUser, sign)
                            changeLogDao.insertLog(
                                ChangeLogEntity(
                                    targetUsername = cleanUser,
                                    eventType = "following_change",
                                    title = title,
                                    oldValue = existing.followingCount.toString(),
                                    newValue = snapshot.followingCount.toString(),
                                    timestamp = now
                                )
                            )
                        }
                    }
                    PoolAction.NONE, PoolAction.SUPPRESSED_FLICKER -> Unit
                }
            }
        } else if (nextFollowingPoolState.baseline <= 0L && snapshot.followingCount > 0L) {
            nextFollowingPoolState = nextFollowingPoolState.copy(baseline = snapshot.followingCount, startedAt = now)
        }

        // 7. Check active 24-hour story update (zero-login latest_reel_media signal)
        val resolvedLatestStoryAt = snapshot.latestStoryAt
        if (existing != null && resolvedLatestStoryAt > 0L && resolvedLatestStoryAt > existing.latestStoryAt) {
            val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "story_posted")
            if (latestLog?.newValue != resolvedLatestStoryAt.toString()) {
                val title = LanguageManager.getString(context, R.string.notif_story_posted_title, cleanUser)
                val desc = LanguageManager.getString(context, R.string.notif_story_posted_desc)
                changeLogDao.insertLog(
                    ChangeLogEntity(
                        targetUsername = cleanUser,
                        eventType = "story_posted",
                        title = title,
                        oldValue = if (existing.latestStoryAt > 0L) existing.latestStoryAt.toString() else null,
                        newValue = resolvedLatestStoryAt.toString(),
                        timestamp = now
                    )
                )
                dispatchAlert(
                    targetUsername = cleanUser,
                    eventType = "story_posted",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            }
        }

        // Resolve local avatar path if still null
        if (localAvatarPath == null) {
            localAvatarPath = findExistingLocalAvatar(cleanUser, snapshot.instagramId)
        }

        val freshInDb = targetDao.getTarget(cleanUser) ?: existing
        val resolvedTurbo = freshInDb?.isTurbo ?: existing?.isTurbo ?: false
        val resolvedActive = freshInDb?.isActive ?: existing?.isActive ?: true
        val resolvedInterval = freshInDb?.checkIntervalSeconds ?: existing?.checkIntervalSeconds ?: 60

        val localPostsCount = database.postDao().getPostsCountForUser(cleanUser).toLong()
        val histFollowers = if (snapshot.followersCount > 0) snapshot.followersCount else (freshInDb?.followersCount ?: existing?.followersCount ?: 0L)
        val histFollowing = if (snapshot.followingCount > 0) snapshot.followingCount else (freshInDb?.followingCount ?: existing?.followingCount ?: 0L)
        val resolvedMediaCount = when {
            snapshot.mediaCount > 0L -> snapshot.mediaCount
            localPostsCount > 0L -> localPostsCount
            (freshInDb?.mediaCount ?: 0L) > 0L -> freshInDb!!.mediaCount
            (existing?.mediaCount ?: 0L) > 0L -> existing!!.mediaCount
            else -> 0L
        }

        if (histFollowers > 0 || histFollowing > 0) {
            database.followerHistoryDao().insertHistory(
                FollowerHistoryEntity(
                    targetUsername = cleanUser,
                    followersCount = histFollowers,
                    followingCount = histFollowing,
                    mediaCount = resolvedMediaCount,
                    timestamp = now
                )
            )
        }

        val newProfileHash = computeProfileStateHash(
            username = cleanUser,
            fullName = snapshot.fullName,
            biography = snapshot.biography,
            isPrivate = snapshot.isPrivate,
            followersCount = snapshot.followersCount,
            followingCount = snapshot.followingCount,
            mediaCount = resolvedMediaCount,
            avatarHash = currentAvatarHash
        )

        val storedFollowersCount = if (keepPreviousFollowerCountForFlicker && existing != null) {
            existing.followersCount
        } else if (snapshot.followersCount > 0) {
            snapshot.followersCount
        } else {
            freshInDb?.followersCount ?: existing?.followersCount ?: 0L
        }
        val storedFollowingCount = if (snapshot.followingCount > 0) {
            snapshot.followingCount
        } else {
            freshInDb?.followingCount ?: existing?.followingCount ?: 0L
        }

        val updatedTarget = TargetEntity(
            username = cleanUser,
            instagramId = snapshot.instagramId ?: freshInDb?.instagramId ?: existing?.instagramId,
            fullName = if (snapshot.fullName.isNotBlank()) snapshot.fullName else (freshInDb?.fullName ?: existing?.fullName ?: ""),
            biography = if (snapshot.biography.isNotBlank()) snapshot.biography else (freshInDb?.biography ?: existing?.biography ?: ""),
            isPrivate = snapshot.isPrivate,
            followersCount = storedFollowersCount,
            followingCount = storedFollowingCount,
            mediaCount = resolvedMediaCount,
            avatarUrl = snapshot.profilePicUrl ?: freshInDb?.avatarUrl ?: existing?.avatarUrl,
            localAvatarPath = localAvatarPath,
            avatarHash = currentAvatarHash ?: freshInDb?.avatarHash ?: existing?.avatarHash,
            profileHash = newProfileHash,
            isTurbo = resolvedTurbo,
            isActive = resolvedActive,
            checkIntervalSeconds = resolvedInterval,
            lastCheckedAt = now,
            lastStatusChangeAt = if (freshInDb != null && freshInDb.isPrivate != snapshot.isPrivate) now else (freshInDb?.lastStatusChangeAt ?: existing?.lastStatusChangeAt),
            createdAt = freshInDb?.createdAt ?: existing?.createdAt ?: now,
            latestStoryAt = resolvedLatestStoryAt,
            poolOverrideMode = freshInDb?.poolOverrideMode ?: existing?.poolOverrideMode ?: "GLOBAL",
            poolCustomThreshold = freshInDb?.poolCustomThreshold ?: existing?.poolCustomThreshold ?: 50L,
            poolCustomPercent = freshInDb?.poolCustomPercent ?: existing?.poolCustomPercent ?: 0.5,
            poolCustomDigestMinutes = freshInDb?.poolCustomDigestMinutes ?: existing?.poolCustomDigestMinutes ?: -1,
            poolCustomCooldownMinutes = freshInDb?.poolCustomCooldownMinutes ?: existing?.poolCustomCooldownMinutes ?: -1,
            poolApplyToFollowing = freshInDb?.poolApplyToFollowing ?: existing?.poolApplyToFollowing ?: -1,
            poolFollowerBaseline = if (nextFollowerPoolState.baseline > 0L) nextFollowerPoolState.baseline else storedFollowersCount,
            poolFollowerStartedAt = if (nextFollowerPoolState.startedAt > 0L) nextFollowerPoolState.startedAt else now,
            poolFollowerLastAlertAt = nextFollowerPoolState.lastAlertAt,
            poolFollowerGrossGain = nextFollowerPoolState.grossGain,
            poolFollowerGrossLoss = nextFollowerPoolState.grossLoss,
            poolFollowingBaseline = if (nextFollowingPoolState.baseline > 0L) nextFollowingPoolState.baseline else storedFollowingCount,
            poolFollowingStartedAt = if (nextFollowingPoolState.startedAt > 0L) nextFollowingPoolState.startedAt else now,
            poolFollowingLastAlertAt = nextFollowingPoolState.lastAlertAt,
            poolPendingFlickerCount = nextFollowerPoolState.pendingFlickerCount
        )

        if (isTargetDeleted(cleanUser)) {
            return@withContext Result.failure(Exception("Target was deleted"))
        }

        // IMMEDIATE DATABASE COMMIT
        targetDao.insertTarget(updatedTarget)

        val isUnfollowedPrivate = snapshot.isPrivate && !snapshot.followedByViewer
        if (isUnfollowedPrivate) {
            unfollowedPrivateUntilMs[cleanUser] = now + UNFOLLOWED_PRIVATE_COOLDOWN_MS
        } else if (!snapshot.isPrivate || snapshot.followedByViewer || wasPrivateToPublic) {
            unfollowedPrivateUntilMs.remove(cleanUser)
        }

        // Check and archive followers & following lists in background (throttled to prevent HTTP 429)
        val targetId = snapshot.instagramId ?: existing?.instagramId
        if (!targetId.isNullOrBlank() && !isUnfollowedPrivate) {
            if (!cookie.isNullOrBlank() || wasPrivateToPublic || !snapshot.isPrivate) {
                val countsChanged = existing == null ||
                    storedFollowersCount != existing.followersCount ||
                    storedFollowingCount != existing.followingCount
                val lastFriendAt = lastFriendshipsCheckMs[cleanUser] ?: 0L
                val friendshipDue = wasPrivateToPublic || countsChanged || (now - lastFriendAt >= FRIENDSHIP_REFRESH_INTERVAL_MS)
                if (friendshipDue) {
                    lastFriendshipsCheckMs[cleanUser] = now
                    inspectFollowersList(cleanUser, targetId, cookie, now, storedFollowersCount, settingsManager.listCompletenessGuardEnabled.value)
                    inspectFollowingList(cleanUser, targetId, cookie, now, storedFollowingCount, settingsManager.listCompletenessGuardEnabled.value)
                }
            }
        }

        // 8. Auto archive highlights, active stories and posts (skip unfollowed private accounts)
        val storyGrew = existing != null && resolvedLatestStoryAt > 0L && resolvedLatestStoryAt > existing.latestStoryAt
        if (!isUnfollowedPrivate && (!snapshot.isPrivate || !cookie.isNullOrBlank())) {
            archiveHighlightsAndPosts(
                username = cleanUser,
                targetId = targetId,
                cookie = cookie,
                wasPrivateToPublic = wasPrivateToPublic,
                isPrivate = snapshot.isPrivate,
                forceFullPagination = postCountDropped,
                storyGrew = storyGrew,
                followedByViewer = snapshot.followedByViewer
            )
        } else {
            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.REQUIRES_SESSION)
        }

        Result.success(updatedTarget)
    } finally {
        targetMutex.unlock()
    }
}

    suspend fun updateDaemonTurbo(targetIdentifier: String, isTurbo: Boolean) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? InstaLoggerApp
        if (app?.settingsManager?.daemonSyncEnabled?.value != true) return@withContext
        val daemonUrl = app.settingsManager.daemonHostUrl.value
        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("$daemonUrl/api/targets/$targetIdentifier/turbo")
                .post("{\"turbo\": $isTurbo}".toRequestBody("application/json".toMediaType()))
                .build()
            fastClient.newCall(request).execute().close()
        } catch (_: Exception) {
            // Daemon not reachable or endpoint failed
        }
    }

    private fun fetchFromLocalDaemon(username: String): InstagramProfileSnapshot? {
        val app = context.applicationContext as? InstaLoggerApp
        if (app?.settingsManager?.daemonSyncEnabled?.value != true) return null
        val daemonUrl = app.settingsManager.daemonHostUrl.value
        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("$daemonUrl/api/targets")
                .get()
                .build()

            fastClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java) ?: return null
                val targets = json.getAsJsonArray("targets") ?: return null

                for (item in targets) {
                    val obj = item.asJsonObject ?: continue
                    val u = obj.get("username")?.asString?.lowercase() ?: continue
                    if (u == username.lowercase()) {
                        val id = obj.get("id")?.asString
                        val fullName = obj.get("full_name")?.asString ?: ""
                        val bio = obj.get("biography")?.asString ?: ""
                        val isPrivate = obj.get("is_private")?.asBoolean ?: false
                        val mediaCount = obj.get("media_count")?.asLong ?: 0L
                        val picPath = obj.get("profile_pic_path")?.asString

                        // Trigger check on daemon in background
                        if (!id.isNullOrBlank()) {
                            try {
                                val checkReq = Request.Builder()
                                    .url("$daemonUrl/api/targets/$id/check")
                                    .post("{}".toRequestBody("application/json".toMediaType()))
                                    .build()
                                httpClient.newCall(checkReq).execute().close()
                            } catch (e: Exception) {
                            }
                        }

                        var localPic: String? = null
                        var avatarUrlString: String? = null
                        if (!picPath.isNullOrBlank()) {
                            avatarUrlString = "$daemonUrl/$picPath"
                            try {
                                val dlReq = Request.Builder().url(avatarUrlString).get().build()
                                httpClient.newCall(dlReq).execute().use { imgResp ->
                                    if (imgResp.isSuccessful) {
                                        val bytes = imgResp.body?.bytes()
                                        if (bytes != null && bytes.isNotEmpty()) {
                                            val internal = ArchiveDirectoryManager.saveInternalAvatarBytes(context, username, bytes)
                                            ArchiveDirectoryManager.saveInternalNamedAvatarBytes(context, username, File(picPath).name, bytes)
                                            localPic = internal?.absolutePath
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                val f1 = File("/storage/emulated/0/instalogger/$picPath")
                                val f2 = File(ArchiveDirectoryManager.getArchiveDirectory(context), picPath.removePrefix("archives/"))
                                val resolvedFile = if (f1.exists()) f1 else if (f2.exists()) f2 else null
                                if (resolvedFile != null) {
                                    val internal = ArchiveDirectoryManager.copyToInternalAvatar(context, username, resolvedFile)
                                    localPic = internal?.absolutePath ?: resolvedFile.absolutePath
                                }
                            }
                        }

                        val daemonFollowers = obj.get("followers_count")?.asLong ?: 0L
                        val daemonFollowing = obj.get("following_count")?.asLong ?: 0L
                        val daemonAvatarHash = obj.get("avatar_hash")?.asString
                        val daemonProfileHash = obj.get("profile_hash")?.asString

                        return InstagramProfileSnapshot(
                            username = username,
                            instagramId = id,
                            fullName = fullName,
                            biography = bio,
                            isPrivate = isPrivate,
                            followersCount = daemonFollowers,
                            followingCount = daemonFollowing,
                            mediaCount = mediaCount,
                            profilePicUrl = localPic ?: avatarUrlString,
                            avatarHash = daemonAvatarHash,
                            profileHash = daemonProfileHash
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Local daemon not reachable, fallback to direct engine
        }
        return null
    }

    suspend fun notifyDaemonAddTarget(username: String, turbo: Boolean): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? InstaLoggerApp
        if (app?.settingsManager?.daemonSyncEnabled?.value != true) return@withContext false
        val daemonUrl = app.settingsManager.daemonHostUrl.value
        val cleanUser = username.trim().trimStart('@').lowercase()
        try {
            val json = JsonObject().apply {
                addProperty("username", cleanUser)
                addProperty("turbo", turbo)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$daemonUrl/api/targets")
                .post(body)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchMediaFromLocalDaemon(username: String, instagramId: String?) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? InstaLoggerApp
        if (app?.settingsManager?.daemonSyncEnabled?.value != true) return@withContext
        val daemonUrl = app.settingsManager.daemonHostUrl.value
        val cleanUser = username.trim().trimStart('@').lowercase()
        val keys = mutableListOf<String>()
        if (!instagramId.isNullOrBlank()) keys.add(instagramId)
        keys.add(cleanUser)

        for (k in keys.distinct()) {
            try {
                val req = Request.Builder()
                    .url("$daemonUrl/api/targets/$k/media")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val json = gson.fromJson(body, JsonObject::class.java) ?: return@use
                    val items = json.getAsJsonArray("items") ?: return@use

                    val postEntities = mutableListOf<PostEntity>()
                    val highlightEntities = mutableListOf<HighlightEntity>()
                    val highlightMedia = mutableListOf<HighlightMediaEntity>()

                    val internalPostDir = File(context.filesDir, "posts/$cleanUser").apply { mkdirs() }
                    val internalHlDir = File(context.filesDir, "highlights/$cleanUser").apply { mkdirs() }

                    for (item in items) {
                        val obj = item.asJsonObject ?: continue
                        val id = obj.get("id")?.asString ?: continue
                        val mediaType = obj.get("media_type")?.asString ?: "image"
                        val cdnUrl = obj.get("cdn_url")?.asString ?: ""
                        val relPath = obj.get("relative_file_path")?.asString
                        val caption = obj.get("caption")?.asString?.cleanDisplayText()
                        val hlTitle = obj.get("highlight_title")?.asString ?: "Highlight"

                        var localPath: String? = null
                        if (!relPath.isNullOrBlank()) {
                            val f1 = File("/storage/emulated/0/instalogger/$relPath")
                            val f2 = File(ArchiveDirectoryManager.getArchiveDirectory(context), relPath.removePrefix("archives/"))
                            val resolved = if (f1.exists() && f1.length() > 0) f1 else if (f2.exists() && f2.length() > 0) f2 else null
                            if (resolved != null) {
                                val dest = if (mediaType == "highlight") File(internalHlDir, resolved.name) else File(internalPostDir, resolved.name)
                                try { resolved.copyTo(dest, overwrite = true) } catch (_: Exception) {}
                                localPath = dest.absolutePath
                            }
                        }

                        if (mediaType == "highlight") {
                            val hlId = "hl_${cleanUser}_${hlTitle.hashCode()}"
                            highlightEntities.add(
                                HighlightEntity(
                                    id = hlId,
                                    username = cleanUser,
                                    title = hlTitle.cleanDisplayText(),
                                    coverUrl = cdnUrl,
                                    localCoverPath = localPath,
                                    mediaCount = 1,
                                    archivedAt = System.currentTimeMillis()
                                )
                            )
                            highlightMedia.add(
                                HighlightMediaEntity(
                                    id = id,
                                    highlightId = hlId,
                                    username = cleanUser,
                                    mediaType = if (relPath?.endsWith(".mp4") == true) "video" else "image",
                                    mediaUrl = cdnUrl,
                                    localPath = localPath ?: cdnUrl,
                                    takenAt = System.currentTimeMillis()
                                )
                            )
                        } else {
                            val shortcode = id.removePrefix("post_").removePrefix("reel_")
                            postEntities.add(
                                PostEntity(
                                    id = shortcode,
                                    username = cleanUser,
                                    caption = caption,
                                    displayUrl = cdnUrl,
                                    localPath = localPath,
                                    isVideo = (mediaType == "reel" || relPath?.endsWith(".mp4") == true),
                                    likeCount = 0,
                                    commentCount = 0,
                                    postedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }

                    if (postEntities.isNotEmpty()) {
                        database.postDao().insertPosts(postEntities)
                    }
                    if (highlightEntities.isNotEmpty()) {
                        database.highlightDao().insertHighlights(highlightEntities)
                    }
                    if (highlightMedia.isNotEmpty()) {
                        database.highlightDao().insertHighlightMedia(highlightMedia)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun importLocalPostsAndHighlights(username: String, instagramId: String?) = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val archiveRoot = ArchiveDirectoryManager.getArchiveDirectory(context)
        val candidateRoots = mutableListOf<File>()

        candidateRoots.add(File(archiveRoot, cleanUser))
        candidateRoots.add(File("/storage/emulated/0/instalogger/archives/$cleanUser"))
        if (!instagramId.isNullOrBlank()) {
            candidateRoots.add(File(archiveRoot, instagramId))
            candidateRoots.add(File("/storage/emulated/0/instalogger/archives/$instagramId"))
        }

        val internalPostDir = File(context.filesDir, "posts/$cleanUser").apply { mkdirs() }
        val postEntities = mutableListOf<PostEntity>()

        for (root in candidateRoots.distinct()) {
            if (!root.exists() || !root.isDirectory) continue

            val postDirs = listOf(File(root, "posts"), File(root, "reels"))
            for (pd in postDirs) {
                if (!pd.exists() || !pd.isDirectory) continue
                val files = pd.listFiles { f -> f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".mp4", ignoreCase = true)) } ?: continue
                for (file in files) {
                    val sc = file.nameWithoutExtension
                    val internalFile = File(internalPostDir, file.name)
                    try { file.copyTo(internalFile, overwrite = true) } catch (_: Exception) {}

                    val capFile = File(pd, "${sc}_caption.txt")
                    val caption = if (capFile.exists()) capFile.readText().cleanDisplayText() else null

                    postEntities.add(
                        PostEntity(
                            id = sc,
                            username = cleanUser,
                            caption = caption,
                            displayUrl = internalFile.absolutePath,
                            localPath = internalFile.absolutePath,
                            isVideo = file.name.endsWith(".mp4", ignoreCase = true),
                            likeCount = 0,
                            commentCount = 0,
                            postedAt = file.lastModified()
                        )
                    )
                }
            }
        }

        if (postEntities.isNotEmpty()) {
            database.postDao().insertPosts(postEntities)
        }
    }

    fun unmarkDeleted(username: String) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val prefs = context.getSharedPreferences("instalogger_tombstones", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("deleted_usernames", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(cleanUser)) {
            prefs.edit().putStringSet("deleted_usernames", current).commit()
        }
    }

    fun isTargetDeleted(usernameOrId: String): Boolean {
        val clean = usernameOrId.trim().trimStart('@').lowercase()
        val prefs = context.getSharedPreferences("instalogger_tombstones", Context.MODE_PRIVATE)
        return prefs.getStringSet("deleted_usernames", emptySet())?.contains(clean) == true
    }

    suspend fun deleteTarget(username: String, instagramId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val targetEntity = database.targetDao().getTarget(cleanUser)
        val idToDelete = instagramId ?: targetEntity?.instagramId

        // Record in persistent tombstones immediately to prevent resurrection under race conditions
        val prefs = context.getSharedPreferences("instalogger_tombstones", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("deleted_usernames", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(cleanUser)
        if (!idToDelete.isNullOrBlank()) current.add(idToDelete.lowercase())
        prefs.edit().putStringSet("deleted_usernames", current).commit()

        // 2. Comprehensive Room database clean up FIRST for 0ms UI reactivity
        try {
            database.targetDao().deleteTarget(cleanUser)
            database.changeLogDao().deleteLogsForTarget(cleanUser)
            database.avatarHistoryDao().deleteAvatarsForTarget(cleanUser)
            database.highlightDao().deleteHighlightsForUser(cleanUser)
            database.highlightDao().deleteMediaForUser(cleanUser)
            database.postDao().deletePostsForUser(cleanUser)
            database.followerAuditDao().deleteAuditsForTarget(cleanUser)
            database.followerHistoryDao().deleteHistoryForTarget(cleanUser)
            database.notificationDao().deleteNotificationsForTarget(cleanUser)
        } catch (e: Exception) {
            android.util.Log.e("ProfileInspector", "Error deleting target $cleanUser from Room", e)
        }

        // 3. Clean up internal avatar and archive files
        try {
            ArchiveDirectoryManager.deleteInternalTargetFiles(context, cleanUser)
        } catch (_: Exception) {}

        // 4. Notify local Rust daemon (optional, if enabled)
        val app = context.applicationContext as? InstaLoggerApp
        if (app?.settingsManager?.daemonSyncEnabled?.value == true) {
            val daemonUrl = app.settingsManager.daemonHostUrl.value
            val candidates = mutableListOf<String>()
            if (!idToDelete.isNullOrBlank()) candidates.add(idToDelete)
            candidates.add(cleanUser)

            val fastClient = httpClient.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()

            for (targetKey in candidates.distinct()) {
                try {
                    val req = Request.Builder()
                        .url("$daemonUrl/api/targets/$targetKey")
                        .delete()
                        .build()
                    fastClient.newCall(req).execute().close()
                } catch (_: Exception) {
                }
            }
        }

        true
    }

    suspend fun syncAllFromDaemon(): List<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? InstaLoggerApp
        if (app?.settingsManager?.daemonSyncEnabled?.value != true) return@withContext emptyList()
        val daemonUrl = app.settingsManager.daemonHostUrl.value
        val synced = mutableListOf<String>()
        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("$daemonUrl/api/targets")
                .get()
                .build()

            fastClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = gson.fromJson(body, JsonObject::class.java) ?: return@withContext emptyList()
                val targets = json.getAsJsonArray("targets") ?: return@withContext emptyList()

                for (item in targets) {
                    val obj = item.asJsonObject ?: continue
                    val u = obj.get("username")?.asString ?: continue
                    val cleanUser = u.trim().trimStart('@').lowercase()
                    val targetId = obj.get("id")?.asString

                    // If previously marked as deleted, ensure daemon removes it and do NOT re-import
                    if (isTargetDeleted(cleanUser) || (!targetId.isNullOrBlank() && isTargetDeleted(targetId))) {
                        try {
                            val delReq = Request.Builder()
                                .url("$daemonUrl/api/targets/${targetId ?: cleanUser}")
                                .delete()
                                .build()
                            fastClient.newCall(delReq).execute().close()
                        } catch (_: Exception) {}
                        continue
                    }

                    inspectTarget(u)
                    synced.add(u)
                }
            }
        } catch (e: Exception) {
            // Local daemon not reachable
        }
        synced
    }

    private fun findExistingLocalAvatar(username: String, instagramId: String?): String? {
        val internal = ArchiveDirectoryManager.getInternalAvatar(context, username)
        if (internal != null) return internal.absolutePath

        val candidates = mutableListOf<File>()
        val archiveRoot = ArchiveDirectoryManager.getArchiveDirectory(context)

        if (!instagramId.isNullOrBlank()) {
            candidates.add(File("/storage/emulated/0/instalogger/archives/$instagramId/profile/avatar_current.jpg"))
            candidates.add(File(archiveRoot, "$instagramId/profile/avatar_current.jpg"))
        }
        candidates.add(File(archiveRoot, "$username/profile/avatar_current.jpg"))
        candidates.add(File("/storage/emulated/0/instalogger/archives/$username/profile/avatar_current.jpg"))

        for (f in candidates) {
            if (f.exists() && f.length() > 0) {
                val copied = ArchiveDirectoryManager.copyToInternalAvatar(context, username, f)
                return copied?.absolutePath ?: f.absolutePath
            }
        }
        return null
    }

    private suspend fun importLocalArchives(username: String, instagramId: String?) {
        try {
            database.avatarHistoryDao().cleanCurrentAvatarDuplicates()
        } catch (e: Exception) {
        }

        val dirs = mutableListOf<File>()
        val archiveRoot = ArchiveDirectoryManager.getArchiveDirectory(context)

        val internalDir = File(context.filesDir, "avatars/$username")
        if (internalDir.exists()) dirs.add(internalDir)

        if (!instagramId.isNullOrBlank()) {
            dirs.add(File("/storage/emulated/0/instalogger/archives/$instagramId/profile"))
            dirs.add(File(archiveRoot, "$instagramId/profile"))
        }
        dirs.add(File(archiveRoot, "$username/profile"))
        dirs.add(File("/storage/emulated/0/instalogger/archives/$username/profile"))

        val existingList = database.avatarHistoryDao().getAvatarsListForTarget(username)
        val existingNames = existingList.map { File(it.localPath).name }.toSet()
        val existingHashes = existingList.mapNotNull { it.fileHash }.toMutableSet()

        for (d in dirs) {
            if (d.exists() && d.isDirectory) {
                val files = d.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) } ?: continue
                for (file in files) {
                    if (file.name == "avatar_current.jpg" || file.name.startsWith(".")) continue

                    val hash = computeFileSha256(file)
                    val dHash = computePerceptualDHash(file)
                    val isDupHash = hash != null && existingHashes.contains(hash)
                    val isDupVisual = dHash != null && existingList.any { isVisuallyDuplicateAvatar(dHash, File(it.localPath)) }
                    if (isDupHash || isDupVisual) {
                        // Redundant duplicate avatar file Delete duplicate from disk if not already registered
                        if (!existingNames.contains(file.name)) {
                            try { file.delete() } catch (_: Exception) {}
                        }
                        continue
                    }

                    if (existingNames.contains(file.name)) continue

                    val internal = ArchiveDirectoryManager.copyToInternalNamedAvatar(context, username, file)
                    val pathToStore = internal?.absolutePath ?: file.absolutePath

                    database.avatarHistoryDao().insertAvatar(
                        AvatarHistoryEntity(
                            username = username,
                            localPath = pathToStore,
                            archivedAt = file.lastModified(),
                            fileSize = file.length(),
                            fileHash = hash
                        )
                    )
                    if (hash != null) existingHashes.add(hash)
                }
            }
        }
    }

    private fun downloadAvatarToTemp(username: String, url: String): File? {
        val tempFile = File(context.cacheDir, "temp_avatar_${username}_${System.currentTimeMillis()}.jpg")
        return try {
            val size = instagramEngine.downloadImage(url, tempFile)
            if (size > 0 && tempFile.exists() && tempFile.length() > 0L) {
                tempFile
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("ProfileInspector", "Failed to download avatar to temp: ${e.message}")
            try { tempFile.delete() } catch (_: Exception) {}
            null
        }
    }

    suspend fun deduplicateAvatars(username: String) {
        withContext(Dispatchers.IO) {
            try {
                val cleanUser = username.trim().trimStart('@').lowercase()
                val avatars = database.avatarHistoryDao().getAvatarsListForTarget(cleanUser)
                if (avatars.size <= 1) return@withContext

                // Sort by archivedAt ASCENDING so we keep the EARLIEST (original) archive
                val sorted = avatars.sortedBy { it.archivedAt }
                val seenHashes = mutableSetOf<String>()
                val seenDHashes = mutableListOf<Long>()
                val toDelete = mutableListOf<AvatarHistoryEntity>()

                for (item in sorted) {
                    val file = File(item.localPath)
                    val plainHash = computeFileSha256(file) ?: item.fileHash
                    val dHash = computePerceptualDHash(file)

                    val isExactDup = plainHash != null && seenHashes.contains(plainHash)
                    val isVisualDup = dHash != null && seenDHashes.any { hammingDistance(it, dHash) <= AVATAR_DHASH_THRESHOLD }

                    if (isExactDup || isVisualDup) {
                        toDelete.add(item)
                    } else {
                        if (plainHash != null) {
                            seenHashes.add(plainHash)
                            if (item.fileHash != plainHash) {
                                database.avatarHistoryDao().insertAvatar(item.copy(fileHash = plainHash))
                            }
                        }
                        if (dHash != null) {
                            seenDHashes.add(dHash)
                        }
                    }
                }

                for (dup in toDelete) {
                    database.avatarHistoryDao().deleteAvatarById(dup.id)
                    try {
                        val f = File(dup.localPath)
                        if (f.exists() && !f.name.startsWith("avatar_current")) {
                            f.delete()
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("ProfileInspector", "Failed to deduplicate avatars: ${e.message}")
            }
        }
    }

    private fun extractAvatarAssetKey(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            java.net.URI(url).path?.trim().orEmpty()
        } catch (_: Exception) {
            url.substringBefore("?")
        }
    }

    private fun readPlaintextBytes(file: File): ByteArray? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val vault = VaultStorageManager.getInstance(context)
            if (vault.isVaultFile(file)) {
                vault.readEncryptedFile(file)
            } else {
                file.readBytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeFileSha256(file: File): String? {
        val bytes = readPlaintextBytes(file) ?: return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    // Computes a 64-bit perceptual difference hash (dHash) over a 9x8 grayscale thumbnail.
    private fun computePerceptualDHash(file: File): Long? {
        val bytes = readPlaintextBytes(file) ?: return null
        return try {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = Bitmap.createScaledBitmap(decoded, 9, 8, true)
            if (scaled !== decoded) decoded.recycle()
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luminance(scaled.getPixel(x, y))
                    val right = luminance(scaled.getPixel(x + 1, y))
                    if (left > right) {
                        hash = hash or (1L shl bit)
                    }
                    bit++
                }
            }
            scaled.recycle()
            hash
        } catch (_: Exception) {
            null
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    private fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }

    private fun isVisuallyDuplicateAvatar(candidateDHash: Long, existingFile: File): Boolean {
        val existingDHash = computePerceptualDHash(existingFile) ?: return false
        return hammingDistance(candidateDHash, existingDHash) <= AVATAR_DHASH_THRESHOLD
    }

    private fun computeProfileStateHash(
        username: String,
        fullName: String,
        biography: String,
        isPrivate: Boolean,
        followersCount: Long,
        followingCount: Long,
        mediaCount: Long,
        avatarHash: String?
    ): String {
        val raw = "${username.lowercase()}|${fullName.trim()}|${biography.trim()}|$isPrivate|$followersCount|$followingCount|$mediaCount|${avatarHash ?: ""}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun flushTargetPool(username: String, writeSummaryLog: Boolean = true): Long = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val target = database.targetDao().getTarget(cleanUser) ?: return@withContext 0L
        val now = System.currentTimeMillis()
        val (netDelta, resetState) = FollowerPoolEngine.manualFlush(
            state = target.followerPoolState(),
            currentCount = target.followersCount,
            nowMs = now
        )

        if (writeSummaryLog && netDelta != 0L) {
            val baseline = if (target.poolFollowerBaseline > 0L) target.poolFollowerBaseline else target.followersCount
            val sign = if (netDelta > 0L) "+$netDelta" else "$netDelta"
            val title = LanguageManager.getString(context, R.string.notif_pool_flush_title, cleanUser, sign)
            val desc = LanguageManager.getString(
                context,
                R.string.notif_pool_flush_desc,
                baseline,
                target.followersCount,
                sign,
                target.poolFollowerGrossGain,
                target.poolFollowerGrossLoss
            )
            database.changeLogDao().insertLog(
                ChangeLogEntity(
                    targetUsername = cleanUser,
                    eventType = "follower_pool_flush",
                    title = title,
                    oldValue = baseline.toString(),
                    newValue = target.followersCount.toString(),
                    timestamp = now
                )
            )
            dispatchAlert(cleanUser, "follower_pool_flush", title, desc, now)
        }

        database.targetDao().resetPoolAccumulators(
            username = cleanUser,
            followerBaseline = target.followersCount,
            followingBaseline = target.followingCount,
            lastAlertAt = resetState.lastAlertAt,
            nowMs = now
        )
        netDelta
    }

    private suspend fun inspectFollowersList(
        username: String,
        targetId: String,
        cookie: String?,
        now: Long,
        reportedTotalCount: Long = 0L,
        completenessGuardEnabled: Boolean = true
    ) {
        try {
            val fetchLimit = if (reportedTotalCount in 1..500) reportedTotalCount.toInt().coerceAtLeast(100) else 500
            val freshUsers = instagramEngine.fetchFollowersList(targetId, cookie, limit = fetchLimit)
            if (freshUsers.isEmpty()) return
            val decision = ListCompletenessGuard.evaluate(
                fetchedCount = freshUsers.size,
                reportedTotalCount = reportedTotalCount,
                guardEnabled = completenessGuardEnabled
            )

            val oldJson = ArchiveDirectoryManager.getUserArchiveJson(context, username, "followers.json")
            if (decision.allowArchiveOverwrite && !oldJson.isNullOrBlank() && (decision.allowGainAlerts || decision.allowLossAlerts)) {
                val oldType = object : com.google.gson.reflect.TypeToken<List<InstagramUserSummary>>() {}.type
                val oldUsers: List<InstagramUserSummary> = try {
                    gson.fromJson(oldJson, oldType) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (oldUsers.isNotEmpty()) {
                    val oldSet = oldUsers.map { it.username.lowercase() }.toSet()
                    val newSet = freshUsers.map { it.username.lowercase() }.toSet()

                    val gained = if (decision.allowGainAlerts) freshUsers.filter { it.username.lowercase() !in oldSet } else emptyList()
                    val lost = if (decision.allowLossAlerts) oldUsers.filter { it.username.lowercase() !in newSet } else emptyList()

                    for (u in gained) {
                        val title = "@$username gained new follower: @${u.username}"
                        val desc = "New follower: ${u.fullName.ifBlank { "@" + u.username }}"
                        database.followerAuditDao().insertAudit(
                            FollowerAuditEntity(
                                targetUsername = username,
                                eventType = "follower_gained",
                                username = u.username,
                                fullName = u.fullName,
                                profilePicUrl = u.profilePicUrl,
                                timestamp = now
                            )
                        )
                        database.changeLogDao().insertLog(
                            ChangeLogEntity(
                                targetUsername = username,
                                eventType = "follower_gained",
                                title = title,
                                newValue = "@${u.username}",
                                timestamp = now
                            )
                        )
                        dispatchAlert(username, "follower_gained", title, desc, now)
                    }

                    for (u in lost) {
                        val title = "@$username lost follower: @${u.username}"
                        val desc = "Unfollowed: ${u.fullName.ifBlank { "@" + u.username }}"
                        database.followerAuditDao().insertAudit(
                            FollowerAuditEntity(
                                targetUsername = username,
                                eventType = "follower_lost",
                                username = u.username,
                                fullName = u.fullName,
                                profilePicUrl = u.profilePicUrl,
                                timestamp = now
                            )
                        )
                        database.changeLogDao().insertLog(
                            ChangeLogEntity(
                                targetUsername = username,
                                eventType = "follower_lost",
                                title = title,
                                oldValue = "@${u.username}",
                                timestamp = now
                            )
                        )
                        dispatchAlert(username, "follower_lost", title, desc, now)
                    }
                }
            }

            val newJson = gson.toJson(freshUsers)
            ArchiveDirectoryManager.saveUserArchiveJson(context, username, "followers.json", newJson)
        } catch (e: Exception) {
            Log.w("ProfileInspector", "Failed to inspect followers list for @$username: ${e.message}")
        }
    }

    private suspend fun inspectFollowingList(
        username: String,
        targetId: String,
        cookie: String?,
        now: Long,
        reportedTotalCount: Long = 0L,
        completenessGuardEnabled: Boolean = true
    ) {
        try {
            val fetchLimit = if (reportedTotalCount in 1..500) reportedTotalCount.toInt().coerceAtLeast(100) else 500
            val freshUsers = instagramEngine.fetchFollowingList(targetId, cookie, limit = fetchLimit)
            if (freshUsers.isEmpty()) return
            val decision = ListCompletenessGuard.evaluate(
                fetchedCount = freshUsers.size,
                reportedTotalCount = reportedTotalCount,
                guardEnabled = completenessGuardEnabled
            )

            val oldJson = ArchiveDirectoryManager.getUserArchiveJson(context, username, "following.json")
            if (decision.allowArchiveOverwrite && !oldJson.isNullOrBlank() && (decision.allowGainAlerts || decision.allowLossAlerts)) {
                val oldType = object : com.google.gson.reflect.TypeToken<List<InstagramUserSummary>>() {}.type
                val oldUsers: List<InstagramUserSummary> = try {
                    gson.fromJson(oldJson, oldType) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (oldUsers.isNotEmpty()) {
                    val oldSet = oldUsers.map { it.username.lowercase() }.toSet()
                    val newSet = freshUsers.map { it.username.lowercase() }.toSet()

                    val gained = if (decision.allowGainAlerts) freshUsers.filter { it.username.lowercase() !in oldSet } else emptyList()
                    val lost = if (decision.allowLossAlerts) oldUsers.filter { it.username.lowercase() !in newSet } else emptyList()

                    for (u in gained) {
                        val title = "@$username followed: @${u.username}"
                        val desc = "Now following: ${u.fullName.ifBlank { "@" + u.username }}"
                        database.followerAuditDao().insertAudit(
                            FollowerAuditEntity(
                                targetUsername = username,
                                eventType = "following_gained",
                                username = u.username,
                                fullName = u.fullName,
                                profilePicUrl = u.profilePicUrl,
                                timestamp = now
                            )
                        )
                        database.changeLogDao().insertLog(
                            ChangeLogEntity(
                                targetUsername = username,
                                eventType = "following_gained",
                                title = title,
                                newValue = "@${u.username}",
                                timestamp = now
                            )
                        )
                        dispatchAlert(username, "following_gained", title, desc, now)
                    }

                    for (u in lost) {
                        val title = "@$username unfollowed: @${u.username}"
                        val desc = "Stopped following: ${u.fullName.ifBlank { "@" + u.username }}"
                        database.followerAuditDao().insertAudit(
                            FollowerAuditEntity(
                                targetUsername = username,
                                eventType = "following_lost",
                                username = u.username,
                                fullName = u.fullName,
                                profilePicUrl = u.profilePicUrl,
                                timestamp = now
                            )
                        )
                        database.changeLogDao().insertLog(
                            ChangeLogEntity(
                                targetUsername = username,
                                eventType = "following_lost",
                                title = title,
                                oldValue = "@${u.username}",
                                timestamp = now
                            )
                        )
                        dispatchAlert(username, "following_lost", title, desc, now)
                    }
                }
            }

            val newJson = gson.toJson(freshUsers)
            ArchiveDirectoryManager.saveUserArchiveJson(context, username, "following.json", newJson)
        } catch (e: Exception) {
            Log.w("ProfileInspector", "Failed to inspect following list for @$username: ${e.message}")
        }
    }

    private suspend fun persistAndEnqueuePostPage(
        cleanUser: String,
        rawPosts: List<InstagramPostItem>
    ): Int {
        if (rawPosts.isEmpty()) return 0
        val postDao = database.postDao()
        val existingPostsMap = postDao.getPostsListForUser(cleanUser).associateBy { it.id }
        val postEntities = mutableListOf<PostEntity>()
        val orchestratorQueue = mutableListOf<Pair<PostEntity, List<CarouselSlideHint>>>()

        for (rawPost in rawPosts) {
            val existingPost = existingPostsMap[rawPost.shortcode]
            val existingLocalPath = existingPost?.localPath?.takeIf { File(it).exists() && File(it).length() > 0L }
            val existingThumbPath = existingPost?.localThumbPath?.takeIf { File(it).exists() && File(it).length() > 0L }

            val isCarousel = rawPost.mediaType == "carousel" || rawPost.slides.size > 1
            val isVideo = rawPost.mediaType == "video" ||
                rawPost.slides.any { it.mediaType == "video" } ||
                (existingPost?.isVideo == true)
            val resolvedMediaType = when {
                isCarousel -> "carousel"
                isVideo -> "video"
                else -> "image"
            }
            val resolvedSlideCount = maxOf(
                rawPost.slides.size,
                existingPost?.slideCount ?: 1,
                1
            )

            val resolvedCaption = rawPost.caption.cleanDisplayText().ifBlank { existingPost?.caption.orEmpty() }.ifBlank { null }
            val resolvedLikes = if (rawPost.likeCount > 0) rawPost.likeCount else (existingPost?.likeCount ?: 0)
            val resolvedComments = if (rawPost.commentCount > 0) rawPost.commentCount else (existingPost?.commentCount ?: 0)
            val resolvedPostedAt = if (existingPost != null && rawPost.likeCount == 0 && existingPost.postedAt > 0L) {
                existingPost.postedAt
            } else {
                rawPost.timestamp
            }

            val entity = PostEntity(
                id = rawPost.shortcode,
                username = cleanUser,
                caption = resolvedCaption,
                displayUrl = rawPost.displayUrl.ifBlank { existingPost?.displayUrl.orEmpty() },
                localPath = existingLocalPath,
                isVideo = isVideo,
                likeCount = resolvedLikes,
                commentCount = resolvedComments,
                postedAt = resolvedPostedAt,
                mediaType = resolvedMediaType,
                slideCount = resolvedSlideCount,
                localThumbPath = existingThumbPath,
                durationSeconds = existingPost?.durationSeconds,
                downloadEngine = existingPost?.downloadEngine,
                downloadStatus = if (existingLocalPath != null) "complete" else (existingPost?.downloadStatus ?: "pending")
            )
            postEntities.add(entity)
            orchestratorQueue.add(entity to rawPost.slides)
        }

        if (postEntities.isNotEmpty()) {
            postDao.insertPosts(postEntities)
            val totalInDb = postDao.getPostsCountForUser(cleanUser).toLong()
            val cur = database.targetDao().getTarget(cleanUser)
            if (cur != null && (cur.mediaCount == 0L || cur.mediaCount < totalInDb)) {
                database.targetDao().insertTarget(cur.copy(mediaCount = totalInDb))
            }
            mediaDownloadOrchestrator?.enqueueBatch(cleanUser, orchestratorQueue)
        }
        return postEntities.size
    }

    suspend fun archiveHighlightsAndPosts(
        username: String,
        targetId: String? = null,
        cookie: String? = null,
        wasPrivateToPublic: Boolean = false,
        isPrivate: Boolean = false,
        forceFullPagination: Boolean = false,
        storyGrew: Boolean = false,
        followedByViewer: Boolean = false
    ) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val job = appBackgroundScope.async {
            archiveHighlightsAndPostsInternal(
                cleanUser = cleanUser,
                targetId = targetId,
                cookie = cookie,
                wasPrivateToPublic = wasPrivateToPublic,
                isPrivate = isPrivate,
                forceFullPagination = forceFullPagination,
                storyGrew = storyGrew,
                followedByViewer = followedByViewer
            )
        }
        try {
            job.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Caller left composition; background archive continues uninterrupted
        }
    }

    private suspend fun archiveHighlightsAndPostsInternal(
        cleanUser: String,
        targetId: String? = null,
        cookie: String? = null,
        wasPrivateToPublic: Boolean = false,
        isPrivate: Boolean = false,
        forceFullPagination: Boolean = false,
        storyGrew: Boolean = false,
        followedByViewer: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val archiveMutex = archiveMutexMap.computeIfAbsent(cleanUser) { Mutex() }
        if (!archiveMutex.tryLock()) {
            AppLogger.d("ProfileInspector", "archiveHighlightsAndPosts(@$cleanUser) already running; skipping duplicate")
            return@withContext
        }

        try {
            val targetRecord = database.targetDao().getTarget(cleanUser)
            val resolvedPrivate = isPrivate || (targetRecord?.isPrivate == true && !wasPrivateToPublic)
            val effectiveTargetId = targetId ?: targetRecord?.instagramId
            val effectiveCookie = cookie ?: sessionManager.getCookieString()
            val settings = getSettingsManager()
            val speedPreset = settings.archiverSpeedPreset.value
            val maxPostsLimit = settings.maxPostsSyncLimit.value

            // 1. Immediately import any media already cached on disk or daemon
            importLocalPostsAndHighlights(cleanUser, effectiveTargetId)
            fetchMediaFromLocalDaemon(cleanUser, effectiveTargetId)

            // Private accounts without a session or not followed by viewer cannot be queried
            val unfollowedPrivateBlocked = resolvedPrivate && !followedByViewer &&
                (unfollowedPrivateUntilMs[cleanUser] ?: 0L) > System.currentTimeMillis()
            if ((resolvedPrivate && effectiveCookie.isNullOrBlank()) || (unfollowedPrivateBlocked && !forceFullPagination && !wasPrivateToPublic)) {
                _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.REQUIRES_SESSION)
                return@withContext
            }

            // Run Highlights archiving AND Multi-Page Post pagination IN PARALLEL
            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.SYNCING)
            val expectedTotal = targetRecord?.mediaCount ?: 0L
            val existingPostsCount = database.postDao().getPostsCountForUser(cleanUser)
            val knownZeroPosts = targetRecord != null && targetRecord.lastCheckedAt != null && expectedTotal == 0L
            val needsFullPagination = forceFullPagination ||
                wasPrivateToPublic ||
                (!knownZeroPosts && existingPostsCount == 0) ||
                (expectedTotal > 0L && existingPostsCount.toLong() < expectedTotal && (maxPostsLimit == 0 || existingPostsCount < maxPostsLimit))

            val (hlCount, fetchedCount) = coroutineScope {
                val activeStoriesJob = async {
                    try {
                        if (targetRecord != null && !unfollowedPrivateBlocked) {
                            val nowStoryMs = System.currentTimeMillis()
                            val lastStoryAt = lastActiveStoriesCheckMs[cleanUser] ?: 0L
                            val shouldCheckStories = storyGrew ||
                                forceFullPagination ||
                                wasPrivateToPublic ||
                                lastStoryAt == 0L ||
                                (nowStoryMs - lastStoryAt >= ACTIVE_STORY_REFRESH_INTERVAL_MS)
                            if (shouldCheckStories) {
                                lastActiveStoriesCheckMs[cleanUser] = nowStoryMs
                                syncAndArchiveActiveStoriesInternal(
                                    target = targetRecord.copy(instagramId = effectiveTargetId ?: targetRecord.instagramId),
                                    cookie = effectiveCookie,
                                    notifyChanges = !wasPrivateToPublic
                                )
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w("ProfileInspector", "Active stories parallel job failed for @$cleanUser: ${e.message}")
                    }
                }

                val highlightsJob = async {
                    var count = 0
                    try {
                        val highlightDao = database.highlightDao()
                        val existingHighlights = highlightDao.getHighlightsListForUser(cleanUser)
                        val existingById = existingHighlights.associateBy { it.id }
                        val hadHighlightBaseline = existingHighlights.isNotEmpty()
                        val nowMs = System.currentTimeMillis()
                        val lastTrayAt = lastHighlightTrayCheckMs[cleanUser] ?: 0L
                        val neverCheckedTray = lastTrayAt == 0L
                        val shouldQueryTray = wasPrivateToPublic ||
                            forceFullPagination ||
                            neverCheckedTray ||
                            (nowMs - lastTrayAt >= HIGHLIGHT_TRAY_REFRESH_INTERVAL_MS)

                        val highlightEntities = if (shouldQueryTray) {
                            lastHighlightTrayCheckMs[cleanUser] = nowMs
                            val trayResult = instagramEngine.fetchUserHighlightsDetailed(
                                userId = effectiveTargetId,
                                cookieString = effectiveCookie,
                                username = cleanUser,
                                isPrivate = resolvedPrivate
                            )
                            val highlights = trayResult.highlights
                            if (trayResult.authoritative && hadHighlightBaseline) {
                                val liveHlIds = highlights.map { it.id }.toHashSet()
                                for (oldHl in existingHighlights) {
                                    if (!oldHl.isVanished && oldHl.id !in liveHlIds) {
                                        highlightDao.markHighlightVanished(oldHl.id, nowMs)
                                        val title = LanguageManager.getString(
                                            context,
                                            R.string.notif_highlight_vanished_title,
                                            cleanUser
                                        )
                                        val desc = LanguageManager.getString(
                                            context,
                                            R.string.notif_highlight_vanished_desc,
                                            oldHl.title
                                        )
                                        database.changeLogDao().insertLog(
                                            ChangeLogEntity(
                                                targetUsername = cleanUser,
                                                eventType = "highlight_vanished",
                                                title = title,
                                                oldValue = oldHl.title,
                                                newValue = desc,
                                                timestamp = nowMs
                                            )
                                        )
                                        dispatchAlert(
                                            targetUsername = cleanUser,
                                            eventType = "highlight_vanished",
                                            title = title,
                                            message = desc,
                                            timestamp = nowMs
                                        )
                                    }
                                }
                            }
                            if (highlights.isNotEmpty()) {
                                count = highlights.size
                                val archiveDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$cleanUser/highlights")
                                archiveDir.mkdirs()
                                ArchiveDirectoryManager.ensureNoMedia(archiveDir)
                                val vaultManager = VaultStorageManager.getInstance(context)

                                // Phase A: Archive covers in parallel into hardware Vault
                                val coverSem = Semaphore(speedPreset.downloadWorkers.coerceIn(2, 10))
                                val mapped = highlights.map { hl ->
                                    async {
                                        coverSem.withPermit {
                                            var localCover: String? = null
                                            val safeId = hl.id.replace(":", "_")
                                            val existingVaultCover = vaultManager.getVaultMediaFile(cleanUser, "highlights", "${safeId}_cover.jpg")
                                            if (existingVaultCover != null && vaultManager.readEncryptedFile(existingVaultCover) != null) {
                                                localCover = existingVaultCover.absolutePath
                                            } else if (!hl.coverUrl.isNullOrBlank()) {
                                                val tempCover = File(context.cacheDir, "hl_cover_${safeId}_${System.currentTimeMillis()}.jpg")
                                                try {
                                                    instagramEngine.downloadImage(hl.coverUrl, tempCover)
                                                    val valid = com.instalogger.app.engine.media.MediaFileValidator.validateAndHash(tempCover)
                                                    if (valid != null && !valid.isVideo) {
                                                        val encCover = vaultManager.saveEncryptedFile(cleanUser, "highlights", "${safeId}_cover.jpg", tempCover.readBytes())
                                                        localCover = encCover.absolutePath
                                                    }
                                                } catch (e: Exception) {
                                                    AppLogger.w("ProfileInspector", "Highlight cover download error ($safeId): ${e.message}")
                                                } finally {
                                                    try { tempCover.delete() } catch (_: Exception) {}
                                                }
                                            }

                                            HighlightEntity(
                                                id = hl.id,
                                                username = cleanUser,
                                                title = hl.title.cleanDisplayText(),
                                                coverUrl = hl.coverUrl ?: "",
                                                localCoverPath = localCover,
                                                mediaCount = hl.mediaCount,
                                                archivedAt = existingById[hl.id]?.archivedAt ?: nowMs,
                                                isVanished = false,
                                                vanishedAt = null
                                            )
                                        }
                                    }
                                }.awaitAll()

                                if (mapped.isNotEmpty()) {
                                    highlightDao.insertHighlights(mapped)
                                    // Notify for newly added highlights when baseline already existed
                                    if (hadHighlightBaseline && !wasPrivateToPublic) {
                                        for (hlEnt in mapped) {
                                            if (hlEnt.id !in existingById) {
                                                val title = LanguageManager.getString(
                                                    context,
                                                    R.string.notif_highlight_added_title,
                                                    cleanUser
                                                )
                                                val desc = LanguageManager.getString(
                                                    context,
                                                    R.string.notif_highlight_added_desc,
                                                    hlEnt.title,
                                                    hlEnt.mediaCount
                                                )
                                                database.changeLogDao().insertLog(
                                                    ChangeLogEntity(
                                                        targetUsername = cleanUser,
                                                        eventType = "highlight_added",
                                                        title = title,
                                                        oldValue = null,
                                                        newValue = hlEnt.title,
                                                        timestamp = nowMs
                                                    )
                                                )
                                                dispatchAlert(
                                                    targetUsername = cleanUser,
                                                    eventType = "highlight_added",
                                                    title = title,
                                                    message = desc,
                                                    timestamp = nowMs
                                                )
                                            }
                                        }
                                    }
                                }
                                mapped
                            } else {
                                existingHighlights.filter { !it.isVanished }
                            }
                        } else {
                            count = existingHighlights.size
                            existingHighlights.filter { !it.isVanished }
                        }

                        if (highlightEntities.isNotEmpty()) {
                            // Phase B: Download & diff stories inside each highlight tray
                            val highlightsNeedingArchive = highlightEntities.filter { hlEntity ->
                                val cachedMedia = highlightDao.getMediaListForHighlight(hlEntity.id)
                                if (cachedMedia.isEmpty()) return@filter true
                                val countChanged = hlEntity.mediaCount > 0 && hlEntity.mediaCount != cachedMedia.count { !it.isVanished }
                                val anyMissingVault = cachedMedia.any { item ->
                                    !item.isVanished && (
                                        item.localPath.startsWith("http") ||
                                            !File(item.localPath).exists() ||
                                            File(item.localPath).length() == 0L
                                        )
                                }
                                shouldQueryTray || countChanged || anyMissingVault
                            }

                            if (highlightsNeedingArchive.isNotEmpty()) {
                                val storySem = Semaphore((speedPreset.downloadWorkers / 2).coerceIn(2, 5))
                                highlightsNeedingArchive.map { hlEntity ->
                                    async {
                                        storySem.withPermit {
                                            try {
                                                archiveHighlightStoriesForEntity(
                                                    highlight = hlEntity,
                                                    cookie = effectiveCookie,
                                                    notifyChanges = hadHighlightBaseline && !wasPrivateToPublic
                                                )
                                            } catch (e: Exception) {
                                                AppLogger.w("ProfileInspector", "Highlight stories error (${hlEntity.id}): ${e.message}")
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w("ProfileInspector", "Highlights parallel job failed for @$cleanUser: ${e.message}")
                    }
                    count
                }

                val postsJob = async {
                    if (knownZeroPosts && !forceFullPagination && !wasPrivateToPublic) {
                        _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.COMPLETED)
                        return@async 0
                    }
                    var count = 0
                    val postResult = if (needsFullPagination) {
                        val effectiveTargetTotal = if (maxPostsLimit > 0 && expectedTotal > 0L) {
                            minOf(expectedTotal.toInt(), maxPostsLimit)
                        } else if (expectedTotal > 0L) {
                            expectedTotal.toInt()
                        } else {
                            maxPostsLimit
                        }
                        _postBackfillProgress.value = _postBackfillProgress.value + (cleanUser to (existingPostsCount to effectiveTargetTotal))
                        instagramEngine.fetchAllPostsPaginated(
                            userId = effectiveTargetId,
                            username = cleanUser,
                            cookieString = effectiveCookie,
                            maxPosts = maxPostsLimit,
                            expectedTotal = expectedTotal,
                            isPrivate = resolvedPrivate,
                            pageDelayMs = speedPreset.pageDelayMs
                        ) { pageItems, discoveredTotal ->
                            persistAndEnqueuePostPage(cleanUser, pageItems)
                            val liveInDb = database.postDao().getPostsCountForUser(cleanUser)
                            val displayTotal = maxOf(effectiveTargetTotal, liveInDb, discoveredTotal)
                            _postBackfillProgress.value = _postBackfillProgress.value + (cleanUser to (maxOf(liveInDb, discoveredTotal) to displayTotal))
                        }
                    } else {
                        instagramEngine.fetchRecentPostsDetailed(
                            userId = effectiveTargetId,
                            username = cleanUser,
                            cookieString = effectiveCookie,
                            limit = 24,
                            isPrivate = resolvedPrivate
                        )
                    }

                    when (postResult) {
                        is PostFetchResult.RateLimited -> {
                            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.RATE_LIMITED)
                        }
                        is PostFetchResult.RequiresSession -> {
                            if (resolvedPrivate) {
                                unfollowedPrivateUntilMs[cleanUser] = System.currentTimeMillis() + UNFOLLOWED_PRIVATE_COOLDOWN_MS
                            }
                            val status = if (resolvedPrivate) PostSyncStatus.REQUIRES_SESSION else PostSyncStatus.FAILED
                            _postSyncState.value = _postSyncState.value + (cleanUser to status)
                        }
                        is PostFetchResult.Error -> {
                            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.FAILED)
                        }
                        is PostFetchResult.Success -> {
                            val rawPosts = postResult.posts
                            count = rawPosts.size
                            if (!needsFullPagination && rawPosts.isNotEmpty()) {
                                persistAndEnqueuePostPage(cleanUser, rawPosts)
                            }

                            // Identify exact vanished/deleted posts when we obtained a complete post snapshot
                            val hasCompleteCoverage = rawPosts.isNotEmpty() && (
                                maxPostsLimit == 0 ||
                                    expectedTotal == 0L ||
                                    rawPosts.size.toLong() >= expectedTotal
                            )
                            if (hasCompleteCoverage) {
                                val nowMs = System.currentTimeMillis()
                                val liveCodes = rawPosts.map { it.shortcode }.toHashSet()
                                val activeArchived = database.postDao().getActivePostsListForUser(cleanUser)
                                for (oldPost in activeArchived) {
                                    if (oldPost.id !in liveCodes) {
                                        database.postDao().markPostVanished(oldPost.id, nowMs)
                                        val snippet = oldPost.caption?.replace("\n", " ")?.trim()?.take(80).orEmpty()
                                        val captionPart = if (snippet.isNotBlank()) "— $snippet" else ""
                                        val title = LanguageManager.getString(
                                            context,
                                            R.string.notif_post_vanished_title,
                                            cleanUser
                                        )
                                        val desc = LanguageManager.getString(
                                            context,
                                            R.string.notif_post_vanished_desc,
                                            oldPost.id,
                                            captionPart
                                        ).trim()
                                        database.changeLogDao().insertLog(
                                            ChangeLogEntity(
                                                targetUsername = cleanUser,
                                                eventType = "post_vanished",
                                                title = "$title (/p/${oldPost.id})",
                                                oldValue = oldPost.id,
                                                newValue = desc,
                                                timestamp = nowMs
                                            )
                                        )
                                        dispatchAlert(
                                            targetUsername = cleanUser,
                                            eventType = "post_vanished",
                                            title = title,
                                            message = desc,
                                            timestamp = nowMs
                                        )
                                    }
                                }
                            }

                            // Also resume any previously pending/failed posts in DB
                            mediaDownloadOrchestrator?.enqueuePendingForUser(cleanUser)

                            val status = if (rawPosts.isEmpty() && resolvedPrivate && effectiveCookie.isNullOrBlank()) {
                                PostSyncStatus.REQUIRES_SESSION
                            } else {
                                PostSyncStatus.COMPLETED
                            }
                            _postSyncState.value = _postSyncState.value + (cleanUser to status)
                        }
                    }
                    count
                }

                activeStoriesJob.await()
                highlightsJob.await() to postsJob.await()
            }

            // If account was private and became public, dispatch a special success alert
            if (wasPrivateToPublic) {
                val now = System.currentTimeMillis()
                val title = LanguageManager.getString(context, R.string.notif_public_archived_title, cleanUser)
                val message = LanguageManager.getString(context, R.string.notif_public_archived_desc, hlCount, fetchedCount)
                dispatchAlert(
                    targetUsername = cleanUser,
                    eventType = "privacy_change",
                    title = title,
                    message = message,
                    timestamp = now
                )
            }
        } catch (e: Exception) {
            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.FAILED)
            AppLogger.e("ProfileInspector", "archiveHighlightsAndPosts(@$cleanUser) failed: ${e.message}")
        } finally {
            _postBackfillProgress.value = _postBackfillProgress.value - cleanUser
            archiveMutex.unlock()
        }
    }

    suspend fun backfillUnarchivedContent(target: TargetEntity): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = target.username.trim().trimStart('@').lowercase()
        if (isTargetDeleted(cleanUser)) return@withContext false
        val hasSession = sessionManager.hasSession()
        if (target.isPrivate && !hasSession) return@withContext false
        if (target.isPrivate && (unfollowedPrivateUntilMs[cleanUser] ?: 0L) > System.currentTimeMillis()) {
            return@withContext false
        }

        val posts = database.postDao().getPostsListForUser(cleanUser)
        val pendingDownloads = posts.count { post ->
            val fileOk = !post.localPath.isNullOrBlank() &&
                File(post.localPath).exists() &&
                File(post.localPath).length() > 0L
            !fileOk || post.downloadStatus != "complete"
        }

        val maxLimit = getSettingsManager().maxPostsSyncLimit.value
        val effectiveExpected = when {
            target.mediaCount <= 0L -> 0L
            maxLimit > 0 -> minOf(target.mediaCount, maxLimit.toLong())
            else -> target.mediaCount
        }
        val missingPosts = (effectiveExpected > 0L && posts.size.toLong() < effectiveExpected) ||
            (posts.isEmpty() && target.mediaCount > 0L)

        val highlights = database.highlightDao().getHighlightsListForUser(cleanUser)
        val lastTrayCheckAt = lastHighlightTrayCheckMs[cleanUser] ?: 0L
        val trayCheckDue = lastTrayCheckAt == 0L ||
            (System.currentTimeMillis() - lastTrayCheckAt >= HIGHLIGHT_TRAY_REFRESH_INTERVAL_MS)
        val missingHighlights = trayCheckDue
        val hasEmptyHighlightStories = highlights.any { hl ->
            !hl.isVanished && database.highlightDao().getMediaListForHighlight(hl.id).isEmpty()
        }

        if (pendingDownloads > 0 && mediaDownloadOrchestrator?.isAutoQueueSuppressed() != true) {
            mediaDownloadOrchestrator?.enqueuePendingForUser(cleanUser)
        }

        if (missingPosts || missingHighlights || hasEmptyHighlightStories) {
            AppLogger.i(
                "ProfileInspector",
                "Auto-backfill @$cleanUser: posts=${posts.size}/${target.mediaCount} pendingDl=$pendingDownloads highlights=${highlights.size} emptyHl=$hasEmptyHighlightStories"
            )
            archiveHighlightsAndPosts(
                username = cleanUser,
                targetId = target.instagramId,
                cookie = sessionManager.getCookieString(),
                wasPrivateToPublic = false,
                isPrivate = target.isPrivate,
                forceFullPagination = missingPosts
            )
            return@withContext true
        }
        pendingDownloads > 0
    }

    suspend fun syncAllPostsNow(username: String): PostSyncStatus = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val target = database.targetDao().getTarget(cleanUser)
        val cookie = sessionManager.getCookieString()
        archiveHighlightsAndPosts(
            username = cleanUser,
            targetId = target?.instagramId,
            cookie = cookie,
            wasPrivateToPublic = false,
            isPrivate = target?.isPrivate ?: false,
            forceFullPagination = true
        )
        _postSyncState.value[cleanUser] ?: PostSyncStatus.FAILED
    }

    private suspend fun archiveHighlightStoriesForEntity(
        highlight: HighlightEntity,
        cookie: String?,
        notifyChanges: Boolean = false
    ): List<HighlightMediaEntity> = withContext(Dispatchers.IO) {
        val cleanUser = highlight.username.trim().trimStart('@').lowercase()
        val dao = database.highlightDao()
        val existingMedia = dao.getMediaListForHighlight(highlight.id)
        val existingById = existingMedia.associateBy { it.id }

        val stories = instagramEngine.fetchHighlightStories(highlight.id, cookie)
        if (stories.isEmpty()) return@withContext existingMedia

        val nowMs = System.currentTimeMillis()
        val vaultManager = VaultStorageManager.getInstance(context)
        val safeHlId = highlight.id.replace(":", "_")
        val workers = getSettingsManager().archiverSpeedPreset.value.downloadWorkers.coerceIn(2, 10)
        val itemSem = Semaphore(workers)

        val mediaEntities = coroutineScope {
            stories.map { story ->
                async {
                    itemSem.withPermit {
                        val ext = if (story.mediaType == "video") "mp4" else "jpg"
                        val fileName = "${safeHlId}_${story.id}.$ext"
                        val existingVault = vaultManager.getVaultMediaFile(cleanUser, "highlights", fileName)
                        var localPath = story.mediaUrl
                        var resolvedMediaType = story.mediaType
                        var fileSize = existingById[story.id]?.fileSize ?: 0L

                        if (existingVault != null && existingVault.exists() && existingVault.length() > 0L) {
                            localPath = existingVault.absolutePath
                            fileSize = existingVault.length()
                        } else if (com.instalogger.app.engine.media.MediaUrlGuard.isAllowedMediaUrl(story.mediaUrl)) {
                            val tempMedia = File(context.cacheDir, "hl_item_${story.id}_${System.currentTimeMillis()}.$ext")
                            try {
                                instagramEngine.downloadMedia(story.mediaUrl, tempMedia)
                                val valid = com.instalogger.app.engine.media.MediaFileValidator.validateAndHash(tempMedia)
                                if (valid != null) {
                                    val encMedia = vaultManager.saveEncryptedFile(cleanUser, "highlights", fileName, tempMedia)
                                    localPath = encMedia.absolutePath
                                    fileSize = encMedia.length()
                                    resolvedMediaType = if (valid.isVideo) "video" else "image"
                                }
                            } catch (e: Exception) {
                                AppLogger.w("ProfileInspector", "Highlight media cache failed (${story.id}): ${e.message}")
                            } finally {
                                try { tempMedia.delete() } catch (_: Exception) {}
                            }
                        }

                        HighlightMediaEntity(
                            id = story.id,
                            highlightId = highlight.id,
                            username = cleanUser,
                            mediaType = resolvedMediaType,
                            mediaUrl = story.mediaUrl,
                            localPath = localPath,
                            takenAt = story.takenAt,
                            fileSize = fileSize,
                            isVanished = false,
                            vanishedAt = null,
                            archivedAt = existingById[story.id]?.archivedAt ?: nowMs
                        )
                    }
                }
            }.awaitAll()
        }

        if (mediaEntities.isNotEmpty()) {
            dao.insertHighlightMedia(mediaEntities)
            val liveIds = mediaEntities.map { it.id }.toHashSet()
            val addedCount = mediaEntities.count { it.id !in existingById }
            val removedItems = existingMedia.filter { !it.isVanished && it.id !in liveIds }

            if (existingMedia.isNotEmpty() && notifyChanges) {
                if (addedCount > 0) {
                    val title = LanguageManager.getString(context, R.string.notif_highlight_updated_title, cleanUser)
                    val desc = LanguageManager.getString(context, R.string.notif_highlight_updated_desc, highlight.title, addedCount)
                    database.changeLogDao().insertLog(
                        ChangeLogEntity(
                            targetUsername = cleanUser,
                            eventType = "highlight_updated",
                            title = title,
                            oldValue = existingMedia.size.toString(),
                            newValue = "${highlight.title} (+$addedCount)",
                            timestamp = nowMs
                        )
                    )
                    dispatchAlert(cleanUser, "highlight_updated", title, desc, nowMs)
                }
                if (removedItems.isNotEmpty()) {
                    for (rem in removedItems) {
                        dao.markHighlightMediaVanished(rem.id, nowMs)
                    }
                    val title = LanguageManager.getString(context, R.string.notif_highlight_item_vanished_title, cleanUser)
                    val desc = LanguageManager.getString(context, R.string.notif_highlight_item_vanished_desc, highlight.title, removedItems.size)
                    database.changeLogDao().insertLog(
                        ChangeLogEntity(
                            targetUsername = cleanUser,
                            eventType = "highlight_item_vanished",
                            title = title,
                            oldValue = highlight.title,
                            newValue = desc,
                            timestamp = nowMs
                        )
                    )
                    dispatchAlert(cleanUser, "highlight_item_vanished", title, desc, nowMs)
                }
            } else {
                for (rem in removedItems) {
                    dao.markHighlightMediaVanished(rem.id, nowMs)
                }
            }

            if (highlight.mediaCount < mediaEntities.size) {
                dao.insertHighlights(listOf(highlight.copy(mediaCount = mediaEntities.size)))
            }
        }
        dao.getMediaListForHighlight(highlight.id)
    }

    suspend fun fetchHighlightMediaOnDemand(highlight: HighlightEntity): List<HighlightMediaEntity> = withContext(Dispatchers.IO) {
        val cached = database.highlightDao().getMediaListForHighlight(highlight.id)
        if (highlight.isVanished) return@withContext cached
        val anyMissing = cached.any { !it.isVanished && (it.localPath.startsWith("http") || !File(it.localPath).exists()) }
        if (cached.isNotEmpty() && !anyMissing) return@withContext cached
        val cookie = sessionManager.getCookieString().takeIf { it.isNotBlank() }
        val fresh = archiveHighlightStoriesForEntity(highlight, cookie, notifyChanges = false)
        if (fresh.isNotEmpty()) fresh else cached
    }

    private suspend fun syncAndArchiveActiveStoriesInternal(
        target: TargetEntity,
        cookie: String?,
        notifyChanges: Boolean
    ): List<HighlightMediaEntity> = withContext(Dispatchers.IO) {
        val cleanUser = target.username.trim().trimStart('@').lowercase()
        val virtualId = "active_story:$cleanUser"
        val dao = database.highlightDao()
        val existingStories = dao.getArchivedStoriesListForUser(cleanUser)
        val existingById = existingStories.associateBy { it.id }
        val viewerDsUserId = instagramEngine.extractDsUserId(cookie)

        var resolvedTargetId = target.instagramId?.takeIf { it.isNotBlank() }
        if (resolvedTargetId == null || (viewerDsUserId != null && resolvedTargetId == viewerDsUserId)) {
            val fresh = instagramEngine.fetchProfile(cleanUser, cookieString = null).getOrNull()
            if (!fresh?.instagramId.isNullOrBlank()) {
                resolvedTargetId = fresh!!.instagramId
                database.targetDao().insertTarget(
                    target.copy(
                        instagramId = resolvedTargetId,
                        fullName = fresh.fullName.ifBlank { target.fullName },
                        avatarUrl = fresh.profilePicUrl ?: target.avatarUrl
                    )
                )
            }
        }

        val feedResult = instagramEngine.fetchActiveUserStoriesDetailed(
            userId = resolvedTargetId,
            username = cleanUser,
            cookieString = cookie
        )
        val freshStories = feedResult.stories
        val nowMs = System.currentTimeMillis()
        val newlyAdded = mutableListOf<HighlightMediaEntity>()

        if (freshStories.isNotEmpty()) {
            val vaultManager = VaultStorageManager.getInstance(context)
            val entities = mutableListOf<HighlightMediaEntity>()

            for (story in freshStories) {
                val ext = if (story.mediaType == "video") "mp4" else "jpg"
                val fileName = "story_${story.id}.$ext"
                val existingVault = vaultManager.getVaultMediaFile(cleanUser, "stories", fileName)
                var localPath = story.mediaUrl
                var resolvedMediaType = story.mediaType
                var fileSize = existingById[story.id]?.fileSize ?: 0L

                if (existingVault != null && existingVault.exists() && existingVault.length() > 0L) {
                    localPath = existingVault.absolutePath
                    fileSize = existingVault.length()
                } else if (com.instalogger.app.engine.media.MediaUrlGuard.isAllowedMediaUrl(story.mediaUrl)) {
                    val tempMedia = File(context.cacheDir, "story_item_${story.id}_${System.currentTimeMillis()}.$ext")
                    try {
                        instagramEngine.downloadMedia(story.mediaUrl, tempMedia)
                        val valid = com.instalogger.app.engine.media.MediaFileValidator.validateAndHash(tempMedia)
                        if (valid != null) {
                            val encFile = vaultManager.saveEncryptedFile(cleanUser, "stories", fileName, tempMedia)
                            localPath = encFile.absolutePath
                            fileSize = encFile.length()
                            resolvedMediaType = if (valid.isVideo) "video" else "image"
                        }
                    } catch (e: Exception) {
                        AppLogger.w("ProfileInspector", "Active story cache failed (${story.id}): ${e.message}")
                    } finally {
                        try { tempMedia.delete() } catch (_: Exception) {}
                    }
                }
                val ent = HighlightMediaEntity(
                    id = story.id,
                    highlightId = virtualId,
                    username = cleanUser,
                    mediaType = resolvedMediaType,
                    mediaUrl = story.mediaUrl,
                    localPath = localPath,
                    takenAt = story.takenAt,
                    fileSize = fileSize,
                    isVanished = false,
                    vanishedAt = null,
                    archivedAt = existingById[story.id]?.archivedAt ?: nowMs
                )
                entities.add(ent)
                if (story.id !in existingById) {
                    newlyAdded.add(ent)
                }
            }

            if (entities.isNotEmpty()) {
                dao.insertHighlightMedia(entities)
            }
        }

        // Notify newly added stories (when rule allows)
        if (notifyChanges && newlyAdded.isNotEmpty()) {
            val title = LanguageManager.getString(context, R.string.notif_story_added_title, cleanUser)
            val desc = LanguageManager.getString(context, R.string.notif_story_added_desc, newlyAdded.size)
            database.changeLogDao().insertLog(
                ChangeLogEntity(
                    targetUsername = cleanUser,
                    eventType = "story_added",
                    title = title,
                    oldValue = existingStories.size.toString(),
                    newValue = "+${newlyAdded.size}",
                    timestamp = nowMs
                )
            )
            dispatchAlert(cleanUser, "story_added", title, desc, nowMs)
        }

        // Detect early-deleted active stories (vanished before 23.5h expiry while feed is authoritative)
        if (feedResult.authoritative && existingStories.isNotEmpty()) {
            val liveIds = freshStories.map { it.id }.toHashSet()
            val earlyExpiryWindowMs = (23L * 60L + 30L) * 60L * 1000L
            val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            for (oldStory in existingStories) {
                val ageMs = nowMs - oldStory.takenAt
                if (!oldStory.isVanished && ageMs in 0L until earlyExpiryWindowMs && oldStory.id !in liveIds) {
                    dao.markHighlightMediaVanished(oldStory.id, nowMs)
                    val timeLabel = timeFmt.format(Date(oldStory.takenAt))
                    val title = LanguageManager.getString(context, R.string.notif_story_vanished_title, cleanUser)
                    val desc = LanguageManager.getString(context, R.string.notif_story_vanished_desc, timeLabel)
                    database.changeLogDao().insertLog(
                        ChangeLogEntity(
                            targetUsername = cleanUser,
                            eventType = "story_vanished",
                            title = title,
                            oldValue = oldStory.id,
                            newValue = desc,
                            timestamp = nowMs
                        )
                    )
                    if (notifyChanges) {
                        dispatchAlert(cleanUser, "story_vanished", title, desc, nowMs)
                    }
                }
            }
        }

        val cutoff = nowMs - (24L * 60L * 60L * 1000L)
        dao.getArchivedStoriesListForUser(cleanUser).filter { !it.isVanished && it.takenAt >= cutoff }
    }

    suspend fun fetchActiveStoriesOnDemand(target: TargetEntity): List<HighlightMediaEntity> = withContext(Dispatchers.IO) {
        val cookie = sessionManager.getCookieString().takeIf { it.isNotBlank() }
        val active = syncAndArchiveActiveStoriesInternal(target, cookie, notifyChanges = false)
        if (active.isNotEmpty()) active
        else database.highlightDao().getArchivedStoriesListForUser(target.username.trim().trimStart('@').lowercase())
    }

    suspend fun syncStoriesAndHighlightsNow(username: String) = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        lastHighlightTrayCheckMs.remove(cleanUser)
        lastActiveStoriesCheckMs.remove(cleanUser)
        val target = database.targetDao().getTarget(cleanUser) ?: return@withContext
        val cookie = sessionManager.getCookieString().takeIf { it.isNotBlank() }
        archiveHighlightsAndPosts(
            username = cleanUser,
            targetId = target.instagramId,
            cookie = cookie,
            wasPrivateToPublic = false,
            isPrivate = target.isPrivate,
            forceFullPagination = false,
            storyGrew = true
        )
    }

    suspend fun deleteArchivedStoryOrHighlightItem(item: HighlightMediaEntity): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        try {
            val f = File(item.localPath)
            if (!item.localPath.startsWith("http") && f.exists()) {
                freed += f.length()
                f.delete()
            }
        } catch (_: Exception) {}
        database.highlightDao().deleteHighlightMediaById(item.id)
        freed
    }

    suspend fun deleteArchivedHighlightTray(highlight: HighlightEntity): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        val dao = database.highlightDao()
        val mediaItems = dao.getMediaListForHighlight(highlight.id)
        for (item in mediaItems) {
            try {
                val f = File(item.localPath)
                if (!item.localPath.startsWith("http") && f.exists()) {
                    freed += f.length()
                    f.delete()
                }
            } catch (_: Exception) {}
        }
        highlight.localCoverPath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) {
                    freed += f.length()
                    f.delete()
                }
            } catch (_: Exception) {}
        }
        dao.deleteMediaForHighlight(highlight.id)
        dao.deleteHighlightById(highlight.id)
        freed
    }

    suspend fun deleteAllArchivedStoriesForUser(username: String): Long = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val dao = database.highlightDao()
        val stories = dao.getArchivedStoriesListForUser(cleanUser)
        var freed = 0L
        for (item in stories) {
            try {
                val f = File(item.localPath)
                if (!item.localPath.startsWith("http") && f.exists()) {
                    freed += f.length()
                    f.delete()
                }
            } catch (_: Exception) {}
        }
        dao.deleteArchivedStoriesForUser(cleanUser)
        freed
    }

    suspend fun inspectHighlightsAndPosts(username: String): PostSyncStatus = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val target = database.targetDao().getTarget(cleanUser)
        val cookie = sessionManager.getCookieString()
        val targetId = target?.instagramId
        val isPrivate = target?.isPrivate ?: false
        archiveHighlightsAndPosts(cleanUser, targetId, cookie, wasPrivateToPublic = false, isPrivate = isPrivate)
        _postSyncState.value[cleanUser] ?: PostSyncStatus.FAILED
    }

    // Fetches and archives Post/Reel comments + threaded replies into Room DB and sidecar JSON.
    suspend fun syncPostComments(
        post: PostEntity,
        maxComments: Int = 500
    ): CommentFetchResult {
        val cleanCode = post.id.trim()
        val cleanUser = post.username.trim().trimStart('@').lowercase()
        val job = appBackgroundScope.async {
            val mutex = commentSyncMutexMap.computeIfAbsent(cleanCode) { Mutex() }
            if (!mutex.tryLock()) {
                val existing = database.postCommentDao().getCommentsForPost(cleanCode)
                return@async CommentFetchResult(
                    comments = existing.map {
                        InstagramCommentItem(
                            id = it.id,
                            parentCommentId = it.parentCommentId,
                            authorUsername = it.authorUsername,
                            authorId = it.authorId,
                            authorFullName = it.authorFullName,
                            authorAvatarUrl = it.authorAvatarUrl,
                            isAuthorVerified = it.isAuthorVerified,
                            text = it.text,
                            likeCount = it.likeCount,
                            replyCount = it.replyCount,
                            createdAt = it.createdAt,
                            hasGifOrMedia = it.hasGifOrMedia
                        )
                    },
                    mediaId = existing.firstOrNull()?.mediaId,
                    collectionStatus = if (existing.any { it.collectionStatus == "partial" }) "partial" else "complete",
                    source = "cached_inflight"
                )
            }
            try {
                _commentSyncProgress.value = _commentSyncProgress.value + (cleanCode to 0)
                val cookie = sessionManager.getCookieString().takeIf { it.isNotBlank() }
                val mediaId = com.instalogger.app.engine.comments.CommentTreeEngine.shortcodeToMediaId(cleanCode)
                val pageDelay = getSettingsManager().archiverSpeedPreset.value.pageDelayMs

                val result = instagramEngine.fetchPostComments(
                    shortcode = cleanCode,
                    cookieString = cookie,
                    maxComments = maxComments,
                    reportedTotalCount = post.commentCount,
                    pageDelayMs = pageDelay
                ) { batch, discoveredTotal ->
                    val now = System.currentTimeMillis()
                    val entities = batch.map { item ->
                        PostCommentEntity(
                            id = item.id,
                            postId = cleanCode,
                            mediaId = mediaId,
                            username = cleanUser,
                            parentCommentId = item.parentCommentId,
                            authorUsername = item.authorUsername,
                            authorId = item.authorId,
                            authorFullName = item.authorFullName,
                            authorAvatarUrl = item.authorAvatarUrl,
                            isAuthorVerified = item.isAuthorVerified,
                            text = item.text,
                            likeCount = item.likeCount,
                            replyCount = item.replyCount,
                            createdAt = item.createdAt,
                            archivedAt = now,
                            collectionStatus = "partial",
                            hasGifOrMedia = item.hasGifOrMedia
                        )
                    }
                    if (entities.isNotEmpty()) {
                        database.postCommentDao().insertComments(entities)
                    }
                    _commentSyncProgress.value = _commentSyncProgress.value + (cleanCode to discoveredTotal)
                }

                // Final pass with finalized collectionStatus + sidecar _comments.json archive
                if (result.comments.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val finalEntities = result.comments.map { item ->
                        PostCommentEntity(
                            id = item.id,
                            postId = cleanCode,
                            mediaId = result.mediaId ?: mediaId,
                            username = cleanUser,
                            parentCommentId = item.parentCommentId,
                            authorUsername = item.authorUsername,
                            authorId = item.authorId,
                            authorFullName = item.authorFullName,
                            authorAvatarUrl = item.authorAvatarUrl,
                            isAuthorVerified = item.isAuthorVerified,
                            text = item.text,
                            likeCount = item.likeCount,
                            replyCount = item.replyCount,
                            createdAt = item.createdAt,
                            archivedAt = now,
                            collectionStatus = result.collectionStatus,
                            hasGifOrMedia = item.hasGifOrMedia
                        )
                    }
                    database.postCommentDao().insertComments(finalEntities)

                    val allStored = database.postCommentDao().getCommentsForPost(cleanCode)
                    if (allStored.size > post.commentCount) {
                        database.postDao().insertPosts(listOf(post.copy(commentCount = allStored.size)))
                    }
                    try {
                        val tree = com.instalogger.app.engine.comments.CommentTreeEngine.buildCommentTree(allStored)
                        val stats = com.instalogger.app.engine.comments.CommentTreeEngine.computeStats(allStored, post.commentCount)
                        val json = com.instalogger.app.engine.comments.CommentExporter.renderJson(
                            shortcode = cleanCode,
                            mediaId = result.mediaId ?: mediaId,
                            targetUsername = cleanUser,
                            nodes = tree,
                            stats = stats
                        )
                        ArchiveDirectoryManager.saveUserArchiveJson(context, cleanUser, "comments/${cleanCode}_comments.json", json)
                    } catch (e: Exception) {
                        AppLogger.w("ProfileInspector", "Failed to save sidecar _comments.json for $cleanCode: ${e.message}")
                    }
                }
                result
            } catch (e: Exception) {
                AppLogger.e("ProfileInspector", "syncPostComments($cleanCode) failed: ${e.message}")
                CommentFetchResult(emptyList(), collectionStatus = "partial", errorMessage = e.message)
            } finally {
                _commentSyncProgress.value = _commentSyncProgress.value - cleanCode
                mutex.unlock()
            }
        }
        return job.await()
    }

    companion object {
        private const val AVATAR_DHASH_THRESHOLD = 6
        private const val HIGHLIGHT_TRAY_REFRESH_INTERVAL_MS = 3L * 60L * 1000L
        private const val ACTIVE_STORY_REFRESH_INTERVAL_MS = 3L * 60L * 1000L
        private const val FRIENDSHIP_REFRESH_INTERVAL_MS = 15L * 60L * 1000L
        private const val UNFOLLOWED_PRIVATE_COOLDOWN_MS = 30L * 60L * 1000L
    }
}
