use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use std::sync::{Arc, Mutex};

use crate::models::{ArchivedMedia, ChangeLog, Target};

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
                profile_pic_path TEXT,
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

            CREATE INDEX IF NOT EXISTS idx_change_logs_target ON change_logs(target_id, timestamp DESC);
            CREATE INDEX IF NOT EXISTS idx_archived_media_target ON archived_media(target_id, posted_at DESC);
            CREATE INDEX IF NOT EXISTS idx_archived_media_type ON archived_media(target_id, media_type);
            CREATE INDEX IF NOT EXISTS idx_archived_media_vanished ON archived_media(target_id, is_vanished);",
        )?;
        Ok(())
    }

    // --- Target CRUD ---

    pub fn create_target(&self, target: &Target) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO targets (
                id, username, full_name, biography, is_private, followed_by_viewer,
                media_count, profile_pic_path, status, turbo_mode,
                check_interval_seconds, last_checked_at, last_status_change_at, created_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
            params![
                target.id,
                target.username,
                target.full_name,
                target.biography,
                target.is_private,
                target.followed_by_viewer,
                target.media_count,
                target.profile_pic_path,
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
                    media_count, profile_pic_path, status, turbo_mode,
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
                    profile_pic_path: row.get(7)?,
                    status: row.get(8)?,
                    turbo_mode: row.get(9)?,
                    check_interval_seconds: row.get(10)?,
                    last_checked_at: row
                        .get::<_, Option<String>>(11)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    last_status_change_at: row
                        .get::<_, Option<String>>(12)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(13)?)
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
                    media_count, profile_pic_path, status, turbo_mode,
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
                profile_pic_path: row.get(7)?,
                status: row.get(8)?,
                turbo_mode: row.get(9)?,
                check_interval_seconds: row.get(10)?,
                last_checked_at: row
                    .get::<_, Option<String>>(11)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                last_status_change_at: row
                    .get::<_, Option<String>>(12)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(13)?)
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
                profile_pic_path = ?7,
                status = ?8,
                last_checked_at = ?9,
                last_status_change_at = ?10
             WHERE id = ?11",
            params![
                target.username,
                target.full_name,
                target.biography,
                target.is_private,
                target.followed_by_viewer,
                target.media_count,
                target.profile_pic_path,
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
            "UPDATE targets SET status = ?1 WHERE id = ?2",
            params![status, id],
        )?;
        Ok(())
    }

    pub fn delete_target(&self, id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM targets WHERE id = ?1", params![id])?;
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
                relative_file_path = excluded.relative_file_path,
                file_size_bytes = CASE WHEN excluded.file_size_bytes > 0 THEN excluded.file_size_bytes ELSE archived_media.file_size_bytes END,
                download_status = excluded.download_status,
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

    pub fn list_media_by_target(
        &self,
        target_id: &str,
        media_type: Option<&str>,
    ) -> Result<Vec<ArchivedMedia>> {
        let conn = self.conn.lock().unwrap();
        let base_select = "SELECT id, target_id, media_type, highlight_title, caption, cdn_url,
                                  relative_file_path, file_size_bytes, posted_at, download_status,
                                  downloaded_at, is_vanished, vanished_at
                           FROM archived_media WHERE target_id = ?1";

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
}
