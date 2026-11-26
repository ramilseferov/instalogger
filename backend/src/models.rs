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
    #[serde(default)]
    pub followers_count: i64,
    #[serde(default)]
    pub following_count: i64,
    #[serde(default)]
    pub latest_story_at: i64,
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RelationshipUser {
    #[serde(default)]
    pub id: String,
    pub username: String,
    #[serde(default)]
    pub full_name: String,
    #[serde(default)]
    pub profile_pic_url: String,
    #[serde(default)]
    pub is_private: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FollowerAudit {
    pub id: i64,
    pub target_id: String,
    pub event_type: String, // follower_gained | follower_lost | following_gained | following_lost
    pub username: String,
    pub full_name: String,
    pub profile_pic_url: String,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FollowerHistoryPoint {
    pub id: i64,
    pub target_id: String,
    pub followers_count: i64,
    pub following_count: i64,
    pub media_count: i64,
    pub recorded_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaAsset {
    pub id: String,
    pub media_id: String,
    pub target_id: String,
    pub position: i64,
    pub media_type: String, // image | video
    pub cdn_url: String,
    pub relative_file_path: String,
    pub file_size_bytes: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PostComment {
    pub id: String,
    pub post_id: String,
    pub target_id: String,
    pub parent_comment_id: Option<String>,
    pub author_username: String,
    pub author_full_name: String,
    pub is_verified: bool,
    pub text: String,
    pub like_count: i64,
    pub reply_count: i64,
    pub created_at: String,
    pub collection_status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderHealth {
    pub provider_id: String,
    pub status: String, // healthy | degraded | cooldown
    pub consecutive_failures: i64,
    pub cooldown_until: i64,
    pub success_count: i64,
    pub failure_count: i64,
    pub last_error: Option<String>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationRecord {
    pub id: i64,
    pub target_id: String,
    pub username: String,
    pub event_type: String,
    pub title: String,
    pub body: String,
    pub is_read: bool,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationRule {
    pub scope_key: String, // "global" or target_id
    pub enabled: bool,
    pub privacy_change: bool,
    pub bio_change: bool,
    pub avatar_change: bool,
    pub post_count_change: bool,
    pub post_vanished: bool,
    pub story_added: bool,
    pub followers_change: bool,
    pub following_change: bool,
    pub quiet_start: Option<String>, // "23:00"
    pub quiet_end: Option<String>,   // "07:00"
}

impl Default for NotificationRule {
    fn default() -> Self {
        Self {
            scope_key: "global".to_string(),
            enabled: true,
            privacy_change: true,
            bio_change: true,
            avatar_change: true,
            post_count_change: true,
            post_vanished: true,
            story_added: true,
            followers_change: true,
            following_change: true,
            quiet_start: None,
            quiet_end: None,
        }
    }
}
