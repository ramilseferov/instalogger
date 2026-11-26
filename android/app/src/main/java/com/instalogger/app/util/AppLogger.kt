package com.instalogger.app.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.instalogger.app.engine.media.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val code: String, val priority: Int) {
    DEBUG("D", 1),
    INFO("I", 2),
    WARN("W", 3),
    ERROR("E", 4);

    companion object {
        fun fromCode(code: String?): LogLevel {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: INFO
        }
    }
}

data class DevLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatTerminalLine(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val ts = sdf.format(Date(timestampMs))
        return "$ts [${level.code}][$tag] $message"
    }
}

// Encrypted, redacted, live-streaming developer console logger.
object AppLogger {
    private const val MAX_ENTRIES = 1500
    private const val LOG_CATEGORY = "_system"
    private const val LOG_FILENAME = "dev_logs.jsonl"
    private const val PERSIST_DEBOUNCE_MS = 1200L

    private val gson = Gson()
    private val idCounter = AtomicLong(1L)
    private val lock = Any()
    private val ringBuffer = ArrayDeque<DevLogEntry>(MAX_ENTRIES)
    private val persistScheduled = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    private val _logs = MutableStateFlow<List<DevLogEntry>>(emptyList())
    val logs: StateFlow<List<DevLogEntry>> = _logs.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ioScope.launch {
            loadEncryptedLogsFromDisk()
            i("AppLogger", "Encrypted developer console initialized (max=$MAX_ENTRIES)")
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            message
        }
        log(LogLevel.ERROR, tag, full)
    }

    fun log(level: LogLevel, tag: String, rawMessage: String) {
        val sanitized = LogSanitizer.redact(rawMessage).replace("\r", " ").trim()
        val entry = DevLogEntry(
            id = idCounter.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag.take(28),
            message = sanitized
        )

        // Mirror to standard Android Logcat safely (works in JVM unit tests too)
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, sanitized)
                LogLevel.INFO -> Log.i(tag, sanitized)
                LogLevel.WARN -> Log.w(tag, sanitized)
                LogLevel.ERROR -> Log.e(tag, sanitized)
            }
        } catch (_: Throwable) {
            // Unit test environment without Android Log stub
        }

        val snapshot: List<DevLogEntry>
        synchronized(lock) {
            if (ringBuffer.size >= MAX_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
            snapshot = ringBuffer.toList()
        }
        _logs.value = snapshot
        scheduleEncryptedPersist()
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
        }
        _logs.value = emptyList()
        val ctx = appContext ?: return
        ioScope.launch {
            try {
                val vault = VaultStorageManager.getInstance(ctx)
                val file = vault.getVaultMediaFile(LOG_CATEGORY, "logs", LOG_FILENAME)
                if (file != null && file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun formatDump(entries: List<DevLogEntry> = _logs.value): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder(entries.size * 96)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        for (e in entries) {
            sb.append(sdf.format(Date(e.timestampMs)))
                .append(" [").append(e.level.code).append("]")
                .append("[").append(e.tag).append("] ")
                .append(e.message)
                .append('\n')
        }
        return sb.toString()
    }

    private fun scheduleEncryptedPersist() {
        if (appContext == null) return
        if (!persistScheduled.compareAndSet(false, true)) return
        ioScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistScheduled.set(false)
            persistEncryptedLogsToDisk()
        }
    }

    private fun persistEncryptedLogsToDisk() {
        val ctx = appContext ?: return
        val snapshot = synchronized(lock) { ringBuffer.toList() }
        try {
            val sb = StringBuilder(snapshot.size * 110)
            for (e in snapshot) {
                val obj = JsonObject().apply {
                    addProperty("t", e.timestampMs)
                    addProperty("l", e.level.code)
                    addProperty("g", e.tag)
                    addProperty("m", e.message)
                }
                sb.append(gson.toJson(obj)).append('\n')
            }
            val vault = VaultStorageManager.getInstance(ctx)
            vault.saveEncryptedFile(
                targetUsername = LOG_CATEGORY,
                category = "logs",
                filename = LOG_FILENAME,
                plaintextBytes = sb.toString().toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            // Avoid recursive logging on disk failure
        }
    }

    private fun loadEncryptedLogsFromDisk() {
        val ctx = appContext ?: return
        try {
            val vault = VaultStorageManager.getInstance(ctx)
            val encFile: File = vault.getVaultMediaFile(LOG_CATEGORY, "logs", LOG_FILENAME) ?: return
            val plainBytes = vault.readEncryptedFile(encFile) ?: return
            val lines = String(plainBytes, Charsets.UTF_8).lineSequence()
            val restored = mutableListOf<DevLogEntry>()
            for (line in lines) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("{")) continue
                try {
                    val obj = gson.fromJson(trimmed, JsonObject::class.java) ?: continue
                    val ts = obj.get("t")?.asLong ?: continue
                    val lvl = LogLevel.fromCode(obj.get("l")?.asString)
                    val tag = obj.get("g")?.asString ?: "App"
                    val msg = obj.get("m")?.asString ?: ""
                    restored.add(
                        DevLogEntry(
                            id = idCounter.getAndIncrement(),
                            timestampMs = ts,
                            level = lvl,
                            tag = tag,
                            message = msg
                        )
                    )
                } catch (_: Exception) {
                }
            }
            if (restored.isNotEmpty()) {
                val merged: List<DevLogEntry>
                synchronized(lock) {
                    val existingNow = ringBuffer.toList()
                    ringBuffer.clear()
                    val combined = (restored + existingNow).takeLast(MAX_ENTRIES)
                    ringBuffer.addAll(combined)
                    merged = ringBuffer.toList()
                }
                _logs.value = merged
            }
        } catch (_: Exception) {
        }
    }
}
