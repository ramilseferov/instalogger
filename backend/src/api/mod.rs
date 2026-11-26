pub mod dev_logs;
pub mod downloads;
pub mod media;
pub mod settings;
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
        .route("/targets", get(targets::list_targets).post(targets::add_target))
        .route(
            "/targets/{id}",
            get(targets::get_target).delete(targets::delete_target),
        )
        .route("/targets/{id}/turbo", post(targets::toggle_turbo))
        .route("/targets/{id}/check-now", post(targets::check_target_now))
        .route("/targets/{id}/sync-media", post(targets::sync_target_media))
        .route(
            "/targets/{id}/pool",
            get(targets::get_target_pool).post(targets::update_target_pool),
        )
        .route("/targets/{id}/pool/flush", post(targets::flush_target_pool))
        .route("/targets/{id}/logs", get(targets::get_target_logs))
        .route("/targets/{id}/avatars", get(targets::get_target_avatars))
        .route("/targets/{id}/relationships", get(targets::get_target_relationships))
        .route("/targets/{id}/sync-relationships", post(targets::sync_target_relationships))
        .route("/targets/{id}/media", get(media::list_media))
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
        ]);

    Router::new()
        .nest("/api", api_routes)
        .nest_service("/archives", ServeDir::new("archives"))
        .fallback_service(ServeDir::new("web/dist"))
        .layer(cors)
        .with_state(state)
}
