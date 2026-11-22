package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowerAuditDao {
    @Query("SELECT * FROM follower_audits WHERE targetUsername = :targetUsername ORDER BY timestamp DESC")
    fun getAuditsForTarget(targetUsername: String): Flow<List<FollowerAuditEntity>>

    @Query("SELECT * FROM follower_audits WHERE targetUsername = :targetUsername AND eventType = :eventType ORDER BY timestamp DESC")
    fun getAuditsByType(targetUsername: String, eventType: String): Flow<List<FollowerAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: FollowerAuditEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudits(audits: List<FollowerAuditEntity>)

    @Query("DELETE FROM follower_audits WHERE targetUsername = :targetUsername")
    suspend fun deleteAuditsForTarget(targetUsername: String)

    @Query("DELETE FROM follower_audits")
    suspend fun clearAll()
}
