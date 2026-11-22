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

    let snapshot = match state
        .scheduler
        .worker
        .check_profile(&clean_username, session_path)
        .await
    {
        Ok(s) => s,
        Err(e) => {
            error!("Initial profile fetch failed for @{}: {}", clean_username, e);
            // Fallback ID
            crate::scheduler::ProfileSnapshot {
                id: format!("usr_{}", clean_username),
                username: clean_username.clone(),
                full_name: "".to_string(),
                biography: "".to_string(),
                is_private: true,
                followed_by_viewer: false,
                media_count: 0,
                profile_pic_url: "".to_string(),
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
        profile_pic_path: None,
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

    // If public or authorized follower, trigger archival
    if !snapshot.is_private || (snapshot.is_private && snapshot.followed_by_viewer) {
        let scheduler_clone = Arc::clone(&state.scheduler);
        let u_clone = snapshot.username.clone();
        let id_clone = snapshot.id.clone();
        tokio::spawn(async move {
            let _ = scheduler_clone
                .trigger_full_archive(&u_clone, &id_clone, None)
                .await;
        });
    }

    (StatusCode::CREATED, Json(json!({ "status": "ok", "target": target })))
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
        Ok(_) => (StatusCode::OK, Json(json!({ "status": "ok" }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
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
