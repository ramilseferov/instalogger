package com.instalogger.app.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.sqlite.db.SupportSQLiteDatabase
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.instalogger.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

enum class StorageCategoryType(
    val id: String,
    val colorHex: Long,
    val selectedByDefault: Boolean,
    val isProtectedCategory: Boolean,
) {
    IMAGE_CACHE(
        id = "image_cache",
        colorHex = 0xFF34C759, // Vibrant Green
        selectedByDefault = true,
        isProtectedCategory = false,
    ),
    TEMP_STAGING(
        id = "temp_staging",
        colorHex = 0xFF14B8A6, // Teal
        selectedByDefault = true,
        isProtectedCategory = false,
    ),
    WEBVIEW_CACHE(
        id = "webview_cache",
        colorHex = 0xFFEAB308, // Golden Amber
        selectedByDefault = true,
        isProtectedCategory = false,
    ),
    LOGS_AND_ALERTS(
        id = "logs_and_alerts",
        colorHex = 0xFFFF9500, // Vibrant Orange
        selectedByDefault = true,
        isProtectedCategory = false,
    ),
    VAULT_POSTS(
        id = "vault_posts",
        colorHex = 0xFF9B59B6, // Vibrant Purple (Protected)
        selectedByDefault = false,
        isProtectedCategory = true,
    ),
    VAULT_STORIES_HIGHLIGHTS(
        id = "vault_stories_highlights",
        colorHex = 0xFF32ADE6, // Sky Blue (Protected)
        selectedByDefault = false,
        isProtectedCategory = true,
    ),
    VAULT_AVATARS(
        id = "vault_avatars",
        colorHex = 0xFFF43F5E, // Rose (Protected)
        selectedByDefault = false,
        isProtectedCategory = true,
    ),
    COMMENTS_AND_ARCHIVES(
        id = "comments_and_archives",
        colorHex = 0xFFEF4444, // Crimson (Protected)
        selectedByDefault = false,
        isProtectedCategory = true,
    ),
}

data class StorageCategoryMetric(
    val type: StorageCategoryType,
    val sizeBytes: Long,
    val itemCount: Int,
)

data class TargetStorageMetric(
    val username: String,
    val postsBytes: Long,
    val storiesHighlightsBytes: Long,
    val avatarsBytes: Long,
    val archivesBytes: Long,
    val totalFiles: Int,
) {
    val totalBytes: Long
        get() = postsBytes + storiesHighlightsBytes + avatarsBytes + archivesBytes
}

data class DeviceStorageSnapshot(
    val totalDeviceBytes: Long,
    val freeDeviceBytes: Long,
    val usedDeviceBytes: Long,
    val categories: List<StorageCategoryMetric>,
    val targetBreakdown: List<TargetStorageMetric>,
    val webViewCookiesActive: Boolean,
    val webViewCookiesBytes: Long,
) {
    val totalAppCacheBytes: Long
        get() = categories.sumOf { it.sizeBytes }

    val appShareOfDevicePercent: Float
        get() = if (totalDeviceBytes > 0L) {
            ((totalAppCacheBytes.toDouble() / totalDeviceBytes.toDouble()) * 100.0).toFloat()
        } else {
            0f
        }
}

object StorageCacheManager {

    private const val DB_NAME = "instalogger_standalone.db"
    private const val SYSTEM_VAULT_DIR = "_system"

    @OptIn(ExperimentalCoilApi::class)
    suspend fun analyzeStorage(context: Context): DeviceStorageSnapshot = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext

        // 1. Device storage via StatFs
        val (totalDevice, freeDevice) = runCatching {
            val stat = StatFs(appCtx.filesDir.absolutePath)
            Pair(stat.blockCountLong * stat.blockSizeLong, stat.availableBlocksLong * stat.blockSizeLong)
        }.getOrElse {
            runCatching {
                val stat = StatFs(Environment.getDataDirectory().absolutePath)
                Pair(stat.blockCountLong * stat.blockSizeLong, stat.availableBlocksLong * stat.blockSizeLong)
            }.getOrDefault(Pair(0L, 0L))
        }

