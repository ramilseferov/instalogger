use axum::{
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::Arc;

use crate::api::AppState;

#[derive(Serialize, Deserialize)]
pub struct SettingsPayload {
    pub enable_termux_api: Option<bool>,
    pub telegram_bot_token: Option<String>,
    pub telegram_chat_id: Option<String>,
    pub session_cookie: Option<String>,
}

pub async fn get_settings(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let termux = state
        .db
        .get_setting("enable_termux_api")
        .unwrap_or(None)
        .map(|v| v == "true")
        .unwrap_or(true);
    let tg_token = state.db.get_setting("telegram_bot_token").unwrap_or(None).unwrap_or_default();
    let tg_chat = state.db.get_setting("telegram_chat_id").unwrap_or(None).unwrap_or_default();

    let session_exists = std::path::Path::new("sessions/instagram_session.json").exists();

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "settings": {
                "enable_termux_api": termux,
                "telegram_bot_token": if tg_token.is_empty() { "" } else { "••••••••" },
                "telegram_chat_id": tg_chat,
                "has_session_cookie": session_exists,
            }
        })),
    )
}

pub async fn update_settings(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<SettingsPayload>,
) -> impl IntoResponse {
    if let Some(termux) = payload.enable_termux_api {
        let _ = state.db.set_setting("enable_termux_api", &termux.to_string());
    }
    if let Some(token) = payload.telegram_bot_token {
        if !token.starts_with("•••") {
            let _ = state.db.set_setting("telegram_bot_token", &token);
        }
    }
    if let Some(chat_id) = payload.telegram_chat_id {
        let _ = state.db.set_setting("telegram_chat_id", &chat_id);
    }
    if let Some(cookie) = payload.session_cookie {
        if !cookie.trim().is_empty() {
            let _ = std::fs::create_dir_all("sessions");
            let _ = std::fs::write("sessions/instagram_session.json", cookie.trim());
        }
    }

    (StatusCode::OK, Json(json!({ "status": "ok" })))
}
