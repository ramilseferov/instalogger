package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TargetDto(
    @SerializedName("id") val id: String,
    @SerializedName("username") val username: String,
    @SerializedName("full_name") val fullName: String? = null,
    @SerializedName("biography") val biography: String? = null,
    @SerializedName("is_private") val isPrivate: Boolean = false,
    @SerializedName("followed_by_viewer") val followedByViewer: Boolean = false,
    @SerializedName("media_count") val mediaCount: Long = 0,
    @SerializedName("profile_pic_path") val profilePicPath: String? = null,
    @SerializedName("profile_pic_url") val profilePicUrl: String? = null,
    @SerializedName("status") val status: String? = "active",
    @SerializedName("turbo_mode") val turboMode: Boolean = false,
    @SerializedName("is_turbo") val isTurbo: Boolean = false,
    @SerializedName("check_interval_seconds") val checkIntervalSeconds: Long = 300,
    @SerializedName("last_checked_at") val lastCheckedAt: String? = null,
    @SerializedName("last_status_change_at") val lastStatusChangeAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val isTurboActive: Boolean
        get() = turboMode || isTurbo

    fun getResolvedAvatarUrl(baseUrl: String): String? {
        val path = profilePicPath ?: profilePicUrl ?: return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val cleanBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val cleanPath = path.removePrefix("/")
        return cleanBase + cleanPath
    }
}

data class CreateTargetRequest(
    @SerializedName("username") val username: String,
    @SerializedName("turbo") val turbo: Boolean = false
)

data class TargetsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("targets") val targets: List<TargetDto> = emptyList()
)

data class TargetResponse(
    @SerializedName("status") val status: String,
    @SerializedName("target") val target: TargetDto?
)

data class TurboResponse(
    @SerializedName("status") val status: String,
    @SerializedName("is_turbo") val isTurbo: Boolean
)

data class GenericStatusResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String? = null
)
