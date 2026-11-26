use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use chrono::Utc;
use serde::Deserialize;
use serde_json::json;
use std::sync::Arc;
use tracing::{error, info};

use crate::api::AppState;
use crate::models::Target;

#[derive(Deserialize)]
pub struct AddTargetPayload {
    pub username: String,
    pub turbo: Option<bool>,
}

#[derive(Deserialize)]
pub struct ToggleTurboPayload {
    pub turbo: bool,
}

#[derive(Deserialize)]
pub struct SetStatusPayload {
    pub status: String, // active | paused
}

#[derive(Deserialize)]
pub struct SetIntervalPayload {
    pub check_interval_seconds: i64,
}

#[derive(Deserialize)]
pub struct HistoryQuery {
    pub range: Option<String>, // 24h | 7d | 30d | all
}

pub async fn list_targets(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    match state.db.list_targets() {
        Ok(targets) => (StatusCode::OK, Json(json!({ "status": "ok", "targets": targets }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn get_target(
    State(state): State<Arc<AppState>>,
    Path(id_or_username): Path<String>,
) -> impl IntoResponse {
    match state.db.get_target(&id_or_username) {
        Ok(Some(target)) => (StatusCode::OK, Json(json!({ "status": "ok", "target": target }))),
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "status": "not_found", "message": "Target not found" })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn add_target(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<AddTargetPayload>,
) -> impl IntoResponse {
    let clean_username = payload.username.trim().trim_start_matches('@').to_string();

    if clean_username.is_empty()
        || clean_username.len() > 30
        || !clean_username.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.')
        || clean_username.contains("..")
    {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({
                "status": "error",
                "message": "Invalid username. Must be 1-30 characters containing only letters, numbers, underscores, or periods."
            })),
        );
    }

    info!("Adding new target: @{}", clean_username);

    // Resolve profile
    let session_file = format!("sessions/session_{}.json", clean_username);
    let session_path = if std::path::Path::new(&session_file).exists() {
        Some(session_file.as_str())
    } else if std::path::Path::new("sessions/instagram_session.json").exists() {
        Some("sessions/instagram_session.json")
    } else {
        None
    };

    let mut initial_warning: Option<String> = None;
    let snapshot = match state
        .scheduler
        .worker
        .check_profile(&clean_username, session_path)
        .await
    {
        Ok(s) => {
            crate::api::dev_logs::push_dev_log(
                "I",
                "Targets",
                &format!(
                    "Added @{} (followers={}, following={}, posts={})",
                    clean_username, s.followers_count, s.following_count, s.media_count
                ),
            );
            s
        }
        Err(e) => {
            error!("Initial profile fetch failed for @{}: {}", clean_username, e);
            crate::api::dev_logs::push_dev_log(
                "W",
                "Targets",
                &format!("Initial fetch failed for @{}: {}", clean_username, e),
            );
            initial_warning = Some(e.to_string());
            crate::scheduler::ProfileSnapshot {
                id: format!("usr_{}", clean_username),
                username: clean_username.clone(),
                full_name: "".to_string(),
                biography: "".to_string(),
                is_private: false,
                followed_by_viewer: false,
                media_count: 0,
                followers_count: 0,
                following_count: 0,
                latest_story_at: 0,
                profile_pic_url: "".to_string(),
                profile_pic_path: None,
                avatar_hash: None,
                profile_hash: None,
            }
        }
    };

    let target = Target {
        id: snapshot.id.clone(),
        username: snapshot.username.clone(),
        full_name: snapshot.full_name.clone(),
        biography: snapshot.biography.clone(),
        is_private: snapshot.is_private,
        followed_by_viewer: snapshot.followed_by_viewer,
        media_count: snapshot.media_count,
        followers_count: snapshot.followers_count,
        following_count: snapshot.following_count,
        latest_story_at: snapshot.latest_story_at,
        profile_pic_path: snapshot.profile_pic_path.clone(),
        avatar_hash: snapshot.avatar_hash.clone(),
        profile_hash: snapshot.profile_hash.clone(),
        status: "active".to_string(),
        turbo_mode: payload.turbo.unwrap_or(false),
        check_interval_seconds: 300,
        last_checked_at: Some(Utc::now()),
        last_status_change_at: None,
        created_at: Utc::now(),
    };

    if let Err(e) = state.db.create_target(&target) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        );
    }

    // If initial fetch succeeded and account is public or authorized follower, trigger archival with session
    if initial_warning.is_none() && (!snapshot.is_private || snapshot.followed_by_viewer) {
        let scheduler_clone = Arc::clone(&state.scheduler);
        let u_clone = snapshot.username.clone();
        let id_clone = snapshot.id.clone();
        let sess_owned = session_path.map(|s| s.to_string());
        tokio::spawn(async move {
            let _ = scheduler_clone
                .trigger_full_archive(&u_clone, &id_clone, sess_owned.as_deref())
                .await;
        });
    }

    (
        StatusCode::CREATED,
        Json(json!({ "status": "ok", "target": target, "warning": initial_warning })),
    )
}

pub async fn delete_target(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    match state.db.delete_target(&id) {
        Ok(_) => (StatusCode::OK, Json(json!({ "status": "ok" }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn toggle_turbo(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(payload): Json<ToggleTurboPayload>,
) -> impl IntoResponse {
    match state.db.set_turbo_mode(&id, payload.turbo) {
        Ok(_) => (StatusCode::OK, Json(json!({ "status": "ok", "turbo": payload.turbo }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn set_target_status(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(payload): Json<SetStatusPayload>,
) -> impl IntoResponse {
    let norm = if payload.status.eq_ignore_ascii_case("paused") {
        "paused"
    } else {
        "active"
    };
    match state.db.set_target_status(&id, norm) {
        Ok(_) => (StatusCode::OK, Json(json!({ "status": "ok", "target_status": norm }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn set_target_interval(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(payload): Json<SetIntervalPayload>,
) -> impl IntoResponse {
    let clamped = payload.check_interval_seconds.clamp(30, 86400);
    match state.db.set_target_interval(&id, clamped) {
        Ok(_) => (
            StatusCode::OK,
            Json(json!({ "status": "ok", "check_interval_seconds": clamped })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn get_target_history(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    axum::extract::Query(query): axum::extract::Query<HistoryQuery>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        Ok(None) => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            )
        }
    };
    let since_iso = match query.range.as_deref().unwrap_or("30d") {
        "24h" => Some((Utc::now() - chrono::Duration::hours(24)).to_rfc3339()),
        "7d" => Some((Utc::now() - chrono::Duration::days(7)).to_rfc3339()),
        "30d" => Some((Utc::now() - chrono::Duration::days(30)).to_rfc3339()),
        _ => None,
    };
    let points = state
        .db
        .list_follower_history(&target.id, since_iso.as_deref())
        .unwrap_or_default();
    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "history": points })),
    )
}

pub async fn get_media_assets(
    State(state): State<Arc<AppState>>,
    Path((_target_id, media_id)): Path<(String, String)>,
) -> impl IntoResponse {
    let assets = state.db.list_media_assets(&media_id).unwrap_or_default();
    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "assets": assets })),
    )
}

pub async fn delete_target_media_item(
    State(state): State<Arc<AppState>>,
    Path((id, media_id)): Path<(String, String)>,
) -> impl IntoResponse {
    let _target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        _ => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
    };
    let mut freed_bytes: u64 = 0;
    if let Ok(Some(item)) = state.db.delete_media_item(&media_id) {
        let rel = item.relative_file_path.trim_start_matches('/');
        if rel.starts_with("archives/") && !rel.contains("..") {
            let p = std::path::Path::new(rel);
            if let Ok(meta) = std::fs::metadata(p) {
                freed_bytes = freed_bytes.saturating_add(meta.len());
            }
            let _ = std::fs::remove_file(p);
            if let Some(stem) = p.file_stem().and_then(|s| s.to_str()) {
                if let Some(parent) = p.parent() {
                    for suffix in ["_thumb.jpg", "_caption.txt", "_meta.json"] {
                        let sidecar = parent.join(format!("{}{}", stem, suffix));
                        if let Ok(meta) = std::fs::metadata(&sidecar) {
                            freed_bytes = freed_bytes.saturating_add(meta.len());
                        }
                        let _ = std::fs::remove_file(&sidecar);
                    }
                }
            }
        }
    }
    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "freed_bytes": freed_bytes })),
    )
}

pub async fn delete_target_avatar(
    State(state): State<Arc<AppState>>,
    Path((id, filename)): Path<(String, String)>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        _ => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
    };
    if filename.contains('/') || filename.contains('\\') || filename.contains("..") || !filename.starts_with("avatar_") {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "status": "error", "message": "Invalid avatar filename" })),
        );
    }
    let safe_id = target.id.replace('/', "").replace('\\', "").replace("..", "");
    let path = format!("archives/{}/profile/{}", safe_id, filename);
    let _ = std::fs::remove_file(&path);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn check_target_now(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        Ok(None) => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            )
        }
    };

    match state.scheduler.check_target(&target).await {
        Ok(_) => {
            crate::api::dev_logs::push_dev_log("I", "Inspector", &format!("Checked @{} successfully", target.username));
            let fresh = state.db.get_target(&target.id).ok().flatten().unwrap_or(target);
            (StatusCode::OK, Json(json!({ "status": "ok", "target": fresh })))
        }
        Err(e) => {
            crate::api::dev_logs::push_dev_log("E", "Inspector", &format!("Check failed for @{}: {}", target.username, e));
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            )
        }
    }
}

