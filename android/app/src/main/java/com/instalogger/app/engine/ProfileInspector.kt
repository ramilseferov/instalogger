package com.instalogger.app.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import com.instalogger.app.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ProfileInspector(
    private val context: Context,
    private val database: AppDatabase,
    private val sessionManager: SessionManager,
    private val instagramEngine: InstagramEngine = InstagramEngine(),
    private val onAlertNotification: (title: String, message: String) -> Unit = { _, _ -> }
) {

    suspend fun inspectTarget(username: String): Result<TargetEntity> = withContext(Dispatchers.IO) {
        val targetDao = database.targetDao()
        val changeLogDao = database.changeLogDao()
        val avatarHistoryDao = database.avatarHistoryDao()
        val notificationDao = database.notificationDao()

        val cookie = sessionManager.getCookieString()
        val snapshotResult = instagramEngine.fetchProfile(username, cookie)

        if (snapshotResult.isFailure) {
            val errorMsg = snapshotResult.exceptionOrNull()?.message ?: "Xəta baş verdi"
            return@withContext Result.failure(Exception(errorMsg))
        }

        val snapshot = snapshotResult.getOrThrow()
        val existing = targetDao.getTarget(username)
        val now = System.currentTimeMillis()

        var localAvatarPath = existing?.localAvatarPath

        // 1. Check avatar change
        val avatarUrl = snapshot.profilePicUrl
        if (!avatarUrl.isNullOrBlank() && (localAvatarPath == null || avatarUrl != existing?.avatarUrl)) {
            val downloadedPath = downloadAndArchiveAvatar(username, avatarUrl)
            if (downloadedPath != null) {
                val oldPath = localAvatarPath
                localAvatarPath = downloadedPath

                // Save to avatar history
                val file = File(downloadedPath)
                avatarHistoryDao.insertAvatar(
                    AvatarHistoryEntity(
                        username = username,
                        localPath = downloadedPath,
                        archivedAt = now,
                        fileSize = file.length()
                    )
                )

                // If this is an actual change (not first download)
                if (existing != null && oldPath != null) {
                    val title = "@$username profil şəkli yeniləndi"
                    val desc = "Yeni profil şəkli uğurla arxivləndi"
                    changeLogDao.insertLog(
                        ChangeLogEntity(
                            targetUsername = username,
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
                            targetId = username,
                            username = username,
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

        // 2. Check privacy change
        if (existing != null && existing.isPrivate != snapshot.isPrivate) {
            val title = "@$username məxfilik statusu dəyişdi"
            val desc = if (snapshot.isPrivate) "Hesab məxfi (Private) edildi" else "Hesab hamı üçün açıq (Public) oldu!"
            changeLogDao.insertLog(
                ChangeLogEntity(
                    targetUsername = username,
                    eventType = "privacy_change",
                    title = title,
                    oldValue = if (existing.isPrivate) "Məxfi" else "Açıq",
                    newValue = if (snapshot.isPrivate) "Məxfi" else "Açıq",
                    timestamp = now
                )
            )
            notificationDao.insertNotification(
                NotificationEntity(
                    targetId = username,
                    username = username,
                    eventType = "privacy_change",
                    title = title,
                    message = desc,
                    timestamp = now
                )
            )
            onAlertNotification(title, desc)
        }

        // 3. Check post count change
        if (existing != null && existing.mediaCount != snapshot.mediaCount) {
            val title = "@$username paylaşım sayı dəyişdi"
            val desc = "${existing.mediaCount} -> ${snapshot.mediaCount} paylaşım"
            changeLogDao.insertLog(
                ChangeLogEntity(
                    targetUsername = username,
                    eventType = "post_count_change",
                    title = title,
                    oldValue = existing.mediaCount.toString(),
                    newValue = snapshot.mediaCount.toString(),
                    timestamp = now
                )
            )
            notificationDao.insertNotification(
                NotificationEntity(
                    targetId = username,
                    username = username,
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
            val title = "@$username bioqrafiyası yeniləndi"
            val desc = snapshot.biography.take(80)
            changeLogDao.insertLog(
                ChangeLogEntity(
                    targetUsername = username,
                    eventType = "bio_change",
                    title = title,
                    oldValue = existing.biography,
                    newValue = snapshot.biography,
                    timestamp = now
                )
            )
        }

        // Create or update TargetEntity
        val updatedTarget = TargetEntity(
            username = username,
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

    private fun downloadAndArchiveAvatar(username: String, url: String): String? {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val fileName = "avatar_${timestamp}.jpg"

        // Primary: Public external directory if accessible, otherwise app external files dir
        val baseDir = File(Environment.getExternalStorageDirectory(), "InstaLogger/archives/$username/profile")
        val appDir = File(context.getExternalFilesDir("archives"), "$username/profile")

        val targetDir = if (baseDir.exists() || baseDir.mkdirs()) baseDir else appDir
        targetDir.mkdirs()

        val destFile = File(targetDir, fileName)
        return try {
            val size = instagramEngine.downloadImage(url, destFile)
            if (size > 0) destFile.absolutePath else null
        } catch (e: Exception) {
            Log.e("ProfileInspector", "Failed to download avatar: ${e.message}")
            null
        }
    }
}
