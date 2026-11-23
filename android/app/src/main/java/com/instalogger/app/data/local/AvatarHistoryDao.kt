package com.instalogger.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AvatarHistoryDao {
    @Query("SELECT * FROM avatar_history WHERE username = :username ORDER BY archivedAt DESC")
    fun getAvatarsForTarget(username: String): Flow<List<AvatarHistoryEntity>>

    @Query("SELECT * FROM avatar_history WHERE username = :username ORDER BY archivedAt DESC")
    suspend fun getAvatarsListForTarget(username: String): List<AvatarHistoryEntity>

    @Query("DELETE FROM avatar_history WHERE localPath LIKE '%avatar_current.jpg%'")
    suspend fun cleanCurrentAvatarDuplicates()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatar(avatar: AvatarHistoryEntity): Long

    @Query("DELETE FROM avatar_history WHERE id = :id")
    suspend fun deleteAvatarById(id: Long)

    @Delete
    suspend fun deleteAvatar(avatar: AvatarHistoryEntity)

    @Query("DELETE FROM avatar_history WHERE username = :username")
    suspend fun deleteAvatarsForTarget(username: String)

    @Query("DELETE FROM avatar_history")
    suspend fun clearAll()
}
