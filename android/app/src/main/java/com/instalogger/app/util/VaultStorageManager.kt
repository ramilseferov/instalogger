package com.instalogger.app.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    @Volatile
    private var cachedSecretKey: SecretKey? = null

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
        cachedSecretKey?.let { return it }
        return synchronized(this) {
            cachedSecretKey?.let { return@synchronized it }
            val key = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw IllegalStateException("Vault MasterKey not available in KeyStore")
            cachedSecretKey = key
            key
        }
    }

    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH) {
            "Unexpected KeyStore GCM IV length: ${iv?.size}"
        }

        val ciphertext = cipher.doFinal(plaintext)

        // VLT1 Output format: [4 bytes MAGIC][12 bytes IV][CIPHERTEXT + 16 bytes TAG]
        val result = ByteArray(MAGIC_HEADER_V1.size + iv.size + ciphertext.size)
        System.arraycopy(MAGIC_HEADER_V1, 0, result, 0, MAGIC_HEADER_V1.size)
        System.arraycopy(iv, 0, result, MAGIC_HEADER_V1.size, iv.size)
        System.arraycopy(ciphertext, 0, result, MAGIC_HEADER_V1.size + iv.size, ciphertext.size)

        return result
    }

    fun decryptBytes(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < MAGIC_HEADER_V1.size + GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException("Data too short to be a valid vault envelope")
        }

        if (matchesMagic(encryptedData, MAGIC_HEADER_V2)) {
            val out = ByteArrayOutputStream()
            DataInputStream(encryptedData.inputStream()).use { dis ->
                val magic = ByteArray(4)
                dis.readFully(magic)
                val secretKey = getSecretKey()
                while (true) {
                    val cipherLen = try {
                        dis.readInt()
                    } catch (_: EOFException) {
                        break
                    }
                    if (cipherLen <= 0 || cipherLen > MAX_CHUNK_CIPHER_BYTES) {
                        throw IllegalArgumentException("Corrupt VLT2 chunk length: $cipherLen")
                    }
                    val iv = ByteArray(GCM_IV_LENGTH)
                    dis.readFully(iv)
                    val cipherChunk = ByteArray(cipherLen)
                    dis.readFully(cipherChunk)
                    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                    out.write(cipher.doFinal(cipherChunk))
                }
            }
            return out.toByteArray()
        }

        if (!matchesMagic(encryptedData, MAGIC_HEADER_V1)) {
            throw IllegalArgumentException("Invalid vault magic header")
        }

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(encryptedData, MAGIC_HEADER_V1.size, iv, 0, GCM_IV_LENGTH)

        val ciphertextOffset = MAGIC_HEADER_V1.size + GCM_IV_LENGTH
        val ciphertextSize = encryptedData.size - ciphertextOffset

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        return cipher.doFinal(encryptedData, ciphertextOffset, ciphertextSize)
    }

    // Memory-safe chunked VLT2 streaming file encryption (512 KB frames).
    fun saveEncryptedFile(
        targetUsername: String,
        category: String,
        filename: String,
        sourceFile: File
    ): File {
        val userDir = File(vaultRoot, "$targetUsername/$category").apply {
            mkdirs()
            ensureNoMedia(this)
        }
        val encName = if (filename.endsWith(".enc")) filename else "$filename.enc"
        val destFile = File(userDir, encName)
        val tmpFile = File(userDir, "$encName.tmp_${System.nanoTime()}")

        try {
            val secretKey = getSecretKey()
            val plainBuf = ByteArray(STREAM_CHUNK_BYTES)
            BufferedInputStream(FileInputStream(sourceFile), 64 * 1024).use { input ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024)).use { dos ->
                    dos.write(MAGIC_HEADER_V2)
                    while (true) {
                        val read = readFullChunk(input, plainBuf)
                        if (read <= 0) break
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                        val iv = cipher.iv
                        require(iv != null && iv.size == GCM_IV_LENGTH) {
                            "Unexpected KeyStore GCM IV length: ${iv?.size}"
                        }
                        val encryptedChunk = cipher.doFinal(plainBuf, 0, read)
                        dos.writeInt(encryptedChunk.size)
                        dos.write(iv)
                        dos.write(encryptedChunk)
                    }
                    dos.flush()
                }
            }
            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            return destFile
        } catch (t: Throwable) {
            try { tmpFile.delete() } catch (_: Exception) {}
            throw t
        }
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

        if (plaintextBytes.size > STREAM_CHUNK_BYTES) {
            val tmpFile = File(userDir, "$encName.tmp_${System.nanoTime()}")
            try {
                val secretKey = getSecretKey()
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024)).use { dos ->
                    dos.write(MAGIC_HEADER_V2)
                    var offset = 0
                    while (offset < plaintextBytes.size) {
                        val len = minOf(STREAM_CHUNK_BYTES, plaintextBytes.size - offset)
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                        val iv = cipher.iv
                        require(iv != null && iv.size == GCM_IV_LENGTH)
                        val encryptedChunk = cipher.doFinal(plaintextBytes, offset, len)
                        dos.writeInt(encryptedChunk.size)
                        dos.write(iv)
                        dos.write(encryptedChunk)
                        offset += len
                    }
                    dos.flush()
                }
                if (destFile.exists()) destFile.delete()
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                return destFile
            } catch (t: Throwable) {
                try { tmpFile.delete() } catch (_: Exception) {}
                throw t
            }
        }

        val encryptedBytes = encryptBytes(plaintextBytes)
        FileOutputStream(destFile).use { it.write(encryptedBytes) }

        return destFile
    }

    fun decryptFileToFile(sourceEncryptedFile: File, destFile: File): Boolean {
        if (!sourceEncryptedFile.exists() || sourceEncryptedFile.length() == 0L) return false
        return try {
            destFile.parentFile?.mkdirs()
            val header = ByteArray(4)
            FileInputStream(sourceEncryptedFile).use { fis ->
                if (fis.read(header) < 4) return false
            }

            when {
                header.contentEquals(MAGIC_HEADER_V2) -> {
                    val secretKey = getSecretKey()
                    DataInputStream(BufferedInputStream(FileInputStream(sourceEncryptedFile), 64 * 1024)).use { dis ->
                        val skipMagic = ByteArray(4)
                        dis.readFully(skipMagic)
                        BufferedOutputStream(FileOutputStream(destFile), 64 * 1024).use { bos ->
                            while (true) {
                                val cipherLen = try {
                                    dis.readInt()
                                } catch (_: EOFException) {
                                    break
                                }
                                if (cipherLen <= 0 || cipherLen > MAX_CHUNK_CIPHER_BYTES) {
                                    return false
                                }
                                val iv = ByteArray(GCM_IV_LENGTH)
                                dis.readFully(iv)
                                val cipherChunk = ByteArray(cipherLen)
                                dis.readFully(cipherChunk)
                                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                                bos.write(cipher.doFinal(cipherChunk))
                            }
                            bos.flush()
                        }
                    }
                    destFile.exists() && destFile.length() > 0L
                }
                header.contentEquals(MAGIC_HEADER_V1) -> {
                    val decrypted = readEncryptedFile(sourceEncryptedFile) ?: return false
                    FileOutputStream(destFile).use { it.write(decrypted) }
                    destFile.exists() && destFile.length() > 0L
                }
                else -> {
                    sourceEncryptedFile.copyTo(destFile, overwrite = true)
                    destFile.exists() && destFile.length() > 0L
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decryptFileToFile failed (${sourceEncryptedFile.name}): ${t.message}")
            false
        }
    }

    private fun readFullChunk(input: BufferedInputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        return total
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
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to decrypt vault file: ${file.name}: ${t.message}")
            null
        }
    }

    fun isVaultFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_HEADER_V1.size) return false
        return try {
            FileInputStream(file).use { stream ->
                val header = ByteArray(MAGIC_HEADER_V1.size)
                if (stream.read(header) == MAGIC_HEADER_V1.size) {
                    header.contentEquals(MAGIC_HEADER_V1) || header.contentEquals(MAGIC_HEADER_V2)
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isVaultFile(bytes: ByteArray): Boolean {
        return matchesMagic(bytes, MAGIC_HEADER_V1) || matchesMagic(bytes, MAGIC_HEADER_V2)
    }

    private fun matchesMagic(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        for (i in magic.indices) {
            if (bytes[i] != magic[i]) return false
        }
        return true
    }

    fun exportToPublic(sourceEncryptedFile: File, destPublicFile: File): Boolean {
        return decryptFileToFile(sourceEncryptedFile, destPublicFile)
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
        private const val STREAM_CHUNK_BYTES = 512 * 1024 // 512 KB chunks -> < 2 MB peak heap
        private const val MAX_CHUNK_CIPHER_BYTES = STREAM_CHUNK_BYTES + 1024

        // Magic 4 bytes: "VLT1" (single-shot) & "VLT2" (chunked streaming)
        private val MAGIC_HEADER_V1 = byteArrayOf(0x56, 0x4C, 0x54, 0x31)
        private val MAGIC_HEADER_V2 = byteArrayOf(0x56, 0x4C, 0x54, 0x32)

        @Volatile
        private var INSTANCE: VaultStorageManager? = null

        fun getInstance(context: Context): VaultStorageManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VaultStorageManager(context).also { INSTANCE = it }
            }
        }
    }
}
