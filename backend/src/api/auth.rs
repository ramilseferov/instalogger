use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    Json,
};
use rand::Rng;
use serde::Deserialize;
use serde_json::json;
use std::collections::HashSet;
use std::sync::{Arc, Mutex, OnceLock};

use crate::api::AppState;

static ACTIVE_TOKENS: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn token_store() -> &'static Mutex<HashSet<String>> {
    ACTIVE_TOKENS.get_or_init(|| Mutex::new(HashSet::new()))
}

fn hash_pin_salted(pin: &str, salt: &str) -> String {
    // Iterative keyed 256-bit mixing digest (pure Rust, no external crypto crate required)
    let mut h: [u64; 4] = [
        0x6a09e667f3bcc908,
        0xbb67ae8584caa73b,
        0x3c6ef372fe94f82b,
        0xa54ff53a5f1d36f1,
    ];
    let combined = format!("{}:instalogger:{}", salt, pin);
    let bytes = combined.as_bytes();
    for round in 0..4096u64 {
        for (i, &b) in bytes.iter().enumerate() {
            let idx = i % 4;
            h[idx] = h[idx]
                .wrapping_mul(0x100000001b3)
                .wrapping_add((b as u64).wrapping_add(round.wrapping_mul(31)))
                .rotate_left(((i as u32) * 7 + 13) % 64);
            h[(idx + 1) % 4] ^= h[idx].rotate_right(17);
        }
    }
    format!("{:016x}{:016x}{:016x}{:016x}", h[0], h[1], h[2], h[3])
}

fn random_hex(len_bytes: usize) -> String {
    let mut rng = rand::thread_rng();
    let mut s = String::with_capacity(len_bytes * 2);
    for _ in 0..len_bytes {
        let b: u8 = rng.gen();
        s.push_str(&format!("{:02x}", b));
    }
    s
}

#[derive(Deserialize)]
pub struct PinSetupPayload {
    pub pin: String,
    pub current_pin: Option<String>,
    pub timeout_minutes: Option<i64>,
}

#[derive(Deserialize)]
pub struct PinUnlockPayload {
    pub pin: String,
}

fn verify_stored_pin(stored: &str, candidate: &str) -> bool {
    let mut parts = stored.splitn(2, ':');
    let (Some(salt), Some(expected)) = (parts.next(), parts.next()) else {
        return false;
    };
    hash_pin_salted(candidate, salt) == expected
}

pub async fn get_auth_status(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let stored = state.db.get_setting("dashboard_pin_hash").ok().flatten().unwrap_or_default();
    let enabled = !stored.is_empty();
    let timeout_minutes = state
        .db
        .get_setting("dashboard_lock_timeout")
        .ok()
        .flatten()
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(15);

    let token = headers
        .get("x-auth-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let unlocked = !enabled
        || token_store()
            .lock()
            .map(|g| g.contains(token))
            .unwrap_or(false);

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "enabled": enabled,
            "locked": enabled && !unlocked,
            "timeout_minutes": timeout_minutes
        })),
    )
}

pub async fn setup_pin(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<PinSetupPayload>,
) -> impl IntoResponse {
    let stored = state.db.get_setting("dashboard_pin_hash").ok().flatten().unwrap_or_default();
    if !stored.is_empty() {
        let cur = payload.current_pin.as_deref().unwrap_or("");
        if !verify_stored_pin(&stored, cur) {
            return (
                StatusCode::UNAUTHORIZED,
                Json(json!({ "status": "error", "message": "Invalid current PIN" })),
            );
        }
    }

    if let Some(t) = payload.timeout_minutes {
        let _ = state.db.set_setting("dashboard_lock_timeout", &t.clamp(1, 1440).to_string());
    }

    let trimmed = payload.pin.trim();
    if trimmed.is_empty() {
        let _ = state.db.set_setting("dashboard_pin_hash", "");
        if let Ok(mut g) = token_store().lock() {
            g.clear();
        }
        return (
            StatusCode::OK,
            Json(json!({ "status": "ok", "enabled": false })),
        );
    }

    if trimmed.len() < 4 || trimmed.len() > 12 || !trimmed.chars().all(|c| c.is_ascii_digit()) {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "status": "error", "message": "PIN must be 4 to 12 digits" })),
        );
    }

    let salt = random_hex(16);
    let digest = hash_pin_salted(trimmed, &salt);
    let _ = state
        .db
        .set_setting("dashboard_pin_hash", &format!("{}:{}", salt, digest));

    let token = random_hex(24);
    if let Ok(mut g) = token_store().lock() {
        g.insert(token.clone());
    }

    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "enabled": true, "token": token })),
    )
}

pub async fn unlock_pin(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<PinUnlockPayload>,
) -> impl IntoResponse {
    let stored = state.db.get_setting("dashboard_pin_hash").ok().flatten().unwrap_or_default();
    if stored.is_empty() {
        return (
            StatusCode::OK,
            Json(json!({ "status": "ok", "token": "" })),
        );
    }
    if !verify_stored_pin(&stored, payload.pin.trim()) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "status": "error", "message": "Incorrect PIN" })),
        );
    }
    let token = random_hex(24);
    if let Ok(mut g) = token_store().lock() {
        g.insert(token.clone());
    }
    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "token": token })),
    )
}

pub async fn lock_dashboard(headers: HeaderMap) -> impl IntoResponse {
    if let Some(tok) = headers.get("x-auth-token").and_then(|v| v.to_str().ok()) {
        if let Ok(mut g) = token_store().lock() {
            g.remove(tok);
        }
    }
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}
