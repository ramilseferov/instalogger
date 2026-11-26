use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde_json::json;
use std::sync::Arc;

use crate::api::AppState;

pub async fn list_downloads(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let (items, summary, archiving_targets) = state.scheduler.downloads.snapshot();
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "items": items,
            "summary": summary,
            "archiving_targets": archiving_targets
        })),
    )
}

pub async fn pause_download(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    state.scheduler.downloads.pause_item(&id);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn resume_download(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    state.scheduler.downloads.resume_item(&id);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn cancel_download(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    state.scheduler.downloads.cancel_item(&id);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn retry_download(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    state.scheduler.downloads.resume_item(&id);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn pause_all_downloads(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    state.scheduler.downloads.pause_all();
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn resume_all_downloads(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    state.scheduler.downloads.resume_all();
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn clear_completed_downloads(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    state.scheduler.downloads.clear_completed();
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}
