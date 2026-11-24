package com.instalogger.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Safely decrypts AES-256-GCM Vault media files (.enc) into an app-private cache subdirectory
 * exposed strictly via FileProvider so external video players and Android Share sheets work
 * seamlessly without leaving permanent plaintext files in public storage.
 */
object VaultPlaybackBridge {
    private const val PLAYBACK_DIR = "playback_tmp"
    private const val STALE_AGE_MS = 30 * 60 * 1000L

    fun prepareShareableUri(context: Context, sourceFile: File, fallbackExtension: String = "mp4"): Pair<Uri, String>? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null

        pruneStaleFiles(context)

        val vault = VaultStorageManager.getInstance(context)
        val tempDir = File(context.cacheDir, PLAYBACK_DIR).apply { mkdirs() }

        val cleanBaseName = sourceFile.name
            .removeSuffix(".enc")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val finalName = if (cleanBaseName.contains('.')) cleanBaseName else "$cleanBaseName.$fallbackExtension"
        val outFile = File(tempDir, finalName)

        val ok = if (vault.isVaultFile(sourceFile)) {
            val decrypted = vault.readEncryptedFile(sourceFile) ?: return null
            FileOutputStream(outFile).use { it.write(decrypted) }
            true
        } else {
            sourceFile.copyTo(outFile, overwrite = true)
            true
        }

        if (!ok || !outFile.exists() || outFile.length() == 0L) return null

        val mime = when (outFile.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return uri to mime
    }

    fun pruneStaleFiles(context: Context) {
        try {
            val dir = File(context.cacheDir, PLAYBACK_DIR)
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - STALE_AGE_MS
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
            // Non-fatal
        }
    }
}
