use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tokio::process::Command;
use tracing::info;

use crate::scheduler::ProfileSnapshot;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlitzItem {
    pub id: String,
    pub shortcode: String,
    pub media_type: String,
    #[serde(default)]
    pub highlight_title: Option<String>,
    pub cdn_url: String,
    pub caption: String,
    pub posted_at: i64,
    pub target_id: Option<String>,
    pub relative_file_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlitzResult {
    pub status: String,
    pub profile: Option<ProfileSnapshot>,
    pub items: Vec<BlitzItem>,
    pub count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HydrateSingleResult {
    pub status: String,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub actual_path: Option<String>,
    #[serde(default)]
    pub file_size: i64,
    #[serde(default)]
    pub speed_bps: i64,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Clone)]
pub struct WorkerBridge {
    pub python_bin: String,
    pub script_path: PathBuf,
}

impl WorkerBridge {
    pub fn new(script_path: PathBuf) -> Self {
        Self {
            python_bin: "python3".to_string(),
            script_path,
        }
    }

    pub async fn check_profile(
        &self,
        target: &str,
        session_path: Option<&str>,
    ) -> Result<ProfileSnapshot> {
        let mut cmd = Command::new(&self.python_bin);
        cmd.arg(&self.script_path)
            .arg("check_profile")
            .arg("--target")
            .arg(target);

        if let Some(sess) = session_path {
            cmd.arg("--session").arg(sess);
        }

        let output = cmd.output().await?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);

        if !output.status.success() || stdout.trim().is_empty() {
            return Err(anyhow!(
                "Worker command failed (exit code {:?}): {}. Output: {}",
                output.status.code(),
                stderr.trim(),
                stdout.trim()
            ));
        }

        let parsed: serde_json::Value = serde_json::from_str(stdout.trim())
            .map_err(|e| anyhow!("Failed to parse check_profile JSON: {}. Output: {}", e, stdout))?;

        if parsed.get("status").and_then(|s| s.as_str()) != Some("ok") {
            return Err(anyhow!(
                "Worker error: {}",
                parsed.get("message").and_then(|m| m.as_str()).unwrap_or("unknown")
            ));
        }

        let profile_val = parsed
            .get("profile")
            .ok_or_else(|| anyhow!("Missing profile key in response"))?;

        let profile: ProfileSnapshot = serde_json::from_value(profile_val.clone())?;
        Ok(profile)
    }

    pub async fn archive_blitz(
        &self,
        target: &str,
        output_dir: &str,
        session_path: Option<&str>,
        limit: i64,
    ) -> Result<BlitzResult> {
        let mut cmd = Command::new(&self.python_bin);
        cmd.arg(&self.script_path)
            .arg("archive_blitz")
            .arg("--target")
            .arg(target)
            .arg("--output-dir")
            .arg(output_dir)
            .arg("--limit")
            .arg(limit.to_string());

        if let Some(sess) = session_path {
            cmd.arg("--session").arg(sess);
        }

        let output = cmd.output().await?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);

        if !output.status.success() || stdout.trim().is_empty() {
            return Err(anyhow!(
                "Worker archive_blitz failed (exit code {:?}): {}. Output: {}",
                output.status.code(),
                stderr.trim(),
                stdout.trim()
            ));
        }

        let res: BlitzResult = serde_json::from_str(stdout.trim())
            .map_err(|e| anyhow!("Failed to parse archive_blitz JSON: {}. Output: {}", e, stdout))?;

        Ok(res)
    }

    pub async fn hydrate_single(
        &self,
        target_id: &str,
        item_id: &str,
        shortcode: &str,
        media_type: &str,
        cdn_url: &str,
        rel_path: &str,
        output_dir: &str,
        caption: &str,
        cookie_file: Option<&str>,
        quality: &str,
        yt_dlp_enabled: bool,
    ) -> Result<HydrateSingleResult> {
        let mut cmd = Command::new(&self.python_bin);
        cmd.kill_on_drop(true)
            .arg(&self.script_path)
            .arg("hydrate_single")
            .arg("--target-id")
            .arg(target_id)
            .arg("--item-id")
            .arg(item_id)
            .arg("--shortcode")
            .arg(shortcode)
            .arg("--media-type")
            .arg(media_type)
            .arg("--cdn-url")
            .arg(cdn_url)
            .arg("--rel-path")
            .arg(rel_path)
            .arg("--output-dir")
            .arg(output_dir)
            .arg("--caption")
            .arg(caption)
            .arg("--quality")
            .arg(quality)
            .arg("--yt-dlp-enabled")
            .arg(if yt_dlp_enabled { "true" } else { "false" });

        if let Some(c) = cookie_file {
            cmd.arg("--cookies").arg(c);
        }

        let output = cmd.output().await?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        let last_line = stdout.lines().rev().find(|l| l.trim().starts_with('{')).unwrap_or("");

        if !output.status.success() || last_line.is_empty() {
            return Err(anyhow!(
                "Hydrate single failed (exit {:?}): {}",
                output.status.code(),
                stderr.trim()
            ));
        }

        let parsed: HydrateSingleResult = serde_json::from_str(last_line)
            .map_err(|e| anyhow!("Invalid hydrate_single JSON: {}", e))?;
        if parsed.status != "ok" {
            return Err(anyhow!(
                "{}",
                parsed.error.unwrap_or_else(|| "Download failed".to_string())
            ));
        }
        Ok(parsed)
    }

    pub async fn hydrate_assets(
        &self,
        target_id: &str,
        input_json_path: &str,
        output_dir: &str,
    ) -> Result<()> {
        let mut cmd = Command::new(&self.python_bin);
        cmd.arg(&self.script_path)
            .arg("hydrate_assets")
            .arg("--target-id")
            .arg(target_id)
            .arg("--input-json")
            .arg(input_json_path)
            .arg("--output-dir")
            .arg(output_dir);

        let output = cmd.output().await?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);

        if !output.status.success() {
            return Err(anyhow!(
                "Worker hydrate_assets failed (exit code {:?}): {}. Output: {}",
                output.status.code(),
                stderr.trim(),
                stdout.trim()
            ));
        }

        info!("Hydrate assets output: {}", stdout);
        Ok(())
    }
}
