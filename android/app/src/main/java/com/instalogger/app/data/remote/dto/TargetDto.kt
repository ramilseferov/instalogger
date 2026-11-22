package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TargetDto(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("target_type") val targetType: String,
    @SerializedName("is_private") val isPrivate: Boolean,
    @SerializedName("is_verified") val isVerified: Boolean,
    @SerializedName("followers_count") val followersCount: Long,
    @SerializedName("following_count") val followingCount: Long,
    @SerializedName("posts_count") val postsCount: Long,
    @SerializedName("profile_pic_url") val profilePicUrl: String?,
    @SerializedName("check_interval") val checkInterval: Long,
    @SerializedName("is_turbo") val isTurbo: Boolean,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("last_checked_at") val lastCheckedAt: String?,
    @SerializedName("last_changed_at") val lastChangedAt: String?,
    @SerializedName("error_count") val errorCount: Int,
    @SerializedName("last_error") val lastError: String?
)

data class CreateTargetRequest(
    @SerializedName("username") val username: String,
    @SerializedName("target_type") val targetType: String = "user",
    @SerializedName("check_interval") val checkInterval: Long = 60,
    @SerializedName("is_turbo") val isTurbo: Boolean = false
)

data class UpdateTargetRequest(
    @SerializedName("check_interval") val checkInterval: Long?,
    @SerializedName("is_turbo") val isTurbo: Boolean?,
    @SerializedName("is_active") val isActive: Boolean?
)
