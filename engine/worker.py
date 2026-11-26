#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
import requests

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

try:
    from engine.instagram_client import InstagramClient
    from engine.providers import PythonProviderOrchestrator
except ImportError:
    from instagram_client import InstagramClient
    from providers import PythonProviderOrchestrator


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


def sync_profile_avatar(profile: Dict[str, Any], session: Optional[requests.Session] = None) -> Optional[str]:
    """Download avatar and preserve historical copies."""
    url = profile.get("profile_pic_url")
    target_id = profile.get("id")
    if not url or not target_id:
        return None

    safe_target_id = sanitize_identifier(target_id)
    profile_dir = os.path.join("archives", safe_target_id, "profile")
    os.makedirs(profile_dir, exist_ok=True)
    key_file = os.path.join(profile_dir, ".avatar_key")
    asset_key = extract_avatar_asset_key(url)

    # Check existing key and file
    if os.path.exists(key_file):
        try:
            with open(key_file, "r", encoding="utf-8") as kf:
                parts = kf.read().strip().split("||")
                if len(parts) >= 2 and parts[0] == asset_key:
                    saved_rel = parts[-1]
                    if os.path.exists(saved_rel):
                        if len(parts) >= 3:
                            profile["avatar_hash"] = parts[1]
                        return saved_rel
        except Exception:
            pass

    # Download new avatar
    sess = session or requests.Session()
    try:
        r = sess.get(url, timeout=15)
        if r.status_code == 200 and len(r.content) > 0:
            now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
            history_filename = f"avatar_{now_str}.jpg"
            history_path = os.path.join(profile_dir, history_filename)
            current_path = os.path.join(profile_dir, "avatar_current.jpg")

            with open(history_path, "wb") as f:
                f.write(r.content)
            with open(current_path, "wb") as f:
                f.write(r.content)

            avatar_hash = hashlib.sha256(r.content).hexdigest()
            profile["avatar_hash"] = avatar_hash

            rel_path = f"archives/{safe_target_id}/profile/{history_filename}"
            with open(key_file, "w", encoding="utf-8") as kf:
                kf.write(f"{asset_key}||{avatar_hash}||{rel_path}")

            return rel_path
    except Exception as e:
        sys.stderr.write(f"Avatar sync error: {e}\n")

    current_path = os.path.join(profile_dir, "avatar_current.jpg")
    if os.path.exists(current_path):
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

    return {
        "id": f"{media_type}_{shortcode}",
        "shortcode": shortcode,
        "media_type": media_type,
        "cdn_url": cdn_url,
        "caption": caption,
        "posted_at": timestamp,
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


def action_archive_blitz(target: str, output_dir: str, session_path: Optional[str]):
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

    media_edges = (user_data.get("edge_owner_to_timeline_media") or {}).get("edges", [])
    if not media_edges and (not profile.get("is_private") or profile.get("followed_by_viewer")):
        media_edges = client.fetch_user_posts(profile["username"], profile.get("id"), limit=24)

    items = []
    seen_shortcodes = set()

    for edge in media_edges:
        node = edge.get("node", {})
        item = parse_posts_blitz(node)
        if not item["shortcode"] or item["shortcode"] in seen_shortcodes:
            continue
        seen_shortcodes.add(item["shortcode"])
        item["target_id"] = profile["id"]
        item["relative_file_path"] = f"archives/{profile['id']}/{item['media_type']}s/{item['shortcode']}.{'mp4' if item['media_type'] == 'reel' else 'jpg'}"
        items.append(item)

    # When profile is active/public or followed by viewer, also extract all highlights
    if not profile.get("is_private") or profile.get("followed_by_viewer"):
        try:
            hl_trays = client.fetch_user_highlights(profile["id"], username=profile["username"])
            for tray in hl_trays:
                tray_title = sanitize_identifier(tray.get("title") or "Highlight")
                stories = client.fetch_highlight_stories(tray["id"])
                for story in stories:
                    story_id = story.get("id") or str(int(time.time()))
                    ext = "mp4" if story.get("media_type") == "video" else "jpg"
                    items.append({
                        "id": f"highlight_{story_id}",
                        "shortcode": story_id,
                        "media_type": "highlight",
                        "highlight_title": tray.get("title") or "Highlight",
                        "cdn_url": story.get("cdn_url") or "",
                        "caption": f"Highlight: {tray.get('title')}",
                        "posted_at": story.get("taken_at") or int(time.time()),
                        "target_id": profile["id"],
                        "relative_file_path": f"archives/{profile['id']}/highlights/{tray_title}/{story_id}.{ext}",
                        "dimensions": story.get("dimensions", {})
                    })
        except Exception as e:
            sys.stderr.write(f"Highlight blitz error: {e}\n")

    print(json.dumps({
        "status": "ok",
        "profile": profile,
        "items": items,
        "count": len(items),
    }))


def action_hydrate_assets(target_id: str, input_json: str, output_dir: str, cookie_file: Optional[str] = None):
    """Phase 2 Hydration: Multi-Provider download (yt-dlp + gallery-dl + HTTP) with SHA-256 deduplication."""
    if not os.path.exists(input_json):
        print(json.dumps({"status": "error", "message": f"Input file not found: {input_json}"}))
        return

    with open(input_json, "r", encoding="utf-8") as f:
        data = json.load(f)

    items = data.get("items", [])
    total = len(items)
    downloaded = 0
    total_bytes = 0

    from pathlib import Path
    orchestrator = PythonProviderOrchestrator()
    cookie_path = Path(cookie_file) if cookie_file else None
    sha_index_file = Path(output_dir) / ".sha256_index.json"
    sha_index: Dict[str, str] = {}
    if sha_index_file.exists():
        try:
            sha_index = json.loads(sha_index_file.read_text(encoding="utf-8"))
        except Exception:
            sha_index = {}

    for idx, item in enumerate(items, 1):
        cdn_url = item.get("cdn_url") or ""
        shortcode = item.get("shortcode") or item.get("id") or f"item_{idx}"
        media_type = item.get("media_type") or "post"

        rel_path = item.get("relative_file_path", "")
        dest_path = Path(os.path.join(output_dir, rel_path.replace(f"archives/{target_id}/", "")))
        dest_path.parent.mkdir(parents=True, exist_ok=True)

        provider_used = "cached"
        asset_records: List[Dict[str, Any]] = []

        if not dest_path.exists() or dest_path.stat().st_size == 0:
            ok, provider_used, asset_records = orchestrator.hydrate_item(
                shortcode=shortcode,
                media_type_hint=media_type,
                dest_path=dest_path,
                cdn_url=cdn_url,
                cookie_file=cookie_path,
                sha_index=sha_index,
            )
            if ok:
                downloaded += 1
                total_bytes += sum(rec.get("file_size", 0) for rec in asset_records)
            else:
                print(json.dumps({
                    "status": "warning",
                    "message": f"Hydration failed for {shortcode}: {provider_used}"
                }))
        else:
            downloaded += 1

        # Save sidecar caption and normalized metadata
        base_no_ext = os.path.splitext(str(dest_path))[0]
        caption = item.get("caption", "")
        if caption:
            with open(f"{base_no_ext}_caption.txt", "w", encoding="utf-8") as f_cap:
                f_cap.write(caption)

        enriched_meta = dict(item)
        enriched_meta["provider"] = provider_used
        if asset_records:
            enriched_meta["assets"] = asset_records
        with open(f"{base_no_ext}_meta.json", "w", encoding="utf-8") as f_meta:
            json.dump(enriched_meta, f_meta, indent=2, ensure_ascii=False)

        print(json.dumps({
            "status": "progress",
            "current": idx,
            "total": total,
            "shortcode": shortcode,
            "media_type": media_type,
            "provider": provider_used,
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
        "bytes_downloaded": total_bytes
    }))


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

    p_hydrate = subparsers.add_parser("hydrate_assets")
    p_hydrate.add_argument("--target-id", required=True, help="Target numeric Instagram user ID")
    p_hydrate.add_argument("--input-json", required=True, help="Path to JSON file containing blitz items")
    p_hydrate.add_argument("--output-dir", required=True, help="Target archives root directory")
    p_hydrate.add_argument("--cookies", default=None, help="Optional Netscape cookies file path")

    p_hl = subparsers.add_parser("check_highlights")
    p_hl.add_argument("--target", required=True, help="Target username")
    p_hl.add_argument("--output-dir", default="archives", help="Storage output directory")
    p_hl.add_argument("--session", default=None, help="Session file path")

    args = parser.parse_args()

    if args.action == "check_profile":
        action_check_profile(args.target, args.session)
    elif args.action == "archive_blitz":
        action_archive_blitz(args.target, args.output_dir, args.session)
    elif args.action == "hydrate_assets":
        action_hydrate_assets(args.target_id, args.input_json, args.output_dir, args.cookies)
    elif args.action == "check_highlights":
        action_check_highlights(args.target, args.output_dir, args.session)


if __name__ == "__main__":
    main()
