package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "targets")
data class TargetEntity(
    @PrimaryKey
    val username: String,
    val instagramId: String? = null,
    val fullName: String = "",
    val biography: String = "",
    val isPrivate: Boolean = false,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val mediaCount: Long = 0,
    val avatarUrl: String? = null,
    val localAvatarPath: String? = null,
    val avatarHash: String? = null,
    val profileHash: String? = null,
    val isTurbo: Boolean = false,
    val isActive: Boolean = true,
    val checkIntervalSeconds: Long = 60,
    val lastCheckedAt: Long? = null,
    val lastStatusChangeAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
