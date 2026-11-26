use anyhow::Result;
use chrono::{DateTime, Utc};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast;
use tracing::{error, info};

use crate::db::Database;
use crate::models::{ArchivedMedia, Target};
use crate::notify::Notifier;
use crate::worker::WorkerBridge;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProfileSnapshot {
    pub id: String,
    pub username: String,
    pub full_name: String,
    pub biography: String,
    pub is_private: bool,
    pub followed_by_viewer: bool,
    pub media_count: i64,
    #[serde(default)]
    pub followers_count: i64,
    #[serde(default)]
    pub following_count: i64,
    #[serde(default)]
    pub latest_story_at: i64,
    pub profile_pic_url: String,
    #[serde(default)]
    pub profile_pic_path: Option<String>,
    #[serde(default)]
    pub avatar_hash: Option<String>,
    #[serde(default)]
    pub profile_hash: Option<String>,
}

pub fn format_notification(lang: &str, event: &str, username: &str, detail: &str) -> (String, String) {
    match (lang, event) {
        ("en", "account_opened") => (
            format!("@{} is now public", username),
            "Account switched from private to public. Archiving started.".to_string(),
        ),
        ("ru", "account_opened") => (
            format!("@{} стал открытым", username),
            "Аккаунт перешел из закрытого в открытый. Архивация запущена.".to_string(),
        ),
        (_, "account_opened") => (
            format!("@{} açıq oldu", username),
            "Hesab gizlidən açıq statusa keçdi. Arxivləşdirmə başladıldı.".to_string(),
        ),
        ("en", "account_closed") => (
            format!("@{} is now private", username),
            "Account switched to private. Existing archive remains preserved.".to_string(),
        ),
        ("ru", "account_closed") => (
            format!("@{} стал закрытым", username),
            "Аккаунт перешел в закрытый режим. Архив сохранен.".to_string(),
        ),
        (_, "account_closed") => (
            format!("@{} gizli oldu", username),
            "Hesab gizli statusa keçdi. Mövcud arxiv qorunur.".to_string(),
        ),
        ("en", "avatar_change") => (
            format!("@{} updated profile photo", username),
            "New profile photo archived locally.".to_string(),
        ),
        ("ru", "avatar_change") => (
            format!("@{} обновил фото профиля", username),
            "Новое фото профиля сохранено в архив.".to_string(),
        ),
        (_, "avatar_change") => (
            format!("@{} profil şəklini yenilədi", username),
            "Yeni profil şəkli lokal arxivə saxlanıldı.".to_string(),
        ),
        ("en", "followers_change") => (
            format!("@{} followers changed ({})", username, detail),
            "Follower count update recorded.".to_string(),
        ),
        ("ru", "followers_change") => (
            format!("@{} подписчики изменились ({})", username, detail),
            "Изменение числа подписчиков зафиксировано.".to_string(),
        ),
        (_, "followers_change") => (
            format!("@{} izləyici sayı dəyişdi ({})", username, detail),
            "İzləyici dəyişikliyi qeydə alındı.".to_string(),
        ),
        ("en", "post_count_change") => (
            format!("@{} posts changed ({})", username, detail),
            "Timeline post count updated.".to_string(),
        ),
        ("ru", "post_count_change") => (
            format!("@{} публикации изменились ({})", username, detail),
            "Количество публикаций обновлено.".to_string(),
        ),
        (_, _) => (
            format!("@{} paylaşım sayı dəyişdi ({})", username, detail),
            "Profil paylaşım sayı yeniləndi.".to_string(),
        ),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DetectedChange {
    pub event_type: String,
    pub old_value: String,
    pub new_value: String,
}

pub fn detect_changes(old: &Target, new: &ProfileSnapshot) -> Vec<DetectedChange> {
    let mut changes = Vec::new();

    if old.is_private != new.is_private {
        changes.push(DetectedChange {
            event_type: "privacy_change".to_string(),
            old_value: old.is_private.to_string(),
            new_value: new.is_private.to_string(),
        });
    }

    if old.username != new.username {
        changes.push(DetectedChange {
            event_type: "username_change".to_string(),
            old_value: old.username.clone(),
            new_value: new.username.clone(),
        });
    }

    if old.full_name != new.full_name {
        changes.push(DetectedChange {
            event_type: "full_name_change".to_string(),
            old_value: old.full_name.clone(),
            new_value: new.full_name.clone(),
        });
    }

    if old.biography != new.biography {
        changes.push(DetectedChange {
            event_type: "bio_change".to_string(),
            old_value: old.biography.clone(),
            new_value: new.biography.clone(),
        });
    }

    if old.media_count != new.media_count && new.media_count > 0 {
        changes.push(DetectedChange {
            event_type: "post_count_change".to_string(),
            old_value: old.media_count.to_string(),
            new_value: new.media_count.to_string(),
        });
    }

    if old.followers_count > 0 && new.followers_count > 0 && old.followers_count != new.followers_count {
        changes.push(DetectedChange {
            event_type: "followers_change".to_string(),
            old_value: old.followers_count.to_string(),
            new_value: new.followers_count.to_string(),
        });
    }

    if old.following_count > 0 && new.following_count > 0 && old.following_count != new.following_count {
        changes.push(DetectedChange {
            event_type: "following_change".to_string(),
            old_value: old.following_count.to_string(),
            new_value: new.following_count.to_string(),
        });
    }

    // Second-layer hash-based validation for avatar
    let hash_changed = match (&old.avatar_hash, &new.avatar_hash) {
        (Some(oh), Some(nh)) => oh != nh,
        _ => false,
    };
    let path_changed = old.profile_pic_path.is_some()
        && new.profile_pic_path.is_some()
        && old.profile_pic_path != new.profile_pic_path;

    if hash_changed || path_changed {
        changes.push(DetectedChange {
            event_type: "avatar_change".to_string(),
            old_value: old.profile_pic_path.clone().or_else(|| old.avatar_hash.clone()).unwrap_or_default(),
            new_value: new.profile_pic_path.clone().or_else(|| new.avatar_hash.clone()).unwrap_or_default(),
        });
    }

    changes
}

#[derive(Clone)]
pub struct Scheduler {
    pub db: Database,
    pub notify: Arc<Notifier>,
    pub worker: Arc<WorkerBridge>,
    pub events_tx: broadcast::Sender<String>,
}

impl Scheduler {
    pub fn new(
        db: Database,
        notify: Arc<Notifier>,
        worker: Arc<WorkerBridge>,
    ) -> (Self, broadcast::Receiver<String>) {
        let (tx, rx) = broadcast::channel(100);
        (
            Self {
                db,
                notify,
                worker,
                events_tx: tx,
            },
            rx,
        )
    }

    pub async fn check_target(&self, target: &Target) -> Result<()> {
        info!("Checking target: @{}", target.username);

        let session_file = format!("sessions/session_{}.json", target.username);
        let session_path = if std::path::Path::new(&session_file).exists() {
            Some(session_file.as_str())
        } else if std::path::Path::new("sessions/instagram_session.json").exists() {
            Some("sessions/instagram_session.json")
        } else {
            None
        };

        let snapshot = match self
            .worker
            .check_profile(&target.username, session_path)
            .await
        {
            Ok(s) => s,
            Err(e) => {
                error!("Failed to check @{}: {}", target.username, e);
                return Err(e);
            }
        };

        let changes = detect_changes(target, &snapshot);
        let mut target_updated = target.clone();
        target_updated.last_checked_at = Some(Utc::now());

        for c in &changes {
            info!(
                "Change detected for @{}: {} ({} -> {})",
                target.username, c.event_type, c.old_value, c.new_value
            );
            self.db
                .log_change(&target.id, &c.event_type, &c.old_value, &c.new_value)?;

            let event_msg = serde_json::json!({
                "type": "change_detected",
                "target_id": target.id,
                "username": target.username,
                "event": c.event_type,
                "old": c.old_value,
                "new": c.new_value
            });
            let _ = self.events_tx.send(event_msg.to_string());
        }

        let notif_lang = self
            .db
            .get_setting("notification_language")
            .ok()
            .flatten()
            .unwrap_or_else(|| "az".to_string());

        // State transitions
        let account_opened = target.is_private && !snapshot.is_private;
        let account_closed = !target.is_private && snapshot.is_private;
        let mut archive_triggered = false;

        if account_opened {
            target_updated.last_status_change_at = Some(Utc::now());
            let (title, body) = format_notification(&notif_lang, "account_opened", &target.username, "");
            self.notify.send(&title, &body, true).await?;
            let _ = self.trigger_full_archive(&target.username, &target.id, session_path).await;
            archive_triggered = true;
        } else if account_closed {
            target_updated.last_status_change_at = Some(Utc::now());
            let (title, body) = format_notification(&notif_lang, "account_closed", &target.username, "");
            self.notify.send(&title, &body, false).await?;
        } else if snapshot.is_private && snapshot.followed_by_viewer {
            let _ = self.trigger_full_archive(&target.username, &target.id, session_path).await;
            archive_triggered = true;
        }

        let has_avatar_change = changes.iter().any(|c| c.event_type == "avatar_change");
        if has_avatar_change {
            let (title, body) = format_notification(&notif_lang, "avatar_change", &target.username, "");
            let _ = self.notify.send(&title, &body, false).await;
        }

        if let Some(fc) = changes.iter().find(|c| c.event_type == "followers_change") {
            let old_n = fc.old_value.parse::<i64>().unwrap_or(0);
            let new_n = fc.new_value.parse::<i64>().unwrap_or(0);
            let diff = new_n - old_n;
            let detail = if diff > 0 { format!("+{}", diff) } else { diff.to_string() };
            let (title, body) = format_notification(&notif_lang, "followers_change", &target.username, &detail);
            let _ = self.notify.send(&title, &body, false).await;
        }

        let post_count_grew = snapshot.media_count > target.media_count;
        if let Some(pc) = changes.iter().find(|c| c.event_type == "post_count_change") {
            let old_n = pc.old_value.parse::<i64>().unwrap_or(0);
            let new_n = pc.new_value.parse::<i64>().unwrap_or(0);
            let diff = new_n - old_n;
            let detail = if diff > 0 { format!("+{}", diff) } else { diff.to_string() };
            let (title, body) = format_notification(&notif_lang, "post_count_change", &target.username, &detail);
            let _ = self.notify.send(&title, &body, false).await;
        }

        target_updated.username = snapshot.username;
        target_updated.full_name = snapshot.full_name;
        target_updated.biography = snapshot.biography;
        target_updated.is_private = snapshot.is_private;
        target_updated.followed_by_viewer = snapshot.followed_by_viewer;
        target_updated.media_count = if snapshot.media_count > 0 { snapshot.media_count } else { target_updated.media_count };
        target_updated.followers_count = if snapshot.followers_count > 0 { snapshot.followers_count } else { target_updated.followers_count };
        target_updated.following_count = if snapshot.following_count > 0 { snapshot.following_count } else { target_updated.following_count };
        target_updated.latest_story_at = if snapshot.latest_story_at > 0 { snapshot.latest_story_at } else { target_updated.latest_story_at };
        target_updated.profile_pic_path = snapshot
            .profile_pic_path
            .clone()
            .or(target_updated.profile_pic_path);
        target_updated.avatar_hash = snapshot.avatar_hash.clone().or(target_updated.avatar_hash);
        target_updated.profile_hash = snapshot.profile_hash.clone().or(target_updated.profile_hash);

        self.db.update_target_profile(&target_updated)?;

        // Backfill posts & highlights for public accounts when local archive is empty or new posts appeared
        if !archive_triggered && (!target_updated.is_private || target_updated.followed_by_viewer) {
            let local_media_count = self
                .db
                .list_media_by_target(&target_updated.id, None)
                .map(|m| m.len())
                .unwrap_or(0);
            if (target_updated.media_count > 0 && local_media_count == 0) || post_count_grew {
                let _ = self
                    .trigger_full_archive(&target_updated.username, &target_updated.id, session_path)
                    .await;
            }
        }
        Ok(())
    }

    pub async fn trigger_full_archive(
        &self,
        username: &str,
        target_id: &str,
        session_path: Option<&str>,
    ) -> Result<()> {
        info!("Initiating Full Archive for @{} ({})", username, target_id);

        let out_dir = format!("archives/{}", target_id);
        std::fs::create_dir_all(&out_dir)?;

        let blitz = self
            .worker
            .archive_blitz(username, &out_dir, session_path)
            .await?;

        // Upsert items
        for item in &blitz.items {
            let media = ArchivedMedia {
                id: item.id.clone(),
                target_id: target_id.to_string(),
                media_type: item.media_type.clone(),
                highlight_title: item.highlight_title.clone(),
                caption: Some(item.caption.clone()),
                cdn_url: item.cdn_url.clone(),
                relative_file_path: item
                    .relative_file_path
                    .clone()
                    .unwrap_or_else(|| format!("archives/{}/{}/{}.jpg", target_id, item.media_type, item.shortcode)),
                file_size_bytes: 0,
                posted_at: DateTime::from_timestamp(item.posted_at, 0).unwrap_or_else(Utc::now),
                download_status: "pending".to_string(),
                downloaded_at: None,
                is_vanished: false,
                vanished_at: None,
            };
            let _ = self.db.upsert_media(&media);
        }

        // Update target media_count if items were discovered and current count is less
        if !blitz.items.is_empty() {
            if let Ok(Some(mut current_target)) = self.db.get_target(target_id) {
                if current_target.media_count == 0 || current_target.media_count < blitz.items.len() as i64 {
                    current_target.media_count = blitz.items.len() as i64;
                    let _ = self.db.update_target_profile(&current_target);
                }
            }
        }

        // Dump payload
        let blitz_json_path = format!("{}/blitz_items.json", out_dir);
        let blitz_payload = serde_json::json!({
            "target_id": target_id,
            "items": blitz.items
        });
        std::fs::write(&blitz_json_path, blitz_payload.to_string())?;

        let worker_clone = Arc::clone(&self.worker);
        let db_clone = self.db.clone();
        let target_id_owned = target_id.to_string();
        let out_dir_owned = out_dir.clone();
        let items_for_sync = blitz.items.clone();

        tokio::spawn(async move {
            info!("Starting Phase 2 asset hydration for target {}", target_id_owned);
            if let Err(e) = worker_clone
                .hydrate_assets(&target_id_owned, &blitz_json_path, &out_dir_owned)
                .await
            {
                error!("Hydration failed for target {}: {}", target_id_owned, e);
            } else {
                for item in items_for_sync {
                    if let Some(rel) = item.relative_file_path {
                        if let Ok(meta) = std::fs::metadata(&rel) {
                            if meta.len() > 0 {
                                let _ = db_clone.mark_media_completed(&item.id, meta.len() as i64);
                            }
                        }
                    }
                }
            }
        });

        Ok(())
    }

    pub fn start_loop(self: Arc<Self>) {
        tokio::spawn(async move {
            info!("Scheduler background loop started.");
            loop {
                let targets = match self.db.list_targets() {
                    Ok(t) => t,
                    Err(e) => {
                        error!("Failed to fetch targets in loop: {}", e);
                        tokio::time::sleep(Duration::from_secs(10)).await;
                        continue;
                    }
                };

                for target in targets {
                    if target.status != "active" {
                        continue;
                    }

                    let base_interval = if target.turbo_mode {
                        45
                    } else {
                        target.check_interval_seconds
                    };

                    // Add organic jitter ±15-30 seconds
                    let jitter: i64 = rand::thread_rng().gen_range(-15..=15);
                    let interval = (base_interval + jitter).max(20) as u64;

                    let should_check = match target.last_checked_at {
                        None => true,
                        Some(last) => {
                            let elapsed = Utc::now().signed_duration_since(last).num_seconds();
                            elapsed >= interval as i64
                        }
                    };

                    if should_check {
                        if let Err(e) = self.check_target(&target).await {
                            error!("Error checking @{}: {}", target.username, e);
                        }
                    }
                }

                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        });
    }
}
