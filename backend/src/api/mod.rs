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
        .route("/targets/{id}/logs", get(targets::get_target_logs))
        .route("/targets/{id}/avatars", get(targets::get_target_avatars))
        .route("/targets/{id}/media", get(media::list_media))
        .route(
            "/settings",
            get(settings::get_settings).post(settings::update_settings),
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
