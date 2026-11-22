import json
import logging
import os
import random
import time
from typing import Any, Dict, List, Optional
import requests
import instaloader

logger = logging.getLogger("instagram_client")

USER_AGENTS = [
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
]

APP_ID = "936619743392459"


class InstagramClient:
    def __init__(self, session_path: Optional[str] = None):
        self.session_path = session_path
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": random.choice(USER_AGENTS),
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Sec-Ch-Ua": '"Chromium";v="130", "Android";v="14"',
            "Sec-Ch-Ua-Mobile": "?1",
            "Sec-Ch-Ua-Platform": '"Android"',
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
            "X-Ig-App-Id": APP_ID,
            "X-Requested-With": "XMLHttpRequest",
        })
        self.il = instaloader.Instaloader(
            download_pictures=False,
            download_videos=False,
            download_video_thumbnails=False,
            download_geotags=False,
            download_comments=False,
            save_metadata=False,
            compress_json=False,
        )
        self._load_session()

    def _load_session(self):
        if not self.session_path or not os.path.exists(self.session_path):
            return

        try:
            with open(self.session_path, "r", encoding="utf-8") as f:
                content = f.read().strip()
                if content.startswith("{"):
                    # JSON format cookie dictionary
                    cookies = json.loads(content)
                    for k, v in cookies.items():
                        self.session.cookies.set(k, str(v), domain=".instagram.com")
                else:
                    # Instaloader session file
                    self.il.load_session_from_file(None, filename=self.session_path)
                    for k, v in self.il.context._session.cookies.items():
                        self.session.cookies.set(k, v, domain=".instagram.com")
        except Exception as e:
            logger.warning(f"Failed to load session cookies from {self.session_path}: {e}")

    def fetch_web_profile(self, username: str) -> Dict[str, Any]:
        """Fetch profile metadata via Instagram API with fallback."""
        url = f"https://www.instagram.com/api/v1/users/web_profile_info/?username={username}"
        self.session.headers["Referer"] = f"https://www.instagram.com/{username}/"

        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200:
                data = resp.json()
                if data.get("data", {}).get("user"):
                    return {"status": "ok", "data": data.get("data", {})}
        except Exception:
            pass

        # Fallback to Instaloader context
        try:
            profile = instaloader.Profile.from_username(self.il.context, username)
            media_count = profile.mediacount if profile.mediacount is not None else 0
            return {
                "status": "ok",
                "data": {
                    "user": {
                        "id": str(profile.userid),
                        "username": profile.username,
                        "full_name": profile.full_name or "",
                        "biography": profile.biography or "",
                        "is_private": bool(profile.is_private),
                        "followed_by_viewer": bool(getattr(profile, "followed_by_viewer", False)),
                        "edge_owner_to_timeline_media": {"count": media_count},
                        "profile_pic_url_hd": profile.profile_pic_url or "",
                    }
                }
            }
        except Exception as e:
            return {"status": "error", "message": str(e)}
