package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "highlight_media",
    indices = [
        Index(value = ["highlightId"]),
        Index(value = ["username"])
    ]
)
data class HighlightMediaEntity(
    @PrimaryKey
    val id: String, // Media item id
    val highlightId: String,
    val username: String,
    val mediaType: String, // "image" or "video"
    val mediaUrl: String,
    val localPath: String,
    val takenAt: Long = System.currentTimeMillis()
)
