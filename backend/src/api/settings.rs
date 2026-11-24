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
    pub clear_session_cookie: Option<bool>,
    pub pool_mode: Option<String>,
    pub pool_fixed_threshold: Option<i64>,
    pub pool_percent_threshold: Option<f64>,
    pub pool_digest_minutes: Option<i64>,
    pub pool_cooldown_minutes: Option<i64>,
    pub pool_anti_flicker: Option<bool>,
    pub pool_spike_alert: Option<bool>,
    pub pool_apply_following: Option<bool>,
    pub pool_suppress_logs: Option<bool>,
    pub list_completeness_guard: Option<bool>,
}

pub async fn get_settings(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let get_bool = |k: &str, def: bool| -> bool {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .map(|v| v == "true")
            .unwrap_or(def)
    };
    let get_i64 = |k: &str, def: i64| -> i64 {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(def)
    };
    let get_f64 = |k: &str, def: f64| -> f64 {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .and_then(|v| v.parse::<f64>().ok())
            .unwrap_or(def)
    };

    let termux = get_bool("enable_termux_api", true);
    let tg_token = state.db.get_setting("telegram_bot_token").unwrap_or(None).unwrap_or_default();
    let tg_chat = state.db.get_setting("telegram_chat_id").unwrap_or(None).unwrap_or_default();
    let pool_mode = state
        .db
        .get_setting("pool_mode")
        .unwrap_or(None)
        .unwrap_or_else(|| "OFF".to_string());

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
                "pool_mode": pool_mode,
                "pool_fixed_threshold": get_i64("pool_fixed_threshold", 50),
                "pool_percent_threshold": get_f64("pool_percent_threshold", 0.5),
                "pool_digest_minutes": get_i64("pool_digest_minutes", 0),
                "pool_cooldown_minutes": get_i64("pool_cooldown_minutes", 0),
                "pool_anti_flicker": get_bool("pool_anti_flicker", true),
                "pool_spike_alert": get_bool("pool_spike_alert", true),
                "pool_apply_following": get_bool("pool_apply_following", false),
                "pool_suppress_logs": get_bool("pool_suppress_logs", true),
                "list_completeness_guard": get_bool("list_completeness_guard", true),
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
    if payload.clear_session_cookie == Some(true) {
        let _ = std::fs::remove_file("sessions/instagram_session.json");
        crate::api::dev_logs::push_dev_log("I", "Session", "Instagram session cookie cleared");
    } else if let Some(cookie) = payload.session_cookie {
        if !cookie.trim().is_empty() {
            let _ = std::fs::create_dir_all("sessions");
            let _ = std::fs::write("sessions/instagram_session.json", cookie.trim());
            crate::api::dev_logs::push_dev_log("I", "Session", "Instagram session cookie updated");
        }
    }
    if let Some(mode) = payload.pool_mode {
        let normalized = match mode.to_uppercase().as_str() {
            "FIXED_COUNT" | "PERCENTAGE" | "AUTO_ADAPTIVE" => mode.to_uppercase(),
            _ => "OFF".to_string(),
        };
        let _ = state.db.set_setting("pool_mode", &normalized);
        crate::api::dev_logs::push_dev_log("I", "PoolPolicy", &format!("Global pool_mode updated to {}", normalized));
    }
    if let Some(v) = payload.pool_fixed_threshold {
        let _ = state.db.set_setting("pool_fixed_threshold", &v.clamp(1, 1_000_000).to_string());
    }
    if let Some(v) = payload.pool_percent_threshold {
        let _ = state.db.set_setting("pool_percent_threshold", &v.clamp(0.01, 100.0).to_string());
    }
    if let Some(v) = payload.pool_digest_minutes {
        let _ = state.db.set_setting("pool_digest_minutes", &v.max(0).to_string());
    }
    if let Some(v) = payload.pool_cooldown_minutes {
        let _ = state.db.set_setting("pool_cooldown_minutes", &v.max(0).to_string());
    }
    if let Some(v) = payload.pool_anti_flicker {
        let _ = state.db.set_setting("pool_anti_flicker", &v.to_string());
    }
    if let Some(v) = payload.pool_spike_alert {
        let _ = state.db.set_setting("pool_spike_alert", &v.to_string());
    }
    if let Some(v) = payload.pool_apply_following {
        let _ = state.db.set_setting("pool_apply_following", &v.to_string());
    }
    if let Some(v) = payload.pool_suppress_logs {
        let _ = state.db.set_setting("pool_suppress_logs", &v.to_string());
    }
    if let Some(v) = payload.list_completeness_guard {
        let _ = state.db.set_setting("list_completeness_guard", &v.to_string());
    }

    (StatusCode::OK, Json(json!({ "status": "ok" })))
}
