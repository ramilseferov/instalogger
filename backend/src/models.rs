use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Target {
    pub id: String,
    pub username: String,
    pub full_name: String,
    pub biography: String,
    pub is_private: bool,
    pub followed_by_viewer: bool,
    pub media_count: i64,
    pub profile_pic_path: Option<String>,
    #[serde(default)]
    pub avatar_hash: Option<String>,
    #[serde(default)]
    pub profile_hash: Option<String>,
    pub status: String, // 'active', 'paused', 'error'
    pub turbo_mode: bool,
    pub check_interval_seconds: i64,
    pub last_checked_at: Option<DateTime<Utc>>,
    pub last_status_change_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangeLog {
    pub id: i64,
    pub target_id: String,
    pub event_type: String, // 'privacy_change', 'username_change', 'bio_change', 'avatar_change', 'post_count_change', 'post_vanished', 'post_added', 'post_modified'
    pub old_value: String,
    pub new_value: String,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArchivedMedia {
    pub id: String, // shortcode or media_id
    pub target_id: String,
    pub media_type: String, // 'post', 'reel', 'story', 'highlight'
    pub highlight_title: Option<String>,
    pub caption: Option<String>,
    pub cdn_url: String,
    pub relative_file_path: String,
    pub file_size_bytes: i64,
    pub posted_at: DateTime<Utc>,
    pub download_status: String, // 'pending', 'downloading', 'completed', 'failed'
    pub downloaded_at: Option<DateTime<Utc>>,
    pub is_vanished: bool,
    pub vanished_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SystemSetting {
    pub key: String,
    pub value: String,
}
