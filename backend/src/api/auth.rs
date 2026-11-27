use crate::{api::AppState, db::Database};
use axum::{extract::Request, http::header, middleware::Next, response::Response};
use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    Json,
};
use rand::Rng;
use serde::Deserialize;
use serde_json::json;
use std::{
    collections::HashMap,
    sync::{Arc, MutexGuard},
    time::{Duration, Instant},
};

#[derive(Default)]
pub struct Sessions {
    tokens: HashMap<String, Instant>,
    failures: u32,
    retry_after: Option<Instant>,
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

fn constant_time_eq(a: &str, b: &str) -> bool {
    let ba = a.as_bytes();
    let bb = b.as_bytes();
    if ba.len() != bb.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in ba.iter().zip(bb.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn verify_stored_pin(stored: &str, candidate: &str) -> bool {
    let mut parts = stored.splitn(2, ':');
    let (Some(salt), Some(expected)) = (parts.next(), parts.next()) else {
        return false;
    };
    constant_time_eq(&hash_pin_salted(candidate, salt), expected)
}

fn valid_pin(pin: &str) -> bool {
    (4..=12).contains(&pin.len()) && pin.bytes().all(|b| b.is_ascii_digit())
}

/// Bootstrap only from operator-supplied input before opening the listener.
pub fn initialize_pin(db: &Database, pin: &str) -> anyhow::Result<()> {
    anyhow::ensure!(valid_pin(pin), "Dashboard PIN must contain 4 to 12 digits");
    let salt = random_hex(16);
    db.initialize_dashboard_pin(&format!("{}:{}", salt, hash_pin_salted(pin, &salt)))
}

type ApiError = (StatusCode, Json<serde_json::Value>);
fn error(code: StatusCode, message: &str) -> ApiError {
    (code, Json(json!({"status":"error", "message": message})))
}

fn sessions(state: &AppState) -> Result<MutexGuard<'_, Sessions>, ApiError> {
    state
        .sessions
        .lock()
        .map_err(|_| error(StatusCode::SERVICE_UNAVAILABLE, "Session store unavailable"))
}

fn stored_pin(db: &Database) -> Result<String, ApiError> {
    db.get_setting("dashboard_pin_hash")
        .ok()
        .flatten()
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            error(
                StatusCode::SERVICE_UNAVAILABLE,
                "Dashboard PIN is unavailable",
            )
        })
}

fn timeout(db: &Database) -> Result<i64, ApiError> {
    db.get_setting("dashboard_lock_timeout")
        .map(|value| {
            value
                .and_then(|v| v.parse::<i64>().ok())
                .unwrap_or(15)
                .clamp(1, 1440)
        })
        .map_err(|_| {
            error(
                StatusCode::SERVICE_UNAVAILABLE,
                "Session settings unavailable",
            )
        })
}

fn token(headers: &HeaderMap) -> Option<&str> {
    if let Some(tok) = headers.get("x-auth-token").and_then(|v| v.to_str().ok()) {
        if !tok.is_empty() {
            return Some(tok);
        }
    }
    for cookie_hdr in headers.get_all(header::COOKIE) {
        if let Ok(raw) = cookie_hdr.to_str() {
            if let Some(tok) = raw
                .split(';')
                .find_map(|v| v.trim().strip_prefix("instalogger_session="))
            {
                if !tok.is_empty() {
                    return Some(tok);
                }
            }
        }
    }
    None
}

fn same_origin_ok(headers: &HeaderMap) -> bool {
    let Some(origin) = headers.get(header::ORIGIN) else {
        return true;
    };
    let origin_host = origin
        .to_str()
        .ok()
        .and_then(|o| o.parse::<axum::http::Uri>().ok())
        .and_then(|uri| uri.authority().map(|a| a.as_str().to_owned()));
    origin_host.is_some()
        && origin_host.as_deref() == headers.get(header::HOST).and_then(|h| h.to_str().ok())
}

pub(super) fn authorized(state: &AppState, headers: &HeaderMap) -> bool {
    let Ok(mut guard) = sessions(state) else {
        return false;
    };
    guard
        .tokens
        .retain(|_, deadline| *deadline > Instant::now());
    token(headers).is_some_and(|t| guard.tokens.contains_key(t))
}

