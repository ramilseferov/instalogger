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

    // Test targets list endpoint
    let targets_res = app
        .oneshot(
            Request::builder()
                .uri("/api/targets")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(targets_res.status(), StatusCode::OK);
}
