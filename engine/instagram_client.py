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
    """Extract structured user profile, page_info, and organic posts from Instagram Desktop SSR HTML."""
    clean_target = username.strip().lstrip("@").lower()
    best_user: Optional[Dict[str, Any]] = None
    best_score = -1
    max_media_count = 0
    max_followers = 0
    max_following = 0
    page_info: Dict[str, Any] = {"has_next_page": False, "end_cursor": None}
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
            s += 2
        return s

    def walk(elem: Any):
        nonlocal best_user, best_score, max_media_count, max_followers, max_following
        if isinstance(elem, dict):
            if _has_non_empty_value(elem.get("ad_id")) or _has_non_empty_value(elem.get("injected")) or _has_non_empty_value(elem.get("explore_story")):
                return
            u_name = str(elem.get("username") or "").strip().lower()
            if u_name == clean_target:
                mc = int(elem.get("media_count") or (elem.get("edge_owner_to_timeline_media") or {}).get("count") or 0)
                fc = int(elem.get("follower_count") or (elem.get("edge_followed_by") or {}).get("count") or 0)
                fg = int(elem.get("following_count") or (elem.get("edge_follow") or {}).get("count") or 0)
                if mc > max_media_count:
                    max_media_count = mc
                if fc > max_followers:
                    max_followers = fc
                if fg > max_following:
                    max_following = fg
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
                    pi = conn.get("page_info")
                    if isinstance(pi, dict) and pi.get("end_cursor") and not page_info.get("end_cursor"):
                        page_info["has_next_page"] = bool(pi.get("has_next_page", True))
                        page_info["end_cursor"] = str(pi.get("end_cursor"))
                    if conn.get("count") and int(conn.get("count") or 0) > max_media_count:
                        max_media_count = int(conn.get("count") or 0)
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

    # Extract og:description counts as fallback for media_count / followers / following when SSR omits them
    og_desc = re.search(r'<meta[^>]+(?:name|property)="(?:description|og:description)"[^>]+content="([^"]+)"', html, re.I)
    og_counts = None
    if og_desc:
        m = re.search(
            r"([\d,.]+[KkMmBb]?)\s+(?:Followers|Takipçi|Подписчик|İzləyici)[^,;]*[,;]\s*"
            r"([\d,.]+[KkMmBb]?)\s+(?:Following|Takip|Подписк|İzlənilən)[^,;]*[,;]\s*"
            r"([\d,.]+[KkMmBb]?)\s+(?:Posts|Gönderi|Публикац|Paylaşım)",
            og_desc.group(1),
            re.I,
        )
        if m:
            og_counts = (
                _parse_formatted_number(m.group(1)),
                _parse_formatted_number(m.group(2)),
                _parse_formatted_number(m.group(3)),
            )

    if best_user is not None:
        uid = str(best_user.get("pk") or best_user.get("id") or "")
        followers = max(
            max_followers,
            int(best_user.get("follower_count") or (best_user.get("edge_followed_by") or {}).get("count") or 0),
            og_counts[0] if og_counts else 0,
        )
        following = max(
            max_following,
            int(best_user.get("following_count") or (best_user.get("edge_follow") or {}).get("count") or 0),
            og_counts[1] if og_counts else 0,
        )
        media_count = max(
            max_media_count,
            int(best_user.get("media_count") or (best_user.get("edge_owner_to_timeline_media") or {}).get("count") or 0),
            og_counts[2] if og_counts else 0,
            len(posts_edges),
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
            "edge_owner_to_timeline_media": {
                "count": media_count,
                "edges": posts_edges,
                "page_info": page_info,
            },
            "profile_pic_url_hd": pic_url,
            "latest_reel_media": latest_reel,
        }

    # Meta og:description fallback for public SSR pages without full Relay JSON
    og_title = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]+)"', html, re.I)
    og_img = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"', html, re.I)
    if og_counts:
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
            "edge_followed_by": {"count": og_counts[0]},
            "edge_follow": {"count": og_counts[1]},
            "edge_owner_to_timeline_media": {
                "count": max(og_counts[2], len(posts_edges)),
                "edges": posts_edges,
                "page_info": page_info,
            },
            "profile_pic_url_hd": og_img.group(1).replace("&amp;", "&") if og_img else "",
            "latest_reel_media": 0,
        }
    return None


