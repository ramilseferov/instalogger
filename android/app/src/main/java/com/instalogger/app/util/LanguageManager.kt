package com.instalogger.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.ChangeLogEntity
import com.instalogger.app.data.local.NotificationEntity
import com.instalogger.app.service.InstaLoggerForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SupportedLanguage(
    val code: String,
    val nameEn: String,
    val nativeName: String
)

object LanguageManager {
    private const val PREFS_NAME = "instalogger_prefs"
    private const val KEY_LANGUAGE = "app_language"
    const val DEFAULT_LANGUAGE = "en"

    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("en", "English", "English"),
        SupportedLanguage("az", "Azerbaijani", "Azərbaycan"),
        SupportedLanguage("ru", "Russian", "Русский")
    )

    private val lock = Any()

    private val _currentLanguage = MutableStateFlow(DEFAULT_LANGUAGE)
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private val parenSignRegex = Regex("""\(([+-]?\d+)\)""")
    private val countArrowRegex = Regex("""(\d+)\s*->\s*(\d+)""")
    private val trailingHandleRegex = Regex("""@([A-Za-z0-9._]+)\s*$""")
    private val shortcodeRegex = Regex("""/p/([A-Za-z0-9_-]+)""")
    private val quotedTitleRegex = Regex(""""([^"]+)"""")
    private val intNumbersRegex = Regex("""\d+""")

    fun normalizeLanguageCode(languageCode: String?): String? {
        val normalized = languageCode?.trim()?.lowercase(Locale.ROOT) ?: return null
        return if (SUPPORTED_LANGUAGES.any { it.code == normalized }) normalized else null
    }

    fun getLocale(languageCode: String): Locale {
        return when (languageCode.trim().lowercase(Locale.ROOT)) {
            "az" -> Locale("az", "AZ")
            "ru" -> Locale("ru", "RU")
            else -> Locale("en", "US")
        }
    }

    fun init(context: Context) {
        synchronized(lock) {
            _isTransitioning.value = false
            val languageCode = resolveInitialLanguage(context)
            _currentLanguage.value = languageCode
            applyLocaleToResources(context, languageCode)
        }
    }

    fun resolveInitialLanguage(context: Context): String {
        val prefs = getPrefs(context)
        val savedLang = normalizeLanguageCode(prefs.getString(KEY_LANGUAGE, null))
        if (savedLang != null) return savedLang

        val systemLang = normalizeLanguageCode(Locale.getDefault().language)
        return systemLang ?: DEFAULT_LANGUAGE
    }

    fun wrapBaseContext(newBase: Context): Context {
        val languageCode = resolveInitialLanguage(newBase)
        applyLocaleToResources(newBase, languageCode)
        val config = getLocalizedConfiguration(newBase.resources.configuration, languageCode)
        return newBase.createConfigurationContext(config)
    }

    /**
     * Updates default Locale/LocaleList AND mutates in-place Resources configurations across the
     * context hierarchy (Activity, ContextWrapper chain, and Application). This ensures that new
     * Dialog/ModalBottomSheet windows spawned from LocalView.current.context (Activity) always
     * inherit the active language immediately without requiring an Activity restart.
     */
    @Suppress("DEPRECATION")
    fun applyLocaleToResources(context: Context?, languageCode: String = _currentLanguage.value) {
        val normalized = normalizeLanguageCode(languageCode) ?: DEFAULT_LANGUAGE
        val locale = getLocale(normalized)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { LocaleList.setDefault(LocaleList(locale)) }
        }
        if (context == null) return

        val visited = HashSet<Context>()
        var curr: Context? = context
        while (curr != null && visited.add(curr)) {
            runCatching {
                val res = curr.resources
                if (res != null) {
                    val cfg = getLocalizedConfiguration(res.configuration, normalized)
                    res.updateConfiguration(cfg, res.displayMetrics)
                }
            }
            curr = if (curr is ContextWrapper) curr.baseContext else null
        }

        val appCtx = context.applicationContext
        if (appCtx != null && visited.add(appCtx)) {
            runCatching {
                val res = appCtx.resources
                if (res != null) {
                    val cfg = getLocalizedConfiguration(res.configuration, normalized)
                    res.updateConfiguration(cfg, res.displayMetrics)
                }
            }
        }
    }

    fun setLanguage(context: Context, languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        synchronized(lock) {
            getPrefs(context).edit().putString(KEY_LANGUAGE, normalized).apply()
            _currentLanguage.value = normalized
            applyLocaleToResources(context, normalized)
        }

        // Keep Android notification channels and persistent service notification synchronized.
        try {
            val appCtx = context.applicationContext
            (appCtx as? InstaLoggerApp)?.refreshNotificationChannels()
            InstaLoggerForegroundService.refreshLanguageNotification()
        } catch (_: Exception) {
        }
    }

    suspend fun changeLanguageWithAnimation(context: Context, languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        if (_currentLanguage.value == normalized) {
            applyLocaleToResources(context, normalized)
            return
        }
        try {
            _isTransitioning.value = true
            kotlinx.coroutines.delay(150)
            setLanguage(context, normalized)
            kotlinx.coroutines.delay(190)
        } finally {
            _isTransitioning.value = false
        }
    }

    fun resetTransition() {
        _isTransitioning.value = false
    }

    fun getLocalizedContext(
        activity: ComponentActivity,
        languageCode: String,
        baseConfig: Configuration = activity.resources.configuration
    ): Context {
        val normalized = normalizeLanguageCode(languageCode) ?: DEFAULT_LANGUAGE
        applyLocaleToResources(activity, normalized)
        val localizedConfig = getLocalizedConfiguration(baseConfig, normalized)
        val localizedResources = activity.createConfigurationContext(localizedConfig).resources
        return LocalizedActivityContext(activity, localizedResources)
    }

    fun getLocalizedContextForAny(
        baseContext: Context,
        languageCode: String,
        baseConfig: Configuration = baseContext.resources.configuration
    ): Context {
        val normalized = normalizeLanguageCode(languageCode) ?: DEFAULT_LANGUAGE
        applyLocaleToResources(baseContext, normalized)
        val localizedConfig = getLocalizedConfiguration(baseConfig, normalized)
        val localizedResources = baseContext.createConfigurationContext(localizedConfig).resources
        val activity = baseContext.findComponentActivity()
        return if (activity != null) {
            LocalizedActivityContext(activity, localizedResources)
        } else {
            LocalizedGeneralContext(baseContext, localizedResources)
        }
    }

    @Composable
    fun ProvideLocalizedContext(content: @Composable () -> Unit) {
        val lang by currentLanguage.collectAsState()
        val baseContext = LocalContext.current
        val baseConfiguration = LocalConfiguration.current
        val localizedConfiguration = remember(lang, baseConfiguration) {
            getLocalizedConfiguration(baseConfiguration, lang)
        }
        val localizedContext = remember(lang, baseContext, baseConfiguration) {
            getLocalizedContextForAny(baseContext, lang, baseConfiguration)
        }
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides localizedConfiguration,
            content = content
        )
    }

    fun getLocalizedAppContext(
        context: Context,
        languageCode: String = _currentLanguage.value
    ): Context {
        val appCtx = context.applicationContext ?: context
        val normalized = normalizeLanguageCode(languageCode) ?: resolveInitialLanguage(appCtx)
        applyLocaleToResources(appCtx, normalized)
        val localizedConfig = getLocalizedConfiguration(appCtx.resources.configuration, normalized)
        return appCtx.createConfigurationContext(localizedConfig)
    }

    fun getString(
        context: Context,
        @StringRes resId: Int,
        vararg formatArgs: Any
    ): String {
        val localized = getLocalizedAppContext(context)
        return if (formatArgs.isEmpty()) {
            localized.getString(resId)
        } else {
            localized.getString(resId, *formatArgs)
        }
    }

    fun getLocalizedConfiguration(baseConfig: Configuration, languageCode: String): Configuration {
        val normalized = normalizeLanguageCode(languageCode) ?: DEFAULT_LANGUAGE
        val locale = getLocale(normalized)
        val config = Configuration(baseConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }
        config.setLayoutDirection(locale)
        return config
    }

    /**
     * Dynamically localizes stored NotificationEntity title and message into the current active
     * language so notifications recorded while another language was selected immediately display
     * in the user's current language.
     */
    fun localizeNotification(
        context: Context,
        notification: NotificationEntity
    ): Pair<String, String> {
        val res = context.resources
        val user = notification.username.removePrefix("@")
        val type = notification.eventType.lowercase(Locale.ROOT)
        val rawTitle = notification.title
        val rawMsg = notification.message

        return runCatching {
            when (type) {
                "avatar_change" -> {
                    res.getString(R.string.notif_avatar_changed_title, user) to
                        res.getString(R.string.notif_avatar_changed_desc)
                }
                "bio_change" -> {
                    res.getString(R.string.notif_bio_changed_title, user) to rawMsg
                }
                "name_change" -> {
                    res.getString(R.string.notif_name_changed_title, user) to rawMsg
                }
                "privacy_change" -> {
                    val isPriv = rawMsg.contains("GİZLİ", ignoreCase = true) ||
                        rawMsg.contains("PRIVATE", ignoreCase = true) ||
                        rawMsg.contains("ЗАКРЫТ", ignoreCase = true)
                    val statusLabel = if (isPriv) {
                        res.getString(R.string.notif_privacy_private)
                    } else {
                        res.getString(R.string.notif_privacy_public)
                    }
                    res.getString(R.string.notif_privacy_changed_title, user) to
                        res.getString(R.string.notif_privacy_changed_desc, statusLabel)
                }
                "followers_change", "follower_count_change" -> {
                    val sign = parenSignRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: parenSignRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val arrow = countArrowRegex.find(rawMsg)
                    val title = res.getString(R.string.notif_followers_changed_title, user, sign)
                    val msg = if (arrow != null) {
                        val c1 = arrow.groupValues[1].toLongOrNull() ?: 0L
                        val c2 = arrow.groupValues[2].toLongOrNull() ?: 0L
                        res.getString(R.string.notif_followers_changed_desc, c1, c2, sign)
                    } else rawMsg
                    title to msg
                }
                "following_change", "following_count_change" -> {
                    val sign = parenSignRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: parenSignRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val arrow = countArrowRegex.find(rawMsg)
                    val title = res.getString(R.string.notif_following_changed_title, user, sign)
                    val msg = if (arrow != null) {
                        val c1 = arrow.groupValues[1].toLongOrNull() ?: 0L
                        val c2 = arrow.groupValues[2].toLongOrNull() ?: 0L
                        res.getString(R.string.notif_following_changed_desc, c1, c2, sign)
                    } else rawMsg
                    title to msg
                }
                "post_count_change" -> {
                    val sign = parenSignRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: parenSignRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val arrow = countArrowRegex.find(rawMsg)
                    val title = res.getString(R.string.notif_posts_changed_title, user, sign)
                    val msg = if (arrow != null) {
                        res.getString(
                            R.string.notif_posts_changed_desc,
                            arrow.groupValues[1],
                            arrow.groupValues[2],
                            sign
                        )
                    } else rawMsg
                    title to msg
                }
                "follower_gained" -> {
                    val actor = trailingHandleRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: trailingHandleRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val display = rawMsg.substringAfter(":", "@$actor").trim().ifBlank { "@$actor" }
                    if (actor.isNotBlank()) {
                        res.getString(R.string.notif_follower_gained_title, user, actor) to
                            res.getString(R.string.notif_follower_gained_desc, display)
                    } else rawTitle to rawMsg
                }
                "follower_lost" -> {
                    val actor = trailingHandleRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: trailingHandleRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val display = rawMsg.substringAfter(":", "@$actor").trim().ifBlank { "@$actor" }
                    if (actor.isNotBlank()) {
                        res.getString(R.string.notif_follower_lost_title, user, actor) to
                            res.getString(R.string.notif_follower_lost_desc, display)
                    } else rawTitle to rawMsg
                }
                "following_gained" -> {
                    val actor = trailingHandleRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: trailingHandleRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val display = rawMsg.substringAfter(":", "@$actor").trim().ifBlank { "@$actor" }
                    if (actor.isNotBlank()) {
                        res.getString(R.string.notif_following_gained_title, user, actor) to
                            res.getString(R.string.notif_following_gained_desc, display)
                    } else rawTitle to rawMsg
                }
                "following_lost" -> {
                    val actor = trailingHandleRegex.find(rawTitle)?.groupValues?.get(1)
                        ?: trailingHandleRegex.find(rawMsg)?.groupValues?.get(1) ?: ""
                    val display = rawMsg.substringAfter(":", "@$actor").trim().ifBlank { "@$actor" }
                    if (actor.isNotBlank()) {
                        res.getString(R.string.notif_following_lost_title, user, actor) to
                            res.getString(R.string.notif_following_lost_desc, display)
                    } else rawTitle to rawMsg
                }
                "post_vanished" -> {
                    val code = shortcodeRegex.find(rawMsg)?.groupValues?.get(1)
                    val title = res.getString(R.string.notif_post_vanished_title, user)
                    val msg = if (code != null) {
                        val suffix = rawMsg.substringAfter("/p/$code").trim()
                        res.getString(R.string.notif_post_vanished_desc, code, suffix).trim()
                    } else rawMsg
                    title to msg
                }
                "story_posted" -> {
                    res.getString(R.string.notif_story_posted_title, user) to
                        res.getString(R.string.notif_story_posted_desc)
                }
                "story_added" -> {
                    val count = intNumbersRegex.find(rawMsg)?.value?.toIntOrNull() ?: 1
                    res.getString(R.string.notif_story_added_title, user) to
                        res.getString(R.string.notif_story_added_desc, count)
                }
                "story_vanished" -> {
                    val timePart = rawMsg.substringAfter("(", "").substringBefore(")", "")
                    res.getString(R.string.notif_story_vanished_title, user) to
                        if (timePart.isNotBlank()) res.getString(R.string.notif_story_vanished_desc, timePart) else rawMsg
                }
                "highlight_added" -> {
                    val hlTitle = quotedTitleRegex.find(rawMsg)?.groupValues?.get(1) ?: "Highlight"
                    val nums = intNumbersRegex.findAll(rawMsg).mapNotNull { it.value.toIntOrNull() }.toList()
                    val count = nums.lastOrNull() ?: 1
                    res.getString(R.string.notif_highlight_added_title, user) to
                        res.getString(R.string.notif_highlight_added_desc, hlTitle, count)
                }
                "highlight_updated" -> {
                    val hlTitle = quotedTitleRegex.find(rawMsg)?.groupValues?.get(1) ?: "Highlight"
                    val nums = intNumbersRegex.findAll(rawMsg).mapNotNull { it.value.toIntOrNull() }.toList()
                    val count = nums.lastOrNull() ?: 1
                    res.getString(R.string.notif_highlight_updated_title, user) to
                        res.getString(R.string.notif_highlight_updated_desc, hlTitle, count)
                }
                "highlight_vanished" -> {
                    val hlTitle = quotedTitleRegex.find(rawMsg)?.groupValues?.get(1) ?: "Highlight"
                    res.getString(R.string.notif_highlight_vanished_title, user) to
                        res.getString(R.string.notif_highlight_vanished_desc, hlTitle)
                }
                "highlight_item_vanished" -> {
                    val hlTitle = quotedTitleRegex.find(rawMsg)?.groupValues?.get(1) ?: "Highlight"
                    val nums = intNumbersRegex.findAll(rawMsg).mapNotNull { it.value.toIntOrNull() }.toList()
                    val count = nums.lastOrNull() ?: 1
                    res.getString(R.string.notif_highlight_item_vanished_title, user) to
                        res.getString(R.string.notif_highlight_item_vanished_desc, hlTitle, count)
                }
                else -> rawTitle to rawMsg
            }
        }.getOrDefault(rawTitle to rawMsg)
    }

    /**
     * Dynamically localizes stored ChangeLogEntity title and privacy old/new values into the
     * current active language.
     */
    fun localizeChangeLog(
        context: Context,
        log: ChangeLogEntity
    ): Triple<String, String?, String?> {
        val res = context.resources
        val user = log.targetUsername.removePrefix("@")
        val type = log.eventType.lowercase(Locale.ROOT)
        val rawTitle = log.title

        fun localizePrivacyValue(v: String?): String? {
            if (v.isNullOrBlank()) return v
            return when {
                v.equals("GİZLİ", true) || v.equals("PRIVATE", true) || v.equals("ЗАКРЫТЫЙ", true) ->
                    res.getString(R.string.notif_privacy_private)
                v.equals("AÇIQ", true) || v.equals("PUBLIC", true) || v.equals("ОТКРЫТЫЙ", true) ->
                    res.getString(R.string.notif_privacy_public)
                else -> v
            }
        }

        val localizedTitle = runCatching {
            when (type) {
                "avatar_change" -> res.getString(R.string.notif_avatar_changed_title, user)
                "bio_change" -> res.getString(R.string.notif_bio_changed_title, user)
                "name_change" -> res.getString(R.string.notif_name_changed_title, user)
                "privacy_change" -> res.getString(R.string.notif_privacy_changed_title, user)
                "followers_change", "follower_count_change" -> {
                    val o = log.oldValue?.toLongOrNull()
                    val n = log.newValue?.toLongOrNull()
                    val sign = if (o != null && n != null) {
                        val d = n - o
                        if (d > 0) "+$d" else d.toString()
                    } else parenSignRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    res.getString(R.string.notif_followers_changed_title, user, sign)
                }
                "following_change", "following_count_change" -> {
                    val o = log.oldValue?.toLongOrNull()
                    val n = log.newValue?.toLongOrNull()
                    val sign = if (o != null && n != null) {
                        val d = n - o
                        if (d > 0) "+$d" else d.toString()
                    } else parenSignRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    res.getString(R.string.notif_following_changed_title, user, sign)
                }
                "post_count_change" -> {
                    val o = log.oldValue?.toLongOrNull()
                    val n = log.newValue?.toLongOrNull()
                    val sign = if (o != null && n != null) {
                        val d = n - o
                        if (d > 0) "+$d" else d.toString()
                    } else parenSignRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    res.getString(R.string.notif_posts_changed_title, user, sign)
                }
                "follower_gained" -> {
                    val actor = log.newValue?.removePrefix("@")?.takeIf { it.isNotBlank() }
                        ?: trailingHandleRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    if (actor.isNotBlank()) res.getString(R.string.notif_follower_gained_title, user, actor) else rawTitle
                }
                "follower_lost" -> {
                    val actor = log.oldValue?.removePrefix("@")?.takeIf { it.isNotBlank() }
                        ?: trailingHandleRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    if (actor.isNotBlank()) res.getString(R.string.notif_follower_lost_title, user, actor) else rawTitle
                }
                "following_gained" -> {
                    val actor = log.newValue?.removePrefix("@")?.takeIf { it.isNotBlank() }
                        ?: trailingHandleRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    if (actor.isNotBlank()) res.getString(R.string.notif_following_gained_title, user, actor) else rawTitle
                }
                "following_lost" -> {
                    val actor = log.oldValue?.removePrefix("@")?.takeIf { it.isNotBlank() }
                        ?: trailingHandleRegex.find(rawTitle)?.groupValues?.get(1) ?: ""
                    if (actor.isNotBlank()) res.getString(R.string.notif_following_lost_title, user, actor) else rawTitle
                }
                "post_vanished" -> res.getString(R.string.notif_post_vanished_title, user)
                "story_posted" -> res.getString(R.string.notif_story_posted_title, user)
                "story_added" -> res.getString(R.string.notif_story_added_title, user)
                "story_vanished" -> res.getString(R.string.notif_story_vanished_title, user)
                "highlight_added" -> res.getString(R.string.notif_highlight_added_title, user)
                "highlight_updated" -> res.getString(R.string.notif_highlight_updated_title, user)
                "highlight_vanished" -> res.getString(R.string.notif_highlight_vanished_title, user)
                "highlight_item_vanished" -> res.getString(R.string.notif_highlight_item_vanished_title, user)
                else -> rawTitle
            }
        }.getOrDefault(rawTitle)

        val oldV = if (type == "privacy_change") localizePrivacyValue(log.oldValue) else log.oldValue
        val newV = if (type == "privacy_change") localizePrivacyValue(log.newValue) else log.newValue
        return Triple(localizedTitle, oldV, newV)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext ?: context
        return appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var curr: Context? = this
    while (curr is ContextWrapper) {
        if (curr is ComponentActivity) return curr
        curr = curr.baseContext
    }
    return curr as? ComponentActivity
}

