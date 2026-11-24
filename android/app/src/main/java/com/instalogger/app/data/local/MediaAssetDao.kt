package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE postId = :postId ORDER BY position ASC")
    fun getAssetsForPostFlow(postId: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE postId = :postId ORDER BY position ASC")
    suspend fun getAssetsForPost(postId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE username = :username AND sha256Hash = :hash AND localVaultPath IS NOT NULL LIMIT 1")
    suspend fun findByHash(username: String, hash: String): MediaAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssets(assets: List<MediaAssetEntity>)

    @Query("DELETE FROM media_assets WHERE postId = :postId")
    suspend fun deleteAssetsForPost(postId: String)

    @Query("DELETE FROM media_assets WHERE username = :username")
    suspend fun deleteAssetsForUser(username: String)

    @Query("DELETE FROM media_assets")
    suspend fun clearAll()
}
