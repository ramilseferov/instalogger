use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::{broadcast, Notify};
use tracing::{error, info};

use crate::db::Database;
use crate::worker::{BlitzItem, WorkerBridge};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadItem {
    pub id: String,
    pub target_id: String,
    pub username: String,
    pub shortcode: String,
    pub media_type: String,
    pub cdn_url: String,
    pub relative_file_path: String,
    pub caption: String,
    pub status: String, // queued | downloading | paused | completed | failed | cancelled
    pub progress: u8,
    pub file_size_bytes: i64,
    pub speed_bps: i64,
    pub provider: Option<String>,
    pub error: Option<String>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadSummary {
    pub active: usize,
    pub queued: usize,
    pub paused: usize,
    pub completed: usize,
    pub failed: usize,
    pub total_speed_bps: i64,
    pub paused_all: bool,
    pub workers: usize,
}

#[derive(Clone)]
pub struct DownloadManager {
    db: Database,
    worker: Arc<WorkerBridge>,
    events_tx: broadcast::Sender<String>,
    items: Arc<Mutex<Vec<DownloadItem>>>,
    active_handles: Arc<Mutex<HashMap<String, tokio::task::AbortHandle>>>,
    archiving_targets: Arc<Mutex<HashSet<String>>>,
    paused_all: Arc<AtomicBool>,
    dispatch_notify: Arc<Notify>,
}

impl DownloadManager {
    pub fn new(
        db: Database,
        worker: Arc<WorkerBridge>,
        events_tx: broadcast::Sender<String>,
    ) -> Arc<Self> {
        let mgr = Arc::new(Self {
            db,
            worker,
            events_tx,
            items: Arc::new(Mutex::new(Vec::new())),
            active_handles: Arc::new(Mutex::new(HashMap::new())),
            archiving_targets: Arc::new(Mutex::new(HashSet::new())),
            paused_all: Arc::new(AtomicBool::new(false)),
            dispatch_notify: Arc::new(Notify::new()),
        });
        mgr.clone().start_dispatcher();
        mgr
    }

    pub fn configured_workers(&self) -> usize {
        if let Ok(Some(w)) = self.db.get_setting("download_workers") {
            if let Ok(parsed) = w.parse::<usize>() {
                return parsed.clamp(1, 10);
            }
        }
        let preset = self
            .db
            .get_setting("archiver_speed_preset")
            .ok()
            .flatten()
            .unwrap_or_else(|| "BALANCED".to_string());
        match preset.to_uppercase().as_str() {
            "ECO" => 2,
            "FAST" => 6,
            "TURBO" => 10,
            _ => 4,
        }
    }

    pub fn configured_quality(&self) -> String {
        self.db
            .get_setting("yt_dlp_quality")
            .ok()
            .flatten()
            .unwrap_or_else(|| "BEST".to_string())
    }

    pub fn configured_yt_dlp_enabled(&self) -> bool {
        self.db
            .get_setting("yt_dlp_enabled")
            .ok()
            .flatten()
            .map(|v| v != "false")
            .unwrap_or(true)
    }

    pub fn mark_archiving(&self, target_id: &str, active: bool) {
        if let Ok(mut set) = self.archiving_targets.lock() {
            if active {
                set.insert(target_id.to_string());
            } else {
                set.remove(target_id);
            }
        }
        let msg = serde_json::json!({
            "type": if active { "archive_started" } else { "archive_completed" },
            "target_id": target_id
        });
        let _ = self.events_tx.send(msg.to_string());
    }

    pub fn enqueue_batch(&self, target_id: &str, username: &str, batch: &[BlitzItem]) {
        let now = Utc::now().to_rfc3339();
        let mut added_count = 0usize;
        if let Ok(mut list) = self.items.lock() {
            for item in batch {
                let rel = item.relative_file_path.clone().unwrap_or_else(|| {
                    format!(
                        "archives/{}/{}s/{}.{}",
                        target_id,
                        item.media_type,
                        item.shortcode,
                        if item.media_type == "reel" { "mp4" } else { "jpg" }
                    )
                });
                if let Some(existing) = list.iter_mut().find(|d| d.id == item.id) {
                    if matches!(existing.status.as_str(), "failed" | "cancelled") {
                        existing.status = "queued".to_string();
                        existing.progress = 0;
                        existing.error = None;
                        existing.updated_at = now.clone();
                        added_count += 1;
                    }
                    continue;
                }
                list.push(DownloadItem {
                    id: item.id.clone(),
                    target_id: target_id.to_string(),
                    username: username.to_string(),
                    shortcode: item.shortcode.clone(),
                    media_type: item.media_type.clone(),
                    cdn_url: item.cdn_url.clone(),
                    relative_file_path: rel,
                    caption: item.caption.clone(),
                    status: "queued".to_string(),
                    progress: 0,
                    file_size_bytes: 0,
                    speed_bps: 0,
                    provider: None,
                    error: None,
                    updated_at: now.clone(),
                });
                added_count += 1;
            }
        }
        if added_count > 0 {
            crate::api::dev_logs::push_dev_log(
                "I",
                "Downloads",
                &format!(
                    "Queued {} item(s) for @{} (workers={})",
                    added_count,
                    username,
                    self.configured_workers()
                ),
            );
            self.broadcast_queue_update(target_id);
            self.dispatch_notify.notify_one();
        }
    }

    pub fn snapshot(&self) -> (Vec<DownloadItem>, DownloadSummary, Vec<String>) {
        let items = self
            .items
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default();
        let archiving = self
            .archiving_targets
            .lock()
            .map(|g| g.iter().cloned().collect())
            .unwrap_or_default();

        let mut active = 0usize;
        let mut queued = 0usize;
        let mut paused = 0usize;
        let mut completed = 0usize;
        let mut failed = 0usize;
        let mut total_speed_bps = 0i64;

        for it in &items {
            match it.status.as_str() {
                "downloading" => {
                    active += 1;
                    total_speed_bps += it.speed_bps;
                }
                "queued" => queued += 1,
                "paused" => paused += 1,
                "completed" => completed += 1,
                "failed" => failed += 1,
                _ => {}
            }
        }

        let summary = DownloadSummary {
            active,
            queued,
            paused,
            completed,
            failed,
            total_speed_bps,
            paused_all: self.paused_all.load(Ordering::SeqCst),
            workers: self.configured_workers(),
        };
        (items, summary, archiving)
    }

    pub fn pause_item(&self, id: &str) {
        self.abort_active(id);
        let mut tid = String::new();
        if let Ok(mut list) = self.items.lock() {
            if let Some(it) = list.iter_mut().find(|d| d.id == id) {
                if matches!(it.status.as_str(), "queued" | "downloading") {
                    it.status = "paused".to_string();
                    it.speed_bps = 0;
                    it.updated_at = Utc::now().to_rfc3339();
                    tid = it.target_id.clone();
                }
            }
        }
        if !tid.is_empty() {
            self.broadcast_queue_update(&tid);
        }
        self.dispatch_notify.notify_one();
    }

    pub fn resume_item(&self, id: &str) {
        self.paused_all.store(false, Ordering::SeqCst);
        let mut tid = String::new();
        if let Ok(mut list) = self.items.lock() {
            if let Some(it) = list.iter_mut().find(|d| d.id == id) {
                if matches!(it.status.as_str(), "paused" | "failed" | "cancelled") {
                    it.status = "queued".to_string();
                    it.progress = 0;
                    it.error = None;
                    it.updated_at = Utc::now().to_rfc3339();
                    tid = it.target_id.clone();
                }
            }
        }
        if !tid.is_empty() {
            self.broadcast_queue_update(&tid);
        }
        self.dispatch_notify.notify_one();
    }

    pub fn cancel_item(&self, id: &str) {
        self.abort_active(id);
        let mut tid = String::new();
        if let Ok(mut list) = self.items.lock() {
            if let Some(it) = list.iter_mut().find(|d| d.id == id) {
                it.status = "cancelled".to_string();
                it.speed_bps = 0;
                it.updated_at = Utc::now().to_rfc3339();
                tid = it.target_id.clone();
            }
        }
        if !tid.is_empty() {
            self.broadcast_queue_update(&tid);
        }
        self.dispatch_notify.notify_one();
    }

    pub fn pause_all(&self) {
        self.paused_all.store(true, Ordering::SeqCst);
        if let Ok(mut handles) = self.active_handles.lock() {
            for (_, h) in handles.drain() {
                h.abort();
            }
        }
        if let Ok(mut list) = self.items.lock() {
            let now = Utc::now().to_rfc3339();
            for it in list.iter_mut() {
                if matches!(it.status.as_str(), "queued" | "downloading") {
                    it.status = "paused".to_string();
                    it.speed_bps = 0;
                    it.updated_at = now.clone();
                }
            }
        }
        self.broadcast_queue_update("");
    }

    pub fn resume_all(&self) {
        self.paused_all.store(false, Ordering::SeqCst);
        if let Ok(mut list) = self.items.lock() {
            let now = Utc::now().to_rfc3339();
            for it in list.iter_mut() {
                if it.status == "paused" {
                    it.status = "queued".to_string();
                    it.error = None;
                    it.updated_at = now.clone();
                }
            }
        }
        self.broadcast_queue_update("");
        self.dispatch_notify.notify_one();
    }

    pub fn clear_completed(&self) {
        if let Ok(mut list) = self.items.lock() {
            list.retain(|it| !matches!(it.status.as_str(), "completed" | "cancelled"));
        }
        self.broadcast_queue_update("");
    }

    pub fn notify_settings_changed(&self) {
        self.dispatch_notify.notify_one();
    }

    fn abort_active(&self, id: &str) {
        if let Ok(mut handles) = self.active_handles.lock() {
            if let Some(h) = handles.remove(id) {
                h.abort();
            }
        }
    }

    fn broadcast_queue_update(&self, target_id: &str) {
        let (_, summary, _) = self.snapshot();
        let msg = serde_json::json!({
            "type": "download_update",
            "target_id": target_id,
            "summary": summary
        });
        let _ = self.events_tx.send(msg.to_string());
    }

    fn start_dispatcher(self: Arc<Self>) {
        tokio::spawn(async move {
            loop {
                self.dispatch_available().await;
                self.dispatch_notify.notified().await;
            }
        });
    }

    async fn dispatch_available(self: &Arc<Self>) {
        if self.paused_all.load(Ordering::SeqCst) {
            return;
        }
        let max_workers = self.configured_workers();
        let quality = self.configured_quality();
        let yt_dlp_enabled = self.configured_yt_dlp_enabled();

        loop {
            let active_count = self
                .active_handles
                .lock()
                .map(|g| g.len())
                .unwrap_or(0);
            if active_count >= max_workers {
                break;
            }

            let next_item: Option<DownloadItem> = {
                let mut list = match self.items.lock() {
                    Ok(g) => g,
                    Err(_) => break,
                };
                if let Some(found) = list.iter_mut().find(|d| d.status == "queued") {
                    found.status = "downloading".to_string();
                    found.progress = 20;
                    found.updated_at = Utc::now().to_rfc3339();
                    Some(found.clone())
                } else {
                    None
                }
            };

            let item = match next_item {
                Some(it) => it,
                None => break,
            };

            self.broadcast_queue_update(&item.target_id);

            let mgr = Arc::clone(self);
            let q = quality.clone();
            let item_id = item.id.clone();
            let task = tokio::spawn(async move {
                let out_dir = format!("archives/{}", item.target_id);
                let session_file = format!("sessions/session_{}.json", item.username);
                let cookie_opt = if std::path::Path::new(&session_file).exists() {
                    Some(session_file)
                } else if std::path::Path::new("sessions/instagram_session.json").exists() {
                    Some("sessions/instagram_session.json".to_string())
                } else {
                    None
                };

                let res = mgr
                    .worker
                    .hydrate_single(
                        &item.target_id,
                        &item.id,
                        &item.shortcode,
                        &item.media_type,
                        &item.cdn_url,
                        &item.relative_file_path,
                        &out_dir,
                        &item.caption,
                        cookie_opt.as_deref(),
                        &q,
                        yt_dlp_enabled,
                    )
                    .await;

                if let Ok(mut handles) = mgr.active_handles.lock() {
                    handles.remove(&item.id);
                }

                match res {
                    Ok(outcome) => {
                        let actual_path = outcome
                            .actual_path
                            .unwrap_or_else(|| item.relative_file_path.clone());
                        let _ = mgr.db.update_media_hydrated(
                            &item.id,
                            &actual_path,
                            outcome.file_size,
                        );
                        if let Some(ref prov) = outcome.provider {
                            if prov != "cached" {
                                let _ = mgr.db.record_provider_result(prov, true, None);
                            }
                        }
                        if let Ok(mut list) = mgr.items.lock() {
                            if let Some(d) = list.iter_mut().find(|x| x.id == item.id) {
                                if d.status == "downloading" {
                                    d.status = "completed".to_string();
                                    d.progress = 100;
                                    d.relative_file_path = actual_path;
                                    d.file_size_bytes = outcome.file_size;
                                    d.speed_bps = outcome.speed_bps;
                                    d.provider = outcome.provider;
                                    d.error = None;
                                    d.updated_at = Utc::now().to_rfc3339();
                                }
                            }
                        }
                        info!(
                            "Downloaded {} ({} B) for @{}",
                            item.shortcode, outcome.file_size, item.username
                        );
                        let media_msg = serde_json::json!({
                            "type": "media_updated",
                            "target_id": item.target_id,
                            "id": item.id
                        });
                        let _ = mgr.events_tx.send(media_msg.to_string());
                    }
                    Err(e) => {
                        let err_str = e.to_string();
                        if let Ok(mut list) = mgr.items.lock() {
                            if let Some(d) = list.iter_mut().find(|x| x.id == item.id) {
                                if d.status == "downloading" {
                                    d.status = "failed".to_string();
                                    d.speed_bps = 0;
                                    d.error = Some(err_str.clone());
                                    d.updated_at = Utc::now().to_rfc3339();
                                }
                            }
                        }
                        error!("Download failed for {}: {}", item.shortcode, err_str);
                        let _ = mgr.db.record_provider_result("yt-dlp", false, Some(&err_str));
                        crate::api::dev_logs::push_dev_log(
                            "W",
                            "Downloads",
                            &format!("Failed {}: {}", item.shortcode, err_str),
                        );
                    }
                }

                mgr.broadcast_queue_update(&item.target_id);
                mgr.dispatch_notify.notify_one();
            });

            if let Ok(mut handles) = self.active_handles.lock() {
                handles.insert(item_id, task.abort_handle());
            }
        }
    }
}
