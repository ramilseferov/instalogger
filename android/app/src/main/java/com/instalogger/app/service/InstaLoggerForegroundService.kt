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
import com.instalogger.app.data.remote.WebSocketManager
import com.instalogger.app.data.remote.WsEvent
import kotlinx.coroutines.*

class InstaLoggerForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var webSocketManager: WebSocketManager? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as InstaLoggerApp
        webSocketManager = app.webSocketManager

        startInForeground()
        listenToWebSocketEvents()
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

    private fun listenToWebSocketEvents() {
        webSocketManager?.connect()

        serviceScope.launch {
            webSocketManager?.events?.collect { event ->
                when (event) {
                    is WsEvent.ChangeDetected -> {
                        handleTargetChange(event)
                    }
                    is WsEvent.TargetChecked -> {
                        // Background check confirmed
                    }
                    is WsEvent.Connected -> {
                        // WS active
                    }
                    is WsEvent.Disconnected -> {
                        // Reconnecting automatically
                    }
                }
            }
        }
    }

    private fun handleTargetChange(change: WsEvent.ChangeDetected) {
        serviceScope.launch {
            val app = application as InstaLoggerApp
            val title = "${change.username} profilində dəyişiklik"
            val message = "${change.fieldName}: ${change.oldValue ?: "-"} -> ${change.newValue ?: "-"}"

            val savedId = app.notificationRepository.insertNotification(
                targetId = change.targetId,
                username = change.username,
                eventType = "change_${change.fieldName}",
                title = title,
                message = message
            )

            showAlertNotification(savedId, title, message)
        }
    }

    private fun showAlertNotification(id: Long, title: String, message: String) {
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

        val notification = NotificationCompat.Builder(this, InstaLoggerApp.CHANNEL_ALERTS_ID)
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
            .build()

        notificationManager.notify(id.toInt(), notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        webSocketManager?.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001

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
