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
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.engine.media.YtDlpQualityPreset
import com.instalogger.app.engine.media.YtDlpUpdateChannel
import com.instalogger.app.engine.pool.FollowerPoolMode
import com.instalogger.app.engine.pool.FollowerPoolPolicy
import com.instalogger.app.engine.pool.TargetPoolOverride
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
                "post_count_change",
                "follower_count_change",
                "following_count_change",
                "followers_change",
                "following_change",
                "follower_pool_flush",
                "following_pool_flush",
                "follower_spike",
                "follower_gained",
                "follower_lost",
                "following_gained",
                "following_lost",
                "story_posted" -> ACTIVITY_UPDATES
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

    // Follower Change Pool & High-Velocity Protection Global Controls
    private val _poolMode = MutableStateFlow(
        FollowerPoolMode.fromKey(prefs.getString(KEY_POOL_MODE, FollowerPoolMode.OFF.key))
    )
    val poolMode: StateFlow<FollowerPoolMode> = _poolMode.asStateFlow()

    private val _poolFixedThreshold = MutableStateFlow(
        prefs.getLong(KEY_POOL_FIXED_THRESHOLD, DEFAULT_POOL_FIXED_THRESHOLD).coerceAtLeast(1L)
    )
    val poolFixedThreshold: StateFlow<Long> = _poolFixedThreshold.asStateFlow()

    private val _poolPercentThreshold = MutableStateFlow(
        prefs.getFloat(KEY_POOL_PERCENT_THRESHOLD, DEFAULT_POOL_PERCENT_THRESHOLD).toDouble().coerceIn(0.01, 100.0)
    )
    val poolPercentThreshold: StateFlow<Double> = _poolPercentThreshold.asStateFlow()

    private val _poolApplyToFollowing = MutableStateFlow(loadBoolean(KEY_POOL_APPLY_FOLLOWING, false))
    val poolApplyToFollowing: StateFlow<Boolean> = _poolApplyToFollowing.asStateFlow()

    private val _poolDigestWindowMinutes = MutableStateFlow(
        prefs.getInt(KEY_POOL_DIGEST_MINUTES, 0).coerceAtLeast(0)
    )
    val poolDigestWindowMinutes: StateFlow<Int> = _poolDigestWindowMinutes.asStateFlow()

    private val _poolCooldownMinutes = MutableStateFlow(
        prefs.getInt(KEY_POOL_COOLDOWN_MINUTES, 0).coerceAtLeast(0)
    )
    val poolCooldownMinutes: StateFlow<Int> = _poolCooldownMinutes.asStateFlow()

    private val _poolAntiFlickerEnabled = MutableStateFlow(loadBoolean(KEY_POOL_ANTI_FLICKER, true))
    val poolAntiFlickerEnabled: StateFlow<Boolean> = _poolAntiFlickerEnabled.asStateFlow()

    private val _poolSpikeAlertEnabled = MutableStateFlow(loadBoolean(KEY_POOL_SPIKE_ALERT, true))
    val poolSpikeAlertEnabled: StateFlow<Boolean> = _poolSpikeAlertEnabled.asStateFlow()

    private val _poolSpikeMultiplier = MutableStateFlow(
        prefs.getFloat(KEY_POOL_SPIKE_MULTIPLIER, DEFAULT_POOL_SPIKE_MULTIPLIER).toDouble().coerceIn(1.5, 20.0)
    )
    val poolSpikeMultiplier: StateFlow<Double> = _poolSpikeMultiplier.asStateFlow()

    private val _poolSuppressMinorAuditLogs = MutableStateFlow(loadBoolean(KEY_POOL_SUPPRESS_MINOR_LOGS, true))
    val poolSuppressMinorAuditLogs: StateFlow<Boolean> = _poolSuppressMinorAuditLogs.asStateFlow()

    private val _listCompletenessGuardEnabled = MutableStateFlow(loadBoolean(KEY_LIST_COMPLETENESS_GUARD, true))
    val listCompletenessGuardEnabled: StateFlow<Boolean> = _listCompletenessGuardEnabled.asStateFlow()

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

    fun setPoolMode(mode: FollowerPoolMode) {
        _poolMode.value = mode
        prefs.edit().putString(KEY_POOL_MODE, mode.key).apply()
    }

    fun setPoolFixedThreshold(value: Long) {
        val safe = value.coerceIn(1L, 1_000_000L)
        _poolFixedThreshold.value = safe
        prefs.edit().putLong(KEY_POOL_FIXED_THRESHOLD, safe).apply()
    }

    fun setPoolPercentThreshold(percent: Double) {
        val safe = percent.coerceIn(0.01, 100.0)
        _poolPercentThreshold.value = safe
        prefs.edit().putFloat(KEY_POOL_PERCENT_THRESHOLD, safe.toFloat()).apply()
    }

    fun setPoolApplyToFollowing(enabled: Boolean) {
        _poolApplyToFollowing.value = enabled
        prefs.edit().putBoolean(KEY_POOL_APPLY_FOLLOWING, enabled).apply()
    }

    fun setPoolDigestWindowMinutes(minutes: Int) {
        val safe = minutes.coerceAtLeast(0)
        _poolDigestWindowMinutes.value = safe
        prefs.edit().putInt(KEY_POOL_DIGEST_MINUTES, safe).apply()
    }

    fun setPoolCooldownMinutes(minutes: Int) {
        val safe = minutes.coerceAtLeast(0)
        _poolCooldownMinutes.value = safe
        prefs.edit().putInt(KEY_POOL_COOLDOWN_MINUTES, safe).apply()
    }

    fun setPoolAntiFlickerEnabled(enabled: Boolean) {
        _poolAntiFlickerEnabled.value = enabled
        prefs.edit().putBoolean(KEY_POOL_ANTI_FLICKER, enabled).apply()
    }

    fun setPoolSpikeAlertEnabled(enabled: Boolean) {
        _poolSpikeAlertEnabled.value = enabled
        prefs.edit().putBoolean(KEY_POOL_SPIKE_ALERT, enabled).apply()
    }

    fun setPoolSpikeMultiplier(multiplier: Double) {
        val safe = multiplier.coerceIn(1.5, 20.0)
        _poolSpikeMultiplier.value = safe
        prefs.edit().putFloat(KEY_POOL_SPIKE_MULTIPLIER, safe.toFloat()).apply()
    }

    fun setPoolSuppressMinorAuditLogs(enabled: Boolean) {
        _poolSuppressMinorAuditLogs.value = enabled
        prefs.edit().putBoolean(KEY_POOL_SUPPRESS_MINOR_LOGS, enabled).apply()
    }

    fun setListCompletenessGuardEnabled(enabled: Boolean) {
        _listCompletenessGuardEnabled.value = enabled
        prefs.edit().putBoolean(KEY_LIST_COMPLETENESS_GUARD, enabled).apply()
    }

    /**
     * Resolves the effective FollowerPoolPolicy by combining global defaults with per-target overrides.
     */
    fun resolvePoolPolicy(target: TargetEntity?, isFollowing: Boolean = false): FollowerPoolPolicy {
        val override = TargetPoolOverride.fromKey(target?.poolOverrideMode)
        val applyToFollowing = when (target?.poolApplyToFollowing ?: -1) {
            1 -> true
            0 -> false
            else -> _poolApplyToFollowing.value
        }

        val resolvedMode = when (override) {
            TargetPoolOverride.GLOBAL -> _poolMode.value
            TargetPoolOverride.OFF -> FollowerPoolMode.OFF
            TargetPoolOverride.FIXED_COUNT -> FollowerPoolMode.FIXED_COUNT
            TargetPoolOverride.PERCENTAGE -> FollowerPoolMode.PERCENTAGE
            TargetPoolOverride.AUTO_ADAPTIVE -> FollowerPoolMode.AUTO_ADAPTIVE
        }

        val finalMode = if (isFollowing && !applyToFollowing) FollowerPoolMode.OFF else resolvedMode
        val useCustom = override != TargetPoolOverride.GLOBAL && override != TargetPoolOverride.OFF
        val fixed = if (useCustom && target != null) target.poolCustomThreshold.coerceAtLeast(1L) else _poolFixedThreshold.value
        val percent = if (useCustom && target != null) target.poolCustomPercent.coerceIn(0.01, 100.0) else _poolPercentThreshold.value
        val digest = if (target != null && target.poolCustomDigestMinutes >= 0) target.poolCustomDigestMinutes else _poolDigestWindowMinutes.value
        val cooldown = if (target != null && target.poolCustomCooldownMinutes >= 0) target.poolCustomCooldownMinutes else _poolCooldownMinutes.value

        return FollowerPoolPolicy(
            mode = finalMode,
            fixedThreshold = fixed,
            percentThreshold = percent,
            digestWindowMinutes = digest,
            cooldownMinutes = cooldown,
            antiFlickerEnabled = _poolAntiFlickerEnabled.value,
            spikeAlertEnabled = _poolSpikeAlertEnabled.value,
            spikeMultiplier = _poolSpikeMultiplier.value,
            suppressMinorAuditLogs = _poolSuppressMinorAuditLogs.value
        )
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
        const val KEY_POOL_MODE = "pref_pool_mode"
        const val KEY_POOL_FIXED_THRESHOLD = "pref_pool_fixed_threshold"
        const val KEY_POOL_PERCENT_THRESHOLD = "pref_pool_percent_threshold"
        const val KEY_POOL_APPLY_FOLLOWING = "pref_pool_apply_following"
        const val KEY_POOL_DIGEST_MINUTES = "pref_pool_digest_minutes"
        const val KEY_POOL_COOLDOWN_MINUTES = "pref_pool_cooldown_minutes"
        const val KEY_POOL_ANTI_FLICKER = "pref_pool_anti_flicker"
        const val KEY_POOL_SPIKE_ALERT = "pref_pool_spike_alert"
        const val KEY_POOL_SPIKE_MULTIPLIER = "pref_pool_spike_multiplier"
        const val KEY_POOL_SUPPRESS_MINOR_LOGS = "pref_pool_suppress_minor_logs"
        const val KEY_LIST_COMPLETENESS_GUARD = "pref_list_completeness_guard"
        const val DEFAULT_POOL_FIXED_THRESHOLD = 50L
        const val DEFAULT_POOL_PERCENT_THRESHOLD = 0.5f
        const val DEFAULT_POOL_SPIKE_MULTIPLIER = 3.0f
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
