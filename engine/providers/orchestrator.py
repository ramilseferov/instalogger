from __future__ import annotations

import json
import shutil
import tempfile
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from .base import (
    DownloadOutcome,
    DownloadStatus,
    InstagramProvider,
    StagedAsset,
)
from .gallerydl_provider import GalleryDlProvider
from .instaloader_provider import InstaloaderHttpProvider
from .ytdlp_provider import YtDlpProvider


class PythonProviderOrchestrator:
    """
    Pluggable Multi-Provider Media Orchestrator for the Python engine.
    - Capability-based routing (yt-dlp for Reels/Videos, gallery-dl for Photos/Carousels, HTTP fallback)
    - Per-provider circuit breaker cooldown on HTTP 429 / repeated failures
    - SHA-256 content-addressed deduplication across target archives
    """

    def __init__(
        self,
        ytdlp: Optional[InstagramProvider] = None,
        gallerydl: Optional[InstagramProvider] = None,
        http_provider: Optional[InstagramProvider] = None,
    ):
        self.ytdlp = ytdlp or YtDlpProvider()
        self.gallerydl = gallerydl or GalleryDlProvider()
        self.http_provider = http_provider or InstaloaderHttpProvider()
        self._cooldowns: Dict[str, float] = {}
        self._failures: Dict[str, int] = {}

    def select_chain(self, media_type_hint: str) -> List[InstagramProvider]:
        hint = (media_type_hint or "image").lower()
        if hint in ("video", "reel"):
            return [self.ytdlp, self.gallerydl, self.http_provider]
        if hint == "carousel":
            return [self.gallerydl, self.ytdlp, self.http_provider]
        return [self.gallerydl, self.http_provider]

    def can_use(self, provider: InstagramProvider, now: Optional[float] = None) -> bool:
        if not provider.is_available():
            return False
        ts = now if now is not None else time.time()
        return ts >= self._cooldowns.get(provider.provider_id, 0.0)

    def _record_outcome(self, outcome: DownloadOutcome) -> None:
        pid = outcome.provider_id
        now = time.time()
        if outcome.status == DownloadStatus.SUCCESS:
            self._failures[pid] = 0
            self._cooldowns[pid] = 0.0
        elif outcome.status == DownloadStatus.RATE_LIMITED:
            self._failures[pid] = self._failures.get(pid, 0) + 1
            self._cooldowns[pid] = now + min(outcome.retry_after_seconds * self._failures[pid], 900)
        elif outcome.status == DownloadStatus.ERROR:
            self._failures[pid] = self._failures.get(pid, 0) + 1
            if self._failures[pid] >= 3:
                self._cooldowns[pid] = now + 60.0

    def hydrate_item(
        self,
        shortcode: str,
        media_type_hint: str,
        dest_path: Path,
        cdn_url: str = "",
        cookie_file: Optional[Path] = None,
        sha_index: Optional[Dict[str, str]] = None,
    ) -> Tuple[bool, str, List[Dict]]:
        """
        Downloads and validates a post into dest_path (supporting multi-slide carousels
        and SHA-256 deduplication). Returns (success, provider_used, persisted_records).
        """
        chain = self.select_chain(media_type_hint)
        last_error = "No provider succeeded"

        with tempfile.TemporaryDirectory(prefix="instalogger_stage_") as tmp_str:
            stage_root = Path(tmp_str)
            for provider in chain:
                if not self.can_use(provider):
                    continue

                provider_stage = stage_root / provider.provider_id
                provider_stage.mkdir(parents=True, exist_ok=True)
                outcome = provider.download_post(
                    shortcode=shortcode,
                    media_type_hint=media_type_hint,
                    staging_dir=provider_stage,
                    cdn_url=cdn_url,
                    cookie_file=cookie_file,
                )
                self._record_outcome(outcome)

                if outcome.status == DownloadStatus.SUCCESS and outcome.assets:
                    records = self._commit_assets(dest_path, outcome.assets, provider.provider_id, sha_index)
                    return True, provider.provider_id, records

                if outcome.status != DownloadStatus.UNSUPPORTED_MEDIA:
                    last_error = outcome.message or outcome.status.value

        return False, last_error, []

    @staticmethod
    def _commit_assets(
        dest_path: Path,
        assets: List[StagedAsset],
        provider_id: str,
        sha_index: Optional[Dict[str, str]],
    ) -> List[Dict]:
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        persisted: List[Dict] = []
        base_stem = dest_path.stem

        for idx, asset in enumerate(assets):
            ext = asset.file_path.suffix.lstrip(".") or ("mp4" if asset.media_type == "video" else "jpg")
            if len(assets) == 1:
                final_target = dest_path.with_suffix(f".{ext}")
            else:
                final_target = dest_path.parent / f"{base_stem}_{idx + 1}.{ext}"

            if sha_index is not None and asset.sha256 and asset.sha256 in sha_index:
                existing = Path(sha_index[asset.sha256])
                if existing.exists() and existing.stat().st_size > 0:
                    final_target = existing
                else:
                    shutil.copy2(asset.file_path, final_target)
                    sha_index[asset.sha256] = str(final_target)
            else:
                shutil.copy2(asset.file_path, final_target)
                if sha_index is not None and asset.sha256:
                    sha_index[asset.sha256] = str(final_target)

            if asset.thumb_path and asset.thumb_path.exists():
                thumb_dest = final_target.with_name(f"{final_target.stem}_thumb.jpg")
                shutil.copy2(asset.thumb_path, thumb_dest)

            persisted.append(
                {
                    "position": idx,
                    "media_type": asset.media_type,
                    "file_path": str(final_target),
                    "mime_type": asset.mime_type,
                    "file_size": asset.file_size,
                    "sha256": asset.sha256,
                    "provider": provider_id,
                }
            )
        return persisted