pub async fn sync_target_media(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        Ok(None) => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            )
        }
    };

    let session_file = format!("sessions/session_{}.json", target.username);
    let sess_owned = if std::path::Path::new(&session_file).exists() {
        Some(session_file)
    } else if std::path::Path::new("sessions/instagram_session.json").exists() {
        Some("sessions/instagram_session.json".to_string())
    } else {
        None
    };

    let scheduler_clone = Arc::clone(&state.scheduler);
    let u_clone = target.username.clone();
    let id_clone = target.id.clone();
    tokio::spawn(async move {
        if let Err(e) = scheduler_clone
            .trigger_full_archive(&u_clone, &id_clone, sess_owned.as_deref())
            .await
        {
            error!("Manual media sync failed for @{}: {}", u_clone, e);
        }
    });

    (
        StatusCode::ACCEPTED,
        Json(json!({ "status": "ok", "message": "Media sync started" })),
    )
}

#[derive(Deserialize)]
pub struct TargetPoolPayload {
    pub override_mode: Option<String>, // GLOBAL | OFF | FIXED_COUNT | PERCENTAGE | AUTO_ADAPTIVE
    pub custom_threshold: Option<i64>,
    pub custom_percent: Option<f64>,
    pub custom_digest_minutes: Option<i64>,
    pub custom_cooldown_minutes: Option<i64>,
}

