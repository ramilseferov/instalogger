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


class YtDlpProvider:
    """
    Production yt-dlp CLI subprocess provider for Instagram Reels, Video posts,
    and DASH audio+video FFmpeg muxing.
    """

    provider_id: str = "yt-dlp"
    capabilities: Set[ProviderCapability] = {
        ProviderCapability.VIDEO_POST,
        ProviderCapability.REEL,
        ProviderCapability.CAROUSEL,
        ProviderCapability.METADATA,
    }

    QUALITY_FORMATS: Dict[str, str] = {
        "BEST": "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/best",
        "HD_1080": "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/best[height<=1080]/best",
        "HD_720": "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/best[height<=720]/best",
        "DATA_SAVER": "bv*[height<=480][ext=mp4]+ba[ext=m4a]/b[height<=480][ext=mp4]/worst[ext=mp4]/best",
    }

    def __init__(self, binary_path: Optional[str] = None, timeout_seconds: int = 120, quality_preset: str = "BEST"):
        self.binary_path = binary_path or shutil.which("yt-dlp")
        self.timeout_seconds = timeout_seconds
        self.quality_preset = (quality_preset or "BEST").upper()

    def is_available(self) -> bool:
        return bool(self.binary_path and Path(self.binary_path).exists())

    @staticmethod
    def classify_error(stderr: str) -> DownloadOutcome:
        lower = (stderr or "").lower()
        safe = sanitize_log(stderr)[:260]
        if (
            "there is no video in this post" in lower
            or "no video formats found" in lower
            or "requested format is not available" in lower
        ):
            return DownloadOutcome(
                status=DownloadStatus.UNSUPPORTED_MEDIA,
                provider_id="yt-dlp",
                message=safe or "No video in post",
            )
        if "429" in lower or "rate-limit" in lower or "too many requests" in lower:
            return DownloadOutcome(
                status=DownloadStatus.RATE_LIMITED,
                provider_id="yt-dlp",
                message=safe or "Instagram rate limit (429)",
                retry_after_seconds=120,
            )
        if "login required" in lower or "checkpoint" in lower or "empty media response" in lower:
            return DownloadOutcome(
                status=DownloadStatus.LOGIN_REQUIRED,
                provider_id="yt-dlp",
                message=safe or "Instagram login required",
            )
        return DownloadOutcome(
            status=DownloadStatus.ERROR,
            provider_id="yt-dlp",
            message=safe or "yt-dlp failed",
        )

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
                message="yt-dlp binary not installed",
            )

        staging_dir.mkdir(parents=True, exist_ok=True)
        urls = (
            [f"https://www.instagram.com/reel/{shortcode}/", f"https://www.instagram.com/p/{shortcode}/"]
            if media_type_hint in ("video", "reel")
            else [f"https://www.instagram.com/p/{shortcode}/"]
        )

        last_outcome = DownloadOutcome(status=DownloadStatus.ERROR, provider_id=self.provider_id, message="No output")

        for url in urls:
            for existing in staging_dir.iterdir():
                if existing.is_file():
                    existing.unlink(missing_ok=True)

            fmt = self.QUALITY_FORMATS.get(self.quality_preset, self.QUALITY_FORMATS["BEST"])
            cmd = [
                self.binary_path,
                "-f", fmt,
                "--merge-output-format", "mp4",
                "--write-thumbnail",
                "--convert-thumbnails", "jpg",
                "--write-info-json",
                "--no-mtime",
                "--no-part",
                "--no-warnings",
                "--retries", "3",
                "--fragment-retries", "3",
                "--socket-timeout", "20",
                "-o", str(staging_dir / "%(playlist_autonumber|01)02d_%(id)s.%(ext)s"),
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
                last_outcome = DownloadOutcome(
                    status=DownloadStatus.ERROR,
                    provider_id=self.provider_id,
                    message=f"yt-dlp timed out after {self.timeout_seconds}s",
                )
                continue
            except Exception as e:
                last_outcome = DownloadOutcome(
                    status=DownloadStatus.ERROR,
                    provider_id=self.provider_id,
                    message=sanitize_log(str(e)),
                )
                continue

            if proc.returncode != 0:
                classified = self.classify_error(proc.stderr or proc.stdout)
                if classified.status in (
                    DownloadStatus.UNSUPPORTED_MEDIA,
                    DownloadStatus.RATE_LIMITED,
                    DownloadStatus.LOGIN_REQUIRED,
                ):
                    return classified
                last_outcome = classified
                continue

            assets, meta = self._collect_outputs(staging_dir)
            if assets:
                return DownloadOutcome(
                    status=DownloadStatus.SUCCESS,
                    provider_id=self.provider_id,
                    assets=assets,
                    metadata=meta,
                )

            last_outcome = DownloadOutcome(
                status=DownloadStatus.UNSUPPORTED_MEDIA,
                provider_id=self.provider_id,
                message="yt-dlp produced no valid video files",
            )

        return last_outcome

    @staticmethod
    def _collect_outputs(staging_dir: Path) -> tuple[List[StagedAsset], Dict]:
        files = sorted(staging_dir.iterdir(), key=lambda p: p.name)
        meta: Dict = {}
        info_files = [p for p in files if p.name.endswith(".info.json")]
        if info_files:
            try:
                meta = json.loads(info_files[0].read_text(encoding="utf-8"))
            except Exception:
                meta = {}

        video_exts = {".mp4", ".mkv", ".webm", ".mov", ".m4v"}
        img_exts = {".jpg", ".jpeg", ".png", ".webp"}
        videos = [p for p in files if p.is_file() and p.suffix.lower() in video_exts]
        thumbs = [p for p in files if p.is_file() and p.suffix.lower() in img_exts]

        assets: List[StagedAsset] = []
        for idx, vf in enumerate(videos):
            validated = validate_media_file(vf)
            if not validated:
                continue
            mime, _ext, is_vid, size, sha = validated
            matching_thumb = next((t for t in thumbs if t.stem == vf.stem), thumbs[idx] if idx < len(thumbs) else None)
            assets.append(
                StagedAsset(
                    position=idx,
                    media_type="video" if is_vid else "image",
                    file_path=vf,
                    thumb_path=matching_thumb,
                    mime_type=mime,
                    file_size=size,
                    sha256=sha,
                )
            )
        return assets, meta
