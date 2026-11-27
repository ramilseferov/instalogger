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
import com.instalogger.app.update.AppUpdateChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ArchiverSpeedPreset(
    val key: String,
    val downloadWorkers: Int,
    val targetWorkers: Int,
    val pageDelayMs: Long,
    val loopTickMs: Long
) {
    ECO("ECO", downloadWorkers = 2, targetWorkers = 1, pageDelayMs = 700L, loopTickMs = 8_000L),
    BALANCED("BALANCED", downloadWorkers = 4, targetWorkers = 2, pageDelayMs = 300L, loopTickMs = 5_000L),
    FAST("FAST", downloadWorkers = 6, targetWorkers = 3, pageDelayMs = 140L, loopTickMs = 3_000L),
    TURBO("TURBO", downloadWorkers = 10, targetWorkers = 4, pageDelayMs = 50L, loopTickMs = 2_000L);

    companion object {
        fun fromKey(key: String?): ArchiverSpeedPreset {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BALANCED
        }
    }
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
                "post_vanished",
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
                "story_posted",
                "story_added",
                "story_vanished",
                "highlight_added",
                "highlight_updated",
                "highlight_vanished",
                "highlight_item_vanished" -> ACTIVITY_UPDATES
                else -> SYSTEM_ALERTS
            }
        }
    }
}

enum class NotificationTreeGroup(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
) {
    FOLLOWERS(
        id = "followers",
        titleRes = R.string.notif_tree_group_followers,
        descRes = R.string.notif_tree_group_followers_desc,
    ),
    CONTENT(
        id = "content",
        titleRes = R.string.notif_tree_group_content,
        descRes = R.string.notif_tree_group_content_desc,
    ),
    PROFILE(
        id = "profile",
        titleRes = R.string.notif_tree_group_profile,
        descRes = R.string.notif_tree_group_profile_desc,
    ),
}

