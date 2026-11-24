package com.instalogger.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetDao {
    @Query("SELECT * FROM targets ORDER BY createdAt DESC")
    fun getAllTargets(): Flow<List<TargetEntity>>

    @Query("SELECT * FROM targets WHERE isActive = 1")
    suspend fun getActiveTargets(): List<TargetEntity>

    @Query("SELECT * FROM targets WHERE LOWER(username) = LOWER(:username) LIMIT 1")
    fun getTargetFlow(username: String): Flow<TargetEntity?>

    @Query("SELECT * FROM targets WHERE LOWER(username) = LOWER(:username) LIMIT 1")
    suspend fun getTarget(username: String): TargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTarget(target: TargetEntity)

    @Update
    suspend fun updateTarget(target: TargetEntity)

    @Query("UPDATE targets SET isTurbo = :isTurbo WHERE LOWER(username) = LOWER(:username)")
    suspend fun updateTurbo(username: String, isTurbo: Boolean)

    @Query("UPDATE targets SET lastCheckedAt = :timestamp WHERE LOWER(username) = LOWER(:username)")
    suspend fun updateLastChecked(username: String, timestamp: Long)

    @Query(
        """
        UPDATE targets
        SET poolOverrideMode = :overrideMode,
            poolCustomThreshold = :customThreshold,
            poolCustomPercent = :customPercent,
            poolCustomDigestMinutes = :customDigestMinutes,
            poolCustomCooldownMinutes = :customCooldownMinutes,
            poolApplyToFollowing = :applyToFollowing,
            poolFollowerBaseline = CASE WHEN poolFollowerBaseline <= 0 THEN followersCount ELSE poolFollowerBaseline END,
            poolFollowerStartedAt = CASE WHEN poolFollowerStartedAt <= 0 THEN :nowMs ELSE poolFollowerStartedAt END,
            poolFollowingBaseline = CASE WHEN poolFollowingBaseline <= 0 THEN followingCount ELSE poolFollowingBaseline END,
            poolFollowingStartedAt = CASE WHEN poolFollowingStartedAt <= 0 THEN :nowMs ELSE poolFollowingStartedAt END
        WHERE LOWER(username) = LOWER(:username)
        """
    )
    suspend fun updatePoolConfig(
        username: String,
        overrideMode: String,
        customThreshold: Long,
        customPercent: Double,
        customDigestMinutes: Int,
        customCooldownMinutes: Int,
        applyToFollowing: Int,
        nowMs: Long
    )

    @Query(
        """
        UPDATE targets
        SET poolFollowerBaseline = :followerBaseline,
            poolFollowerStartedAt = :nowMs,
            poolFollowerLastAlertAt = :lastAlertAt,
            poolFollowerGrossGain = 0,
            poolFollowerGrossLoss = 0,
            poolFollowingBaseline = :followingBaseline,
            poolFollowingStartedAt = :nowMs,
            poolPendingFlickerCount = -1
        WHERE LOWER(username) = LOWER(:username)
        """
    )
    suspend fun resetPoolAccumulators(
        username: String,
        followerBaseline: Long,
        followingBaseline: Long,
        lastAlertAt: Long,
        nowMs: Long
    )

    @Query("DELETE FROM targets WHERE LOWER(username) = LOWER(:username)")
    suspend fun deleteTarget(username: String)

    @Query("DELETE FROM targets")
    suspend fun clearAll()
}
