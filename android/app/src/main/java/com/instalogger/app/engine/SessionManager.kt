package com.instalogger.app.engine

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveCookieString(cookie: String) {
        prefs.edit().putString(KEY_COOKIE, cookie.trim()).apply()
    }

    fun getCookieString(): String {
        return prefs.getString(KEY_COOKIE, "") ?: ""
    }

    fun hasSession(): Boolean {
        val c = getCookieString()
        return c.isNotBlank() && c.contains("sessionid=")
    }

    fun clearSession() {
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    companion object {
        private const val PREFS_NAME = "instalogger_session"
        private const val KEY_COOKIE = "instagram_cookie"
    }
}
