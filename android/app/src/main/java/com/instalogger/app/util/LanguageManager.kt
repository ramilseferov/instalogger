package com.instalogger.app.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistryOwner
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

    private val _currentLanguage = MutableStateFlow(DEFAULT_LANGUAGE)
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    fun getLocale(languageCode: String): Locale {
        return when (languageCode.lowercase()) {
            "az" -> Locale("az", "AZ")
            "ru" -> Locale("ru", "RU")
            else -> Locale("en", "US")
        }
    }

    fun init(context: Context) {
        _isTransitioning.value = false
        val prefs = getPrefs(context)
        val savedLang = prefs.getString(KEY_LANGUAGE, null)
        val languageCode = if (!savedLang.isNullOrBlank() && SUPPORTED_LANGUAGES.any { it.code == savedLang }) {
            savedLang
        } else {
            val systemLang = Locale.getDefault().language
            if (SUPPORTED_LANGUAGES.any { it.code == systemLang }) systemLang else DEFAULT_LANGUAGE
        }
        _currentLanguage.value = languageCode
        applyLocale(context, languageCode)
    }

    fun setLanguage(context: Context, languageCode: String) {
        if (_currentLanguage.value == languageCode) return
        if (SUPPORTED_LANGUAGES.none { it.code == languageCode }) return

        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
        applyLocale(context, languageCode)
        _currentLanguage.value = languageCode
    }

    suspend fun changeLanguageWithAnimation(context: Context, languageCode: String) {
        if (_currentLanguage.value == languageCode) return
        if (SUPPORTED_LANGUAGES.none { it.code == languageCode }) return

        try {
            _isTransitioning.value = true
            kotlinx.coroutines.delay(180) // Silky smooth blur & frosted veil flows in
            setLanguage(context, languageCode)
            kotlinx.coroutines.delay(200) // Recomposition settles cleanly under blur
        } finally {
            _isTransitioning.value = false
        }
    }

    fun resetTransition() {
        _isTransitioning.value = false
    }

    private fun applyLocale(context: Context, languageCode: String) {
        try {
            val locale = getLocale(languageCode)
            Locale.setDefault(locale)
            val config = context.resources.configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                config.setLocale(locale)
            }
            config.setLayoutDirection(locale)
            context.createConfigurationContext(config)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        } catch (_: Exception) {
            // Graceful fallback
        }
    }

    fun getLocalizedContext(activity: ComponentActivity, languageCode: String): Context {
        val locale = getLocale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(activity.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            config.setLocale(locale)
        }
        config.setLayoutDirection(locale)
        val base = activity.createConfigurationContext(config)
        return object : ContextWrapper(base), ActivityResultRegistryOwner by activity {}
    }

    fun getLocalizedConfiguration(baseConfig: Configuration, languageCode: String): Configuration {
        val locale = getLocale(languageCode)
        val config = Configuration(baseConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            config.setLocale(locale)
        }
        config.setLayoutDirection(locale)
        return config
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
