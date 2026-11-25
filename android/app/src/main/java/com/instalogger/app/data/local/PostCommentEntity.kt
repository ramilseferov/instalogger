package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "post_comments",
    indices = [
        Index(value = ["postId"]),
        Index(value = ["username"]),
        Index(value = ["postId", "parentCommentId"])
    ]
)
data class PostCommentEntity(
    @PrimaryKey
    val id: String,
    val postId: String,
    val mediaId: String? = null,
    val username: String,
    val parentCommentId: String? = null,
    val authorUsername: String,
    val authorId: String? = null,
    val authorFullName: String = "",
    val authorAvatarUrl: String? = null,
    val isAuthorVerified: Boolean = false,
    val text: String,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val archivedAt: Long = System.currentTimeMillis(),
    val collectionStatus: String = "complete", // "complete" or "partial"
    val hasGifOrMedia: Boolean = false
)
