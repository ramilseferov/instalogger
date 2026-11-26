package com.instalogger.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.instalogger.app.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed class ApkVerifyResult {
    data class Valid(
        val sha256Verified: Boolean,
        val actualSha256: String,
        val archiveVersionCode: Long
    ) : ApkVerifyResult()

    data class Invalid(
        val code: String,
        val detail: String
    ) : ApkVerifyResult()
}

object ApkIntegrityVerifier {
    private const val TAG = "ApkIntegrityVerifier"

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.lowercase().toByteArray(Charsets.UTF_8)
        val bb = b.lowercase().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(ba, bb)
    }

    @Suppress("DEPRECATION")
    fun verifyApk(
        context: Context,
        apkFile: File,
        expectedSha256: String?,
        installedVersionCode: Int
    ): ApkVerifyResult {
        if (!apkFile.exists() || apkFile.length() < 1024 * 1024) {
            return ApkVerifyResult.Invalid("CORRUPT_FILE", "APK file is missing or truncated")
        }

        // 1. Cryptographic SHA-256 verification
        val actualSha256 = try {
            computeSha256(apkFile)
        } catch (e: Exception) {
            return ApkVerifyResult.Invalid("HASH_ERROR", e.message ?: "Unable to compute SHA-256")
        }

        val shaVerified = if (!expectedSha256.isNullOrBlank()) {
            if (!constantTimeEquals(actualSha256, expectedSha256.trim())) {
                AppLogger.e(TAG, "SHA-256 mismatch! expected=$expectedSha256 actual=$actualSha256")
                return ApkVerifyResult.Invalid("SHA256_MISMATCH", "SHA-256 checksum mismatch")
            }
            true
        } else {
            false
        }

        // 2. PackageManager archive metadata + package name + versionCode verification
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
        } catch (e: Exception) {
            return ApkVerifyResult.Invalid("PARSE_ERROR", e.message ?: "Invalid APK archive")
        } ?: return ApkVerifyResult.Invalid("PARSE_ERROR", "Unable to parse APK package archive")

        if (archiveInfo.packageName != context.packageName) {
            return ApkVerifyResult.Invalid(
                "PACKAGE_MISMATCH",
                "Expected ${context.packageName}, got ${archiveInfo.packageName}"
            )
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }

        if (archiveVersionCode < installedVersionCode.toLong()) {
            return ApkVerifyResult.Invalid(
                "DOWNGRADE_BLOCKED",
                "APK build ($archiveVersionCode) is older than installed ($installedVersionCode)"
            )
        }

        // 3. Signing certificate fingerprint match against currently installed app
        val installedSigs = extractCertFingerprints(getInstalledPackageInfo(context, flags))
        val archiveSigs = extractCertFingerprints(archiveInfo)
        if (installedSigs.isNotEmpty() && archiveSigs.isNotEmpty() && installedSigs.intersect(archiveSigs).isEmpty()) {
            AppLogger.e(TAG, "APK signing certificate mismatch! installed=$installedSigs archive=$archiveSigs")
            return ApkVerifyResult.Invalid("SIGNING_MISMATCH", "APK signing certificate does not match installed app")
        }

        AppLogger.i(TAG, "APK verified OK: pkg=${archiveInfo.packageName} build=$archiveVersionCode sha256Verified=$shaVerified")
        return ApkVerifyResult.Valid(
            sha256Verified = shaVerified,
            actualSha256 = actualSha256,
            archiveVersionCode = archiveVersionCode
        )
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(context: Context, flags: Int): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, flags)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun extractCertFingerprints(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
            } else {
                info.signatures
            }
        } else {
            info.signatures
        } ?: return emptySet()

        return sigs.mapNotNull { sig ->
            runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }
}
