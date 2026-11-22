package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "change_logs")
data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetUsername: String,
    val eventType: String,
    val title: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val oldAvatarPath: String? = null,
    val newAvatarPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
