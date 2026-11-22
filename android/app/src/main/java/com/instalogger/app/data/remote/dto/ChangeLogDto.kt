package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChangeLogDto(
    @SerializedName("id") val id: Long,
    @SerializedName("target_id") val targetId: Long,
    @SerializedName("field_name") val fieldName: String,
    @SerializedName("old_value") val oldValue: String?,
    @SerializedName("new_value") val newValue: String?,
    @SerializedName("detected_at") val detectedAt: String
)
