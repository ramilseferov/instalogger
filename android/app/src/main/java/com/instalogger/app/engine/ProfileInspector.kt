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
import java.security.MessageDigest
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
            } else if (localAvatarPath == null || avatarUrl != existing?.avatarUrl) {
                val downloadedPath = downloadAndArchiveAvatar(cleanUser, avatarUrl)
                if (downloadedPath != null) {
                    val file = File(downloadedPath)
                    val newHash = computeFileSha256(file)

                    // SECOND-PASS HASH CHECK:
                    // Compare cryptographic SHA-256 byte digest with existing avatar
                    if (currentAvatarHash != null && newHash != null && currentAvatarHash == newHash) {
                        // Byte content is 100% IDENTICAL (Instagram CDN token rotation false-positive)
                        // Update reference quietly without creating duplicate log or alert
                        localAvatarPath = downloadedPath
                    } else {
                        // Hashes genuinely differ -> Genuine avatar change confirmed!
                        val oldPath = localAvatarPath
                        localAvatarPath = downloadedPath
                        currentAvatarHash = newHash

                        avatarHistoryDao.insertAvatar(
                            AvatarHistoryEntity(
                                username = cleanUser,
                                localPath = downloadedPath,
                                archivedAt = now,
                                fileSize = file.length(),
                                fileHash = newHash
                            )
                        )

                        if (existing != null && oldPath != null) {
                            val title = "@$cleanUser profile picture updated"
                            val desc = "New profile picture archived successfully [SHA-256 verified]"
                            changeLogDao.insertLog(
                                ChangeLogEntity(
                                    targetUsername = cleanUser,
                                    eventType = "avatar_change",
                                    title = title,
                                    oldValue = existing.avatarHash ?: oldPath,
                                    newValue = newHash ?: downloadedPath,
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
        }

        // Also import existing archives if available on disk
        importLocalArchives(cleanUser, snapshot.instagramId)

        // 2. Check privacy change
        val wasPrivateToPublic = (existing != null && existing.isPrivate && !snapshot.isPrivate)
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

        // 5. Check followers count change
        if (existing != null && existing.followersCount > 0 && snapshot.followersCount > 0 && existing.followersCount != snapshot.followersCount) {
            val diff = snapshot.followersCount - existing.followersCount
            val sign = if (diff > 0) "+$diff" else "$diff"
            val title = "@$cleanUser followers count changed ($sign)"
            val desc = "${existing.followersCount} -> ${snapshot.followersCount} followers ($sign)"
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

        // 6. Check following count change
        if (existing != null && existing.followingCount > 0 && snapshot.followingCount > 0 && existing.followingCount != snapshot.followingCount) {
            val diff = snapshot.followingCount - existing.followingCount
            val sign = if (diff > 0) "+$diff" else "$diff"
            val title = "@$cleanUser following count changed ($sign)"
            val desc = "${existing.followingCount} -> ${snapshot.followingCount} following ($sign)"
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

        // 7. Check and archive followers & following lists
        val targetId = snapshot.instagramId ?: existing?.instagramId
        if (!targetId.isNullOrBlank()) {
            if (!cookie.isNullOrBlank() || wasPrivateToPublic || !snapshot.isPrivate) {
                inspectFollowersList(cleanUser, targetId, cookie, now)
                inspectFollowingList(cleanUser, targetId, cookie, now)
            }
        }

        // 8. Auto archive highlights and posts if account transitioned to Public or is Public
        if (!snapshot.isPrivate && !targetId.isNullOrBlank()) {
            archiveHighlightsAndPosts(cleanUser, targetId, cookie, wasPrivateToPublic)
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

        // 9. Record follower & following count point for trend diagram
        val histFollowers = if (snapshot.followersCount > 0) snapshot.followersCount else (freshInDb?.followersCount ?: existing?.followersCount ?: 0L)
        val histFollowing = if (snapshot.followingCount > 0) snapshot.followingCount else (freshInDb?.followingCount ?: existing?.followingCount ?: 0L)
        val histMedia = if (snapshot.mediaCount > 0) snapshot.mediaCount else (freshInDb?.mediaCount ?: existing?.mediaCount ?: 0L)
        if (histFollowers > 0 || histFollowing > 0) {
            database.followerHistoryDao().insertHistory(
                FollowerHistoryEntity(
                    targetUsername = cleanUser,
                    followersCount = histFollowers,
                    followingCount = histFollowing,
                    mediaCount = histMedia,
                    timestamp = now
                )
            )
        }

        // Holistic second-layer state hash calculation
        val newProfileHash = computeProfileStateHash(
            username = cleanUser,
            fullName = snapshot.fullName,
            biography = snapshot.biography,
            isPrivate = snapshot.isPrivate,
            followersCount = snapshot.followersCount,
            followingCount = snapshot.followingCount,
            mediaCount = snapshot.mediaCount,
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
            mediaCount = if (snapshot.mediaCount > 0) snapshot.mediaCount else (freshInDb?.mediaCount ?: existing?.mediaCount ?: 0L),
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

        targetDao.insertTarget(updatedTarget)
        Result.success(updatedTarget)
    }

    suspend fun updateDaemonTurbo(targetIdentifier: String, isTurbo: Boolean) = withContext(Dispatchers.IO) {
        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:3000/api/targets/$targetIdentifier/turbo")
                .post("{\"turbo\": $isTurbo}".toRequestBody("application/json".toMediaType()))
                .build()
            fastClient.newCall(request).execute().close()
        } catch (_: Exception) {
            // Daemon not reachable or endpoint failed
        }
    }

    private fun fetchFromLocalDaemon(username: String): InstagramProfileSnapshot? {
        try {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:3000/api/targets")
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

    private fun computeFileSha256(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
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
        targetId: String,
        cookie: String?,
        wasPrivateToPublic: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch highlights list
            val highlights = instagramEngine.fetchUserHighlights(targetId, cookie)
            if (highlights.isNotEmpty()) {
                val highlightDao = database.highlightDao()
                val highlightEntities = mutableListOf<HighlightEntity>()
                val mediaEntities = mutableListOf<HighlightMediaEntity>()

                val archiveDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$username/highlights")
                archiveDir.mkdirs()
                val internalDir = File(context.filesDir, "highlights/$username")
                internalDir.mkdirs()

                for (hl in highlights) {
                    var localCover: String? = null
                    if (!hl.coverUrl.isNullOrBlank()) {
                        val coverFile = File(archiveDir, "${hl.id.replace(":", "_")}_cover.jpg")
                        if (!coverFile.exists() || coverFile.length() == 0L) {
                            instagramEngine.downloadMedia(hl.coverUrl, coverFile)
                        }
                        if (coverFile.exists() && coverFile.length() > 0L) {
                            val internalCover = File(internalDir, coverFile.name)
                            try { coverFile.copyTo(internalCover, overwrite = true) } catch (_: Exception) {}
                            localCover = internalCover.absolutePath
                        }
                    }

                    highlightEntities.add(
                        HighlightEntity(
                            id = hl.id,
                            username = username,
                            title = hl.title,
                            coverUrl = hl.coverUrl ?: "",
                            localCoverPath = localCover,
                            mediaCount = hl.mediaCount,
                            archivedAt = System.currentTimeMillis()
                        )
                    )

                    // Fetch stories for this highlight
                    val stories = instagramEngine.fetchHighlightStories(hl.id, cookie)
                    for (story in stories) {
                        val ext = if (story.mediaType == "video") "mp4" else "jpg"
                        val fileName = "${hl.id.replace(":", "_")}_${story.id}.$ext"
                        val mediaFile = File(archiveDir, fileName)
                        val internalMedia = File(internalDir, fileName)

                        if (!mediaFile.exists() || mediaFile.length() == 0L) {
                            instagramEngine.downloadMedia(story.mediaUrl, mediaFile)
                        }

                        val localPath = if (mediaFile.exists() && mediaFile.length() > 0L) {
                            try { mediaFile.copyTo(internalMedia, overwrite = true) } catch (_: Exception) {}
                            internalMedia.absolutePath
                        } else {
                            story.mediaUrl
                        }

                        mediaEntities.add(
                            HighlightMediaEntity(
                                id = story.id,
                                highlightId = hl.id,
                                username = username,
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

            // 2. Fetch and archive recent posts
            val posts = instagramEngine.fetchRecentPosts(targetId, username, cookie, limit = 24)
            if (posts.isNotEmpty()) {
                val postDao = database.postDao()
                val postEntities = mutableListOf<PostEntity>()

                val postDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$username/posts")
                postDir.mkdirs()
                val internalPostDir = File(context.filesDir, "posts/$username")
                internalPostDir.mkdirs()

                for (p in posts) {
                    var localPath: String? = null
                    val ext = if (p.mediaType == "video") "mp4" else "jpg"
                    val file = File(postDir, "${p.shortcode}.$ext")
                    val internalFile = File(internalPostDir, "${p.shortcode}.$ext")

                    val downloadUrl = if (p.mediaType == "video" && !p.videoUrl.isNullOrBlank()) p.videoUrl else p.displayUrl
                    if (!downloadUrl.isNullOrBlank()) {
                        if (!file.exists() || file.length() == 0L) {
                            instagramEngine.downloadMedia(downloadUrl, file)
                        }
                        if (file.exists() && file.length() > 0L) {
                            try { file.copyTo(internalFile, overwrite = true) } catch (_: Exception) {}
                            localPath = internalFile.absolutePath
                        }
                    }

                    postEntities.add(
                        PostEntity(
                            id = p.shortcode,
                            username = username,
                            caption = p.caption.ifBlank { null },
                            displayUrl = p.displayUrl,
                            localPath = localPath,
                            isVideo = (p.mediaType == "video"),
                            likeCount = p.likeCount,
                            commentCount = p.commentCount,
                            postedAt = p.timestamp
                        )
                    )
                }

                if (postEntities.isNotEmpty()) {
                    postDao.insertPosts(postEntities)
                }
            }

            // If account was private and became public, dispatch a special success alert
            if (wasPrivateToPublic) {
                val now = System.currentTimeMillis()
                val title = "@$username is now PUBLIC! Highlights & Posts archived."
                val message = "Archived ${highlights.size} highlight reels and ${posts.size} posts in high speed."
                dispatchAlert(
                    targetUsername = username,
                    eventType = "privacy_change",
                    title = title,
                    message = message,
                    timestamp = now
                )
            }
        } catch (e: Exception) {
            Log.w("ProfileInspector", "Failed to archive highlights and posts for @$username: ${e.message}")
        }
    }

    suspend fun inspectHighlightsAndPosts(username: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().trimStart('@').lowercase()
        val target = database.targetDao().getTarget(cleanUser) ?: return@withContext false
        val cookie = sessionManager.getCookieString()
        val targetId = target.instagramId
        if (targetId.isNullOrBlank()) return@withContext false
        archiveHighlightsAndPosts(cleanUser, targetId, cookie, wasPrivateToPublic = false)
        true
    }
}
