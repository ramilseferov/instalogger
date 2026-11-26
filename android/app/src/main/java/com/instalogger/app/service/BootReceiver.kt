package com.instalogger.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.instalogger.app.util.AppLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            AppLogger.i("BootReceiver", "System broadcast ($action) received -> starting InstaLoggerForegroundService")
            try {
                InstaLoggerForegroundService.start(context.applicationContext)
            } catch (e: Exception) {
                AppLogger.e("BootReceiver", "Failed to auto-start foreground service", e)
            }
        }
    }
}
