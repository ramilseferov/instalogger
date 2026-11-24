package com.instalogger.app.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.instalogger.app.R
import com.instalogger.app.engine.media.YtDlpQualityPreset
import com.instalogger.app.engine.media.YtDlpUpdateChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class NotificationCategory(
    val id: String,
    val prefKey: String,
    val channelId: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val icon: ImageVector,
    val defaultEnabled: Boolean = true
) {
    PROFILE_CHANGES(
        id = "profile",
        prefKey = "notif_cat_profile",
        channelId = "instalogger_channel_profile",
        titleRes = R.string.notif_cat_profile_title,
        descRes = R.string.notif_cat_profile_desc,
        icon = Icons.Default.AccountCircle
    ),
    PRIVACY_ALERTS(
        id = "privacy",
        prefKey = "notif_cat_privacy",
        channelId = "instalogger_channel_privacy",
        titleRes = R.string.notif_cat_privacy_title,
        descRes = R.string.notif_cat_privacy_desc,
        icon = Icons.Default.Lock
    ),
    ACTIVITY_UPDATES(
        id = "activity",
        prefKey = "notif_cat_activity",
        channelId = "instalogger_channel_activity",
        titleRes = R.string.notif_cat_activity_title,
        descRes = R.string.notif_cat_activity_desc,
        icon = Icons.AutoMirrored.Filled.TrendingUp
    ),
    SYSTEM_ALERTS(
        id = "system",
        prefKey = "notif_cat_system",
        channelId = "instalogger_channel_system",
        titleRes = R.string.notif_cat_system_title,
        descRes = R.string.notif_cat_system_desc,
        icon = Icons.Default.Tune
    );

    companion object {
        fun fromEventType(eventType: String?): NotificationCategory {
            return when (eventType?.lowercase()) {
                "avatar_change", "bio_change", "name_change" -> PROFILE_CHANGES
                "privacy_change" -> PRIVACY_ALERTS
                "post_count_change", "follower_count_change", "following_count_change" -> ACTIVITY_UPDATES
                else -> SYSTEM_ALERTS
            }
        }
    }
}

class AppSettingsManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("instalogger_app_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private val _statusBarNotifications = MutableStateFlow(loadBoolean(KEY_STATUS_BAR_NOTIFS, true))
    val statusBarNotifications: StateFlow<Boolean> = _statusBarNotifications.asStateFlow()

    private val _inAppNotifications = MutableStateFlow(loadBoolean(KEY_IN_APP_NOTIFS, true))
    val inAppNotifications: StateFlow<Boolean> = _inAppNotifications.asStateFlow()

    private val _soundEnabled = MutableStateFlow(loadBoolean(KEY_NOTIF_SOUND, true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _vibrationEnabled = MutableStateFlow(loadBoolean(KEY_NOTIF_VIBRATION, true))
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    private val _daemonSyncEnabled = MutableStateFlow(loadBoolean(KEY_DAEMON_SYNC_ENABLED, false))
    val daemonSyncEnabled: StateFlow<Boolean> = _daemonSyncEnabled.asStateFlow()

    private val _daemonHostUrl = MutableStateFlow(prefs.getString(KEY_DAEMON_HOST_URL, DEFAULT_DAEMON_URL) ?: DEFAULT_DAEMON_URL)
    val daemonHostUrl: StateFlow<String> = _daemonHostUrl.asStateFlow()

    private val _ytDlpAutoDownloadVideos = MutableStateFlow(loadBoolean(KEY_YTDLP_AUTO_VIDEOS, true))
    val ytDlpAutoDownloadVideos: StateFlow<Boolean> = _ytDlpAutoDownloadVideos.asStateFlow()

    private val _ytDlpWifiOnly = MutableStateFlow(loadBoolean(KEY_YTDLP_WIFI_ONLY, false))
    val ytDlpWifiOnly: StateFlow<Boolean> = _ytDlpWifiOnly.asStateFlow()

    private val _ytDlpQualityPreset = MutableStateFlow(
        YtDlpQualityPreset.fromKey(prefs.getString(KEY_YTDLP_QUALITY, YtDlpQualityPreset.BEST.key))
    )
    val ytDlpQualityPreset: StateFlow<YtDlpQualityPreset> = _ytDlpQualityPreset.asStateFlow()

    private val _ytDlpUpdateChannel = MutableStateFlow(
        YtDlpUpdateChannel.fromKey(prefs.getString(KEY_YTDLP_CHANNEL, YtDlpUpdateChannel.STABLE.key))
    )
    val ytDlpUpdateChannel: StateFlow<YtDlpUpdateChannel> = _ytDlpUpdateChannel.asStateFlow()

    private val _categoryStates: Map<NotificationCategory, MutableStateFlow<Boolean>> =
        NotificationCategory.entries.associateWith { cat ->
            MutableStateFlow(prefs.getBoolean(cat.prefKey, cat.defaultEnabled))
        }

    fun getCategoryFlow(category: NotificationCategory): StateFlow<Boolean> {
        return _categoryStates[category] ?: MutableStateFlow(category.defaultEnabled)
    }

    fun isCategoryEnabled(category: NotificationCategory): Boolean {
        return _categoryStates[category]?.value ?: prefs.getBoolean(category.prefKey, category.defaultEnabled)
    }

    fun setCategoryEnabled(category: NotificationCategory, enabled: Boolean) {
        _categoryStates[category]?.value = enabled
        prefs.edit().putBoolean(category.prefKey, enabled).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    suspend fun changeThemeWithAnimation(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        try {
            _isTransitioning.value = true
            kotlinx.coroutines.delay(160) // Silky smooth blur & frosted veil flows in
            setThemeMode(mode)
            kotlinx.coroutines.delay(200) // Recomposition settles cleanly under blur
        } finally {
            _isTransitioning.value = false
        }
    }

    fun setStatusBarNotifications(enabled: Boolean) {
        _statusBarNotifications.value = enabled
        prefs.edit().putBoolean(KEY_STATUS_BAR_NOTIFS, enabled).apply()
    }

    fun setInAppNotifications(enabled: Boolean) {
        _inAppNotifications.value = enabled
        prefs.edit().putBoolean(KEY_IN_APP_NOTIFS, enabled).apply()
    }

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
        prefs.edit().putBoolean(KEY_NOTIF_SOUND, enabled).apply()
    }

    fun setVibrationEnabled(enabled: Boolean) {
        _vibrationEnabled.value = enabled
        prefs.edit().putBoolean(KEY_NOTIF_VIBRATION, enabled).apply()
    }

    fun setDaemonSyncEnabled(enabled: Boolean) {
        _daemonSyncEnabled.value = enabled
        prefs.edit().putBoolean(KEY_DAEMON_SYNC_ENABLED, enabled).apply()
    }

    fun setDaemonHostUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        _daemonHostUrl.value = clean
        prefs.edit().putString(KEY_DAEMON_HOST_URL, clean).apply()
    }

    fun setYtDlpAutoDownloadVideos(enabled: Boolean) {
        _ytDlpAutoDownloadVideos.value = enabled
        prefs.edit().putBoolean(KEY_YTDLP_AUTO_VIDEOS, enabled).apply()
    }

    fun setYtDlpWifiOnly(enabled: Boolean) {
        _ytDlpWifiOnly.value = enabled
        prefs.edit().putBoolean(KEY_YTDLP_WIFI_ONLY, enabled).apply()
    }

    fun setYtDlpQualityPreset(preset: YtDlpQualityPreset) {
        _ytDlpQualityPreset.value = preset
        prefs.edit().putString(KEY_YTDLP_QUALITY, preset.key).apply()
    }

    fun setYtDlpUpdateChannel(channel: YtDlpUpdateChannel) {
        _ytDlpUpdateChannel.value = channel
        prefs.edit().putString(KEY_YTDLP_CHANNEL, channel.key).apply()
    }

    fun isNotificationAllowed(eventType: String?): Boolean {
        if (!_statusBarNotifications.value) return false
        val cat = NotificationCategory.fromEventType(eventType)
        return isCategoryEnabled(cat)
    }

    fun isNotificationCategoryAllowed(category: NotificationCategory): Boolean {
        if (!_statusBarNotifications.value) return false
        return isCategoryEnabled(category)
    }

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return try {
            ThemeMode.valueOf(name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    private fun loadBoolean(key: String, default: Boolean): Boolean {
        return prefs.getBoolean(key, default)
    }

    companion object {
        const val KEY_THEME_MODE = "pref_theme_mode"
        const val KEY_STATUS_BAR_NOTIFS = "pref_status_bar_notifs"
        const val KEY_IN_APP_NOTIFS = "pref_in_app_notifs"
        const val KEY_NOTIF_SOUND = "pref_notif_sound"
        const val KEY_NOTIF_VIBRATION = "pref_notif_vibration"
        const val KEY_DAEMON_SYNC_ENABLED = "pref_daemon_sync_enabled"
        const val KEY_DAEMON_HOST_URL = "pref_daemon_host_url"
        const val KEY_YTDLP_AUTO_VIDEOS = "pref_ytdlp_auto_videos"
        const val KEY_YTDLP_WIFI_ONLY = "pref_ytdlp_wifi_only"
        const val KEY_YTDLP_QUALITY = "pref_ytdlp_quality"
        const val KEY_YTDLP_CHANNEL = "pref_ytdlp_channel"
        const val DEFAULT_DAEMON_URL = "http://127.0.0.1:3000"

        @Volatile
        private var INSTANCE: AppSettingsManager? = null

        fun getInstance(context: Context): AppSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun openSystemNotificationSettings(context: Context) {
            try {
                val intent = Intent().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        action = "android.settings.APP_NOTIFICATION_SETTINGS"
                        putExtra("app_package", context.packageName)
                        putExtra("app_uid", context.applicationInfo.uid)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {
                    // Safe fallback
                }
            }
        }
    }
}
