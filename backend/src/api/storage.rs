use axum::{
    extract::{Path, State},
    http::{header, StatusCode},
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashSet;
use std::path::Path as FsPath;
use std::sync::Arc;

use crate::api::AppState;

#[derive(Debug, Serialize)]
pub struct TargetStorageBreakdown {
    pub target_id: String,
    pub username: String,
    pub total_bytes: u64,
    pub posts_bytes: u64,
    pub reels_bytes: u64,
    pub stories_bytes: u64,
    pub highlights_bytes: u64,
    pub avatars_bytes: u64,
    pub file_count: usize,
}

#[derive(Deserialize)]
pub struct CleanupPayload {
    pub scope: String, // logs | target_media | orphans
    pub target_id: Option<String>,
}

fn dir_size_and_count(path: &FsPath) -> (u64, usize) {
    let mut total = 0u64;
    let mut count = 0usize;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() {
                let (b, c) = dir_size_and_count(&p);
                total += b;
                count += c;
            } else if let Ok(meta) = entry.metadata() {
                total += meta.len();
                count += 1;
            }
        }
    }
    (total, count)
}

pub async fn get_storage_overview(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let targets = state.db.list_targets().unwrap_or_default();
    let db_bytes = std::fs::metadata("data/instalogger.db")
        .map(|m| m.len())
        .unwrap_or(0)
        + std::fs::metadata("data/instalogger.db-wal")
            .map(|m| m.len())
            .unwrap_or(0);
    let (logs_bytes, _) = dir_size_and_count(FsPath::new("logs"));

    let mut per_target = Vec::new();
    let mut archives_total_bytes = 0u64;
    let mut known_dirs = HashSet::new();

    for t in &targets {
        let safe_id = t.id.replace('/', "").replace('\\', "").replace("..", "");
        known_dirs.insert(safe_id.clone());
        let root = format!("archives/{}", safe_id);
        let root_path = FsPath::new(&root);
        let (total_bytes, file_count) = dir_size_and_count(root_path);
        let (posts_bytes, _) = dir_size_and_count(&root_path.join("posts"));
        let (reels_bytes, _) = dir_size_and_count(&root_path.join("reels"));
        let (stories_bytes, _) = dir_size_and_count(&root_path.join("stories"));
        let (highlights_bytes, _) = dir_size_and_count(&root_path.join("highlights"));
        let (avatars_bytes, _) = dir_size_and_count(&root_path.join("profile"));
        archives_total_bytes += total_bytes;

        per_target.push(TargetStorageBreakdown {
            target_id: t.id.clone(),
            username: t.username.clone(),
            total_bytes,
            posts_bytes,
            reels_bytes,
            stories_bytes,
            highlights_bytes,
            avatars_bytes,
            file_count,
        });
    }

    let mut orphan_bytes = 0u64;
    let mut orphan_dirs = 0usize;
    if let Ok(entries) = std::fs::read_dir("archives") {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() {
                let name = entry.file_name().to_string_lossy().to_string();
                if !known_dirs.contains(&name) {
                    let (b, _) = dir_size_and_count(&p);
                    orphan_bytes += b;
                    orphan_dirs += 1;
                }
            }
        }
    }

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "total_bytes": db_bytes + logs_bytes + archives_total_bytes + orphan_bytes,
            "db_bytes": db_bytes,
            "logs_bytes": logs_bytes,
            "archives_bytes": archives_total_bytes,
            "orphan_bytes": orphan_bytes,
            "orphan_dirs": orphan_dirs,
            "targets": per_target
        })),
    )
}

