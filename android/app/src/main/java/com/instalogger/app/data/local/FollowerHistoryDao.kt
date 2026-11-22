package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowerHistoryDao {
    @Query("SELECT * FROM follower_history WHERE targetUsername = :targetUsername ORDER BY timestamp ASC")
    fun getHistoryForTarget(targetUsername: String): Flow<List<FollowerHistoryEntity>>

    @Query("SELECT * FROM follower_history WHERE targetUsername = :targetUsername ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(targetUsername: String, limit: Int = 100): Flow<List<FollowerHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: FollowerHistoryEntity): Long

    @Query("DELETE FROM follower_history WHERE targetUsername = :targetUsername")
    suspend fun deleteHistoryForTarget(targetUsername: String)

    @Query("DELETE FROM follower_history")
    suspend fun clearAll()
}
