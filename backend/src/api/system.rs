use axum::{extract::Query, http::StatusCode, response::IntoResponse, Json};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

const REPO: &str = "ramilseferov/instalogger";
const CDN_MANIFEST_URL: &str =
    "https://github.com/ramilseferov/instalogger/releases/latest/download/update-manifest.json";
const API_LATEST_URL: &str =
    "https://api.github.com/repos/ramilseferov/instalogger/releases/latest";
const CACHE_TTL: Duration = Duration::from_secs(600);

#[derive(Debug, Deserialize)]
pub struct UpdateCheckQuery {
    pub force: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateCheckResponse {
    pub current_version: String,
    pub current_version_code: i32,
    pub latest_tag: String,
    pub latest_version: String,
    pub latest_version_code: i32,
    pub update_available: bool,
    pub channel: String,
    pub published_at: String,
    pub apk_name: String,
    pub apk_url: String,
    pub apk_size_bytes: u64,
    pub sha256: Option<String>,
    pub release_url: String,
    pub changelog_az: String,
    pub changelog_en: String,
    pub changelog_ru: String,
    pub source: String,
    pub checked_at: String,
}

struct CachedUpdate {
    fetched_at: Instant,
    etag: Option<String>,
    response: UpdateCheckResponse,
}

fn cache_slot() -> &'static Mutex<Option<CachedUpdate>> {
    static SLOT: OnceLock<Mutex<Option<CachedUpdate>>> = OnceLock::new();
    SLOT.get_or_init(|| Mutex::new(None))
}

fn read_local_android_version() -> (String, i32) {
    let candidates = [
        "android/app/build.gradle.kts",
        "../android/app/build.gradle.kts",
    ];
    for path in candidates {
        if let Ok(content) = std::fs::read_to_string(path) {
            let mut vname = String::from("1.0.0");
            let mut vcode = 0i32;
            for line in content.lines() {
                let t = line.trim();
                if let Some(rest) = t.strip_prefix("versionName") {
                    if let Some(q1) = rest.find('"') {
                        if let Some(q2) = rest[q1 + 1..].find('"') {
                            vname = rest[q1 + 1..q1 + 1 + q2].to_string();
                        }
                    }
                } else if let Some(rest) = t.strip_prefix("versionCode") {
                    let digits: String = rest.chars().filter(|c| c.is_ascii_digit()).collect();
                    if let Ok(parsed) = digits.parse::<i32>() {
                        vcode = parsed;
                    }
                }
            }
            if vcode > 0 {
                return (vname, vcode);
            }
        }
    }
    ("1.0.27".to_string(), 28)
}

pub fn parse_version_code_from_tag(tag: &str) -> i32 {
    if let Some(idx) = tag.rfind("-b") {
        let digits: String = tag[idx + 2..]
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if let Ok(v) = digits.parse::<i32>() {
            return v;
        }
    }
    0
}

pub fn parse_version_name_from_tag(tag: &str) -> String {
    let stripped = tag.trim().trim_start_matches('v');
    stripped.split('-').next().unwrap_or(stripped).to_string()
}

pub fn extract_sha256(body: &str) -> Option<String> {
    let lower = body.to_lowercase();
    let idx = lower.find("sha-256")?;
    let tail = &body[idx..];
    let mut run = String::new();
    for ch in tail.chars() {
        if ch.is_ascii_hexdigit() {
            run.push(ch.to_ascii_lowercase());
            if run.len() == 64 {
                return Some(run);
            }
        } else {
            run.clear();
        }
    }
    None
}

fn parse_manifest_response(
    v: &Value,
    cur_ver: &str,
    cur_code: i32,
    source: &str,
) -> Option<UpdateCheckResponse> {
    let tag = v.get("tag")?.as_str()?.trim().to_string();
    let version_name = v
        .get("versionName")
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| parse_version_name_from_tag(&tag));
    let version_code = v
        .get("versionCode")
        .and_then(|x| x.as_i64())
        .map(|n| n as i32)
        .unwrap_or_else(|| parse_version_code_from_tag(&tag));
    let apk = v.get("apk")?;
    let apk_url = apk.get("url")?.as_str()?.trim().to_string();
    let apk_name = apk
        .get("name")
        .and_then(|x| x.as_str())
        .unwrap_or("InstaLogger.apk")
        .to_string();
    let apk_size_bytes = apk.get("sizeBytes").and_then(|x| x.as_u64()).unwrap_or(0);
    let sha256 = apk
        .get("sha256")
        .and_then(|x| x.as_str())
        .map(|s| s.trim().to_lowercase())
        .filter(|s| s.len() == 64);

    let ch = v.get("changelog");
    let changelog_az = ch
        .and_then(|c| c.get("az"))
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string();
    let changelog_en = ch
        .and_then(|c| c.get("en"))
        .and_then(|x| x.as_str())
        .unwrap_or(&changelog_az)
        .to_string();
    let changelog_ru = ch
        .and_then(|c| c.get("ru"))
        .and_then(|x| x.as_str())
        .unwrap_or(&changelog_az)
        .to_string();

    let release_url = v
        .get("releaseUrl")
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("https://github.com/{REPO}/releases/tag/{tag}"));

    Some(UpdateCheckResponse {
        current_version: cur_ver.to_string(),
        current_version_code: cur_code,
        latest_tag: tag,
        latest_version: version_name,
        latest_version_code: version_code,
        update_available: version_code > cur_code,
        channel: v
            .get("channel")
            .and_then(|x| x.as_str())
            .unwrap_or("stable")
            .to_string(),
        published_at: v
            .get("publishedAt")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string(),
        apk_name,
        apk_url,
        apk_size_bytes,
        sha256,
        release_url,
        changelog_az,
        changelog_en,
        changelog_ru,
        source: source.to_string(),
        checked_at: chrono::Utc::now().to_rfc3339(),
    })
}

