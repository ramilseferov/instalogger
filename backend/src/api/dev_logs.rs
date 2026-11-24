use axum::{http::StatusCode, response::IntoResponse, Json};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};

const MAX_DEV_LOGS: usize = 1000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DevLogEntry {
    pub id: u64,
    pub timestamp: String,
    pub level: String, // "D" | "I" | "W" | "E"
    pub tag: String,
    pub message: String,
}

struct DevLogBuffer {
    next_id: u64,
    entries: VecDeque<DevLogEntry>,
}

fn buffer() -> &'static Mutex<DevLogBuffer> {
    static STORE: OnceLock<Mutex<DevLogBuffer>> = OnceLock::new();
    STORE.get_or_init(|| {
        let mut init_entries = VecDeque::with_capacity(MAX_DEV_LOGS);
        init_entries.push_back(DevLogEntry {
            id: 1,
            timestamp: Utc::now().to_rfc3339(),
            level: "I".to_string(),
            tag: "Daemon".to_string(),
            message: "Developer log ring buffer initialized (redacted mode)".to_string(),
        });
        Mutex::new(DevLogBuffer {
            next_id: 2,
            entries: init_entries,
        })
    })
}

/// Redacts session cookies and signed CDN query parameters so secrets never leak to UI logs.
pub fn redact_secrets(raw: &str) -> String {
    let mut out = raw.to_string();
    for key in ["sessionid=", "csrftoken=", "ds_user_id=", "rur=", "oh=", "oe=", "token="] {
        let mut search_from = 0usize;
        while let Some(pos) = out[search_from..].find(key) {
            let start = search_from + pos + key.len();
            let end = out[start..]
                .find(|c: char| c == ';' || c == '&' || c == ' ' || c == '"' || c == '\'' || c == '\n')
                .map(|rel| start + rel)
                .unwrap_or(out.len());
            if end > start {
                out.replace_range(start..end, "[REDACTED]");
                search_from = start + "[REDACTED]".len();
            } else {
                search_from = start;
            }
        }
    }
    out
}

pub fn push_dev_log(level: &str, tag: &str, message: &str) {
    let clean_msg = redact_secrets(message);
    if let Ok(mut guard) = buffer().lock() {
        let id = guard.next_id;
        guard.next_id += 1;
        if guard.entries.len() >= MAX_DEV_LOGS {
            guard.entries.pop_front();
        }
        guard.entries.push_back(DevLogEntry {
            id,
            timestamp: Utc::now().to_rfc3339(),
            level: level.to_uppercase(),
            tag: tag.to_string(),
            message: clean_msg,
        });
    }
}

pub async fn get_dev_logs() -> impl IntoResponse {
    let mut entries: Vec<DevLogEntry> = buffer()
        .lock()
        .map(|g| g.entries.iter().cloned().collect())
        .unwrap_or_default();

    // Also include tail of logs/instalogger.log if present on disk (redacted)
    if let Ok(content) = std::fs::read_to_string("logs/instalogger.log") {
        let mut disk_id = 10_000_000u64;
        for line in content.lines().rev().take(200).collect::<Vec<_>>().into_iter().rev() {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }
            let level = if trimmed.contains("ERROR") || trimmed.contains("ERR") {
                "E"
            } else if trimmed.contains("WARN") {
                "W"
            } else if trimmed.contains("DEBUG") {
                "D"
            } else {
                "I"
            };
            entries.push(DevLogEntry {
                id: disk_id,
                timestamp: Utc::now().to_rfc3339(),
                level: level.to_string(),
                tag: "LogFile".to_string(),
                message: redact_secrets(trimmed),
            });
            disk_id += 1;
        }
    }

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "logs": entries
        })),
    )
}

pub async fn clear_dev_logs() -> impl IntoResponse {
    if let Ok(mut guard) = buffer().lock() {
        guard.entries.clear();
    }
    let _ = std::fs::write("logs/instalogger.log", "");
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}
