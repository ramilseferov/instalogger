package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TargetsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("targets") val targets: List<TargetDto> = emptyList()
)

data class TargetResponse(
    @SerializedName("status") val status: String,
    @SerializedName("target") val target: TargetDto?
)

data class LogsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("logs") val logs: List<ChangeLogDto> = emptyList()
)

data class AvatarItemDto(
    @SerializedName("filename") val filename: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("archived_at") val archivedAt: String,
    @SerializedName("url") val url: String
)

data class AvatarsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("avatars") val avatars: List<AvatarItemDto> = emptyList()
)

data class TurboResponse(
    @SerializedName("status") val status: String,
    @SerializedName("is_turbo") val isTurbo: Boolean
)

data class GenericStatusResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?
)
