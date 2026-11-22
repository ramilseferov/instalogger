use anyhow::Result;
use reqwest::Client;
use std::process::Command;
use tracing::{error, info, warn};

use crate::db::Database;

#[derive(Clone)]
pub struct Notifier {
    db: Database,
    http_client: Client,
}

impl Notifier {
    pub fn new(db: Database) -> Self {
        Self {
            db,
            http_client: Client::new(),
        }
    }

    pub async fn send(&self, title: &str, content: &str, high_priority: bool) -> Result<()> {
        info!("[NOTIFY] {}: {}", title, content);

        // Termux notification
        let priority_flag = if high_priority { "high" } else { "default" };
        let termux_api_enabled = self
            .db
            .get_setting("enable_termux_api")?
            .map(|v| v == "true" || v == "1")
            .unwrap_or(true);

        if termux_api_enabled {
            let res = Command::new("termux-notification")
                .arg("--title")
                .arg(title)
                .arg("--content")
                .arg(content)
                .arg("--priority")
                .arg(priority_flag)
                .output();

            if let Err(e) = res {
                warn!("termux-notification command not available: {}", e);
            }
        }

        // Telegram notification
        let token_opt = self.db.get_setting("telegram_bot_token")?;
        let chat_id_opt = self.db.get_setting("telegram_chat_id")?;

        if let (Some(token), Some(chat_id)) = (token_opt, chat_id_opt) {
            if !token.is_empty() && !chat_id.is_empty() {
                let text = format!("*{}*\n{}", title, content);
                let url = format!("https://api.telegram.org/bot{}/sendMessage", token);
                let payload = serde_json::json!({
                    "chat_id": chat_id,
                    "text": text,
                    "parse_mode": "Markdown"
                });

                if let Err(e) = self.http_client.post(&url).json(&payload).send().await {
                    error!("Failed to send Telegram notification: {}", e);
                }
            }
        }

        Ok(())
    }
}
