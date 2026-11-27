package com.instalogger.app.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

object ArchiveDirectoryManager {
    private const val PREFS_NAME = "instalogger_prefs"
    private const val KEY_ARCHIVE_PATH = "custom_archive_path"
    private const val KEY_ARCHIVE_TREE_URI = "custom_archive_tree_uri"

    private val _displayPathFlow = MutableStateFlow("")
    val displayPathFlow: StateFlow<String> = _displayPathFlow.asStateFlow()

    fun init(context: Context) {
        _displayPathFlow.value = getDisplayPath(context)
    }

    fun getDisplayPath(context: Context): String {
        val prefs = getPrefs(context)
        val uriStr = prefs.getString(KEY_ARCHIVE_TREE_URI, null)
        if (!uriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(uriStr)
                val docId = DocumentsContract.getTreeDocumentId(uri)
                if (docId != null) {
                    if (docId.startsWith("primary:")) {
                        val sub = docId.substringAfter("primary:")
                        return "/storage/emulated/0/" + sub.trimStart('/')
                    } else if (docId.contains(":")) {
                        return "/storage/" + docId.replace(":", "/")
                    }
                }
                return uri.path ?: uriStr
            } catch (e: Exception) {
            }
        }

        val customPath = prefs.getString(KEY_ARCHIVE_PATH, null)
        if (!customPath.isNullOrBlank()) {
            return customPath
        }

