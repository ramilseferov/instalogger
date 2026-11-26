import React, { createContext, useContext, useState, useCallback, useEffect } from 'react';
import { ThemeMode } from './types';

export type Language = 'az' | 'en' | 'ru';

export interface Translations {
  app_title: string;
  app_subtitle: string;
  monitoring_active: string;
  reconnecting: string;
  targets_badge: string;
  search_targets: string;
  loading_targets: string;
  settings: string;
  dev_logs: string;
  add_target: string;
  add_target_title: string;
  username_label: string;
  username_placeholder: string;
  turbo_mode: string;
  turbo_active: string;
  turbo_desc: string;
  start_tracking: string;
  checking: string;
  private: string;
  public: string;
  posts: string;
  no_name: string;
  no_targets_title: string;
  no_targets_desc: string;
  back: string;
  audit_timeline: string;
  avatar_history: string;
  avatar_history_desc: string;
  avatar_current: string;
  avatar_empty: string;
  download_file: string;
  check_now: string;
  refresh_all: string;
  delete_target: string;
  confirm_delete_title: string;
  confirm_delete_desc: string;
  cancel: string;
  confirm: string;
  delete: string;
  save: string;
  saving: string;
  saved: string;
  clear: string;
  language: string;
  language_desc: string;
  appearance: string;
  appearance_desc: string;
  theme_light: string;
  theme_dark: string;
  theme_system: string;
  status_active: string;
  status_paused: string;
  followers: string;
  following: string;
  archived: string;
  instagram_posts: string;
  local_archive_files: string;
  archive_gallery: string;
  highlights: string;
  previous: string;
  next: string;
  story_badge: string;
  all: string;
  reels: string;
  vanished: string;
  vanished_badge: string;
  gallery_empty_title: string;
  gallery_empty_desc: string;
  timeline_empty_title: string;
  timeline_empty_desc: string;
  event_privacy_opened: string;
  event_privacy_opened_desc: string;
  event_privacy_closed: string;
  event_privacy_closed_desc: string;
  event_username_changed: string;
  event_fullname_changed: string;
  event_bio_changed: string;
  event_avatar_changed: string;
  event_posts_changed: string;
  event_followers_changed: string;
  event_following_changed: string;
  event_post_vanished: string;
  empty_value: string;
  deleted_value: string;
  settings_subtitle: string;
  section_appearance_lang: string;
  section_session: string;
  section_notifications: string;
  section_follower_batch: string;
  section_diagnostics: string;
  session_title: string;
  session_desc: string;
  session_connected: string;
  session_not_set: string;
  session_placeholder: string;
  session_save: string;
  session_clear: string;
  session_clear_confirm_title: string;
  session_clear_confirm_desc: string;
  notif_lang_title: string;
  notif_lang_desc: string;
  notif_termux_title: string;
  notif_termux_desc: string;
  notif_telegram_title: string;
  notif_telegram_token: string;
  notif_telegram_chat: string;
  batch_title: string;
  batch_desc: string;
  batch_mode_global: string;
  batch_mode_off: string;
  batch_mode_fixed: string;
  batch_mode_percent: string;
  batch_mode_auto: string;
  batch_digest: string;
  batch_cooldown: string;
  batch_off: string;
  batch_antiflicker_title: string;
  batch_antiflicker_desc: string;
  batch_spike_title: string;
  batch_spike_desc: string;
  batch_following_title: string;
  batch_following_desc: string;
  batch_suppress_title: string;
  batch_suppress_desc: string;
  batch_guard_title: string;
  batch_guard_desc: string;
  batch_override_title: string;
  batch_override_desc: string;
  batch_flush: string;
  dev_logs_desc: string;
  open_console: string;
  logs_live: string;
  logs_paused: string;
  logs_copy_all: string;
  logs_copied: string;
  logs_line_copied: string;
  logs_clear: string;
  logs_clear_confirm_title: string;
  logs_clear_confirm_desc: string;
  logs_search_placeholder: string;
  logs_redacted_notice: string;
  logs_empty: string;
  logs_level_all: string;
  logs_level_err: string;
  logs_level_warn: string;
  logs_level_info: string;
  logs_level_debug: string;
  logs_lines: string;
  toast_target_updated: string;
  toast_check_failed: string;
  toast_refresh_done: string;
  toast_refresh_partial: string;
  toast_session_needed: string;
  toast_pool_updated: string;
  toast_pool_flushed: string;
  profile_incomplete_notice: string;
  open_session_settings: string;
  files_count: string;
  loading: string;
  downloads: string;
  downloads_subtitle: string;
  downloads_empty_title: string;
  downloads_empty_desc: string;
  downloads_active: string;
  downloads_queued: string;
  downloads_completed: string;
  downloads_failed: string;
  downloads_paused: string;
  downloads_cancelled: string;
  downloads_pause_all: string;
  downloads_resume_all: string;
  downloads_clear_completed: string;
  downloads_workers: string;
  downloads_sync_media: string;
  downloads_syncing_posts: string;
  downloads_retry: string;
  downloads_pause: string;
  downloads_resume: string;
  section_downloads: string;
  dl_engine_title: string;
  dl_engine_desc: string;
  dl_engine_ready: string;
  dl_engine_fallback: string;
  dl_quality_title: string;
  dl_quality_best: string;
  dl_speed_title: string;
  dl_speed_desc: string;
  dl_speed_eco: string;
  dl_speed_balanced: string;
  dl_speed_fast: string;
  dl_speed_turbo: string;
  dl_workers_title: string;
  dl_workers_desc: string;
  dl_max_posts_title: string;
  dl_max_posts_unlimited: string;
  dl_auto_title: string;
  dl_auto_desc: string;
  stories: string;
  active_stories: string;
  story_highlight_session_notice: string;
  relationships_title: string;
  relationships_changes: string;
  relationships_search: string;
  relationships_empty: string;
  relationships_sync_now: string;
  relationships_session_required: string;
  audit_unfollowers: string;
  audit_unfollowed: string;
  audit_new_followers: string;
  audit_new_following: string;
  session_copy: string;
  session_export: string;
  session_copied: string;
  growth_chart_title: string;
  growth_chart_empty: string;
  growth_min: string;
  growth_max: string;
  growth_points: string;
  comments_title: string;
  comments_search: string;
  comments_sort_likes: string;
  comments_sort_newest: string;
  comments_empty: string;
  comments_fetch_now: string;
  notifications_center: string;
  notifications_unread: string;
  notifications_mark_all_read: string;
  notifications_empty: string;
  notif_tree_title: string;
  notif_tree_desc: string;
  notif_quiet_hours: string;
  storage_title: string;
  storage_subtitle: string;
  storage_total: string;
  storage_orphans: string;
  storage_clear_logs: string;
  storage_prune_orphans: string;
  storage_per_profile: string;
  provider_health_title: string;
  pin_lock_title: string;
  pin_lock_desc: string;
  pin_unlock: string;
  pin_incorrect: string;
  pin_set_btn: string;
  pin_disable_btn: string;
  check_interval_label: string;
  avatar_compare: string;
  avatar_before: string;
  avatar_after: string;
  export_csv: string;
  export_json: string;
  story_archive_title: string;
  story_status_active: string;
  story_status_archived: string;
  story_status_deleted: string;
  story_storage_used: string;
  delete_from_storage: string;
  confirm_delete_media_title: string;
  confirm_delete_media_desc: string;
  event_story_added: string;
  event_story_vanished: string;
  event_highlight_added: string;
  event_highlight_vanished: string;
  section_updates: string;
  update_title: string;
  update_check_now: string;
  update_up_to_date: string;
  update_available_badge: string;
  update_download_apk: string;
  update_view_github: string;
  update_current_label: string;
  update_latest_label: string;
}