        // 2. Coil Image & Thumbnail Cache
        val coilDiskDir = File(appCtx.cacheDir, "image_cache")
        val coilDiskBytes = calculateDirSize(coilDiskDir)
        val coilDiskCount = countFilesInDir(coilDiskDir)
        val coilMemBytes = runCatching {
            Coil.imageLoader(appCtx).memoryCache?.size?.toLong() ?: 0L
        }.getOrDefault(0L)
        val imageCacheBytes = coilDiskBytes + coilMemBytes

        // 3. Temp, Playback & yt-dlp Staging Cache
        val excludedCacheNames = setOf("image_cache", "WebView", "org.chromium.android_webview")
        val tempCacheEntries = appCtx.cacheDir.listFiles()?.filter { it.name !in excludedCacheNames }.orEmpty()
        val tempStagingBytes = tempCacheEntries.sumOf { calculateDirSize(it) }
        val tempStagingCount = tempCacheEntries.sumOf { countFilesInDir(it) }

        // 4. WebView Disk Cache & Cookies measurement
        val dataRoot = appCtx.applicationInfo.dataDir?.let { File(it) }
        val appWebViewDir = dataRoot?.let { File(it, "app_webview") }
        val webViewCacheDirs = listOfNotNull(
            File(appCtx.cacheDir, "WebView"),
            File(appCtx.cacheDir, "org.chromium.android_webview"),
            appWebViewDir?.let { File(it, "Default/Cache") },
            appWebViewDir?.let { File(it, "Default/Code Cache") },
            appWebViewDir?.let { File(it, "Default/GPUCache") },
            appWebViewDir?.let { File(it, "Default/Service Worker") },
        )
        val webViewCookieTargets = listOfNotNull(
            appWebViewDir?.let { File(it, "Default/Cookies") },
            appWebViewDir?.let { File(it, "Default/Cookies-journal") },
            appWebViewDir?.let { File(it, "Default/Local Storage") },
            appWebViewDir?.let { File(it, "Default/Session Storage") },
            appWebViewDir?.let { File(it, "Default/IndexedDB") },
            appWebViewDir?.let { File(it, "Cookies") },
        )
        val webViewCacheBytes = webViewCacheDirs.sumOf { calculateDirSize(it) }
        val webViewCacheCount = webViewCacheDirs.sumOf { countFilesInDir(it) }
        val webViewCookiesBytes = webViewCookieTargets.sumOf { calculateDirSize(it) }
        val hasCookies = runCatching { CookieManager.getInstance().hasCookies() }.getOrDefault(false)

        // 5. Room SQLite table measurements
        val db = AppDatabase.getInstance(appCtx).openHelper.readableDatabase

        val changeLogsStats = measureTableBytes(db, "change_logs", perRowOverheadBytes = 128L)
        val notifsStats = measureTableBytes(db, "notifications", perRowOverheadBytes = 128L)
        val healthStats = measureTableBytes(db, "provider_health", perRowOverheadBytes = 96L)
        val mediaAssetsStats = measureTableBytes(db, "media_assets", perRowOverheadBytes = 144L)
        val postsStats = measureTableBytes(db, "posts", perRowOverheadBytes = 160L)
        val highlightMediaStats = measureTableBytes(db, "highlight_media", perRowOverheadBytes = 128L)
        val avatarHistoryStats = measureTableBytes(db, "avatar_history", perRowOverheadBytes = 112L)
        val commentsStats = measureTableBytes(db, "post_comments", perRowOverheadBytes = 160L)
        val followerHistoryStats = measureTableBytes(db, "follower_history", perRowOverheadBytes = 96L)
        val followerAuditStats = measureTableBytes(db, "follower_audits", perRowOverheadBytes = 128L)

        // 6. Encrypted Developer Logs + SQLite WAL/SHM overhead
        val vaultRoot = File(appCtx.filesDir, "vault")
        val sysLogsDir = File(vaultRoot, "$SYSTEM_VAULT_DIR/logs")
        val sysLogsDiskBytes = calculateDirSize(sysLogsDir)
        val sysLogsMemBytes = AppLogger.logs.value.sumOf { (it.message.length + it.tag.length + 32).toLong() }
        val walFile = appCtx.getDatabasePath("$DB_NAME-wal")
        val shmFile = appCtx.getDatabasePath("$DB_NAME-shm")
        val walOverheadBytes = (if (walFile.exists()) walFile.length() else 0L) +
            (if (shmFile.exists()) shmFile.length() else 0L)

