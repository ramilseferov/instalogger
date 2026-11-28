use axum::{
    extract::{Query, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::IntoResponse,
    Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;

use crate::api::AppState;
use crate::models::{NotificationRule, Target};

const MAX_IMPORT_BYTES: usize = 2 * 1024 * 1024; // 2 MiB hard cap
const MAX_IMPORT_TARGETS: usize = 2000;

#[derive(Deserialize)]
pub struct ExportQuery {
    #[serde(default = "default_format")]
    pub format: String, // json | csv
    #[serde(default = "default_scope")]
    pub scope: String, // full | targets
    #[serde(default)]
    pub include_secrets: bool,
    #[serde(default)]
    pub theme_mode: Option<String>,
    #[serde(default)]
    pub language: Option<String>,
}

fn default_format() -> String {
    "json".to_string()
}

fn default_scope() -> String {
    "full".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PoolOverrideExport {
    #[serde(default = "default_global")]
    pub override_mode: String,
    #[serde(default = "default_fixed")]
    pub custom_threshold: i64,
    #[serde(default = "default_percent")]
    pub custom_percent: f64,
    #[serde(default = "default_neg_one")]
    pub custom_digest_minutes: i64,
    #[serde(default = "default_neg_one")]
    pub custom_cooldown_minutes: i64,
    #[serde(default = "default_neg_one")]
    pub apply_to_following: i64,
}

fn default_global() -> String {
    "GLOBAL".to_string()
}
fn default_fixed() -> i64 {
    50
}
fn default_percent() -> f64 {
    0.5
}
fn default_neg_one() -> i64 {
    -1
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TargetExportItem {
    pub username: String,
    #[serde(default)]
    pub instagram_id: String,
    #[serde(default)]
    pub full_name: String,
    #[serde(default)]
    pub is_private: bool,
    #[serde(default = "default_active")]
    pub status: String,
    #[serde(default)]
    pub turbo_mode: bool,
    #[serde(default = "default_interval")]
    pub check_interval_seconds: i64,
    #[serde(default)]
    pub followers_count: i64,
    #[serde(default)]
    pub following_count: i64,
    #[serde(default)]
    pub media_count: i64,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub pool_override: Option<PoolOverrideExport>,
}

fn default_active() -> String {
    "active".to_string()
}
fn default_interval() -> i64 {
    300
}

#[derive(Deserialize)]
pub struct ImportOptions {
    #[serde(default = "default_true")]
    pub import_targets: bool,
    #[serde(default = "default_true")]
    pub import_settings: bool,
    #[serde(default = "default_true")]
    pub import_notification_rules: bool,
    #[serde(default = "default_merge")]
    pub conflict_mode: String, // "merge" | "skip"
    #[serde(default)]
    pub include_secrets: bool,
}

fn default_true() -> bool {
    true
}
fn default_merge() -> String {
    "merge".to_string()
}

impl Default for ImportOptions {
    fn default() -> Self {
        Self {
            import_targets: true,
            import_settings: true,
            import_notification_rules: true,
            conflict_mode: "merge".to_string(),
            include_secrets: false,
        }
    }
}

#[derive(Deserialize)]
pub struct ImportPayload {
    #[serde(default = "default_format")]
    pub format: String, // "json" | "csv"
    pub content: String,
    #[serde(default)]
    pub options: ImportOptions,
}

fn sanitize_username(raw: &str) -> Option<String> {
    let trimmed = raw.trim().trim_start_matches('@').to_lowercase();
    if trimmed.is_empty() || trimmed.len() > 30 {
        return None;
    }
    if trimmed
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_')
    {
        Some(trimmed)
    } else {
        None
    }
}

fn sanitize_csv_cell(val: &str, protect_formula: bool) -> String {
    let cleaned = val.replace('\r', " ").replace('\n', " ");
    let safe = if protect_formula
        && cleaned
            .chars()
            .next()
            .map(|c| matches!(c, '=' | '+' | '-' | '@' | '\t'))
            .unwrap_or(false)
    {
        format!("'{}", cleaned)
    } else {
        cleaned
    };
    if safe.contains(',') || safe.contains('"') || safe.contains('\n') || safe.contains('\r') {
        format!("\"{}\"", safe.replace('"', "\"\""))
    } else {
        safe
    }
}

fn parse_csv_line(line: &str) -> Vec<String> {
    let mut fields = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    chars.next();
                    current.push('"');
                } else {
                    in_quotes = false;
                }
            } else {
                current.push(c);
            }
        } else if c == '"' {
            in_quotes = true;
        } else if c == ',' {
            fields.push(current.trim().to_string());
            current.clear();
        } else {
            current.push(c);
        }
    }
    fields.push(current.trim().to_string());
    fields
}

