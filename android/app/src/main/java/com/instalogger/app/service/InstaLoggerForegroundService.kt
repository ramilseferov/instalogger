package com.instalogger.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.MainActivity
import com.instalogger.app.R
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.LanguageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class InstaLoggerForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val lastBackfillAttemptMs = ConcurrentHashMap<String, Long>()
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()
    private val failureBackoffUntilMs = ConcurrentHashMap<String, Long>()
    private var lastNotificationStatusText: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        startMonitoringLoop()
    }

    @SuppressLint("WakelockTimeout")
    private fun syncWakeLock(keepAwake: Boolean) {
        try {
            if (keepAwake) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "InstaLogger::BackgroundArchiverWakeLock"
                    )?.apply {
                        setReferenceCounted(false)
                    }
                }
                // Renew rolling 10-minute wake lock each cycle so CPU stays active without permanent leaks
                wakeLock?.acquire(WAKELOCK_RENEWAL_MS)
            } else {
                releaseWakeLock()
            }
        } catch (e: Exception) {
            AppLogger.w("ForegroundService", "WakeLock sync error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildForegroundNotification(customStatus: String? = null): Notification {
        val resCtx = LanguageManager.getLocalizedAppContext(this)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, InstaLoggerApp.CHANNEL_FOREGROUND_ID)
            .setContentTitle(resCtx.getString(R.string.service_notification_title))
            .setContentText(customStatus ?: resCtx.getString(R.string.service_notification_desc))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground() {
        val notification = buildForegroundNotification()

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

    private fun updateForegroundNotification(customStatus: String? = null) {
        try {
            val statusKey = customStatus ?: "__default__"
            if (statusKey == lastNotificationStatusText) return
            lastNotificationStatusText = statusKey
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(customStatus))
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    private fun updateForegroundNotificationLanguage() {
        lastNotificationStatusText = null
        updateForegroundNotification(null)
    }

    private fun startMonitoringLoop() {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            val app = application as InstaLoggerApp
            val database = app.database
            val profileInspector = app.profileInspector
            val settings = app.settingsManager
            val orchestrator = app.mediaDownloadOrchestrator

            AppLogger.i("ForegroundService", "Parallel background monitoring engine started")

            while (isActive) {
                val speed = settings.archiverSpeedPreset.value
                val keepAwake = settings.keepAwakeEnabled.value
                val autoArchive = settings.autoArchivePendingEnabled.value

                syncWakeLock(keepAwake)

                try {
                    val activeTargets = database.targetDao().getActiveTargets()
                    val (deletedZombies, liveTargets) = activeTargets.partition { profileInspector.isTargetDeleted(it.username) }
                    for (zombie in deletedZombies) {
                        AppLogger.i("ForegroundService", "Purging zombie tombstoned target @${zombie.username} from Room DB")
                        database.targetDao().deleteTarget(zombie.username)
                        consecutiveFailures.remove(zombie.username)
                        failureBackoffUntilMs.remove(zombie.username)
                        lastBackfillAttemptMs.remove(zombie.username)
                    }
                    val now = System.currentTimeMillis()

                    val activeDl = orchestrator.activeDownloadCount.value
                    val queuedDl = orchestrator.queuedDownloadCount.value
                    val resCtx = LanguageManager.getLocalizedAppContext(this@InstaLoggerForegroundService)
                    val liveStatus = if (activeDl > 0 || queuedDl > 0) {
                        resCtx.getString(
                            R.string.service_notification_live_downloads,
                            speed.key,
                            speed.downloadWorkers,
                            activeDl,
                            queuedDl
                        )
                    } else {
                        resCtx.getString(
                            R.string.service_notification_live_idle,
                            liveTargets.size,
                            speed.key,
                            speed.downloadWorkers
                        )
                    }
                    updateForegroundNotification(liveStatus)

                    if (liveTargets.isNotEmpty()) {
                        val targetSem = Semaphore(speed.targetWorkers.coerceIn(1, 6))
                        coroutineScope {
                            liveTargets.map { target ->
                                async {
                                    targetSem.withPermit {
                                        val intervalMs = if (target.isTurbo) 15_000L else (target.checkIntervalSeconds * 1000L)
                                        val lastCheck = target.lastCheckedAt ?: 0L
                                        val backoffUntil = failureBackoffUntilMs[target.username] ?: 0L

                                        if (now - lastCheck >= intervalMs && now >= backoffUntil) {
                                            try {
                                                val res = profileInspector.inspectTarget(target.username)
                                                if (res.isSuccess) {
                                                    consecutiveFailures.remove(target.username)
                                                    failureBackoffUntilMs.remove(target.username)
                                                } else {
                                                    val failCount = (consecutiveFailures[target.username] ?: 0) + 1
                                                    consecutiveFailures[target.username] = failCount
                                                    val delayMs = (30_000L shl (failCount - 1).coerceAtMost(4)).coerceAtMost(300_000L)
                                                    failureBackoffUntilMs[target.username] = System.currentTimeMillis() + delayMs
                                                    AppLogger.w("ForegroundService", "Backoff @${target.username} for ${delayMs / 1000}s after failure #$failCount")
                                                }
                                            } catch (e: Exception) {
                                                val failCount = (consecutiveFailures[target.username] ?: 0) + 1
                                                consecutiveFailures[target.username] = failCount
                                                val delayMs = (30_000L shl (failCount - 1).coerceAtMost(4)).coerceAtMost(300_000L)
                                                failureBackoffUntilMs[target.username] = System.currentTimeMillis() + delayMs
                                                AppLogger.w("ForegroundService", "inspectTarget(@${target.username}) error (backoff ${delayMs / 1000}s): ${e.message}")
                                            }
                                        } else if (autoArchive && now >= backoffUntil) {
                                            val lastBackfill = lastBackfillAttemptMs[target.username] ?: 0L
                                            if (now - lastBackfill >= BACKFILL_COOLDOWN_MS) {
                                                lastBackfillAttemptMs[target.username] = now
                                                try {
                                                    profileInspector.backfillUnarchivedContent(target)
                                                } catch (e: Exception) {
                                                    AppLogger.w("ForegroundService", "backfill(@${target.username}) error: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("ForegroundService", "Monitoring sweep error: ${e.message}")
                }

                delay(speed.loopTickMs.coerceIn(1_500L, 15_000L))
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

        val notifTag = "${eventType ?: "alert"}_$title"
        val markReadIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_TAG, notifTag)
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
                LanguageManager.getString(this, R.string.action_mark_read),
                pendingMarkRead
            )

        if (!settings.soundEnabled.value) {
            builder.setSilent(true)
        }
        if (!settings.vibrationEnabled.value) {
            builder.setVibrate(longArrayOf(0L))
        }

        notificationManager.notify(notifTag, (id and 0x7FFFFFFFL).toInt(), builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        instance = null
        isRunning = false
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_RENEWAL_MS = 10 * 60 * 1000L
        private const val BACKFILL_COOLDOWN_MS = 3 * 60 * 1000L

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

        fun refreshLanguageNotification() {
            instance?.updateForegroundNotificationLanguage()
        }
    }
}