        val logsAndAlertsBytes = changeLogsStats.first + notifsStats.first + healthStats.first +
            sysLogsDiskBytes + sysLogsMemBytes + walOverheadBytes
        val logsAndAlertsCount = changeLogsStats.second + notifsStats.second + AppLogger.logs.value.size

        // 7. Encrypted Vault Media & Per-Target Breakdown
        val internalArchivesDir = File(appCtx.filesDir, "archives")
        val externalArchivesDir = runCatching { ArchiveDirectoryManager.getArchiveDirectory(appCtx) }.getOrNull()
        val legacyAvatarsDir = File(appCtx.filesDir, "avatars")

        val scanResult = scanVaultAndArchives(
            vaultRoot = vaultRoot,
            legacyAvatarsDir = legacyAvatarsDir,
            internalArchivesDir = internalArchivesDir,
            externalArchivesDir = externalArchivesDir,
        )

        val vaultPostsBytes = scanResult.postsBytes + mediaAssetsStats.first
        val vaultPostsCount = scanResult.postsFiles.coerceAtLeast(
            if (scanResult.postsFiles > 0) scanResult.postsFiles else mediaAssetsStats.second
        )

        val vaultStoriesBytes = scanResult.storiesHighlightsBytes + highlightMediaStats.first
        val vaultStoriesCount = scanResult.storiesHighlightsFiles.coerceAtLeast(highlightMediaStats.second)

        val vaultAvatarsBytes = scanResult.avatarsBytes + avatarHistoryStats.first
        val vaultAvatarsCount = scanResult.avatarsFiles.coerceAtLeast(avatarHistoryStats.second)

        val commentsAndArchivesBytes = scanResult.archivesBytes +
            commentsStats.first + followerHistoryStats.first + followerAuditStats.first
        val commentsAndArchivesCount = scanResult.archivesFiles +
            commentsStats.second + followerHistoryStats.second + followerAuditStats.second

        val metrics = listOf(
            StorageCategoryMetric(
                type = StorageCategoryType.IMAGE_CACHE,
                sizeBytes = imageCacheBytes,
                itemCount = coilDiskCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.TEMP_STAGING,
                sizeBytes = tempStagingBytes,
                itemCount = tempStagingCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.WEBVIEW_CACHE,
                sizeBytes = webViewCacheBytes,
                itemCount = webViewCacheCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.LOGS_AND_ALERTS,
                sizeBytes = logsAndAlertsBytes,
                itemCount = logsAndAlertsCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.VAULT_POSTS,
                sizeBytes = vaultPostsBytes,
                itemCount = vaultPostsCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.VAULT_STORIES_HIGHLIGHTS,
                sizeBytes = vaultStoriesBytes,
                itemCount = vaultStoriesCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.VAULT_AVATARS,
                sizeBytes = vaultAvatarsBytes,
                itemCount = vaultAvatarsCount,
            ),
            StorageCategoryMetric(
                type = StorageCategoryType.COMMENTS_AND_ARCHIVES,
                sizeBytes = commentsAndArchivesBytes,
                itemCount = commentsAndArchivesCount,
            ),
        )

