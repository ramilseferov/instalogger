package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_health")
data class ProviderHealthEntity(
    @PrimaryKey
    val providerId: String,
    val status: String = "HEALTHY", // HEALTHY, DEGRADED, RATE_LIMITED, LOGIN_REQUIRED, DISABLED
    val consecutiveFailures: Int = 0,
    val cooldownUntil: Long = 0L,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
