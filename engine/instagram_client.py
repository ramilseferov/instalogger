import json
import logging
import os
import random
import re
import time
from typing import Any, Dict, List, Optional
import requests
import instaloader

logger = logging.getLogger("instagram_client")

DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
IG_ANDROID_APP_UA = (
    "Instagram 350.0.0.0.0 Android (34/14; 420dpi; 1080x2400; "
    "Google; Pixel 8; shiba; zuma; en_US; 634000000)"
)
APP_ID = "936619743392459"
ASBD_ID = "129477"

AD_KEYS = (
    "ad_id", "fb_ad_id", "ad_action", "is_ad", "injected", "dr_ad_type",
    "ad_link_type", "ad_metadata", "sponsored_label", "is_sponsored",
    "ad_header_style", "ad_expiry_timestamp_in_millis", "ad_cta",
    "click_id", "ad_intent_data",
)


def _has_non_empty_value(val: Any) -> bool:
    if val is None or val is False or val == 0 or val == "":
        return False
    if isinstance(val, (dict, list)):
        return len(val) > 0
    return True


def is_sponsored_or_foreign_node(node: Dict[str, Any], target_username: Optional[str] = None) -> bool:
    if not isinstance(node, dict):
        return True
    for k in AD_KEYS:
        if k in node and _has_non_empty_value(node[k]):
            return True
    p_type = str(node.get("product_type") or "").lower()
    if p_type in ("ad", "sponsored"):
        return True
    comm = str(node.get("commerciality_status") or "").lower()
    if comm in ("ads", "sponsored"):
        return True
    label = str(node.get("label") or "").lower()
    if any(w in label for w in ("sponsored", "reklam", "реклама")):
        return True

    clean_target = (target_username or "").strip().lstrip("@").lower()
    if clean_target:
        owner_obj = node.get("user") or node.get("owner") or {}
        owner_name = str(owner_obj.get("username") or "").strip().lower() if isinstance(owner_obj, dict) else ""
        if owner_name and owner_name != clean_target:
            coauthors = node.get("coauthor_producers") or []
            is_co = any(
                isinstance(c, dict) and str(c.get("username") or "").strip().lower() == clean_target
                for c in coauthors
            )
            if not is_co:
                return True
    return False


def _parse_formatted_number(raw: str) -> int:
    s = (raw or "").strip().upper()
    if not s:
        return 0
    try:
        if s.endswith("K"):
            return int(float(s[:-1].replace(",", ".")) * 1_000)
        if s.endswith("M"):
            return int(float(s[:-1].replace(",", ".")) * 1_000_000)
        if s.endswith("B"):
            return int(float(s[:-1].replace(",", ".")) * 1_000_000_000)
        return int(re.sub(r"[^\d]", "", s) or "0")
    except Exception:
        return 0


def parse_cookie_text(content: str) -> Dict[str, str]:
    """Parse JSON dict or raw semicolon-separated HTTP Cookie header."""
    text = (content or "").strip()
    if not text:
        return {}
    if text.startswith("{"):
        try:
            data = json.loads(text)
            if isinstance(data, dict):
                return {str(k).strip(): str(v).strip() for k, v in data.items() if str(k).strip() and str(v).strip()}
        except Exception:
            pass
    cookies: Dict[str, str] = {}
    for pair in text.replace("\n", ";").split(";"):
        pair = pair.strip()
        if "=" not in pair:
            continue
        k, v = pair.split("=", 1)
        k = k.strip()
        v = v.strip()
        if k and v and k.lower() not in ("path", "domain", "expires", "max-age", "secure", "httponly", "samesite"):
            cookies[k] = v
    return cookies