def parse_highlights_from_ssr_html(html: str) -> List[Dict[str, Any]]:
    """Extract highlight trays from Instagram Desktop SSR HTML (lox_highlights_connection / edge_highlight_reels)."""
    results: List[Dict[str, Any]] = []
    seen_ids = set()

    def walk(elem: Any):
        if isinstance(elem, dict):
            conn = elem.get("lox_highlights_connection") or elem.get("edge_highlight_reels")
            if isinstance(conn, dict) and isinstance(conn.get("edges"), list):
                for edge in conn["edges"]:
                    node = edge.get("node") if isinstance(edge, dict) else None
                    if not isinstance(node, dict):
                        continue
                    raw_id = str(node.get("id") or "").strip()
                    if not raw_id:
                        continue
                    norm_id = raw_id if raw_id.startswith("highlight:") else f"highlight:{raw_id}"
                    if norm_id in seen_ids:
                        continue
                    seen_ids.add(norm_id)
                    cover_media = node.get("cover_media") if isinstance(node.get("cover_media"), dict) else {}
                    cover = (
                        node.get("cover_media_cropped_thumbnail_url")
                        or cover_media.get("thumbnail_src")
                        or (cover_media.get("cropped_image_version") or {}).get("url")
                        or (node.get("cover_media_cropped_thumbnail") or {}).get("url")
                        or ""
                    )
                    results.append({
                        "id": norm_id,
                        "title": str(node.get("title") or "Highlight").strip() or "Highlight",
                        "cover_url": str(cover),
                        "media_count": int(node.get("media_count") or 0),
                    })
            for v in elem.values():
                walk(v)
        elif isinstance(elem, list):
            for child in elem:
                walk(child)

    for match in re.finditer(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', html, re.DOTALL):
        raw = match.group(1)
        if "lox_highlights_connection" not in raw and "edge_highlight_reels" not in raw:
            continue
        try:
            walk(json.loads(raw))
        except Exception:
            continue
    return results


def parse_single_story_node(obj: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Normalize a story or highlight item across Mobile v1, Web SSR, and GraphQL schemas."""
    if not isinstance(obj, dict):
        return None
    item_id = str(obj.get("pk") or obj.get("id") or "").strip()
    if not item_id:
        return None

    vids = obj.get("video_versions") or []
    vid_resources = obj.get("video_resources") or []
    vid_url = (
        (vids[0].get("url") if vids and isinstance(vids[0], dict) else "")
        or (vid_resources[0].get("src") if vid_resources and isinstance(vid_resources[0], dict) else "")
        or str(obj.get("video_url") or "")
    )
    imgs = (obj.get("image_versions2") or {}).get("candidates") or []
    img_url = (
        (imgs[0].get("url") if imgs and isinstance(imgs[0], dict) else "")
        or str(obj.get("display_url") or "")
        or str(obj.get("display_uri") or "")
    )

    is_video = bool(vid_url) or bool(obj.get("is_video", False)) or obj.get("media_type") == 2
    cdn_url = vid_url if (is_video and vid_url) else img_url
    if not cdn_url:
        return None

    taken_at = int(obj.get("taken_at") or obj.get("taken_at_timestamp") or int(time.time()))
    return {
        "id": item_id,
        "media_type": "video" if is_video else "image",
        "cdn_url": cdn_url,
        "thumb_url": img_url or cdn_url,
        "taken_at": taken_at,
        "dimensions": obj.get("dimensions") or {},
    }


def parse_reels_media_json(data: Dict[str, Any], reel_key: Optional[str] = None) -> List[Dict[str, Any]]:
    """Extract normalized story items from all Instagram reels_media / story JSON formats."""
    if not isinstance(data, dict):
        return []
    results: List[Dict[str, Any]] = []
    seen = set()

    def collect(items: Any):
        if not isinstance(items, list):
            return
        for raw in items:
            parsed = parse_single_story_node(raw)
            if parsed and parsed["id"] not in seen:
                seen.add(parsed["id"])
                results.append(parsed)

    # Format A: { "reels": { "<key>": { "items": [...] } } }
    reels_obj = data.get("reels")
    if isinstance(reels_obj, dict) and reels_obj:
        target_reel = reels_obj.get(reel_key) if reel_key else None
        if not isinstance(target_reel, dict):
            target_reel = next(iter(reels_obj.values()), None)
        if isinstance(target_reel, dict):
            collect(target_reel.get("items"))
        if results:
            return results

    # Format B: { "reels_media": [ { "items": [...] } ] }
    reels_media = data.get("reels_media") or (data.get("data") or {}).get("reels_media")
    if isinstance(reels_media, list):
        for reel in reels_media:
            if isinstance(reel, dict):
                collect(reel.get("items"))
        if results:
            return results

    # Format C: { "reel": { "items": [...] } }
    reel_single = data.get("reel")
    if isinstance(reel_single, dict):
        collect(reel_single.get("items"))

    return results


def parse_stories_from_ssr_html(html: str) -> List[Dict[str, Any]]:
    """Extract active/highlight story items from Desktop SSR HTML (xdt_api__v1__feed__reels_media__connection)."""
    results: List[Dict[str, Any]] = []
    seen = set()

    def walk(elem: Any):
        if isinstance(elem, dict):
            conn = elem.get("xdt_api__v1__feed__reels_media__connection")
            if isinstance(conn, dict) and isinstance(conn.get("edges"), list):
                for edge in conn["edges"]:
                    node = edge.get("node") if isinstance(edge, dict) else None
                    if isinstance(node, dict) and isinstance(node.get("items"), list):
                        for item in node["items"]:
                            parsed = parse_single_story_node(item)
                            if parsed and parsed["id"] not in seen:
                                seen.add(parsed["id"])
                                results.append(parsed)
            for v in elem.values():
                walk(v)
        elif isinstance(elem, list):
            for child in elem:
                walk(child)

    for match in re.finditer(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', html, re.DOTALL):
        raw = match.group(1)
        if "xdt_api__v1__feed__reels_media__connection" not in raw:
            continue
        try:
            walk(json.loads(raw))
        except Exception:
            continue
    return results


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

    def fetch_user_posts(
        self,
        username: str,
        user_id: Optional[str] = None,
        limit: int = 50,
        initial_end_cursor: Optional[str] = None,
        already_seen: Optional[set] = None,
    ) -> List[Dict[str, Any]]:
        """Paginate organic posts beyond the initial 12 using v1 max_id, GraphQL end_cursor, and Instaloader."""
        clean_user = username.strip().lstrip("@").lower()
        target_limit = 500 if limit <= 0 else max(1, min(limit, 500))
        edges: List[Dict[str, Any]] = []
        seen_codes = set(already_seen or set())

        def add_node(node: Dict[str, Any]) -> bool:
            if not isinstance(node, dict) or is_sponsored_or_foreign_node(node, clean_user):
                return False
            code = str(node.get("code") or node.get("shortcode") or "").strip()
            if not code or code in seen_codes:
                return False
            seen_codes.add(code)
            edges.append({"node": node})
            return True

        self._ensure_guest_cookies()

        # Stage 1: Fast v1 Feed Cursor Pagination (33 posts/page via next_max_id)
        if self.has_session or (user_id and str(user_id).isdigit()):
            next_max_id: Optional[str] = None
            max_pages = max(1, min((target_limit + 32) // 33, 40))
            for _page in range(max_pages):
                if len(seen_codes) >= target_limit:
                    break
                cursor_q = f"&max_id={requests.utils.quote(next_max_id, safe='')}" if next_max_id else ""
                candidates: List[tuple[str, str]] = []
                if user_id and str(user_id).isdigit():
                    if self.has_session:
                        candidates.append((f"https://i.instagram.com/api/v1/feed/user/{user_id}/?count=33{cursor_q}", IG_ANDROID_APP_UA))
                    candidates.append((f"https://www.instagram.com/api/v1/feed/user/{user_id}/?count=33{cursor_q}", DESKTOP_UA))
                candidates.append((f"https://www.instagram.com/api/v1/feed/user/{clean_user}/username/?count=33{cursor_q}", DESKTOP_UA))

                page_added = 0
                page_more = False
                page_next_cursor: Optional[str] = None
                page_ok = False
                for url, ua in candidates:
                    try:
                        headers = dict(self.session.headers)
                        headers["User-Agent"] = ua
                        headers["Referer"] = f"https://www.instagram.com/{clean_user}/"
                        resp = self.session.get(url, headers=headers, timeout=15)
                        if resp.status_code != 200 or not resp.text.lstrip().startswith("{"):
                            continue
                        payload = resp.json()
                        items = payload.get("items") or []
                        if not items:
                            continue
                        page_ok = True
                        for node in items:
                            if len(seen_codes) >= target_limit:
                                break
                            if add_node(node):
                                page_added += 1
                        page_more = bool(payload.get("more_available", False))
                        page_next_cursor = payload.get("next_max_id")
                        break
                    except Exception as e:
                        logger.debug(f"v1 feed pagination failed ({url}): {e}")

                if not page_ok or not page_more or not page_next_cursor or page_next_cursor == next_max_id:
                    break
                next_max_id = str(page_next_cursor)
                time.sleep(0.25)

        # Stage 2: GraphQL Timeline Cursor Pagination via end_cursor
        if len(seen_codes) < target_limit and user_id and str(user_id).isdigit() and initial_end_cursor:
            cursor: Optional[str] = initial_end_cursor
            for _page in range(25):
                if len(seen_codes) >= target_limit or not cursor:
                    break
                gql_ok = False
                next_cursor: Optional[str] = None
                has_next = False
                for qhash in (
                    "69cba40317214236af40e7efa697781d",
                    "003056d32c2554def87228bc3fd9668a",
                    "e769aa130647d2354c40ea6a439bfc08",
                ):
                    try:
                        variables = json.dumps({"id": str(user_id), "first": 50, "after": cursor})
                        url = f"https://www.instagram.com/graphql/query/?query_hash={qhash}&variables={requests.utils.quote(variables, safe='')}"
                        headers = dict(self.session.headers)
                        headers["Referer"] = f"https://www.instagram.com/{clean_user}/"
                        resp = self.session.get(url, headers=headers, timeout=15)
                        if resp.status_code != 200 or not resp.text.lstrip().startswith("{"):
                            continue
                        tl = (
                            resp.json()
                            .get("data", {})
                            .get("user", {})
                            .get("edge_owner_to_timeline_media", {})
                        )
                        gql_edges = tl.get("edges") or []
                        if not gql_edges:
                            continue
                        gql_ok = True
                        for edge in gql_edges:
                            if len(seen_codes) >= target_limit:
                                break
                            node = edge.get("node") if isinstance(edge, dict) else None
                            if node:
                                add_node(node)
                        pi = tl.get("page_info") or {}
                        has_next = bool(pi.get("has_next_page", False))
                        next_cursor = pi.get("end_cursor")
                        break
                    except Exception as e:
                        logger.debug(f"GraphQL pagination ({qhash[:8]}) failed: {e}")

                if not gql_ok or not has_next or not next_cursor or next_cursor == cursor:
                    break
                cursor = str(next_cursor)
                time.sleep(0.3)

        # Stage 3: Instaloader cursor pagination fallback when still below target_limit
        if len(seen_codes) < target_limit and self.has_session:
            try:
                profile = instaloader.Profile.from_username(self.il.context, clean_user)
                for post in profile.get_posts():
                    if len(seen_codes) >= target_limit:
                        break
                    code = post.shortcode
                    if not code or code in seen_codes:
                        continue
                    seen_codes.add(code)
                    edges.append({
                        "node": {
                            "id": str(post.mediaid),
                            "shortcode": code,
                            "display_url": post.url,
                            "is_video": post.is_video,
                            "video_url": post.video_url if post.is_video else None,
                            "edge_media_to_caption": {"edges": [{"node": {"text": post.caption or ""}}]},
                            "edge_media_preview_like": {"count": post.likes or 0},
                            "edge_media_to_comment": {"count": post.comments or 0},
                            "taken_at_timestamp": int(post.date_utc.timestamp()),
                        }
                    })
            except Exception as e:
                logger.debug(f"Instaloader paginated posts fallback failed for @{clean_user}: {e}")

        return edges

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
                        if idx >= 50:
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

    def fetch_active_user_stories(
        self,
        user_id: Optional[str],
        username: str,
    ) -> List[Dict[str, Any]]:
        """Multi-tier active 24h story extraction: Mobile/Web v1 REST -> Desktop SSR -> GraphQL -> Instaloader."""
        clean_user = username.strip().lstrip("@").lower()
        uid = str(user_id).strip() if user_id and str(user_id).isdigit() else None

        # Tier 1: Authenticated Mobile & Web v1 REST APIs
        if uid and self.has_session:
            referer = f"https://www.instagram.com/stories/{clean_user}/"
            candidates = [
                (f"https://i.instagram.com/api/v1/feed/reels_media/?reel_ids={uid}", IG_ANDROID_APP_UA),
                (f"https://i.instagram.com/api/v1/feed/user/{uid}/story/", IG_ANDROID_APP_UA),
                (f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids={uid}", DESKTOP_UA),
                (f"https://www.instagram.com/api/v1/feed/user/{uid}/story/", DESKTOP_UA),
            ]
            for url, ua in candidates:
                try:
                    headers = dict(self.session.headers)
                    headers["User-Agent"] = ua
                    headers["Referer"] = referer
                    resp = self.session.get(url, headers=headers, timeout=15)
                    if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                        items = parse_reels_media_json(resp.json(), reel_key=uid)
                        if items:
                            return items
                except Exception as e:
                    logger.debug(f"v1 active stories failed ({url}): {e}")

        # Tier 2: Desktop SSR on /stories/<username>/
        try:
            ssr_url = f"https://www.instagram.com/stories/{clean_user}/"
            doc_headers = {
                "User-Agent": DESKTOP_UA,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9",
                "Sec-Fetch-Dest": "document",
                "Sec-Fetch-Mode": "navigate",
                "Sec-Fetch-Site": "none",
            }
            resp = (
                self.session.get(ssr_url, headers=doc_headers, timeout=15)
                if self.has_session
                else requests.get(ssr_url, headers=doc_headers, timeout=15)
            )
            if resp.status_code == 200 and resp.text:
                items = parse_stories_from_ssr_html(resp.text)
                if items:
                    return items
        except Exception as e:
            logger.debug(f"SSR active stories failed for @{clean_user}: {e}")

        # Tier 3: GraphQL reels_media query_hash
        if uid and self.has_session:
            try:
                gql_vars = json.dumps({"reel_ids": [uid], "precomposed_overlay": False})
                url = f"https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables={requests.utils.quote(gql_vars, safe='')}"
                headers = dict(self.session.headers)
                headers["Referer"] = f"https://www.instagram.com/stories/{clean_user}/"
                resp = self.session.get(url, headers=headers, timeout=15)
                if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                    items = parse_reels_media_json(resp.json(), reel_key=uid)
                    if items:
                        return items
            except Exception as e:
                logger.debug(f"GraphQL active stories failed for @{clean_user}: {e}")

        # Tier 4: Instaloader stories fallback (authenticated)
        if uid and self.has_session:
            try:
                results = []
                for story in self.il.get_stories(userids=[int(uid)]):
                    for item in story.get_items():
                        cdn_url = item.video_url if (item.is_video and item.video_url) else item.url
                        if cdn_url:
                            results.append({
                                "id": str(item.mediaid),
                                "media_type": "video" if item.is_video else "image",
                                "cdn_url": cdn_url,
                                "thumb_url": item.url or cdn_url,
                                "taken_at": int(item.date_utc.timestamp()),
                                "dimensions": {},
                            })
                if results:
                    return results
            except Exception as e:
                logger.debug(f"Instaloader active stories failed for @{clean_user}: {e}")

        return []

    def fetch_user_highlights(
        self,
        user_id: str,
        username: Optional[str] = None,
        is_private: bool = False,
    ) -> List[Dict[str, Any]]:
        """Multi-tier highlight trays: Public SSR -> Mobile/Web v1 REST -> Auth SSR -> GraphQL -> Instaloader."""
        clean_user = (username or "").strip().lstrip("@").lower()
        uid = str(user_id).strip() if user_id and str(user_id).isdigit() else None

        doc_headers = {
            "User-Agent": DESKTOP_UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
        }

        # Tier 1: Zero-Login Desktop SSR HTML (Polaris lox_highlights_connection)
        if not is_private and clean_user:
            try:
                resp = requests.get(f"https://www.instagram.com/{clean_user}/", headers=doc_headers, timeout=15)
                if resp.status_code == 200 and resp.text:
                    ssr_trays = parse_highlights_from_ssr_html(resp.text)
                    if ssr_trays:
                        return ssr_trays
            except Exception as e:
                logger.debug(f"Zero-login SSR highlights failed for @{clean_user}: {e}")

        # Tier 2: Authenticated Mobile & Web v1 REST highlights_tray
        if uid and self.has_session:
            referer = f"https://www.instagram.com/{clean_user}/" if clean_user else "https://www.instagram.com/"
            for tray_url, ua in (
                (f"https://i.instagram.com/api/v1/highlights/{uid}/highlights_tray/", IG_ANDROID_APP_UA),
                (f"https://www.instagram.com/api/v1/highlights/{uid}/highlights_tray/", DESKTOP_UA),
            ):
                try:
                    headers = dict(self.session.headers)
                    headers["User-Agent"] = ua
                    headers["Referer"] = referer
                    resp = self.session.get(tray_url, headers=headers, timeout=15)
                    if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                        tray = resp.json().get("tray")
                        if isinstance(tray, list):
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
                                    "title": str(obj.get("title") or "Highlight").strip() or "Highlight",
                                    "cover_url": cover,
                                    "media_count": int(obj.get("media_count") or 0),
                                })
                            return results
                except Exception as e:
                    logger.debug(f"v1 highlights_tray failed ({tray_url}): {e}")

        # Tier 3: Authenticated SSR HTML
        if self.has_session and clean_user:
            try:
                resp = self.session.get(f"https://www.instagram.com/{clean_user}/", headers=doc_headers, timeout=15)
                if resp.status_code == 200 and resp.text:
                    ssr_trays = parse_highlights_from_ssr_html(resp.text)
                    if ssr_trays:
                        return ssr_trays
            except Exception as e:
                logger.debug(f"Authenticated SSR highlights failed for @{clean_user}: {e}")

        # Tier 4: Legacy GraphQL query_hash
        if uid:
            url = f"https://www.instagram.com/graphql/query/?query_hash=d4d88dc1500312af6f937f7b804c68c3&variables={requests.utils.quote(json.dumps({'user_id': uid, 'include_highlight_reels': True}), safe='')}"
            self.session.headers["Referer"] = "https://www.instagram.com/"
            try:
                resp = self.session.get(url, timeout=15)
                if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                    edges = resp.json().get("data", {}).get("user", {}).get("edge_highlight_reels", {}).get("edges", [])
                    results = []
                    for edge in edges:
                        node = edge.get("node", {})
                        results.append({
                            "id": str(node.get("id")),
                            "title": str(node.get("title") or "Highlight").strip() or "Highlight",
                            "cover_url": (node.get("cover_media") or {}).get("thumbnail_src") or "",
                            "media_count": int(node.get("media_count") or 0),
                        })
                    if results:
                        return results
            except Exception as e:
                logger.debug(f"Highlights GraphQL failed for {uid}: {e}")

        # Tier 5: Instaloader highlights fallback (authenticated)
        if uid and self.has_session:
            try:
                profile = instaloader.Profile.from_id(self.il.context, int(uid))
                results = []
                for highlight in self.il.get_highlights(user=profile):
                    results.append({
                        "id": f"highlight:{highlight.unique_id}",
                        "title": highlight.title or "Highlight",
                        "cover_url": highlight.cover_url or "",
                        "media_count": highlight.itemcount,
                    })
                return results
            except Exception as e:
                logger.debug(f"Instaloader highlight fallback error for {uid}: {e}")

        return []

    def fetch_highlight_stories(self, highlight_id: str) -> List[Dict[str, Any]]:
        """Multi-tier highlight story extraction: Mobile/Web v1 REST -> Desktop SSR -> GraphQL -> Instaloader."""
        numeric_id = str(highlight_id).replace("highlight:", "").strip()
        if not numeric_id:
            return []
        clean_id = f"highlight:{numeric_id}"
        encoded_id = requests.utils.quote(clean_id, safe="")
        referer = f"https://www.instagram.com/stories/highlights/{numeric_id}/"

        # Tier 1: Mobile & Web v1 reels_media
        v1_candidates = (
            [
                (f"https://i.instagram.com/api/v1/feed/reels_media/?reel_ids={encoded_id}", IG_ANDROID_APP_UA),
                (f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids={encoded_id}", DESKTOP_UA),
            ]
            if self.has_session
            else [(f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids={encoded_id}", DESKTOP_UA)]
        )
        for v1_url, ua in v1_candidates:
            try:
                headers = dict(self.session.headers)
                headers["User-Agent"] = ua
                headers["Referer"] = referer
                resp = self.session.get(v1_url, headers=headers, timeout=15)
                if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                    stories = parse_reels_media_json(resp.json(), reel_key=clean_id)
                    if stories:
                        return stories
            except Exception as e:
                logger.debug(f"v1 reels_media failed ({v1_url}): {e}")

        # Tier 2: Desktop SSR on /stories/highlights/<numeric_id>/
        try:
            doc_headers = {
                "User-Agent": DESKTOP_UA,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9",
                "Sec-Fetch-Dest": "document",
                "Sec-Fetch-Mode": "navigate",
                "Sec-Fetch-Site": "none",
            }
            resp = (
                self.session.get(referer, headers=doc_headers, timeout=15)
                if self.has_session
                else requests.get(referer, headers=doc_headers, timeout=15)
            )
            if resp.status_code == 200 and resp.text:
                stories = parse_stories_from_ssr_html(resp.text)
                if stories:
                    return stories
        except Exception as e:
            logger.debug(f"SSR highlight stories failed for {clean_id}: {e}")

        # Tier 3: Legacy GraphQL query_hash
        url = f"https://www.instagram.com/graphql/query/?query_hash=452461bc5c9e67e8280862c4335b390c&variables={requests.utils.quote(json.dumps({'highlight_reel_ids': [clean_id]}), safe='')}"
        self.session.headers["Referer"] = referer
        try:
            resp = self.session.get(url, timeout=15)
            if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
                stories = parse_reels_media_json(resp.json(), reel_key=clean_id)
                if stories:
                    return stories
        except Exception as e:
            logger.debug(f"Highlight stories GraphQL failed for {clean_id}: {e}")

        return []
