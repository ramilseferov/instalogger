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
    ) -> Result<BlitzResult> {
        let mut cmd = Command::new(&self.python_bin);
        cmd.arg(&self.script_path)
            .arg("archive_blitz")
            .arg("--target")
            .arg(target)
            .arg("--output-dir")
            .arg(output_dir);

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
