package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "avatar_history")
data class AvatarHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val localPath: String,
    val archivedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0,
    val fileHash: String? = null
)