pub async fn cleanup_storage(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<CleanupPayload>,
) -> impl IntoResponse {
    match payload.scope.as_str() {
        "logs" => {
            let _ = std::fs::write("logs/instalogger.log", "");
            crate::api::dev_logs::push_dev_log("I", "Storage", "Cleared runtime log file");
            (StatusCode::OK, Json(json!({ "status": "ok" })))
        }
        "target_media" => {
            let Some(tid) = payload.target_id else {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(json!({ "status": "error", "message": "target_id required" })),
                );
            };
            let safe_id = tid.replace('/', "").replace('\\', "").replace("..", "");
            for sub in ["posts", "reels", "stories", "highlights"] {
                let p = format!("archives/{}/{}", safe_id, sub);
                let _ = std::fs::remove_dir_all(&p);
            }
            let _ = state.db.delete_media_by_target(&tid);
            crate::api::dev_logs::push_dev_log("I", "Storage", &format!("Cleared archived media for {}", safe_id));
            (StatusCode::OK, Json(json!({ "status": "ok" })))
        }
        "orphans" => {
            let targets = state.db.list_targets().unwrap_or_default();
            let known: HashSet<String> = targets
                .iter()
                .map(|t| t.id.replace('/', "").replace('\\', "").replace("..", ""))
                .collect();
            let mut removed = 0usize;
            if let Ok(entries) = std::fs::read_dir("archives") {
                for entry in entries.flatten() {
                    let p = entry.path();
                    if p.is_dir() {
                        let name = entry.file_name().to_string_lossy().to_string();
                        if !known.contains(&name) && std::fs::remove_dir_all(&p).is_ok() {
                            removed += 1;
                        }
                    }
                }
            }
            crate::api::dev_logs::push_dev_log("I", "Storage", &format!("Pruned {} orphan archive folder(s)", removed));
            (StatusCode::OK, Json(json!({ "status": "ok", "removed": removed })))
        }
        _ => (
            StatusCode::BAD_REQUEST,
            Json(json!({ "status": "error", "message": "Unsupported cleanup scope" })),
        ),
    }
}

pub async fn export_target_archive_zip(
    State(state): State<Arc<AppState>>,
    Path(target_id): Path<String>,
) -> impl IntoResponse {
    let target = match state.db.get_target(&target_id) {
        Ok(Some(t)) => t,
        _ => {
            return (
                StatusCode::NOT_FOUND,
                [(header::CONTENT_TYPE, "application/json".to_string()), (header::CONTENT_DISPOSITION, "inline".to_string())],
                b"{\"status\":\"not_found\"}".to_vec(),
            );
        }
    };

    let safe_id = target.id.replace('/', "").replace('\\', "").replace("..", "");
    let src_dir = format!("archives/{}", safe_id);
    let tmp_zip = format!("/data/data/com.termux/files/usr/tmp/instalogger_{}.zip", safe_id);
    let fallback_tmp = format!("data/export_{}.zip", safe_id);
    let zip_path = if FsPath::new("/data/data/com.termux/files/usr/tmp").exists() {
        tmp_zip
    } else {
        fallback_tmp
    };

    let py_script = "import os, sys, zipfile\nsrc, dst = sys.argv[1], sys.argv[2]\nwith zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zf:\n  if os.path.isdir(src):\n    for root, _, files in os.walk(src):\n      for f in files:\n        fp = os.path.join(root, f)\n        zf.write(fp, os.path.relpath(fp, src))\n";
    let status = tokio::process::Command::new("python3")
        .arg("-c")
        .arg(py_script)
        .arg(&src_dir)
        .arg(&zip_path)
        .status()
        .await;

    let bytes = match status {
        Ok(s) if s.success() => std::fs::read(&zip_path).unwrap_or_default(),
        _ => Vec::new(),
    };
    let _ = std::fs::remove_file(&zip_path);

    let safe_user: String = target
        .username
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '.')
        .collect();
    (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "application/zip".to_string()),
            (
                header::CONTENT_DISPOSITION,
                format!("attachment; filename=\"instalogger_{}.zip\"", safe_user),
            ),
        ],
        bytes,
    )
}

pub async fn list_provider_health(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let mut rows = state.db.list_provider_health().unwrap_or_default();
    let defaults = ["yt-dlp", "gallery-dl", "instaloader-http"];
    for d in defaults {
        if !rows.iter().any(|r| r.provider_id == d) {
            rows.push(crate::models::ProviderHealth {
                provider_id: d.to_string(),
                status: "healthy".to_string(),
                consecutive_failures: 0,
                cooldown_until: 0,
                success_count: 0,
                failure_count: 0,
                last_error: None,
                updated_at: chrono::Utc::now(),
            });
        }
    }
    (
        StatusCode::OK,
        Json(json!({ "status": "ok", "providers": rows })),
    )
}

pub async fn reset_provider_health(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let _ = state.db.reset_provider_health();
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}
