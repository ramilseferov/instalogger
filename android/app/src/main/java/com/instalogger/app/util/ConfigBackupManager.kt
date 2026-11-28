package com.instalogger.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.instalogger.app.BuildConfig
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.engine.media.YtDlpQualityPreset
import com.instalogger.app.engine.media.YtDlpUpdateChannel
import com.instalogger.app.engine.pool.FollowerPoolMode
import com.instalogger.app.engine.pool.TargetPoolOverride
import com.instalogger.app.settings.ArchiverSpeedPreset
import com.instalogger.app.settings.NotificationCategory
import com.instalogger.app.settings.NotificationEventKey
import com.instalogger.app.settings.ThemeMode
import com.instalogger.app.update.AppUpdateChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ConfigBackupManager {

    private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024 // 2 MiB hard cap
    private const val MAX_IMPORT_TARGETS = 2000
    private val USERNAME_REGEX = Regex("^[a-zA-Z0-9._]{1,30}$")

    enum class BackupKind {
        FULL_BACKUP_JSON,
        TARGETS_JSON,
        TARGETS_CSV
    }

    data class ImportedPoolOverride(
        val overrideMode: String = "GLOBAL",
        val customThreshold: Long = 50L,
        val customPercent: Double = 0.5,
        val customDigestMinutes: Int = -1,
        val customCooldownMinutes: Int = -1,
        val applyToFollowing: Int = -1
    )

    data class ImportedTarget(
        val username: String,
        val instagramId: String? = null,
        val fullName: String = "",
        val isPrivate: Boolean = false,
        val isActive: Boolean = true,
        val isTurbo: Boolean = false,
        val checkIntervalSeconds: Long = 60L,
        val followersCount: Long = 0L,
        val followingCount: Long = 0L,
        val mediaCount: Long = 0L,
        val poolOverride: ImportedPoolOverride? = null
    )

    data class ImportPreview(
        val fileName: String,
        val kind: BackupKind,
        val source: String?,
        val exportedAt: String?,
        val targets: List<ImportedTarget>,
        val newTargetsCount: Int,
        val existingTargetsCount: Int,
        val settingsObj: JSONObject?,
        val settingsKeyCount: Int,
        val notificationSettingsObj: JSONObject?,
        val notificationRuleCount: Int,
        val appearanceTheme: ThemeMode?,
        val appearanceLanguage: String?,
        val warnings: List<String> = emptyList()
    ) {
        val hasAppearance: Boolean
            get() = appearanceTheme != null || appearanceLanguage != null
    }

    data class ImportOptions(
        val importTargets: Boolean = true,
        val importSettings: Boolean = true,
        val importNotificationRules: Boolean = true,
        val importAppearance: Boolean = true,
        val mergeExistingTargets: Boolean = true
    )

    data class ImportResult(
        val targetsAdded: Int,
        val targetsUpdated: Int,
        val targetsSkipped: Int,
        val settingsApplied: Int,
        val rulesApplied: Int,
        val appearanceApplied: Boolean
    )

    fun buildSuggestedFileName(prefix: String, ext: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        return "${prefix}-${fmt.format(Date())}.$ext"
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date())
    }

    private fun sanitizeUsername(raw: String?): String? {
        val clean = raw?.trim()?.trimStart('@')?.lowercase(Locale.ROOT) ?: return null
        return if (USERNAME_REGEX.matches(clean)) clean else null
    }

    private fun sanitizeCsvCell(value: String, protectFormula: Boolean): String {
        val cleaned = value.replace("\r", " ").replace("\n", " ")
        val safe = if (
            protectFormula &&
            cleaned.isNotEmpty() &&
            cleaned[0] in charArrayOf('=', '+', '-', '@', '\t')
        ) {
            "'$cleaned"
        } else {
            cleaned
        }
        return if (safe.contains(',') || safe.contains('"') || safe.contains('\n') || safe.contains('\r')) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else {
            safe
        }
    }

    private fun targetToJson(target: TargetEntity): JSONObject {
        val poolObj = JSONObject().apply {
            put("override_mode", target.poolOverrideMode)
            put("custom_threshold", target.poolCustomThreshold)
            put("custom_percent", target.poolCustomPercent)
            put("custom_digest_minutes", target.poolCustomDigestMinutes)
            put("custom_cooldown_minutes", target.poolCustomCooldownMinutes)
            put("apply_to_following", target.poolApplyToFollowing)
        }
        return JSONObject().apply {
            put("username", target.username)
            put("instagram_id", target.instagramId.orEmpty())
            put("full_name", target.fullName)
            put("is_private", target.isPrivate)
            put("status", if (target.isActive) "active" else "paused")
            put("is_active", target.isActive)
            put("turbo_mode", target.isTurbo)
            put("check_interval_seconds", target.checkIntervalSeconds)
            put("followers_count", target.followersCount)
            put("following_count", target.followingCount)
            put("media_count", target.mediaCount)
            put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(target.createdAt)))
            put("pool_override", poolObj)
        }
    }

    fun exportTargetsCsv(targets: List<TargetEntity>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("username,full_name,instagram_id,is_private,status,turbo_mode,check_interval_seconds,followers_count,following_count,media_count,pool_override_mode,pool_custom_threshold,pool_custom_percent,created_at\n")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (t in targets) {
            val cols = listOf(
                sanitizeCsvCell(t.username, false),
                sanitizeCsvCell(t.fullName, true),
                sanitizeCsvCell(t.instagramId.orEmpty(), true),
                t.isPrivate.toString(),
                if (t.isActive) "active" else "paused",
                t.isTurbo.toString(),
                t.checkIntervalSeconds.toString(),
                t.followersCount.toString(),
                t.followingCount.toString(),
                t.mediaCount.toString(),
                t.poolOverrideMode,
                t.poolCustomThreshold.toString(),
                t.poolCustomPercent.toString(),
                sanitizeCsvCell(isoFmt.format(Date(t.createdAt)), false)
            )
            sb.append(cols.joinToString(",")).append('\n')
        }
        return sb.toString()
    }

    fun exportTargetsJson(targets: List<TargetEntity>): String {
        val arr = JSONArray()
        targets.forEach { arr.put(targetToJson(it)) }
        val root = JSONObject().apply {
            put("schema", "instalogger.targets")
            put("version", 2)
            put("exported_at", isoNow())
            put("source", "android")
            put("app_version", BuildConfig.VERSION_NAME)
            put("count", targets.size)
            put("targets", arr)
        }
        return root.toString(2)
    }

    fun exportFullBackupJson(
        context: Context,
        app: InstaLoggerApp,
        targets: List<TargetEntity>
    ): String {
        val sm = app.settingsManager
        val lang = LanguageManager.currentLanguage.value

        val appearance = JSONObject().apply {
            put("theme_mode", sm.themeMode.value.name.lowercase(Locale.ROOT))
            put("language", lang)
        }

        val settingsObj = JSONObject().apply {
            put("notification_language", lang)
            put("pool_mode", sm.poolMode.value.key)
            put("pool_fixed_threshold", sm.poolFixedThreshold.value)
            put("pool_percent_threshold", sm.poolPercentThreshold.value)
            put("pool_digest_minutes", sm.poolDigestWindowMinutes.value)
            put("pool_cooldown_minutes", sm.poolCooldownMinutes.value)
            put("pool_anti_flicker", sm.poolAntiFlickerEnabled.value)
            put("pool_spike_alert", sm.poolSpikeAlertEnabled.value)
            put("pool_spike_multiplier", sm.poolSpikeMultiplier.value)
            put("pool_apply_following", sm.poolApplyToFollowing.value)
            put("pool_suppress_logs", sm.poolSuppressMinorAuditLogs.value)
            put("list_completeness_guard", sm.listCompletenessGuardEnabled.value)
            put("archiver_speed_preset", sm.archiverSpeedPreset.value.key)
            put("download_workers", sm.archiverSpeedPreset.value.downloadWorkers)
            put("max_posts_sync_limit", sm.maxPostsSyncLimit.value)
            put("yt_dlp_enabled", sm.ytDlpAutoDownloadVideos.value)
            put("yt_dlp_quality", sm.ytDlpQualityPreset.value.key)
            put("yt_dlp_wifi_only", sm.ytDlpWifiOnly.value)
            put("yt_dlp_channel", sm.ytDlpUpdateChannel.value.key)
            put("auto_download_media", sm.autoArchivePendingEnabled.value)
            put("keep_awake_enabled", sm.keepAwakeEnabled.value)
            put("auto_archive_pending", sm.autoArchivePendingEnabled.value)
            put("session_webview_auto_preview", sm.sessionWebViewAutoPreview.value)
            put("daemon_sync_enabled", sm.daemonSyncEnabled.value)
            put("daemon_host_url", sm.daemonHostUrl.value)
            put("auto_check_updates", sm.autoCheckUpdates.value)
            put("update_wifi_only", sm.updateWifiOnly.value)
            put("update_auto_download_wifi", sm.updateAutoDownloadWifi.value)
            put("update_channel", sm.updateChannel.value.key)
        }

        val categoriesObj = JSONObject()
        NotificationCategory.entries.forEach { cat ->
            categoriesObj.put(cat.id, sm.isCategoryEnabled(cat))
        }

        val globalEventsObj = JSONObject()
        NotificationEventKey.entries.forEach { leaf ->
            globalEventsObj.put(leaf.id, sm.isEventKeyEnabled(leaf))
        }

        val targetOverridesObj = JSONObject()
        val webRulesArr = JSONArray()

        // Global rule for cross-platform compatibility with Web dashboard
        webRulesArr.put(
            JSONObject().apply {
                put("scope_key", "global")
                put("enabled", sm.statusBarNotifications.value)
                put("privacy_change", sm.isEventKeyEnabled(NotificationEventKey.PRIVACY_CHANGED))
                put("bio_change", sm.isEventKeyEnabled(NotificationEventKey.BIO_CHANGED))
                put("avatar_change", sm.isEventKeyEnabled(NotificationEventKey.AVATAR_CHANGED))
                put("post_count_change", sm.isEventKeyEnabled(NotificationEventKey.NEW_POSTS))
                put("post_vanished", sm.isEventKeyEnabled(NotificationEventKey.NEW_POSTS))
                put("story_added", sm.isEventKeyEnabled(NotificationEventKey.STORIES_ARCHIVED))
                put(
                    "followers_change",
                    sm.isEventKeyEnabled(NotificationEventKey.FOLLOWERS_GAINED) ||
                        sm.isEventKeyEnabled(NotificationEventKey.FOLLOWERS_LOST)
                )
                put("following_change", sm.isEventKeyEnabled(NotificationEventKey.FOLLOWING_CHANGED))
                put("quiet_start", JSONObject.NULL)
                put("quiet_end", JSONObject.NULL)
            }
        )

        targets.forEach { target ->
            val muted = sm.isTargetNotificationsMuted(target.username)
            val custom = sm.hasTargetNotificationOverride(target.username)
            if (muted || custom) {
                val evs = JSONObject()
                NotificationEventKey.entries.forEach { leaf ->
                    evs.put(leaf.id, sm.isTargetEventKeyEnabled(target.username, leaf))
                }
                targetOverridesObj.put(
                    target.username,
                    JSONObject().apply {
                        put("muted", muted)
                        put("custom", custom)
                        put("events", evs)
                    }
                )
                webRulesArr.put(
                    JSONObject().apply {
                        put("scope_key", target.username)
                        put("enabled", !muted)
                        put("privacy_change", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.PRIVACY_CHANGED))
                        put("bio_change", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.BIO_CHANGED))
                        put("avatar_change", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.AVATAR_CHANGED))
                        put("post_count_change", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.NEW_POSTS))
                        put("post_vanished", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.NEW_POSTS))
                        put("story_added", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.STORIES_ARCHIVED))
                        put(
                            "followers_change",
                            sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.FOLLOWERS_GAINED) ||
                                sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.FOLLOWERS_LOST)
                        )
                        put("following_change", sm.isTargetEventKeyEnabled(target.username, NotificationEventKey.FOLLOWING_CHANGED))
                        put("quiet_start", JSONObject.NULL)
                        put("quiet_end", JSONObject.NULL)
                    }
                )
            }
        }

        val notifSettingsObj = JSONObject().apply {
            put("status_bar_notifications", sm.statusBarNotifications.value)
            put("in_app_notifications", sm.inAppNotifications.value)
            put("sound_enabled", sm.soundEnabled.value)
            put("vibration_enabled", sm.vibrationEnabled.value)
            put("categories", categoriesObj)
            put("global_events", globalEventsObj)
            put("target_overrides", targetOverridesObj)
            put("rules", webRulesArr)
        }

        val targetsArr = JSONArray()
        targets.forEach { targetsArr.put(targetToJson(it)) }

        val root = JSONObject().apply {
            put("schema", "instalogger.backup")
            put("version", 2)
            put("exported_at", isoNow())
            put("source", "android")
            put("app_version", BuildConfig.VERSION_NAME)
            put("includes_secrets", false)
            put("appearance", appearance)
            put("settings", settingsObj)
            put("notification_settings", notifSettingsObj)
            put("targets", targetsArr)
        }
        return root.toString(2)
    }

    fun inspectImport(
        rawText: String,
        fileName: String,
        existingTargets: List<TargetEntity>
    ): ImportPreview? {
        if (rawText.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) return null
        val trimmed = rawText.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return null

        val existingSet = existingTargets.map { it.username.lowercase(Locale.ROOT) }.toSet()
        val warnings = mutableListOf<String>()

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return runCatching {
                val parsedTargets = mutableListOf<ImportedTarget>()
                val seen = HashSet<String>()

                fun addTargetFromAny(item: Any?) {
                    if (parsedTargets.size >= MAX_IMPORT_TARGETS) return
                    val imported = parseJsonTarget(item)
                    if (imported != null && seen.add(imported.username)) {
                        parsedTargets.add(imported)
                    } else if (imported == null) {
                        warnings.add("Skipped invalid target entry")
                    }
                }

                if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        addTargetFromAny(arr.opt(i))
                    }
                    if (parsedTargets.isEmpty()) return null
                    val existingCount = parsedTargets.count { it.username in existingSet }
                    return ImportPreview(
                        fileName = fileName,
                        kind = BackupKind.TARGETS_JSON,
                        source = null,
                        exportedAt = null,
                        targets = parsedTargets,
                        newTargetsCount = parsedTargets.size - existingCount,
                        existingTargetsCount = existingCount,
                        settingsObj = null,
                        settingsKeyCount = 0,
                        notificationSettingsObj = null,
                        notificationRuleCount = 0,
                        appearanceTheme = null,
                        appearanceLanguage = null,
                        warnings = warnings
                    )
                }

                val root = JSONObject(trimmed)
                val tArr = root.optJSONArray("targets")
                if (tArr != null) {
                    for (i in 0 until tArr.length()) {
                        addTargetFromAny(tArr.opt(i))
                    }
                }

                val settingsObj = root.optJSONObject("settings")
                val settingsCount = settingsObj?.length() ?: 0

                val notifObj = root.optJSONObject("notification_settings")
                var notifCount = 0
                if (notifObj != null) {
                    notifObj.optJSONObject("global_events")?.let { notifCount += it.length() }
                    notifObj.optJSONObject("target_overrides")?.let { notifCount += it.length() }
                    if (notifCount == 0) {
                        notifObj.optJSONArray("rules")?.let { notifCount += it.length() }
                    }
                } else {
                    root.optJSONArray("notification_rules")?.let { notifCount += it.length() }
                }

                val appearanceObj = root.optJSONObject("appearance")
                val themeMode = appearanceObj?.optString("theme_mode", "")?.let { raw ->
                    when (raw.trim().lowercase(Locale.ROOT)) {
                        "light" -> ThemeMode.LIGHT
                        "dark" -> ThemeMode.DARK
                        "system" -> ThemeMode.SYSTEM
                        else -> null
                    }
                }
                val lang = appearanceObj?.optString("language", "")?.let {
                    LanguageManager.normalizeLanguageCode(it)
                }

                val isFull = root.optString("schema") == "instalogger.backup" ||
                    settingsCount > 0 || notifCount > 0 || themeMode != null || lang != null

                if (!isFull && parsedTargets.isEmpty()) return null

                val existingCount = parsedTargets.count { it.username in existingSet }
                val effectiveNotifObj = notifObj ?: root.optJSONArray("notification_rules")?.let {
                    JSONObject().put("rules", it)
                }

                ImportPreview(
                    fileName = fileName,
                    kind = if (isFull) BackupKind.FULL_BACKUP_JSON else BackupKind.TARGETS_JSON,
                    source = root.optString("source").takeIf { it.isNotBlank() },
                    exportedAt = root.optString("exported_at").takeIf { it.isNotBlank() },
                    targets = parsedTargets,
                    newTargetsCount = parsedTargets.size - existingCount,
                    existingTargetsCount = existingCount,
                    settingsObj = settingsObj,
                    settingsKeyCount = settingsCount,
                    notificationSettingsObj = effectiveNotifObj,
                    notificationRuleCount = notifCount,
                    appearanceTheme = themeMode,
                    appearanceLanguage = lang,
                    warnings = warnings
                )
            }.getOrNull()
        }

        // CSV parser
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val firstCols = parseCsvLine(lines.first()).map { it.lowercase(Locale.ROOT) }
        val headerMap = firstCols.withIndex().associate { (idx, name) -> name to idx }
        val usernameColIdx = headerMap["username"] ?: headerMap["handle"] ?: headerMap["account"]
        val startIdx = if (usernameColIdx != null) 1 else 0
        val uIdx = usernameColIdx ?: 0

        val parsedTargets = mutableListOf<ImportedTarget>()
        val seen = HashSet<String>()

        for (i in startIdx until lines.size) {
            if (parsedTargets.size >= MAX_IMPORT_TARGETS) break
            val cols = parseCsvLine(lines[i])
            val username = sanitizeUsername(cols.getOrNull(uIdx)) ?: continue
            if (!seen.add(username)) continue

            fun col(vararg names: String): String? {
                for (n in names) {
                    val idx = headerMap[n] ?: continue
                    val value = cols.getOrNull(idx)
                    if (!value.isNullOrBlank()) return value
                }
                return null
            }

            val poolMode = col("pool_override_mode", "pool_mode")?.let {
                TargetPoolOverride.fromKey(it).key
            }
            val poolOverride = if (poolMode != null) {
                ImportedPoolOverride(
                    overrideMode = poolMode,
                    customThreshold = (col("pool_custom_threshold")?.toLongOrNull() ?: 50L).coerceIn(1L, 1_000_000L),
                    customPercent = (col("pool_custom_percent")?.toDoubleOrNull() ?: 0.5).coerceIn(0.01, 100.0)
                )
            } else null

            parsedTargets.add(
                ImportedTarget(
                    username = username,
                    instagramId = col("instagram_id", "id")?.takeIf { it.isNotBlank() },
                    fullName = col("full_name", "name").orEmpty().trimStart('\'').take(120),
                    isPrivate = parseBool(col("is_private"), false),
                    isActive = !col("status").equals("paused", ignoreCase = true),
                    isTurbo = parseBool(col("turbo_mode", "is_turbo", "turbo"), false),
                    checkIntervalSeconds = (col("check_interval_seconds", "interval")?.toLongOrNull() ?: 60L).coerceIn(30L, 86400L),
                    followersCount = (col("followers_count")?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                    followingCount = (col("following_count")?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                    mediaCount = (col("media_count")?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                    poolOverride = poolOverride
                )
            )
        }

        if (parsedTargets.isEmpty()) return null
        val existingCount = parsedTargets.count { it.username in existingSet }
        return ImportPreview(
            fileName = fileName,
            kind = BackupKind.TARGETS_CSV,
            source = null,
            exportedAt = null,
            targets = parsedTargets,
            newTargetsCount = parsedTargets.size - existingCount,
            existingTargetsCount = existingCount,
            settingsObj = null,
            settingsKeyCount = 0,
            notificationSettingsObj = null,
            notificationRuleCount = 0,
            appearanceTheme = null,
            appearanceLanguage = null,
            warnings = warnings
        )
    }

    suspend fun applyImport(
        context: Context,
        app: InstaLoggerApp,
        preview: ImportPreview,
        options: ImportOptions
    ): ImportResult {
        var added = 0
        var updated = 0
        var skipped = 0
        var settingsApplied = 0
        var rulesApplied = 0
        var appearanceApplied = false

        val dao = app.database.targetDao()
        val sm = app.settingsManager
        val nowMs = System.currentTimeMillis()

        // 1. Targets
        if (options.importTargets && preview.targets.isNotEmpty()) {
            for (item in preview.targets) {
                val existing = dao.getTarget(item.username)
                if (existing != null) {
                    if (!options.mergeExistingTargets) {
                        skipped++
                        continue
                    }
                    val pool = item.poolOverride
                    dao.updateTarget(
                        existing.copy(
                            isTurbo = item.isTurbo,
                            isActive = item.isActive,
                            checkIntervalSeconds = item.checkIntervalSeconds.coerceIn(30L, 86400L),
                            poolOverrideMode = pool?.overrideMode ?: existing.poolOverrideMode,
                            poolCustomThreshold = pool?.customThreshold ?: existing.poolCustomThreshold,
                            poolCustomPercent = pool?.customPercent ?: existing.poolCustomPercent,
                            poolCustomDigestMinutes = pool?.customDigestMinutes ?: existing.poolCustomDigestMinutes,
                            poolCustomCooldownMinutes = pool?.customCooldownMinutes ?: existing.poolCustomCooldownMinutes,
                            poolApplyToFollowing = pool?.applyToFollowing ?: existing.poolApplyToFollowing
                        )
                    )
                    updated++
                } else {
                    val pool = item.poolOverride
                    dao.insertTarget(
                        TargetEntity(
                            username = item.username,
                            instagramId = item.instagramId?.take(64),
                            fullName = item.fullName.take(120),
                            isPrivate = item.isPrivate,
                            followersCount = item.followersCount.coerceAtLeast(0L),
                            followingCount = item.followingCount.coerceAtLeast(0L),
                            mediaCount = item.mediaCount.coerceAtLeast(0L),
                            isTurbo = item.isTurbo,
                            isActive = item.isActive,
                            checkIntervalSeconds = item.checkIntervalSeconds.coerceIn(30L, 86400L),
                            createdAt = nowMs,
                            poolOverrideMode = pool?.overrideMode ?: "GLOBAL",
                            poolCustomThreshold = pool?.customThreshold ?: 50L,
                            poolCustomPercent = pool?.customPercent ?: 0.5,
                            poolCustomDigestMinutes = pool?.customDigestMinutes ?: -1,
                            poolCustomCooldownMinutes = pool?.customCooldownMinutes ?: -1,
                            poolApplyToFollowing = pool?.applyToFollowing ?: -1
                        )
                    )
                    added++
                }
            }
        }

        // 2. Global App Settings
        if (options.importSettings && preview.settingsObj != null) {
            val s = preview.settingsObj
            if (s.has("pool_mode")) {
                sm.setPoolMode(FollowerPoolMode.fromKey(s.optString("pool_mode")))
                settingsApplied++
            }
            if (s.has("pool_fixed_threshold")) {
                sm.setPoolFixedThreshold(s.optLong("pool_fixed_threshold", 50L))
                settingsApplied++
            }
            if (s.has("pool_percent_threshold")) {
                sm.setPoolPercentThreshold(s.optDouble("pool_percent_threshold", 0.5))
                settingsApplied++
            }
            if (s.has("pool_digest_minutes")) {
                sm.setPoolDigestWindowMinutes(s.optInt("pool_digest_minutes", 0))
                settingsApplied++
            }
            if (s.has("pool_cooldown_minutes")) {
                sm.setPoolCooldownMinutes(s.optInt("pool_cooldown_minutes", 0))
                settingsApplied++
            }
            if (s.has("pool_anti_flicker")) {
                sm.setPoolAntiFlickerEnabled(s.optBoolean("pool_anti_flicker", true))
                settingsApplied++
            }
            if (s.has("pool_spike_alert")) {
                sm.setPoolSpikeAlertEnabled(s.optBoolean("pool_spike_alert", true))
                settingsApplied++
            }
            if (s.has("pool_spike_multiplier")) {
                sm.setPoolSpikeMultiplier(s.optDouble("pool_spike_multiplier", 3.0))
                settingsApplied++
            }
            if (s.has("pool_apply_following")) {
                sm.setPoolApplyToFollowing(s.optBoolean("pool_apply_following", false))
                settingsApplied++
            }
            if (s.has("pool_suppress_logs")) {
                sm.setPoolSuppressMinorAuditLogs(s.optBoolean("pool_suppress_logs", true))
                settingsApplied++
            }
            if (s.has("list_completeness_guard")) {
                sm.setListCompletenessGuardEnabled(s.optBoolean("list_completeness_guard", true))
                settingsApplied++
            }
            if (s.has("archiver_speed_preset")) {
                sm.setArchiverSpeedPreset(ArchiverSpeedPreset.fromKey(s.optString("archiver_speed_preset")))
                settingsApplied++
            }
            if (s.has("max_posts_sync_limit")) {
                sm.setMaxPostsSyncLimit(s.optInt("max_posts_sync_limit", 0).coerceIn(0, 5000))
                settingsApplied++
            }
            if (s.has("yt_dlp_enabled")) {
                sm.setYtDlpAutoDownloadVideos(s.optBoolean("yt_dlp_enabled", true))
                settingsApplied++
            }
            if (s.has("yt_dlp_quality")) {
                sm.setYtDlpQualityPreset(YtDlpQualityPreset.fromKey(s.optString("yt_dlp_quality")))
                settingsApplied++
            }
            if (s.has("yt_dlp_wifi_only")) {
                sm.setYtDlpWifiOnly(s.optBoolean("yt_dlp_wifi_only", false))
                settingsApplied++
            }
            if (s.has("yt_dlp_channel")) {
                sm.setYtDlpUpdateChannel(YtDlpUpdateChannel.fromKey(s.optString("yt_dlp_channel")))
                settingsApplied++
            }
            if (s.has("auto_archive_pending") || s.has("auto_download_media")) {
                val v = if (s.has("auto_archive_pending")) s.optBoolean("auto_archive_pending", true)
                else s.optBoolean("auto_download_media", true)
                sm.setAutoArchivePendingEnabled(v)
                settingsApplied++
            }
            if (s.has("keep_awake_enabled")) {
                sm.setKeepAwakeEnabled(s.optBoolean("keep_awake_enabled", true))
                settingsApplied++
            }
            if (s.has("session_webview_auto_preview")) {
                sm.setSessionWebViewAutoPreview(s.optBoolean("session_webview_auto_preview", false))
                settingsApplied++
            }
            if (s.has("daemon_sync_enabled")) {
                sm.setDaemonSyncEnabled(s.optBoolean("daemon_sync_enabled", false))
                settingsApplied++
            }
            if (s.has("daemon_host_url")) {
                val url = s.optString("daemon_host_url", "").trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    sm.setDaemonHostUrl(url.take(256))
                    settingsApplied++
                }
            }
            if (s.has("auto_check_updates")) {
                sm.setAutoCheckUpdates(s.optBoolean("auto_check_updates", true))
                settingsApplied++
            }
            if (s.has("update_wifi_only")) {
                sm.setUpdateWifiOnly(s.optBoolean("update_wifi_only", false))
                settingsApplied++
            }
            if (s.has("update_auto_download_wifi")) {
                sm.setUpdateAutoDownloadWifi(s.optBoolean("update_auto_download_wifi", false))
                settingsApplied++
            }
            if (s.has("update_channel")) {
                sm.setUpdateChannel(AppUpdateChannel.fromKey(s.optString("update_channel")))
                settingsApplied++
            }
        }

        // 3. Notification Settings & Tree Rules (supports both Android native & Web rules)
        if (options.importNotificationRules && preview.notificationSettingsObj != null) {
            val ns = preview.notificationSettingsObj
            if (ns.has("status_bar_notifications")) {
                sm.setStatusBarNotifications(ns.optBoolean("status_bar_notifications", true))
                rulesApplied++
            }
            if (ns.has("in_app_notifications")) {
                sm.setInAppNotifications(ns.optBoolean("in_app_notifications", true))
                rulesApplied++
            }
            if (ns.has("sound_enabled")) {
                sm.setSoundEnabled(ns.optBoolean("sound_enabled", true))
                rulesApplied++
            }
            if (ns.has("vibration_enabled")) {
                sm.setVibrationEnabled(ns.optBoolean("vibration_enabled", true))
                rulesApplied++
            }
            ns.optJSONObject("categories")?.let { cats ->
                NotificationCategory.entries.forEach { cat ->
                    if (cats.has(cat.id)) {
                        sm.setCategoryEnabled(cat, cats.optBoolean(cat.id, cat.defaultEnabled))
                        rulesApplied++
                    }
                }
            }
            val globalEvents = ns.optJSONObject("global_events")
            if (globalEvents != null) {
                NotificationEventKey.entries.forEach { leaf ->
                    if (globalEvents.has(leaf.id)) {
                        sm.setEventKeyEnabled(leaf, globalEvents.optBoolean(leaf.id, leaf.defaultEnabled))
                        rulesApplied++
                    }
                }
            }
            val targetOverrides = ns.optJSONObject("target_overrides")
            if (targetOverrides != null) {
                val keys = targetOverrides.keys()
                while (keys.hasNext()) {
                    val rawUser = keys.next()
                    val cleanUser = sanitizeUsername(rawUser) ?: continue
                    val ov = targetOverrides.optJSONObject(rawUser) ?: continue
                    val muted = ov.optBoolean("muted", false)
                    val custom = ov.optBoolean("custom", false)
                    sm.setTargetNotificationsMuted(cleanUser, muted)
                    sm.setTargetNotificationOverrideEnabled(cleanUser, custom)
                    ov.optJSONObject("events")?.let { evs ->
                        NotificationEventKey.entries.forEach { leaf ->
                            if (evs.has(leaf.id)) {
                                sm.setTargetEventKeyEnabled(cleanUser, leaf, evs.optBoolean(leaf.id, true))
                            }
                        }
                    }
                    rulesApplied++
                }
            }
            // Fallback: import from Web `rules` array when Android-native keys are absent
            if (globalEvents == null && targetOverrides == null) {
                val rulesArr = ns.optJSONArray("rules")
                if (rulesArr != null) {
                    for (i in 0 until rulesArr.length()) {
                        val r = rulesArr.optJSONObject(i) ?: continue
                        val scopeKey = r.optString("scope_key").trim()
                        val enabled = r.optBoolean("enabled", true)
                        if (scopeKey == "global") {
                            sm.setStatusBarNotifications(enabled)
                            sm.setEventKeyEnabled(NotificationEventKey.PRIVACY_CHANGED, r.optBoolean("privacy_change", true))
                            sm.setEventKeyEnabled(NotificationEventKey.BIO_CHANGED, r.optBoolean("bio_change", true))
                            sm.setEventKeyEnabled(NotificationEventKey.AVATAR_CHANGED, r.optBoolean("avatar_change", true))
                            sm.setEventKeyEnabled(NotificationEventKey.NEW_POSTS, r.optBoolean("post_count_change", true) || r.optBoolean("post_vanished", true))
                            sm.setEventKeyEnabled(NotificationEventKey.STORIES_ARCHIVED, r.optBoolean("story_added", true))
                            val foll = r.optBoolean("followers_change", true)
                            sm.setEventKeyEnabled(NotificationEventKey.FOLLOWERS_GAINED, foll)
                            sm.setEventKeyEnabled(NotificationEventKey.FOLLOWERS_LOST, foll)
                            sm.setEventKeyEnabled(NotificationEventKey.FOLLOWING_CHANGED, r.optBoolean("following_change", true))
                            rulesApplied++
                        } else {
                            val cleanUser = sanitizeUsername(scopeKey) ?: continue
                            sm.setTargetNotificationsMuted(cleanUser, !enabled)
                            sm.setTargetNotificationOverrideEnabled(cleanUser, true)
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.PRIVACY_CHANGED, r.optBoolean("privacy_change", true))
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.BIO_CHANGED, r.optBoolean("bio_change", true))
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.AVATAR_CHANGED, r.optBoolean("avatar_change", true))
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.NEW_POSTS, r.optBoolean("post_count_change", true) || r.optBoolean("post_vanished", true))
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.STORIES_ARCHIVED, r.optBoolean("story_added", true))
                            val foll = r.optBoolean("followers_change", true)
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.FOLLOWERS_GAINED, foll)
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.FOLLOWERS_LOST, foll)
                            sm.setTargetEventKeyEnabled(cleanUser, NotificationEventKey.FOLLOWING_CHANGED, r.optBoolean("following_change", true))
                            rulesApplied++
                        }
                    }
                }
            }
        }

        // 4. Appearance & Language
        if (options.importAppearance) {
            preview.appearanceTheme?.let {
                sm.setThemeMode(it)
                appearanceApplied = true
            }
            preview.appearanceLanguage?.let {
                LanguageManager.setLanguage(context, it)
                appearanceApplied = true
            }
        }

        return ImportResult(
            targetsAdded = added,
            targetsUpdated = updated,
            targetsSkipped = skipped,
            settingsApplied = settingsApplied,
            rulesApplied = rulesApplied,
            appearanceApplied = appearanceApplied
        )
    }

    fun writeTextToUri(context: Context, uri: Uri, content: String): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false
            true
        }.getOrDefault(false)
    }

    fun readTextFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) return null
                    baos.write(buf, 0, read)
                }
                baos.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    fun createShareIntent(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String
    ): Intent? {
        return runCatching {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            // Keep export scratch folder clean
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                ?.forEach { it.delete() }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val file = File(dir, safeName)
            file.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, safeName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

    private fun parseJsonTarget(item: Any?): ImportedTarget? {
        if (item is String) {
            val u = sanitizeUsername(item) ?: return null
            return ImportedTarget(username = u)
        }
        val obj = item as? JSONObject ?: return null
        val username = sanitizeUsername(obj.optString("username")) ?: return null
        val poolObj = obj.optJSONObject("pool_override")
        val poolOverride = poolObj?.let { p ->
            ImportedPoolOverride(
                overrideMode = TargetPoolOverride.fromKey(p.optString("override_mode")).key,
                customThreshold = p.optLong("custom_threshold", 50L).coerceIn(1L, 1_000_000L),
                customPercent = p.optDouble("custom_percent", 0.5).coerceIn(0.01, 100.0),
                customDigestMinutes = p.optInt("custom_digest_minutes", -1).coerceAtLeast(-1),
                customCooldownMinutes = p.optInt("custom_cooldown_minutes", -1).coerceAtLeast(-1),
                applyToFollowing = p.optInt("apply_to_following", -1).coerceIn(-1, 1)
            )
        }
        val statusStr = obj.optString("status", "")
        val active = when {
            statusStr.equals("paused", ignoreCase = true) -> false
            obj.has("is_active") -> obj.optBoolean("is_active", true)
            else -> true
        }
        val turbo = when {
            obj.has("turbo_mode") -> obj.optBoolean("turbo_mode", false)
            obj.has("is_turbo") -> obj.optBoolean("is_turbo", false)
            else -> false
        }
        val igId = obj.optString("instagram_id").ifBlank { obj.optString("id") }.takeIf { it.isNotBlank() }
        return ImportedTarget(
            username = username,
            instagramId = igId,
            fullName = obj.optString("full_name", "").take(120),
            isPrivate = obj.optBoolean("is_private", false),
            isActive = active,
            isTurbo = turbo,
            checkIntervalSeconds = obj.optLong("check_interval_seconds", 60L).coerceIn(30L, 86400L),
            followersCount = obj.optLong("followers_count", 0L).coerceAtLeast(0L),
            followingCount = obj.optLong("following_count", 0L).coerceAtLeast(0L),
            mediaCount = obj.optLong("media_count", 0L).coerceAtLeast(0L),
            poolOverride = poolOverride
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cur.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        out.add(cur.toString().trim())
                        cur.clear()
                    }
                    else -> cur.append(c)
                }
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }

    private fun parseBool(raw: String?, default: Boolean): Boolean {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }
}
