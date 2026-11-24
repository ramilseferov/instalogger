from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Set

from .base import (
    DownloadOutcome,
    DownloadStatus,
    ProviderCapability,
    StagedAsset,
    sanitize_log,
    validate_media_file,
)


class GalleryDlProvider:
    """
    GPL-isolated CLI subprocess provider for gallery-dl.
    Excels at Instagram photo posts, multi-slide photo/mixed carousels, stories, and highlights.
    Gracefully reports unavailable if gallery-dl is not installed in the environment.
    """

    provider_id: str = "gallery-dl"
    capabilities: Set[ProviderCapability] = {
        ProviderCapability.PHOTO_POST,
        ProviderCapability.VIDEO_POST,
        ProviderCapability.REEL,
        ProviderCapability.CAROUSEL,
        ProviderCapability.STORY,
        ProviderCapability.HIGHLIGHT,
        ProviderCapability.METADATA,
    }

    def __init__(self, binary_path: Optional[str] = None, timeout_seconds: int = 120):
        self.binary_path = binary_path or shutil.which("gallery-dl")
        self.timeout_seconds = timeout_seconds

    def is_available(self) -> bool:
        return bool(self.binary_path and Path(self.binary_path).exists())

    def download_post(
        self,
        shortcode: str,
        media_type_hint: str,
        staging_dir: Path,
        cdn_url: str = "",
        cookie_file: Optional[Path] = None,
    ) -> DownloadOutcome:
        if not self.is_available():
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message="gallery-dl binary not installed",
            )

        staging_dir.mkdir(parents=True, exist_ok=True)
        url = f"https://www.instagram.com/p/{shortcode}/"

        cmd = [
            self.binary_path,
            "--directory", str(staging_dir),
            "--write-metadata",
            "--no-mtime",
        ]
        if cookie_file and cookie_file.exists():
            cmd.extend(["--cookies", str(cookie_file)])
        cmd.append(url)

        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=self.timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message=f"gallery-dl timed out after {self.timeout_seconds}s",
            )
        except Exception as e:
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message=sanitize_log(str(e)),
            )

        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").lower()
            safe = sanitize_log(proc.stderr or proc.stdout)[:260]
            if "429" in err or "too many requests" in err:
                return DownloadOutcome(
                    status=DownloadStatus.RATE_LIMITED,
                    provider_id=self.provider_id,
                    message=safe or "gallery-dl rate limited (429)",
                    retry_after_seconds=180,
                )
            if "login" in err or "cookie" in err or "auth" in err:
                return DownloadOutcome(
                    status=DownloadStatus.LOGIN_REQUIRED,
                    provider_id=self.provider_id,
                    message=safe or "gallery-dl login required",
                )
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message=safe or "gallery-dl failed",
            )

        assets: List[StagedAsset] = []
        meta: Dict = {}
        media_files = sorted(
            [p for p in staging_dir.rglob("*") if p.is_file() and not p.name.endswith(".json")],
            key=lambda p: p.name,
        )
        json_files = sorted(staging_dir.rglob("*.json"), key=lambda p: p.name)
        if json_files:
            try:
                meta = json.loads(json_files[0].read_text(encoding="utf-8"))
            except Exception:
                meta = {}

        for idx, mf in enumerate(media_files):
            validated = validate_media_file(mf)
            if not validated:
                continue
            mime, _ext, is_vid, size, sha = validated
            assets.append(
                StagedAsset(
                    position=idx,
                    media_type="video" if is_vid else "image",
                    file_path=mf,
                    mime_type=mime,
                    file_size=size,
                    sha256=sha,
                )
            )

        if not assets:
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message="gallery-dl returned no valid media files",
            )

        return DownloadOutcome(
            status=DownloadStatus.SUCCESS,
            provider_id=self.provider_id,
            assets=assets,
            metadata=meta,
        )
