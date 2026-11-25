package com.instalogger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostCommentDao {
    @Query("SELECT * FROM post_comments WHERE postId = :postId ORDER BY createdAt ASC")
    fun getCommentsForPostFlow(postId: String): Flow<List<PostCommentEntity>>

    @Query("SELECT * FROM post_comments WHERE postId = :postId ORDER BY createdAt ASC")
    suspend fun getCommentsForPost(postId: String): List<PostCommentEntity>

    @Query("SELECT COUNT(*) FROM post_comments WHERE postId = :postId")
    fun getCommentsCountForPostFlow(postId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM post_comments WHERE postId = :postId")
    suspend fun getCommentsCountForPost(postId: String): Int

    @Query("SELECT * FROM post_comments WHERE LOWER(username) = LOWER(:username) ORDER BY createdAt DESC")
    fun getCommentsForTargetFlow(username: String): Flow<List<PostCommentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComments(comments: List<PostCommentEntity>)

    @Query("DELETE FROM post_comments WHERE postId = :postId")
    suspend fun deleteCommentsForPost(postId: String)

    @Query("DELETE FROM post_comments WHERE LOWER(username) = LOWER(:username)")
    suspend fun deleteCommentsForUser(username: String)
}
