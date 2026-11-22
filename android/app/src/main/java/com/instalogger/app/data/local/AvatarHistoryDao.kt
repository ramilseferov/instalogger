package com.instalogger.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AvatarHistoryDao {
    @Query("SELECT * FROM avatar_history WHERE username = :username ORDER BY archivedAt DESC")
    fun getAvatarsForTarget(username: String): Flow<List<AvatarHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatar(avatar: AvatarHistoryEntity): Long

    @Query("DELETE FROM avatar_history WHERE username = :username")
    suspend fun deleteAvatarsForTarget(username: String)

    @Query("DELETE FROM avatar_history")
    suspend fun clearAll()
}
