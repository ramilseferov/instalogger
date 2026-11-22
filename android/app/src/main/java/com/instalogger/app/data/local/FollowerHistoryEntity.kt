package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "follower_history",
    indices = [
        Index(value = ["targetUsername"])
    ]
)
data class FollowerHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetUsername: String,
    val followersCount: Long,
    val followingCount: Long,
    val mediaCount: Long,
    val timestamp: Long = System.currentTimeMillis()
)
