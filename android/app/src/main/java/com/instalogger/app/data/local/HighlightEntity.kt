package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "highlights",
    indices = [Index(value = ["username"])]
)
data class HighlightEntity(
    @PrimaryKey
    val id: String, // Instagram highlight tray id (e.g. highlight:180...)
    val username: String,
    val title: String,
    val coverUrl: String,
    val localCoverPath: String? = null,
    val mediaCount: Int = 0,
    val archivedAt: Long = System.currentTimeMillis()
)
