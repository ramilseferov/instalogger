package com.instalogger.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.instalogger.app.data.local.AppDatabase
import com.instalogger.app.data.remote.ApiService
import com.instalogger.app.data.remote.WebSocketManager
import com.instalogger.app.data.repository.NotificationRepository
import com.instalogger.app.data.repository.TargetRepository

class InstaLoggerApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var notificationRepository: NotificationRepository
        private set

    lateinit var targetRepository: TargetRepository
        private set

    lateinit var webSocketManager: WebSocketManager
        private set

    lateinit var apiService: ApiService
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        notificationRepository = NotificationRepository(database.notificationDao())

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, ApiService.DEFAULT_BASE_URL) ?: ApiService.DEFAULT_BASE_URL

        apiService = ApiService.create(serverUrl)
        targetRepository = TargetRepository(apiService)
        webSocketManager = WebSocketManager()
        webSocketManager.updateUrl(serverUrl)

        createNotificationChannels()
    }

    fun updateServerUrl(newUrl: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, newUrl).apply()

        apiService = ApiService.create(newUrl)
        targetRepository.updateApiService(apiService)
        webSocketManager.updateUrl(newUrl)
    }

    fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, ApiService.DEFAULT_BASE_URL) ?: ApiService.DEFAULT_BASE_URL
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
        }
    }

    companion object {
        const val PREFS_NAME = "instalogger_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val CHANNEL_FOREGROUND_ID = "instalogger_foreground_channel"
        const val CHANNEL_ALERTS_ID = "instalogger_alerts_channel"
    }
}
