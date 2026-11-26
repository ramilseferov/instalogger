package com.instalogger.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.util.AppLogger

class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val app = context.applicationContext as? InstaLoggerApp
        val updateManager = app?.updateManager

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to launch system install confirmation", e)
                        updateManager?.onInstallFailed(status, e.message ?: "Unable to launch installer")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLogger.i(TAG, "PackageInstaller reported STATUS_SUCCESS")
                updateManager?.onInstallSucceeded()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLogger.i(TAG, "PackageInstaller installation aborted by user")
                updateManager?.onInstallCancelled()
            }
            else -> {
                AppLogger.e(TAG, "PackageInstaller failed: status=$status msg=$message")
                updateManager?.onInstallFailed(status, message)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.instalogger.app.ACTION_UPDATE_INSTALL_STATUS"
    }
}