const translations: Record<Language, Translations> = {
  az: {
    app_title: 'InstaLogger',
    app_subtitle: 'Instagram monitorinq və lokal arxiv paneli',
    monitoring_active: 'Monitorinq aktivdir',
    reconnecting: 'Yenidən qoşulur',
    targets_badge: 'Hədəf',
    search_targets: 'Hədəflərdə axtar',
    loading_targets: 'Hədəflər yüklənir',
    settings: 'Ayarlar',
    dev_logs: 'Sistem loqları',
    add_target: 'Hədəf əlavə et',
    add_target_title: 'Yeni profil əlavə et',
    username_label: 'Instagram istifadəçi adı',
    username_placeholder: 'istifadeci_adi',
    turbo_mode: 'Turbo rejim',
    turbo_active: 'Turbo aktiv',
    turbo_desc: 'Hər 30-60 saniyədən bir yoxla',
    start_tracking: 'İzləməyə başla',
    checking: 'Yoxlanılır',
    private: 'Gizli',
    public: 'Açıq',
    posts: 'paylaşım',
    no_name: 'Ad qeyd edilməyib',
    no_targets_title: 'İzlənilən hədəf yoxdur',
    no_targets_desc: 'Avtomatik izləmə və arxivləşdirmə üçün Instagram profili əlavə edin.',
    back: 'Geri',
    audit_timeline: 'Tarixçə',
    avatar_history: 'Avatar tarixçəsi',
    avatar_history_desc: 'Arxivlənmiş profil şəkilləri',
    avatar_current: 'Cari şəkil',
    avatar_empty: 'Arxivlənmiş profil şəkli tapılmadı',
    download_file: 'Endir',
    check_now: 'Yenilə',
    refresh_all: 'Hamısını yenilə',
    delete_target: 'Hədəfi sil',
    confirm_delete_title: 'Hədəfi sil',
    confirm_delete_desc: 'Bu hədəf və ona aid bütün arxiv qeydləri silinəcək.',
    cancel: 'Ləğv et',
    confirm: 'Təsdiq et',
    delete: 'Sil',
    save: 'Yadda saxla',
    saving: 'Saxlanılır',
    saved: 'Yadda saxlanıldı',
    clear: 'Sıfırla',
    language: 'İnterfeys dili',
    language_desc: 'Panelin bütün menyu və xəbərdarlıq mətnləri',
    appearance: 'Görünüş teması',
    appearance_desc: 'Açıq, tünd və ya sistem görünüşünü seçin',
    theme_light: 'Açıq',
    theme_dark: 'Tünd',
    theme_system: 'Sistem',
    status_active: 'Aktiv',
    status_paused: 'Dayandırılıb',
    followers: 'İzləyici',
    following: 'İzlənilən',
    archived: 'Arxiv',
    instagram_posts: 'Paylaşım',
    local_archive_files: 'Arxiv faylı',
    archive_gallery: 'Qalereya',
    highlights: 'Önə çıxarılanlar',
    previous: 'Əvvəlki',
    next: 'Növbəti',
    story_badge: 'Hekayə',
    all: 'Hamısı',
    reels: 'Reels',
    vanished: 'Silinənlər',
    vanished_badge: 'Silinib',
    gallery_empty_title: 'Bu bölmədə arxivlənmiş media yoxdur',
    gallery_empty_desc: 'Profil yeniləndikdə paylaşımlar və hekayələr burada görünəcək.',
    timeline_empty_title: 'Hələ dəyişiklik qeydə alınmayıb',
    timeline_empty_desc: 'Profil statusu, izləyici sayı və media dəyişiklikləri burada göstəriləcək.',
    event_privacy_opened: 'Gizlidən açığa keçdi',
    event_privacy_opened_desc: 'Hesab açıq oldu, arxivləşdirmə başladıldı',
    event_privacy_closed: 'Açıqdan gizliyə keçdi',
    event_privacy_closed_desc: 'Mövcud arxiv faylları qorunur',
    event_username_changed: 'İstifadəçi adı dəyişdi',
    event_fullname_changed: 'Profil adı dəyişdi',
    event_bio_changed: 'Bioqrafiya yeniləndi',
    event_avatar_changed: 'Profil şəkli yeniləndi',
    event_posts_changed: 'Paylaşım sayı dəyişdi',
    event_followers_changed: 'İzləyici sayı dəyişdi',
    event_following_changed: 'İzlənilən sayı dəyişdi',
    event_post_vanished: 'Paylaşım profildən silinib, lokal arxivdə qorunur',
    empty_value: 'yox idi',
    deleted_value: 'silinib',
    settings_subtitle: 'Tema, dil, sessiya, bildiriş və izləmə qaydaları',
    section_appearance_lang: 'Görünüş və dil',
    section_session: 'Instagram sessiyası',
    section_notifications: 'Bildirişlər',
    section_follower_batch: 'İzləyici dəyişiklik qruplaşdırması',
    section_diagnostics: 'Diaqnostika',
    session_title: 'Sessiya kukisi',
    session_desc: 'Giriş tələb edən profillər, paylaşımlar və hekayələr üçün istifadə olunur.',
    session_connected: 'Aktiv sessiya',
    session_not_set: 'Sessiya yoxdur',
    session_placeholder: 'sessionid=...; csrftoken=...; ds_user_id=...',
    session_save: 'Kukini yadda saxla',
    session_clear: 'Sessiyanı sıfırla',
    session_clear_confirm_title: 'Sessiyanı sıfırla',
    session_clear_confirm_desc: 'Saxlanılmış Instagram sessiya kukisi silinəcək.',
    notif_lang_title: 'Bildiriş dili',
    notif_lang_desc: 'Termux və Telegram bildirişlərinin göndərilmə dili',
    notif_termux_title: 'Termux bildirişləri',
    notif_termux_desc: 'Cihazda yerli sistem bildirişləri göndər',
    notif_telegram_title: 'Telegram bot bildirişləri',
    notif_telegram_token: 'Bot tokeni',
    notif_telegram_chat: 'Chat ID və ya @kanal',
    batch_title: 'Qruplaşdırma rejimi',
    batch_desc: 'Böyük hesablarda kiçik izləyici artım-azalışlarını birləşdirin.',
    batch_mode_global: 'Qlobal',
    batch_mode_off: 'Anlıq',
    batch_mode_fixed: 'Sabit say',
    batch_mode_percent: 'Faiz',
    batch_mode_auto: 'Avtomatik',
    batch_digest: 'Xülasə pəncərəsi',
    batch_cooldown: 'Bildiriş fasiləsi',
    batch_off: 'Bağlı',
    batch_antiflicker_title: 'Keş titrəmə filtri',
    batch_antiflicker_desc: 'Tək sorğulu ±1 və ya ±2 dəyişiklik titrəmələrini süz',
    batch_spike_title: 'Kəskin sıçrayış xəbərdarlığı',
    batch_spike_desc: 'Qəfil yüksək artım və ya azalma olduqda dərhal bildir',
    batch_following_title: 'İzlənilənlərə də tətbiq et',
    batch_following_desc: 'Eyni limit qaydalarını izlənilən sayına da tətbiq et',
    batch_suppress_title: 'Kiçik tarixçə qeydlərini birləşdir',
    batch_suppress_desc: 'Limit dolduqda vahid ümumiləşdirilmiş qeyd yaz',
    batch_guard_title: 'Natamam siyahı qoruması',
    batch_guard_desc: 'Yarımçıq API cavablarından yaranan yanlış bildirişləri blokla',
    batch_override_title: 'Profilə özəl qayda',
    batch_override_desc: 'Qlobal ayarı istifadə edin və ya bu profil üçün fərqli limit təyin edin.',
    batch_flush: 'Sayğacı sıfırla',
    dev_logs_desc: 'Maskalanmış sistem hadisələri, HTTP statusları və səhv diaqnostikası',
    open_console: 'Loqları aç',
    logs_live: 'Canlı',
    logs_paused: 'Fasilə',
    logs_copy_all: 'Hamısını kopyala',
    logs_copied: 'Kopyalandı',
    logs_line_copied: 'Sətir kopyalandı',
    logs_clear: 'Təmizlə',
    logs_clear_confirm_title: 'Loqları təmizlə',
    logs_clear_confirm_desc: 'Bütün daxili və disk loq qeydləri silinəcək.',
    logs_search_placeholder: 'Teq, status kodu və ya istifadəçi axtar',
    logs_redacted_notice: 'Sessiya kukiləri və imzalı media açarları avtomatik gizlədilir',
    logs_empty: 'Uyğun loq qeydi tapılmadı',
    logs_level_all: 'Hamısı',
    logs_level_err: 'Xəta',
    logs_level_warn: 'Xəbərdarlıq',
    logs_level_info: 'Məlumat',
    logs_level_debug: 'Sazlama',
    logs_lines: 'sətir',
    toast_target_updated: 'yeniləndi',
    toast_check_failed: 'yoxlanış alınmadı',
    toast_refresh_done: 'Bütün hədəflər yeniləndi',
    toast_refresh_partial: 'Yeniləmə bitdi',
    toast_session_needed: 'Instagram bu şəbəkədə sessiya kukisi tələb edir. Ayarlar bölməsindən əlavə edin.',
    toast_pool_updated: 'Qruplaşdırma qaydası yeniləndi',
    toast_pool_flushed: 'Sayğac sıfırlandı',
    profile_incomplete_notice: 'Profil məlumatları hələ alınmayıb. Instagram giriş tələb edirsə, Ayarlar bölməsindən sessiya kukisi əlavə edib yeniləyin.',
    open_session_settings: 'Sessiya ayarları',
    files_count: 'fayl',
    loading: 'Yüklənir',
    downloads: 'Endirilənlər',
    downloads_subtitle: 'Paralel media endirmə növbəsi və canlı sürət',
    downloads_empty_title: 'Endirmə növbəsi boşdur',
    downloads_empty_desc: 'Profillər sinxronlaşdırıldıqda postlar, reels və hekayələr burada paralel endiriləcək.',
    downloads_active: 'Aktiv',
    downloads_queued: 'Növbədə',
    downloads_completed: 'Tamamlandı',
    downloads_failed: 'Xəta',
    downloads_paused: 'Dayandırılıb',
    downloads_cancelled: 'Ləğv edilib',
    downloads_pause_all: 'Hamısını dayandır',
    downloads_resume_all: 'Hamısını davam et',
    downloads_clear_completed: 'Bitənləri təmizlə',
    downloads_workers: 'paralel axın',
    downloads_sync_media: 'Paylaşımları endir',
    downloads_syncing_posts: 'Paylaşımlar sinxronlaşdırılır və endirilir',
    downloads_retry: 'Təkrar cəhd',
    downloads_pause: 'Dayandır',
    downloads_resume: 'Davam et',
    section_downloads: 'Endirmə və media mühərriki',
    dl_engine_title: 'yt-dlp media mühərriki',
    dl_engine_desc: 'Reels və videoları tam keyfiyyətdə səs və görüntü ilə endir',
    dl_engine_ready: 'Hazır',
    dl_engine_fallback: 'HTTP ehtiyat',
    dl_quality_title: 'Video keyfiyyəti',
    dl_quality_best: 'Ən yaxşı',
    dl_speed_title: 'Arxivləşdirmə sürət profili',
    dl_speed_desc: 'Eyni anda paralel endirilən fayl sayını tənzimləyin',
    dl_speed_eco: 'Qənaət',
    dl_speed_balanced: 'Balanslı',
    dl_speed_fast: 'Sürətli',
    dl_speed_turbo: 'Turbo',
    dl_workers_title: 'Paralel endirmə sayı',
    dl_workers_desc: 'Eyni anda çalışan media endirmə axınlarının sayı',
    dl_max_posts_title: 'Profil başına post limiti',
    dl_max_posts_unlimited: 'Limitsiz',
    dl_auto_title: 'Avtomatik media endirmə',
    dl_auto_desc: 'Yeni paylaşım aşkarlandıqda avtomatik endirmə növbəsinə əlavə et',
    stories: 'Hekayələr',
    active_stories: 'Aktiv hekayələr',
    story_highlight_session_notice: 'Aktiv hekayələr, önə çıxarılanlar və 12-dən çox paylaşımın yüklənməsi üçün Instagram sessiya kukisi tələb edir.',
    relationships_title: 'İzləyicilər və izlənilənlər',
    relationships_changes: 'Dəyişikliklər',
    relationships_search: 'İstifadəçi adı və ya ad axtar',
    relationships_empty: 'Siyahı hələ endirilməyib',
    relationships_sync_now: 'Siyahını endir',
    relationships_session_required: 'İzləyici və izlənilən siyahılarını endirmək üçün aktiv sessiya kukisi tələb olunur.',
    audit_unfollowers: 'İzləmədən çıxanlar',
    audit_unfollowed: 'İzləmədən çıxarılanlar',
    audit_new_followers: 'Yeni izləyicilər',
    audit_new_following: 'Yeni izlənilənlər',
    session_copy: 'Kopyala',
    session_export: 'İxrac et',
    session_copied: 'Sessiya kopyalandı',
    growth_chart_title: 'İzləyici artım qrafiki',
    growth_chart_empty: 'Qrafik üçün ən azı 2 yoxlanış məlumatı toplanmalıdır.',
    growth_min: 'Minimum',
    growth_max: 'Maksimum',
    growth_points: 'Nöqtə',
    comments_title: 'Şərhlər',
    comments_search: 'Şərhlərdə axtar',
    comments_sort_likes: 'Ən çox bəyənilən',
    comments_sort_newest: 'Ən yeni',
    comments_empty: 'Arxivlənmiş şərh yoxdur',
    comments_fetch_now: 'Şərhləri yüklə',
    notifications_center: 'Bildiriş mərkəzi',
    notifications_unread: 'oxunmamış',
    notifications_mark_all_read: 'Hamısını oxundu et',
    notifications_empty: 'Hələ bildiriş yoxdur',
    notif_tree_title: 'Kateqoriya və profil bildiriş ağacı',
    notif_tree_desc: 'Qlobal və hər profil üçün ayrıca bildiriş növlərini idarə edin',
    notif_quiet_hours: 'Səssiz saatlar',
    storage_title: 'Yaddaş və məlumat idarəetməsi',
    storage_subtitle: 'Disk istifadəsi, keş təmizləmə, ZIP ixrac və provayder sağlamlığı',
    storage_total: 'Ümumi istifadə',
    storage_orphans: 'Yetim qovluqlar',
    storage_clear_logs: 'Loqları təmizlə',
    storage_prune_orphans: 'Yetimləri sil',
    storage_per_profile: 'Profil üzrə yaddaş bölgüsü',
    provider_health_title: 'Media provayder sağlamlığı',
    pin_lock_title: 'Panel PIN kilidi',
    pin_lock_desc: 'Davam etmək üçün PIN kodunuzu daxil edin',
    pin_unlock: 'Kilidi aç',
    pin_incorrect: 'Yanlış PIN kodu',
    pin_set_btn: 'PIN təyin et',
    pin_disable_btn: 'Kilidi söndür',
    check_interval_label: 'Yoxlama intervalı',
    avatar_compare: 'Müqayisə et',
    avatar_before: 'Əvvəl',
    avatar_after: 'Sonra',
    export_csv: 'CSV',
    export_json: 'JSON',
    story_archive_title: 'Hekayə arxivi',
    story_status_active: 'Aktiv',
    story_status_archived: 'Arxivlənib',
    story_status_deleted: 'Silinib',
    story_storage_used: 'Yaddaşda tutulan yer',
    delete_from_storage: 'Yaddaşdan sil',
    confirm_delete_media_title: 'Arxiv faylını sil',
    confirm_delete_media_desc: 'Bu media faylı lokal yaddaşdan və arxiv tarixçəsindən həmişəlik silinəcək.',
    event_story_added: 'Yeni hekayə paylaşdı',
    event_story_vanished: 'Hekayə silindi (arxiv qorunur)',
    event_highlight_added: 'Yeni highlight əlavə edildi',
    event_highlight_vanished: 'Highlight silindi (arxiv qorunur)',
    section_updates: 'Versiya və Yeniləmə',
    update_title: 'GitHub Reliz Yeniləməsi',
    update_check_now: 'İndi yoxla',
    update_up_to_date: 'Ən son versiyadır',
    update_available_badge: 'Yeni versiya',
    update_download_apk: 'APK Endir',
    update_view_github: 'GitHub Relizi',
    update_current_label: 'Cari',
    update_latest_label: 'Ən son',
  },
  en: {
    app_title: 'InstaLogger',
    app_subtitle: 'Instagram monitoring and local archive dashboard',
    monitoring_active: 'Monitoring active',
    reconnecting: 'Reconnecting',
    targets_badge: 'Targets',
    search_targets: 'Search targets',
    loading_targets: 'Loading targets',
    settings: 'Settings',
    dev_logs: 'Developer logs',
    add_target: 'Add target',
    add_target_title: 'Track new profile',
    username_label: 'Instagram username',
    username_placeholder: 'username',
    turbo_mode: 'Turbo mode',
    turbo_active: 'Turbo active',
    turbo_desc: 'Check every 30-60 seconds',
    start_tracking: 'Start tracking',
    checking: 'Checking',
    private: 'Private',
    public: 'Public',
    posts: 'posts',
    no_name: 'No display name',
    no_targets_title: 'No monitored targets',
    no_targets_desc: 'Add an Instagram profile to start automated tracking and local archiving.',
    back: 'Back',
    audit_timeline: 'Timeline',
    avatar_history: 'Avatar history',
    avatar_history_desc: 'Archived profile photos',
    avatar_current: 'Current photo',
    avatar_empty: 'No archived profile photos found',
    download_file: 'Download',
    check_now: 'Refresh',
    refresh_all: 'Refresh all',
    delete_target: 'Delete target',
    confirm_delete_title: 'Delete target',
    confirm_delete_desc: 'This target and all locally stored archive records will be removed.',
    cancel: 'Cancel',
    confirm: 'Confirm',
    delete: 'Delete',
    save: 'Save',
    saving: 'Saving',
    saved: 'Saved',
    clear: 'Reset',
    language: 'Interface language',
    language_desc: 'Controls all labels and messages across the dashboard',
    appearance: 'Appearance',
    appearance_desc: 'Choose light, dark, or match your system preference',
    theme_light: 'Light',
    theme_dark: 'Dark',
    theme_system: 'System',
    status_active: 'Active',
    status_paused: 'Paused',
    followers: 'Followers',
    following: 'Following',
    archived: 'Archived',
    instagram_posts: 'Posts',
    local_archive_files: 'Archive files',
    archive_gallery: 'Gallery',
    highlights: 'Highlights',
    previous: 'Previous',
    next: 'Next',
    story_badge: 'Story',
    all: 'All',
    reels: 'Reels',
    vanished: 'Vanished',
    vanished_badge: 'Vanished',
    gallery_empty_title: 'No archived media in this view',
    gallery_empty_desc: 'Posts, reels, and highlights will appear here once synced.',
    timeline_empty_title: 'No changes recorded yet',
    timeline_empty_desc: 'Privacy transitions, follower shifts, and media updates appear here chronologically.',
    event_privacy_opened: 'Switched to public',
    event_privacy_opened_desc: 'Account opened and archival started',
    event_privacy_closed: 'Switched to private',
    event_privacy_closed_desc: 'Existing archived media remains preserved',
    event_username_changed: 'Username changed',
    event_fullname_changed: 'Display name changed',
    event_bio_changed: 'Biography updated',
    event_avatar_changed: 'Profile photo updated',
    event_posts_changed: 'Post count changed',
    event_followers_changed: 'Followers changed',
    event_following_changed: 'Following changed',
    event_post_vanished: 'Post removed from profile, preserved locally',
    empty_value: 'empty',
    deleted_value: 'removed',
    settings_subtitle: 'Theme, language, session, alerts, and tracking policies',
    section_appearance_lang: 'Appearance and language',
    section_session: 'Instagram session',
    section_notifications: 'Notifications',
    section_follower_batch: 'Follower change batching',
    section_diagnostics: 'Diagnostics',
    session_title: 'Session cookie',
    session_desc: 'Required when Instagram gates profiles, posts, or stories behind login.',
    session_connected: 'Session active',
    session_not_set: 'No session',
    session_placeholder: 'sessionid=...; csrftoken=...; ds_user_id=...',
    session_save: 'Save cookie',
    session_clear: 'Reset session',
    session_clear_confirm_title: 'Reset session',
    session_clear_confirm_desc: 'The saved Instagram session cookie will be removed.',
    notif_lang_title: 'Alert language',
    notif_lang_desc: 'Language used for Termux and Telegram notifications',
    notif_termux_title: 'Termux notifications',
    notif_termux_desc: 'Dispatch local alerts through Termux API',
    notif_telegram_title: 'Telegram bot alerts',
    notif_telegram_token: 'Bot token',
    notif_telegram_chat: 'Chat ID or @channel',
    batch_title: 'Batching mode',
    batch_desc: 'Consolidate micro follower changes on high-velocity profiles.',
    batch_mode_global: 'Global',
    batch_mode_off: 'Instant',
    batch_mode_fixed: 'Fixed count',
    batch_mode_percent: 'Percentage',
    batch_mode_auto: 'Auto',
    batch_digest: 'Digest window',
    batch_cooldown: 'Alert cooldown',
    batch_off: 'Off',
    batch_antiflicker_title: 'Edge cache anti-flicker',
    batch_antiflicker_desc: 'Suppress single-poll +/-1 or +/-2 counter oscillations',
    batch_spike_title: 'Rapid spike alert',
    batch_spike_desc: 'Notify immediately on sudden surges or drops',
    batch_following_title: 'Apply to following',
    batch_following_desc: 'Use the same batching thresholds for following counts',
    batch_suppress_title: 'Consolidate minor logs',
    batch_suppress_desc: 'Write a single summary entry when batching flushes',
    batch_guard_title: 'List completeness guard',
    batch_guard_desc: 'Block false unfollow alerts from truncated API lists',
    batch_override_title: 'Per-profile policy',
    batch_override_desc: 'Inherit global rules or set a custom threshold for this profile.',
    batch_flush: 'Reset baseline',
    dev_logs_desc: 'Redacted runtime events, HTTP diagnostics, and worker errors',
    open_console: 'Open logs',
    logs_live: 'Live',
    logs_paused: 'Paused',
    logs_copy_all: 'Copy all',
    logs_copied: 'Copied',
    logs_line_copied: 'Line copied',
    logs_clear: 'Clear',
    logs_clear_confirm_title: 'Clear logs',
    logs_clear_confirm_desc: 'All in-memory and disk log entries will be cleared.',
    logs_search_placeholder: 'Filter by tag, status, or target',
    logs_redacted_notice: 'Session cookies and signed CDN tokens are redacted automatically',
    logs_empty: 'No matching log entries',
    logs_level_all: 'All',
    logs_level_err: 'Error',
    logs_level_warn: 'Warn',
    logs_level_info: 'Info',
    logs_level_debug: 'Debug',
    logs_lines: 'lines',
    toast_target_updated: 'updated',
    toast_check_failed: 'check failed',
    toast_refresh_done: 'All targets checked',
    toast_refresh_partial: 'Refresh complete',
    toast_session_needed: 'Instagram requires a session cookie on this network. Add it in Settings.',
    toast_pool_updated: 'Batch policy updated',
    toast_pool_flushed: 'Baseline reset',
    profile_incomplete_notice: 'Profile details could not be loaded yet. If Instagram requires login, add a session cookie in Settings and refresh.',
    open_session_settings: 'Session settings',
    files_count: 'files',
    loading: 'Loading',
    downloads: 'Downloads',
    downloads_subtitle: 'Parallel media download queue and live throughput',
    downloads_empty_title: 'Download queue is empty',
    downloads_empty_desc: 'Posts, reels, and highlights will appear here as they download in parallel.',
    downloads_active: 'Active',
    downloads_queued: 'Queued',
    downloads_completed: 'Completed',
    downloads_failed: 'Failed',
    downloads_paused: 'Paused',
    downloads_cancelled: 'Cancelled',
    downloads_pause_all: 'Pause all',
    downloads_resume_all: 'Resume all',
    downloads_clear_completed: 'Clear completed',
    downloads_workers: 'parallel workers',
    downloads_sync_media: 'Download posts',
    downloads_syncing_posts: 'Syncing and downloading posts',
    downloads_retry: 'Retry',
    downloads_pause: 'Pause',
    downloads_resume: 'Resume',
    section_downloads: 'Downloads and media engine',
    dl_engine_title: 'yt-dlp media engine',
    dl_engine_desc: 'Download reels and video posts with merged audio and thumbnails',
    dl_engine_ready: 'Ready',
    dl_engine_fallback: 'HTTP fallback',
    dl_quality_title: 'Video quality',
    dl_quality_best: 'Best',
    dl_speed_title: 'Archiver speed preset',
    dl_speed_desc: 'Controls parallel download throughput across targets',
    dl_speed_eco: 'Eco',
    dl_speed_balanced: 'Balanced',
    dl_speed_fast: 'Fast',
    dl_speed_turbo: 'Turbo',
    dl_workers_title: 'Parallel downloads',
    dl_workers_desc: 'Number of concurrent media download workers',
    dl_max_posts_title: 'Posts sync limit per profile',
    dl_max_posts_unlimited: 'Unlimited',
    dl_auto_title: 'Auto-download media',
    dl_auto_desc: 'Automatically enqueue discovered posts and highlights',
    stories: 'Stories',
    active_stories: 'Active stories',
    story_highlight_session_notice: 'Instagram requires an active session cookie to load active stories, highlights, and more than 12 posts.',
    relationships_title: 'Followers and following',
    relationships_changes: 'Changes',
    relationships_search: 'Search by username or name',
    relationships_empty: 'List has not been synced yet',
    relationships_sync_now: 'Download list',
    relationships_session_required: 'An active session cookie is required to fetch followers and following lists.',
    audit_unfollowers: 'Unfollowers',
    audit_unfollowed: 'Unfollowed',
    audit_new_followers: 'New followers',
    audit_new_following: 'New following',
    session_copy: 'Copy',
    session_export: 'Export',
    session_copied: 'Session copied',
    growth_chart_title: 'Follower growth chart',
    growth_chart_empty: 'At least 2 check snapshots are required to plot growth.',
    growth_min: 'Min',
    growth_max: 'Max',
    growth_points: 'Points',
    comments_title: 'Comments',
    comments_search: 'Search comments',
    comments_sort_likes: 'Most liked',
    comments_sort_newest: 'Newest',
    comments_empty: 'No archived comments yet',
    comments_fetch_now: 'Fetch comments',
    notifications_center: 'Notifications',
    notifications_unread: 'unread',
    notifications_mark_all_read: 'Mark all read',
    notifications_empty: 'No notifications yet',
    notif_tree_title: 'Category and per-profile notification tree',
    notif_tree_desc: 'Control global and per-profile alert categories individually',
    notif_quiet_hours: 'Quiet hours',
    storage_title: 'Storage and data manager',
    storage_subtitle: 'Disk usage, cleanup, ZIP archive export, and provider health',
    storage_total: 'Total usage',
    storage_orphans: 'Orphan directories',
    storage_clear_logs: 'Clear logs',
    storage_prune_orphans: 'Prune orphans',
    storage_per_profile: 'Storage breakdown by profile',
    provider_health_title: 'Media provider health',
    pin_lock_title: 'Dashboard PIN lock',
    pin_lock_desc: 'Enter your PIN to unlock the dashboard',
    pin_unlock: 'Unlock',
    pin_incorrect: 'Incorrect PIN',
    pin_set_btn: 'Set PIN',
    pin_disable_btn: 'Disable lock',
    check_interval_label: 'Check interval',
    avatar_compare: 'Compare',
    avatar_before: 'Before',
    avatar_after: 'After',
    export_csv: 'CSV',
    export_json: 'JSON',
    story_archive_title: 'Story archive',
    story_status_active: 'Active',
    story_status_archived: 'Archived',
    story_status_deleted: 'Deleted',
    story_storage_used: 'Storage used',
    delete_from_storage: 'Delete from storage',
    confirm_delete_media_title: 'Delete archived file',
    confirm_delete_media_desc: 'This media file will be permanently deleted from local storage and archive history.',
    event_story_added: 'Posted a new story',
    event_story_vanished: 'Story deleted (archive preserved)',
    event_highlight_added: 'Added highlight',
    event_highlight_vanished: 'Highlight removed (archive preserved)',
    section_updates: 'Version & Updates',
    update_title: 'GitHub Release Updates',
    update_check_now: 'Check now',
    update_up_to_date: 'Up to date',
    update_available_badge: 'Update available',
    update_download_apk: 'Download APK',
    update_view_github: 'GitHub Release',
    update_current_label: 'Current',
    update_latest_label: 'Latest',
  },
  ru: {
    app_title: 'InstaLogger',
    app_subtitle: 'Мониторинг Instagram и локальный архив',
    monitoring_active: 'Мониторинг активен',
    reconnecting: 'Переподключение',
    targets_badge: 'Целей',
    search_targets: 'Поиск целей',
    loading_targets: 'Загрузка целей',
    settings: 'Настройки',
    dev_logs: 'Системные логи',
    add_target: 'Добавить цель',
    add_target_title: 'Новый профиль',
    username_label: 'Имя пользователя Instagram',
    username_placeholder: 'username',
    turbo_mode: 'Турбо-режим',
    turbo_active: 'Турбо активен',
    turbo_desc: 'Проверка каждые 30-60 секунд',
    start_tracking: 'Начать отслеживание',
    checking: 'Проверка',
    private: 'Закрытый',
    public: 'Открытый',
    posts: 'публикаций',
    no_name: 'Имя не указано',
    no_targets_title: 'Нет отслеживаемых целей',
    no_targets_desc: 'Добавьте профиль Instagram для автоматического мониторинга и архивации.',
    back: 'Назад',
    audit_timeline: 'Хронология',
    avatar_history: 'История аватаров',
    avatar_history_desc: 'Сохраненные фото профиля',
    avatar_current: 'Текущее фото',
    avatar_empty: 'Сохраненные аватары не найдены',
    download_file: 'Скачать',
    check_now: 'Обновить',
    refresh_all: 'Обновить все',
    delete_target: 'Удалить цель',
    confirm_delete_title: 'Удалить цель',
    confirm_delete_desc: 'Цель и все локальные архивные записи будут удалены.',
    cancel: 'Отмена',
    confirm: 'Подтвердить',
    delete: 'Удалить',
    save: 'Сохранить',
    saving: 'Сохранение',
    saved: 'Сохранено',
    clear: 'Сбросить',
    language: 'Язык интерфейса',
    language_desc: 'Язык всех экранов и уведомлений панели',
    appearance: 'Оформление',
    appearance_desc: 'Светлая, темная или системная тема',
    theme_light: 'Светлая',
    theme_dark: 'Темная',
    theme_system: 'Система',
    status_active: 'Активен',
    status_paused: 'Приостановлен',
    followers: 'Подписчики',
    following: 'Подписки',
    archived: 'Архив',
    instagram_posts: 'Публикации',
    local_archive_files: 'Файлы архива',
    archive_gallery: 'Галерея',
    highlights: 'Актуальное',
    previous: 'Предыдущее',
    next: 'Следующее',
    story_badge: 'История',
    all: 'Все',
    reels: 'Reels',
    vanished: 'Удаленные',
    vanished_badge: 'Удалено',
    gallery_empty_title: 'В этом разделе нет архивных медиа',
    gallery_empty_desc: 'Публикации и истории появятся здесь после обновления профиля.',
    timeline_empty_title: 'Изменения еще не зафиксированы',
    timeline_empty_desc: 'Смена статуса, подписчиков и медиа отображается здесь.',
    event_privacy_opened: 'Стал открытым',
    event_privacy_opened_desc: 'Аккаунт открыт, запущена архивация',
    event_privacy_closed: 'Стал закрытым',
    event_privacy_closed_desc: 'Сохраненный архив остается доступным',
    event_username_changed: 'Изменено имя пользователя',
    event_fullname_changed: 'Изменено имя профиля',
    event_bio_changed: 'Обновлено описание',
    event_avatar_changed: 'Обновлено фото профиля',
    event_posts_changed: 'Изменилось число публикаций',
    event_followers_changed: 'Изменилось число подписчиков',
    event_following_changed: 'Изменилось число подписок',
    event_post_vanished: 'Публикация удалена из профиля, сохранена локально',
    empty_value: 'пусто',
    deleted_value: 'удалено',
    settings_subtitle: 'Тема, язык, сессия, уведомления и правила отслеживания',
    section_appearance_lang: 'Оформление и язык',
    section_session: 'Сессия Instagram',
    section_notifications: 'Уведомления',
    section_follower_batch: 'Группировка изменений подписчиков',
    section_diagnostics: 'Диагностика',
    session_title: 'Cookie сессии',
    session_desc: 'Используется при требовании входа для профилей, публикаций и историй.',
    session_connected: 'Сессия активна',
    session_not_set: 'Нет сессии',
    session_placeholder: 'sessionid=...; csrftoken=...; ds_user_id=...',
    session_save: 'Сохранить cookie',
    session_clear: 'Сбросить сессию',
    session_clear_confirm_title: 'Сбросить сессию',
    session_clear_confirm_desc: 'Сохраненные cookie сессии Instagram будут удалены.',
    notif_lang_title: 'Язык уведомлений',
    notif_lang_desc: 'Язык оповещений для Termux и Telegram',
    notif_termux_title: 'Уведомления Termux',
    notif_termux_desc: 'Отправлять локальные оповещения через Termux API',
    notif_telegram_title: 'Уведомления Telegram',
    notif_telegram_token: 'Токен бота',
    notif_telegram_chat: 'Chat ID или @канал',
    batch_title: 'Режим группировки',
    batch_desc: 'Объединяйте мелкие колебания подписчиков для крупных профилей.',
    batch_mode_global: 'Глобально',
    batch_mode_off: 'Мгновенно',
    batch_mode_fixed: 'Фикс. число',
    batch_mode_percent: 'Процент',
    batch_mode_auto: 'Авто',
    batch_digest: 'Окно сводки',
    batch_cooldown: 'Пауза оповещений',
    batch_off: 'Выкл',
    batch_antiflicker_title: 'Фильтр колебаний кеша',
    batch_antiflicker_desc: 'Игнорировать разовые скачки ±1 или ±2',
    batch_spike_title: 'Оповещение о резком скачке',
    batch_spike_desc: 'Сразу сообщать о внезапном росте или падении',
    batch_following_title: 'Применять к подпискам',
    batch_following_desc: 'Использовать те же пороги для числа подписок',
    batch_suppress_title: 'Объединять мелкие записи',
    batch_suppress_desc: 'Писать одну сводную запись при сбросе счетчика',
    batch_guard_title: 'Защита от неполных списков',
    batch_guard_desc: 'Блокировать ложные отписки при неполных ответах API',
    batch_override_title: 'Правило для профиля',
    batch_override_desc: 'Наследовать глобальные настройки или задать свой порог.',
    batch_flush: 'Сбросить базу',
    dev_logs_desc: 'События системы, статусы HTTP и ошибки без утечки токенов',
    open_console: 'Открыть логи',
    logs_live: 'Онлайн',
    logs_paused: 'Пауза',
    logs_copy_all: 'Копировать все',
    logs_copied: 'Скопировано',
    logs_line_copied: 'Строка скопирована',
    logs_clear: 'Очистить',
    logs_clear_confirm_title: 'Очистить логи',
    logs_clear_confirm_desc: 'Все записи в памяти и файле журнала будут удалены.',
    logs_search_placeholder: 'Поиск по тегу, статусу или цели',
    logs_redacted_notice: 'Cookie сессии и подписанные CDN-токены скрываются автоматически',
    logs_empty: 'Подходящие записи не найдены',
    logs_level_all: 'Все',
    logs_level_err: 'Ошибки',
    logs_level_warn: 'Предупр.',
    logs_level_info: 'Инфо',
    logs_level_debug: 'Отладка',
    logs_lines: 'строк',
    toast_target_updated: 'обновлен',
    toast_check_failed: 'ошибка проверки',
    toast_refresh_done: 'Все цели проверены',
    toast_refresh_partial: 'Обновление завершено',
    toast_session_needed: 'Instagram требует cookie сессии в этой сети. Добавьте его в настройках.',
    toast_pool_updated: 'Правило группировки обновлено',
    toast_pool_flushed: 'Счетчик сброшен',
    profile_incomplete_notice: 'Данные профиля еще не получены. Если Instagram требует вход, добавьте cookie сессии в настройках и обновите.',
    open_session_settings: 'Настройки сессии',
    files_count: 'файлов',
    loading: 'Загрузка',
    downloads: 'Загрузки',
    downloads_subtitle: 'Параллельная очередь медиа и скорость загрузки',
    downloads_empty_title: 'Очередь загрузок пуста',
    downloads_empty_desc: 'Публикации, reels и истории появятся здесь при синхронизации профилей.',
    downloads_active: 'Активные',
    downloads_queued: 'В очереди',
    downloads_completed: 'Готово',
    downloads_failed: 'Ошибки',
    downloads_paused: 'Пауза',
    downloads_cancelled: 'Отменено',
    downloads_pause_all: 'Пауза для всех',
    downloads_resume_all: 'Продолжить все',
    downloads_clear_completed: 'Очистить готовые',
    downloads_workers: 'потоков',
    downloads_sync_media: 'Скачать публикации',
    downloads_syncing_posts: 'Синхронизация и загрузка публикаций',
    downloads_retry: 'Повторить',
    downloads_pause: 'Пауза',
    downloads_resume: 'Продолжить',
    section_downloads: 'Загрузки и медиа-движок',
    dl_engine_title: 'Медиа-движок yt-dlp',
    dl_engine_desc: 'Загрузка reels и видео в полном качестве со звуком и превью',
    dl_engine_ready: 'Готов',
    dl_engine_fallback: 'HTTP резерв',
    dl_quality_title: 'Качество видео',
    dl_quality_best: 'Максимум',
    dl_speed_title: 'Профиль скорости архивации',
    dl_speed_desc: 'Управляет количеством параллельных загрузок',
    dl_speed_eco: 'Эко',
    dl_speed_balanced: 'Баланс',
    dl_speed_fast: 'Быстрый',
    dl_speed_turbo: 'Турбо',
    dl_workers_title: 'Параллельные загрузки',
    dl_workers_desc: 'Количество одновременно работающих потоков',
    dl_max_posts_title: 'Лимит постов на профиль',
    dl_max_posts_unlimited: 'Без лимита',
    dl_auto_title: 'Автозагрузка медиа',
    dl_auto_desc: 'Автоматически добавлять найденные посты в очередь загрузок',
    stories: 'Истории',
    active_stories: 'Активные истории',
    story_highlight_session_notice: 'Instagram требует cookie сессии для загрузки активных историй, актуального и более 12 публикаций.',
    relationships_title: 'Подписчики и подписки',
    relationships_changes: 'Изменения',
    relationships_search: 'Поиск по логину или имени',
    relationships_empty: 'Список еще не загружен',
    relationships_sync_now: 'Загрузить список',
    relationships_session_required: 'Для загрузки списков подписчиков и подписок требуется активный cookie сессии.',
    audit_unfollowers: 'Отписались',
    audit_unfollowed: 'Отписки профиля',
    audit_new_followers: 'Новые подписчики',
    audit_new_following: 'Новые подписки',
    session_copy: 'Копировать',
    session_export: 'Экспорт',
    session_copied: 'Сессия скопирована',
    growth_chart_title: 'График роста подписчиков',
    growth_chart_empty: 'Для графика нужно минимум 2 проверки профиля.',
    growth_min: 'Мин',
    growth_max: 'Макс',
    growth_points: 'Точек',
    comments_title: 'Комментарии',
    comments_search: 'Поиск комментариев',
    comments_sort_likes: 'По лайкам',
    comments_sort_newest: 'Новые',
    comments_empty: 'Комментарии еще не загружены',
    comments_fetch_now: 'Загрузить комментарии',
    notifications_center: 'Центр уведомлений',
    notifications_unread: 'непрочитанных',
    notifications_mark_all_read: 'Прочитать все',
    notifications_empty: 'Уведомлений пока нет',
    notif_tree_title: 'Дерево уведомлений по категориям и профилям',
    notif_tree_desc: 'Настройте типы оповещений глобально и для каждого профиля',
    notif_quiet_hours: 'Тихие часы',
    storage_title: 'Управление хранилищем и данными',
    storage_subtitle: 'Использование диска, очистка, ZIP-экспорт и статус движков',
    storage_total: 'Всего занято',
    storage_orphans: 'Осиротевшие папки',
    storage_clear_logs: 'Очистить логи',
    storage_prune_orphans: 'Удалить лишние',
    storage_per_profile: 'Хранилище по профилям',
    provider_health_title: 'Состояние медиа-провайдеров',
    pin_lock_title: 'PIN-блокировка панели',
    pin_lock_desc: 'Введите PIN-код для доступа к панели',
    pin_unlock: 'Разблокировать',
    pin_incorrect: 'Неверный PIN-код',
    pin_set_btn: 'Установить PIN',
    pin_disable_btn: 'Отключить',
    check_interval_label: 'Интервал проверки',
    avatar_compare: 'Сравнить',
    avatar_before: 'До',
    avatar_after: 'После',
    export_csv: 'CSV',
    export_json: 'JSON',
    story_archive_title: 'Архив историй',
    story_status_active: 'Активна',
    story_status_archived: 'В архиве',
    story_status_deleted: 'Удалена',
    story_storage_used: 'Занято на диске',
    delete_from_storage: 'Удалить с диска',
    confirm_delete_media_title: 'Удалить файл архива',
    confirm_delete_media_desc: 'Этот медиафайл будет навсегда удален из локального хранилища и истории.',
    event_story_added: 'Опубликована новая история',
    event_story_vanished: 'История удалена (архив сохранен)',
    event_highlight_added: 'Добавлено актуальное',
    event_highlight_vanished: 'Актуальное удалено (архив сохранен)',
    section_updates: 'Версия и обновления',
    update_title: 'Обновления GitHub',
    update_check_now: 'Проверить сейчас',
    update_up_to_date: 'Актуальная версия',
    update_available_badge: 'Доступно обновление',
    update_download_apk: 'Скачать APK',
    update_view_github: 'Релиз GitHub',
    update_current_label: 'Текущая',
    update_latest_label: 'Последняя',
  },
};

