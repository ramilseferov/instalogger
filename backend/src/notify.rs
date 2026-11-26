use anyhow::Result;
use chrono::{Local, Timelike};
use reqwest::Client;
use std::process::Command;
use tracing::{error, info, warn};

use crate::db::Database;
use crate::models::NotificationRule;

#[derive(Clone)]
pub struct Notifier {
    db: Database,
    http_client: Client,
}

fn is_in_quiet_hours(start: Option<&str>, end: Option<&str>) -> bool {
    let (Some(s), Some(e)) = (start, end) else {
        return false;
    };
    let parse_mins = |raw: &str| -> Option<u32> {
        let mut p = raw.trim().split(':');
        let h: u32 = p.next()?.parse().ok()?;
        let m: u32 = p.next()?.parse().ok()?;
        if h < 24 && m < 60 {
            Some(h * 60 + m)
        } else {
            None
        }
    };
    let (Some(sm), Some(em)) = (parse_mins(s), parse_mins(e)) else {
        return false;
    };
    let now = Local::now();
    let cur = now.hour() * 60 + now.minute();
    if sm <= em {
        cur >= sm && cur < em
    } else {
        cur >= sm || cur < em
    }
}

fn rule_allows_event(rule: &NotificationRule, event_type: &str) -> bool {
    if !rule.enabled {
        return false;
    }
    match event_type {
        "privacy_change" | "account_opened" | "account_closed" => rule.privacy_change,
        "bio_change" | "full_name_change" | "username_change" => rule.bio_change,
        "avatar_change" => rule.avatar_change,
        "post_count_change" => rule.post_count_change,
        "post_vanished" => rule.post_vanished,
        "story_added" => rule.story_added,
        "followers_change" | "follower_gained" | "follower_lost" | "follower_pool_flush" | "follower_spike" => {
            rule.followers_change
        }
        "following_change" | "following_gained" | "following_lost" | "following_pool_flush" => {
            rule.following_change
        }
        _ => true,
    }
}

impl Notifier {
    pub fn new(db: Database) -> Self {
        Self {
            db,
            http_client: Client::new(),
        }
    }

    pub async fn send_event(
        &self,
        target_id: &str,
        username: &str,
        event_type: &str,
        title: &str,
        content: &str,
        high_priority: bool,
    ) -> Result<()> {
        let global_rule = self
            .db
            .get_notification_rule("global")
            .ok()
            .flatten()
            .unwrap_or_default();
        let target_rule = if !target_id.is_empty() {
            self.db.get_notification_rule(target_id).ok().flatten()
        } else {
            None
        };
        let effective = target_rule.as_ref().unwrap_or(&global_rule);
        if !rule_allows_event(effective, event_type) {
            return Ok(());
        }

        // Persist into Notification Center inbox regardless of quiet hours
        let _ = self
            .db
            .insert_notification(target_id, username, event_type, title, content);

        let quiet = is_in_quiet_hours(
            effective.quiet_start.as_deref().or(global_rule.quiet_start.as_deref()),
            effective.quiet_end.as_deref().or(global_rule.quiet_end.as_deref()),
        );
        if quiet && !high_priority {
            info!("[NOTIFY-QUIET] {}: {}", title, content);
            return Ok(());
        }

        self.send(title, content, high_priority).await
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
