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

    @Query("DELETE FROM targets WHERE LOWER(username) = LOWER(:username)")
    suspend fun deleteTarget(username: String)

    @Query("DELETE FROM targets")
    suspend fun clearAll()
}