interface I18nContextType {
  language: Language;
  setLanguage: (lang: Language) => void;
  themeMode: ThemeMode;
  setThemeMode: (mode: ThemeMode) => void;
  resolvedDark: boolean;
  t: Translations;
}

const I18nContext = createContext<I18nContextType>({
  language: 'az',
  setLanguage: () => {},
  themeMode: 'system',
  setThemeMode: () => {},
  resolvedDark: false,
  t: translations.az,
});

export const I18nProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [language, setLanguageState] = useState<Language>(() => {
    try {
      const saved = localStorage.getItem('instalogger_lang') as Language;
      return saved && translations[saved] ? saved : 'az';
    } catch {
      return 'az';
    }
  });

  const [themeMode, setThemeModeState] = useState<ThemeMode>(() => {
    try {
      const saved = localStorage.getItem('instalogger_theme_mode') as ThemeMode;
      if (saved === 'light' || saved === 'dark' || saved === 'system') return saved;
      const legacy = localStorage.getItem('instalogger_theme');
      if (legacy === 'dark') return 'dark';
      if (legacy === 'light') return 'light';
      return 'system';
    } catch {
      return 'system';
    }
  });

  const [systemDark, setSystemDark] = useState<boolean>(() => {
    try {
      return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
    } catch {
      return false;
    }
  });

  const [veilActive, setVeilActive] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!mq) return;
    const handler = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener?.('change', handler);
    return () => mq.removeEventListener?.('change', handler);
  }, []);

  const resolvedDark = themeMode === 'dark' || (themeMode === 'system' && systemDark);

  useEffect(() => {
    const root = document.documentElement;
    if (resolvedDark) {
      root.classList.add('dark');
      root.style.colorScheme = 'dark';
    } else {
      root.classList.remove('dark');
      root.style.colorScheme = 'light';
    }
  }, [resolvedDark]);

  const runWithBlurTransition = useCallback((applyChange: () => void) => {
    const prefersReduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (prefersReduced) {
      applyChange();
      return;
    }
    setVeilActive(true);
    window.setTimeout(() => {
      applyChange();
      window.setTimeout(() => setVeilActive(false), 120);
    }, 110);
  }, []);

  const setLanguage = useCallback(
    (lang: Language) => {
      if (lang === language) return;
      runWithBlurTransition(() => {
        try {
          localStorage.setItem('instalogger_lang', lang);
        } catch {}
        setLanguageState(lang);
      });
    },
    [language, runWithBlurTransition]
  );

  const setThemeMode = useCallback(
    (mode: ThemeMode) => {
      if (mode === themeMode) return;
      runWithBlurTransition(() => {
        try {
          localStorage.setItem('instalogger_theme_mode', mode);
        } catch {}
        setThemeModeState(mode);
      });
    },
    [themeMode, runWithBlurTransition]
  );

  return (
    <I18nContext.Provider
      value={{ language, setLanguage, themeMode, setThemeMode, resolvedDark, t: translations[language] }}
    >
      {children}
      <div aria-hidden="true" className={`transition-veil ${veilActive ? 'is-active' : ''}`} />
    </I18nContext.Provider>
  );
};

export const useI18n = () => useContext(I18nContext);