pub async fn get_target_pool(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let key = format!("target_pool:{}", id);
    let stored = state.db.get_setting(&key).unwrap_or(None);
    let config: serde_json::Value = stored
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| {
            json!({
                "override_mode": "GLOBAL",
                "custom_threshold": 50,
                "custom_percent": 0.5,
                "custom_digest_minutes": -1,
                "custom_cooldown_minutes": -1,
                "last_flushed_at": null
            })
        });
    (StatusCode::OK, Json(json!({ "status": "ok", "pool": config })))
}

pub async fn update_target_pool(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Json(payload): Json<TargetPoolPayload>,
) -> impl IntoResponse {
    let key = format!("target_pool:{}", id);
    let mode = match payload
        .override_mode
        .unwrap_or_else(|| "GLOBAL".to_string())
        .to_uppercase()
        .as_str()
    {
        "OFF" => "OFF",
        "FIXED_COUNT" => "FIXED_COUNT",
        "PERCENTAGE" => "PERCENTAGE",
        "AUTO_ADAPTIVE" => "AUTO_ADAPTIVE",
        _ => "GLOBAL",
    };
    let config = json!({
        "override_mode": mode,
        "custom_threshold": payload.custom_threshold.unwrap_or(50).clamp(1, 1_000_000),
        "custom_percent": payload.custom_percent.unwrap_or(0.5).clamp(0.01, 100.0),
        "custom_digest_minutes": payload.custom_digest_minutes.unwrap_or(-1),
        "custom_cooldown_minutes": payload.custom_cooldown_minutes.unwrap_or(-1),
        "updated_at": Utc::now().to_rfc3339()
    });
    let _ = state.db.set_setting(&key, &config.to_string());
    crate::api::dev_logs::push_dev_log("I", "PoolPolicy", &format!("Updated pool override for {} -> {}", id, mode));
    (StatusCode::OK, Json(json!({ "status": "ok", "pool": config })))
}

