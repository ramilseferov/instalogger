package com.instalogger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChangeLogDto(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("target_id") val targetId: String = "",
    @SerializedName("event_type") val eventType: String? = null,
    @SerializedName("field_name") val fieldName: String? = null,
    @SerializedName("old_value") val oldValue: String? = null,
    @SerializedName("new_value") val newValue: String? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("detected_at") val detectedAt: String? = null
) {
    val displayEventName: String
        get() {
            val name = eventType ?: fieldName ?: "Dəyişiklik"
            return when (name) {
                "avatar_change" -> "Profil Şəkli Dəyişdirildi"
                "privacy_change" -> "Məxfilik Statusu Dəyişdi"
                "username_change" -> "İstifadəçi Adı Dəyişdi"
                "bio_change" -> "Bioqrafiya Dəyişdi"
                "post_count_change" -> "Paylaşım Sayı Dəyişdi"
                "post_added" -> "Yeni Paylaşım Aşkarlandı"
                "post_vanished" -> "Paylaşım Silindi / Arxivləndi"
                else -> name.replace("_", " ").uppercase()
            }
        }

    val displayTime: String
        get() = (timestamp ?: detectedAt)?.take(19)?.replace("T", " ") ?: ""
}

data class LogsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("logs") val logs: List<ChangeLogDto> = emptyList()
)