pub async fn require_session(
    State(state): State<Arc<AppState>>,
    request: Request,
    next: Next,
) -> Response {
    let headers = request.headers();
    if !same_origin_ok(headers) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !authorized(&state, headers) {
        return error(StatusCode::UNAUTHORIZED, "Dashboard locked").into_response();
    }
    let mut response = next.run(request).await;
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    response
}

pub async fn get_auth_status(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    stored_pin(&state.db)?;
    Ok(Json(
        json!({"status":"ok", "enabled":true, "locked":!authorized(&state,&headers),
        "timeout_minutes":timeout(&state.db)?}),
    ))
}

fn issue(guard: &mut Sessions, minutes: i64) -> Response {
    let token = random_hex(24);
    let now = Instant::now();
    guard.tokens.retain(|_, deadline| *deadline > now);
    guard.tokens.insert(
        token.clone(),
        now + Duration::from_secs(minutes as u64 * 60),
    );
    guard.failures = 0;
    guard.retry_after = None;
    let cookie = format!("instalogger_session={token}; Path=/; HttpOnly; Secure; SameSite=Strict");
    (
        [
            (header::SET_COOKIE, cookie),
            (header::CACHE_CONTROL, "no-store".into()),
        ],
        Json(json!({"status":"ok","enabled":true,"token":token})),
    )
        .into_response()
}

pub async fn setup_pin(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<PinSetupPayload>,
) -> Result<Response, ApiError> {
    let mut guard = sessions(&state)?;
    if guard.retry_after.is_some_and(|t| t > Instant::now()) {
        return Err(error(StatusCode::TOO_MANY_REQUESTS, "Try again later"));
    }
    let stored = stored_pin(&state.db)?;
    let current = payload.current_pin.as_deref().unwrap_or("").trim();
    if !verify_stored_pin(&stored, current) {
        guard.failures += 1;
        if guard.failures >= 5 {
            guard.failures = 0;
            guard.retry_after = Some(Instant::now() + Duration::from_secs(60));
        }
        return Err(error(StatusCode::UNAUTHORIZED, "Invalid current PIN"));
    }
    let pin = payload.pin.trim();
    if !valid_pin(pin) {
        return Err(error(StatusCode::BAD_REQUEST, "PIN must be 4 to 12 digits"));
    }
    let minutes = payload
        .timeout_minutes
        .unwrap_or(timeout(&state.db)?)
        .clamp(1, 1440);
    let salt = random_hex(16);
    state
        .db
        .update_dashboard_pin(
            &format!("{}:{}", salt, hash_pin_salted(pin, &salt)),
            minutes,
        )
        .map_err(|_| error(StatusCode::SERVICE_UNAVAILABLE, "Could not update PIN"))?;
    guard.tokens.clear();
    Ok(issue(&mut guard, minutes))
}

pub async fn unlock_pin(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(payload): Json<PinUnlockPayload>,
) -> Result<Response, ApiError> {
    if !same_origin_ok(&headers) {
        return Err(error(StatusCode::FORBIDDEN, "Cross-origin request rejected"));
    }
    let mut guard = sessions(&state)?;
    if guard.retry_after.is_some_and(|t| t > Instant::now()) {
        return Err(error(StatusCode::TOO_MANY_REQUESTS, "Try again later"));
    }
    let stored = stored_pin(&state.db)?;
    let pin = payload.pin.trim();
    if !valid_pin(pin) || !verify_stored_pin(&stored, pin) {
        guard.failures += 1;
        if guard.failures >= 5 {
            guard.failures = 0;
            guard.retry_after = Some(Instant::now() + Duration::from_secs(60));
        }
        return Err(error(StatusCode::UNAUTHORIZED, "Incorrect PIN"));
    }
    Ok(issue(&mut guard, timeout(&state.db)?))
}

pub async fn lock_dashboard(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, ApiError> {
    if let Some(tok) = token(&headers) {
        sessions(&state)?.tokens.remove(tok);
    }
    Ok((
        [(
            header::SET_COOKIE,
            "instalogger_session=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0",
        )],
        Json(json!({"status":"ok"})),
    ))
}