fn parse_bool_str(val: &str, default: bool) -> bool {
    match val.trim().to_lowercase().as_str() {
        "true" | "1" | "yes" | "on" => true,
        "false" | "0" | "no" | "off" => false,
        _ => default,
    }
}

fn normalize_status(raw: &str) -> String {
    match raw.trim().to_lowercase().as_str() {
        "paused" => "paused".to_string(),
        "error" => "error".to_string(),
        _ => "active".to_string(),
    }
}

fn normalize_pool_mode(raw: &str) -> String {
    match raw.trim().to_uppercase().as_str() {
        "OFF" => "OFF".to_string(),
        "FIXED_COUNT" => "FIXED_COUNT".to_string(),
        "PERCENTAGE" => "PERCENTAGE".to_string(),
        "AUTO_ADAPTIVE" => "AUTO_ADAPTIVE".to_string(),
        _ => "GLOBAL".to_string(),
    }
}

fn collect_targets_export(state: &AppState) -> Vec<TargetExportItem> {
    let targets = state.db.list_targets().unwrap_or_default();
    targets
        .into_iter()
        .map(|t| {
            let pool_raw = state
                .db
                .get_setting(&format!("target_pool:{}", t.id))
                .ok()
                .flatten()
                .or_else(|| {
                    state
                        .db
                        .get_setting(&format!("target_pool:{}", t.username))
                        .ok()
                        .flatten()
                });
            let pool_override = pool_raw.and_then(|s| serde_json::from_str::<Value>(&s).ok()).map(|v| {
                PoolOverrideExport {
                    override_mode: normalize_pool_mode(
                        v.get("override_mode").and_then(|x| x.as_str()).unwrap_or("GLOBAL"),
                    ),
                    custom_threshold: v
                        .get("custom_threshold")
                        .and_then(|x| x.as_i64())
                        .unwrap_or(50)
                        .clamp(1, 1_000_000),
                    custom_percent: v
                        .get("custom_percent")
                        .and_then(|x| x.as_f64())
                        .unwrap_or(0.5)
                        .clamp(0.01, 100.0),
                    custom_digest_minutes: v
                        .get("custom_digest_minutes")
                        .and_then(|x| x.as_i64())
                        .unwrap_or(-1),
                    custom_cooldown_minutes: v
                        .get("custom_cooldown_minutes")
                        .and_then(|x| x.as_i64())
                        .unwrap_or(-1),
                    apply_to_following: v
                        .get("apply_to_following")
                        .and_then(|x| x.as_i64())
                        .unwrap_or(-1),
                }
            });

            TargetExportItem {
                username: t.username,
                instagram_id: t.id,
                full_name: t.full_name,
                is_private: t.is_private,
                status: t.status,
                turbo_mode: t.turbo_mode,
                check_interval_seconds: t.check_interval_seconds,
                followers_count: t.followers_count,
                following_count: t.following_count,
                media_count: t.media_count,
                created_at: Some(t.created_at.to_rfc3339()),
                pool_override,
            }
        })
        .collect()
}

