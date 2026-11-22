package com.instalogger.app.util

import android.app.LocaleManager
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

    fun init(context: Context) {
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
        _currentLanguage.value = languageCode
        applyLocale(context, languageCode)
    }

    private fun applyLocale(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
                localeManager?.applicationLocales = LocaleList(locale)
            } catch (e: Exception) {
                // Ignore fallback
            }
        }
    }

    fun getLocalizedContext(activity: ComponentActivity, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(activity.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val base = activity.createConfigurationContext(config)
        return object : ContextWrapper(base), ActivityResultRegistryOwner by activity {}
    }

    fun getLocalizedConfiguration(baseConfig: Configuration, languageCode: String): Configuration {
        val locale = Locale(languageCode)
        val config = Configuration(baseConfig)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return config
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