def parse_profile_and_posts_from_ssr_html(html: str, username: str, limit: int = 24) -> Optional[Dict[str, Any]]:
    """Extract structured user profile + organic posts from Instagram Desktop SSR HTML."""
    clean_target = username.strip().lstrip("@").lower()
    best_user: Optional[Dict[str, Any]] = None
    best_score = -1
    posts_edges: List[Dict[str, Any]] = []
    seen_codes = set()

    def score_user(obj: Dict[str, Any]) -> int:
        s = 0
        if obj.get("pk") or (obj.get("id") and str(obj.get("id")).isdigit()):
            s += 4
        if "follower_count" in obj or "edge_followed_by" in obj:
            s += 4
        if "following_count" in obj or "edge_follow" in obj:
            s += 2
        if "full_name" in obj:
            s += 2
        if "biography" in obj:
            s += 2
        if any(k in obj for k in ("hd_profile_pic_url_info", "profile_pic_url_hd", "profile_pic_url")):
            s += 2
        if "media_count" in obj or "edge_owner_to_timeline_media" in obj:
            s += 1
        return s

    def walk(elem: Any):
        nonlocal best_user, best_score
        if isinstance(elem, dict):
            if _has_non_empty_value(elem.get("ad_id")) or _has_non_empty_value(elem.get("injected")) or _has_non_empty_value(elem.get("explore_story")):
                return
            u_name = str(elem.get("username") or "").strip().lower()
            if u_name == clean_target:
                sc = score_user(elem)
                if sc > best_score:
                    best_score = sc
                    best_user = elem

            for conn_key in (
                "polaris_ordered_timeline_connection",
                "xdt_api__v1__feed__user_timeline_graphql_connection",
                "edge_owner_to_timeline_media",
            ):
                conn = elem.get(conn_key)
                if isinstance(conn, dict) and isinstance(conn.get("edges"), list):
                    for edge in conn["edges"]:
                        if len(posts_edges) >= limit:
                            break
                        node = edge.get("node") if isinstance(edge, dict) else None
                        if not isinstance(node, dict) or is_sponsored_or_foreign_node(node, clean_target):
                            continue
                        code = str(node.get("code") or node.get("shortcode") or "").strip()
                        if not code or code in seen_codes:
                            continue
                        seen_codes.add(code)
                        posts_edges.append({"node": node})

            for v in elem.values():
                walk(v)
        elif isinstance(elem, list):
            for child in elem:
                walk(child)

    for match in re.finditer(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', html, re.DOTALL):
        raw = match.group(1)
        if clean_target not in raw.lower() and "timeline" not in raw:
            continue
        try:
            walk(json.loads(raw))
        except Exception:
            continue

    if best_user is not None:
        uid = str(best_user.get("pk") or best_user.get("id") or "")
        followers = int(
            best_user.get("follower_count")
            or (best_user.get("edge_followed_by") or {}).get("count")
            or 0
        )
        following = int(
            best_user.get("following_count")
            or (best_user.get("edge_follow") or {}).get("count")
            or 0
        )
        media_count = int(
            best_user.get("media_count")
            or (best_user.get("edge_owner_to_timeline_media") or {}).get("count")
            or len(posts_edges)
        )
        pic_url = (
            (best_user.get("hd_profile_pic_url_info") or {}).get("url")
            or best_user.get("profile_pic_url_hd")
            or best_user.get("profile_pic_url")
            or ""
        )
        latest_reel = int(best_user.get("latest_reel_media") or 0)
        return {
            "id": uid or f"usr_{clean_target}",
            "username": str(best_user.get("username") or clean_target),
            "full_name": str(best_user.get("full_name") or ""),
            "biography": str(best_user.get("biography") or ""),
            "is_private": bool(best_user.get("is_private", False)),
            "followed_by_viewer": bool(best_user.get("followed_by_viewer", False)),
            "edge_followed_by": {"count": followers},
            "edge_follow": {"count": following},
            "edge_owner_to_timeline_media": {"count": media_count, "edges": posts_edges},
            "profile_pic_url_hd": pic_url,
            "latest_reel_media": latest_reel,
        }

    # Meta og:description fallback for public SSR pages without full Relay JSON
    og_desc = re.search(r'<meta[^>]+(?:name|property)="(?:description|og:description)"[^>]+content="([^"]+)"', html, re.I)
    og_title = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]+)"', html, re.I)
    og_img = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"', html, re.I)
    if og_desc:
        desc = og_desc.group(1)
        m = re.search(
            r"([\d,.]+[KkMmBb]?)\s+(?:Followers|Takipçi|Подписчик|İzləyici)[^,;]*[,;]\s*"
            r"([\d,.]+[KkMmBb]?)\s+(?:Following|Takip|Подписк|İzlənilən)[^,;]*[,;]\s*"
            r"([\d,.]+[KkMmBb]?)\s+(?:Posts|Gönderi|Публикац|Paylaşım)",
            desc,
            re.I,
        )
        if m:
            pk_match = re.search(r'"(?:pk|profile_id)"\s*:\s*"?(\d+)"?', html)
            uid = pk_match.group(1) if pk_match else f"usr_{clean_target}"
            full_name = og_title.group(1).split("(")[0].strip() if og_title else ""
            return {
                "id": uid,
                "username": clean_target,
                "full_name": full_name,
                "biography": "",
                "is_private": '"is_private":true' in html,
                "followed_by_viewer": False,
                "edge_followed_by": {"count": _parse_formatted_number(m.group(1))},
                "edge_follow": {"count": _parse_formatted_number(m.group(2))},
                "edge_owner_to_timeline_media": {"count": _parse_formatted_number(m.group(3)), "edges": posts_edges},
                "profile_pic_url_hd": og_img.group(1).replace("&amp;", "&") if og_img else "",
                "latest_reel_media": 0,
            }
    return None