        DeviceStorageSnapshot(
            totalDeviceBytes = totalDevice,
            freeDeviceBytes = freeDevice,
            usedDeviceBytes = (totalDevice - freeDevice).coerceAtLeast(0L),
            categories = metrics,
            targetBreakdown = scanResult.perTarget,
            webViewCookiesActive = hasCookies || webViewCookiesBytes > 0L,
            webViewCookiesBytes = webViewCookiesBytes,
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearSelectedCategories(
        context: Context,
        selectedTypes: Set<StorageCategoryType>,
    ): Pair<Long, DeviceStorageSnapshot> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val beforeSnapshot = analyzeStorage(appCtx)
        val selectedBeforeBytes = beforeSnapshot.categories
            .filter { it.type in selectedTypes }
            .sumOf { it.sizeBytes }

        val roomDb = AppDatabase.getInstance(appCtx)
        val writableDb = roomDb.openHelper.writableDatabase
        val vaultRoot = File(appCtx.filesDir, "vault")

        // 1. Coil Image & Thumbnail Cache
        if (StorageCategoryType.IMAGE_CACHE in selectedTypes) {
            runCatching {
                Coil.imageLoader(appCtx).memoryCache?.clear()
                Coil.imageLoader(appCtx).diskCache?.clear()
            }
            deleteFileOrDirectory(File(appCtx.cacheDir, "image_cache"))
        }

        // 2. Temp, Playback & yt-dlp Staging Cache
        if (StorageCategoryType.TEMP_STAGING in selectedTypes) {
            val excludedCacheNames = setOf("image_cache", "WebView", "org.chromium.android_webview")
            appCtx.cacheDir.listFiles()
                ?.filter { it.name !in excludedCacheNames }
                ?.forEach { deleteFileOrDirectory(it) }
        }

        // 3. WebView Disk Cache (preserves login cookies unless user invokes cookie purge)
        if (StorageCategoryType.WEBVIEW_CACHE in selectedTypes) {
            val dataRoot = appCtx.applicationInfo.dataDir?.let { File(it) }
            val appWebViewDir = dataRoot?.let { File(it, "app_webview") }
            listOfNotNull(
                File(appCtx.cacheDir, "WebView"),
                File(appCtx.cacheDir, "org.chromium.android_webview"),
                appWebViewDir?.let { File(it, "Default/Cache") },
                appWebViewDir?.let { File(it, "Default/Code Cache") },
                appWebViewDir?.let { File(it, "Default/GPUCache") },
                appWebViewDir?.let { File(it, "Default/Service Worker") },
            ).forEach { deleteFileOrDirectory(it) }
        }

        // 4. Developer Logs, Notifications & Change Logs
        if (StorageCategoryType.LOGS_AND_ALERTS in selectedTypes) {
            runCatching {
                writableDb.execSQL("DELETE FROM change_logs")
                writableDb.execSQL("DELETE FROM notifications")
                writableDb.execSQL("DELETE FROM provider_health")
            }
            AppLogger.clear()
            deleteFileOrDirectory(File(vaultRoot, "$SYSTEM_VAULT_DIR/logs"))
        }

        // 5. Protected: Encrypted Vault Posts & Videos (preserves post metadata, marks pending)
        if (StorageCategoryType.VAULT_POSTS in selectedTypes) {
            vaultRoot.listFiles()?.forEach { userDir ->
                if (userDir.isDirectory && userDir.name != SYSTEM_VAULT_DIR) {
                    deleteDirectoryContents(File(userDir, "posts"), preserveNoMedia = true)
                }
            }
            runCatching {
                writableDb.execSQL("DELETE FROM media_assets")
                writableDb.execSQL("UPDATE posts SET localPath = NULL, localThumbPath = NULL, downloadStatus = 'pending'")
            }
        }

        // 6. Protected: Stories & Highlights Vault Media
        if (StorageCategoryType.VAULT_STORIES_HIGHLIGHTS in selectedTypes) {
            vaultRoot.listFiles()?.forEach { userDir ->
                if (userDir.isDirectory && userDir.name != SYSTEM_VAULT_DIR) {
                    deleteDirectoryContents(File(userDir, "highlights"), preserveNoMedia = true)
                    deleteDirectoryContents(File(userDir, "stories"), preserveNoMedia = true)
                }
            }
            runCatching {
                writableDb.execSQL("DELETE FROM highlight_media")
                writableDb.execSQL("UPDATE highlights SET localCoverPath = NULL")
            }
        }

        // 7. Protected: Profile Photos & Avatar History
        if (StorageCategoryType.VAULT_AVATARS in selectedTypes) {
            vaultRoot.listFiles()?.forEach { userDir ->
                if (userDir.isDirectory && userDir.name != SYSTEM_VAULT_DIR) {
                    deleteDirectoryContents(File(userDir, "profile"), preserveNoMedia = true)
                }
            }
            deleteDirectoryContents(File(appCtx.filesDir, "avatars"), preserveNoMedia = true)
            runCatching {
                writableDb.execSQL("DELETE FROM avatar_history")
                writableDb.execSQL("UPDATE targets SET localAvatarPath = NULL")
            }
        }

        // 8. Protected: Comments, Follower Analytics & Exported JSON Archives
        if (StorageCategoryType.COMMENTS_AND_ARCHIVES in selectedTypes) {
            runCatching {
                writableDb.execSQL("DELETE FROM post_comments")
                writableDb.execSQL("DELETE FROM follower_history")
                writableDb.execSQL("DELETE FROM follower_audits")
            }
            val internalArchivesDir = File(appCtx.filesDir, "archives")
            deleteDirectoryContents(internalArchivesDir, preserveNoMedia = true)
            runCatching {
                val externalDir = ArchiveDirectoryManager.getArchiveDirectory(appCtx)
                if (externalDir.canonicalPath != internalArchivesDir.canonicalPath) {
                    deleteDirectoryContents(externalDir, preserveNoMedia = true)
                }
            }
        }

        // Compact SQLite WAL & reclaim disk pages immediately
        compactDatabase(writableDb)

        val afterSnapshot = analyzeStorage(appCtx)
        val selectedAfterBytes = afterSnapshot.categories
            .filter { it.type in selectedTypes }
            .sumOf { it.sizeBytes }
        val freedBytes = (selectedBeforeBytes - selectedAfterBytes).coerceAtLeast(0L)

        AppLogger.i(
            "StorageManager",
            "Cleared ${selectedTypes.size} storage categories: ${formatBytes(freedBytes)} freed"
        )

        Pair(freedBytes, afterSnapshot)
    }