enum class NotificationEventKey(
    val id: String,
    val group: NotificationTreeGroup,
    val legacyCategory: NotificationCategory,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val defaultEnabled: Boolean = true,
) {
    FOLLOWERS_GAINED(
        id = "followers_gained",
        group = NotificationTreeGroup.FOLLOWERS,
        legacyCategory = NotificationCategory.ACTIVITY_UPDATES,
        titleRes = R.string.notif_leaf_followers_gained,
        descRes = R.string.notif_leaf_followers_gained_desc,
    ),
    FOLLOWERS_LOST(
        id = "followers_lost",
        group = NotificationTreeGroup.FOLLOWERS,
        legacyCategory = NotificationCategory.ACTIVITY_UPDATES,
        titleRes = R.string.notif_leaf_followers_lost,
        descRes = R.string.notif_leaf_followers_lost_desc,
    ),
    FOLLOWING_CHANGED(
        id = "following_changed",
        group = NotificationTreeGroup.FOLLOWERS,
        legacyCategory = NotificationCategory.ACTIVITY_UPDATES,
        titleRes = R.string.notif_leaf_following_changed,
        descRes = R.string.notif_leaf_following_changed_desc,
    ),
    FOLLOWER_SPIKE(
        id = "follower_spike",
        group = NotificationTreeGroup.FOLLOWERS,
        legacyCategory = NotificationCategory.ACTIVITY_UPDATES,
        titleRes = R.string.notif_leaf_follower_spike,
        descRes = R.string.notif_leaf_follower_spike_desc,
    ),
    NEW_POSTS(
        id = "new_posts",
        group = NotificationTreeGroup.CONTENT,
        legacyCategory = NotificationCategory.ACTIVITY_UPDATES,
        titleRes = R.string.notif_leaf_new_posts,
        descRes = R.string.notif_leaf_new_posts_desc,
    ),
    STORIES_ARCHIVED(
        id = "stories_archived",
        group = NotificationTreeGroup.CONTENT,
        legacyCategory = NotificationCategory.ACTIVITY_UPDATES,
        titleRes = R.string.notif_leaf_stories,
        descRes = R.string.notif_leaf_stories_desc,
    ),
    AVATAR_CHANGED(
        id = "avatar_changed",
        group = NotificationTreeGroup.PROFILE,
        legacyCategory = NotificationCategory.PROFILE_CHANGES,
        titleRes = R.string.notif_leaf_avatar,
        descRes = R.string.notif_leaf_avatar_desc,
    ),
    BIO_CHANGED(
        id = "bio_changed",
        group = NotificationTreeGroup.PROFILE,
        legacyCategory = NotificationCategory.PROFILE_CHANGES,
        titleRes = R.string.notif_leaf_bio,
        descRes = R.string.notif_leaf_bio_desc,
    ),
    NAME_CHANGED(
        id = "name_changed",
        group = NotificationTreeGroup.PROFILE,
        legacyCategory = NotificationCategory.PROFILE_CHANGES,
        titleRes = R.string.notif_leaf_name,
        descRes = R.string.notif_leaf_name_desc,
    ),
    PRIVACY_CHANGED(
        id = "privacy_changed",
        group = NotificationTreeGroup.PROFILE,
        legacyCategory = NotificationCategory.PRIVACY_ALERTS,
        titleRes = R.string.notif_leaf_privacy,
        descRes = R.string.notif_leaf_privacy_desc,
    );

    companion object {
        fun forGroup(group: NotificationTreeGroup): List<NotificationEventKey> =
            entries.filter { it.group == group }

        fun fromEventType(eventType: String?): NotificationEventKey? {
            return when (eventType?.lowercase()) {
                "follower_gained", "follower_count_change", "followers_change", "follower_pool_flush" -> FOLLOWERS_GAINED
                "follower_lost" -> FOLLOWERS_LOST
                "following_gained", "following_lost", "following_count_change", "following_change", "following_pool_flush" -> FOLLOWING_CHANGED
                "follower_spike" -> FOLLOWER_SPIKE
                "post_count_change", "new_post", "post_deleted", "post_vanished" -> NEW_POSTS
                "story_posted", "story_added", "story_vanished", "highlight_archived", "highlight_added", "highlight_updated", "highlight_vanished", "highlight_item_vanished" -> STORIES_ARCHIVED
                "avatar_change" -> AVATAR_CHANGED
                "bio_change" -> BIO_CHANGED
                "name_change" -> NAME_CHANGED
                "privacy_change" -> PRIVACY_CHANGED
                else -> null
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

    private val _archiverSpeedPreset = MutableStateFlow(
        ArchiverSpeedPreset.fromKey(prefs.getString(KEY_ARCHIVER_SPEED, ArchiverSpeedPreset.BALANCED.key))
    )
    val archiverSpeedPreset: StateFlow<ArchiverSpeedPreset> = _archiverSpeedPreset.asStateFlow()

    private val _keepAwakeEnabled = MutableStateFlow(loadBoolean(KEY_KEEP_AWAKE, true))
    val keepAwakeEnabled: StateFlow<Boolean> = _keepAwakeEnabled.asStateFlow()

    private val _autoArchivePendingEnabled = MutableStateFlow(loadBoolean(KEY_AUTO_ARCHIVE_PENDING, true))
    val autoArchivePendingEnabled: StateFlow<Boolean> = _autoArchivePendingEnabled.asStateFlow()

    private val _sessionWebViewAutoPreview = MutableStateFlow(loadBoolean(KEY_SESSION_WEBVIEW_AUTO_PREVIEW, false))
    val sessionWebViewAutoPreview: StateFlow<Boolean> = _sessionWebViewAutoPreview.asStateFlow()

    // 0 = Unlimited (sync all posts on profile, e.g., 300, 1000+)
    private val _maxPostsSyncLimit = MutableStateFlow(prefs.getInt(KEY_MAX_POSTS_SYNC_LIMIT, 0).coerceAtLeast(0))

    // In-App GitHub Update Settings
    private val _autoCheckUpdates = MutableStateFlow(loadBoolean(KEY_AUTO_CHECK_UPDATES, true))
    val autoCheckUpdates: StateFlow<Boolean> = _autoCheckUpdates.asStateFlow()

    private val _updateWifiOnly = MutableStateFlow(loadBoolean(KEY_UPDATE_WIFI_ONLY, false))
    val updateWifiOnly: StateFlow<Boolean> = _updateWifiOnly.asStateFlow()

    private val _updateAutoDownloadWifi = MutableStateFlow(loadBoolean(KEY_UPDATE_AUTO_DOWNLOAD_WIFI, false))
    val updateAutoDownloadWifi: StateFlow<Boolean> = _updateAutoDownloadWifi.asStateFlow()

    private val _updateChannel = MutableStateFlow(
        AppUpdateChannel.fromKey(prefs.getString(KEY_UPDATE_CHANNEL, AppUpdateChannel.STABLE.key))
    )
    val updateChannel: StateFlow<AppUpdateChannel> = _updateChannel.asStateFlow()

    fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, enabled).apply()
        _autoCheckUpdates.value = enabled
    }

    fun setUpdateWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_WIFI_ONLY, enabled).apply()
        _updateWifiOnly.value = enabled
    }

    fun setUpdateAutoDownloadWifi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_DOWNLOAD_WIFI, enabled).apply()
        _updateAutoDownloadWifi.value = enabled
    }

    fun setUpdateChannel(channel: AppUpdateChannel) {
        prefs.edit().putString(KEY_UPDATE_CHANNEL, channel.key).apply()
        _updateChannel.value = channel
    }
    val maxPostsSyncLimit: StateFlow<Int> = _maxPostsSyncLimit.asStateFlow()

    private val _categoryStates: Map<NotificationCategory, MutableStateFlow<Boolean>> =
        NotificationCategory.entries.associateWith { cat ->
            MutableStateFlow(prefs.getBoolean(cat.prefKey, cat.defaultEnabled))
        }

    private val _notifTreeRevision = MutableStateFlow(0)
    val notifTreeRevision: StateFlow<Int> = _notifTreeRevision.asStateFlow()

    fun getCategoryFlow(category: NotificationCategory): StateFlow<Boolean> {
        return _categoryStates[category] ?: MutableStateFlow(category.defaultEnabled)
    }

    fun isCategoryEnabled(category: NotificationCategory): Boolean {
        return _categoryStates[category]?.value ?: prefs.getBoolean(category.prefKey, category.defaultEnabled)
    }

    fun setCategoryEnabled(category: NotificationCategory, enabled: Boolean) {
        _categoryStates[category]?.value = enabled
        prefs.edit().putBoolean(category.prefKey, enabled).apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    private fun normalizeUserKey(username: String): String =
        username.trim().trimStart('@').lowercase()

    fun isEventKeyEnabled(key: NotificationEventKey): Boolean {
        val prefKey = "notif_leaf_${key.id}"
        if (prefs.contains(prefKey)) {
            return prefs.getBoolean(prefKey, key.defaultEnabled)
        }
        return isCategoryEnabled(key.legacyCategory)
    }

    fun setEventKeyEnabled(key: NotificationEventKey, enabled: Boolean) {
        prefs.edit().putBoolean("notif_leaf_${key.id}", enabled).apply()
        // Keep legacy category enabled if at least one leaf under it is enabled
        val anyEnabled = NotificationEventKey.entries
            .filter { it.legacyCategory == key.legacyCategory }
            .any { isEventKeyEnabled(it) }
        _categoryStates[key.legacyCategory]?.value = anyEnabled
        prefs.edit().putBoolean(key.legacyCategory.prefKey, anyEnabled).apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    fun setTreeGroupEnabled(group: NotificationTreeGroup, enabled: Boolean) {
        val editor = prefs.edit()
        val leaves = NotificationEventKey.forGroup(group)
        leaves.forEach { leaf ->
            editor.putBoolean("notif_leaf_${leaf.id}", enabled)
        }
        editor.apply()
        leaves.map { it.legacyCategory }.distinct().forEach { cat ->
            val anyEnabled = NotificationEventKey.entries
                .filter { it.legacyCategory == cat }
                .any { isEventKeyEnabled(it) }
            _categoryStates[cat]?.value = anyEnabled
            prefs.edit().putBoolean(cat.prefKey, anyEnabled).apply()
        }
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    fun hasTargetNotificationOverride(username: String): Boolean {
        val u = normalizeUserKey(username)
        return prefs.getBoolean("notif_user_${u}_custom", false)
    }

    fun setTargetNotificationOverrideEnabled(username: String, enabled: Boolean) {
        val u = normalizeUserKey(username)
        val editor = prefs.edit().putBoolean("notif_user_${u}_custom", enabled)
        if (enabled) {
            // Seed target custom leaves from current global defaults if never customized before
            NotificationEventKey.entries.forEach { leaf ->
                val k = "notif_user_${u}_leaf_${leaf.id}"
                if (!prefs.contains(k)) {
                    editor.putBoolean(k, isEventKeyEnabled(leaf))
                }
            }
        }
        editor.apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    fun isTargetNotificationsMuted(username: String): Boolean {
        val u = normalizeUserKey(username)
        return prefs.getBoolean("notif_user_${u}_muted", false)
    }

    fun setTargetNotificationsMuted(username: String, muted: Boolean) {
        val u = normalizeUserKey(username)
        prefs.edit().putBoolean("notif_user_${u}_muted", muted).apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    fun isTargetEventKeyEnabled(username: String, key: NotificationEventKey): Boolean {
        val u = normalizeUserKey(username)
        if (isTargetNotificationsMuted(u)) return false
        if (!hasTargetNotificationOverride(u)) return isEventKeyEnabled(key)
        val prefKey = "notif_user_${u}_leaf_${key.id}"
        return prefs.getBoolean(prefKey, isEventKeyEnabled(key))
    }

    fun setTargetEventKeyEnabled(username: String, key: NotificationEventKey, enabled: Boolean) {
        val u = normalizeUserKey(username)
        val editor = prefs.edit()
        if (!hasTargetNotificationOverride(u)) {
            editor.putBoolean("notif_user_${u}_custom", true)
            NotificationEventKey.entries.forEach { leaf ->
                val k = "notif_user_${u}_leaf_${leaf.id}"
                if (!prefs.contains(k)) {
                    editor.putBoolean(k, isEventKeyEnabled(leaf))
                }
            }
        }
        editor.putBoolean("notif_user_${u}_leaf_${key.id}", enabled)
        if (enabled) {
            editor.putBoolean("notif_user_${u}_muted", false)
        }
        editor.apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    fun setTargetTreeGroupEnabled(username: String, group: NotificationTreeGroup, enabled: Boolean) {
        val u = normalizeUserKey(username)
        val editor = prefs.edit()
        if (!hasTargetNotificationOverride(u)) {
            editor.putBoolean("notif_user_${u}_custom", true)
            NotificationEventKey.entries.forEach { leaf ->
                val k = "notif_user_${u}_leaf_${leaf.id}"
                if (!prefs.contains(k)) {
                    editor.putBoolean(k, isEventKeyEnabled(leaf))
                }
            }
        }
        NotificationEventKey.forGroup(group).forEach { leaf ->
            editor.putBoolean("notif_user_${u}_leaf_${leaf.id}", enabled)
        }
        if (enabled) {
            editor.putBoolean("notif_user_${u}_muted", false)
        }
        editor.apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
    }

    fun resetTargetNotificationOverrides(username: String) {
        val u = normalizeUserKey(username)
        val editor = prefs.edit()
            .remove("notif_user_${u}_custom")
            .remove("notif_user_${u}_muted")
        NotificationEventKey.entries.forEach { leaf ->
            editor.remove("notif_user_${u}_leaf_${leaf.id}")
        }
        editor.apply()
        _notifTreeRevision.value = _notifTreeRevision.value + 1
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

    fun setArchiverSpeedPreset(preset: ArchiverSpeedPreset) {
        _archiverSpeedPreset.value = preset
        prefs.edit().putString(KEY_ARCHIVER_SPEED, preset.key).apply()
    }

    fun setKeepAwakeEnabled(enabled: Boolean) {
        _keepAwakeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_KEEP_AWAKE, enabled).apply()
    }

    fun setAutoArchivePendingEnabled(enabled: Boolean) {
        _autoArchivePendingEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_ARCHIVE_PENDING, enabled).apply()
    }

    fun setSessionWebViewAutoPreview(enabled: Boolean) {
        _sessionWebViewAutoPreview.value = enabled
        prefs.edit().putBoolean(KEY_SESSION_WEBVIEW_AUTO_PREVIEW, enabled).apply()
    }

    fun setMaxPostsSyncLimit(limit: Int) {
        val safe = limit.coerceAtLeast(0)
        _maxPostsSyncLimit.value = safe
        prefs.edit().putInt(KEY_MAX_POSTS_SYNC_LIMIT, safe).apply()
    }

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

    fun isNotificationAllowed(eventType: String?, username: String? = null): Boolean {
        if (!_statusBarNotifications.value) return false
        return isEventAllowedForTarget(eventType, username)
    }

    fun isInAppNotificationAllowed(eventType: String?, username: String? = null): Boolean {
        if (!_inAppNotifications.value) return false
        return isEventAllowedForTarget(eventType, username)
    }

    fun isEventAllowedForTarget(eventType: String?, username: String? = null): Boolean {
        val leaf = NotificationEventKey.fromEventType(eventType)
        if (!username.isNullOrBlank()) {
            val u = normalizeUserKey(username)
            if (isTargetNotificationsMuted(u)) return false
            if (leaf != null) return isTargetEventKeyEnabled(u, leaf)
        }
        if (leaf != null) return isEventKeyEnabled(leaf)
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
        const val KEY_ARCHIVER_SPEED = "pref_archiver_speed_preset"
        const val KEY_KEEP_AWAKE = "pref_keep_awake_wakelock"
        const val KEY_AUTO_ARCHIVE_PENDING = "pref_auto_archive_pending"
        const val KEY_SESSION_WEBVIEW_AUTO_PREVIEW = "pref_session_webview_auto_preview"
        const val KEY_MAX_POSTS_SYNC_LIMIT = "pref_max_posts_sync_limit"
        const val KEY_AUTO_CHECK_UPDATES = "pref_auto_check_updates"
        const val KEY_UPDATE_WIFI_ONLY = "pref_update_wifi_only"
        const val KEY_UPDATE_AUTO_DOWNLOAD_WIFI = "pref_update_auto_download_wifi"
        const val KEY_UPDATE_CHANNEL = "pref_update_channel"
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
                }
            }
        }
    }
}
