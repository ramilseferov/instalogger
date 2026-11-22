package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SettingsData(
    @SerializedName("enable_termux_api") val enableTermuxApi: Boolean = true,
    @SerializedName("telegram_bot_token") val telegramBotToken: String = "",
    @SerializedName("telegram_chat_id") val telegramChatId: String = "",
    @SerializedName("has_session_cookie") val hasSessionCookie: Boolean = false
)

data class SettingsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("settings") val settings: SettingsData
)

data class UpdateSettingsRequest(
    @SerializedName("enable_termux_api") val enableTermuxApi: Boolean? = null,
    @SerializedName("telegram_bot_token") val telegramBotToken: String? = null,
    @SerializedName("telegram_chat_id") val telegramChatId: String? = null,
    @SerializedName("session_cookie") val sessionCookie: String? = null
)
