use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde_json::json;
use std::sync::Arc;

use crate::api::AppState;
use crate::models::NotificationRule;

pub async fn list_notifications(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    match state.db.list_notifications(200) {
        Ok(items) => {
            let unread = items.iter().filter(|n| !n.is_read).count();
            (
                StatusCode::OK,
                Json(json!({
                    "status": "ok",
                    "notifications": items,
                    "unread_count": unread
                })),
            )
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn mark_all_read(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let _ = state.db.mark_notifications_read(None);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn mark_one_read(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let _ = state.db.mark_notifications_read(Some(id));
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn delete_one_notification(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> impl IntoResponse {
    let _ = state.db.delete_notification(Some(id));
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn clear_notifications(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let _ = state.db.delete_notification(None);
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn list_notification_rules(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let mut rules = state.db.list_notification_rules().unwrap_or_default();
    if !rules.iter().any(|r| r.scope_key == "global") {
        rules.insert(0, NotificationRule::default());
    }
    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "rules": rules })),
    )
}

pub async fn upsert_notification_rule(
    State(state): State<Arc<AppState>>,
    Json(rule): Json<NotificationRule>,
) -> impl IntoResponse {
    match state.db.upsert_notification_rule(&rule) {
        Ok(_) => (
            StatusCode::OK,
            Json(json!({ "status": "ok", "rule": rule })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}