        // App-isolated sandbox storage by default
        val appDir = context.getExternalFilesDir("archives") ?: File(context.filesDir, "archives")
        return appDir.absolutePath
    }

    fun getArchiveDirectory(context: Context): File {
        val display = getDisplayPath(context)
        val target = File(display)
        if (target.exists() || target.mkdirs()) {
            ensureNoMedia(target)
            return target
        }

        // App-isolated sandbox fallback
        val appDir = context.getExternalFilesDir("archives") ?: File(context.filesDir, "archives")
        appDir.mkdirs()
        ensureNoMedia(appDir)
        return appDir
    }

    fun setArchiveUri(context: Context, uri: Uri): Boolean {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                // Some Android ROMs don't support persistable flags
            }

            getPrefs(context).edit()
                .putString(KEY_ARCHIVE_TREE_URI, uri.toString())
                .apply()

            val display = getDisplayPath(context)
            _displayPathFlow.value = display
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun resetToDefault(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_ARCHIVE_TREE_URI)
            .remove(KEY_ARCHIVE_PATH)
            .apply()
        _displayPathFlow.value = getDisplayPath(context)
    }

    fun copyToInternalAvatar(context: Context, username: String, sourceFile: File): File? {
        return try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) return null
            val bytes = sourceFile.readBytes()
            saveInternalAvatarBytes(context, username, bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun saveInternalAvatarBytes(context: Context, username: String, bytes: ByteArray): File? {
        return try {
            val vaultManager = VaultStorageManager.getInstance(context)
            vaultManager.saveEncryptedFile(username, "profile", "avatar_current.jpg", bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun copyToInternalNamedAvatar(context: Context, username: String, sourceFile: File): File? {
        return try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) return null
            val bytes = sourceFile.readBytes()
            saveInternalNamedAvatarBytes(context, username, sourceFile.name, bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun saveInternalNamedAvatarBytes(context: Context, username: String, filename: String, bytes: ByteArray): File? {
        return try {
            val vaultManager = VaultStorageManager.getInstance(context)
            vaultManager.saveEncryptedFile(username, "profile", filename, bytes)
        } catch (e: Exception) {
            null
        }
    }

    fun getInternalAvatar(context: Context, username: String): File? {
        val vaultAvatar = VaultStorageManager.getInstance(context).getVaultAvatarFile(username)
        if (vaultAvatar != null) return vaultAvatar

        // Seamless one-time migration if a legacy plaintext avatar still exists
        val legacy = File(context.filesDir, "avatars/$username/avatar_current.jpg")
        if (legacy.exists() && legacy.length() > 0L) {
            val migrated = copyToInternalAvatar(context, username, legacy)
            if (migrated != null) {
                try { legacy.delete() } catch (_: Exception) {}
                return migrated
            }
            return legacy
        }
        return null
    }

    private fun resolveArchiveVaultLocation(filename: String): Pair<String, String> {
        val cleanRel = filename.trimStart('/')
        return if (cleanRel.contains('/')) {
            "archives/" + cleanRel.substringBeforeLast('/') to cleanRel.substringAfterLast('/')
        } else {
            "archives" to cleanRel
        }
    }

    fun saveUserArchiveJson(context: Context, username: String, filename: String, content: String): File? {
        return try {
            val vaultManager = VaultStorageManager.getInstance(context)
            val (category, baseName) = resolveArchiveVaultLocation(filename)
            val encrypted = vaultManager.saveEncryptedFile(
                targetUsername = username,
                category = category,
                filename = baseName,
                plaintextBytes = content.toByteArray(Charsets.UTF_8)
            )
            // Remove any leftover plaintext copy from older versions
            try {
                File(context.filesDir, "archives/$username/$filename").takeIf { it.exists() }?.delete()
                File(getArchiveDirectory(context), "$username/$filename").takeIf { it.exists() }?.delete()
            } catch (_: Exception) {}
            encrypted
        } catch (e: Exception) {
            null
        }
    }

    fun getUserArchiveJson(context: Context, username: String, filename: String): String? {
        return try {
            val vaultManager = VaultStorageManager.getInstance(context)
            val (category, baseName) = resolveArchiveVaultLocation(filename)
            val vaultFile = vaultManager.getVaultMediaFile(username, category, baseName)
            if (vaultFile != null) {
                val decrypted = vaultManager.readEncryptedFile(vaultFile)
                if (decrypted != null) {
                    return String(decrypted, Charsets.UTF_8)
                }
            }

            // Legacy plaintext fallback -> transparently migrate to Vault and delete plaintext
            val internal = File(context.filesDir, "archives/$username/$filename")
            val external = File(getArchiveDirectory(context), "$username/$filename")
            val legacyFile = when {
                internal.exists() && internal.length() > 0L -> internal
                external.exists() && external.length() > 0L -> external
                else -> null
            } ?: return null

            val content = legacyFile.readText(Charsets.UTF_8)
            if (saveUserArchiveJson(context, username, filename, content) != null) {
                try { internal.delete() } catch (_: Exception) {}
                try { external.delete() } catch (_: Exception) {}
            }
            content
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Migrates any legacy unencrypted avatars, media copies, or JSON archives from earlier
     * app versions into the AES-256-GCM Vault and deletes the plaintext copies so zero
     * unencrypted media remains exposed on disk or to Android's MediaScanner/Gallery.
     */
    fun migrateAndPurgePlaintextMedia(context: Context) {
        try {
            val appCtx = context.applicationContext
            val vaultManager = VaultStorageManager.getInstance(appCtx)

            // Guarantee .nomedia markers on all app-owned directories
            listOfNotNull(
                appCtx.filesDir,
                vaultManager.vaultRoot,
                appCtx.cacheDir,
                appCtx.externalCacheDir,
                appCtx.getExternalFilesDir(null),
                appCtx.getExternalFilesDir("archives"),
                runCatching { getArchiveDirectory(appCtx) }.getOrNull()
            ).forEach { dir ->
                if (dir.exists() && dir.isDirectory) ensureNoMedia(dir)
            }

            var migratedCount = 0
            var purgedCount = 0

            fun isMediaFile(f: File): Boolean {
                val n = f.name.lowercase()
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                    n.endsWith(".webp") || n.endsWith(".mp4")
            }

            fun migrateAndDeleteFile(username: String, category: String, file: File) {
                if (!file.isFile || file.name == ".nomedia" || file.name.endsWith(".enc")) return
                try {
                    val existingVault = vaultManager.getVaultMediaFile(username, category, file.name)
                    if (existingVault == null && file.length() > 0L) {
                        vaultManager.saveEncryptedFile(username, category, file.name, file)
                        migratedCount++
                    }
                    if (file.delete()) {
                        purgedCount++
                    }
                } catch (e: Exception) {
                    AppLogger.w("ArchiveDirectoryManager", "Plaintext migration failed (${file.name}): ${e.message}")
                }
            }

            // 1. Legacy internal plaintext avatars: filesDir/avatars/<user>/*
            val legacyAvatarsRoot = File(appCtx.filesDir, "avatars")
            if (legacyAvatarsRoot.exists() && legacyAvatarsRoot.isDirectory) {
                legacyAvatarsRoot.listFiles()?.forEach { userDir ->
                    if (userDir.isDirectory) {
                        userDir.listFiles()?.forEach { f ->
                            if (isMediaFile(f)) migrateAndDeleteFile(userDir.name, "profile", f)
                        }
                        deleteDirIfEmptyOrNoMediaOnly(userDir)
                    }
                }
            }

            // 2. Legacy internal plaintext posts & highlights: filesDir/posts/<user>/*, filesDir/highlights/<user>/*
            listOf("posts" to "posts", "highlights" to "highlights").forEach { (dirName, category) ->
                val root = File(appCtx.filesDir, dirName)
                if (root.exists() && root.isDirectory) {
                    root.listFiles()?.forEach { userDir ->
                        if (userDir.isDirectory) {
                            userDir.listFiles()?.forEach { f ->
                                if (isMediaFile(f)) migrateAndDeleteFile(userDir.name, category, f)
                            }
                            deleteDirIfEmptyOrNoMediaOnly(userDir)
                        }
                    }
                }
            }

            // 3. External/internal archive directories: plaintext profile avatars & user JSON archives
            val archiveRoots = linkedSetOf<File>()
            archiveRoots.add(File(appCtx.filesDir, "archives"))
            appCtx.getExternalFilesDir("archives")?.let { archiveRoots.add(it) }
            runCatching { getArchiveDirectory(appCtx) }.getOrNull()?.let { archiveRoots.add(it) }

            for (root in archiveRoots) {
                if (!root.exists() || !root.isDirectory) continue
                ensureNoMedia(root)
                root.listFiles()?.forEach { userDir ->
                    if (!userDir.isDirectory || userDir.name.startsWith(".")) return@forEach
                    val username = userDir.name

                    // 3a. Plaintext profile avatar mirror (<archive>/<user>/profile/*.jpg)
                    val profileDir = File(userDir, "profile")
                    if (profileDir.exists() && profileDir.isDirectory) {
                        profileDir.listFiles()?.forEach { f ->
                            if (isMediaFile(f)) migrateAndDeleteFile(username, "profile", f)
                        }
                        deleteDirIfEmptyOrNoMediaOnly(profileDir)
                    }

                    // 3b. Plaintext followers.json & following.json
                    listOf("followers.json", "following.json").forEach { jsonName ->
                        val jf = File(userDir, jsonName)
                        if (jf.exists() && jf.isFile) {
                            migrateAndDeleteFile(username, "archives", jf)
                        }
                    }

                    // 3c. Plaintext sidecar comments/*.json
                    val commentsDir = File(userDir, "comments")
                    if (commentsDir.exists() && commentsDir.isDirectory) {
                        commentsDir.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.endsWith(".json", ignoreCase = true)) {
                                migrateAndDeleteFile(username, "archives/comments", f)
                            }
                        }
                        deleteDirIfEmptyOrNoMediaOnly(commentsDir)
                    }

                    // 3d. Remove empty highlights mirror folder if created by older builds
                    val hlDir = File(userDir, "highlights")
                    if (hlDir.exists() && hlDir.isDirectory) {
                        deleteDirIfEmptyOrNoMediaOnly(hlDir)
                    }

                    deleteDirIfEmptyOrNoMediaOnly(userDir)
                }
            }

            if (migratedCount > 0 || purgedCount > 0) {
                AppLogger.i(
                    "ArchiveDirectoryManager",
                    "Vault privacy sweep complete: migrated=$migratedCount, purgedPlaintext=$purgedCount"
                )
            }
        } catch (e: Exception) {
            AppLogger.w("ArchiveDirectoryManager", "migrateAndPurgePlaintextMedia error: ${e.message}")
        }
    }

    private fun deleteDirIfEmptyOrNoMediaOnly(dir: File) {
        try {
            val remaining = dir.listFiles()?.filter { it.name != ".nomedia" } ?: return
            if (remaining.isEmpty()) {
                dir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    fun deleteInternalTargetFiles(context: Context, username: String) {
        try {
            File(context.filesDir, "avatars/$username").deleteRecursively()
            File(context.filesDir, "archives/$username").deleteRecursively()
            File(context.filesDir, "posts/$username").deleteRecursively()
            File(context.filesDir, "highlights/$username").deleteRecursively()
            File(VaultStorageManager.getInstance(context).vaultRoot, username).deleteRecursively()
            runCatching { File(getArchiveDirectory(context), username).deleteRecursively() }
        } catch (_: Exception) {}
    }

    fun ensureNoMedia(dir: File) {
        try {
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        } catch (_: Exception) {}
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
