package com.instalogger.app.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.annotation.StringRes
import com.instalogger.app.InstaLoggerApp
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
            Locale.setDefault(getLocale(languageCode))
            _currentLanguage.value = languageCode
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
        val locale = getLocale(languageCode)
        Locale.setDefault(locale)
        val config = getLocalizedConfiguration(newBase.resources.configuration, languageCode)
        return newBase.createConfigurationContext(config)
    }

    fun setLanguage(context: Context, languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        synchronized(lock) {
            if (_currentLanguage.value == normalized) return

            // SharedPreferences.apply() updates in-memory map immediately and persists sequentially
            // off the main thread without blocking rapid language switches.
            getPrefs(context).edit().putString(KEY_LANGUAGE, normalized).apply()
            Locale.setDefault(getLocale(normalized))
            _currentLanguage.value = normalized
        }

        // Keep Android notification channels and persistent service notification synchronized.
        try {
            val appCtx = context.applicationContext
            (appCtx as? InstaLoggerApp)?.refreshNotificationChannels()
            InstaLoggerForegroundService.refreshLanguageNotification()
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    suspend fun changeLanguageWithAnimation(context: Context, languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        if (_currentLanguage.value == normalized) return
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

    /**
     * Builds an immutable localized Activity-backed Context without ever calling the deprecated
     * Resources.updateConfiguration(), which mutates shared ResourcesImpl entries in Android's
     * ResourcesManager cache and causes stale/previous-language desynchronization on rapid switches.
     */
    fun getLocalizedContext(
        activity: ComponentActivity,
        languageCode: String,
        baseConfig: Configuration = activity.resources.configuration
    ): Context {
        val normalized = normalizeLanguageCode(languageCode) ?: DEFAULT_LANGUAGE
        val locale = getLocale(normalized)
        Locale.setDefault(locale)
        val localizedConfig = getLocalizedConfiguration(baseConfig, normalized)
        val localizedResources = activity.createConfigurationContext(localizedConfig).resources
        return LocalizedActivityContext(activity, localizedResources)
    }

    /**
     * Returns an immutable localized Context for Application, Services, and background inspectors.
     */
    fun getLocalizedAppContext(
        context: Context,
        languageCode: String = _currentLanguage.value
    ): Context {
        val appCtx = context.applicationContext ?: context
        val normalized = normalizeLanguageCode(languageCode) ?: resolveInitialLanguage(appCtx)
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

    private fun getPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext ?: context
        return appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * Wraps the host ComponentActivity (preserving startActivity, FragmentActivity unwrapping,
 * Window tokens, and ActivityResultRegistryOwner) while routing Resources/Assets/Theme
 * strictly to an immutable configuration-scoped Resources instance.
 */
private class LocalizedActivityContext(
    private val activity: ComponentActivity,
    private val localizedResources: Resources
) : ContextWrapper(activity), ActivityResultRegistryOwner by activity {

    private var localizedTheme: Resources.Theme? = null

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

    override fun getApplicationContext(): Context = activity.applicationContext
}
