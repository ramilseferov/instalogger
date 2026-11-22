use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::json;
use std::sync::Arc;

use crate::api::AppState;

#[derive(Deserialize)]
pub struct MediaFilterQuery {
    pub r#type: Option<String>,
}

pub async fn list_media(
    State(state): State<Arc<AppState>>,
    Path(target_id): Path<String>,
    Query(query): Query<MediaFilterQuery>,
) -> impl IntoResponse {
    match state
        .db
        .list_media_by_target(&target_id, query.r#type.as_deref())
    {
        Ok(items) => (StatusCode::OK, Json(json!({ "status": "ok", "items": items }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}
