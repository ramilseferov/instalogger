package com.instalogger.app.engine.media

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

// Ephemeral Netscape HTTP Cookie File bridge for yt-dlp.
object NetscapeCookieBridge {
    private const val COOKIE_DIR_NAME = "ytdlp_cookies"
    private const val DEFAULT_EXPIRY_EPOCH = 2147483647L // Safe far-future epoch (2038)

    fun buildNetscapeContent(rawCookieHeader: String?): String? {
        if (rawCookieHeader.isNullOrBlank()) return null

        val entries = linkedMapOf<String, String>()
        rawCookieHeader.split(";").forEach { segment ->
            val trimmed = segment.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty() &&
                    !name.contains('\t') && !name.contains('\n') && !name.contains('\r') &&
                    !value.contains('\n') && !value.contains('\r')
                ) {
                    entries[name] = value
                }
            }
        }

        if (entries.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# Generated ephemerally by InstaLogger\n")
        for ((name, value) in entries) {
            // domain \t include_subdomains \t path \t secure \t expiration \t name \t value
            sb.append(".instagram.com\tTRUE\t/\tTRUE\t")
                .append(DEFAULT_EXPIRY_EPOCH)
                .append('\t')
                .append(name)
                .append('\t')
                .append(value)
                .append('\n')
        }
        return sb.toString()
    }

    suspend fun <T> withEphemeralCookieFile(
        context: Context,
        rawCookieHeader: String?,
        block: suspend (File?) -> T
    ): T {
        val content = buildNetscapeContent(rawCookieHeader)
            ?: return block(null)

        val dir = File(context.noBackupFilesDir, COOKIE_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "ck_${UUID.randomUUID()}.txt")
        try {
            file.writeText(content, Charsets.UTF_8)
            // Owner-only permissions (0600 equivalent)
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            return block(file)
        } finally {
            secureWipeAndDelete(file)
        }
    }

    fun purgeOrphanedCookieFiles(context: Context) {
        try {
            val dir = File(context.noBackupFilesDir, COOKIE_DIR_NAME)
            dir.listFiles()?.forEach { secureWipeAndDelete(it) }
        } catch (_: Exception) {
        }
    }

    fun secureWipeAndDelete(file: File) {
        try {
            if (file.exists() && file.isFile) {
                val len = file.length()
                if (len > 0L) {
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(0L)
                        val zeros = ByteArray(minOf(len.toInt(), 4096))
                        var remaining = len
                        while (remaining > 0L) {
                            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                            raf.write(zeros, 0, chunk)
                            remaining -= chunk
                        }
                        raf.fd.sync()
                    }
                }
                file.delete()
            }
        } catch (_: Exception) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
