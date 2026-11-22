package com.instalogger.app.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

object ArchiveDirectoryManager {
    private const val PREFS_NAME = "instalogger_prefs"
    private const val KEY_ARCHIVE_PATH = "custom_archive_path"

    fun getArchiveDirectory(context: Context): File {
        val prefs = getPrefs(context)
        val customPath = prefs.getString(KEY_ARCHIVE_PATH, null)
        if (!customPath.isNullOrBlank()) {
            val dir = File(customPath)
            if (dir.exists() || dir.mkdirs()) {
                return dir
            }
        }

        // Default priority 1: /storage/emulated/0/instalogger/archives
        val workspaceArchives = File("/storage/emulated/0/instalogger/archives")
        if (workspaceArchives.exists() || workspaceArchives.mkdirs()) {
            return workspaceArchives
        }

        // Default priority 2: /storage/emulated/0/InstaLogger/archives
        val standardArchives = File(Environment.getExternalStorageDirectory(), "InstaLogger/archives")
        if (standardArchives.exists() || standardArchives.mkdirs()) {
            return standardArchives
        }

        // Fallback: App external files dir
        val appDir = context.getExternalFilesDir("archives") ?: File(context.filesDir, "archives")
        appDir.mkdirs()
        return appDir
    }

    fun setArchiveDirectory(context: Context, path: String): Boolean {
        val dir = File(path.trim())
        val ok = dir.exists() || dir.mkdirs()
        if (ok) {
            getPrefs(context).edit().putString(KEY_ARCHIVE_PATH, dir.absolutePath).apply()
        }
        return ok
    }

    fun getCustomPath(context: Context): String {
        return getPrefs(context).getString(KEY_ARCHIVE_PATH, "") ?: ""
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
