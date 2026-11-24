package com.instalogger.app.engine.media

import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

data class ValidatedMediaFile(
    val mimeType: String,
    val extension: String,
    val isVideo: Boolean,
    val fileSize: Long,
    val sha256: String
)

object LogSanitizer {
    private val SENSITIVE_COOKIE_REGEX = Regex(
        "(?i)(sessionid|csrftoken|ds_user_id|rur|mid|ig_did|shbid|shbts|authorization|cookie)\\s*[:=]\\s*[^;\\s,\"'&]+"
    )
    private val SENSITIVE_QUERY_REGEX = Regex(
        "(?i)([?&](?:oe|oh|_nc_sid|ccb|efg|signature|token|access_token)=)[^&\\s\"']+"
    )

    fun redact(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cookiesRedacted = SENSITIVE_COOKIE_REGEX.replace(raw) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        return SENSITIVE_QUERY_REGEX.replace(cookiesRedacted) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
    }
}

object MediaUrlGuard {
    private val ALLOWED_HOST_SUFFIXES = listOf(
        "instagram.com",
        "cdninstagram.com",
        "fbcdn.net",
        "fbsbx.com"
    )

    fun isAllowedMediaUrl(url: String?, allowLocalHost: Boolean = false): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false

            if (allowLocalHost && (scheme == "http" || scheme == "https") &&
                (host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2" || host.startsWith("192.168."))
            ) {
                return true
            }

            if (scheme != "https") return false
            ALLOWED_HOST_SUFFIXES.any { suffix ->
                host == suffix || host.endsWith(".$suffix")
            }
        } catch (_: Exception) {
            false
        }
    }
}

object MediaFileValidator {
    private const val MIN_MEDIA_BYTES = 128L
    private const val MAX_MEDIA_BYTES = 250L * 1024L * 1024L // 250 MB safety cap

    fun validateAndHash(file: File): ValidatedMediaFile? {
        if (!file.exists() || !file.isFile) return null
        val size = file.length()
        if (size < MIN_MEDIA_BYTES || size > MAX_MEDIA_BYTES) return null

        val header = ByteArray(32)
        val readCount = try {
            FileInputStream(file).use { it.read(header) }
        } catch (_: Exception) {
            return null
        }
        if (readCount < 12) return null

        // Explicitly reject HTML/XML error/login pages masquerading as media
        val headAscii = String(header, 0, readCount, Charsets.US_ASCII).trimStart().lowercase(Locale.ROOT)
        if (headAscii.startsWith("<!doc") || headAscii.startsWith("<html") || headAscii.startsWith("<?xml") || headAscii.startsWith("{")) {
            return null
        }

        val detected = detectMediaSignature(header, readCount) ?: return null
        val sha256 = computeSha256(file) ?: return null

        return ValidatedMediaFile(
            mimeType = detected.first,
            extension = detected.second,
            isVideo = detected.third,
            fileSize = size,
            sha256 = sha256
        )
    }

    internal fun detectMediaSignature(header: ByteArray, length: Int): Triple<String, String, Boolean>? {
        if (length < 12) return null

        // JPEG: FF D8 FF
        if ((header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF
        ) {
            return Triple("image/jpeg", "jpg", false)
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((header[0].toInt() and 0xFF) == 0x89 &&
            header[1].toInt() == 0x50 &&
            header[2].toInt() == 0x4E &&
            header[3].toInt() == 0x47
        ) {
            return Triple("image/png", "png", false)
        }

        // GIF: GIF87a / GIF89a
        if (header[0].toInt() == 'G'.code && header[1].toInt() == 'I'.code && header[2].toInt() == 'F'.code) {
            return Triple("image/gif", "gif", false)
        }

        // WEBP: RIFF....WEBP
        if (header[0].toInt() == 'R'.code && header[1].toInt() == 'I'.code &&
            header[2].toInt() == 'F'.code && header[3].toInt() == 'F'.code &&
            header[8].toInt() == 'W'.code && header[9].toInt() == 'E'.code &&
            header[10].toInt() == 'B'.code && header[11].toInt() == 'P'.code
        ) {
            return Triple("image/webp", "webp", false)
        }

        // ISO BMFF / MP4 / MOV / M4V / HEIC: bytes 4..7 == "ftyp"
        if (header[4].toInt() == 'f'.code && header[5].toInt() == 't'.code &&
            header[6].toInt() == 'y'.code && header[7].toInt() == 'p'.code
        ) {
            val brand = String(header, 8, minOf(4, length - 8), Charsets.US_ASCII).lowercase(Locale.ROOT)
            if (brand == "heic" || brand == "heix" || brand == "mif1" || brand == "avif") {
                return Triple("image/$brand", brand, false)
            }
            return Triple("video/mp4", "mp4", true)
        }

        // WebM / Matroska: 1A 45 DF A3
        if ((header[0].toInt() and 0xFF) == 0x1A &&
            (header[1].toInt() and 0xFF) == 0x45 &&
            (header[2].toInt() and 0xFF) == 0xDF &&
            (header[3].toInt() and 0xFF) == 0xA3
        ) {
            return Triple("video/webm", "webm", true)
        }

        return null
    }

    fun computeSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
