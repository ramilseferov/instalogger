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
