from __future__ import annotations

import hashlib
import os
import re
import urllib.parse
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol, Set, Tuple


class ProviderCapability(str, Enum):
    PHOTO_POST = "photo_post"
    VIDEO_POST = "video_post"
    REEL = "reel"
    CAROUSEL = "carousel"
    STORY = "story"
    HIGHLIGHT = "highlight"
    METADATA = "metadata"


class DownloadStatus(str, Enum):
    SUCCESS = "success"
    UNSUPPORTED_MEDIA = "unsupported_media"
    RATE_LIMITED = "rate_limited"
    LOGIN_REQUIRED = "login_required"
    ERROR = "error"


@dataclass
class StagedAsset:
    position: int
    media_type: str  # "image" or "video"
    file_path: Path
    thumb_path: Optional[Path] = None
    mime_type: str = "application/octet-stream"
    file_size: int = 0
    sha256: str = ""
    remote_url: str = ""


@dataclass
class DownloadOutcome:
    status: DownloadStatus
    provider_id: str
    assets: List[StagedAsset] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    message: str = ""
    retry_after_seconds: int = 120


class InstagramProvider(Protocol):
    provider_id: str
    capabilities: Set[ProviderCapability]

    def is_available(self) -> bool:
        ...

    def download_post(
        self,
        shortcode: str,
        media_type_hint: str,
        staging_dir: Path,
        cdn_url: str = "",
        cookie_file: Optional[Path] = None,
    ) -> DownloadOutcome:
        ...


_SENSITIVE_COOKIE_RE = re.compile(
    r"(?i)(sessionid|csrftoken|ds_user_id|rur|mid|ig_did|authorization|cookie)\s*[:=]\s*[^;\s,\"'&]+"
)
_SENSITIVE_QUERY_RE = re.compile(
    r"(?i)([?&](?:oe|oh|_nc_sid|ccb|efg|signature|token|access_token)=)[^&\s\"']+"
)

ALLOWED_HOST_SUFFIXES = (
    "instagram.com",
    "cdninstagram.com",
    "fbcdn.net",
    "fbsbx.com",
)


def sanitize_log(raw: Optional[str]) -> str:
    if not raw:
        return ""
    step1 = _SENSITIVE_COOKIE_RE.sub(lambda m: f"{m.group(1)}=[REDACTED]", raw)
    return _SENSITIVE_QUERY_RE.sub(lambda m: f"{m.group(1)}[REDACTED]", step1)


def is_allowed_media_url(url: Optional[str]) -> bool:
    if not url or not url.strip():
        return False
    try:
        parsed = urllib.parse.urlparse(url.strip())
        if parsed.scheme.lower() != "https":
            return False
        host = (parsed.hostname or "").lower()
        return any(host == suffix or host.endswith(f".{suffix}") for suffix in ALLOWED_HOST_SUFFIXES)
    except Exception:
        return False


def compute_sha256(file_path: Path) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_media_file(file_path: Path, max_bytes: int = 250 * 1024 * 1024) -> Optional[Tuple[str, str, bool, int, str]]:
    """
    Validates downloaded media via magic bytes (rejecting HTML/JSON login pages)
    and returns (mime_type, ext, is_video, size, sha256) or None.
    """
    if not file_path.exists() or not file_path.is_file():
        return None
    size = file_path.stat().st_size
    if size < 128 or size > max_bytes:
        return None

    with open(file_path, "rb") as f:
        header = f.read(32)
    if len(header) < 12:
        return None

    head_ascii = header.decode("ascii", errors="ignore").lstrip().lower()
    if head_ascii.startswith(("<!doc", "<html", "<?xml", "{")):
        return None

    detected: Optional[Tuple[str, str, bool]] = None
    if header[0:3] == b"\xff\xd8\xff":
        detected = ("image/jpeg", "jpg", False)
    elif header[0:4] == b"\x89PNG":
        detected = ("image/png", "png", False)
    elif header[0:3] == b"GIF":
        detected = ("image/gif", "gif", False)
    elif header[0:4] == b"RIFF" and header[8:12] == b"WEBP":
        detected = ("image/webp", "webp", False)
    elif header[4:8] == b"ftyp":
        brand = header[8:12].decode("ascii", errors="ignore").lower()
        if brand in ("heic", "heix", "mif1", "avif"):
            detected = (f"image/{brand}", brand, False)
        else:
            detected = ("video/mp4", "mp4", True)
    elif header[0:4] == b"\x1a\x45\xdf\xa3":
        detected = ("video/webm", "webm", True)

    if not detected:
        return None

    sha256 = compute_sha256(file_path)
    return (detected[0], detected[1], detected[2], size, sha256)