fn parse_github_api_response(
    v: &Value,
    cur_ver: &str,
    cur_code: i32,
) -> Option<UpdateCheckResponse> {
    let tag = v.get("tag_name")?.as_str()?.trim().to_string();
    let version_code = parse_version_code_from_tag(&tag);
    let version_name = parse_version_name_from_tag(&tag);
    let body = v.get("body").and_then(|x| x.as_str()).unwrap_or("");
    let sha256 = extract_sha256(body);

    let mut apk_url = String::new();
    let mut apk_name = String::from("InstaLogger.apk");
    let mut apk_size_bytes = 0u64;
    if let Some(assets) = v.get("assets").and_then(|x| x.as_array()) {
        for asset in assets {
            let name = asset.get("name").and_then(|x| x.as_str()).unwrap_or("");
            if name.to_lowercase().ends_with(".apk") {
                apk_name = name.to_string();
                apk_url = asset
                    .get("browser_download_url")
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string();
                apk_size_bytes = asset.get("size").and_then(|x| x.as_u64()).unwrap_or(0);
                break;
            }
        }
    }

    let release_url = v
        .get("html_url")
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("https://github.com/{REPO}/releases/tag/{tag}"));

    Some(UpdateCheckResponse {
        current_version: cur_ver.to_string(),
        current_version_code: cur_code,
        latest_tag: tag,
        latest_version: version_name,
        latest_version_code: version_code,
        update_available: version_code > cur_code,
        channel: if v
            .get("prerelease")
            .and_then(|x| x.as_bool())
            .unwrap_or(false)
        {
            "beta".to_string()
        } else {
            "stable".to_string()
        },
        published_at: v
            .get("published_at")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string(),
        apk_name,
        apk_url,
        apk_size_bytes,
        sha256,
        release_url,
        changelog_az: body.to_string(),
        changelog_en: body.to_string(),
        changelog_ru: body.to_string(),
        source: "github_api".to_string(),
        checked_at: chrono::Utc::now().to_rfc3339(),
    })
}

pub async fn check_updates(Query(params): Query<UpdateCheckQuery>) -> impl IntoResponse {
    let force = params.force.unwrap_or(false);
    let (cur_ver, cur_code) = read_local_android_version();
    let mut cached_etag: Option<String> = None;

    if let Ok(guard) = cache_slot().lock() {
        if let Some(entry) = guard.as_ref() {
            cached_etag = entry.etag.clone();
            if !force && entry.fetched_at.elapsed() < CACHE_TTL {
                return (StatusCode::OK, Json(json!(entry.response)));
            }
        }
    }

    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(12))
        .user_agent("InstaLogger-Backend-Updater/1.0")
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": format!("HTTP client error: {e}") })),
            );
        }
    };

    // Tier 1: GitHub Fastly CDN update-manifest.json (zero API rate-limit)
    if let Ok(resp) = client.get(CDN_MANIFEST_URL).send().await {
        if resp.status().is_success() {
            if let Ok(val) = resp.json::<Value>().await {
                if let Some(parsed) =
                    parse_manifest_response(&val, &cur_ver, cur_code, "github_cdn_manifest")
                {
                    if let Ok(mut guard) = cache_slot().lock() {
                        *guard = Some(CachedUpdate {
                            fetched_at: Instant::now(),
                            etag: cached_etag,
                            response: parsed.clone(),
                        });
                    }
                    return (StatusCode::OK, Json(json!(parsed)));
                }
            }
        }
    }

    // Tier 2: GitHub REST API with If-None-Match ETag
    let mut req = client
        .get(API_LATEST_URL)
        .header("Accept", "application/vnd.github+json");
    if !force {
        if let Some(ref etag) = cached_etag {
            req = req.header("If-None-Match", etag);
        }
    }

    match req.send().await {
        Ok(resp) => {
            if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
                if let Ok(mut guard) = cache_slot().lock() {
                    if let Some(entry) = guard.as_mut() {
                        entry.fetched_at = Instant::now();
                        return (StatusCode::OK, Json(json!(entry.response)));
                    }
                }
            }
            if !resp.status().is_success() {
                return (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({
                        "error": format!("GitHub API HTTP {}", resp.status().as_u16()),
                        "current_version": cur_ver,
                        "current_version_code": cur_code,
                    })),
                );
            }
            let new_etag = resp
                .headers()
                .get("etag")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string())
                .or(cached_etag);
            match resp.json::<Value>().await {
                Ok(val) => {
                    if let Some(parsed) = parse_github_api_response(&val, &cur_ver, cur_code) {
                        if let Ok(mut guard) = cache_slot().lock() {
                            *guard = Some(CachedUpdate {
                                fetched_at: Instant::now(),
                                etag: new_etag,
                                response: parsed.clone(),
                            });
                        }
                        (StatusCode::OK, Json(json!(parsed)))
                    } else {
                        (
                            StatusCode::BAD_GATEWAY,
                            Json(json!({ "error": "Unable to parse GitHub release payload" })),
                        )
                    }
                }
                Err(e) => (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({ "error": format!("Invalid GitHub JSON: {e}") })),
                ),
            }
        }
        Err(e) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({
                "error": format!("GitHub unreachable: {e}"),
                "current_version": cur_ver,
                "current_version_code": cur_code,
            })),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_tag_and_sha256() {
        assert_eq!(parse_version_code_from_tag("v1.0.27-b28"), 28);
        assert_eq!(parse_version_name_from_tag("v1.0.27-b28"), "1.0.27");
        let sha = extract_sha256(
            "- **SHA-256:** `0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef`",
        );
        assert_eq!(
            sha.as_deref(),
            Some("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );
    }
}
