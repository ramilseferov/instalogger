use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use instalogger_backend::api::create_router;
use instalogger_backend::db::Database;
use instalogger_backend::notify::Notifier;
use instalogger_backend::scheduler::Scheduler;
use instalogger_backend::worker::WorkerBridge;
use std::sync::Arc;
use tower::ServiceExt;

#[tokio::test]
async fn test_api_health_and_targets_list() {
    let db = Database::init(":memory:").expect("DB init failed");
    instalogger_backend::api::auth::initialize_pin(&db, "487519").unwrap();
    let notify = Arc::new(Notifier::new(db.clone()));
    let worker = Arc::new(WorkerBridge::new("engine/worker.py".into()));
    let (scheduler, _) = Scheduler::new(db.clone(), notify, worker);
    let app = create_router(db, Arc::new(scheduler));

    // Test health endpoint
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"], "ok");

    // Authentication must precede file serving, upgrades and API handlers.
    for path in [
        "/api/targets",
        "/api/ws",
        "/archives/test.png",
        "/api/settings",
    ] {
        let response = app
            .clone()
            .oneshot(Request::builder().uri(path).body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "{path}");
    }
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/auth/unlock")
                .header("content-type", "application/json")
                .body(Body::from(r#"{"pin":"487519"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let cookie = response.headers()["set-cookie"]
        .to_str()
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .to_owned();

    // Test targets list endpoint
    let targets_res = app
        .clone()
        .oneshot(
            Request::builder()
                .header("cookie", &cookie)
                .uri("/api/targets")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(targets_res.status(), StatusCode::OK);

    // Test developer logs redaction & endpoint
    instalogger_backend::api::dev_logs::push_dev_log(
        "E",
        "TestSuite",
        "HTTP 500 sessionid=SECRET_COOKIE_999&oh=SIGNED_123",
    );
    let logs_res = app
        .clone()
        .oneshot(
            Request::builder()
                .header("cookie", &cookie)
                .uri("/api/dev-logs")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(logs_res.status(), StatusCode::OK);
    let logs_bytes = logs_res.into_body().collect().await.unwrap().to_bytes();
    let logs_text = String::from_utf8_lossy(&logs_bytes);
    assert!(!logs_text.contains("SECRET_COOKIE_999"));
    assert!(!logs_text.contains("SIGNED_123"));
    assert!(logs_text.contains("[REDACTED]"));

    // Test global pool settings round-trip
    let settings_res = app
        .clone()
        .oneshot(
            Request::builder()
                .header("cookie", &cookie)
                .uri("/api/settings")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(settings_res.status(), StatusCode::OK);
    let settings_bytes = settings_res.into_body().collect().await.unwrap().to_bytes();
    let settings_json: serde_json::Value = serde_json::from_slice(&settings_bytes).unwrap();
    assert_eq!(settings_json["settings"]["pool_mode"], "OFF");
    assert_eq!(settings_json["settings"]["list_completeness_guard"], true);

    let rotation = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/auth/setup")
                .header("cookie", &cookie)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"current_pin":"487519","pin":"501682"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(rotation.status(), StatusCode::OK);
    let new_cookie = rotation.headers()["set-cookie"]
        .to_str()
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let expired = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/targets")
                .header("cookie", &cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(expired.status(), StatusCode::UNAUTHORIZED);
    let cookie = new_cookie;

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/auth/lock")
                .header("cookie", &cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/targets")
                .header("cookie", cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn uninitialized_router_requires_operator_bootstrap() {
    let db = Database::init(":memory:").unwrap();
    let notify = Arc::new(Notifier::new(db.clone()));
    let worker = Arc::new(WorkerBridge::new("engine/worker.py".into()));
    let (scheduler, _) = Scheduler::new(db.clone(), notify, worker);
    let app = create_router(db.clone(), Arc::new(scheduler));
    let response = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/auth/setup")
                .header("content-type", "application/json")
                .body(Body::from(r#"{"pin":"123456"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert!(db
        .get_setting("dashboard_pin_hash")
        .unwrap()
        .unwrap_or_default()
        .is_empty());
}

#[tokio::test]
async fn cross_origin_and_bruteforce_are_blocked() {
    let db = Database::init(":memory:").unwrap();
    instalogger_backend::api::auth::initialize_pin(&db, "487519").unwrap();
    let notify = Arc::new(Notifier::new(db.clone()));
    let worker = Arc::new(WorkerBridge::new("engine/worker.py".into()));
    let (scheduler, _) = Scheduler::new(db.clone(), notify, worker);
    let app = create_router(db, Arc::new(scheduler));

    // Cross-origin unlock and API calls must be rejected with 403
    let cross_unlock = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/auth/unlock")
                .header("host", "127.0.0.1:3000")
                .header("origin", "https://evil.example")
                .header("content-type", "application/json")
                .body(Body::from(r#"{"pin":"487519"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cross_unlock.status(), StatusCode::FORBIDDEN);

    let cross_api = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/targets")
                .header("host", "127.0.0.1:3000")
                .header("origin", "https://evil.example")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cross_api.status(), StatusCode::FORBIDDEN);

    // 5 failed PIN attempts must trigger 429 rate-limit lockout
    for _ in 0..5 {
        let bad = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/auth/unlock")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"pin":"000000"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bad.status(), StatusCode::UNAUTHORIZED);
    }

    let throttled = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/auth/unlock")
                .header("content-type", "application/json")
                .body(Body::from(r#"{"pin":"487519"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(throttled.status(), StatusCode::TOO_MANY_REQUESTS);
}
