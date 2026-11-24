package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE username = :username ORDER BY archivedAt DESC")
    fun getHighlightsForUser(username: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlight_media WHERE highlightId = :highlightId ORDER BY takenAt ASC")
    fun getMediaForHighlight(highlightId: String): Flow<List<HighlightMediaEntity>>

    @Query("SELECT * FROM highlight_media WHERE highlightId = :highlightId ORDER BY takenAt ASC")
    suspend fun getMediaListForHighlight(highlightId: String): List<HighlightMediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlights(highlights: List<HighlightEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlightMedia(media: List<HighlightMediaEntity>)

    @Query("DELETE FROM highlights WHERE username = :username")
    suspend fun deleteHighlightsForUser(username: String)

    @Query("DELETE FROM highlight_media WHERE username = :username")
    suspend fun deleteMediaForUser(username: String)

    @Query("DELETE FROM highlights")
    suspend fun clearAll()

    @Query("DELETE FROM highlight_media")
    suspend fun clearAllMedia()
}
