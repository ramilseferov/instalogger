package com.instalogger.app.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class AppLockManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences

    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(true)
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled.asStateFlow()

    private val _isSecureScreenEnabled = MutableStateFlow(true)
    val isSecureScreenEnabled: StateFlow<Boolean> = _isSecureScreenEnabled.asStateFlow()

    private val _autoLockTimeoutSeconds = MutableStateFlow(0)
    val autoLockTimeoutSeconds: StateFlow<Int> = _autoLockTimeoutSeconds.asStateFlow()

    private val _lockoutRemainingSeconds = MutableStateFlow(0)
    val lockoutRemainingSeconds: StateFlow<Int> = _lockoutRemainingSeconds.asStateFlow()

    private var lastBackgroundTimestamp: Long = 0L
    private var lockoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to private preferences if device keystore fails initialization
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }

        _isBiometricEnabled.value = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true)
        _isSecureScreenEnabled.value = prefs.getBoolean(KEY_SECURE_SCREEN, true)
        _autoLockTimeoutSeconds.value = prefs.getInt(KEY_AUTOLOCK_TIMEOUT, 0)

        // Lock on cold start if lock is configured
        if (isLockConfigured()) {
            _isAppLocked.value = true
        }

        checkActiveLockout()
    }

    fun isLockConfigured(): Boolean {
        val hash = prefs.getString(KEY_PIN_HASH, null)
        return !hash.isNullOrBlank()
    }

    fun setPin(pin: String) {
        val cleanPin = pin.trim()
        val salt = generateSalt()
        val hash = hashPin(cleanPin, salt)
        val combined = "$salt:$hash"

        prefs.edit()
            .putString(KEY_PIN_HASH, combined)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()

        _isAppLocked.value = false
        _lockoutRemainingSeconds.value = 0
    }

    fun disableLock() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()

        _isAppLocked.value = false
        _lockoutRemainingSeconds.value = 0
    }

    fun removeLock() = disableLock()

    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = parts[0]
        val expectedHash = parts[1]
        return hashPin(pin.trim(), salt) == expectedHash
    }

    fun unlockWithPin(pin: String): Boolean {
        if (isLockedOut()) return false

        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return true
        val parts = stored.split(":")
        if (parts.size != 2) return false

        val salt = parts[0]
        val expectedHash = parts[1]
        val computedHash = hashPin(pin.trim(), salt)

        if (computedHash == expectedHash) {
            resetFailedAttempts()
            _isAppLocked.value = false
            return true
        } else {
            handleFailedAttempt()
            return false
        }
    }

    fun unlockBiometric() {
        if (!isLockedOut()) {
            resetFailedAttempts()
            _isAppLocked.value = false
        }
    }

    fun lockApp() {
        if (isLockConfigured()) {
            _isAppLocked.value = true
        }
    }

    fun onAppBackgrounded() {
        lastBackgroundTimestamp = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        if (!isLockConfigured()) return

        val timeout = _autoLockTimeoutSeconds.value
        val elapsedMillis = System.currentTimeMillis() - lastBackgroundTimestamp

        if (timeout == 0 || elapsedMillis >= (timeout * 1000L)) {
            _isAppLocked.value = true
        }
        checkActiveLockout()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        _isBiometricEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun setSecureScreenEnabled(enabled: Boolean) {
        _isSecureScreenEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SECURE_SCREEN, enabled).apply()
    }

    fun setAutoLockTimeoutSeconds(seconds: Int) {
        _autoLockTimeoutSeconds.value = seconds
        prefs.edit().putInt(KEY_AUTOLOCK_TIMEOUT, seconds).apply()
    }

    fun setAutoLockTimeout(seconds: Int) {
        _autoLockTimeoutSeconds.value = seconds
        prefs.edit().putInt(KEY_AUTOLOCK_TIMEOUT, seconds).apply()
    }

    fun isLockedOut(): Boolean {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return lockoutUntil > now
    }

    private fun handleFailedAttempt() {
        val current = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, current)

        if (current >= MAX_FAILED_ATTEMPTS) {
            val lockoutUntil = System.currentTimeMillis() + (LOCKOUT_DURATION_SECONDS * 1000L)
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
            startLockoutCountdown(LOCKOUT_DURATION_SECONDS)
        }
        editor.apply()
    }

    private fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        _lockoutRemainingSeconds.value = 0
        lockoutJob?.cancel()
    }

    private fun checkActiveLockout() {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (lockoutUntil > now) {
            val remainingSec = ((lockoutUntil - now) / 1000L).toInt().coerceAtLeast(1)
            startLockoutCountdown(remainingSec)
        } else {
            _lockoutRemainingSeconds.value = 0
        }
    }

    private fun startLockoutCountdown(seconds: Int) {
        lockoutJob?.cancel()
        lockoutJob = scope.launch {
            var rem = seconds
            while (rem > 0) {
                _lockoutRemainingSeconds.value = rem
                delay(1000L)
                rem--
            }
            _lockoutRemainingSeconds.value = 0
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
        }
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt.joinToString("") { "%02x".format(it) }
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = "$salt:$pin"
        val bytes = md.digest(combined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "instalogger_encrypted_security"
        private const val FALLBACK_PREFS_NAME = "instalogger_security_fallback"

        private const val KEY_PIN_HASH = "sec_pin_hash"
        private const val KEY_BIOMETRIC_ENABLED = "sec_biometric_enabled"
        private const val KEY_SECURE_SCREEN = "sec_secure_screen"
        private const val KEY_AUTOLOCK_TIMEOUT = "sec_autolock_timeout"
        private const val KEY_FAILED_ATTEMPTS = "sec_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "sec_lockout_until"

        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_SECONDS = 30

        @Volatile
        private var INSTANCE: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppLockManager(context).also { INSTANCE = it }
            }
        }
    }
}
