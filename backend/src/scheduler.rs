use anyhow::Result;
use chrono::{DateTime, Utc};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast;
use tracing::{error, info};

use crate::db::Database;
use crate::downloads::DownloadManager;
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
        ("en", "post_vanished") => (
            format!("@{} deleted a post", username),
            detail.to_string(),
        ),
        ("ru", "post_vanished") => (
            format!("@{} удалил публикацию", username),
            detail.to_string(),
        ),
        (_, "post_vanished") => (
            format!("@{} paylaşım sildi", username),
            detail.to_string(),
        ),
        ("en", "follower_gained") => (
            format!("@{} gained follower @{}", username, detail),
            "New follower detected.".to_string(),
        ),
        ("ru", "follower_gained") => (
            format!("Новый подписчик @{}: @{}", username, detail),
            "Зафиксирован новый подписчик.".to_string(),
        ),
        (_, "follower_gained") => (
            format!("@{} yeni izləyici: @{}", username, detail),
            "Yeni izləyici qeydə alındı.".to_string(),
        ),
        ("en", "follower_lost") => (
            format!("@{} lost follower @{}", username, detail),
            "User unfollowed this profile.".to_string(),
        ),
        ("ru", "follower_lost") => (
            format!("Отписка от @{}: @{}", username, detail),
            "Пользователь отписался от профиля.".to_string(),
        ),
        (_, "follower_lost") => (
            format!("@{} izləyici itirdi: @{}", username, detail),
            "İstifadəçi izləmədən çıxdı.".to_string(),
        ),
        ("en", "following_gained") => (
            format!("@{} followed @{}", username, detail),
            "Profile started following a new account.".to_string(),
        ),
        ("ru", "following_gained") => (
            format!("@{} подписался на @{}", username, detail),
            "Добавлена новая подписка.".to_string(),
        ),
        (_, "following_gained") => (
            format!("@{} izləməyə başladı: @{}", username, detail),
            "Yeni izlənilən hesab qeydə alındı.".to_string(),
        ),
        ("en", "following_lost") => (
            format!("@{} unfollowed @{}", username, detail),
            "Profile stopped following an account.".to_string(),
        ),
        ("ru", "following_lost") => (
            format!("@{} отписался от @{}", username, detail),
            "Подписка удалена.".to_string(),
        ),
        (_, "following_lost") => (
            format!("@{} izləmədən çıxardı: @{}", username, detail),
            "İzlənilən hesab çıxarıldı.".to_string(),
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

fn dhash_hamming(a: &str, b: &str) -> Option<u32> {
    let ha = a.strip_prefix("dhash:")?;
    let hb = b.strip_prefix("dhash:")?;
    let va = u64::from_str_radix(ha, 16).ok()?;
    let vb = u64::from_str_radix(hb, 16).ok()?;
    Some((va ^ vb).count_ones())
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

    // Visual perceptual hash check for avatar (immune to CDN URL rotation / JPEG re-encoding)
    let avatar_truly_changed = match (&old.avatar_hash, &new.avatar_hash) {
        (Some(oh), Some(nh)) if oh != nh => {
            if let Some(dist) = dhash_hamming(oh, nh) {
                dist > 8
            } else if oh.starts_with("dhash:") != nh.starts_with("dhash:") {
                // One-time migration from legacy SHA-256 to dHash: do not fire a false positive
                false
            } else {
                true
            }
        }
        _ => false,
    };

    if avatar_truly_changed {
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
    pub downloads: Arc<DownloadManager>,
    pub events_tx: broadcast::Sender<String>,
}

impl Scheduler {
    pub fn new(
        db: Database,
        notify: Arc<Notifier>,
        worker: Arc<WorkerBridge>,
    ) -> (Self, broadcast::Receiver<String>) {
        let (tx, rx) = broadcast::channel(200);
        let downloads = DownloadManager::new(db.clone(), Arc::clone(&worker), tx.clone());
        (
            Self {
                db,
                notify,
                worker,
                downloads,
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
        let post_count_dropped = snapshot.media_count > 0 && snapshot.media_count < target.media_count;
        let story_grew = snapshot.latest_story_at > target.latest_story_at && snapshot.latest_story_at > 0;
        let followers_changed = target.followers_count > 0 && snapshot.followers_count > 0 && target.followers_count != snapshot.followers_count;
        let following_changed = target.following_count > 0 && snapshot.following_count > 0 && target.following_count != snapshot.following_count;
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

        // Backfill posts, stories & highlights for public accounts when archive is incomplete or new content appeared
        if !archive_triggered && (!target_updated.is_private || target_updated.followed_by_viewer) {
            let local_post_count = self
                .db
                .list_media_by_target(&target_updated.id, None)
                .map(|m| {
                    m.iter()
                        .filter(|x| x.media_type == "post" || x.media_type == "reel")
                        .count() as i64
                })
                .unwrap_or(0);
            let max_limit = self
                .db
                .get_setting("max_posts_sync_limit")
                .ok()
                .flatten()
                .and_then(|v| v.parse::<i64>().ok())
                .unwrap_or(50);
            let expected_target_posts = if max_limit > 0 {
                target_updated.media_count.min(max_limit)
            } else {
                target_updated.media_count
            };
            let needs_backfill = (expected_target_posts > 0 && local_post_count == 0)
                || post_count_grew
                || post_count_dropped
                || story_grew;
            if needs_backfill {
                let _ = self
                    .trigger_full_archive(&target_updated.username, &target_updated.id, session_path)
                    .await;
            }
        }

        // Sync followers & following lists when session is present and either counts shifted or archive is empty
        if session_path.is_some() && (!target_updated.is_private || target_updated.followed_by_viewer) {
            let local_followers = self
                .db
                .list_relationships(&target_updated.id, "followers")
                .map(|v| v.len())
                .unwrap_or(0);
            let local_following = self
                .db
                .list_relationships(&target_updated.id, "following")
                .map(|v| v.len())
                .unwrap_or(0);
            if followers_changed
                || following_changed
                || (target_updated.followers_count > 0 && local_followers == 0)
                || (target_updated.following_count > 0 && local_following == 0)
            {
                let _ = self
                    .sync_relationships(&target_updated.id, &target_updated.username, session_path)
                    .await;
            }
        }
        Ok(())
    }

    pub async fn sync_relationships(
        &self,
        target_id: &str,
        username: &str,
        session_path: Option<&str>,
    ) -> Result<(usize, usize)> {
        let res = self
            .worker
            .fetch_relationships(username, Some(target_id), session_path, 500)
            .await?;

        let guard_enabled = self
            .db
            .get_setting("list_completeness_guard")
            .ok()
            .flatten()
            .map(|v| v != "false")
            .unwrap_or(true);
        let notif_lang = self
            .db
            .get_setting("notification_language")
            .ok()
            .flatten()
            .unwrap_or_else(|| "az".to_string());
        let target_opt = self.db.get_target(target_id).ok().flatten();

        let mut process_kind = |kind: &str,
                                fresh: &[crate::models::RelationshipUser],
                                reported_total: i64|
         -> Vec<(String, crate::models::RelationshipUser)> {
            if fresh.is_empty() {
                return Vec::new();
            }
            let old_users = self
                .db
                .list_relationships(target_id, kind)
                .unwrap_or_default();
            let _ = self.db.replace_relationships(target_id, kind, fresh);

            if old_users.is_empty() {
                return Vec::new();
            }
            let min_complete = ((reported_total as f64) * 0.85).ceil() as usize;
            let complete = !guard_enabled || reported_total <= 50 || fresh.len() >= min_complete;
            if !complete {
                return Vec::new();
            }

            let old_set: std::collections::HashSet<String> =
                old_users.iter().map(|u| u.username.to_lowercase()).collect();
            let new_set: std::collections::HashSet<String> =
                fresh.iter().map(|u| u.username.to_lowercase()).collect();

            let gain_event = if kind == "followers" { "follower_gained" } else { "following_gained" };
            let loss_event = if kind == "followers" { "follower_lost" } else { "following_lost" };
            let mut events = Vec::new();
            for u in fresh {
                if !old_set.contains(&u.username.to_lowercase()) {
                    let _ = self.db.insert_follower_audit(target_id, gain_event, u);
                    let _ = self.db.log_change(target_id, gain_event, "", &format!("@{}", u.username));
                    events.push((gain_event.to_string(), u.clone()));
                }
            }
            for u in &old_users {
                if !new_set.contains(&u.username.to_lowercase()) {
                    let _ = self.db.insert_follower_audit(target_id, loss_event, u);
                    let _ = self.db.log_change(target_id, loss_event, &format!("@{}", u.username), "");
                    events.push((loss_event.to_string(), u.clone()));
                }
            }
            events
        };

        let reported_followers = target_opt.as_ref().map(|t| t.followers_count).unwrap_or(0);
        let reported_following = target_opt.as_ref().map(|t| t.following_count).unwrap_or(0);
        let mut emitted = process_kind("followers", &res.followers, reported_followers);
        emitted.extend(process_kind("following", &res.following, reported_following));

        for (ev, u) in emitted {
            let (title, body) = format_notification(&notif_lang, &ev, username, &u.username);
            let _ = self.notify.send(&title, &body, false).await;
            let msg = serde_json::json!({
                "type": "change_detected",
                "target_id": target_id,
                "username": username,
                "event": ev,
                "old": "",
                "new": format!("@{}", u.username)
            });
            let _ = self.events_tx.send(msg.to_string());
        }

        Ok((res.followers.len(), res.following.len()))
    }

    pub async fn trigger_full_archive(
        &self,
        username: &str,
        target_id: &str,
        session_path: Option<&str>,
    ) -> Result<()> {
        info!("Initiating Full Archive for @{} ({})", username, target_id);
        self.downloads.mark_archiving(target_id, true);

        let out_dir = format!("archives/{}", target_id);
        if let Err(e) = std::fs::create_dir_all(&out_dir) {
            self.downloads.mark_archiving(target_id, false);
            return Err(e.into());
        }

        let max_posts_limit = self
            .db
            .get_setting("max_posts_sync_limit")
            .ok()
            .flatten()
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(50);

        let blitz = match self
            .worker
            .archive_blitz(username, &out_dir, session_path, max_posts_limit)
            .await
        {
            Ok(b) => b,
            Err(e) => {
                self.downloads.mark_archiving(target_id, false);
                return Err(e);
            }
        };

        // Snapshot previously archived active posts/reels before upserting so we can identify exact vanished posts
        let existing_media = self
            .db
            .list_media_by_target(target_id, None)
            .unwrap_or_default();

        // Upsert items into SQLite immediately so Gallery shows them right away
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
                    .unwrap_or_else(|| format!("archives/{}/{}s/{}.jpg", target_id, item.media_type, item.shortcode)),
                file_size_bytes: 0,
                posted_at: DateTime::from_timestamp(item.posted_at, 0).unwrap_or_else(Utc::now),
                download_status: "pending".to_string(),
                downloaded_at: None,
                is_vanished: false,
                vanished_at: None,
            };
            let _ = self.db.upsert_media(&media);
        }

        // Exact Vanished Post Detection (when blitz fetched full active post/reel list)
        let discovered_post_ids: std::collections::HashSet<String> = blitz
            .items
            .iter()
            .filter(|x| x.media_type == "post" || x.media_type == "reel")
            .map(|x| x.id.clone())
            .collect();
        let reported_media_count = blitz
            .profile
            .as_ref()
            .map(|p| p.media_count)
            .unwrap_or_else(|| {
                self.db
                    .get_target(target_id)
                    .ok()
                    .flatten()
                    .map(|t| t.media_count)
                    .unwrap_or(0)
            });
        let has_complete_post_snapshot = !discovered_post_ids.is_empty()
            && (max_posts_limit == 0
                || reported_media_count == 0
                || (discovered_post_ids.len() as i64) >= reported_media_count);
        if has_complete_post_snapshot {
            let notif_lang = self
                .db
                .get_setting("notification_language")
                .ok()
                .flatten()
                .unwrap_or_else(|| "az".to_string());
            for old_item in &existing_media {
                if (old_item.media_type == "post" || old_item.media_type == "reel")
                    && !old_item.is_vanished
                    && !discovered_post_ids.contains(&old_item.id)
                {
                    let _ = self.db.mark_media_vanished(&old_item.id);
                    let shortcode = old_item
                        .id
                        .strip_prefix("post_")
                        .or_else(|| old_item.id.strip_prefix("reel_"))
                        .unwrap_or(&old_item.id);
                    let caption_snippet: String = old_item
                        .caption
                        .as_deref()
                        .unwrap_or("")
                        .replace('\n', " ")
                        .chars()
                        .take(90)
                        .collect();
                    let detail = if caption_snippet.trim().is_empty() {
                        format!("/p/{}", shortcode)
                    } else {
                        format!("/p/{} — {}", shortcode, caption_snippet.trim())
                    };
                    let old_meta = format!("{}|{}", shortcode, old_item.relative_file_path);
                    let _ = self.db.log_change(target_id, "post_vanished", &old_meta, &detail);
                    let (title, body) = format_notification(&notif_lang, "post_vanished", username, &detail);
                    let _ = self.notify.send(&title, &body, true).await;
                    let msg = serde_json::json!({
                        "type": "change_detected",
                        "target_id": target_id,
                        "username": username,
                        "event": "post_vanished",
                        "old": old_meta,
                        "new": detail
                    });
                    let _ = self.events_tx.send(msg.to_string());
                }
            }
        }

        if !blitz.items.is_empty() {
            let discovered_posts = blitz
                .items
                .iter()
                .filter(|x| x.media_type == "post" || x.media_type == "reel")
                .count() as i64;
            if let Ok(Some(mut current_target)) = self.db.get_target(target_id) {
                if current_target.media_count == 0 || current_target.media_count < discovered_posts {
                    current_target.media_count = discovered_posts;
                    let _ = self.db.update_target_profile(&current_target);
                }
            }
            let discovered_msg = serde_json::json!({
                "type": "media_discovered",
                "target_id": target_id,
                "count": blitz.items.len()
            });
            let _ = self.events_tx.send(discovered_msg.to_string());
        }

        // Dump payload for audit
        let blitz_json_path = format!("{}/blitz_items.json", out_dir);
        let blitz_payload = serde_json::json!({
            "target_id": target_id,
            "items": blitz.items
        });
        let _ = std::fs::write(&blitz_json_path, blitz_payload.to_string());

        let auto_download = self
            .db
            .get_setting("auto_download_media")
            .ok()
            .flatten()
            .map(|v| v != "false")
            .unwrap_or(true);

        if auto_download && !blitz.items.is_empty() {
            self.downloads.enqueue_batch(target_id, username, &blitz.items);
        }

        self.downloads.mark_archiving(target_id, false);
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
