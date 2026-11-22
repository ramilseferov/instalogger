use chrono::Utc;
use instalogger_backend::models::Target;
use instalogger_backend::scheduler::{detect_changes, ProfileSnapshot};

#[test]
fn test_state_machine_transition_detection() {
    let old_target = Target {
        id: "12345678".to_string(),
        username: "user123".to_string(),
        full_name: "Old Name".to_string(),
        biography: "Old Bio".to_string(),
        is_private: true,
        followed_by_viewer: false,
        media_count: 10,
        profile_pic_path: Some("archives/12345678/profile/avatar_old.jpg".to_string()),
        avatar_hash: Some("hash_old_123".to_string()),
        profile_hash: Some("profile_old_hash".to_string()),
        status: "active".to_string(),
        turbo_mode: false,
        check_interval_seconds: 300,
        last_checked_at: None,
        last_status_change_at: None,
        created_at: Utc::now(),
    };

    let new_profile = ProfileSnapshot {
        id: "12345678".to_string(),
        username: "user123".to_string(),
        full_name: "New Name".to_string(),
        biography: "Updated Bio".to_string(),
        is_private: false,
        followed_by_viewer: false,
        media_count: 15,
        profile_pic_url: "https://example.com/pic2.jpg".to_string(),
        profile_pic_path: Some("archives/12345678/profile/avatar_new.jpg".to_string()),
        avatar_hash: Some("hash_new_456".to_string()),
        profile_hash: Some("profile_new_hash".to_string()),
    };

    let events = detect_changes(&old_target, &new_profile);
    assert!(events
        .iter()
        .any(|e| e.event_type == "privacy_change" && e.new_value == "false"));
    assert!(events
        .iter()
        .any(|e| e.event_type == "bio_change" && e.new_value == "Updated Bio"));
    assert!(events
        .iter()
        .any(|e| e.event_type == "full_name_change" && e.new_value == "New Name"));
    assert!(events
        .iter()
        .any(|e| e.event_type == "post_count_change" && e.new_value == "15"));
    assert!(events
        .iter()
        .any(|e| e.event_type == "avatar_change" && e.new_value == "archives/12345678/profile/avatar_new.jpg"));
}
