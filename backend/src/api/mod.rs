pub mod auth;
pub mod comments;
pub mod dev_logs;
pub mod downloads;
pub mod media;
pub mod notifications;
pub mod settings;
pub mod storage;
pub mod system;
pub mod targets;
pub mod ws;

use axum::{
    http::{header, HeaderValue, Method, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use serde_json::json;
use std::sync::Arc;
use tower_http::cors::CorsLayer;
use tower_http::services::ServeDir;

use crate::db::Database;
use crate::scheduler::Scheduler;

pub struct AppState {
    pub db: Database,
    pub scheduler: Arc<Scheduler>,
}

pub async fn health_check() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "app": "instalogger",
            "version": "1.0.0"
        })),
    )
}

pub fn create_router(db: Database, scheduler: Arc<Scheduler>) -> Router {
    let state = Arc::new(AppState { db, scheduler });

    let api_routes = Router::new()
        .route("/health", get(health_check))
        .route("/system/update-check", get(system::check_updates))
        .route("/targets", get(targets::list_targets).post(targets::add_target))
        .route(
            "/targets/{id}",
            get(targets::get_target).delete(targets::delete_target),
        )
        .route("/targets/{id}/turbo", post(targets::toggle_turbo))
        .route("/targets/{id}/status", post(targets::set_target_status))
        .route("/targets/{id}/interval", post(targets::set_target_interval))
        .route("/targets/{id}/history", get(targets::get_target_history))
        .route("/targets/{id}/check-now", post(targets::check_target_now))
        .route("/targets/{id}/sync-media", post(targets::sync_target_media))
        .route(
            "/targets/{id}/pool",
            get(targets::get_target_pool).post(targets::update_target_pool),
        )
        .route("/targets/{id}/pool/flush", post(targets::flush_target_pool))
        .route("/targets/{id}/logs", get(targets::get_target_logs))
        .route("/targets/{id}/avatars", get(targets::get_target_avatars))
        .route("/targets/{id}/avatars/{filename}", axum::routing::delete(targets::delete_target_avatar))
        .route("/targets/{id}/relationships", get(targets::get_target_relationships))
        .route("/targets/{id}/sync-relationships", post(targets::sync_target_relationships))
        .route("/targets/{id}/media", get(media::list_media))
        .route("/targets/{id}/media/{media_id}", axum::routing::delete(targets::delete_target_media_item))
        .route("/targets/{id}/media/{media_id}/assets", get(targets::get_media_assets))
        .route("/targets/{id}/posts/{shortcode}/comments", get(comments::list_post_comments))
        .route("/targets/{id}/posts/{shortcode}/comments/sync", post(comments::sync_post_comments))
        .route("/targets/{id}/posts/{shortcode}/comments/export", get(comments::export_post_comments))
        .route(
            "/notifications",
            get(notifications::list_notifications).delete(notifications::clear_notifications),
        )
        .route("/notifications/read-all", post(notifications::mark_all_read))
        .route("/notifications/{id}/read", post(notifications::mark_one_read))
        .route("/notifications/{id}", axum::routing::delete(notifications::delete_one_notification))
        .route(
            "/notification-rules",
            get(notifications::list_notification_rules).post(notifications::upsert_notification_rule),
        )
        .route("/storage", get(storage::get_storage_overview))
        .route("/storage/cleanup", post(storage::cleanup_storage))
        .route("/storage/export/{target_id}", get(storage::export_target_archive_zip))
        .route("/providers/health", get(storage::list_provider_health))
        .route("/providers/health/reset", post(storage::reset_provider_health))
        .route("/auth/status", get(auth::get_auth_status))
        .route("/auth/setup", post(auth::setup_pin))
        .route("/auth/unlock", post(auth::unlock_pin))
        .route("/auth/lock", post(auth::lock_dashboard))
        .route("/downloads", get(downloads::list_downloads))
        .route("/downloads/pause-all", post(downloads::pause_all_downloads))
        .route("/downloads/resume-all", post(downloads::resume_all_downloads))
        .route("/downloads/clear-completed", post(downloads::clear_completed_downloads))
        .route("/downloads/{id}/pause", post(downloads::pause_download))
        .route("/downloads/{id}/resume", post(downloads::resume_download))
        .route("/downloads/{id}/cancel", post(downloads::cancel_download))
        .route("/downloads/{id}/retry", post(downloads::retry_download))
        .route(
            "/settings",
            get(settings::get_settings).post(settings::update_settings),
        )
        .route("/settings/session-export", get(settings::export_session_cookie))
        .route(
            "/dev-logs",
            get(dev_logs::get_dev_logs).delete(dev_logs::clear_dev_logs),
        )
        .route("/ws", get(ws::ws_handler));

    let cors = CorsLayer::new()
        .allow_origin([
            "http://localhost:3000".parse::<HeaderValue>().unwrap(),
            "http://127.0.0.1:3000".parse::<HeaderValue>().unwrap(),
            "http://localhost:5173".parse::<HeaderValue>().unwrap(),
            "http://127.0.0.1:5173".parse::<HeaderValue>().unwrap(),
        ])
        .allow_methods([
            Method::GET,
            Method::POST,
            Method::DELETE,
            Method::OPTIONS,
        ])
        .allow_headers([
            header::CONTENT_TYPE,
            header::AUTHORIZATION,
            header::ACCEPT,
            header::HeaderName::from_static("x-auth-token"),
        ]);

    Router::new()
        .nest("/api", api_routes)
        .nest_service("/archives", ServeDir::new("archives"))
        .fallback_service(ServeDir::new("web/dist"))
        .layer(cors)
        .with_state(state)
}
