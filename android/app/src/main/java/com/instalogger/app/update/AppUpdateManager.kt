package com.instalogger.app.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.instalogger.app.BuildConfig
import com.instalogger.app.R
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.settings.AppSettingsManager
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.LanguageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class AppUpdateManager(
    private val context: Context,
    private val settingsManager: AppSettingsManager
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val releaseClient = GitHubReleaseClient()
    private val downloader = ResumableApkDownloader()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _isSheetVisible = MutableStateFlow(false)
    val isSheetVisible: StateFlow<Boolean> = _isSheetVisible.asStateFlow()

    private val _availableRelease = MutableStateFlow<ReleaseInfo?>(null)
    val availableRelease: StateFlow<ReleaseInfo?> = _availableRelease.asStateFlow()

    private val _lastCheckedAtMs = MutableStateFlow(prefs.getLong(KEY_LAST_CHECK_MS, 0L))
    val lastCheckedAtMs: StateFlow<Long> = _lastCheckedAtMs.asStateFlow()

    @Volatile
    private var activeDownloadJob: Job? = null

    @Volatile
    private var lastVerifiedApkPath: String? = null

    fun installedVersionCode(): Int = BuildConfig.VERSION_CODE
    fun installedVersionName(): String = BuildConfig.VERSION_NAME

    fun getSkippedVersionCode(): Int = prefs.getInt(KEY_SKIPPED_VERSION_CODE, 0)

    fun isBannerVisibleFor(release: ReleaseInfo?): Boolean {
        if (release == null) return false
        if (release.forceUpdate) return true
        return release.versionCode != getSkippedVersionCode()
    }

    fun openSheet() {
        _isSheetVisible.value = true
    }

    fun dismissSheet() {
        val current = _state.value
        val forced = when (current) {
            is UpdateUiState.Available -> current.release.forceUpdate
            is UpdateUiState.ReadyToInstall -> current.release.forceUpdate
            else -> false
        }
        if (!forced) {
            _isSheetVisible.value = false
        }
    }

    fun skipVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_SKIPPED_VERSION_CODE, versionCode).apply()
        _isSheetVisible.value = false
        AppLogger.i(TAG, "User skipped update build $versionCode")
    }

    fun checkForUpdatesAsync(
        manual: Boolean = false,
        openSheetIfAvailable: Boolean = false,
        notifyIfAvailable: Boolean = false
    ) {
        scope.launch {
            checkForUpdates(
                manual = manual,
                openSheetIfAvailable = openSheetIfAvailable,
                notifyIfAvailable = notifyIfAvailable
            )
        }
    }

    suspend fun checkForUpdates(
        manual: Boolean = false,
        openSheetIfAvailable: Boolean = false,
        notifyIfAvailable: Boolean = false
    ): UpdateUiState = withContext(Dispatchers.IO) {
        if (!manual && !settingsManager.autoCheckUpdates.value) {
            return@withContext _state.value
        }

        // Do not interrupt an active download / install flow on background check
        val cur = _state.value
        if (!manual && (cur is UpdateUiState.Downloading || cur is UpdateUiState.Verifying || cur is UpdateUiState.Installing)) {
            return@withContext cur
        }

        checkMutex.withLock {
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
            if (!manual && lastCheck > 0L && (now - lastCheck) < AUTO_CHECK_COOLDOWN_MS) {
                loadCachedReleaseIfNewer()?.let { cached ->
                    _availableRelease.value = cached
                    val cachedApk = findCachedApkFile(cached)
                    if (_state.value is UpdateUiState.Idle) {
                        _state.value = UpdateUiState.Available(cached, isCachedApkReady = cachedApk != null)
                    }
                }
                return@withContext _state.value
            }

            if (manual) {
                _state.value = UpdateUiState.Checking
            }

            val channel = settingsManager.updateChannel.value
            val cachedEtag = prefs.getString(KEY_CACHED_ETAG, null)
            val result = releaseClient.fetchLatestRelease(channel = channel, cachedEtag = if (manual) null else cachedEtag)

            prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
            _lastCheckedAtMs.value = now

            if (result.notModified) {
                val cached = loadCachedReleaseIfNewer()
                if (cached != null) {
                    _availableRelease.value = cached
                    val ready = findCachedApkFile(cached) != null
                    val next = UpdateUiState.Available(cached, isCachedApkReady = ready)
                    _state.value = next
                    if (openSheetIfAvailable && (manual || isBannerVisibleFor(cached))) {
                        _isSheetVisible.value = true
                    }
                    return@withContext next
                } else {
                    val up = UpdateUiState.UpToDate(installedVersionName(), installedVersionCode(), now)
                    if (manual || _state.value is UpdateUiState.Idle) _state.value = up
                    return@withContext up
                }
            }

            if (result.error != null && result.release == null) {
                AppLogger.w(TAG, "Update check failed: ${result.error}")
                val err = UpdateUiState.Error(
                    message = LanguageManager.getString(appContext, R.string.update_error_network, result.error),
                    release = _availableRelease.value,
                    retryable = true
                )
                if (manual) _state.value = err
                return@withContext err
            }

            val remote = result.release ?: run {
                val up = UpdateUiState.UpToDate(installedVersionName(), installedVersionCode(), now)
                if (manual) _state.value = up
                return@withContext up
            }

            prefs.edit()
                .putString(KEY_CACHED_ETAG, result.etag)
                .putString(KEY_CACHED_RELEASE_JSON, gson.toJson(remote))
                .apply()

            val isNewer = GitHubReleaseClient.isRemoteNewer(
                remote = remote,
                installedVersionCode = installedVersionCode(),
                installedVersionName = installedVersionName()
            )

            if (!isNewer) {
                _availableRelease.value = null
                val up = UpdateUiState.UpToDate(installedVersionName(), installedVersionCode(), now)
                _state.value = up
                AppLogger.i(TAG, "App is up to date (installed=${installedVersionName()}-b${installedVersionCode()}, remote=${remote.tag})")
                return@withContext up
            }

            _availableRelease.value = remote
            val cachedApk = findCachedApkFile(remote)
            val availableState = UpdateUiState.Available(remote, isCachedApkReady = cachedApk != null)
            _state.value = availableState
            AppLogger.i(TAG, "New update available: ${remote.tag} (source=${result.source}, cached=${cachedApk != null})")

            if (openSheetIfAvailable && (manual || isBannerVisibleFor(remote))) {
                _isSheetVisible.value = true
            }

            if (notifyIfAvailable && isBannerVisibleFor(remote)) {
                maybeNotifyUpdateAvailable(remote)
            }

            // Optional background auto-download on Wi-Fi
            if (!manual && settingsManager.updateAutoDownloadWifi.value && isOnWifi() && cachedApk == null && isBannerVisibleFor(remote)) {
                startDownload(remote, ignoreWifiOnly = false, autoInstall = false)
            }

            availableState
        }
    }

    fun startDownload(
        release: ReleaseInfo,
        ignoreWifiOnly: Boolean = false,
        autoInstall: Boolean = true
    ) {
        if (!ignoreWifiOnly && settingsManager.updateWifiOnly.value && !isOnWifi()) {
            _state.value = UpdateUiState.Error(
                message = LanguageManager.getString(appContext, R.string.update_error_wifi_required),
                release = release,
                retryable = true
            )
            return
        }

        // Fast-path: if already downloaded & verified on disk, jump straight to verify/install
        val targetFile = getApkTargetFile(release)
        activeDownloadJob?.cancel()
        activeDownloadJob = scope.launch {
            try {
                if (!targetFile.exists() || (release.sizeBytes > 0L && targetFile.length() != release.sizeBytes)) {
                    _state.value = UpdateUiState.Downloading(
                        release = release,
                        downloadedBytes = File(targetFile.parentFile, "${targetFile.name}.part").takeIf { it.exists() }?.length() ?: 0L,
                        totalBytes = release.sizeBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        paused = false
                    )
                    val dlRes = downloader.downloadWithResume(
                        url = release.apkUrl,
                        destApkFile = targetFile,
                        expectedSizeBytes = release.sizeBytes
                    ) { progress ->
                        _state.value = UpdateUiState.Downloading(
                            release = release,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            speedBytesPerSec = progress.speedBytesPerSec,
                            etaSeconds = progress.etaSeconds,
                            paused = false
                        )
                    }
                    if (dlRes.isFailure) {
                        val err = dlRes.exceptionOrNull()
                        val errMsg = formatDownloadError(err)
                        _state.value = UpdateUiState.Error(errMsg, release = release, retryable = true)
                        return@launch
                    }
                }

                // Cryptographic + Signing + PackageManager verification
                _state.value = UpdateUiState.Verifying(release)
                val verify = ApkIntegrityVerifier.verifyApk(
                    context = appContext,
                    apkFile = targetFile,
                    expectedSha256 = release.sha256,
                    installedVersionCode = installedVersionCode()
                )
                when (verify) {
                    is ApkVerifyResult.Invalid -> {
                        try { targetFile.delete() } catch (_: Exception) {}
                        val friendly = when (verify.code) {
                            "SHA256_MISMATCH" -> LanguageManager.getString(appContext, R.string.update_error_sha256)
                            "SIGNING_MISMATCH" -> LanguageManager.getString(appContext, R.string.update_error_signing)
                            "PACKAGE_MISMATCH" -> LanguageManager.getString(appContext, R.string.update_error_package)
                            else -> LanguageManager.getString(appContext, R.string.update_error_corrupt, verify.detail)
                        }
                        _state.value = UpdateUiState.Error(friendly, release = release, retryable = true)
                        return@launch
                    }
                    is ApkVerifyResult.Valid -> {
                        lastVerifiedApkPath = targetFile.absolutePath
                        _state.value = UpdateUiState.ReadyToInstall(
                            release = release,
                            apkFilePath = targetFile.absolutePath,
                            sha256Verified = verify.sha256Verified
                        )
                        if (autoInstall) {
                            triggerInstall(release, targetFile)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Handled by pauseDownload or cancelDownload
            } catch (e: Exception) {
                AppLogger.e(TAG, "Update pipeline error", e)
                _state.value = UpdateUiState.Error(
                    message = e.message ?: LanguageManager.getString(appContext, R.string.update_error_generic),
                    release = release,
                    retryable = true
                )
            }
        }
    }

    fun pauseDownload() {
        val cur = _state.value
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        if (cur is UpdateUiState.Downloading) {
            _state.value = cur.copy(paused = true, speedBytesPerSec = 0L, etaSeconds = -1L)
        }
    }

    fun cancelDownload() {
        val cur = _state.value
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        val rel = when (cur) {
            is UpdateUiState.Downloading -> cur.release
            is UpdateUiState.Error -> cur.release
            else -> _availableRelease.value
        }
        if (rel != null) {
            val target = getApkTargetFile(rel)
            try { File(target.parentFile, "${target.name}.part").delete() } catch (_: Exception) {}
            _state.value = UpdateUiState.Available(rel, isCachedApkReady = target.exists())
        } else {
            _state.value = UpdateUiState.Idle
        }
    }

    fun triggerInstall(release: ReleaseInfo, apkFile: File? = null) {
        val file = apkFile ?: lastVerifiedApkPath?.let { File(it) } ?: getApkTargetFile(release)
        if (!file.exists()) {
            startDownload(release, ignoreWifiOnly = true, autoInstall = true)
            return
        }
        if (!ApkInstallerEngine.canRequestPackageInstalls(appContext)) {
            _state.value = UpdateUiState.PermissionRequired(release, file.absolutePath)
            return
        }
        _state.value = UpdateUiState.Installing(release)
        scope.launch {
            val res = ApkInstallerEngine.installApk(appContext, file)
            if (res.isFailure) {
                _state.value = UpdateUiState.Error(
                    message = res.exceptionOrNull()?.message ?: LanguageManager.getString(appContext, R.string.update_error_install_failed),
                    release = release,
                    retryable = true
                )
            }
        }
    }

    fun onActivityResumed() {
        val cur = _state.value
        if (cur is UpdateUiState.PermissionRequired && ApkInstallerEngine.canRequestPackageInstalls(appContext)) {
            AppLogger.i(TAG, "REQUEST_INSTALL_PACKAGES granted on resume -> auto-continuing installation")
            triggerInstall(cur.release, File(cur.apkFilePath))
        }
    }

    fun onInstallSucceeded() {
        cleanupUpdateCache(appContext)
        _isSheetVisible.value = false
        _availableRelease.value = null
        _state.value = UpdateUiState.UpToDate(installedVersionName(), installedVersionCode(), System.currentTimeMillis())
    }

    fun onInstallCancelled() {
        val rel = _availableRelease.value
        val path = lastVerifiedApkPath
        if (rel != null && path != null && File(path).exists()) {
            _state.value = UpdateUiState.ReadyToInstall(rel, path, sha256Verified = !rel.sha256.isNullOrBlank())
        } else if (rel != null) {
            _state.value = UpdateUiState.Available(rel, isCachedApkReady = false)
        } else {
            _state.value = UpdateUiState.Idle
        }
    }

    fun onInstallFailed(status: Int, detail: String) {
        val rel = _availableRelease.value
        val msg = when (status) {
            PackageInstaller.STATUS_FAILURE_STORAGE -> LanguageManager.getString(appContext, R.string.update_error_storage_generic)
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> LanguageManager.getString(appContext, R.string.update_error_signing)
            else -> detail.ifBlank { LanguageManager.getString(appContext, R.string.update_error_install_failed) }
        }
        _state.value = UpdateUiState.Error(msg, release = rel, retryable = true)
    }

    private fun maybeNotifyUpdateAvailable(release: ReleaseInfo) {
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_VERSION_CODE, 0)
        if (lastNotified >= release.versionCode) return
        prefs.edit().putInt(KEY_LAST_NOTIFIED_VERSION_CODE, release.versionCode).apply()
        val title = LanguageManager.getString(appContext, R.string.update_notif_title, release.versionName)
        val body = LanguageManager.getString(appContext, R.string.update_notif_body, release.tag)
        InstaLoggerForegroundService.instance?.showUpdateNotification(
            versionCode = release.versionCode,
            title = title,
            message = body
        )
    }

    private fun loadCachedReleaseIfNewer(): ReleaseInfo? {
        val raw = prefs.getString(KEY_CACHED_RELEASE_JSON, null) ?: return null
        val rel = runCatching { gson.fromJson(raw, ReleaseInfo::class.java) }.getOrNull() ?: return null
        return if (GitHubReleaseClient.isRemoteNewer(rel, installedVersionCode(), installedVersionName())) rel else null
    }

    private fun getUpdatesDir(): File = File(appContext.cacheDir, "updates").apply { if (!exists()) mkdirs() }

    private fun getApkTargetFile(release: ReleaseInfo): File {
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(getUpdatesDir(), "InstaLogger_$safeTag.apk")
    }

    private fun findCachedApkFile(release: ReleaseInfo): File? {
        val f = getApkTargetFile(release)
        if (!f.exists() || f.length() < 1024 * 1024) return null
        if (release.sizeBytes > 0L && f.length() != release.sizeBytes) return null
        return f
    }

    private fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun formatDownloadError(err: Throwable?): String {
        val raw = err?.message.orEmpty()
        if (raw.startsWith("INSUFFICIENT_STORAGE:")) {
            val mb = raw.substringAfter(":")
            return LanguageManager.getString(appContext, R.string.update_error_storage_mb, mb)
        }
        return raw.ifBlank { LanguageManager.getString(appContext, R.string.update_error_generic) }
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val PREFS_NAME = "instalogger_update_prefs"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val KEY_CACHED_ETAG = "cached_etag"
        private const val KEY_CACHED_RELEASE_JSON = "cached_release_json"
        private const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"
        private const val KEY_LAST_NOTIFIED_VERSION_CODE = "last_notified_version_code"
        private const val AUTO_CHECK_COOLDOWN_MS = 6L * 60L * 60L * 1000L

        fun cleanupUpdateCache(context: Context) {
            try {
                val dir = File(context.applicationContext.cacheDir, "updates")
                if (dir.exists() && dir.isDirectory) {
                    var freed = 0L
                    dir.listFiles()?.forEach { f ->
                        freed += f.length()
                        f.delete()
                    }
                    if (freed > 0L) {
                        AppLogger.i(TAG, "Cleaned up update cache (${freed / (1024 * 1024)} MB freed)")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to clean update cache: ${e.message}")
            }
        }
    }
}
