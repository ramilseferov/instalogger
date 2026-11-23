package com.instalogger.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChangeLogDao {
    @Query("SELECT * FROM change_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ChangeLogEntity>>

    @Query("SELECT * FROM change_logs WHERE targetUsername = :username ORDER BY timestamp DESC")
    fun getLogsForTarget(username: String): Flow<List<ChangeLogEntity>>

    @Query("SELECT * FROM change_logs WHERE targetUsername = :username AND eventType = :eventType ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLogForEvent(username: String, eventType: String): ChangeLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ChangeLogEntity): Long

    @Query("DELETE FROM change_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM change_logs WHERE targetUsername = :username")
    suspend fun deleteLogsForTarget(username: String)

    @Query("DELETE FROM change_logs")
    suspend fun clearAll()
}
