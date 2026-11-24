package com.instalogger.app.engine.media

import com.instalogger.app.data.local.ProviderHealthDao
import com.instalogger.app.data.local.ProviderHealthEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit-breaker health tracker for pluggable media providers.
 * Prevents hammering rate-limited (HTTP 429) or failing extractors while automatically
 * recovering once cooldown expires.
 */
class ProviderHealthTracker(
    private val dao: ProviderHealthDao
) {
    private val cache = ConcurrentHashMap<String, ProviderHealthEntity>()

    val healthFlow: Flow<List<ProviderHealthEntity>> = dao.getAllFlow()

    suspend fun canUseProvider(providerId: String, now: Long = System.currentTimeMillis()): Boolean {
        val current = getOrLoad(providerId)
        if (current.status == STATUS_DISABLED) return false
        if (current.cooldownUntil > now) return false
        return true
    }

    suspend fun recordSuccess(providerId: String, now: Long = System.currentTimeMillis()) {
        val cur = getOrLoad(providerId)
        val updated = cur.copy(
            status = STATUS_HEALTHY,
            consecutiveFailures = 0,
            cooldownUntil = 0L,
            successCount = cur.successCount + 1,
            lastError = null,
            updatedAt = now
        )
        cache[providerId] = updated
        dao.upsert(updated)
    }

    suspend fun recordRateLimit(
        providerId: String,
        retryAfterMs: Long = 120_000L,
        error: String,
        now: Long = System.currentTimeMillis()
    ) {
        val cur = getOrLoad(providerId)
        val failures = cur.consecutiveFailures + 1
        val multiplier = when {
            failures <= 1 -> 1L
            failures == 2 -> 2L
            else -> 5L
        }
        val cooldown = (retryAfterMs * multiplier).coerceAtMost(15 * 60_000L)
        val updated = cur.copy(
            status = STATUS_RATE_LIMITED,
            consecutiveFailures = failures,
            cooldownUntil = now + cooldown,
            failureCount = cur.failureCount + 1,
            lastError = LogSanitizer.redact(error).take(200),
            updatedAt = now
        )
        cache[providerId] = updated
        dao.upsert(updated)
    }

    suspend fun recordLoginRequired(
        providerId: String,
        error: String,
        now: Long = System.currentTimeMillis()
    ) {
        val cur = getOrLoad(providerId)
        val updated = cur.copy(
            status = STATUS_LOGIN_REQUIRED,
            cooldownUntil = now + 30_000L,
            failureCount = cur.failureCount + 1,
            lastError = LogSanitizer.redact(error).take(200),
            updatedAt = now
        )
        cache[providerId] = updated
        dao.upsert(updated)
    }

    suspend fun recordFailure(
        providerId: String,
        error: String,
        now: Long = System.currentTimeMillis()
    ) {
        val cur = getOrLoad(providerId)
        val failures = cur.consecutiveFailures + 1
        val cooldownUntil = if (failures >= 3) now + 60_000L else 0L
        val status = if (failures >= 3) STATUS_DEGRADED else STATUS_HEALTHY
        val updated = cur.copy(
            status = status,
            consecutiveFailures = failures,
            cooldownUntil = cooldownUntil,
            failureCount = cur.failureCount + 1,
            lastError = LogSanitizer.redact(error).take(200),
            updatedAt = now
        )
        cache[providerId] = updated
        dao.upsert(updated)
    }

    suspend fun resetProvider(providerId: String, now: Long = System.currentTimeMillis()) {
        val cur = getOrLoad(providerId)
        val updated = cur.copy(
            status = STATUS_HEALTHY,
            consecutiveFailures = 0,
            cooldownUntil = 0L,
            lastError = null,
            updatedAt = now
        )
        cache[providerId] = updated
        dao.upsert(updated)
    }

    private suspend fun getOrLoad(providerId: String): ProviderHealthEntity {
        cache[providerId]?.let { return it }
        val loaded = dao.getById(providerId) ?: ProviderHealthEntity(providerId = providerId)
        cache[providerId] = loaded
        return loaded
    }

    companion object {
        const val STATUS_HEALTHY = "HEALTHY"
        const val STATUS_DEGRADED = "DEGRADED"
        const val STATUS_RATE_LIMITED = "RATE_LIMITED"
        const val STATUS_LOGIN_REQUIRED = "LOGIN_REQUIRED"
        const val STATUS_DISABLED = "DISABLED"
    }
}
