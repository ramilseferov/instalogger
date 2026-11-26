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
            val vaultFile = vaultManager.saveEncryptedFile(username, "profile", "avatar_current.jpg", bytes)

            // Ensure fallback legacy dir has .nomedia
            val legacyDir = File(context.filesDir, "avatars/$username")
            legacyDir.mkdirs()
            ensureNoMedia(legacyDir)

            vaultFile
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
            val vaultFile = vaultManager.saveEncryptedFile(username, "profile", filename, bytes)

            val legacyDir = File(context.filesDir, "avatars/$username")
            legacyDir.mkdirs()
            ensureNoMedia(legacyDir)

            vaultFile
        } catch (e: Exception) {
            null
        }
    }

    fun getInternalAvatar(context: Context, username: String): File? {
        val vaultAvatar = VaultStorageManager.getInstance(context).getVaultAvatarFile(username)
        if (vaultAvatar != null) return vaultAvatar

        val f = File(context.filesDir, "avatars/$username/avatar_current.jpg")
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun saveUserArchiveJson(context: Context, username: String, filename: String, content: String): File? {
        return try {
            val root = getArchiveDirectory(context)
            val userDir = File(root, username)
            userDir.mkdirs()
            ensureNoMedia(userDir)
            val dest = File(userDir, filename)
            dest.writeText(content, Charsets.UTF_8)

            // Also keep internal app-isolated copy
            val internalDir = File(context.filesDir, "archives/$username")
            internalDir.mkdirs()
            ensureNoMedia(internalDir)
            File(internalDir, filename).writeText(content, Charsets.UTF_8)

            dest
        } catch (e: Exception) {
            null
        }
    }

    fun getUserArchiveJson(context: Context, username: String, filename: String): String? {
        return try {
            val root = getArchiveDirectory(context)
            val f = File(root, "$username/$filename")
            if (f.exists() && f.length() > 0L) {
                return f.readText(Charsets.UTF_8)
            }
            val internal = File(context.filesDir, "archives/$username/$filename")
            if (internal.exists() && internal.length() > 0L) {
                return internal.readText(Charsets.UTF_8)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun deleteInternalTargetFiles(context: Context, username: String) {
        try {
            File(context.filesDir, "avatars/$username").deleteRecursively()
            File(context.filesDir, "archives/$username").deleteRecursively()
            File(VaultStorageManager.getInstance(context).vaultRoot, username).deleteRecursively()
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
