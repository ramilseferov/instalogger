export type ThemeMode = 'system' | 'light' | 'dark';

export interface Target {
  id: string;
  username: string;
  full_name: string;
  biography: string;
  is_private: boolean;
  followed_by_viewer: boolean;
  media_count: number;
  followers_count: number;
  following_count: number;
  latest_story_at: number;
  profile_pic_path: string | null;
  status: string;
  turbo_mode: boolean;
  check_interval_seconds: number;
  last_checked_at: string | null;
  last_status_change_at: string | null;
  created_at: string;
}

export interface ChangeLog {
  id: number;
  target_id: string;
  event_type: string;
  old_value: string;
  new_value: string;
  timestamp: string;
}

export interface ArchivedMedia {
  id: string;
  target_id: string;
  media_type: 'post' | 'reel' | 'story' | 'highlight';
  highlight_title: string | null;
  caption: string | null;
  cdn_url: string;
  relative_file_path: string;
  file_size_bytes: number;
  posted_at: string;
  download_status: 'pending' | 'downloading' | 'completed' | 'failed';
  downloaded_at: string | null;
  is_vanished: boolean;
  vanished_at: string | null;
}

export type PoolMode = 'OFF' | 'FIXED_COUNT' | 'PERCENTAGE' | 'AUTO_ADAPTIVE';
export type TargetPoolOverrideMode = 'GLOBAL' | PoolMode;
export type ArchiverSpeedPreset = 'ECO' | 'BALANCED' | 'FAST' | 'TURBO';
export type YtDlpQuality = 'BEST' | 'HD_1080' | 'HD_720' | 'DATA_SAVER';

export interface SystemSettings {
  enable_termux_api: boolean;
  telegram_bot_token: string;
  telegram_chat_id: string;
  notification_language: 'az' | 'en' | 'ru';
  has_session_cookie: boolean;
  pool_mode: PoolMode;
  pool_fixed_threshold: number;
  pool_percent_threshold: number;
  pool_digest_minutes: number;
  pool_cooldown_minutes: number;
  pool_anti_flicker: boolean;
  pool_spike_alert: boolean;
  pool_apply_following: boolean;
  pool_suppress_logs: boolean;
  list_completeness_guard: boolean;
  archiver_speed_preset: ArchiverSpeedPreset;
  download_workers: number;
  max_posts_sync_limit: number;
  yt_dlp_enabled: boolean;
  yt_dlp_quality: YtDlpQuality;
  auto_download_media: boolean;
  yt_dlp_available: boolean;
}

export interface DownloadItem {
  id: string;
  target_id: string;
  username: string;
  shortcode: string;
  media_type: 'post' | 'reel' | 'story' | 'highlight' | string;
  cdn_url: string;
  relative_file_path: string;
  caption: string;
  status: 'queued' | 'downloading' | 'paused' | 'completed' | 'failed' | 'cancelled';
  progress: number;
  file_size_bytes: number;
  speed_bps: number;
  provider: string | null;
  error: string | null;
  updated_at: string;
}

export interface DownloadSummary {
  active: number;
  queued: number;
  paused: number;
  completed: number;
  failed: number;
  total_speed_bps: number;
  paused_all: boolean;
  workers: number;
}

export interface DownloadsResponse {
  items: DownloadItem[];
  summary: DownloadSummary;
  archiving_targets: string[];
}

export interface TargetPoolConfig {
  override_mode: TargetPoolOverrideMode;
  custom_threshold: number;
  custom_percent: number;
  custom_digest_minutes: number;
  custom_cooldown_minutes: number;
  last_flushed_at?: string | null;
}

export interface DevLogEntry {
  id: number;
  timestamp: string;
  level: 'D' | 'I' | 'W' | 'E' | string;
  tag: string;
  message: string;
}

export interface RelationshipUser {
  id: string;
  username: string;
  full_name: string;
  profile_pic_url: string;
  is_private: boolean;
}

export interface FollowerAudit {
  id: number;
  target_id: string;
  event_type: 'follower_gained' | 'follower_lost' | 'following_gained' | 'following_lost' | string;
  username: string;
  full_name: string;
  profile_pic_url: string;
  timestamp: string;
}

export interface RelationshipsResponse {
  followers: RelationshipUser[];
  following: RelationshipUser[];
  audits: FollowerAudit[];
}

export interface FollowerHistoryPoint {
  id: number;
  target_id: string;
  followers_count: number;
  following_count: number;
  media_count: number;
  recorded_at: string;
}

export interface MediaAsset {
  id: string;
  media_id: string;
  target_id: string;
  position: number;
  media_type: 'image' | 'video' | string;
  cdn_url: string;
  relative_file_path: string;
  file_size_bytes: number;
}

export interface PostComment {
  id: string;
  post_id: string;
  target_id: string;
  parent_comment_id: string | null;
  author_username: string;
  author_full_name: string;
  is_verified: boolean;
  text: string;
  like_count: number;
  reply_count: number;
  created_at: string;
  collection_status: string;
}

export interface ProviderHealth {
  provider_id: string;
  status: 'healthy' | 'degraded' | 'cooldown' | string;
  consecutive_failures: number;
  cooldown_until: number;
  success_count: number;
  failure_count: number;
  last_error: string | null;
  updated_at: string;
}

export interface NotificationRecord {
  id: number;
  target_id: string;
  username: string;
  event_type: string;
  title: string;
  body: string;
  is_read: boolean;
  timestamp: string;
}

export interface NotificationRule {
  scope_key: string;
  enabled: boolean;
  privacy_change: boolean;
  bio_change: boolean;
  avatar_change: boolean;
  post_count_change: boolean;
  post_vanished: boolean;
  story_added: boolean;
  followers_change: boolean;
  following_change: boolean;
  quiet_start: string | null;
  quiet_end: string | null;
}

export interface TargetStorageBreakdown {
  target_id: string;
  username: string;
  total_bytes: number;
  posts_bytes: number;
  reels_bytes: number;
  stories_bytes: number;
  highlights_bytes: number;
  avatars_bytes: number;
  file_count: number;
}

export interface StorageOverview {
  total_bytes: number;
  db_bytes: number;
  logs_bytes: number;
  archives_bytes: number;
  orphan_bytes: number;
  orphan_dirs: number;
  targets: TargetStorageBreakdown[];
}

export interface AuthStatus {
  enabled: boolean;
  locked: boolean;
  timeout_minutes: number;
}

export interface UpdateCheckResult {
  current_version: string;
  current_version_code: number;
  latest_tag: string;
  latest_version: string;
  latest_version_code: number;
  update_available: boolean;
  channel: string;
  published_at: string;
  apk_name: string;
  apk_url: string;
  apk_size_bytes: number;
  sha256: string | null;
  release_url: string;
  changelog_az: string;
  changelog_en: string;
  changelog_ru: string;
  source: string;
  checked_at: string;
}

export interface BackupImportOptions {
  import_targets: boolean;
  import_settings: boolean;
  import_notification_rules: boolean;
  conflict_mode: 'merge' | 'skip';
  include_secrets: boolean;
}

export interface BackupImportSummary {
  targets_added: number;
  targets_updated: number;
  targets_skipped: number;
  pool_overrides_applied: number;
  settings_applied: number;
  rules_applied: number;
  warnings: string[];
}

export interface BackupImportResponse {
  status: string;
  summary: BackupImportSummary;
  appearance?: {
    theme_mode?: ThemeMode;
    language?: 'az' | 'en' | 'ru';
  } | null;
}

