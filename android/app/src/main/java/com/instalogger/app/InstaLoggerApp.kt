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
import com.instalogger.app.engine.media.DaemonBridgeProvider
import com.instalogger.app.engine.media.MediaDownloadOrchestrator
import com.instalogger.app.engine.media.NativeGraphProvider
import com.instalogger.app.engine.media.ProviderHealthTracker
import com.instalogger.app.engine.media.YtDlpEmbeddedProvider
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.util.AppLockManager
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.LanguageManager
import com.instalogger.app.util.VaultMediaFetcher
import com.instalogger.app.util.VaultStorageManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    lateinit var ytDlpProvider: YtDlpEmbeddedProvider
        private set

    lateinit var providerHealthTracker: ProviderHealthTracker
        private set

    lateinit var mediaDownloadOrchestrator: MediaDownloadOrchestrator
        private set

    override fun newImageLoader(): ImageLoader {
        val coilClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host.lowercase()
                val builder = req.newBuilder()
                if (host.contains("instagram.com") || host.contains("cdninstagram.com") || host.contains("fbcdn.net")) {
                    if (req.header("User-Agent") == null) {
                        builder.header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                    }
                    if (req.header("Accept") == null) {
                        builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    }
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(coilClient)
            .components {
                add(VaultMediaFetcher.Factory(this@InstaLoggerApp))
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        LanguageManager.init(this)
        AppLogger.init(this)
        appLockManager = AppLockManager.getInstance(this)
        vaultStorageManager = VaultStorageManager.getInstance(this)
        settingsManager = AppSettingsManager.getInstance(this)
        database = AppDatabase.getInstance(this)
        sessionManager = SessionManager(this)
        instagramEngine = InstagramEngine(this)
        notificationRepository = NotificationRepository(database.notificationDao())

        ytDlpProvider = YtDlpEmbeddedProvider(this)
        val nativeGraphProvider = NativeGraphProvider(instagramEngine)
        val daemonBridgeProvider = DaemonBridgeProvider(settingsManager)
        providerHealthTracker = ProviderHealthTracker(database.providerHealthDao())
        mediaDownloadOrchestrator = MediaDownloadOrchestrator(
            context = this,
            database = database,
            sessionManager = sessionManager,
            settingsManager = settingsManager,
            vaultStorageManager = vaultStorageManager,
            ytDlpProvider = ytDlpProvider,
            nativeGraphProvider = nativeGraphProvider,
            daemonBridgeProvider = daemonBridgeProvider,
            healthTracker = providerHealthTracker
        )
        ytDlpProvider.warmUpAsync()

        profileInspector = ProfileInspector(
            context = this,
            database = database,
            sessionManager = sessionManager,
            instagramEngine = instagramEngine,
            mediaDownloadOrchestrator = mediaDownloadOrchestrator
        )

        refreshNotificationChannels()
    }

    fun refreshNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val resCtx = LanguageManager.getLocalizedAppContext(this)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val foregroundChannel = NotificationChannel(
                CHANNEL_FOREGROUND_ID,
                resCtx.getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = resCtx.getString(R.string.service_notification_desc)
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                resCtx.getString(R.string.alert_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = resCtx.getString(R.string.notifications_subtitle)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(foregroundChannel)
            notificationManager.createNotificationChannel(alertsChannel)

            NotificationCategory.entries.forEach { cat ->
                val catChannel = NotificationChannel(
                    cat.channelId,
                    resCtx.getString(cat.titleRes),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = resCtx.getString(cat.descRes)
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
