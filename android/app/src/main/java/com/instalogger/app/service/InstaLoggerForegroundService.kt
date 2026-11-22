package com.instalogger.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.MainActivity
import com.instalogger.app.R
import com.instalogger.app.engine.ProfileInspector
import com.instalogger.app.settings.NotificationCategory
import kotlinx.coroutines.*

class InstaLoggerForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        startMonitoringLoop()
    }

    private fun startInForeground() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, InstaLoggerApp.CHANNEL_FOREGROUND_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoringLoop() {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            val app = application as InstaLoggerApp
            val database = app.database
            val profileInspector = app.profileInspector

            while (isActive) {
                try {
                    val activeTargets = database.targetDao().getActiveTargets()
                    val now = System.currentTimeMillis()

                    for (target in activeTargets) {
                        val intervalMs = if (target.isTurbo) 15_000L else (target.checkIntervalSeconds * 1000L)
                        val lastCheck = target.lastCheckedAt ?: 0L

                        if (now - lastCheck >= intervalMs) {
                            try {
                                profileInspector.inspectTarget(target.username)
                            } catch (e: Exception) {
                                // Ignore individual check error and continue loop
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Loop guard
                }

                delay(5_000L)
            }
        }
    }

    fun showAlertNotification(id: Long, title: String, message: String, eventType: String? = null) {
        val app = application as InstaLoggerApp
        val settings = app.settingsManager

        if (!settings.isNotificationAllowed(eventType)) return

        val cat = NotificationCategory.fromEventType(eventType)
        val channelId = cat.channelId

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            this,
            id.toInt(),
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val markReadIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
        }
        val pendingMarkRead = PendingIntent.getBroadcast(
            this,
            id.toInt(),
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingContent)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.action_mark_read),
                pendingMarkRead
            )

        if (!settings.soundEnabled.value) {
            builder.setSilent(true)
        }
        if (!settings.vibrationEnabled.value) {
            builder.setVibrate(longArrayOf(0L))
        }

        notificationManager.notify(id.toInt(), builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: InstaLoggerForegroundService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, InstaLoggerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, InstaLoggerForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
