#!/usr/bin/env python3
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import io
import json
import os
import sys
import tempfile
import threading
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional
import requests

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

try:
    from engine.instagram_client import InstagramClient, parse_cookie_text
    from engine.providers import PythonProviderOrchestrator
except ImportError:
    from instagram_client import InstagramClient, parse_cookie_text
    from providers import PythonProviderOrchestrator


def build_netscape_cookie_tempfile(session_path: Optional[str]) -> Optional[Path]:
    """Convert raw/JSON session cookie file into a temporary Netscape cookies.txt for yt-dlp."""
    if not session_path or not os.path.exists(session_path):
        return None
    try:
        with open(session_path, "r", encoding="utf-8") as f:
            content = f.read().strip()
        if content.startswith("# Netscape HTTP Cookie File"):
            return Path(session_path)
        cookies = parse_cookie_text(content)
        if not cookies:
            return None
        fd, tmp_name = tempfile.mkstemp(prefix="ig_cookies_", suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            out.write("# Netscape HTTP Cookie File\n")
            for k, v in cookies.items():
                out.write(f".instagram.com\tTRUE\t/\tTRUE\t2147483647\t{k}\t{v}\n")
        return Path(tmp_name)
    except Exception:
        return None


def sanitize_identifier(val: Any) -> str:
    """Sanitize identifiers to prevent directory traversal attacks."""
    s = str(val or "")
    sanitized = "".join(c for c in s if c.isalnum() or c in "._-")
    return sanitized.strip("._-") or "unknown"


def extract_avatar_asset_key(url: str) -> str:
    """Extract asset path from CDN URL stripping query parameters."""
    if not url:
        return ""
    try:
        parsed = urllib.parse.urlparse(url)
        return parsed.path
    except Exception:
        return url.split("?")[0]


AVATAR_DHASH_THRESHOLD = 8


def compute_avatar_dhash(img_bytes: bytes) -> Optional[str]:
    """Compute a 64-bit perceptual difference hash (dHash) over a 9x8 grayscale thumbnail."""
    if not img_bytes:
        return None
    try:
        from PIL import Image
        with Image.open(io.BytesIO(img_bytes)) as img:
            gray = img.convert("L").resize((9, 8), Image.Resampling.LANCZOS)
            pixels = list(gray.getdata())
        bits = 0
        for row in range(8):
            offset = row * 9
            for col in range(8):
                bits = (bits << 1) | (1 if pixels[offset + col] > pixels[offset + col + 1] else 0)
        return f"dhash:{bits:016x}"
    except Exception:
        return None


def dhash_hamming_distance(h1: Optional[str], h2: Optional[str]) -> Optional[int]:
    if not h1 or not h2:
        return None
    if not h1.startswith("dhash:") or not h2.startswith("dhash:"):
        return None
    try:
        v1 = int(h1.split(":", 1)[1], 16)
        v2 = int(h2.split(":", 1)[1], 16)
        return (v1 ^ v2).bit_count()
    except Exception:
        return None


def sync_profile_avatar(profile: Dict[str, Any], session: Optional[requests.Session] = None) -> Optional[str]:
    """Download avatar and preserve historical copies only when visually changed (dHash deduplication)."""
    url = profile.get("profile_pic_url")
    target_id = profile.get("id")
    if not url or not target_id:
        return None

    safe_target_id = sanitize_identifier(target_id)
    profile_dir = os.path.join("archives", safe_target_id, "profile")
    os.makedirs(profile_dir, exist_ok=True)
    key_file = os.path.join(profile_dir, ".avatar_key")
    current_path = os.path.join(profile_dir, "avatar_current.jpg")
    asset_key = extract_avatar_asset_key(url)

    stored_asset_key = ""
    stored_hash = ""
    stored_rel = ""
    stored_sha = ""
    if os.path.exists(key_file):
        try:
            with open(key_file, "r", encoding="utf-8") as kf:
                parts = kf.read().strip().split("||")
                if len(parts) >= 3:
                    stored_asset_key = parts[0]
                    stored_hash = parts[1]
                    stored_rel = parts[2]
                    stored_sha = parts[3] if len(parts) >= 4 else ""
                    if stored_asset_key == asset_key and os.path.exists(stored_rel):
                        profile["avatar_hash"] = stored_hash
                        return stored_rel
        except Exception:
            pass

    # Compute baseline dHash from existing avatar_current.jpg if stored hash is legacy sha256
    baseline_dhash = stored_hash if stored_hash.startswith("dhash:") else None
    if baseline_dhash is None and os.path.exists(current_path):
        try:
            with open(current_path, "rb") as ef:
                baseline_dhash = compute_avatar_dhash(ef.read())
        except Exception:
            baseline_dhash = None

    # Download candidate avatar bytes
    sess = session or requests.Session()
    try:
        r = sess.get(url, timeout=15)
        if r.status_code == 200 and len(r.content) > 0:
            raw_sha = hashlib.sha256(r.content).hexdigest()
            cand_dhash = compute_avatar_dhash(r.content)
            effective_hash = cand_dhash or raw_sha

            # 1. Exact SHA-256 match against previous avatar
            same_bytes = bool(stored_sha and raw_sha == stored_sha) or (stored_hash == raw_sha)
            # 2. Perceptual visual match (CDN URL rotation / JPEG re-encoding immunity)
            dist = dhash_hamming_distance(baseline_dhash, cand_dhash)
            same_visual = dist is not None and dist <= AVATAR_DHASH_THRESHOLD

            if (same_bytes or same_visual) and stored_rel and os.path.exists(stored_rel):
                stable_hash = baseline_dhash or effective_hash
                profile["avatar_hash"] = stable_hash
                try:
                    with open(key_file, "w", encoding="utf-8") as kf:
                        kf.write(f"{asset_key}||{stable_hash}||{stored_rel}||{raw_sha}")
                except Exception:
                    pass
                return stored_rel

            # Visually distinct new avatar -> persist historical copy
            now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
            history_filename = f"avatar_{now_str}.jpg"
            history_path = os.path.join(profile_dir, history_filename)

            with open(history_path, "wb") as f:
                f.write(r.content)
            with open(current_path, "wb") as f:
                f.write(r.content)

            profile["avatar_hash"] = effective_hash
            rel_path = f"archives/{safe_target_id}/profile/{history_filename}"
            with open(key_file, "w", encoding="utf-8") as kf:
                kf.write(f"{asset_key}||{effective_hash}||{rel_path}||{raw_sha}")

            return rel_path
    except Exception as e:
        sys.stderr.write(f"Avatar sync error: {e}\n")

    if stored_rel and os.path.exists(stored_rel):
        if baseline_dhash or stored_hash:
            profile["avatar_hash"] = baseline_dhash or stored_hash
        return stored_rel
    if os.path.exists(current_path):
        if baseline_dhash:
            profile["avatar_hash"] = baseline_dhash
        return f"archives/{safe_target_id}/profile/avatar_current.jpg"
    return None


def parse_profile_metadata(user_data: Dict[str, Any]) -> Dict[str, Any]:
    """Extract normalized profile fields from Instagram user object."""
    media_edge = user_data.get("edge_owner_to_timeline_media") or {}
    media_count = media_edge.get("count") if media_edge.get("count") is not None else user_data.get("media_count", 0)
    followed_by = user_data.get("edge_followed_by") or {}
    followers_count = followed_by.get("count") if followed_by.get("count") is not None else user_data.get("follower_count", 0)
    follow = user_data.get("edge_follow") or {}
    following_count = follow.get("count") if follow.get("count") is not None else user_data.get("following_count", 0)
    profile_pic = (
        (user_data.get("hd_profile_pic_url_info") or {}).get("url")
        or user_data.get("profile_pic_url_hd")
        or user_data.get("profile_pic_url")
        or ""
    )
    latest_reel_sec = int(user_data.get("latest_reel_media") or 0)
    latest_story_at = latest_reel_sec * 1000 if latest_reel_sec > 0 and latest_reel_sec < 10_000_000_000 else latest_reel_sec

    prof = {
        "id": str(user_data.get("pk") or user_data.get("id") or ""),
        "username": str(user_data.get("username", "")),
        "full_name": str(user_data.get("full_name", "")),
        "biography": str(user_data.get("biography", "")),
        "is_private": bool(user_data.get("is_private", False)),
        "followed_by_viewer": bool(user_data.get("followed_by_viewer", False)),
        "media_count": int(media_count or 0),
        "followers_count": int(followers_count or 0),
        "following_count": int(following_count or 0),
        "latest_story_at": int(latest_story_at),
        "profile_pic_url": str(profile_pic),
    }

    raw_state = f"{prof['username']}|{prof['full_name']}|{prof['biography']}|{prof['is_private']}|{prof['followers_count']}|{prof['following_count']}|{prof['media_count']}"
    prof["profile_hash"] = hashlib.sha256(raw_state.encode("utf-8")).hexdigest()
    return prof


def parse_posts_blitz(node: Dict[str, Any]) -> Dict[str, Any]:
    """Extract normalized media metadata and signed CDN URL from a post/reel node."""
    shortcode = str(node.get("shortcode") or node.get("code") or node.get("id") or "")
    vids = node.get("video_versions") or []
    imgs = (node.get("image_versions2") or {}).get("candidates") or []
    is_video = bool(node.get("is_video", False)) or node.get("media_type") == 2 or bool(vids)
    media_type = "reel" if is_video else "post"

    video_url = node.get("video_url") or (vids[0].get("url") if vids and isinstance(vids[0], dict) else "")
    display_url = (
        node.get("display_url")
        or node.get("display_uri")
        or node.get("thumbnail_src")
        or (imgs[0].get("url") if imgs and isinstance(imgs[0], dict) else "")
        or ""
    )
    cdn_url = video_url if (is_video and video_url) else display_url

    caption = ""
    cap_obj = node.get("caption")
    if isinstance(cap_obj, dict) and cap_obj.get("text"):
        caption = str(cap_obj.get("text") or "")
    else:
        caption_edges = (node.get("edge_media_to_caption") or {}).get("edges", [])
        if caption_edges and isinstance(caption_edges, list):
            caption = str(caption_edges[0].get("node", {}).get("text", "") or "")

    timestamp = int(node.get("taken_at_timestamp") or node.get("taken_at") or int(time.time()))

    # Extract all carousel slides when present (carousel_media or edge_sidecar_to_children)
    slides: List[Dict[str, Any]] = []
    carousel_raw = node.get("carousel_media") or []
    sidecar_edges = (node.get("edge_sidecar_to_children") or {}).get("edges") or []
    if isinstance(carousel_raw, list) and len(carousel_raw) > 1:
        for idx, cm in enumerate(carousel_raw):
            if not isinstance(cm, dict):
                continue
            cm_vids = cm.get("video_versions") or []
            cm_imgs = (cm.get("image_versions2") or {}).get("candidates") or []
            cm_is_vid = bool(cm_vids) or cm.get("media_type") == 2
            cm_url = (cm_vids[0].get("url") if cm_vids and isinstance(cm_vids[0], dict) else "") or (
                cm_imgs[0].get("url") if cm_imgs and isinstance(cm_imgs[0], dict) else ""
            )
            if cm_url:
                slides.append({
                    "position": idx,
                    "media_type": "video" if cm_is_vid else "image",
                    "cdn_url": cm_url,
                })
    elif isinstance(sidecar_edges, list) and len(sidecar_edges) > 1:
        for idx, edge in enumerate(sidecar_edges):
            sn = edge.get("node") if isinstance(edge, dict) else None
            if not isinstance(sn, dict):
                continue
            sn_is_vid = bool(sn.get("is_video", False))
            sn_url = (sn.get("video_url") if sn_is_vid else None) or sn.get("display_url") or sn.get("display_uri") or ""
            if sn_url:
                slides.append({
                    "position": idx,
                    "media_type": "video" if sn_is_vid else "image",
                    "cdn_url": sn_url,
                })

    return {
        "id": f"{media_type}_{shortcode}",
        "shortcode": shortcode,
        "media_type": media_type,
        "cdn_url": cdn_url,
        "caption": caption,
        "posted_at": timestamp,
        "slides": slides,
        "dimensions": {
            "height": (node.get("dimensions") or {}).get("height", 0),
            "width": (node.get("dimensions") or {}).get("width", 0),
        },
    }


def action_check_profile(target: str, session_path: Optional[str]):
    client = InstagramClient(session_path)
    res = client.fetch_web_profile(target)
    if res.get("status") != "ok":
        print(json.dumps(res))
        return

    user_data = res.get("data", {}).get("user")
    if not user_data:
        print(json.dumps({"status": "error", "message": "User data not found in response"}))
        return

    profile = parse_profile_metadata(user_data)
    avatar_rel = sync_profile_avatar(profile, client.session)
    profile["profile_pic_path"] = avatar_rel
    print(json.dumps({"status": "ok", "profile": profile}))


def action_archive_blitz(target: str, output_dir: str, session_path: Optional[str], limit: int = 24):
    """Phase 1 Blitz: Quickly extract all media metadata & CDN URLs without waiting for downloads."""
    client = InstagramClient(session_path)
    res = client.fetch_web_profile(target)
    if res.get("status") != "ok":
        print(json.dumps(res))
        return

    user_data = res.get("data", {}).get("user", {})
    profile = parse_profile_metadata(user_data)
    avatar_rel = sync_profile_avatar(profile, client.session)
    profile["profile_pic_path"] = avatar_rel

    effective_limit = 500 if limit <= 0 else max(1, min(limit, 500))
    timeline_obj = user_data.get("edge_owner_to_timeline_media") or {}
    media_edges = list(timeline_obj.get("edges") or [])
    initial_end_cursor = (timeline_obj.get("page_info") or {}).get("end_cursor")
    expected_posts = int(profile.get("media_count") or 0)
    desired_posts = min(effective_limit, expected_posts) if expected_posts > 0 else effective_limit

    if (not media_edges or len(media_edges) < desired_posts) and (
        not profile.get("is_private") or profile.get("followed_by_viewer")
    ):
        already_codes = {
            str((e.get("node") or {}).get("shortcode") or (e.get("node") or {}).get("code") or "").strip()
            for e in media_edges
            if isinstance(e, dict)
        }
        already_codes.discard("")
        extra_edges = client.fetch_user_posts(
            profile["username"],
            profile.get("id"),
            limit=desired_posts,
            initial_end_cursor=initial_end_cursor,
            already_seen=already_codes,
        )
        if extra_edges:
            media_edges = media_edges + list(extra_edges)

    items = []
    seen_shortcodes = set()

    for edge in media_edges:
        if len(items) >= effective_limit:
            break
        node = edge.get("node", {})
        item = parse_posts_blitz(node)
        if not item["shortcode"] or item["shortcode"] in seen_shortcodes:
            continue
        seen_shortcodes.add(item["shortcode"])
        item["target_id"] = profile["id"]
        item["relative_file_path"] = f"archives/{profile['id']}/{item['media_type']}s/{item['shortcode']}.{'mp4' if item['media_type'] == 'reel' else 'jpg'}"
        items.append(item)

    # Extract active 24h stories and highlights when profile is public or followed by viewer
    stories_authoritative = False
    highlights_authoritative = False
    if not profile.get("is_private") or profile.get("followed_by_viewer"):
        try:
            active_stories = client.fetch_active_user_stories(profile["id"], profile["username"])
            stories_authoritative = bool(getattr(client, "last_stories_authoritative", False)) or bool(active_stories)
            for story in active_stories:
                story_id = sanitize_identifier(story.get("id") or str(int(time.time())))
                ext = "mp4" if story.get("media_type") == "video" else "jpg"
                items.append({
                    "id": f"story_{story_id}",
                    "shortcode": story_id,
                    "media_type": "story",
                    "highlight_title": None,
                    "cdn_url": story.get("cdn_url") or "",
                    "caption": f"Story: @{profile['username']}",
                    "posted_at": story.get("taken_at") or int(time.time()),
                    "target_id": profile["id"],
                    "relative_file_path": f"archives/{profile['id']}/stories/{story_id}.{ext}",
                    "dimensions": story.get("dimensions", {}),
                })
        except Exception as e:
            sys.stderr.write(f"Active story blitz error: {e}\n")

        try:
            hl_trays = client.fetch_user_highlights(
                profile["id"],
                username=profile["username"],
                is_private=bool(profile.get("is_private", False)),
            )
            highlights_authoritative = bool(getattr(client, "last_highlights_authoritative", False))
            for tray in hl_trays:
                tray_title_raw = str(tray.get("title") or "Highlight").strip() or "Highlight"
                tray_title = sanitize_identifier(tray_title_raw)
                stories = client.fetch_highlight_stories(tray["id"])
                for story in stories:
                    story_id = sanitize_identifier(story.get("id") or str(int(time.time())))
                    ext = "mp4" if story.get("media_type") == "video" else "jpg"
                    items.append({
                        "id": f"highlight_{story_id}",
                        "shortcode": story_id,
                        "media_type": "highlight",
                        "highlight_title": tray_title_raw,
                        "cdn_url": story.get("cdn_url") or "",
                        "caption": f"Highlight: {tray_title_raw}",
                        "posted_at": story.get("taken_at") or int(time.time()),
                        "target_id": profile["id"],
                        "relative_file_path": f"archives/{profile['id']}/highlights/{tray_title}/{story_id}.{ext}",
                        "dimensions": story.get("dimensions", {}),
                    })
        except Exception as e:
            sys.stderr.write(f"Highlight blitz error: {e}\n")

    print(json.dumps({
        "status": "ok",
        "profile": profile,
        "items": items,
        "count": len(items),
        "stories_authoritative": stories_authoritative,
        "highlights_authoritative": highlights_authoritative,
    }))


def _hydrate_one_item(
    orchestrator: PythonProviderOrchestrator,
    target_id: str,
    output_dir: str,
    item: Dict[str, Any],
    cookie_path: Optional[Path],
    sha_index: Dict[str, str],
    sha_lock: threading.Lock,
) -> Dict[str, Any]:
    t0 = time.monotonic()
    cdn_url = item.get("cdn_url") or ""
    shortcode = item.get("shortcode") or item.get("id") or "item"
    media_type = item.get("media_type") or "post"
    item_id = item.get("id") or f"{media_type}_{shortcode}"
    rel_path = item.get("relative_file_path", "")
    dest_path = Path(os.path.join(output_dir, rel_path.replace(f"archives/{target_id}/", "")))
    dest_path.parent.mkdir(parents=True, exist_ok=True)

    # Check if either target path or alternate extension already exists
    existing_file: Optional[Path] = None
    for cand in (dest_path, dest_path.with_suffix(".mp4"), dest_path.with_suffix(".jpg")):
        if cand.exists() and cand.stat().st_size > 0:
            existing_file = cand
            break

    provider_used = "cached"
    asset_records: List[Dict[str, Any]] = []
    ok = True
    actual_file: Optional[Path] = existing_file

    if existing_file is None:
        with sha_lock:
            local_sha = dict(sha_index)
        ok, provider_used, asset_records = orchestrator.hydrate_item(
            shortcode=shortcode,
            media_type_hint=media_type,
            dest_path=dest_path,
            cdn_url=cdn_url,
            cookie_file=cookie_path,
            sha_index=local_sha,
        )
        if ok:
            with sha_lock:
                sha_index.update(local_sha)
            if asset_records:
                actual_file = Path(asset_records[0]["file_path"])
        else:
            actual_file = None

    file_size = actual_file.stat().st_size if (actual_file and actual_file.exists()) else 0
    elapsed = max(time.monotonic() - t0, 0.01)
    speed_bps = int(file_size / elapsed) if (file_size > 0 and provider_used != "cached") else 0

    actual_rel = rel_path
    if actual_file and actual_file.exists():
        try:
            rel_from_cwd = os.path.relpath(str(actual_file), os.getcwd()).replace("\\", "/")
            actual_rel = rel_from_cwd
        except Exception:
            actual_rel = str(actual_file).replace("\\", "/")

    # Save sidecar caption & metadata
    if ok and actual_file:
        base_no_ext = os.path.splitext(str(actual_file))[0]
        caption = item.get("caption", "")
        if caption:
            try:
                with open(f"{base_no_ext}_caption.txt", "w", encoding="utf-8") as f_cap:
                    f_cap.write(caption)
            except Exception:
                pass
        enriched_meta = dict(item)
        enriched_meta["provider"] = provider_used
        enriched_meta["relative_file_path"] = actual_rel
        if asset_records:
            enriched_meta["assets"] = asset_records
        try:
            with open(f"{base_no_ext}_meta.json", "w", encoding="utf-8") as f_meta:
                json.dump(enriched_meta, f_meta, indent=2, ensure_ascii=False)
        except Exception:
            pass

    return {
        "ok": ok,
        "id": item_id,
        "shortcode": shortcode,
        "media_type": media_type,
        "provider": provider_used,
        "actual_path": actual_rel,
        "file_size": file_size,
        "speed_bps": speed_bps,
        "error": None if ok else provider_used,
    }


def action_hydrate_single(
    target_id: str,
    item_id: str,
    shortcode: str,
    media_type: str,
    cdn_url: str,
    rel_path: str,
    output_dir: str,
    caption: str = "",
    cookie_file: Optional[str] = None,
    quality: str = "BEST",
    yt_dlp_enabled: bool = True,
):
    """Hydrate a single media item cleanly (used by Rust parallel DownloadManager)."""
    tmp_cookie = build_netscape_cookie_tempfile(cookie_file)
    try:
        orchestrator = PythonProviderOrchestrator(
            yt_dlp_enabled=yt_dlp_enabled,
            quality_preset=quality,
        )
        sha_index_file = Path(output_dir) / ".sha256_index.json"
        sha_index: Dict[str, str] = {}
        if sha_index_file.exists():
            try:
                sha_index = json.loads(sha_index_file.read_text(encoding="utf-8"))
            except Exception:
                sha_index = {}

        res = _hydrate_one_item(
            orchestrator=orchestrator,
            target_id=target_id,
            output_dir=output_dir,
            item={
                "id": item_id,
                "shortcode": shortcode,
                "media_type": media_type,
                "cdn_url": cdn_url,
                "relative_file_path": rel_path,
                "caption": caption,
            },
            cookie_path=tmp_cookie,
            sha_index=sha_index,
            sha_lock=threading.Lock(),
        )
        try:
            sha_index_file.parent.mkdir(parents=True, exist_ok=True)
            sha_index_file.write_text(json.dumps(sha_index, indent=2), encoding="utf-8")
        except Exception:
            pass
        print(json.dumps({"status": "ok" if res["ok"] else "error", **res}), flush=True)
    finally:
        if tmp_cookie and cookie_file and str(tmp_cookie) != str(cookie_file):
            try:
                tmp_cookie.unlink(missing_ok=True)
            except Exception:
                pass


def action_hydrate_assets(
    target_id: str,
    input_json: str,
    output_dir: str,
    cookie_file: Optional[str] = None,
    workers: int = 4,
    quality: str = "BEST",
    yt_dlp_enabled: bool = True,
):
    """Phase 2 Hydration: Parallel Multi-Provider download (yt-dlp + gallery-dl + HTTP) with SHA-256 deduplication."""
    if not os.path.exists(input_json):
        print(json.dumps({"status": "error", "message": f"Input file not found: {input_json}"}))
        return

    with open(input_json, "r", encoding="utf-8") as f:
        data = json.load(f)

    items = data.get("items", [])
    total = len(items)
    downloaded = 0
    total_bytes = 0
    max_workers = max(1, min(int(workers or 4), 10))

    tmp_cookie = build_netscape_cookie_tempfile(cookie_file)
    try:
        orchestrator = PythonProviderOrchestrator(
            yt_dlp_enabled=yt_dlp_enabled,
            quality_preset=quality,
        )
        sha_index_file = Path(output_dir) / ".sha256_index.json"
        sha_index: Dict[str, str] = {}
        if sha_index_file.exists():
            try:
                sha_index = json.loads(sha_index_file.read_text(encoding="utf-8"))
            except Exception:
                sha_index = {}
        sha_lock = threading.Lock()
        completed_count = 0

        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            futures = [
                pool.submit(
                    _hydrate_one_item,
                    orchestrator,
                    target_id,
                    output_dir,
                    item,
                    tmp_cookie,
                    sha_index,
                    sha_lock,
                )
                for item in items
            ]
            for fut in as_completed(futures):
                completed_count += 1
                res = fut.result()
                if res["ok"]:
                    downloaded += 1
                    total_bytes += res["file_size"]
                    print(json.dumps({
                        "status": "progress",
                        "current": completed_count,
                        "total": total,
                        "id": res["id"],
                        "shortcode": res["shortcode"],
                        "media_type": res["media_type"],
                        "provider": res["provider"],
                        "actual_path": res["actual_path"],
                        "file_size": res["file_size"],
                        "speed_bps": res["speed_bps"],
                    }), flush=True)
                else:
                    print(json.dumps({
                        "status": "warning",
                        "current": completed_count,
                        "total": total,
                        "id": res["id"],
                        "shortcode": res["shortcode"],
                        "message": f"Hydration failed for {res['shortcode']}: {res['error']}",
                    }), flush=True)

        try:
            sha_index_file.parent.mkdir(parents=True, exist_ok=True)
            sha_index_file.write_text(json.dumps(sha_index, indent=2), encoding="utf-8")
        except Exception:
            pass

        print(json.dumps({
            "status": "completed",
            "total_items": total,
            "downloaded_items": downloaded,
            "bytes_downloaded": total_bytes,
        }), flush=True)
    finally:
        if tmp_cookie and cookie_file and str(tmp_cookie) != str(cookie_file):
            try:
                tmp_cookie.unlink(missing_ok=True)
            except Exception:
                pass


def action_fetch_relationships(
    target: str,
    target_id: Optional[str],
    session_path: Optional[str],
    limit: int = 500,
):
    """Fetch paginated followers and following lists for a target profile."""
    client = InstagramClient(session_path)
    uid = str(target_id or "").strip()
    if not uid.isdigit():
        res = client.fetch_web_profile(target)
        if res.get("status") == "ok":
            uid = str((res.get("data") or {}).get("user", {}).get("id") or "")
    if not uid.isdigit():
        print(json.dumps({
            "status": "error",
            "message": "Numeric user ID could not be resolved",
            "followers": [],
            "following": [],
            "has_session": client.has_session,
        }))
        return

    followers = client.fetch_followers_list(uid, username=target, limit=limit)
    following = client.fetch_following_list(uid, username=target, limit=limit)
    print(json.dumps({
        "status": "ok",
        "target_id": uid,
        "has_session": client.has_session,
        "followers": followers,
        "following": following,
    }))


def action_fetch_comments(
    shortcode: str,
    session_path: Optional[str],
    limit: int = 200,
):
    """Extract threaded comments for a post/reel via comments_extractor."""
    try:
        try:
            from engine.comments_extractor import extract_comments_sync
        except ImportError:
            from comments_extractor import extract_comments_sync
        cookies: Dict[str, str] = {}
        if session_path and os.path.exists(session_path):
            with open(session_path, "r", encoding="utf-8", errors="ignore") as f:
                cookies = parse_cookie_text(f.read())
        records = extract_comments_sync(shortcode=shortcode, cookies=cookies, max_comments=limit)
        out = [
            {
                "id": r.comment_id,
                "post_id": r.shortcode,
                "parent_comment_id": r.parent_comment_id,
                "author_username": r.author_username,
                "author_full_name": r.author_full_name,
                "is_verified": bool(r.is_verified),
                "text": r.text,
                "like_count": int(r.like_count),
                "reply_count": int(r.reply_count),
                "created_at": r.created_at,
                "collection_status": r.collection_status,
            }
            for r in records
        ]
        print(json.dumps({"status": "ok", "shortcode": shortcode, "comments": out, "count": len(out)}))
    except Exception as e:
        print(json.dumps({"status": "error", "message": str(e), "comments": [], "count": 0}))


def action_check_highlights(target: str, output_dir: str, session_path: Optional[str]):
    """Traverse and extract highlight collections."""
    client = InstagramClient(session_path)
    try:
        profile = instaloader.Profile.from_username(client.il.context, target)
        highlights_data = []

        for highlight in client.il.get_highlights(user=profile):
            title = highlight.title.replace("/", "_").replace("\\", "_")
            items = []
            for item in highlight.get_items():
                items.append({
                    "id": f"highlight_{item.mediaid}",
                    "shortcode": item.shortcode,
                    "media_type": "highlight",
                    "highlight_title": title,
                    "cdn_url": item.video_url if item.is_video else item.url,
                    "posted_at": int(item.date_utc.replace(tzinfo=timezone.utc).timestamp()),
                    "relative_file_path": f"archives/{profile.userid}/highlights/{title}/{item.date_utc.strftime('%Y-%m-%d_%H-%M-%S')}_{item.shortcode}.{'mp4' if item.is_video else 'jpg'}"
                })
            highlights_data.append({
                "title": title,
                "count": len(items),
                "items": items
            })

        print(json.dumps({
            "status": "ok",
            "highlights": highlights_data,
            "total_highlights": len(highlights_data)
        }))
    except Exception as e:
        print(json.dumps({"status": "error", "message": f"Failed to fetch highlights: {e}"}))


def main():
    parser = argparse.ArgumentParser(description="Instalogger Python Scraping & Archival Worker")
    subparsers = parser.add_subparsers(dest="action", required=True)

    p_check = subparsers.add_parser("check_profile")
    p_check.add_argument("--target", required=True, help="Target username")
    p_check.add_argument("--session", default=None, help="Session file path")

    p_blitz = subparsers.add_parser("archive_blitz")
    p_blitz.add_argument("--target", required=True, help="Target username")
    p_blitz.add_argument("--output-dir", default="archives", help="Storage output directory")
    p_blitz.add_argument("--session", default=None, help="Session file path")
    p_blitz.add_argument("--limit", type=int, default=24, help="Max posts limit (0=unlimited)")

    p_hydrate = subparsers.add_parser("hydrate_assets")
    p_hydrate.add_argument("--target-id", required=True, help="Target numeric Instagram user ID")
    p_hydrate.add_argument("--input-json", required=True, help="Path to JSON file containing blitz items")
    p_hydrate.add_argument("--output-dir", required=True, help="Target archives root directory")
    p_hydrate.add_argument("--cookies", default=None, help="Optional cookies file path")
    p_hydrate.add_argument("--workers", type=int, default=4, help="Parallel download workers")
    p_hydrate.add_argument("--quality", default="BEST", help="Video quality preset")
    p_hydrate.add_argument("--yt-dlp-enabled", default="true", help="Enable yt-dlp engine")

    p_single = subparsers.add_parser("hydrate_single")
    p_single.add_argument("--target-id", required=True)
    p_single.add_argument("--item-id", required=True)
    p_single.add_argument("--shortcode", required=True)
    p_single.add_argument("--media-type", required=True)
    p_single.add_argument("--cdn-url", default="")
    p_single.add_argument("--rel-path", required=True)
    p_single.add_argument("--output-dir", required=True)
    p_single.add_argument("--caption", default="")
    p_single.add_argument("--cookies", default=None)
    p_single.add_argument("--quality", default="BEST")
    p_single.add_argument("--yt-dlp-enabled", default="true")

    p_hl = subparsers.add_parser("check_highlights")
    p_hl.add_argument("--target", required=True, help="Target username")
    p_hl.add_argument("--output-dir", default="archives", help="Storage output directory")
    p_hl.add_argument("--session", default=None, help="Session file path")

    p_rel = subparsers.add_parser("fetch_relationships")
    p_rel.add_argument("--target", required=True, help="Target username")
    p_rel.add_argument("--target-id", default=None, help="Numeric Instagram user ID")
    p_rel.add_argument("--session", default=None, help="Session file path")
    p_rel.add_argument("--limit", type=int, default=500, help="Max users per list")

    p_com = subparsers.add_parser("fetch_comments")
    p_com.add_argument("--shortcode", required=True, help="Post/Reel shortcode")
    p_com.add_argument("--session", default=None, help="Session file path")
    p_com.add_argument("--limit", type=int, default=200, help="Max comments")

    args = parser.parse_args()

    if args.action == "check_profile":
        action_check_profile(args.target, args.session)
    elif args.action == "archive_blitz":
        action_archive_blitz(args.target, args.output_dir, args.session, args.limit)
    elif args.action == "hydrate_assets":
        action_hydrate_assets(
            args.target_id,
            args.input_json,
            args.output_dir,
            args.cookies,
            args.workers,
            args.quality,
            str(args.yt_dlp_enabled).lower() != "false",
        )
    elif args.action == "hydrate_single":
        action_hydrate_single(
            target_id=args.target_id,
            item_id=args.item_id,
            shortcode=args.shortcode,
            media_type=args.media_type,
            cdn_url=args.cdn_url,
            rel_path=args.rel_path,
            output_dir=args.output_dir,
            caption=args.caption,
            cookie_file=args.cookies,
            quality=args.quality,
            yt_dlp_enabled=str(args.yt_dlp_enabled).lower() != "false",
        )
    elif args.action == "check_highlights":
        action_check_highlights(args.target, args.output_dir, args.session)
    elif args.action == "fetch_relationships":
        action_fetch_relationships(args.target, args.target_id, args.session, args.limit)
    elif args.action == "fetch_comments":
        action_fetch_comments(args.shortcode, args.session, args.limit)


if __name__ == "__main__":
    main()
