use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use std::sync::{Arc, Mutex};

use crate::models::{
    ArchivedMedia, ChangeLog, FollowerAudit, FollowerHistoryPoint, MediaAsset, NotificationRecord,
    NotificationRule, PostComment, ProviderHealth, RelationshipUser, Target,
};

#[derive(Clone)]
pub struct Database {
    conn: Arc<Mutex<Connection>>,
}

impl Database {
    pub fn init(path: &str) -> Result<Self> {
        let conn = if path == ":memory:" {
            Connection::open_in_memory()?
        } else {
            Connection::open(path)
                .with_context(|| format!("Failed to open SQLite database at {}", path))?
        };

        // WAL mode and pragma configuration
        if path != ":memory:" {
            conn.execute_batch(
                "PRAGMA journal_mode = WAL;
                 PRAGMA synchronous = NORMAL;
                 PRAGMA foreign_keys = ON;
                 PRAGMA busy_timeout = 5000;",
            )?;
        } else {
            conn.execute_batch(
                "PRAGMA foreign_keys = ON;
                 PRAGMA busy_timeout = 5000;",
            )?;
        }

        let db = Database {
            conn: Arc::new(Mutex::new(conn)),
        };

        db.create_tables()?;
        Ok(db)
    }

    fn create_tables(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS targets (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                full_name TEXT NOT NULL DEFAULT '',
                biography TEXT NOT NULL DEFAULT '',
                is_private BOOLEAN NOT NULL DEFAULT 0,
                followed_by_viewer BOOLEAN NOT NULL DEFAULT 0,
                media_count INTEGER NOT NULL DEFAULT 0,
                followers_count INTEGER NOT NULL DEFAULT 0,
                following_count INTEGER NOT NULL DEFAULT 0,
                latest_story_at INTEGER NOT NULL DEFAULT 0,
                profile_pic_path TEXT,
                avatar_hash TEXT,
                profile_hash TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                turbo_mode BOOLEAN NOT NULL DEFAULT 0,
                check_interval_seconds INTEGER NOT NULL DEFAULT 300,
                last_checked_at TEXT,
                last_status_change_at TEXT,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS change_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                old_value TEXT NOT NULL,
                new_value TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS archived_media (
                id TEXT PRIMARY KEY,
                target_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                highlight_title TEXT,
                caption TEXT,
                cdn_url TEXT NOT NULL,
                relative_file_path TEXT NOT NULL,
                file_size_bytes INTEGER NOT NULL DEFAULT 0,
                posted_at TEXT NOT NULL,
                download_status TEXT NOT NULL DEFAULT 'pending',
                downloaded_at TEXT,
                is_vanished BOOLEAN NOT NULL DEFAULT 0,
                vanished_at TEXT,
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS system_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS target_relationships (
                target_id TEXT NOT NULL,
                relation_type TEXT NOT NULL,
                user_id TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL,
                full_name TEXT NOT NULL DEFAULT '',
                profile_pic_url TEXT NOT NULL DEFAULT '',
                is_private BOOLEAN NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (target_id, relation_type, username),
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS follower_audits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                username TEXT NOT NULL,
                full_name TEXT NOT NULL DEFAULT '',
                profile_pic_url TEXT NOT NULL DEFAULT '',
                timestamp TEXT NOT NULL,
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS follower_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_id TEXT NOT NULL,
                followers_count INTEGER NOT NULL DEFAULT 0,
                following_count INTEGER NOT NULL DEFAULT 0,
                media_count INTEGER NOT NULL DEFAULT 0,
                recorded_at TEXT NOT NULL,
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS media_assets (
                id TEXT PRIMARY KEY,
                media_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                media_type TEXT NOT NULL DEFAULT 'image',
                cdn_url TEXT NOT NULL DEFAULT '',
                relative_file_path TEXT NOT NULL DEFAULT '',
                file_size_bytes INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS post_comments (
                id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                parent_comment_id TEXT,
                author_username TEXT NOT NULL,
                author_full_name TEXT NOT NULL DEFAULT '',
                is_verified BOOLEAN NOT NULL DEFAULT 0,
                text TEXT NOT NULL,
                like_count INTEGER NOT NULL DEFAULT 0,
                reply_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                collection_status TEXT NOT NULL DEFAULT 'complete',
                FOREIGN KEY (target_id) REFERENCES targets(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS provider_health (
                provider_id TEXT PRIMARY KEY,
                status TEXT NOT NULL DEFAULT 'healthy',
                consecutive_failures INTEGER NOT NULL DEFAULT 0,
                cooldown_until INTEGER NOT NULL DEFAULT 0,
                success_count INTEGER NOT NULL DEFAULT 0,
                failure_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_id TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                event_type TEXT NOT NULL DEFAULT 'info',
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                is_read BOOLEAN NOT NULL DEFAULT 0,
                timestamp TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS notification_rules (
                scope_key TEXT PRIMARY KEY,
                enabled BOOLEAN NOT NULL DEFAULT 1,
                privacy_change BOOLEAN NOT NULL DEFAULT 1,
                bio_change BOOLEAN NOT NULL DEFAULT 1,
                avatar_change BOOLEAN NOT NULL DEFAULT 1,
                post_count_change BOOLEAN NOT NULL DEFAULT 1,
                post_vanished BOOLEAN NOT NULL DEFAULT 1,
                story_added BOOLEAN NOT NULL DEFAULT 1,
                followers_change BOOLEAN NOT NULL DEFAULT 1,
                following_change BOOLEAN NOT NULL DEFAULT 1,
                quiet_start TEXT,
                quiet_end TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_change_logs_target ON change_logs(target_id, timestamp DESC);
            CREATE INDEX IF NOT EXISTS idx_archived_media_target ON archived_media(target_id, posted_at DESC);
            CREATE INDEX IF NOT EXISTS idx_archived_media_type ON archived_media(target_id, media_type);
            CREATE INDEX IF NOT EXISTS idx_archived_media_vanished ON archived_media(target_id, is_vanished);
            CREATE INDEX IF NOT EXISTS idx_relationships_target ON target_relationships(target_id, relation_type);
            CREATE INDEX IF NOT EXISTS idx_follower_audits_target ON follower_audits(target_id, id DESC);
            CREATE INDEX IF NOT EXISTS idx_follower_history_target ON follower_history(target_id, recorded_at ASC);
            CREATE INDEX IF NOT EXISTS idx_media_assets_media ON media_assets(media_id, position ASC);
            CREATE INDEX IF NOT EXISTS idx_post_comments_post ON post_comments(post_id, like_count DESC);
            CREATE INDEX IF NOT EXISTS idx_notifications_ts ON notifications(id DESC);",
        )?;
        let _ = conn.execute("ALTER TABLE targets ADD COLUMN avatar_hash TEXT", []);
        let _ = conn.execute("ALTER TABLE targets ADD COLUMN profile_hash TEXT", []);
        let _ = conn.execute("ALTER TABLE targets ADD COLUMN followers_count INTEGER NOT NULL DEFAULT 0", []);
        let _ = conn.execute("ALTER TABLE targets ADD COLUMN following_count INTEGER NOT NULL DEFAULT 0", []);
        let _ = conn.execute("ALTER TABLE targets ADD COLUMN latest_story_at INTEGER NOT NULL DEFAULT 0", []);
        Ok(())
    }

    // --- Target CRUD ---

    pub fn create_target(&self, target: &Target) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO targets (
                id, username, full_name, biography, is_private, followed_by_viewer,
                media_count, followers_count, following_count, latest_story_at,
                profile_pic_path, avatar_hash, profile_hash, status, turbo_mode,
                check_interval_seconds, last_checked_at, last_status_change_at, created_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)",
            params![
                target.id,
                target.username,
                target.full_name,
                target.biography,
                target.is_private,
                target.followed_by_viewer,
                target.media_count,
                target.followers_count,
                target.following_count,
                target.latest_story_at,
                target.profile_pic_path,
                target.avatar_hash,
                target.profile_hash,
                target.status,
                target.turbo_mode,
                target.check_interval_seconds,
                target.last_checked_at.map(|t| t.to_rfc3339()),
                target.last_status_change_at.map(|t| t.to_rfc3339()),
                target.created_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn get_target(&self, id_or_username: &str) -> Result<Option<Target>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, username, full_name, biography, is_private, followed_by_viewer,
                    media_count, followers_count, following_count, latest_story_at,
                    profile_pic_path, avatar_hash, profile_hash, status, turbo_mode,
                    check_interval_seconds, last_checked_at, last_status_change_at, created_at
             FROM targets WHERE id = ?1 OR username = ?1 LIMIT 1",
        )?;

        let target = stmt
            .query_row(params![id_or_username], |row| {
                Ok(Target {
                    id: row.get(0)?,
                    username: row.get(1)?,
                    full_name: row.get(2)?,
                    biography: row.get(3)?,
                    is_private: row.get(4)?,
                    followed_by_viewer: row.get(5)?,
                    media_count: row.get(6)?,
                    followers_count: row.get(7)?,
                    following_count: row.get(8)?,
                    latest_story_at: row.get(9)?,
                    profile_pic_path: row.get(10)?,
                    avatar_hash: row.get(11)?,
                    profile_hash: row.get(12)?,
                    status: row.get(13)?,
                    turbo_mode: row.get(14)?,
                    check_interval_seconds: row.get(15)?,
                    last_checked_at: row
                        .get::<_, Option<String>>(16)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    last_status_change_at: row
                        .get::<_, Option<String>>(17)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(18)?)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                })
            })
            .optional()?;

        Ok(target)
    }

    pub fn list_targets(&self) -> Result<Vec<Target>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, username, full_name, biography, is_private, followed_by_viewer,
                    media_count, followers_count, following_count, latest_story_at,
                    profile_pic_path, avatar_hash, profile_hash, status, turbo_mode,
                    check_interval_seconds, last_checked_at, last_status_change_at, created_at
             FROM targets ORDER BY created_at DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(Target {
                id: row.get(0)?,
                username: row.get(1)?,
                full_name: row.get(2)?,
                biography: row.get(3)?,
                is_private: row.get(4)?,
                followed_by_viewer: row.get(5)?,
                media_count: row.get(6)?,
                followers_count: row.get(7)?,
                following_count: row.get(8)?,
                latest_story_at: row.get(9)?,
                profile_pic_path: row.get(10)?,
                avatar_hash: row.get(11)?,
                profile_hash: row.get(12)?,
                status: row.get(13)?,
                turbo_mode: row.get(14)?,
                check_interval_seconds: row.get(15)?,
                last_checked_at: row
                    .get::<_, Option<String>>(16)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                last_status_change_at: row
                    .get::<_, Option<String>>(17)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(18)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        })?;

        let mut targets = Vec::new();
        for r in rows {
            targets.push(r?);
        }
        Ok(targets)
    }

    pub fn update_target_profile(&self, target: &Target) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE targets SET
                username = ?1,
                full_name = ?2,
                biography = ?3,
                is_private = ?4,
                followed_by_viewer = ?5,
                media_count = ?6,
                followers_count = ?7,
                following_count = ?8,
                latest_story_at = ?9,
                profile_pic_path = ?10,
                avatar_hash = ?11,
                profile_hash = ?12,
                status = ?13,
                last_checked_at = ?14,
                last_status_change_at = ?15
             WHERE id = ?16",
            params![
                target.username,
                target.full_name,
                target.biography,
                target.is_private,
                target.followed_by_viewer,
                target.media_count,
                target.followers_count,
                target.following_count,
                target.latest_story_at,
                target.profile_pic_path,
                target.avatar_hash,
                target.profile_hash,
                target.status,
                target.last_checked_at.map(|t| t.to_rfc3339()),
                target.last_status_change_at.map(|t| t.to_rfc3339()),
                target.id,
            ],
        )?;
        Ok(())
    }

    pub fn set_turbo_mode(&self, id: &str, turbo: bool) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE targets SET turbo_mode = ?1 WHERE id = ?2",
            params![turbo, id],
        )?;
        Ok(())
    }

