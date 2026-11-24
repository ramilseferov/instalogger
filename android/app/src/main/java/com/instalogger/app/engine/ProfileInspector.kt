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
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.LanguageManager
import com.instalogger.app.util.VaultStorageManager
import com.instalogger.app.util.cleanDisplayText
import kotlinx.coroutines.Dispatchers
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val inspectionLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private suspend fun dispatchAlert(
        targetUsername: String,
        eventType: String,
        title: String,
        message: String,
        timestamp: Long
    ) {
        val app = context.applicationContext as? InstaLoggerApp
        val settings = app?.settingsManager

        // Deduplication guard: ignore identical message for the same event and target within 60 seconds
        val latestNotif = database.notificationDao().getLatestNotificationForEvent(targetUsername, eventType)
        if (latestNotif != null && latestNotif.message == message && (timestamp - latestNotif.timestamp) < 60_000L) {
            return
        }

        var notifId = timestamp

        if (settings == null || settings.inAppNotifications.value) {
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
            eventType = eventType
        )
        onAlertNotification(title, message)
    }

    suspend fun inspectTarget(username: String): Result<TargetEntity> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()

        // Fast-path guard: Abort immediately if target has been deleted
        if (isTargetDeleted(cleanUser)) {
            return@withContext Result.failure(Exception("Target was deleted"))
        }

        val targetMutex = inspectionLocks.computeIfAbsent(cleanUser) { kotlinx.coroutines.sync.Mutex() }
        if (!targetMutex.tryLock()) {
            val inDb = database.targetDao().getTarget(cleanUser)
            return@withContext if (inDb != null) Result.success(inDb) else Result.failure(Exception("Inspection already running for @$cleanUser"))
        }

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
                    mediaCount = if (directSnapshot.mediaCount > 0) directSnapshot.mediaCount else daemonSnapshot.mediaCount
                )
            } else {
                daemonSnapshot
            }
        } else {
            val freshInDb = targetDao.getTarget(cleanUser)
            if (freshInDb != null) {
                val updated = freshInDb.copy(lastCheckedAt = System.currentTimeMillis())
                targetDao.insertTarget(updated)
                return@withContext Result.success(updated)
            }
            return@withContext Result.failure(engineResult.exceptionOrNull() ?: Exception("Failed to fetch target data"))
        }

        val existing = targetDao.getTarget(cleanUser)
        val now = System.currentTimeMillis()
        var localAvatarPath = existing?.localAvatarPath
        var currentAvatarHash = existing?.avatarHash

        // Verify if localAvatarPath actually exists on disk! If missing or 0 bytes, reset so it re-downloads cleanly
        if (localAvatarPath != null) {
            val fileCheck = File(localAvatarPath)
            if (!fileCheck.exists() || fileCheck.length() == 0L) {
                localAvatarPath = null
                currentAvatarHash = null
            }
        }

        // If existing record has local file but no stored hash, compute it lazily
        if (currentAvatarHash == null && localAvatarPath != null) {
            currentAvatarHash = computeFileSha256(File(localAvatarPath))
        }

        // 1. Check avatar change (Dual check: URL diff + second-pass SHA-256 binary hash verification)
        val avatarUrl = snapshot.profilePicUrl
        if (!avatarUrl.isNullOrBlank()) {
            if (avatarUrl.startsWith("/")) {
                val f = File(avatarUrl)
                if (f.exists()) {
                    val internal = ArchiveDirectoryManager.copyToInternalAvatar(context, cleanUser, f)
                    localAvatarPath = internal?.absolutePath ?: f.absolutePath
                    currentAvatarHash = computeFileSha256(File(localAvatarPath))
                }
            } else if (localAvatarPath == null ||
                currentAvatarHash == null ||
                extractAvatarAssetKey(avatarUrl) != extractAvatarAssetKey(existing?.avatarUrl)
            ) {
                val tempFile = downloadAvatarToTemp(cleanUser, avatarUrl)
                if (tempFile != null) {
                    val newHash = computeFileSha256(tempFile)
                    val newDHash = computePerceptualDHash(tempFile)
                    val existingAvatars = avatarHistoryDao.getAvatarsListForTarget(cleanUser)
                    val isExactDup = (currentAvatarHash != null && newHash == currentAvatarHash) ||
                        (newHash != null && existingAvatars.any { it.fileHash == newHash })
                    val isVisualDup = newDHash != null && (
                        (localAvatarPath != null && isVisuallyDuplicateAvatar(newDHash, File(localAvatarPath))) ||
                            existingAvatars.any { isVisuallyDuplicateAvatar(newDHash, File(it.localPath)) }
                        )
                    val isDuplicateAvatar = isExactDup || isVisualDup

                    if (isDuplicateAvatar && localAvatarPath != null && File(localAvatarPath).exists()) {
                        // Image is byte-identical or perceptually identical (CDN JPEG re-encoding / resolution variant)
                        tempFile.delete()
                    } else {
                        // Genuinely NEW avatar (or initial avatar on first run)
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                        val timestamp = dateFormat.format(Date(now))
                        val fileName = "avatar_${timestamp}.jpg"

                        val targetDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$cleanUser/profile")
                        targetDir.mkdirs()

                        val destFile = File(targetDir, fileName)
                        val currentFile = File(targetDir, "avatar_current.jpg")

                        try {
                            tempFile.copyTo(destFile, overwrite = true)
                            tempFile.copyTo(currentFile, overwrite = true)
                        } catch (e: Exception) {
                            Log.e("ProfileInspector", "Failed to copy avatar files: ${e.message}")
                        } finally {
                            tempFile.delete()
                        }

                        ArchiveDirectoryManager.copyToInternalAvatar(context, cleanUser, destFile)

                        val oldPath = localAvatarPath
                        localAvatarPath = destFile.absolutePath
                        val oldHash = currentAvatarHash
                        currentAvatarHash = newHash

                        avatarHistoryDao.insertAvatar(
                            AvatarHistoryEntity(
                                username = cleanUser,
                                localPath = destFile.absolutePath,
                                archivedAt = now,
                                fileSize = destFile.length(),
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
                                    newValue = newHash ?: destFile.absolutePath,
                                    oldAvatarPath = oldPath,
                                    newAvatarPath = destFile.absolutePath,
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

        // 5. Check followers count change (Strict deduplication guard against race-conditions)
        if (existing != null && existing.followersCount > 0 && snapshot.followersCount > 0 && existing.followersCount != snapshot.followersCount) {
            val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "followers_change")
            if (latestLog?.newValue != snapshot.followersCount.toString()) {
                val diff = snapshot.followersCount - existing.followersCount
                val sign = if (diff > 0) "+$diff" else "$diff"
                val title = LanguageManager.getString(context, R.string.notif_followers_changed_title, cleanUser, sign)
                val desc = LanguageManager.getString(context, R.string.notif_followers_changed_desc, existing.followersCount, snapshot.followersCount, sign)
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
                dispatchAlert(
                    targetUsername = cleanUser,
                    eventType = "followers_change",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            }
        }

        // 6. Check following count change (Strict deduplication guard against race-conditions)
        if (existing != null && existing.followingCount > 0 && snapshot.followingCount > 0 && existing.followingCount != snapshot.followingCount) {
            val latestLog = changeLogDao.getLatestLogForEvent(cleanUser, "following_change")
            if (latestLog?.newValue != snapshot.followingCount.toString()) {
                val diff = snapshot.followingCount - existing.followingCount
                val sign = if (diff > 0) "+$diff" else "$diff"
                val title = LanguageManager.getString(context, R.string.notif_following_changed_title, cleanUser, sign)
                val desc = LanguageManager.getString(context, R.string.notif_following_changed_desc, existing.followingCount, snapshot.followingCount, sign)
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
                dispatchAlert(
                    targetUsername = cleanUser,
                    eventType = "following_change",
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

        val updatedTarget = TargetEntity(
            username = cleanUser,
            instagramId = snapshot.instagramId ?: freshInDb?.instagramId ?: existing?.instagramId,
            fullName = if (snapshot.fullName.isNotBlank()) snapshot.fullName else (freshInDb?.fullName ?: existing?.fullName ?: ""),
            biography = if (snapshot.biography.isNotBlank()) snapshot.biography else (freshInDb?.biography ?: existing?.biography ?: ""),
            isPrivate = snapshot.isPrivate,
            followersCount = if (snapshot.followersCount > 0) snapshot.followersCount else (freshInDb?.followersCount ?: existing?.followersCount ?: 0L),
            followingCount = if (snapshot.followingCount > 0) snapshot.followingCount else (freshInDb?.followingCount ?: existing?.followingCount ?: 0L),
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
            createdAt = freshInDb?.createdAt ?: existing?.createdAt ?: now
        )

        if (isTargetDeleted(cleanUser)) {
            return@withContext Result.failure(Exception("Target was deleted"))
        }

        // IMMEDIATE DATABASE COMMIT: Guarantees that any concurrent/subsequent check reads fresh state
        targetDao.insertTarget(updatedTarget)

        // 7. Check and archive followers & following lists in background
        val targetId = snapshot.instagramId ?: existing?.instagramId
        if (!targetId.isNullOrBlank()) {
            if (!cookie.isNullOrBlank() || wasPrivateToPublic || !snapshot.isPrivate) {
                inspectFollowersList(cleanUser, targetId, cookie, now)
                inspectFollowingList(cleanUser, targetId, cookie, now)
            }
        }

        // 8. Auto archive highlights and posts (public profiles always run without session)
        if (!snapshot.isPrivate || !cookie.isNullOrBlank()) {
            archiveHighlightsAndPosts(
                username = cleanUser,
                targetId = targetId,
                cookie = cookie,
                wasPrivateToPublic = wasPrivateToPublic,
                isPrivate = snapshot.isPrivate
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
                                // Ignore check trigger error
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

        // 1. Record in persistent tombstones immediately to prevent resurrection under race conditions
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
                    // Ignore if daemon is offline
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
            // Ignore
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
                        // Redundant duplicate avatar file! Delete duplicate from disk if not already registered
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

    /**
     * Computes SHA-256 over the PLAINTEXT media bytes (automatically decrypting Vault .enc files first)
     * so encrypted random-IV files never cause false-positive hash mismatches.
     */
    private fun computeFileSha256(file: File): String? {
        val bytes = readPlaintextBytes(file) ?: return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Computes a 64-bit perceptual difference hash (dHash) over a 9x8 grayscale thumbnail.
     * Catches visually identical avatars re-encoded or resized by Instagram CDN edges.
     */
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

    private suspend fun inspectFollowersList(username: String, targetId: String, cookie: String?, now: Long) {
        try {
            val freshUsers = instagramEngine.fetchFollowersList(targetId, cookie, limit = 50)
            if (freshUsers.isEmpty()) return

            val oldJson = ArchiveDirectoryManager.getUserArchiveJson(context, username, "followers.json")
            if (!oldJson.isNullOrBlank()) {
                val oldType = object : com.google.gson.reflect.TypeToken<List<InstagramUserSummary>>() {}.type
                val oldUsers: List<InstagramUserSummary> = try {
                    gson.fromJson(oldJson, oldType) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (oldUsers.isNotEmpty()) {
                    val oldSet = oldUsers.map { it.username.lowercase() }.toSet()
                    val newSet = freshUsers.map { it.username.lowercase() }.toSet()

                    val gained = freshUsers.filter { it.username.lowercase() !in oldSet }
                    val lost = oldUsers.filter { it.username.lowercase() !in newSet }

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

            // Save updated followers list archive
            val newJson = gson.toJson(freshUsers)
            ArchiveDirectoryManager.saveUserArchiveJson(context, username, "followers.json", newJson)
        } catch (e: Exception) {
            Log.w("ProfileInspector", "Failed to inspect followers list for @$username: ${e.message}")
        }
    }

    private suspend fun inspectFollowingList(username: String, targetId: String, cookie: String?, now: Long) {
        try {
            val freshUsers = instagramEngine.fetchFollowingList(targetId, cookie, limit = 50)
            if (freshUsers.isEmpty()) return

            val oldJson = ArchiveDirectoryManager.getUserArchiveJson(context, username, "following.json")
            if (!oldJson.isNullOrBlank()) {
                val oldType = object : com.google.gson.reflect.TypeToken<List<InstagramUserSummary>>() {}.type
                val oldUsers: List<InstagramUserSummary> = try {
                    gson.fromJson(oldJson, oldType) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (oldUsers.isNotEmpty()) {
                    val oldSet = oldUsers.map { it.username.lowercase() }.toSet()
                    val newSet = freshUsers.map { it.username.lowercase() }.toSet()

                    val gained = freshUsers.filter { it.username.lowercase() !in oldSet }
                    val lost = oldUsers.filter { it.username.lowercase() !in newSet }

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

            // Save updated following list archive
            val newJson = gson.toJson(freshUsers)
            ArchiveDirectoryManager.saveUserArchiveJson(context, username, "following.json", newJson)
        } catch (e: Exception) {
            Log.w("ProfileInspector", "Failed to inspect following list for @$username: ${e.message}")
        }
    }

    suspend fun archiveHighlightsAndPosts(
        username: String,
        targetId: String? = null,
        cookie: String? = null,
        wasPrivateToPublic: Boolean = false,
        isPrivate: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val targetRecord = database.targetDao().getTarget(cleanUser)
        val resolvedPrivate = isPrivate || (targetRecord?.isPrivate == true && !wasPrivateToPublic)
        val effectiveTargetId = targetId ?: targetRecord?.instagramId
        val effectiveCookie = cookie ?: sessionManager.getCookieString()

        // 1. Immediately import any media already cached on disk or daemon
        importLocalPostsAndHighlights(cleanUser, effectiveTargetId)
        fetchMediaFromLocalDaemon(cleanUser, effectiveTargetId)

        // Private accounts without a session cannot be queried anonymously
        if (resolvedPrivate && effectiveCookie.isNullOrBlank()) {
            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.REQUIRES_SESSION)
            return@withContext
        }

        try {
            // 2. Fetch highlights list if targetId is available
            var hlCount = 0
            if (!effectiveTargetId.isNullOrBlank()) {
                val highlights = instagramEngine.fetchUserHighlights(effectiveTargetId, effectiveCookie)
                if (highlights.isNotEmpty()) {
                    hlCount = highlights.size
                    val highlightDao = database.highlightDao()
                    val highlightEntities = mutableListOf<HighlightEntity>()
                    val mediaEntities = mutableListOf<HighlightMediaEntity>()

                    val archiveDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$cleanUser/highlights")
                    archiveDir.mkdirs()
                    ArchiveDirectoryManager.ensureNoMedia(archiveDir)
                    val internalDir = File(context.filesDir, "highlights/$cleanUser")
                    internalDir.mkdirs()
                    ArchiveDirectoryManager.ensureNoMedia(internalDir)
                    val vaultManager = VaultStorageManager.getInstance(context)

                    for (hl in highlights) {
                        var localCover: String? = null
                        if (!hl.coverUrl.isNullOrBlank()) {
                            val coverFile = File(archiveDir, "${hl.id.replace(":", "_")}_cover.jpg")
                            if (!coverFile.exists() || coverFile.length() == 0L) {
                                instagramEngine.downloadMedia(hl.coverUrl, coverFile)
                            }
                            if (coverFile.exists() && coverFile.length() > 0L) {
                                val bytes = coverFile.readBytes()
                                val encCover = vaultManager.saveEncryptedFile(cleanUser, "highlights", coverFile.name, bytes)
                                localCover = encCover.absolutePath
                            }
                        }

                        highlightEntities.add(
                            HighlightEntity(
                                id = hl.id,
                                username = cleanUser,
                                title = hl.title.cleanDisplayText(),
                                coverUrl = hl.coverUrl ?: "",
                                localCoverPath = localCover,
                                mediaCount = hl.mediaCount,
                                archivedAt = System.currentTimeMillis()
                            )
                        )

                        // Fetch stories for this highlight
                        val stories = instagramEngine.fetchHighlightStories(hl.id, effectiveCookie)
                        for (story in stories) {
                            val ext = if (story.mediaType == "video") "mp4" else "jpg"
                            val fileName = "${hl.id.replace(":", "_")}_${story.id}.$ext"
                            val mediaFile = File(archiveDir, fileName)

                            if (!mediaFile.exists() || mediaFile.length() == 0L) {
                                instagramEngine.downloadMedia(story.mediaUrl, mediaFile)
                            }

                            val localPath = if (mediaFile.exists() && mediaFile.length() > 0L) {
                                val bytes = mediaFile.readBytes()
                                val encMedia = vaultManager.saveEncryptedFile(cleanUser, "highlights", fileName, bytes)
                                encMedia.absolutePath
                            } else {
                                story.mediaUrl
                            }

                            mediaEntities.add(
                                HighlightMediaEntity(
                                    id = story.id,
                                    highlightId = hl.id,
                                    username = cleanUser,
                                    mediaType = story.mediaType,
                                    mediaUrl = story.mediaUrl,
                                    localPath = localPath,
                                    takenAt = story.takenAt
                                )
                            )
                        }
                    }

                    if (highlightEntities.isNotEmpty()) {
                        highlightDao.insertHighlights(highlightEntities)
                    }
                    if (mediaEntities.isNotEmpty()) {
                        highlightDao.insertHighlightMedia(mediaEntities)
                    }
                }
            }

            // 3. Fetch and archive recent posts (zero-login for public profiles)
            _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.SYNCING)
            val postResult = instagramEngine.fetchRecentPostsDetailed(
                userId = effectiveTargetId,
                username = cleanUser,
                cookieString = effectiveCookie,
                limit = 24,
                isPrivate = resolvedPrivate
            )
            var fetchedCount = 0
            when (postResult) {
                is PostFetchResult.RateLimited -> {
                    _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.RATE_LIMITED)
                }
                is PostFetchResult.RequiresSession -> {
                    val status = if (resolvedPrivate) PostSyncStatus.REQUIRES_SESSION else PostSyncStatus.FAILED
                    _postSyncState.value = _postSyncState.value + (cleanUser to status)
                }
                is PostFetchResult.Error -> {
                    _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.FAILED)
                }
                is PostFetchResult.Success -> {
                    val rawPosts = postResult.posts
                    fetchedCount = rawPosts.size
                    if (rawPosts.isNotEmpty()) {
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
                            // Persist discovered post list immediately so the grid renders with zero wait
                            postDao.insertPosts(postEntities)
                            val cur = database.targetDao().getTarget(cleanUser)
                            if (cur != null && (cur.mediaCount == 0L || cur.mediaCount < postEntities.size)) {
                                database.targetDao().insertTarget(cur.copy(mediaCount = postEntities.size.toLong()))
                            }

                            // Hand off media extraction, FFmpeg muxing, SHA-256 dedup & Vault encryption
                            // to the bounded multi-provider orchestrator
                            mediaDownloadOrchestrator?.enqueueBatch(cleanUser, orchestratorQueue)
                        }
                        _postSyncState.value = _postSyncState.value + (cleanUser to PostSyncStatus.COMPLETED)
                    } else {
                        val status = if (resolvedPrivate && effectiveCookie.isNullOrBlank()) {
                            PostSyncStatus.REQUIRES_SESSION
                        } else {
                            PostSyncStatus.COMPLETED
                        }
                        _postSyncState.value = _postSyncState.value + (cleanUser to status)
                    }
                }
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
            Log.w("ProfileInspector", "Failed to archive highlights and posts for @$cleanUser: ${e.message}")
        }
    }

    suspend fun inspectHighlightsAndPosts(username: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val target = database.targetDao().getTarget(cleanUser)
        val cookie = sessionManager.getCookieString()
        val targetId = target?.instagramId
        val isPrivate = target?.isPrivate ?: false
        archiveHighlightsAndPosts(cleanUser, targetId, cookie, wasPrivateToPublic = false, isPrivate = isPrivate)
        true
    }

    companion object {
        private const val AVATAR_DHASH_THRESHOLD = 6
    }
}
