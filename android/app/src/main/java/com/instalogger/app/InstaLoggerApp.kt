package com.instalogger.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.instalogger.app.data.local.AppDatabase
import com.instalogger.app.data.repository.NotificationRepository
import com.instalogger.app.engine.InstagramEngine
import com.instalogger.app.engine.ProfileInspector
import com.instalogger.app.engine.SessionManager
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.util.AppLockManager
import com.instalogger.app.util.VaultMediaFetcher
import com.instalogger.app.util.VaultStorageManager

class InstaLoggerApp : Application(), ImageLoaderFactory {

    lateinit var database: AppDatabase
        private set

    lateinit var sessionManager: SessionManager
        private set

    lateinit var instagramEngine: InstagramEngine
        private set

    lateinit var profileInspector: ProfileInspector
        private set

    lateinit var notificationRepository: NotificationRepository
        private set

    lateinit var settingsManager: AppSettingsManager
        private set

    lateinit var appLockManager: AppLockManager
        private set

    lateinit var vaultStorageManager: VaultStorageManager
        private set

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VaultMediaFetcher.Factory(this@InstaLoggerApp))
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        appLockManager = AppLockManager.getInstance(this)
        vaultStorageManager = VaultStorageManager.getInstance(this)
        settingsManager = AppSettingsManager.getInstance(this)
        database = AppDatabase.getInstance(this)
        sessionManager = SessionManager(this)
        instagramEngine = InstagramEngine()
        notificationRepository = NotificationRepository(database.notificationDao())

        profileInspector = ProfileInspector(
            context = this,
            database = database,
            sessionManager = sessionManager,
            instagramEngine = instagramEngine
        )

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val foregroundChannel = NotificationChannel(
                CHANNEL_FOREGROUND_ID,
                getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_desc)
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                getString(R.string.alert_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notifications_subtitle)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(foregroundChannel)
            notificationManager.createNotificationChannel(alertsChannel)

            NotificationCategory.entries.forEach { cat ->
                val catChannel = NotificationChannel(
                    cat.channelId,
                    getString(cat.titleRes),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(cat.descRes)
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(catChannel)
            }
        }
    }

    companion object {
        const val CHANNEL_FOREGROUND_ID = "instalogger_foreground_channel"
        const val CHANNEL_ALERTS_ID = "instalogger_alerts_channel"
    }
}
