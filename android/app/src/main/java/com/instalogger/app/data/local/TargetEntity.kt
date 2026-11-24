package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.instalogger.app.engine.pool.PoolMetricState

@Entity(tableName = "targets")
data class TargetEntity(
    @PrimaryKey
    val username: String,
    val instagramId: String? = null,
    val fullName: String = "",
    val biography: String = "",
    val isPrivate: Boolean = false,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val mediaCount: Long = 0,
    val avatarUrl: String? = null,
    val localAvatarPath: String? = null,
    val avatarHash: String? = null,
    val profileHash: String? = null,
    val isTurbo: Boolean = false,
    val isActive: Boolean = true,
    val checkIntervalSeconds: Long = 60,
    val lastCheckedAt: Long? = null,
    val lastStatusChangeAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val latestStoryAt: Long = 0L, // Epoch ms of newest active 24h story (0 if none)
    // Per-target Follower Change Pool override settings
    val poolOverrideMode: String = "GLOBAL", // GLOBAL | OFF | FIXED_COUNT | PERCENTAGE | AUTO_ADAPTIVE
    val poolCustomThreshold: Long = 50L,
    val poolCustomPercent: Double = 0.5,
    val poolCustomDigestMinutes: Int = -1, // -1 = inherit global, 0 = off, >0 = minutes
    val poolCustomCooldownMinutes: Int = -1, // -1 = inherit global, 0 = off, >0 = minutes
    val poolApplyToFollowing: Int = -1, // -1 = inherit global, 0 = off, 1 = on
    // Persistent Pool Accumulator State
    val poolFollowerBaseline: Long = 0L,
    val poolFollowerStartedAt: Long = 0L,
    val poolFollowerLastAlertAt: Long = 0L,
    val poolFollowerGrossGain: Long = 0L,
    val poolFollowerGrossLoss: Long = 0L,
    val poolFollowingBaseline: Long = 0L,
    val poolFollowingStartedAt: Long = 0L,
    val poolFollowingLastAlertAt: Long = 0L,
    val poolPendingFlickerCount: Long = -1L
) {
    val hasActiveStory: Boolean
        get() = latestStoryAt > 0L && (System.currentTimeMillis() - latestStoryAt) < ACTIVE_STORY_WINDOW_MS

    fun followerPoolState(): PoolMetricState = PoolMetricState(
        baseline = poolFollowerBaseline,
        startedAt = poolFollowerStartedAt,
        lastAlertAt = poolFollowerLastAlertAt,
        grossGain = poolFollowerGrossGain,
        grossLoss = poolFollowerGrossLoss,
        pendingFlickerCount = poolPendingFlickerCount
    )

    fun followingPoolState(): PoolMetricState = PoolMetricState(
        baseline = poolFollowingBaseline,
        startedAt = poolFollowingStartedAt,
        lastAlertAt = poolFollowingLastAlertAt,
        grossGain = 0L,
        grossLoss = 0L,
        pendingFlickerCount = -1L
    )

    companion object {
        private const val ACTIVE_STORY_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}
