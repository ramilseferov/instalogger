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
    val createdAt: Long = System.currentTimeMillis(),
    val latestStoryAt: Long = 0L // Epoch ms of newest active 24h story (0 if none)
) {
    val hasActiveStory: Boolean
        get() = latestStoryAt > 0L && (System.currentTimeMillis() - latestStoryAt) < ACTIVE_STORY_WINDOW_MS

    companion object {
        private const val ACTIVE_STORY_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}
