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

    def fetch_via_graphql_hash(self, username: str) -> Optional[Dict[str, Any]]:
        """Fetch profile using Instagram persistent GraphQL query hash."""
        url = f"https://www.instagram.com/graphql/query/?query_hash=69cba40317214236af40e7efa697781d&variables={json.dumps({'user_name': username})}"
        self.session.headers["Referer"] = f"https://www.instagram.com/{username}/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200:
                data = resp.json()
                if data.get("data", {}).get("user"):
                    return {"status": "ok", "data": data.get("data", {})}
        except Exception as e:
            logger.debug(f"GraphQL hash query failed for @{username}: {e}")
        return None

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

        # Second attempt: GraphQL persistent query hash
        hash_res = self.fetch_via_graphql_hash(username)
        if hash_res:
            return hash_res

        # Fallback to Instaloader context
        try:
            profile = instaloader.Profile.from_username(self.il.context, username)
            media_count = profile.mediacount if profile.mediacount is not None else 0
            followers = profile.followers if profile.followers is not None else 0
            followees = profile.followees if profile.followees is not None else 0

            posts_edges = []
            if not profile.is_private or getattr(profile, "followed_by_viewer", False):
                try:
                    for idx, post in enumerate(profile.get_posts()):
                        if idx >= 24:
                            break
                        posts_edges.append({
                            "node": {
                                "id": str(post.mediaid),
                                "shortcode": post.shortcode,
                                "display_url": post.url,
                                "is_video": post.is_video,
                                "video_url": post.video_url if post.is_video else None,
                                "edge_media_to_caption": {"edges": [{"node": {"text": post.caption or ""}}]},
                                "edge_media_preview_like": {"count": post.likes or 0},
                                "edge_media_to_comment": {"count": post.comments or 0},
                                "taken_at_timestamp": int(post.date_utc.timestamp()),
                            }
                        })
                except Exception as post_err:
                    logger.debug(f"Instaloader posts extraction error for @{username}: {post_err}")

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
                        "edge_followed_by": {"count": followers},
                        "edge_follow": {"count": followees},
                        "edge_owner_to_timeline_media": {"count": media_count, "edges": posts_edges},
                        "profile_pic_url_hd": profile.profile_pic_url or "",
                    }
                }
            }
        except Exception as e:
            return {"status": "error", "message": str(e)}

    def fetch_user_highlights(self, user_id: str) -> List[Dict[str, Any]]:
        """Fetch all user highlight trays via Instagram GraphQL or web endpoint."""
        url = f"https://www.instagram.com/graphql/query/?query_hash=d4d88dc1500312af6f937f7b804c68c3&variables={json.dumps({'user_id': str(user_id), 'include_highlight_reels': True})}"
        self.session.headers["Referer"] = "https://www.instagram.com/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200:
                data = resp.json()
                edges = data.get("data", {}).get("user", {}).get("edge_highlight_reels", {}).get("edges", [])
                results = []
                for edge in edges:
                    node = edge.get("node", {})
                    results.append({
                        "id": str(node.get("id")),
                        "title": node.get("title") or "Highlight",
                        "cover_url": node.get("cover_media", {}).get("thumbnail_src") or "",
                        "media_count": int(node.get("media_count") or 0)
                    })
                if results:
                    return results
        except Exception as e:
            logger.debug(f"Highlights query failed for user {user_id}: {e}")

        # Fallback to Instaloader highlights
        try:
            profile = instaloader.Profile.from_id(self.il.context, int(user_id))
            results = []
            for highlight in self.il.get_highlights(user=profile):
                results.append({
                    "id": str(highlight.unique_id),
                    "title": highlight.title or "Highlight",
                    "cover_url": highlight.cover_url or "",
                    "media_count": highlight.itemcount
                })
            return results
        except Exception as e:
            logger.debug(f"Instaloader highlight fallback error for user {user_id}: {e}")

        return []

    def fetch_highlight_stories(self, highlight_id: str) -> List[Dict[str, Any]]:
        """Fetch all stories within a specific highlight tray."""
        clean_id = highlight_id if highlight_id.startswith("highlight:") else f"highlight:{highlight_id}"
        url = f"https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables={json.dumps({'highlight_reel_ids': [clean_id]})}"
        self.session.headers["Referer"] = "https://www.instagram.com/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200:
                data = resp.json()
                reels_media = data.get("data", {}).get("reels_media", [])
                stories = []
                for reel in reels_media:
                    items = reel.get("items", [])
                    for item in items:
                        is_video = bool(item.get("is_video", False))
                        cdn_url = ""
                        if is_video:
                            video_resources = item.get("video_resources", [])
                            cdn_url = video_resources[0].get("src") if video_resources else (item.get("video_url") or "")
                        if not cdn_url:
                            cdn_url = item.get("display_url") or ""

                        stories.append({
                            "id": str(item.get("id")),
                            "media_type": "video" if is_video else "image",
                            "cdn_url": cdn_url,
                            "taken_at": item.get("taken_at_timestamp") or int(time.time()),
                            "dimensions": item.get("dimensions", {})
                        })
                return stories
        except Exception as e:
            logger.debug(f"Highlight stories query failed for {highlight_id}: {e}")
        return []
