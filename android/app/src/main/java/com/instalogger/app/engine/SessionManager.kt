package com.instalogger.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    @Volatile
    private var hasCachedCookie: Boolean = false

    @Volatile
    private var cachedCookieValue: String = ""

    fun saveCookieString(cookie: String) {
        val trimmed = cookie.trim()
        if (trimmed.isEmpty()) {
            clearSession()
            return
        }
        cachedCookieValue = trimmed
        hasCachedCookie = true
        val encrypted = encrypt(trimmed)
        prefs.edit().putString(KEY_COOKIE, encrypted).apply()
    }

    fun getCookieString(): String {
        if (hasCachedCookie) return cachedCookieValue

        val stored = prefs.getString(KEY_COOKIE, "") ?: ""
        if (stored.isBlank()) {
            cachedCookieValue = ""
            hasCachedCookie = true
            return ""
        }

        // Check if legacy unencrypted plaintext format
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            cachedCookieValue = stored
            hasCachedCookie = true
            if (stored.contains("sessionid=")) {
                saveCookieString(stored)
            }
            return stored
        }

        val decrypted = decrypt(stored)
        cachedCookieValue = decrypted
        hasCachedCookie = true
        return decrypted
    }

    fun hasSession(): Boolean {
        val c = getCookieString()
        return c.isNotBlank() && c.contains("sessionid=")
    }

    fun clearSession() {
        cachedCookieValue = ""
        hasCachedCookie = true
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        cachedSecretKey?.let { return it }
        return synchronized(this) {
            cachedSecretKey?.let { return@synchronized it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            val resolved = entry?.secretKey ?: (keyStore.getKey(KEY_ALIAS, null) as SecretKey)
            cachedSecretKey = resolved
            resolved
        }
    }

    private fun encrypt(plaintext: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            "$ENCRYPTED_PREFIX$ivBase64:$ctBase64"
        } catch (e: Exception) {
            plaintext
        }
    }

    private fun decrypt(encryptedWithPrefix: String): String {
        return try {
            val payload = encryptedWithPrefix.removePrefix(ENCRYPTED_PREFIX)
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val PREFS_NAME = "instalogger_session"
        private const val KEY_COOKIE = "instagram_cookie"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "instalogger_session_aes"
        private const val ENCRYPTED_PREFIX = "enc:v1:"
    }
}
