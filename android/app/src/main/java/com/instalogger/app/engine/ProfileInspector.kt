package com.instalogger.app.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.instalogger.app.data.local.*
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

    suspend fun inspectTarget(username: String): Result<TargetEntity> = withContext(Dispatchers.IO) {
        val targetDao = database.targetDao()
        val changeLogDao = database.changeLogDao()
        val avatarHistoryDao = database.avatarHistoryDao()
        val notificationDao = database.notificationDao()
        val cleanUser = username.trim().trimStart('@').lowercase()

        // 1. First priority: Check local daemon at http://127.0.0.1:3000 (same source powering website)
        val daemonSnapshot = fetchFromLocalDaemon(cleanUser)

        val snapshot = if (daemonSnapshot != null) {
            daemonSnapshot
        } else {
            // 2. Fallback to direct Instagram engine
            val cookie = sessionManager.getCookieString()
            val engineResult = instagramEngine.fetchProfile(cleanUser, cookie)
            if (engineResult.isSuccess) {
                engineResult.getOrThrow()
            } else {
                val existing = targetDao.getTarget(cleanUser)
                if (existing != null) {
                    // Update check timestamp even if network failed
                    val updated = existing.copy(lastCheckedAt = System.currentTimeMillis())
                    targetDao.insertTarget(updated)
                    return@withContext Result.success(updated)
                }
                return@withContext Result.failure(engineResult.exceptionOrNull() ?: Exception("Failed to fetch target data"))
            }
        }

        val existing = targetDao.getTarget(cleanUser)
        val now = System.currentTimeMillis()
        var localAvatarPath = existing?.localAvatarPath

        // 1. Check avatar change
        val avatarUrl = snapshot.profilePicUrl
        if (!avatarUrl.isNullOrBlank() && (localAvatarPath == null || avatarUrl != existing?.avatarUrl)) {
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
                    notificationDao.insertNotification(
                        NotificationEntity(
                            targetId = cleanUser,
                            username = cleanUser,
                            eventType = "avatar_change",
                            title = title,
                            message = desc,
                            timestamp = now
                        )
                    )
                    onAlertNotification(title, desc)
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
            notificationDao.insertNotification(
                NotificationEntity(
                    targetId = cleanUser,
                    username = cleanUser,
                    eventType = "privacy_change",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            )
            onAlertNotification(title, desc)
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
            notificationDao.insertNotification(
                NotificationEntity(
                    targetId = cleanUser,
                    username = cleanUser,
                    eventType = "post_count_change",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            )
            onAlertNotification(title, desc)
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

        // 5. Always record an inspection check log so user sees live update details
        changeLogDao.insertLog(
            ChangeLogEntity(
                targetUsername = cleanUser,
                eventType = "check_status",
                title = "@$cleanUser verified",
                oldValue = "Checked at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))}",
                newValue = if (snapshot.isPrivate) "Private | Media: ${snapshot.mediaCount}" else "Public | Media: ${snapshot.mediaCount}",
                timestamp = now
            )
        )

        // Resolve local avatar path if still null
        if (localAvatarPath == null) {
            localAvatarPath = findExistingLocalAvatar(cleanUser, snapshot.instagramId)
        }

        val updatedTarget = TargetEntity(
            username = cleanUser,
            instagramId = snapshot.instagramId ?: existing?.instagramId,
            fullName = if (snapshot.fullName.isNotBlank()) snapshot.fullName else (existing?.fullName ?: ""),
            biography = if (snapshot.biography.isNotBlank()) snapshot.biography else (existing?.biography ?: ""),
            isPrivate = snapshot.isPrivate,
            followersCount = if (snapshot.followersCount > 0) snapshot.followersCount else (existing?.followersCount ?: 0L),
            followingCount = if (snapshot.followingCount > 0) snapshot.followingCount else (existing?.followingCount ?: 0L),
            mediaCount = if (snapshot.mediaCount > 0) snapshot.mediaCount else (existing?.mediaCount ?: 0L),
            avatarUrl = snapshot.profilePicUrl ?: existing?.avatarUrl,
            localAvatarPath = localAvatarPath,
            isTurbo = existing?.isTurbo ?: false,
            isActive = true,
            checkIntervalSeconds = existing?.checkIntervalSeconds ?: 60,
            lastCheckedAt = now,
            lastStatusChangeAt = if (existing != null && existing.isPrivate != snapshot.isPrivate) now else existing?.lastStatusChangeAt,
            createdAt = existing?.createdAt ?: now
        )

        targetDao.insertTarget(updatedTarget)
        Result.success(updatedTarget)
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
                        if (!picPath.isNullOrBlank()) {
                            val f1 = File("/storage/emulated/0/instalogger/$picPath")
                            val f2 = File(ArchiveDirectoryManager.getArchiveDirectory(context), picPath.removePrefix("archives/"))
                            if (f1.exists()) localPic = f1.absolutePath
                            else if (f2.exists()) localPic = f2.absolutePath
                        }

                        return InstagramProfileSnapshot(
                            username = username,
                            instagramId = id,
                            fullName = fullName,
                            biography = bio,
                            isPrivate = isPrivate,
                            followersCount = 0L,
                            followingCount = 0L,
                            mediaCount = mediaCount,
                            profilePicUrl = localPic
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Local daemon not reachable, fallback to direct engine
        }
        return null
    }

    private fun findExistingLocalAvatar(username: String, instagramId: String?): String? {
        val candidates = mutableListOf<File>()
        val archiveRoot = ArchiveDirectoryManager.getArchiveDirectory(context)

        if (!instagramId.isNullOrBlank()) {
            candidates.add(File("/storage/emulated/0/instalogger/archives/$instagramId/profile/avatar_current.jpg"))
            candidates.add(File(archiveRoot, "$instagramId/profile/avatar_current.jpg"))
        }
        candidates.add(File(archiveRoot, "$username/profile/avatar_current.jpg"))
        candidates.add(File("/storage/emulated/0/instalogger/archives/$username/profile/avatar_current.jpg"))

        for (f in candidates) {
            if (f.exists() && f.length() > 0) return f.absolutePath
        }
        return null
    }

    private suspend fun importLocalArchives(username: String, instagramId: String?) {
        val dirs = mutableListOf<File>()
        val archiveRoot = ArchiveDirectoryManager.getArchiveDirectory(context)

        if (!instagramId.isNullOrBlank()) {
            dirs.add(File("/storage/emulated/0/instalogger/archives/$instagramId/profile"))
            dirs.add(File(archiveRoot, "$instagramId/profile"))
        }
        dirs.add(File(archiveRoot, "$username/profile"))
        dirs.add(File("/storage/emulated/0/instalogger/archives/$username/profile"))

        for (d in dirs) {
            if (d.exists() && d.isDirectory) {
                val files = d.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) } ?: continue
                for (file in files) {
                    database.avatarHistoryDao().insertAvatar(
                        AvatarHistoryEntity(
                            username = username,
                            localPath = file.absolutePath,
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
                destFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e("ProfileInspector", "Failed to download avatar: ${e.message}")
            null
        }
    }
}