private class LocalizedActivityContext(
    private val activity: ComponentActivity,
    private val localizedResources: Resources
) : ContextWrapper(activity), ActivityResultRegistryOwner by activity {

    private var localizedTheme: Resources.Theme? = null
    private var localizedInflater: LayoutInflater? = null

    override fun getResources(): Resources = localizedResources

    override fun getAssets(): AssetManager = localizedResources.assets

    override fun getTheme(): Resources.Theme {
        localizedTheme?.let { return it }
        return try {
            localizedResources.newTheme().also {
                it.setTo(activity.theme)
                localizedTheme = it
            }
        } catch (_: Exception) {
            activity.theme
        }
    }

    override fun getSystemService(name: String): Any? {
        if (Context.LAYOUT_INFLATER_SERVICE == name) {
            return localizedInflater ?: LayoutInflater.from(activity).cloneInContext(this).also {
                localizedInflater = it
            }
        }
        return super.getSystemService(name)
    }

    override fun getApplicationContext(): Context = activity.applicationContext
}

private class LocalizedGeneralContext(
    base: Context,
    private val localizedResources: Resources
) : ContextWrapper(base) {

    private var localizedTheme: Resources.Theme? = null

    override fun getResources(): Resources = localizedResources

    override fun getAssets(): AssetManager = localizedResources.assets

    override fun getTheme(): Resources.Theme {
        localizedTheme?.let { return it }
        return try {
            localizedResources.newTheme().also {
                it.setTo(baseContext.theme)
                localizedTheme = it
            }
        } catch (_: Exception) {
            baseContext.theme
        }
    }
}