    pub fn set_target_status(&self, id: &str, status: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE targets SET status = ?1 WHERE id = ?2 OR username = ?2",
            params![status, id],
        )?;
        Ok(())
    }

    pub fn set_target_interval(&self, id: &str, interval_seconds: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE targets SET check_interval_seconds = ?1 WHERE id = ?2 OR username = ?2",
            params![interval_seconds.clamp(30, 86400), id],
        )?;
        Ok(())
    }

    pub fn delete_target(&self, id_or_username: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT id, username FROM targets WHERE id = ?1 OR username = ?1 LIMIT 1")?;
        let maybe_target = stmt.query_row(params![id_or_username], |row| {
            let id: String = row.get(0)?;
            let username: String = row.get(1)?;
            Ok((id, username))
        });

        if let Ok((id, username)) = maybe_target {
            conn.execute("DELETE FROM targets WHERE id = ?1 OR username = ?2", params![id, username])?;
            let _ = conn.execute("DELETE FROM change_logs WHERE target_id = ?1 OR target_id = ?2", params![id, username]);
            let _ = conn.execute("DELETE FROM archived_media WHERE target_id = ?1 OR target_id = ?2", params![id, username]);
        } else {
            conn.execute("DELETE FROM targets WHERE id = ?1 OR username = ?1", params![id_or_username])?;
            let _ = conn.execute("DELETE FROM change_logs WHERE target_id = ?1", params![id_or_username]);
            let _ = conn.execute("DELETE FROM archived_media WHERE target_id = ?1", params![id_or_username]);
        }
        Ok(())
    }

    // --- Change Logs ---

    pub fn log_change(
        &self,
        target_id: &str,
        event_type: &str,
        old_val: &str,
        new_val: &str,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO change_logs (target_id, event_type, old_value, new_value, timestamp)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![target_id, event_type, old_val, new_val, now],
        )?;
        Ok(())
    }

    pub fn get_change_logs(&self, target_id: &str) -> Result<Vec<ChangeLog>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, target_id, event_type, old_value, new_value, timestamp
             FROM change_logs WHERE target_id = ?1 ORDER BY id DESC",
        )?;

        let rows = stmt.query_map(params![target_id], |row| {
            Ok(ChangeLog {
                id: row.get(0)?,
                target_id: row.get(1)?,
                event_type: row.get(2)?,
                old_value: row.get(3)?,
                new_value: row.get(4)?,
                timestamp: DateTime::parse_from_rfc3339(&row.get::<_, String>(5)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        })?;

        let mut logs = Vec::new();
        for r in rows {
            logs.push(r?);
        }
        Ok(logs)
    }

    // --- Archived Media ---

    pub fn upsert_media(&self, media: &ArchivedMedia) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO archived_media (
                id, target_id, media_type, highlight_title, caption, cdn_url,
                relative_file_path, file_size_bytes, posted_at, download_status,
                downloaded_at, is_vanished, vanished_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
            ON CONFLICT(id) DO UPDATE SET
                caption = excluded.caption,
                cdn_url = excluded.cdn_url,
                relative_file_path = CASE WHEN archived_media.download_status = 'completed' AND archived_media.file_size_bytes > 0 THEN archived_media.relative_file_path ELSE excluded.relative_file_path END,
                file_size_bytes = CASE WHEN excluded.file_size_bytes > 0 THEN excluded.file_size_bytes ELSE archived_media.file_size_bytes END,
                download_status = CASE WHEN archived_media.download_status = 'completed' AND archived_media.file_size_bytes > 0 THEN 'completed' ELSE excluded.download_status END,
                downloaded_at = COALESCE(excluded.downloaded_at, archived_media.downloaded_at)",
            params![
                media.id,
                media.target_id,
                media.media_type,
                media.highlight_title,
                media.caption,
                media.cdn_url,
                media.relative_file_path,
                media.file_size_bytes,
                media.posted_at.to_rfc3339(),
                media.download_status,
                media.downloaded_at.map(|t| t.to_rfc3339()),
                media.is_vanished,
                media.vanished_at.map(|t| t.to_rfc3339()),
            ],
        )?;
        Ok(())
    }

    pub fn get_media(&self, id: &str) -> Result<Option<ArchivedMedia>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, target_id, media_type, highlight_title, caption, cdn_url,
                    relative_file_path, file_size_bytes, posted_at, download_status,
                    downloaded_at, is_vanished, vanished_at
             FROM archived_media WHERE id = ?1 LIMIT 1",
        )?;

        let media = stmt
            .query_row(params![id], |row| {
                Ok(ArchivedMedia {
                    id: row.get(0)?,
                    target_id: row.get(1)?,
                    media_type: row.get(2)?,
                    highlight_title: row.get(3)?,
                    caption: row.get(4)?,
                    cdn_url: row.get(5)?,
                    relative_file_path: row.get(6)?,
                    file_size_bytes: row.get(7)?,
                    posted_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(8)?)
                        .map(|dt| dt.with_timezone(&Utc))
                        .unwrap_or_else(|_| Utc::now()),
                    download_status: row.get(9)?,
                    downloaded_at: row
                        .get::<_, Option<String>>(10)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    is_vanished: row.get(11)?,
                    vanished_at: row
                        .get::<_, Option<String>>(12)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                })
            })
            .optional()?;

        Ok(media)
    }

    pub fn mark_media_vanished(&self, id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "UPDATE archived_media SET is_vanished = 1, vanished_at = ?1 WHERE id = ?2",
            params![now, id],
        )?;
        Ok(())
    }

    pub fn mark_media_completed(&self, id: &str, file_size_bytes: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "UPDATE archived_media SET download_status = 'completed', downloaded_at = ?1, file_size_bytes = ?2 WHERE id = ?3",
            params![now, file_size_bytes, id],
        )?;
        Ok(())
    }

    pub fn update_media_hydrated(&self, id: &str, relative_file_path: &str, file_size_bytes: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "UPDATE archived_media SET relative_file_path = ?1, file_size_bytes = ?2, download_status = 'completed', downloaded_at = ?3 WHERE id = ?4",
            params![relative_file_path, file_size_bytes, now, id],
        )?;
        Ok(())
    }

    pub fn list_media_by_target(
        &self,
        target_id: &str,
        media_type: Option<&str>,
    ) -> Result<Vec<ArchivedMedia>> {
        let conn = self.conn.lock().unwrap();
        let base_select = "SELECT id, target_id, media_type, highlight_title, caption, cdn_url,
                                  relative_file_path, file_size_bytes, posted_at, download_status,
                                  downloaded_at, is_vanished, vanished_at
                           FROM archived_media
                           WHERE (target_id = ?1
                              OR target_id = (SELECT id FROM targets WHERE username = ?1 LIMIT 1)
                              OR target_id = (SELECT username FROM targets WHERE id = ?1 LIMIT 1))";

        let map_row = |row: &rusqlite::Row| {
            Ok(ArchivedMedia {
                id: row.get(0)?,
                target_id: row.get(1)?,
                media_type: row.get(2)?,
                highlight_title: row.get(3)?,
                caption: row.get(4)?,
                cdn_url: row.get(5)?,
                relative_file_path: row.get(6)?,
                file_size_bytes: row.get(7)?,
                posted_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(8)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
                download_status: row.get(9)?,
                downloaded_at: row
                    .get::<_, Option<String>>(10)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                is_vanished: row.get(11)?,
                vanished_at: row
                    .get::<_, Option<String>>(12)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
            })
        };

        let mut items = Vec::new();
        match media_type {
            Some("vanished") => {
                let sql = format!("{} AND is_vanished = 1 ORDER BY posted_at DESC", base_select);
                let mut stmt = conn.prepare(&sql)?;
                let rows = stmt.query_map(params![target_id], map_row)?;
                for r in rows {
                    items.push(r?);
                }
            }
            Some(mt) => {
                let sql = format!("{} AND media_type = ?2 ORDER BY posted_at DESC", base_select);
                let mut stmt = conn.prepare(&sql)?;
                let rows = stmt.query_map(params![target_id, mt], map_row)?;
                for r in rows {
                    items.push(r?);
                }
            }
            None => {
                let sql = format!("{} ORDER BY posted_at DESC", base_select);
                let mut stmt = conn.prepare(&sql)?;
                let rows = stmt.query_map(params![target_id], map_row)?;
                for r in rows {
                    items.push(r?);
                }
            }
        }

        Ok(items)
    }

    // --- Settings ---

    pub fn get_setting(&self, key: &str) -> Result<Option<String>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT value FROM system_settings WHERE key = ?1")?;
        let val = stmt
            .query_row(params![key], |row| row.get(0))
            .optional()?;
        Ok(val)
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO system_settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    // --- Relationships & Follower Audits ---

    pub fn replace_relationships(
        &self,
        target_id: &str,
        relation_type: &str,
        users: &[RelationshipUser],
    ) -> Result<()> {
        let mut conn = self.conn.lock().unwrap();
        let tx = conn.transaction()?;
        tx.execute(
            "DELETE FROM target_relationships WHERE target_id = ?1 AND relation_type = ?2",
            params![target_id, relation_type],
        )?;
        let now = Utc::now().to_rfc3339();
        {
            let mut stmt = tx.prepare(
                "INSERT OR REPLACE INTO target_relationships (
                    target_id, relation_type, user_id, username, full_name, profile_pic_url, is_private, updated_at
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            )?;
            for u in users {
                stmt.execute(params![
                    target_id,
                    relation_type,
                    u.id,
                    u.username,
                    u.full_name,
                    u.profile_pic_url,
                    u.is_private,
                    now,
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn list_relationships(
        &self,
        target_id: &str,
        relation_type: &str,
    ) -> Result<Vec<RelationshipUser>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT user_id, username, full_name, profile_pic_url, is_private
             FROM target_relationships
             WHERE target_id = ?1 AND relation_type = ?2
             ORDER BY username COLLATE NOCASE ASC",
        )?;
        let rows = stmt.query_map(params![target_id, relation_type], |row| {
            Ok(RelationshipUser {
                id: row.get(0)?,
                username: row.get(1)?,
                full_name: row.get(2)?,
                profile_pic_url: row.get(3)?,
                is_private: row.get(4)?,
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn insert_follower_audit(
        &self,
        target_id: &str,
        event_type: &str,
        user: &RelationshipUser,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO follower_audits (target_id, event_type, username, full_name, profile_pic_url, timestamp)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                target_id,
                event_type,
                user.username,
                user.full_name,
                user.profile_pic_url,
                now,
            ],
        )?;
        Ok(())
    }

    pub fn list_follower_audits(&self, target_id: &str) -> Result<Vec<FollowerAudit>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, target_id, event_type, username, full_name, profile_pic_url, timestamp
             FROM follower_audits WHERE target_id = ?1 ORDER BY id DESC LIMIT 300",
        )?;
        let rows = stmt.query_map(params![target_id], |row| {
            Ok(FollowerAudit {
                id: row.get(0)?,
                target_id: row.get(1)?,
                event_type: row.get(2)?,
                username: row.get(3)?,
                full_name: row.get(4)?,
                profile_pic_url: row.get(5)?,
                timestamp: DateTime::parse_from_rfc3339(&row.get::<_, String>(6)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    // --- Follower History ---

    pub fn record_follower_history(
        &self,
        target_id: &str,
        followers_count: i64,
        following_count: i64,
        media_count: i64,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO follower_history (target_id, followers_count, following_count, media_count, recorded_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![target_id, followers_count, following_count, media_count, now],
        )?;
        Ok(())
    }

    pub fn list_follower_history(
        &self,
        target_id: &str,
        since_iso: Option<&str>,
    ) -> Result<Vec<FollowerHistoryPoint>> {
        let conn = self.conn.lock().unwrap();
        let map_row = |row: &rusqlite::Row| {
            Ok(FollowerHistoryPoint {
                id: row.get(0)?,
                target_id: row.get(1)?,
                followers_count: row.get(2)?,
                following_count: row.get(3)?,
                media_count: row.get(4)?,
                recorded_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(5)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        };
        let mut out = Vec::new();
        if let Some(since) = since_iso {
            let mut stmt = conn.prepare(
                "SELECT id, target_id, followers_count, following_count, media_count, recorded_at
                 FROM follower_history WHERE target_id = ?1 AND recorded_at >= ?2 ORDER BY id ASC LIMIT 500",
            )?;
            let rows = stmt.query_map(params![target_id, since], map_row)?;
            for r in rows {
                out.push(r?);
            }
        } else {
            let mut stmt = conn.prepare(
                "SELECT id, target_id, followers_count, following_count, media_count, recorded_at
                 FROM follower_history WHERE target_id = ?1 ORDER BY id ASC LIMIT 500",
            )?;
            let rows = stmt.query_map(params![target_id], map_row)?;
            for r in rows {
                out.push(r?);
            }
        }
        Ok(out)
    }

    // --- Carousel Media Assets ---

    pub fn upsert_media_assets(&self, assets: &[MediaAsset]) -> Result<()> {
        if assets.is_empty() {
            return Ok(());
        }
        let mut conn = self.conn.lock().unwrap();
        let tx = conn.transaction()?;
        {
            let mut stmt = tx.prepare(
                "INSERT INTO media_assets (id, media_id, target_id, position, media_type, cdn_url, relative_file_path, file_size_bytes)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT(id) DO UPDATE SET
                   cdn_url = excluded.cdn_url,
                   relative_file_path = CASE WHEN excluded.relative_file_path != '' THEN excluded.relative_file_path ELSE media_assets.relative_file_path END,
                   file_size_bytes = CASE WHEN excluded.file_size_bytes > 0 THEN excluded.file_size_bytes ELSE media_assets.file_size_bytes END",
            )?;
            for a in assets {
                stmt.execute(params![
                    a.id,
                    a.media_id,
                    a.target_id,
                    a.position,
                    a.media_type,
                    a.cdn_url,
                    a.relative_file_path,
                    a.file_size_bytes,
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn list_media_assets(&self, media_id: &str) -> Result<Vec<MediaAsset>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, media_id, target_id, position, media_type, cdn_url, relative_file_path, file_size_bytes
             FROM media_assets WHERE media_id = ?1 ORDER BY position ASC",
        )?;
        let rows = stmt.query_map(params![media_id], |row| {
            Ok(MediaAsset {
                id: row.get(0)?,
                media_id: row.get(1)?,
                target_id: row.get(2)?,
                position: row.get(3)?,
                media_type: row.get(4)?,
                cdn_url: row.get(5)?,
                relative_file_path: row.get(6)?,
                file_size_bytes: row.get(7)?,
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    // --- Post Comments ---

    pub fn upsert_post_comments(&self, comments: &[PostComment]) -> Result<()> {
        if comments.is_empty() {
            return Ok(());
        }
        let mut conn = self.conn.lock().unwrap();
        let tx = conn.transaction()?;
        {
            let mut stmt = tx.prepare(
                "INSERT INTO post_comments (
                    id, post_id, target_id, parent_comment_id, author_username, author_full_name,
                    is_verified, text, like_count, reply_count, created_at, collection_status
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
                 ON CONFLICT(id) DO UPDATE SET
                   text = excluded.text,
                   like_count = excluded.like_count,
                   reply_count = excluded.reply_count,
                   collection_status = excluded.collection_status",
            )?;
            for c in comments {
                stmt.execute(params![
                    c.id,
                    c.post_id,
                    c.target_id,
                    c.parent_comment_id,
                    c.author_username,
                    c.author_full_name,
                    c.is_verified,
                    c.text,
                    c.like_count,
                    c.reply_count,
                    c.created_at,
                    c.collection_status,
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn list_post_comments(&self, post_id: &str) -> Result<Vec<PostComment>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, post_id, target_id, parent_comment_id, author_username, author_full_name,
                    is_verified, text, like_count, reply_count, created_at, collection_status
             FROM post_comments WHERE post_id = ?1 ORDER BY like_count DESC, created_at DESC",
        )?;
        let rows = stmt.query_map(params![post_id], |row| {
            Ok(PostComment {
                id: row.get(0)?,
                post_id: row.get(1)?,
                target_id: row.get(2)?,
                parent_comment_id: row.get(3)?,
                author_username: row.get(4)?,
                author_full_name: row.get(5)?,
                is_verified: row.get(6)?,
                text: row.get(7)?,
                like_count: row.get(8)?,
                reply_count: row.get(9)?,
                created_at: row.get(10)?,
                collection_status: row.get(11)?,
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    // --- Provider Health ---

    pub fn record_provider_result(
        &self,
        provider_id: &str,
        success: bool,
        last_error: Option<&str>,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now();
        let now_iso = now.to_rfc3339();
        let existing: Option<(i64, i64, i64)> = conn
            .query_row(
                "SELECT consecutive_failures, success_count, failure_count FROM provider_health WHERE provider_id = ?1",
                params![provider_id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        let (prev_cf, prev_ok, prev_fail) = existing.unwrap_or((0, 0, 0));
        let (cf, ok_c, fail_c, status, cooldown_until, err_val) = if success {
            (0, prev_ok + 1, prev_fail, "healthy", 0i64, None)
        } else {
            let ncf = prev_cf + 1;
            let cd = if ncf >= 3 {
                now.timestamp() + 300
            } else {
                0
            };
            let st = if ncf >= 3 { "cooldown" } else { "degraded" };
            (ncf, prev_ok, prev_fail + 1, st, cd, last_error)
        };
        conn.execute(
            "INSERT INTO provider_health (provider_id, status, consecutive_failures, cooldown_until, success_count, failure_count, last_error, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
             ON CONFLICT(provider_id) DO UPDATE SET
               status = excluded.status,
               consecutive_failures = excluded.consecutive_failures,
               cooldown_until = excluded.cooldown_until,
               success_count = excluded.success_count,
               failure_count = excluded.failure_count,
               last_error = excluded.last_error,
               updated_at = excluded.updated_at",
            params![provider_id, status, cf, cooldown_until, ok_c, fail_c, err_val, now_iso],
        )?;
        Ok(())
    }

    pub fn list_provider_health(&self) -> Result<Vec<ProviderHealth>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT provider_id, status, consecutive_failures, cooldown_until, success_count, failure_count, last_error, updated_at
             FROM provider_health ORDER BY provider_id ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(ProviderHealth {
                provider_id: row.get(0)?,
                status: row.get(1)?,
                consecutive_failures: row.get(2)?,
                cooldown_until: row.get(3)?,
                success_count: row.get(4)?,
                failure_count: row.get(5)?,
                last_error: row.get(6)?,
                updated_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(7)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn reset_provider_health(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "UPDATE provider_health SET status = 'healthy', consecutive_failures = 0, cooldown_until = 0, last_error = NULL, updated_at = ?1",
            params![now],
        )?;
        Ok(())
    }

    // --- Notifications Inbox & Rules ---

    pub fn insert_notification(
        &self,
        target_id: &str,
        username: &str,
        event_type: &str,
        title: &str,
        body: &str,
    ) -> Result<i64> {
        let conn = self.conn.lock().unwrap();
        let now = Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO notifications (target_id, username, event_type, title, body, is_read, timestamp)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, ?6)",
            params![target_id, username, event_type, title, body, now],
        )?;
        Ok(conn.last_insert_rowid())
    }

    pub fn list_notifications(&self, limit: i64) -> Result<Vec<NotificationRecord>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, target_id, username, event_type, title, body, is_read, timestamp
             FROM notifications ORDER BY id DESC LIMIT ?1",
        )?;
        let rows = stmt.query_map(params![limit.clamp(1, 500)], |row| {
            Ok(NotificationRecord {
                id: row.get(0)?,
                target_id: row.get(1)?,
                username: row.get(2)?,
                event_type: row.get(3)?,
                title: row.get(4)?,
                body: row.get(5)?,
                is_read: row.get(6)?,
                timestamp: DateTime::parse_from_rfc3339(&row.get::<_, String>(7)?)
                    .map(|dt| dt.with_timezone(&Utc))
                    .unwrap_or_else(|_| Utc::now()),
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn mark_notifications_read(&self, id: Option<i64>) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        if let Some(nid) = id {
            conn.execute("UPDATE notifications SET is_read = 1 WHERE id = ?1", params![nid])?;
        } else {
            conn.execute("UPDATE notifications SET is_read = 1 WHERE is_read = 0", [])?;
        }
        Ok(())
    }

    pub fn delete_notification(&self, id: Option<i64>) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        if let Some(nid) = id {
            conn.execute("DELETE FROM notifications WHERE id = ?1", params![nid])?;
        } else {
            conn.execute("DELETE FROM notifications", [])?;
        }
        Ok(())
    }

    pub fn get_notification_rule(&self, scope_key: &str) -> Result<Option<NotificationRule>> {
        let conn = self.conn.lock().unwrap();
        let row = conn
            .query_row(
                "SELECT scope_key, enabled, privacy_change, bio_change, avatar_change, post_count_change,
                        post_vanished, story_added, followers_change, following_change, quiet_start, quiet_end
                 FROM notification_rules WHERE scope_key = ?1",
                params![scope_key],
                |r| {
                    Ok(NotificationRule {
                        scope_key: r.get(0)?,
                        enabled: r.get(1)?,
                        privacy_change: r.get(2)?,
                        bio_change: r.get(3)?,
                        avatar_change: r.get(4)?,
                        post_count_change: r.get(5)?,
                        post_vanished: r.get(6)?,
                        story_added: r.get(7)?,
                        followers_change: r.get(8)?,
                        following_change: r.get(9)?,
                        quiet_start: r.get(10)?,
                        quiet_end: r.get(11)?,
                    })
                },
            )
            .optional()?;
        Ok(row)
    }

    pub fn list_notification_rules(&self) -> Result<Vec<NotificationRule>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT scope_key, enabled, privacy_change, bio_change, avatar_change, post_count_change,
                    post_vanished, story_added, followers_change, following_change, quiet_start, quiet_end
             FROM notification_rules ORDER BY scope_key ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(NotificationRule {
                scope_key: r.get(0)?,
                enabled: r.get(1)?,
                privacy_change: r.get(2)?,
                bio_change: r.get(3)?,
                avatar_change: r.get(4)?,
                post_count_change: r.get(5)?,
                post_vanished: r.get(6)?,
                story_added: r.get(7)?,
                followers_change: r.get(8)?,
                following_change: r.get(9)?,
                quiet_start: r.get(10)?,
                quiet_end: r.get(11)?,
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn upsert_notification_rule(&self, rule: &NotificationRule) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO notification_rules (
                scope_key, enabled, privacy_change, bio_change, avatar_change, post_count_change,
                post_vanished, story_added, followers_change, following_change, quiet_start, quiet_end
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
             ON CONFLICT(scope_key) DO UPDATE SET
               enabled = excluded.enabled,
               privacy_change = excluded.privacy_change,
               bio_change = excluded.bio_change,
               avatar_change = excluded.avatar_change,
               post_count_change = excluded.post_count_change,
               post_vanished = excluded.post_vanished,
               story_added = excluded.story_added,
               followers_change = excluded.followers_change,
               following_change = excluded.following_change,
               quiet_start = excluded.quiet_start,
               quiet_end = excluded.quiet_end",
            params![
                rule.scope_key,
                rule.enabled,
                rule.privacy_change,
                rule.bio_change,
                rule.avatar_change,
                rule.post_count_change,
                rule.post_vanished,
                rule.story_added,
                rule.followers_change,
                rule.following_change,
                rule.quiet_start,
                rule.quiet_end,
            ],
        )?;
        Ok(())
    }

    pub fn delete_media_item(&self, id: &str) -> Result<Option<ArchivedMedia>> {
        let existing = self.get_media(id)?;
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM media_assets WHERE media_id = ?1", params![id])?;
        conn.execute("DELETE FROM archived_media WHERE id = ?1", params![id])?;
        Ok(existing)
    }

    pub fn delete_media_by_target(&self, target_id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM media_assets WHERE target_id = ?1", params![target_id])?;
        conn.execute("DELETE FROM archived_media WHERE target_id = ?1", params![target_id])?;
        Ok(())
    }
}
