pub mod media;
pub mod settings;
pub mod targets;
pub mod ws;

use axum::{
    http::StatusCode,
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
        .route("/targets/{id}/logs", get(targets::get_target_logs))
        .route("/targets/{id}/media", get(media::list_media))
        .route(
            "/settings",
            get(settings::get_settings).post(settings::update_settings),
        )
        .route("/ws", get(ws::ws_handler));

    Router::new()
        .nest("/api", api_routes)
        .nest_service("/archives", ServeDir::new("archives"))
        .fallback_service(ServeDir::new("web/dist"))
        .layer(CorsLayer::permissive())
        .with_state(state)
}
