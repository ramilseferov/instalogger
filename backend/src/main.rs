use anyhow::Result;
use instalogger_backend::api::create_router;
use instalogger_backend::db::Database;
use instalogger_backend::notify::Notifier;
use instalogger_backend::scheduler::Scheduler;
use instalogger_backend::worker::WorkerBridge;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tracing::info;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    info!("Starting InstaLogger Daemon...");

    // Create storage directories
    let _ = std::fs::create_dir_all("data");
    let _ = std::fs::create_dir_all("archives");
    let _ = std::fs::create_dir_all("sessions");
    let _ = std::fs::create_dir_all("logs");

    let db_path = "data/instalogger.db";
    let db = Database::init(db_path)?;
    info!("SQLite database initialized at {}", db_path);

    let notify = Arc::new(Notifier::new(db.clone()));
    let worker_script = PathBuf::from("engine/worker.py");
    let worker = Arc::new(WorkerBridge::new(worker_script));

    let (scheduler_raw, _) = Scheduler::new(db.clone(), notify, worker);
    let scheduler = Arc::new(scheduler_raw);

    scheduler.clone().start_loop();

    // Axum router
    let app = create_router(db, scheduler);

    let host_str = std::env::var("INSTALOGGER_HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
    let port: u16 = std::env::var("INSTALOGGER_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(3000);
    let addr: SocketAddr = format!("{}:{}", host_str, port)
        .parse()
        .unwrap_or_else(|_| SocketAddr::from(([127, 0, 0, 1], 3000)));
    info!("InstaLogger Web Dashboard & API listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
