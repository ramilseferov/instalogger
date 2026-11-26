use axum::{
    extract::{Path, Query, State},
    http::{header, StatusCode},
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::json;
use std::sync::Arc;

use crate::api::AppState;
use crate::models::PostComment;

#[derive(Deserialize)]
pub struct ExportQuery {
    pub format: Option<String>,
}

pub async fn list_post_comments(
    State(state): State<Arc<AppState>>,
    Path((_target_id, shortcode)): Path<(String, String)>,
) -> impl IntoResponse {
    match state.db.list_post_comments(&shortcode) {
        Ok(comments) => (
            StatusCode::OK,
            Json(json!({ "status": "ok", "comments": comments, "count": comments.len() })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn sync_post_comments(
    State(state): State<Arc<AppState>>,
    Path((target_id, shortcode)): Path<(String, String)>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&target_id) {
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
    let session_path = if std::path::Path::new(&session_file).exists() {
        Some(session_file.as_str())
    } else if std::path::Path::new("sessions/instagram_session.json").exists() {
        Some("sessions/instagram_session.json")
    } else {
        None
    };

    match state
        .scheduler
        .worker
        .fetch_comments(&shortcode, session_path, 200)
        .await
    {
        Ok(fetched) => {
            let mapped: Vec<PostComment> = fetched
                .into_iter()
                .map(|c| PostComment {
                    id: c.id,
                    post_id: shortcode.clone(),
                    target_id: target.id.clone(),
                    parent_comment_id: c.parent_comment_id,
                    author_username: c.author_username,
                    author_full_name: c.author_full_name,
                    is_verified: c.is_verified,
                    text: c.text,
                    like_count: c.like_count,
                    reply_count: c.reply_count,
                    created_at: c.created_at,
                    collection_status: c.collection_status,
                })
                .collect();
            let _ = state.db.upsert_post_comments(&mapped);
            let stored = state.db.list_post_comments(&shortcode).unwrap_or(mapped);
            (
                StatusCode::OK,
                Json(json!({ "status": "ok", "comments": stored, "count": stored.len() })),
            )
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "status": "error", "message": e.to_string() })),
        ),
    }
}

pub async fn export_post_comments(
    State(state): State<Arc<AppState>>,
    Path((_target_id, shortcode)): Path<(String, String)>,
    Query(query): Query<ExportQuery>,
) -> impl IntoResponse {
    let comments = state.db.list_post_comments(&shortcode).unwrap_or_default();
    let fmt = query.format.unwrap_or_else(|| "json".to_string()).to_lowercase();
    let safe_code: String = shortcode
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .collect();

    match fmt.as_str() {
        "csv" => {
            let mut out = String::from("id,parent_id,author,verified,likes,replies,created_at,text\n");
            for c in &comments {
                let esc = c.text.replace('"', "\"\"").replace('\n', " ");
                out.push_str(&format!(
                    "\"{}\",\"{}\",\"{}\",{},{},{},\"{}\",\"{}\"\n",
                    c.id,
                    c.parent_comment_id.as_deref().unwrap_or(""),
                    c.author_username,
                    c.is_verified,
                    c.like_count,
                    c.reply_count,
                    c.created_at,
                    esc
                ));
            }
            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, "text/csv; charset=utf-8".to_string()),
                    (
                        header::CONTENT_DISPOSITION,
                        format!("attachment; filename=\"comments_{}.csv\"", safe_code),
                    ),
                ],
                out,
            )
        }
        "md" => {
            let mut out = format!("# Comments for /p/{}\n\nTotal: {}\n\n", safe_code, comments.len());
            for c in &comments {
                let prefix = if c.parent_comment_id.is_some() { "  - " } else { "- " };
                out.push_str(&format!(
                    "{}**@{}** ({} likes · {}): {}\n",
                    prefix, c.author_username, c.like_count, c.created_at, c.text
                ));
            }
            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, "text/markdown; charset=utf-8".to_string()),
                    (
                        header::CONTENT_DISPOSITION,
                        format!("attachment; filename=\"comments_{}.md\"", safe_code),
                    ),
                ],
                out,
            )
        }
        "txt" => {
            let mut out = format!("Comments for /p/{} ({})\n\n", safe_code, comments.len());
            for c in &comments {
                let indent = if c.parent_comment_id.is_some() { "  -> " } else { "" };
                out.push_str(&format!(
                    "{}@{} [{} likes | {}]: {}\n",
                    indent, c.author_username, c.like_count, c.created_at, c.text
                ));
            }
            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, "text/plain; charset=utf-8".to_string()),
                    (
                        header::CONTENT_DISPOSITION,
                        format!("attachment; filename=\"comments_{}.txt\"", safe_code),
                    ),
                ],
                out,
            )
        }
        _ => {
            let body = serde_json::to_string_pretty(&json!({
                "shortcode": safe_code,
                "count": comments.len(),
                "comments": comments
            }))
            .unwrap_or_else(|_| "{}".to_string());
            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, "application/json; charset=utf-8".to_string()),
                    (
                        header::CONTENT_DISPOSITION,
                        format!("attachment; filename=\"comments_{}.json\"", safe_code),
                    ),
                ],
                body,
            )
        }
    }
}