    suspend fun clearTargetMedia(
        context: Context,
        username: String,
    ): Pair<Long, DeviceStorageSnapshot> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val cleanUser = username.trim().removePrefix("@")
        val beforeSnapshot = analyzeStorage(appCtx)
        val targetBefore = beforeSnapshot.targetBreakdown
            .firstOrNull { it.username.equals(cleanUser, ignoreCase = true) }
            ?.totalBytes ?: 0L

        val vaultRoot = File(appCtx.filesDir, "vault")
        deleteFileOrDirectory(File(vaultRoot, cleanUser))
        deleteFileOrDirectory(File(appCtx.filesDir, "avatars/$cleanUser"))
        val internalUserArchive = File(appCtx.filesDir, "archives/$cleanUser")
        deleteFileOrDirectory(internalUserArchive)
        runCatching {
            val externalDir = ArchiveDirectoryManager.getArchiveDirectory(appCtx)
            val extUserArchive = File(externalDir, cleanUser)
            if (extUserArchive.canonicalPath != internalUserArchive.canonicalPath) {
                deleteFileOrDirectory(extUserArchive)
            }
        }

        val writableDb = AppDatabase.getInstance(appCtx).openHelper.writableDatabase
        runCatching {
            writableDb.execSQL("DELETE FROM media_assets WHERE username = ?", arrayOf(cleanUser))
            writableDb.execSQL(
                "UPDATE posts SET localPath = NULL, localThumbPath = NULL, downloadStatus = 'pending' WHERE username = ?",
                arrayOf(cleanUser)
            )
            writableDb.execSQL("DELETE FROM highlight_media WHERE username = ?", arrayOf(cleanUser))
            writableDb.execSQL("UPDATE highlights SET localCoverPath = NULL WHERE username = ?", arrayOf(cleanUser))
            writableDb.execSQL("DELETE FROM avatar_history WHERE username = ?", arrayOf(cleanUser))
            writableDb.execSQL("UPDATE targets SET localAvatarPath = NULL WHERE username = ?", arrayOf(cleanUser))
        }
        compactDatabase(writableDb)

        val afterSnapshot = analyzeStorage(appCtx)
        val targetAfter = afterSnapshot.targetBreakdown
            .firstOrNull { it.username.equals(cleanUser, ignoreCase = true) }
            ?.totalBytes ?: 0L
        val freed = (targetBefore - targetAfter).coerceAtLeast(0L)