pub async fn export_backup(
    State(state): State<Arc<AppState>>,
    Query(query): Query<ExportQuery>,
) -> impl IntoResponse {
    let format = query.format.trim().to_lowercase();
    let scope = query.scope.trim().to_lowercase();
    let now = Utc::now();
    let stamp = now.format("%Y%m%d-%H%M").to_string();
    let targets = collect_targets_export(&state);

    if format == "csv" {
        let mut csv = String::from("\u{FEFF}username,full_name,instagram_id,is_private,status,turbo_mode,check_interval_seconds,followers_count,following_count,media_count,pool_override_mode,pool_custom_threshold,pool_custom_percent,created_at\n");
        for t in &targets {
            let pool = t.pool_override.as_ref();
            let row = [
                sanitize_csv_cell(&t.username, false),
                sanitize_csv_cell(&t.full_name, true),
                sanitize_csv_cell(&t.instagram_id, true),
                t.is_private.to_string(),
                sanitize_csv_cell(&t.status, false),
                t.turbo_mode.to_string(),
                t.check_interval_seconds.to_string(),
                t.followers_count.to_string(),
                t.following_count.to_string(),
                t.media_count.to_string(),
                pool.map(|p| p.override_mode.as_str())
                    .unwrap_or("GLOBAL")
                    .to_string(),
                pool.map(|p| p.custom_threshold).unwrap_or(50).to_string(),
                pool.map(|p| p.custom_percent).unwrap_or(0.5).to_string(),
                sanitize_csv_cell(t.created_at.as_deref().unwrap_or(""), false),
            ];
            csv.push_str(&row.join(","));
            csv.push('\n');
        }

        let filename = format!("instalogger-targets-{}.csv", stamp);
        let mut headers = HeaderMap::new();
        headers.insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("text/csv; charset=utf-8"),
        );
        if let Ok(cd) = HeaderValue::from_str(&format!("attachment; filename=\"{}\"", filename)) {
            headers.insert(header::CONTENT_DISPOSITION, cd);
        }
        return (StatusCode::OK, headers, csv).into_response();
    }

    if scope == "targets" {
        let payload = json!({
            "schema": "instalogger.targets",
            "version": 2,
            "exported_at": now.to_rfc3339(),
            "source": "web",
            "app_version": env!("CARGO_PKG_VERSION"),
            "count": targets.len(),
            "targets": targets,
        });
        let body = serde_json::to_string_pretty(&payload).unwrap_or_else(|_| "{}".to_string());
        let filename = format!("instalogger-targets-{}.json", stamp);
        let mut headers = HeaderMap::new();
        headers.insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("application/json; charset=utf-8"),
        );
        if let Ok(cd) = HeaderValue::from_str(&format!("attachment; filename=\"{}\"", filename)) {
            headers.insert(header::CONTENT_DISPOSITION, cd);
        }
        return (StatusCode::OK, headers, body).into_response();
    }

    // Full cross-platform backup JSON (v2)
    let get_bool = |k: &str, def: bool| -> bool {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .map(|v| v == "true")
            .unwrap_or(def)
    };
    let get_i64 = |k: &str, def: i64| -> i64 {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(def)
    };
    let get_f64 = |k: &str, def: f64| -> f64 {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .and_then(|v| v.parse::<f64>().ok())
            .unwrap_or(def)
    };
    let get_str = |k: &str, def: &str| -> String {
        state
            .db
            .get_setting(k)
            .unwrap_or(None)
            .filter(|v| !v.is_empty())
            .unwrap_or_else(|| def.to_string())
    };

    let mut settings_obj = serde_json::Map::new();
    settings_obj.insert("enable_termux_api".into(), json!(get_bool("enable_termux_api", true)));
    settings_obj.insert(
        "notification_language".into(),
        json!(get_str("notification_language", "az")),
    );
    settings_obj.insert("pool_mode".into(), json!(get_str("pool_mode", "OFF")));
    settings_obj.insert(
        "pool_fixed_threshold".into(),
        json!(get_i64("pool_fixed_threshold", 50)),
    );
    settings_obj.insert(
        "pool_percent_threshold".into(),
        json!(get_f64("pool_percent_threshold", 0.5)),
    );
    settings_obj.insert(
        "pool_digest_minutes".into(),
        json!(get_i64("pool_digest_minutes", 0)),
    );
    settings_obj.insert(
        "pool_cooldown_minutes".into(),
        json!(get_i64("pool_cooldown_minutes", 0)),
    );
    settings_obj.insert(
        "pool_anti_flicker".into(),
        json!(get_bool("pool_anti_flicker", true)),
    );
    settings_obj.insert(
        "pool_spike_alert".into(),
        json!(get_bool("pool_spike_alert", true)),
    );
    settings_obj.insert(
        "pool_apply_following".into(),
        json!(get_bool("pool_apply_following", false)),
    );
    settings_obj.insert(
        "pool_suppress_logs".into(),
        json!(get_bool("pool_suppress_logs", true)),
    );
    settings_obj.insert(
        "list_completeness_guard".into(),
        json!(get_bool("list_completeness_guard", true)),
    );
    settings_obj.insert(
        "archiver_speed_preset".into(),
        json!(get_str("archiver_speed_preset", "BALANCED")),
    );
    settings_obj.insert(
        "download_workers".into(),
        json!(get_i64("download_workers", 4)),
    );
    settings_obj.insert(
        "max_posts_sync_limit".into(),
        json!(get_i64("max_posts_sync_limit", 50)),
    );
    settings_obj.insert("yt_dlp_enabled".into(), json!(get_bool("yt_dlp_enabled", true)));
    settings_obj.insert(
        "yt_dlp_quality".into(),
        json!(get_str("yt_dlp_quality", "BEST")),
    );
    settings_obj.insert(
        "auto_download_media".into(),
        json!(get_bool("auto_download_media", true)),
    );

    if query.include_secrets {
        let tg_token = state
            .db
            .get_setting("telegram_bot_token")
            .unwrap_or(None)
            .unwrap_or_default();
        let tg_chat = state
            .db
            .get_setting("telegram_chat_id")
            .unwrap_or(None)
            .unwrap_or_default();
        settings_obj.insert("telegram_bot_token".into(), json!(tg_token));
        settings_obj.insert("telegram_chat_id".into(), json!(tg_chat));
    }

    let rules = state.db.list_notification_rules().unwrap_or_default();

    let theme_mode = query
        .theme_mode
        .as_deref()
        .map(|s| match s.to_lowercase().as_str() {
            "light" => "light",
            "dark" => "dark",
            _ => "system",
        })
        .unwrap_or("system");
    let language = query
        .language
        .as_deref()
        .map(|s| match s.to_lowercase().as_str() {
            "en" => "en",
            "ru" => "ru",
            _ => "az",
        })
        .unwrap_or("az");

    let payload = json!({
        "schema": "instalogger.backup",
        "version": 2,
        "exported_at": now.to_rfc3339(),
        "source": "web",
        "app_version": env!("CARGO_PKG_VERSION"),
        "includes_secrets": query.include_secrets,
        "appearance": {
            "theme_mode": theme_mode,
            "language": language,
        },
        "settings": Value::Object(settings_obj),
        "notification_settings": {
            "rules": rules,
        },
        "targets": targets,
    });

    let body = serde_json::to_string_pretty(&payload).unwrap_or_else(|_| "{}".to_string());
    let filename = format!("instalogger-backup-{}.json", stamp);
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json; charset=utf-8"),
    );
    if let Ok(cd) = HeaderValue::from_str(&format!("attachment; filename=\"{}\"", filename)) {
        headers.insert(header::CONTENT_DISPOSITION, cd);
    }
    crate::api::dev_logs::push_dev_log(
        "I",
        "Backup",
        &format!(
            "Exported full configuration backup ({} targets, {} rules, secrets={})",
            targets.len(),
            rules.len(),
            query.include_secrets
        ),
    );
    (StatusCode::OK, headers, body).into_response()
}

