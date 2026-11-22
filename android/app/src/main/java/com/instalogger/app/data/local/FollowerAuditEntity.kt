package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "follower_audits",
    indices = [
        Index(value = ["targetUsername"]),
        Index(value = ["eventType"])
    ]
)
data class FollowerAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetUsername: String,
    val eventType: String, // "follower_lost", "following_lost", "follower_gained", "following_gained"
    val username: String,
    val fullName: String = "",
    val profilePicUrl: String? = null,
    val localAvatarPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
