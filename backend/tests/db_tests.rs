use instalogger_backend::db::Database;
use instalogger_backend::models::{ArchivedMedia, Target};

#[test]
fn test_db_init_and_target_lifecycle() {
    let db = Database::init(":memory:").expect("DB init failed");
    let target = Target {
        id: "12345678".to_string(),
        username: "testuser".to_string(),
        full_name: "Test User".to_string(),
        biography: "Hello World".to_string(),
        is_private: true,
        followed_by_viewer: false,
        media_count: 10,
        profile_pic_path: None,
        avatar_hash: None,
        profile_hash: None,
        status: "active".to_string(),
        turbo_mode: false,
        check_interval_seconds: 300,
        last_checked_at: None,
        last_status_change_at: None,
        created_at: chrono::Utc::now(),
    };
    db.create_target(&target).expect("create_target failed");
    let fetched = db.get_target("12345678").expect("get_target failed").unwrap();
    assert_eq!(fetched.username, "testuser");
    assert_eq!(fetched.is_private, true);

    // Test change log
    db.log_change("12345678", "privacy_change", "true", "false")
        .expect("log_change failed");
    let logs = db.get_change_logs("12345678").expect("get logs failed");
    assert_eq!(logs.len(), 1);
    assert_eq!(logs[0].event_type, "privacy_change");

    // Test media preservation & vanish flag
    let media = ArchivedMedia {
        id: "post_abc123".to_string(),
        target_id: "12345678".to_string(),
        media_type: "post".to_string(),
        highlight_title: None,
        caption: Some("First post".to_string()),
        cdn_url: "https://cdn.example.com/1.jpg".to_string(),
        relative_file_path: "archives/12345678/posts/1.jpg".to_string(),
        file_size_bytes: 1024,
        posted_at: chrono::Utc::now(),
        download_status: "completed".to_string(),
        downloaded_at: Some(chrono::Utc::now()),
        is_vanished: false,
        vanished_at: None,
    };
    db.upsert_media(&media).expect("upsert media failed");
    db.mark_media_vanished("post_abc123").expect("mark vanished failed");
    let fetched_media = db.get_media("post_abc123").expect("get media failed").unwrap();
    assert_eq!(fetched_media.is_vanished, true);
    assert!(fetched_media.vanished_at.is_some());
}
