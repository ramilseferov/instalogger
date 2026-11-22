export interface Target {
  id: string;
  username: string;
  full_name: string;
  biography: string;
  is_private: boolean;
  followed_by_viewer: boolean;
  media_count: number;
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

export interface SystemSettings {
  enable_termux_api: boolean;
  telegram_bot_token: string;
  telegram_chat_id: string;
  has_session_cookie: boolean;
}