pub async fn import_backup(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<ImportPayload>,
) -> impl IntoResponse {
    if payload.content.len() > MAX_IMPORT_BYTES {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(json!({
                "status": "error",
                "error": "Import file exceeds 2 MB limit"
            })),
        );
    }

    let trimmed = payload.content.trim_start_matches('\u{FEFF}').trim();
    if trimmed.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({
                "status": "error",
                "error": "Import content is empty"
            })),
        );
    }

    let format = payload.format.trim().to_lowercase();
    let conflict_skip = payload.options.conflict_mode.trim().eq_ignore_ascii_case("skip");

    let mut targets_to_import: Vec<TargetExportItem> = Vec::new();
    let mut settings_map: Option<serde_json::Map<String, Value>> = None;
    let mut rules_to_import: Vec<NotificationRule> = Vec::new();
    let mut appearance_out: Option<Value> = None;
    let mut warnings: Vec<String> = Vec::new();

    if format == "csv" || (!trimmed.starts_with('{') && !trimmed.starts_with('[')) {
        let mut lines = trimmed.lines().filter(|l| !l.trim().is_empty());
        let Some(header_line) = lines.next() else {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({ "status": "error", "error": "CSV file has no rows" })),
            );
        };
        let headers: Vec<String> = parse_csv_line(header_line)
            .into_iter()
            .map(|h| h.trim().to_lowercase())
            .collect();
        let col_idx: HashMap<String, usize> = headers
            .iter()
            .enumerate()
            .map(|(i, h)| (h.clone(), i))
            .collect();

        let username_col = col_idx
            .get("username")
            .or_else(|| col_idx.get("handle"))
            .or_else(|| col_idx.get("account"))
            .copied();

        let (start_with_first, u_col) = if let Some(idx) = username_col {
            (false, idx)
        } else {
            // Single-column plain list fallback (first line is a username if valid)
            (true, 0)
        };

        let mut all_rows: Vec<String> = Vec::new();
        if start_with_first {
            all_rows.push(header_line.to_string());
        }
        all_rows.extend(lines.map(|s| s.to_string()));

        for (row_num, raw_line) in all_rows.iter().enumerate() {
            if targets_to_import.len() >= MAX_IMPORT_TARGETS {
                warnings.push(format!("Target import capped at {} items", MAX_IMPORT_TARGETS));
                break;
            }
            let cols = parse_csv_line(raw_line);
            let raw_u = cols.get(u_col).map(|s| s.as_str()).unwrap_or("");
            let Some(username) = sanitize_username(raw_u) else {
                warnings.push(format!("Skipped invalid username at row {}", row_num + 1));
                continue;
            };
            let get_col = |keys: &[&str]| -> Option<&str> {
                for k in keys {
                    if let Some(&idx) = col_idx.get(*k) {
                        if let Some(val) = cols.get(idx) {
                            if !val.is_empty() {
                                return Some(val.as_str());
                            }
                        }
                    }
                }
                None
            };
            let pool_mode = get_col(&["pool_override_mode", "pool_mode"])
                .map(normalize_pool_mode);
            let pool_override = pool_mode.map(|m| PoolOverrideExport {
                override_mode: m,
                custom_threshold: get_col(&["pool_custom_threshold"])
                    .and_then(|v| v.parse::<i64>().ok())
                    .unwrap_or(50)
                    .clamp(1, 1_000_000),
                custom_percent: get_col(&["pool_custom_percent"])
                    .and_then(|v| v.parse::<f64>().ok())
                    .unwrap_or(0.5)
                    .clamp(0.01, 100.0),
                custom_digest_minutes: -1,
                custom_cooldown_minutes: -1,
                apply_to_following: -1,
            });

            targets_to_import.push(TargetExportItem {
                username,
                instagram_id: get_col(&["instagram_id", "id"]).unwrap_or("").to_string(),
                full_name: get_col(&["full_name", "name"])
                    .unwrap_or("")
                    .trim_start_matches('\'')
                    .to_string(),
                is_private: get_col(&["is_private"])
                    .map(|v| parse_bool_str(v, false))
                    .unwrap_or(false),
                status: get_col(&["status"])
                    .map(normalize_status)
                    .unwrap_or_else(|| "active".to_string()),
                turbo_mode: get_col(&["turbo_mode", "is_turbo", "turbo"])
                    .map(|v| parse_bool_str(v, false))
                    .unwrap_or(false),
                check_interval_seconds: get_col(&["check_interval_seconds", "interval"])
                    .and_then(|v| v.parse::<i64>().ok())
                    .unwrap_or(300)
                    .clamp(30, 86400),
                followers_count: get_col(&["followers_count"])
                    .and_then(|v| v.parse::<i64>().ok())
                    .unwrap_or(0)
                    .max(0),
                following_count: get_col(&["following_count"])
                    .and_then(|v| v.parse::<i64>().ok())
                    .unwrap_or(0)
                    .max(0),
                media_count: get_col(&["media_count"])
                    .and_then(|v| v.parse::<i64>().ok())
                    .unwrap_or(0)
                    .max(0),
                created_at: get_col(&["created_at"]).map(|s| s.to_string()),
                pool_override,
            });
        }
    } else {
        let parsed: Value = match serde_json::from_str(trimmed) {
            Ok(v) => v,
            Err(e) => {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(json!({
                        "status": "error",
                        "error": format!("Invalid JSON: {}", e)
                    })),
                );
            }
        };

        if let Some(arr) = parsed.as_array() {
            for item in arr.iter().take(MAX_IMPORT_TARGETS) {
                if let Some(t) = parse_target_json_value(item) {
                    targets_to_import.push(t);
                } else {
                    warnings.push("Skipped invalid target entry in JSON array".to_string());
                }
            }
        } else if let Some(obj) = parsed.as_object() {
            if let Some(arr) = obj.get("targets").and_then(|v| v.as_array()) {
                for item in arr.iter().take(MAX_IMPORT_TARGETS) {
                    if let Some(t) = parse_target_json_value(item) {
                        targets_to_import.push(t);
                    } else {
                        warnings.push("Skipped invalid target entry".to_string());
                    }
                }
            }
            if let Some(s_obj) = obj.get("settings").and_then(|v| v.as_object()) {
                settings_map = Some(s_obj.clone());
            }
            if let Some(app_val) = obj.get("appearance") {
                appearance_out = Some(app_val.clone());
            }
            // Notification rules (Web native format + Android cross-platform mapping)
            if let Some(ns) = obj.get("notification_settings").and_then(|v| v.as_object()) {
                if let Some(r_arr) = ns.get("rules").and_then(|v| v.as_array()) {
                    for rv in r_arr {
                        if let Ok(rule) = serde_json::from_value::<NotificationRule>(rv.clone()) {
                            if !rule.scope_key.trim().is_empty() && rule.scope_key.len() <= 64 {
                                rules_to_import.push(rule);
                            }
                        }
                    }
                }
                // Cross-platform import from Android backup (global_events + target_overrides)
                if rules_to_import.is_empty() {
                    if let Some(global_ev) = ns.get("global_events").and_then(|v| v.as_object()) {
                        let enabled = ns
                            .get("status_bar_notifications")
                            .and_then(|v| v.as_bool())
                            .unwrap_or(true);
                        rules_to_import.push(rule_from_android_events("global", enabled, global_ev));
                    }
                    if let Some(overrides) = ns.get("target_overrides").and_then(|v| v.as_object()) {
                        for (uname, ov_val) in overrides {
                            if let Some(clean_u) = sanitize_username(uname) {
                                if let Some(ov) = ov_val.as_object() {
                                    let muted = ov.get("muted").and_then(|v| v.as_bool()).unwrap_or(false);
                                    let ev_map = ov
                                        .get("events")
                                        .and_then(|v| v.as_object())
                                        .cloned()
                                        .unwrap_or_default();
                                    rules_to_import.push(rule_from_android_events(&clean_u, !muted, &ev_map));
                                }
                            }
                        }
                    }
                }
            } else if let Some(r_arr) = obj.get("notification_rules").and_then(|v| v.as_array()) {
                for rv in r_arr {
                    if let Ok(rule) = serde_json::from_value::<NotificationRule>(rv.clone()) {
                        if !rule.scope_key.trim().is_empty() && rule.scope_key.len() <= 64 {
                            rules_to_import.push(rule);
                        }
                    }
                }
            }
        } else {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({ "status": "error", "error": "Unsupported JSON structure" })),
            );
        }
    }

    let mut targets_added = 0usize;
    let mut targets_updated = 0usize;
    let mut targets_skipped = 0usize;
    let mut pool_overrides_applied = 0usize;
    let mut settings_applied = 0usize;
    let mut rules_applied = 0usize;

    // 1. Apply Targets
    if payload.options.import_targets {
        for item in targets_to_import {
            let existing = state.db.get_target(&item.username).ok().flatten();
            if let Some(ex) = existing {
                if conflict_skip {
                    targets_skipped += 1;
                    continue;
                }
                let _ = state.db.set_turbo_mode(&ex.id, item.turbo_mode);
                let _ = state.db.set_target_status(&ex.id, &normalize_status(&item.status));
                let _ = state
                    .db
                    .set_target_interval(&ex.id, item.check_interval_seconds.clamp(30, 86400));
                if let Some(pool) = item.pool_override {
                    let pool_json = json!({
                        "override_mode": normalize_pool_mode(&pool.override_mode),
                        "custom_threshold": pool.custom_threshold.clamp(1, 1_000_000),
                        "custom_percent": pool.custom_percent.clamp(0.01, 100.0),
                        "custom_digest_minutes": pool.custom_digest_minutes.max(-1),
                        "custom_cooldown_minutes": pool.custom_cooldown_minutes.max(-1),
                        "apply_to_following": pool.apply_to_following.clamp(-1, 1),
                        "updated_at": Utc::now().to_rfc3339()
                    });
                    if state
                        .db
                        .set_setting(&format!("target_pool:{}", ex.id), &pool_json.to_string())
                        .is_ok()
                    {
                        pool_overrides_applied += 1;
                    }
                }
                targets_updated += 1;
            } else {
                let target_id = if !item.instagram_id.trim().is_empty()
                    && item.instagram_id.len() <= 64
                    && item
                        .instagram_id
                        .chars()
                        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
                {
                    item.instagram_id.trim().to_string()
                } else {
                    item.username.clone()
                };
                let new_target = Target {
                    id: target_id.clone(),
                    username: item.username.clone(),
                    full_name: item.full_name.chars().take(120).collect(),
                    biography: String::new(),
                    is_private: item.is_private,
                    followed_by_viewer: false,
                    media_count: item.media_count.max(0),
                    followers_count: item.followers_count.max(0),
                    following_count: item.following_count.max(0),
                    latest_story_at: 0,
                    profile_pic_path: None,
                    avatar_hash: None,
                    profile_hash: None,
                    status: normalize_status(&item.status),
                    turbo_mode: item.turbo_mode,
                    check_interval_seconds: item.check_interval_seconds.clamp(30, 86400),
                    last_checked_at: None,
                    last_status_change_at: None,
                    created_at: Utc::now(),
                };
                if state.db.create_target(&new_target).is_ok() {
                    targets_added += 1;
                    if let Some(pool) = item.pool_override {
                        let pool_json = json!({
                            "override_mode": normalize_pool_mode(&pool.override_mode),
                            "custom_threshold": pool.custom_threshold.clamp(1, 1_000_000),
                            "custom_percent": pool.custom_percent.clamp(0.01, 100.0),
                            "custom_digest_minutes": pool.custom_digest_minutes.max(-1),
                            "custom_cooldown_minutes": pool.custom_cooldown_minutes.max(-1),
                            "apply_to_following": pool.apply_to_following.clamp(-1, 1),
                            "updated_at": Utc::now().to_rfc3339()
                        });
                        if state
                            .db
                            .set_setting(&format!("target_pool:{}", target_id), &pool_json.to_string())
                            .is_ok()
                        {
                            pool_overrides_applied += 1;
                        }
                    }
                } else {
                    warnings.push(format!("Failed to insert target @{}", item.username));
                }
            }
        }
    }

    // 2. Apply Global Settings (strictly validated allowlist)
    if payload.options.import_settings {
        if let Some(s) = settings_map {
            if let Some(v) = s.get("enable_termux_api").and_then(|x| x.as_bool()) {
                let _ = state.db.set_setting("enable_termux_api", &v.to_string());
                settings_applied += 1;
            }
            if let Some(v) = s.get("notification_language").and_then(|x| x.as_str()) {
                let norm = match v.trim().to_lowercase().as_str() {
                    "en" => "en",
                    "ru" => "ru",
                    _ => "az",
                };
                let _ = state.db.set_setting("notification_language", norm);
                settings_applied += 1;
            }
            if let Some(v) = s.get("pool_mode").and_then(|x| x.as_str()) {
                let norm = match v.trim().to_uppercase().as_str() {
                    "FIXED_COUNT" | "PERCENTAGE" | "AUTO_ADAPTIVE" => v.trim().to_uppercase(),
                    _ => "OFF".to_string(),
                };
                let _ = state.db.set_setting("pool_mode", &norm);
                settings_applied += 1;
            }
            if let Some(v) = s.get("pool_fixed_threshold").and_then(|x| x.as_i64()) {
                let _ = state
                    .db
                    .set_setting("pool_fixed_threshold", &v.clamp(1, 1_000_000).to_string());
                settings_applied += 1;
            }
            if let Some(v) = s.get("pool_percent_threshold").and_then(|x| x.as_f64()) {
                let _ = state
                    .db
                    .set_setting("pool_percent_threshold", &v.clamp(0.01, 100.0).to_string());
                settings_applied += 1;
            }
            if let Some(v) = s.get("pool_digest_minutes").and_then(|x| x.as_i64()) {
                let _ = state
                    .db
                    .set_setting("pool_digest_minutes", &v.clamp(0, 1440).to_string());
                settings_applied += 1;
            }
            if let Some(v) = s.get("pool_cooldown_minutes").and_then(|x| x.as_i64()) {
                let _ = state
                    .db
                    .set_setting("pool_cooldown_minutes", &v.clamp(0, 1440).to_string());
                settings_applied += 1;
            }
            for bool_key in [
                "pool_anti_flicker",
                "pool_spike_alert",
                "pool_apply_following",
                "pool_suppress_logs",
                "list_completeness_guard",
                "yt_dlp_enabled",
                "auto_download_media",
            ] {
                if let Some(v) = s.get(bool_key).and_then(|x| x.as_bool()) {
                    let _ = state.db.set_setting(bool_key, &v.to_string());
                    settings_applied += 1;
                }
            }
            if let Some(v) = s.get("archiver_speed_preset").and_then(|x| x.as_str()) {
                let norm = match v.trim().to_uppercase().as_str() {
                    "ECO" | "FAST" | "TURBO" => v.trim().to_uppercase(),
                    _ => "BALANCED".to_string(),
                };
                let _ = state.db.set_setting("archiver_speed_preset", &norm);
                settings_applied += 1;
            }
            if let Some(v) = s.get("download_workers").and_then(|x| x.as_i64()) {
                let _ = state
                    .db
                    .set_setting("download_workers", &v.clamp(1, 10).to_string());
                settings_applied += 1;
            }
            if let Some(v) = s.get("max_posts_sync_limit").and_then(|x| x.as_i64()) {
                let _ = state
                    .db
                    .set_setting("max_posts_sync_limit", &v.clamp(0, 500).to_string());
                settings_applied += 1;
            }
            if let Some(v) = s.get("yt_dlp_quality").and_then(|x| x.as_str()) {
                let norm = match v.trim().to_uppercase().as_str() {
                    "HD_1080" | "HD_720" | "DATA_SAVER" => v.trim().to_uppercase(),
                    _ => "BEST".to_string(),
                };
                let _ = state.db.set_setting("yt_dlp_quality", &norm);
                settings_applied += 1;
            }
            if payload.options.include_secrets {
                if let Some(token) = s.get("telegram_bot_token").and_then(|x| x.as_str()) {
                    if !token.is_empty() && !token.starts_with("•••") && token.len() <= 256 {
                        let _ = state.db.set_setting("telegram_bot_token", token.trim());
                        settings_applied += 1;
                    }
                }
                if let Some(chat_id) = s.get("telegram_chat_id").and_then(|x| x.as_str()) {
                    if !chat_id.is_empty() && chat_id.len() <= 128 {
                        let _ = state.db.set_setting("telegram_chat_id", chat_id.trim());
                        settings_applied += 1;
                    }
                }
            }
            state.scheduler.downloads.notify_settings_changed();
        }
    }

    // 3. Apply Notification Rules
    if payload.options.import_notification_rules {
        for rule in rules_to_import {
            let scope_clean = rule.scope_key.trim();
            if scope_clean == "global" || sanitize_username(scope_clean).is_some() || scope_clean.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-') {
                if state.db.upsert_notification_rule(&rule).is_ok() {
                    rules_applied += 1;
                }
            }
        }
    }

    crate::api::dev_logs::push_dev_log(
        "I",
        "Backup",
        &format!(
            "Imported backup: +{} targets, ~{} updated, {} skipped, {} settings, {} rules",
            targets_added, targets_updated, targets_skipped, settings_applied, rules_applied
        ),
    );

    (
        StatusCode::OK,
        Json(json!({
            "status": "ok",
            "summary": {
                "targets_added": targets_added,
                "targets_updated": targets_updated,
                "targets_skipped": targets_skipped,
                "pool_overrides_applied": pool_overrides_applied,
                "settings_applied": settings_applied,
                "rules_applied": rules_applied,
                "warnings": warnings,
            },
            "appearance": appearance_out,
        })),
    )
}

