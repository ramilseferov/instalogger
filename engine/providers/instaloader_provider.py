from __future__ import annotations

from pathlib import Path
from typing import Optional, Set
import requests

from .base import (
    DownloadOutcome,
    DownloadStatus,
    ProviderCapability,
    StagedAsset,
    is_allowed_media_url,
    sanitize_log,
    validate_media_file,
)


class InstaloaderHttpProvider:
    """
    MIT-compatible authenticated/guest HTTP media provider for direct Instagram CDN URLs
    (photos, videos, and fallback hydration when CLI extractors are unavailable).
    """

    provider_id: str = "instaloader-http"
    capabilities: Set[ProviderCapability] = {
        ProviderCapability.PHOTO_POST,
        ProviderCapability.VIDEO_POST,
        ProviderCapability.CAROUSEL,
    }

    def __init__(self, timeout_seconds: int = 30):
        self.timeout_seconds = timeout_seconds
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": (
                    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
            }
        )

    def is_available(self) -> bool:
        return True

    def download_post(
        self,
        shortcode: str,
        media_type_hint: str,
        staging_dir: Path,
        cdn_url: str = "",
        cookie_file: Optional[Path] = None,
    ) -> DownloadOutcome:
        if not is_allowed_media_url(cdn_url):
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message="Missing or disallowed CDN URL",
            )

        staging_dir.mkdir(parents=True, exist_ok=True)
        ext = "mp4" if media_type_hint in ("video", "reel") else "jpg"
        dest = staging_dir / f"{shortcode}.{ext}"

        try:
            r = self.session.get(cdn_url, timeout=self.timeout_seconds, stream=True)
            if r.status_code == 429:
                return DownloadOutcome(
                    status=DownloadStatus.RATE_LIMITED,
                    provider_id=self.provider_id,
                    message="HTTP 429 Too Many Requests",
                )
            if r.status_code in (401, 403):
                return DownloadOutcome(
                    status=DownloadStatus.LOGIN_REQUIRED,
                    provider_id=self.provider_id,
                    message=f"HTTP {r.status_code}",
                )
            if r.status_code != 200:
                return DownloadOutcome(
                    status=DownloadStatus.ERROR,
                    provider_id=self.provider_id,
                    message=f"HTTP {r.status_code}",
                )

            with open(dest, "wb") as f_out:
                for chunk in r.iter_content(chunk_size=65536):
                    if chunk:
                        f_out.write(chunk)
        except Exception as e:
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message=sanitize_log(str(e)),
            )

        validated = validate_media_file(dest)
        if not validated:
            dest.unlink(missing_ok=True)
            return DownloadOutcome(
                status=DownloadStatus.ERROR,
                provider_id=self.provider_id,
                message="Downloaded payload failed media magic-byte validation",
            )

        mime, real_ext, is_vid, size, sha = validated
        if real_ext != ext:
            renamed = staging_dir / f"{shortcode}.{real_ext}"
            dest.rename(renamed)
            dest = renamed

        return DownloadOutcome(
            status=DownloadStatus.SUCCESS,
            provider_id=self.provider_id,
            assets=[
                StagedAsset(
                    position=0,
                    media_type="video" if is_vid else "image",
                    file_path=dest,
                    mime_type=mime,
                    file_size=size,
                    sha256=sha,
                    remote_url=cdn_url,
                )
            ],
        )
