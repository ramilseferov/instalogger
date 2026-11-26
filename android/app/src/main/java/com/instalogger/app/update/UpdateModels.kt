package com.instalogger.app.update

enum class AppUpdateChannel(val key: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromKey(key: String?): AppUpdateChannel {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: STABLE
        }
    }
}

data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val channel: AppUpdateChannel = AppUpdateChannel.STABLE,
    val publishedAt: String = "",
    val minSdk: Int = 26,
    val minSupportedVersionCode: Int = 1,
    val forceUpdate: Boolean = false,
    val apkName: String = "InstaLogger.apk",
    val apkUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val changelogAz: String = "",
    val changelogEn: String = "",
    val changelogRu: String = "",
    val releaseUrl: String = ""
) {
    fun changelogForLanguage(langCode: String): String {
        return when (langCode.lowercase()) {
            "en" -> changelogEn.ifBlank { changelogAz }.ifBlank { changelogRu }
            "ru" -> changelogRu.ifBlank { changelogEn }.ifBlank { changelogAz }
            else -> changelogAz.ifBlank { changelogEn }.ifBlank { changelogRu }
        }.trim()
    }
}

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(
        val currentVersionName: String,
        val currentVersionCode: Int,
        val checkedAtMs: Long
    ) : UpdateUiState()

    data class Available(
        val release: ReleaseInfo,
        val isCachedApkReady: Boolean = false
    ) : UpdateUiState()

    data class Downloading(
        val release: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val etaSeconds: Long,
        val paused: Boolean = false
    ) : UpdateUiState() {
        val progressFraction: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }

    data class Verifying(val release: ReleaseInfo) : UpdateUiState()

    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFilePath: String,
        val sha256Verified: Boolean
    ) : UpdateUiState()

    data class PermissionRequired(
        val release: ReleaseInfo,
        val apkFilePath: String
    ) : UpdateUiState()

    data class Installing(val release: ReleaseInfo) : UpdateUiState()

    data class Error(
        val message: String,
        val release: ReleaseInfo? = null,
        val retryable: Boolean = true
    ) : UpdateUiState()
}
