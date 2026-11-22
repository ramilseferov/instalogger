package com.instalogger.app.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.data.local.*
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.util.ArchiveDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ProfileInspector(
    private val context: Context,
    private val database: AppDatabase,
    private val sessionManager: SessionManager,
    private val instagramEngine: InstagramEngine = InstagramEngine(),
    private val onAlertNotification: (title: String, message: String) -> Unit = { _, _ -> }
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private suspend fun dispatchAlert(
        targetUsername: String,
        eventType: String,
        title: String,
        message: String,
        timestamp: Long
    ) {
        val app = context.applicationContext as? InstaLoggerApp
        val settings = app?.settingsManager
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
        val targetDao = database.targetDao()
        val changeLogDao = database.changeLogDao()
        val avatarHistoryDao = database.avatarHistoryDao()
        val notificationDao = database.notificationDao()
        val cleanUser = username.trim().trimStart('@').lowercase()

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

        // 1. Check avatar change
        val avatarUrl = snapshot.profilePicUrl
        if (!avatarUrl.isNullOrBlank()) {
            if (avatarUrl.startsWith("/")) {
                val f = File(avatarUrl)
                if (f.exists()) {
                    val internal = ArchiveDirectoryManager.copyToInternalAvatar(context, cleanUser, f)
                    localAvatarPath = internal?.absolutePath ?: f.absolutePath
                }
            } else if (localAvatarPath == null || avatarUrl != existing?.avatarUrl) {
                val downloadedPath = downloadAndArchiveAvatar(cleanUser, avatarUrl)
                if (downloadedPath != null) {
                    val oldPath = localAvatarPath
                    localAvatarPath = downloadedPath

                    val file = File(downloadedPath)
                    avatarHistoryDao.insertAvatar(
                        AvatarHistoryEntity(
                            username = cleanUser,
                            localPath = downloadedPath,
                            archivedAt = now,
                            fileSize = file.length()
                        )
                    )

                    if (existing != null && oldPath != null) {
                        val title = "@$cleanUser profile picture updated"
                        val desc = "New profile picture archived successfully"
                        changeLogDao.insertLog(
                            ChangeLogEntity(
                                targetUsername = cleanUser,
                                eventType = "avatar_change",
                                title = title,
                                oldValue = oldPath,
                                newValue = downloadedPath,
                                oldAvatarPath = oldPath,
                                newAvatarPath = downloadedPath,
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

        // Also import existing archives if available on disk
        importLocalArchives(cleanUser, snapshot.instagramId)

        // 2. Check privacy change
        if (existing != null && existing.isPrivate != snapshot.isPrivate) {
            val title = "@$cleanUser privacy status changed"
            val desc = if (snapshot.isPrivate) "Account changed to Private" else "Account changed to Public"
            val oldVal = if (existing.isPrivate) "Private" else "Public"
            val newVal = if (snapshot.isPrivate) "Private" else "Public"
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

        // 3. Check post count change
        if (existing != null && existing.mediaCount != snapshot.mediaCount && snapshot.mediaCount > 0) {
            val title = "@$cleanUser post count changed"
            val desc = "${existing.mediaCount} -> ${snapshot.mediaCount} posts"
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

        // 4. Check biography change
        if (existing != null && snapshot.biography.isNotBlank() && existing.biography != snapshot.biography) {
            val title = "@$cleanUser biography updated"
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

        // Resolve local avatar path if still null
        if (localAvatarPath == null) {
            localAvatarPath = findExistingLocalAvatar(cleanUser, snapshot.instagramId)
        }

        // Always query the absolute freshest record from Room DB right before writing
        // to prevent overwriting user modifications (e.g. isTurbo toggle) made while network fetch was running
        val freshInDb = targetDao.getTarget(cleanUser) ?: existing
        val resolvedTurbo = freshInDb?.isTurbo ?: existing?.isTurbo ?: false
        val resolvedActive = freshInDb?.isActive ?: existing?.isActive ?: true
        val resolvedInterval = freshInDb?.checkIntervalSeconds ?: existing?.checkIntervalSeconds ?: 60

        val updatedTarget = TargetEntity(
            username = cleanUser,
            instagramId = snapshot.instagramId ?: freshInDb?.instagramId ?: existing?.instagramId,
            fullName = if (snapshot.fullName.isNotBlank()) snapshot.fullName else (freshInDb?.fullName ?: existing?.fullName ?: ""),
            biography = if (snapshot.biography.isNotBlank()) snapshot.biography else (freshInDb?.biography ?: existing?.biography ?: ""),
            isPrivate = snapshot.isPrivate,
            followersCount = if (snapshot.followersCount > 0) snapshot.followersCount else (freshInDb?.followersCount ?: existing?.followersCount ?: 0L),
            followingCount = if (snapshot.followingCount > 0) snapshot.followingCount else (freshInDb?.followingCount ?: existing?.followingCount ?: 0L),
            mediaCount = if (snapshot.mediaCount > 0) snapshot.mediaCount else (freshInDb?.mediaCount ?: existing?.mediaCount ?: 0L),
            avatarUrl = snapshot.profilePicUrl ?: freshInDb?.avatarUrl ?: existing?.avatarUrl,
            localAvatarPath = localAvatarPath,
            isTurbo = resolvedTurbo,
            isActive = resolvedActive,
            checkIntervalSeconds = resolvedInterval,
            lastCheckedAt = now,
            lastStatusChangeAt = if (freshInDb != null && freshInDb.isPrivate != snapshot.isPrivate) now else (freshInDb?.lastStatusChangeAt ?: existing?.lastStatusChangeAt),
            createdAt = freshInDb?.createdAt ?: existing?.createdAt ?: now
        )

        targetDao.insertTarget(updatedTarget)
        Result.success(updatedTarget)
    }

    suspend fun updateDaemonTurbo(targetIdentifier: String, isTurbo: Boolean) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:3000/api/targets/$targetIdentifier/turbo")
                .post("{\"turbo\": $isTurbo}".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().close()
        } catch (_: Exception) {
            // Daemon not reachable or endpoint failed
        }
    }

    private fun fetchFromLocalDaemon(username: String): InstagramProfileSnapshot? {
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:3000/api/targets")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
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
                                    .url("http://127.0.0.1:3000/api/targets/$id/check")
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
                            avatarUrlString = "http://127.0.0.1:3000/$picPath"
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

                        return InstagramProfileSnapshot(
                            username = username,
                            instagramId = id,
                            fullName = fullName,
                            biography = bio,
                            isPrivate = isPrivate,
                            followersCount = daemonFollowers,
                            followingCount = daemonFollowing,
                            mediaCount = mediaCount,
                            profilePicUrl = localPic ?: avatarUrlString
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Local daemon not reachable, fallback to direct engine
        }
        return null
    }

    suspend fun syncAllFromDaemon(): List<String> = withContext(Dispatchers.IO) {
        val synced = mutableListOf<String>()
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:3000/api/targets")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = gson.fromJson(body, JsonObject::class.java) ?: return@withContext emptyList()
                val targets = json.getAsJsonArray("targets") ?: return@withContext emptyList()

                for (item in targets) {
                    val obj = item.asJsonObject ?: continue
                    val u = obj.get("username")?.asString ?: continue
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

        for (d in dirs) {
            if (d.exists() && d.isDirectory) {
                val files = d.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) } ?: continue
                for (file in files) {
                    if (file.name == "avatar_current.jpg" || file.name.startsWith(".")) continue
                    if (existingNames.contains(file.name)) continue

                    val internal = ArchiveDirectoryManager.copyToInternalNamedAvatar(context, username, file)
                    val pathToStore = internal?.absolutePath ?: file.absolutePath

                    database.avatarHistoryDao().insertAvatar(
                        AvatarHistoryEntity(
                            username = username,
                            localPath = pathToStore,
                            archivedAt = file.lastModified(),
                            fileSize = file.length()
                        )
                    )
                }
            }
        }
    }

    private fun downloadAndArchiveAvatar(username: String, url: String): String? {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val fileName = "avatar_${timestamp}.jpg"

        val targetDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$username/profile")
        targetDir.mkdirs()

        val destFile = File(targetDir, fileName)
        val currentFile = File(targetDir, "avatar_current.jpg")

        return try {
            val size = instagramEngine.downloadImage(url, destFile)
            if (size > 0) {
                try {
                    destFile.copyTo(currentFile, overwrite = true)
                } catch (e: Exception) {
                    // Ignore copy error
                }
                ArchiveDirectoryManager.copyToInternalAvatar(context, username, destFile)
                destFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e("ProfileInspector", "Failed to download avatar: ${e.message}")
            null
        }
    }
}
