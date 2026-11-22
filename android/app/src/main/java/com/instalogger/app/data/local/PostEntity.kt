package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "posts",
    indices = [Index(value = ["username"])]
)
data class PostEntity(
    @PrimaryKey
    val id: String, // Shortcode or ID
    val username: String,
    val caption: String? = null,
    val displayUrl: String,
    val localPath: String? = null,
    val isVideo: Boolean = false,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val postedAt: Long = System.currentTimeMillis()
)