pub async fn flush_target_pool(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let key = format!("target_pool:{}", id);
    let mut config: serde_json::Value = state
        .db
        .get_setting(&key)
        .unwrap_or(None)
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| json!({ "override_mode": "GLOBAL", "custom_threshold": 50, "custom_percent": 0.5 }));
    let now = Utc::now().to_rfc3339();
    config["last_flushed_at"] = json!(now);
    let _ = state.db.set_setting(&key, &config.to_string());
    crate::api::dev_logs::push_dev_log("I", "PoolPolicy", &format!("Manual pool flush/reset executed for {}", id));
    (StatusCode::OK, Json(json!({ "status": "ok", "pool": config })))
}

pub async fn get_target_logs(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    match state.db.get_change_logs(&id) {
        Ok(logs) => (StatusCode::OK, Json(json!({ "status": "ok", "logs": logs }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn get_target_avatars(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        Ok(None) => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "error", "message": "Target not found" })),
            );
        }
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            );
        }
    };

    let safe_id = target.id.replace('/', "").replace('\\', "").replace("..", "");
    let profile_dir = format!("archives/{}/profile", safe_id);
    let mut avatars = Vec::new();

    if let Ok(entries) = std::fs::read_dir(&profile_dir) {
        for entry in entries.flatten() {
            let file_name = entry.file_name().to_string_lossy().to_string();
            if file_name.starts_with("avatar_") && file_name.ends_with(".jpg") && file_name != "avatar_current.jpg" {
                avatars.push(format!("archives/{}/profile/{}", safe_id, file_name));
            }
        }
    }

    avatars.sort();
    avatars.reverse();

    if avatars.is_empty() && std::path::Path::new(&format!("{}/avatar_current.jpg", profile_dir)).exists() {
        avatars.push(format!("archives/{}/profile/avatar_current.jpg", safe_id));
    }

    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "avatars": avatars })),
    )
}

pub async fn get_target_relationships(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        Ok(None) => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            )
        }
    };

    let followers = state
        .db
        .list_relationships(&target.id, "followers")
        .unwrap_or_default();
    let following = state
        .db
        .list_relationships(&target.id, "following")
        .unwrap_or_default();
    let audits = state
        .db
        .list_follower_audits(&target.id)
        .unwrap_or_default();

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "followers": followers,
            "following": following,
            "audits": audits
        })),
    )
}

pub async fn sync_target_relationships(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&id) {
        Ok(Some(t)) => t,
        Ok(None) => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "status": "not_found", "message": "Target not found" })),
            )
        }
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "status": "error", "message": e.to_string() })),
            )
        }
    };

    let session_file = format!("sessions/session_{}.json", target.username);
    let sess_owned = if std::path::Path::new(&session_file).exists() {
        Some(session_file)
    } else if std::path::Path::new("sessions/instagram_session.json").exists() {
        Some("sessions/instagram_session.json".to_string())
    } else {
        None
    };

    match state
        .scheduler
        .sync_relationships(&target.id, &target.username, sess_owned.as_deref())
        .await
    {
        Ok((fl_count, fg_count)) => {
            let followers = state
                .db
                .list_relationships(&target.id, "followers")
                .unwrap_or_default();
            let following = state
                .db
                .list_relationships(&target.id, "following")
                .unwrap_or_default();
            let audits = state
                .db
                .list_follower_audits(&target.id)
                .unwrap_or_default();
            (
                StatusCode::OK,
                Json(json!({
                    "status": "ok",
                    "fetched_followers": fl_count,
                    "fetched_following": fg_count,
                    "followers": followers,
                    "following": following,
                    "audits": audits
                })),
            )
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

