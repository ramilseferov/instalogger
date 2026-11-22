package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AvatarsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("avatars") val avatars: List<String> = emptyList()
)