class InstagramClient:
    def __init__(self, session_path: Optional[str] = None):
        self.session_path = session_path
        self.has_session = False
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": DESKTOP_UA,
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Origin": "https://www.instagram.com",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
            "X-Ig-App-Id": APP_ID,
            "X-Asbd-Id": ASBD_ID,
            "X-Ig-Www-Claim": "0",
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

    def _apply_cookies(self, cookies: Dict[str, str]):
        if not cookies:
            return
        for k, v in cookies.items():
            self.session.cookies.set(k, str(v), domain=".instagram.com")
            self.il.context._session.cookies.set(k, str(v), domain=".instagram.com")
        csrf = cookies.get("csrftoken")
        if csrf:
            self.session.headers["X-CSRFToken"] = csrf
            self.il.context._session.headers["X-CSRFToken"] = csrf
        if cookies.get("sessionid"):
            self.has_session = True

    def _load_session(self):
        if not self.session_path or not os.path.exists(self.session_path):
            return
        try:
            with open(self.session_path, "rb") as fb:
                raw_bytes = fb.read()
            try:
                content = raw_bytes.decode("utf-8").strip()
            except UnicodeDecodeError:
                content = ""

            if content:
                cookies = parse_cookie_text(content)
                if cookies:
                    self._apply_cookies(cookies)
                    return

            # Binary Instaloader pickle session fallback
            self.il.load_session_from_file(None, filename=self.session_path)
            loaded = dict(self.il.context._session.cookies.items())
            self._apply_cookies(loaded)
        except Exception as e:
            logger.warning(f"Failed to load session cookies from {self.session_path}: {e}")

    def _ensure_guest_cookies(self):
        if self.session.cookies.get("csrftoken"):
            return
        try:
            resp = requests.get(
                "https://www.instagram.com/",
                headers={
                    "User-Agent": DESKTOP_UA,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "en-US,en;q=0.9",
                },
                timeout=12,
            )
            guest = dict(resp.cookies.items())
            if "csrftoken" not in guest:
                m = re.search(r'"csrf_token"\s*:\s*"([^"]+)"', resp.text)
                if m:
                    guest["csrftoken"] = m.group(1)
            for k, v in guest.items():
                self.session.cookies.set(k, v, domain=".instagram.com")
            if guest.get("csrftoken"):
                self.session.headers["X-CSRFToken"] = guest["csrftoken"]
        except Exception as e:
            logger.debug(f"Guest cookie handshake failed: {e}")

    def _fetch_ssr_profile(self, username: str, authenticated: bool = False) -> Optional[Dict[str, Any]]:
        url = f"https://www.instagram.com/{username}/"
        headers = {
            "User-Agent": DESKTOP_UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
        }
        try:
            if authenticated and self.has_session:
                resp = self.session.get(url, headers=headers, timeout=15, allow_redirects=False)
            else:
                resp = requests.get(url, headers=headers, timeout=15, allow_redirects=False)
            if resp.status_code != 200:
                return None
            user = parse_profile_and_posts_from_ssr_html(resp.text, username)
            if user:
                return {"status": "ok", "data": {"user": user}}
        except Exception as e:
            logger.debug(f"SSR profile fetch failed for @{username} (auth={authenticated}): {e}")
        return None

    def _fetch_mobile_usernameinfo(self, username: str) -> Optional[Dict[str, Any]]:
        if not self.has_session:
            return None
        url = f"https://i.instagram.com/api/v1/users/{username}/usernameinfo/"
        headers = dict(self.session.headers)
        headers["User-Agent"] = IG_ANDROID_APP_UA
        headers["Referer"] = f"https://www.instagram.com/{username}/"
        try:
            resp = self.session.get(url, headers=headers, timeout=15)
            if resp.status_code != 200 or not resp.text.lstrip().startswith("{"):
                return None
            user = resp.json().get("user")
            if not isinstance(user, dict):
                return None
            if str(user.get("username") or "").lower() != username.lower():
                return None
            pic = (user.get("hd_profile_pic_url_info") or {}).get("url") or user.get("profile_pic_url") or ""
            return {
                "status": "ok",
                "data": {
                    "user": {
                        "id": str(user.get("pk") or user.get("id") or ""),
                        "username": user.get("username") or username,
                        "full_name": user.get("full_name") or "",
                        "biography": user.get("biography") or "",
                        "is_private": bool(user.get("is_private", False)),
                        "followed_by_viewer": bool(user.get("friendship_status", {}).get("following", False)),
                        "edge_followed_by": {"count": int(user.get("follower_count") or 0)},
                        "edge_follow": {"count": int(user.get("following_count") or 0)},
                        "edge_owner_to_timeline_media": {"count": int(user.get("media_count") or 0), "edges": []},
                        "profile_pic_url_hd": pic,
                        "latest_reel_media": int(user.get("latest_reel_media") or 0),
                    }
                },
            }
        except Exception as e:
            logger.debug(f"Mobile usernameinfo failed for @{username}: {e}")
        return None

    def fetch_via_graphql_hash(self, username: str) -> Optional[Dict[str, Any]]:
        url = f"https://www.instagram.com/graphql/query/?query_hash=69cba40317214236af40e7efa697781d&variables={json.dumps({'user_name': username})}"
        self.session.headers["Referer"] = f"https://www.instagram.com/{username}/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                data = resp.json()
                if data.get("data", {}).get("user"):
                    return {"status": "ok", "data": data.get("data", {})}
        except Exception as e:
            logger.debug(f"GraphQL hash query failed for @{username}: {e}")
        return None

    def fetch_user_posts(self, username: str, user_id: Optional[str] = None, limit: int = 24) -> List[Dict[str, Any]]:
        """Fetch recent organic posts via authenticated v1 user feed endpoints."""
        clean_user = username.strip().lstrip("@").lower()
        candidates: List[tuple[str, str]] = []
        if user_id and str(user_id).isdigit():
            candidates.append((f"https://www.instagram.com/api/v1/feed/user/{user_id}/?count={limit}", DESKTOP_UA))
            if self.has_session:
                candidates.append((f"https://i.instagram.com/api/v1/feed/user/{user_id}/?count={limit}", IG_ANDROID_APP_UA))
        candidates.append((f"https://www.instagram.com/api/v1/feed/user/{clean_user}/username/?count={limit}", DESKTOP_UA))

        self._ensure_guest_cookies()
        for url, ua in candidates:
            try:
                headers = dict(self.session.headers)
                headers["User-Agent"] = ua
                headers["Referer"] = f"https://www.instagram.com/{clean_user}/"
                resp = self.session.get(url, headers=headers, timeout=15)
                if resp.status_code != 200 or not resp.text.lstrip().startswith("{"):
                    continue
                items = resp.json().get("items") or []
                if not items:
                    continue
                edges = []
                for node in items:
                    if len(edges) >= limit:
                        break
                    if is_sponsored_or_foreign_node(node, clean_user):
                        continue
                    edges.append({"node": node})
                if edges:
                    return edges
            except Exception as e:
                logger.debug(f"Feed query failed ({url}): {e}")
        return []

    def fetch_web_profile(self, username: str) -> Dict[str, Any]:
        """Multi-tier profile fetch: Zero-login SSR -> Mobile API -> Web API -> Auth SSR -> GraphQL -> Instaloader."""
        clean_user = username.strip().lstrip("@").lower()

        # Tier 1: Zero-login Desktop SSR HTML
        ssr_res = self._fetch_ssr_profile(clean_user, authenticated=False)
        if ssr_res:
            return ssr_res

        # Tier 2: Authenticated Mobile API usernameinfo
        mobile_res = self._fetch_mobile_usernameinfo(clean_user)
        if mobile_res:
            return mobile_res

        # Tier 3: Web Profile Info API (with guest or session cookies + CSRF)
        self._ensure_guest_cookies()
        url = f"https://www.instagram.com/api/v1/users/web_profile_info/?username={clean_user}"
        self.session.headers["Referer"] = f"https://www.instagram.com/{clean_user}/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                data = resp.json()
                user = data.get("data", {}).get("user")
                if user and str(user.get("username") or "").lower() == clean_user:
                    return {"status": "ok", "data": {"user": user}}
        except Exception:
            pass

        # Tier 4: Authenticated Desktop SSR HTML
        if self.has_session:
            auth_ssr = self._fetch_ssr_profile(clean_user, authenticated=True)
            if auth_ssr:
                return auth_ssr

        # Tier 5: Legacy GraphQL query hash
        hash_res = self.fetch_via_graphql_hash(clean_user)
        if hash_res:
            return hash_res

        # Tier 6: Instaloader fallback (with loaded session cookies)
        try:
            profile = instaloader.Profile.from_username(self.il.context, clean_user)
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
                    logger.debug(f"Instaloader posts extraction error for @{clean_user}: {post_err}")

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
                },
            }
        except Exception as e:
            msg = str(e)
            if "Redirected to login" in msg and not self.has_session:
                msg = "Instagram requires an active session cookie on this network. Add your session cookie in Settings."
            return {"status": "error", "message": msg}

    def fetch_user_highlights(self, user_id: str, username: Optional[str] = None) -> List[Dict[str, Any]]:
        """Fetch user highlight trays via v1 REST API, GraphQL, or Instaloader."""
        if not user_id or not str(user_id).isdigit():
            return []

        if self.has_session:
            try:
                tray_url = f"https://www.instagram.com/api/v1/highlights/{user_id}/highlights_tray/"
                headers = dict(self.session.headers)
                headers["Referer"] = f"https://www.instagram.com/{username}/" if username else "https://www.instagram.com/"
                resp = self.session.get(tray_url, headers=headers, timeout=15)
                if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                    tray = resp.json().get("tray") or []
                    results = []
                    for obj in tray:
                        cover_media = obj.get("cover_media") or {}
                        cover = (
                            (cover_media.get("cropped_image_version") or {}).get("url")
                            or (cover_media.get("full_image_version") or {}).get("url")
                            or ""
                        )
                        results.append({
                            "id": str(obj.get("id")),
                            "title": obj.get("title") or "Highlight",
                            "cover_url": cover,
                            "media_count": int(obj.get("media_count") or 0),
                        })
                    if results:
                        return results
            except Exception as e:
                logger.debug(f"v1 highlights_tray failed for {user_id}: {e}")

        url = f"https://www.instagram.com/graphql/query/?query_hash=d4d88dc1500312af6f937f7b804c68c3&variables={json.dumps({'user_id': str(user_id), 'include_highlight_reels': True})}"
        self.session.headers["Referer"] = "https://www.instagram.com/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                data = resp.json()
                edges = data.get("data", {}).get("user", {}).get("edge_highlight_reels", {}).get("edges", [])
                results = []
                for edge in edges:
                    node = edge.get("node", {})
                    results.append({
                        "id": str(node.get("id")),
                        "title": node.get("title") or "Highlight",
                        "cover_url": node.get("cover_media", {}).get("thumbnail_src") or "",
                        "media_count": int(node.get("media_count") or 0),
                    })
                if results:
                    return results
        except Exception as e:
            logger.debug(f"Highlights query failed for user {user_id}: {e}")

        try:
            profile = instaloader.Profile.from_id(self.il.context, int(user_id))
            results = []
            for highlight in self.il.get_highlights(user=profile):
                results.append({
                    "id": str(highlight.unique_id),
                    "title": highlight.title or "Highlight",
                    "cover_url": highlight.cover_url or "",
                    "media_count": highlight.itemcount,
                })
            return results
        except Exception as e:
            logger.debug(f"Instaloader highlight fallback error for user {user_id}: {e}")

        return []

    def fetch_highlight_stories(self, highlight_id: str) -> List[Dict[str, Any]]:
        """Fetch stories inside a specific highlight tray."""
        clean_id = highlight_id if highlight_id.startswith("highlight:") else f"highlight:{highlight_id}"

        if self.has_session:
            try:
                v1_url = f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids={clean_id}"
                resp = self.session.get(v1_url, timeout=15)
                if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                    data = resp.json()
                    reels = data.get("reels") or {}
                    reel = reels.get(clean_id) or (next(iter(reels.values())) if reels else None)
                    items = (reel or {}).get("items") or []
                    stories = []
                    for item in items:
                        vids = item.get("video_versions") or []
                        imgs = (item.get("image_versions2") or {}).get("candidates") or []
                        is_video = bool(vids) or item.get("media_type") == 2
                        cdn_url = (vids[0].get("url") if vids else "") or (imgs[0].get("url") if imgs else "")
                        if cdn_url:
                            stories.append({
                                "id": str(item.get("pk") or item.get("id")),
                                "media_type": "video" if is_video else "image",
                                "cdn_url": cdn_url,
                                "taken_at": int(item.get("taken_at") or time.time()),
                                "dimensions": {},
                            })
                    if stories:
                        return stories
            except Exception as e:
                logger.debug(f"v1 reels_media failed for {highlight_id}: {e}")

        url = f"https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables={json.dumps({'highlight_reel_ids': [clean_id]})}"
        self.session.headers["Referer"] = "https://www.instagram.com/"
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                data = resp.json()
                reels_media = data.get("data", {}).get("reels_media", [])
                stories = []
                for reel in reels_media:
                    for item in reel.get("items", []):
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
                            "dimensions": item.get("dimensions", {}),
                        })
                return stories
        except Exception as e:
            logger.debug(f"Highlight stories query failed for {highlight_id}: {e}")
        return []
