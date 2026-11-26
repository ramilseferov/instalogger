package com.instalogger.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.instalogger.app.util.AppLogger
import java.io.File
import java.io.FileInputStream

object ApkInstallerEngine {
    private const val TAG = "ApkInstallerEngine"

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            installViaPackageInstallerSession(context, apkFile)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.w(TAG, "PackageInstaller.Session failed (${e.message}); falling back to FileProvider ACTION_VIEW")
            try {
                installViaFileProviderFallback(context, apkFile)
                Result.success(Unit)
            } catch (e2: Exception) {
                AppLogger.e(TAG, "FileProvider install fallback also failed", e2)
                Result.failure(e2)
            }
        }
    }

    private fun installViaPackageInstallerSession(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            session = packageInstaller.openSession(sessionId)
            session.openWrite("instalogger_update.apk", 0L, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
                session.fsync(out)
            }

            val callbackIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, piFlags)
            session.commit(pendingIntent.intentSender)
            AppLogger.i(TAG, "PackageInstaller session #$sessionId committed for ${apkFile.name}")
        } catch (e: Exception) {
            try {
                session?.abandon()
            } catch (_: Exception) {
            }
            throw e
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun installViaFileProviderFallback(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
