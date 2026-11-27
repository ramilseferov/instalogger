package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    @Query("SELECT * FROM posts WHERE username = :username ORDER BY postedAt DESC")
    fun getPostsForUser(username: String): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE username = :username ORDER BY postedAt DESC")
    suspend fun getPostsListForUser(username: String): List<PostEntity>

    @Query("SELECT * FROM posts WHERE id = :postId LIMIT 1")
    suspend fun getPostById(postId: String): PostEntity?

    @Query("SELECT COUNT(*) FROM posts WHERE username = :username")
    suspend fun getPostsCountForUser(username: String): Int

    @Query("SELECT COUNT(*) FROM posts WHERE username = :username AND isVanished = 0")
    suspend fun getActivePostsCountForUser(username: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: PostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosts(posts: List<PostEntity>)

    @Query("SELECT * FROM posts WHERE username = :username AND isVanished = 0 ORDER BY postedAt DESC")
    suspend fun getActivePostsListForUser(username: String): List<PostEntity>

    @Query("UPDATE posts SET isVanished = 1, vanishedAt = :vanishedAt WHERE id = :postId")
    suspend fun markPostVanished(postId: String, vanishedAt: Long)

    @Query("DELETE FROM posts WHERE id = :postId")
    suspend fun deletePostById(postId: String)

    @Query("DELETE FROM posts WHERE username = :username")
    suspend fun deletePostsForUser(username: String)

    @Query("DELETE FROM posts")
    suspend fun clearAll()
}