        AppLogger.i("StorageManager", "Cleared local media for @$cleanUser (${formatBytes(freed)} freed)")
        Pair(freed, afterSnapshot)
    }

    // Dedicated 1-tap WebView Cookies & DOM Storage purge.
    suspend fun clearOnlyWebViewCookies(context: Context): Pair<Long, DeviceStorageSnapshot> {
        val appCtx = context.applicationContext
        val dataRoot = appCtx.applicationInfo.dataDir?.let { File(it) }
        val appWebViewDir = dataRoot?.let { File(it, "app_webview") }
        val cookieTargets = listOfNotNull(
            appWebViewDir?.let { File(it, "Default/Cookies") },
            appWebViewDir?.let { File(it, "Default/Cookies-journal") },
            appWebViewDir?.let { File(it, "Default/Local Storage") },
            appWebViewDir?.let { File(it, "Default/Session Storage") },
            appWebViewDir?.let { File(it, "Default/IndexedDB") },
            appWebViewDir?.let { File(it, "Cookies") },
        )
        val beforeBytes = withContext(Dispatchers.IO) {
            cookieTargets.sumOf { calculateDirSize(it) }
        }

        // Android WebView CookieManager & WebStorage must be invoked on the main thread
        withContext(Dispatchers.Main) {
            runCatching {
                suspendCancellableCoroutine<Unit> { cont ->
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies {
                        runCatching { cm.flush() }
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }

        val freedAndSnapshot = withContext(Dispatchers.IO) {
            var deletedBytes = 0L
            cookieTargets.forEach { deletedBytes += deleteFileOrDirectory(it) }
            val updated = analyzeStorage(appCtx)
            val freed = maxOf(deletedBytes, (beforeBytes - updated.webViewCookiesBytes).coerceAtLeast(0L))
            AppLogger.i("StorageManager", "Cleared WebView cookies & DOM storage (${formatBytes(freed)})")
            Pair(freed, updated)
        }
        return freedAndSnapshot
    }

    suspend fun clearAllAppData(context: Context): Pair<Long, DeviceStorageSnapshot> {
        val appCtx = context.applicationContext
        val beforeTotal = analyzeStorage(appCtx).totalAppCacheBytes
        clearSelectedCategories(appCtx, StorageCategoryType.entries.toSet())
        clearOnlyWebViewCookies(appCtx)
        val afterSnapshot = withContext(Dispatchers.IO) {
            val writableDb = AppDatabase.getInstance(appCtx).openHelper.writableDatabase
            runCatching {
                writableDb.execSQL("DELETE FROM targets")
                writableDb.execSQL("DELETE FROM posts")
                writableDb.execSQL("DELETE FROM highlights")
                writableDb.execSQL("DELETE FROM highlight_media")
            }
            compactDatabase(writableDb)
            analyzeStorage(appCtx)
        }
        val freed = (beforeTotal - afterSnapshot.totalAppCacheBytes).coerceAtLeast(0L)
        AppLogger.i("StorageManager", "Full app data reset completed (${formatBytes(freed)} freed)")
        return Pair(freed, afterSnapshot)
    }

    internal data class VaultArchiveScanResult(
        val postsBytes: Long,
        val postsFiles: Int,
        val storiesHighlightsBytes: Long,
        val storiesHighlightsFiles: Int,
        val avatarsBytes: Long,
        val avatarsFiles: Int,
        val archivesBytes: Long,
        val archivesFiles: Int,
        val perTarget: List<TargetStorageMetric>,
    )

    internal fun scanVaultAndArchives(
        vaultRoot: File?,
        legacyAvatarsDir: File?,
        internalArchivesDir: File?,
        externalArchivesDir: File?,
    ): VaultArchiveScanResult {
        var totalPostsBytes = 0L
        var totalPostsFiles = 0
        var totalStoriesBytes = 0L
        var totalStoriesFiles = 0
        var totalAvatarsBytes = 0L
        var totalAvatarsFiles = 0
        var totalArchivesBytes = 0L
        var totalArchivesFiles = 0

        data class MutableTargetAcc(
            var postsBytes: Long = 0L,
            var storiesBytes: Long = 0L,
            var avatarsBytes: Long = 0L,
            var archivesBytes: Long = 0L,
            var files: Int = 0,
        )

        val targetAcc = linkedMapOf<String, MutableTargetAcc>()
        fun acc(user: String): MutableTargetAcc = targetAcc.getOrPut(user) { MutableTargetAcc() }

        // 1. Encrypted Vault root: vault/<username>/{posts, highlights, stories, profile}
        if (vaultRoot != null && vaultRoot.exists() && vaultRoot.isDirectory) {
            vaultRoot.listFiles()?.forEach { userDir ->
                if (!userDir.isDirectory || userDir.name == SYSTEM_VAULT_DIR) return@forEach
                val username = userDir.name
                val t = acc(username)

                userDir.listFiles()?.forEach { sub ->
                    if (sub.name == ".nomedia") return@forEach
                    val bytes = calculateDirSize(sub)
                    val files = countFilesInDir(sub)
                    when (sub.name.lowercase(Locale.US)) {
                        "posts" -> {
                            totalPostsBytes += bytes
                            totalPostsFiles += files
                            t.postsBytes += bytes
                            t.files += files
                        }
                        "highlights", "stories" -> {
                            totalStoriesBytes += bytes
                            totalStoriesFiles += files
                            t.storiesBytes += bytes
                            t.files += files
                        }
                        "profile" -> {
                            totalAvatarsBytes += bytes
                            totalAvatarsFiles += files
                            t.avatarsBytes += bytes
                            t.files += files
                        }
                        "archives" -> {
                            totalArchivesBytes += bytes
                            totalArchivesFiles += files
                            t.archivesBytes += bytes
                            t.files += files
                        }
                        else -> {
                            totalPostsBytes += bytes
                            totalPostsFiles += files
                            t.postsBytes += bytes
                            t.files += files
                        }
                    }
                }
            }
        }

        // 2. Legacy avatars directory: files/avatars/<username>
        if (legacyAvatarsDir != null && legacyAvatarsDir.exists() && legacyAvatarsDir.isDirectory) {
            legacyAvatarsDir.listFiles()?.forEach { child ->
                if (child.name == ".nomedia") return@forEach
                val bytes = calculateDirSize(child)
                val files = countFilesInDir(child)
                totalAvatarsBytes += bytes
                totalAvatarsFiles += files
                if (child.isDirectory) {
                    val t = acc(child.name)
                    t.avatarsBytes += bytes
                    t.files += files
                }
            }
        }

        // 3. Archives directories (deduplicated by canonical path)
        val archiveDirs = buildList {
            if (internalArchivesDir != null && internalArchivesDir.exists()) add(internalArchivesDir)
            if (externalArchivesDir != null && externalArchivesDir.exists()) {
                val extCanon = runCatching { externalArchivesDir.canonicalPath }.getOrNull()
                val intCanon = internalArchivesDir?.let { runCatching { it.canonicalPath }.getOrNull() }
                if (extCanon == null || extCanon != intCanon) {
                    add(externalArchivesDir)
                }
            }
        }
        for (dir in archiveDirs) {
            dir.listFiles()?.forEach { child ->
                if (child.name == ".nomedia") return@forEach
                val bytes = calculateDirSize(child)
                val files = countFilesInDir(child)
                totalArchivesBytes += bytes
                totalArchivesFiles += files
                if (child.isDirectory) {
                    val t = acc(child.name)
                    t.archivesBytes += bytes
                    t.files += files
                }
            }
        }

        val perTarget = targetAcc.entries
            .map { (user, a) ->
                TargetStorageMetric(
                    username = user,
                    postsBytes = a.postsBytes,
                    storiesHighlightsBytes = a.storiesBytes,
                    avatarsBytes = a.avatarsBytes,
                    archivesBytes = a.archivesBytes,
                    totalFiles = a.files,
                )
            }
            .filter { it.totalBytes > 0L || it.totalFiles > 0 }
            .sortedByDescending { it.totalBytes }

        return VaultArchiveScanResult(
            postsBytes = totalPostsBytes,
            postsFiles = totalPostsFiles,
            storiesHighlightsBytes = totalStoriesBytes,
            storiesHighlightsFiles = totalStoriesFiles,
            avatarsBytes = totalAvatarsBytes,
            avatarsFiles = totalAvatarsFiles,
            archivesBytes = totalArchivesBytes,
            archivesFiles = totalArchivesFiles,
            perTarget = perTarget,
        )
    }

    private fun compactDatabase(writableDb: SupportSQLiteDatabase) {
        runCatching {
            writableDb.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            writableDb.execSQL("VACUUM")
        }
    }

    private fun measureTableBytes(
        db: SupportSQLiteDatabase,
        tableName: String,
        perRowOverheadBytes: Long,
    ): Pair<Long, Int> {
        return runCatching {
            val cols = mutableListOf<String>()
            db.query("PRAGMA table_info($tableName)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) cols.add(cursor.getString(nameIdx))
                }
            }
            if (cols.isEmpty()) return Pair(0L, 0)
            val sumExpr = cols.joinToString(" + ") { "COALESCE(LENGTH(CAST(\"$it\" AS BLOB)), 0)" }
            val sql = "SELECT COUNT(*), COALESCE(SUM($sumExpr), 0) FROM $tableName"
            db.query(sql).use { cursor ->
                if (cursor.moveToFirst()) {
                    val count = cursor.getInt(0)
                    val payloadBytes = cursor.getLong(1)
                    val totalBytes = if (count > 0) payloadBytes + (count * perRowOverheadBytes) else 0L
                    Pair(totalBytes, count)
                } else {
                    Pair(0L, 0)
                }
            }
        }.getOrDefault(Pair(0L, 0))
    }

    internal fun calculateDirSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return if (file.name == ".nomedia") 0L else file.length()
        var total = 0L
        runCatching {
            file.listFiles()?.forEach { child ->
                total += calculateDirSize(child)
            }
        }
        return total
    }

    internal fun countFilesInDir(file: File?): Int {
        if (file == null || !file.exists()) return 0
        if (file.isFile) return if (file.name == ".nomedia") 0 else 1
        var count = 0
        runCatching {
            file.listFiles()?.forEach { child ->
                count += countFilesInDir(child)
            }
        }
        return count
    }

    internal fun deleteFileOrDirectory(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        var freed = 0L
        runCatching {
            if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    freed += deleteFileOrDirectory(child)
                }
            }
            val len = if (file.isFile) file.length() else 0L
            if (file.delete()) {
                freed += len
            }
        }
        return freed
    }

    internal fun deleteDirectoryContents(dir: File?, preserveNoMedia: Boolean = true): Long {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0L
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { child ->
                if (preserveNoMedia && child.name == ".nomedia") return@forEach
                freed += deleteFileOrDirectory(child)
            }
            if (preserveNoMedia) {
                val nm = File(dir, ".nomedia")
                if (!nm.exists()) nm.createNewFile()
            }
        }
        return freed
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1.0) return "$bytes B"
        val mb = kb / 1024.0
        if (mb < 1.0) return String.format(Locale.US, "%.1f KB", kb).replace('.', ',')
        val gb = mb / 1024.0
        if (gb < 1.0) return String.format(Locale.US, "%.2f MB", mb).replace('.', ',')
        return String.format(Locale.US, "%.2f GB", gb).replace('.', ',')
    }

    fun splitBytesValueAndUnit(bytes: Long): Pair<String, String> {
        if (bytes <= 0L) return Pair("0", "B")
        val kb = bytes / 1024.0
        if (kb < 1.0) return Pair(bytes.toString(), "B")
        val mb = kb / 1024.0
        if (mb < 1.0) return Pair(String.format(Locale.US, "%.1f", kb).replace('.', ','), "KB")
        val gb = mb / 1024.0
        if (gb < 1.0) return Pair(String.format(Locale.US, "%.1f", mb).replace('.', ','), "MB")
        return Pair(String.format(Locale.US, "%.2f", gb).replace('.', ','), "GB")
    }
}
