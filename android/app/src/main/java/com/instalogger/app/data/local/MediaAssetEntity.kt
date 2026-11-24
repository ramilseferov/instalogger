package com.instalogger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["postId"]),
        Index(value = ["username", "sha256Hash"])
    ]
)
data class MediaAssetEntity(
    @PrimaryKey
    val id: String, // "${postId}_$position"
    val postId: String,
    val username: String,
    val position: Int = 0,
    val mediaType: String, // "image" or "video"
    val remoteUrl: String,
    val localVaultPath: String? = null,
    val thumbVaultPath: String? = null,
    val mimeType: String? = null,
    val fileSize: Long = 0L,
    val sha256Hash: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Int? = null,
    val providerName: String? = null,
    val archivedAt: Long = System.currentTimeMillis()
)
