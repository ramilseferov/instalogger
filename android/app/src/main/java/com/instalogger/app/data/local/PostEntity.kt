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
    val postedAt: Long = System.currentTimeMillis(),
    val mediaType: String = "image", // "image", "video", "carousel"
    val slideCount: Int = 1,
    val localThumbPath: String? = null,
    val durationSeconds: Int? = null,
    val downloadEngine: String? = null, // "yt-dlp", "native-graph", "daemon", etc.
    val downloadStatus: String = "pending", // "pending", "complete", "partial", "failed"
    val isVanished: Boolean = false,
    val vanishedAt: Long? = null
)
