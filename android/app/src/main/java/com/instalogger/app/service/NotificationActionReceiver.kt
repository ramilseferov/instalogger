package com.instalogger.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.instalogger.app.InstaLoggerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MARK_READ) {
            val notificationId = intent.getLongExtra(EXTRA_NOTIFICATION_ID, -1L)
            if (notificationId != -1L) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId.toInt())

                val app = context.applicationContext as? InstaLoggerApp
                val repository = app?.notificationRepository
                if (repository != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.markAsRead(notificationId)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.instalogger.app.ACTION_MARK_READ"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