fn parse_target_json_value(v: &Value) -> Option<TargetExportItem> {
    if let Some(s) = v.as_str() {
        let username = sanitize_username(s)?;
        return Some(TargetExportItem {
            username,
            instagram_id: String::new(),
            full_name: String::new(),
            is_private: false,
            status: "active".to_string(),
            turbo_mode: false,
            check_interval_seconds: 300,
            followers_count: 0,
            following_count: 0,
            media_count: 0,
            created_at: None,
            pool_override: None,
        });
    }
    let obj = v.as_object()?;
    let raw_u = obj.get("username")?.as_str()?;
    let username = sanitize_username(raw_u)?;
    let pool_override = obj
        .get("pool_override")
        .and_then(|p| serde_json::from_value::<PoolOverrideExport>(p.clone()).ok());
    Some(TargetExportItem {
        username,
        instagram_id: obj
            .get("instagram_id")
            .or_else(|| obj.get("id"))
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string(),
        full_name: obj
            .get("full_name")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string(),
        is_private: obj
            .get("is_private")
            .and_then(|x| x.as_bool())
            .unwrap_or(false),
        status: obj
            .get("status")
            .and_then(|x| x.as_str())
            .map(normalize_status)
            .unwrap_or_else(|| {
                if obj.get("is_active").and_then(|x| x.as_bool()) == Some(false) {
                    "paused".to_string()
                } else {
                    "active".to_string()
                }
            }),
        turbo_mode: obj
            .get("turbo_mode")
            .or_else(|| obj.get("is_turbo"))
            .and_then(|x| x.as_bool())
            .unwrap_or(false),
        check_interval_seconds: obj
            .get("check_interval_seconds")
            .and_then(|x| x.as_i64())
            .unwrap_or(300)
            .clamp(30, 86400),
        followers_count: obj
            .get("followers_count")
            .and_then(|x| x.as_i64())
            .unwrap_or(0)
            .max(0),
        following_count: obj
            .get("following_count")
            .and_then(|x| x.as_i64())
            .unwrap_or(0)
            .max(0),
        media_count: obj
            .get("media_count")
            .and_then(|x| x.as_i64())
            .unwrap_or(0)
            .max(0),
        created_at: obj
            .get("created_at")
            .and_then(|x| x.as_str())
            .map(|s| s.to_string()),
        pool_override,
    })
}

fn rule_from_android_events(
    scope_key: &str,
    enabled: bool,
    events: &serde_json::Map<String, Value>,
) -> NotificationRule {
    let flag = |k: &str, def: bool| events.get(k).and_then(|v| v.as_bool()).unwrap_or(def);
    NotificationRule {
        scope_key: scope_key.to_string(),
        enabled,
        privacy_change: flag("privacy_changed", true),
        bio_change: flag("bio_changed", true),
        avatar_change: flag("avatar_changed", true),
        post_count_change: flag("new_posts", true),
        post_vanished: flag("new_posts", true),
        story_added: flag("stories_archived", true),
        followers_change: flag("followers_gained", true) || flag("followers_lost", true),
        following_change: flag("following_changed", true),
        quiet_start: None,
        quiet_end: None,
    }
}
