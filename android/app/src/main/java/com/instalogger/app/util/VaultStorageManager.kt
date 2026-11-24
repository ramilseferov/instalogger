package com.instalogger.app.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultStorageManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    private val secureRandom = SecureRandom()

    val vaultRoot: File = File(appContext.filesDir, "vault").apply {
        mkdirs()
        ensureNoMedia(this)
    }

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            try {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate KeyStore AES-256 key: ${e.message}")
            }
        }
    }

    private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Vault MasterKey not available in KeyStore")
    }

    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        // Android KeyStore enforces randomizedEncryptionRequired=true and generates the 12-byte GCM IV itself
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH) {
            "Unexpected KeyStore GCM IV length: ${iv?.size}"
        }

        val ciphertext = cipher.doFinal(plaintext)

        // Output format: [4 bytes MAGIC][12 bytes IV][CIPHERTEXT + 16 bytes TAG]
        val result = ByteArray(MAGIC_HEADER.size + iv.size + ciphertext.size)
        System.arraycopy(MAGIC_HEADER, 0, result, 0, MAGIC_HEADER.size)
        System.arraycopy(iv, 0, result, MAGIC_HEADER.size, iv.size)
        System.arraycopy(ciphertext, 0, result, MAGIC_HEADER.size + iv.size, ciphertext.size)

        return result
    }

    fun decryptBytes(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < MAGIC_HEADER.size + GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException("Data too short to be a valid vault envelope")
        }

        // Verify magic header
        for (i in MAGIC_HEADER.indices) {
            if (encryptedData[i] != MAGIC_HEADER[i]) {
                throw IllegalArgumentException("Invalid vault magic header")
            }
        }

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(encryptedData, MAGIC_HEADER.size, iv, 0, GCM_IV_LENGTH)

        val ciphertextOffset = MAGIC_HEADER.size + GCM_IV_LENGTH
        val ciphertextSize = encryptedData.size - ciphertextOffset

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        return cipher.doFinal(encryptedData, ciphertextOffset, ciphertextSize)
    }

    fun saveEncryptedFile(
        targetUsername: String,
        category: String,
        filename: String,
        plaintextBytes: ByteArray
    ): File {
        val userDir = File(vaultRoot, "$targetUsername/$category").apply {
            mkdirs()
            ensureNoMedia(this)
        }

        val encName = if (filename.endsWith(".enc")) filename else "$filename.enc"
        val destFile = File(userDir, encName)

        val encryptedBytes = encryptBytes(plaintextBytes)
        FileOutputStream(destFile).use { it.write(encryptedBytes) }

        return destFile
    }

    fun getVaultAvatarFile(username: String): File? {
        val f = File(vaultRoot, "$username/profile/avatar_current.jpg.enc")
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun getVaultMediaFile(username: String, category: String, filename: String): File? {
        val encName = if (filename.endsWith(".enc")) filename else "$filename.enc"
        val f = File(vaultRoot, "$username/$category/$encName")
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun readEncryptedFile(file: File): ByteArray? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val bytes = FileInputStream(file).use { it.readBytes() }
            if (isVaultFile(bytes)) {
                decryptBytes(bytes)
            } else {
                bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt vault file: ${file.name}: ${e.message}")
            null
        }
    }

    fun isVaultFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_HEADER.size) return false
        return try {
            FileInputStream(file).use { stream ->
                val header = ByteArray(MAGIC_HEADER.size)
                if (stream.read(header) == MAGIC_HEADER.size) {
                    header.contentEquals(MAGIC_HEADER)
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isVaultFile(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_HEADER.size) return false
        for (i in MAGIC_HEADER.indices) {
            if (bytes[i] != MAGIC_HEADER[i]) return false
        }
        return true
    }

    fun exportToPublic(sourceEncryptedFile: File, destPublicFile: File): Boolean {
        return try {
            val decrypted = readEncryptedFile(sourceEncryptedFile) ?: return false
            destPublicFile.parentFile?.mkdirs()
            FileOutputStream(destPublicFile).use { it.write(decrypted) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export vault file to public storage: ${e.message}")
            false
        }
    }

    fun getVaultSizeBytes(): Long {
        return calculateDirSize(vaultRoot)
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) calculateDirSize(f) else f.length()
        }
        return size
    }

    private fun ensureNoMedia(dir: File) {
        try {
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VaultStorageManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "instalogger_vault_master_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        // Magic 4 bytes: "VLT1"
        private val MAGIC_HEADER = byteArrayOf(0x56, 0x4C, 0x54, 0x31)

        @Volatile
        private var INSTANCE: VaultStorageManager? = null

        fun getInstance(context: Context): VaultStorageManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VaultStorageManager(context).also { INSTANCE = it }
            }
        }
    }
}
