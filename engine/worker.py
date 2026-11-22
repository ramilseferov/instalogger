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
except ImportError:
    from instagram_client import InstagramClient


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
    media_count = media_edge.get("count", 0)
    followed_by = user_data.get("edge_followed_by") or {}
    followers_count = followed_by.get("count", 0)
    follow = user_data.get("edge_follow") or {}
    following_count = follow.get("count", 0)
    profile_pic = user_data.get("profile_pic_url_hd") or user_data.get("profile_pic_url") or ""

    prof = {
        "id": str(user_data.get("id", "")),
        "username": str(user_data.get("username", "")),
        "full_name": str(user_data.get("full_name", "")),
        "biography": str(user_data.get("biography", "")),
        "is_private": bool(user_data.get("is_private", False)),
        "followed_by_viewer": bool(user_data.get("followed_by_viewer", False)),
        "media_count": int(media_count),
        "followers_count": int(followers_count),
        "following_count": int(following_count),
        "profile_pic_url": str(profile_pic),
    }

    raw_state = f"{prof['username']}|{prof['full_name']}|{prof['biography']}|{prof['is_private']}|{followers_count}|{following_count}|{media_count}"
    prof["profile_hash"] = hashlib.sha256(raw_state.encode("utf-8")).hexdigest()
    return prof


def parse_posts_blitz(node: Dict[str, Any]) -> Dict[str, Any]:
    """Extract normalized media metadata and signed CDN URL from a post/reel node."""
    shortcode = node.get("shortcode") or node.get("code") or node.get("id") or ""
    is_video = bool(node.get("is_video", False))
    media_type = "reel" if is_video else "post"

    cdn_url = node.get("video_url") if is_video else (node.get("display_url") or node.get("thumbnail_src") or "")
    if not cdn_url:
        cdn_url = node.get("display_url") or ""

    caption = ""
    caption_edges = node.get("edge_media_to_caption", {}).get("edges", [])
    if caption_edges and isinstance(caption_edges, list):
        caption = caption_edges[0].get("node", {}).get("text", "")

    timestamp = node.get("taken_at_timestamp") or node.get("taken_at") or int(time.time())

    return {
        "id": f"{media_type}_{shortcode}",
        "shortcode": shortcode,
        "media_type": media_type,
        "cdn_url": cdn_url,
        "caption": caption,
        "posted_at": timestamp,
        "dimensions": {
            "height": node.get("dimensions", {}).get("height", 0),
            "width": node.get("dimensions", {}).get("width", 0),
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

    media_edges = user_data.get("edge_owner_to_timeline_media", {}).get("edges", [])
    items = []

    for edge in media_edges:
        node = edge.get("node", {})
        item = parse_posts_blitz(node)
        item["target_id"] = profile["id"]
        item["relative_file_path"] = f"archives/{profile['id']}/{item['media_type']}s/{item['shortcode']}.{'mp4' if item['media_type'] == 'reel' else 'jpg'}"
        items.append(item)

    # When profile is active/public, also extract all highlights
    if not profile.get("is_private"):
        try:
            hl_trays = client.fetch_user_highlights(profile["id"])
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


def action_hydrate_assets(target_id: str, input_json: str, output_dir: str):
    """Phase 2 Hydration: Download media binaries from CDN URLs into storage directories."""
    if not os.path.exists(input_json):
        print(json.dumps({"status": "error", "message": f"Input file not found: {input_json}"}))
        return

    with open(input_json, "r", encoding="utf-8") as f:
        data = json.load(f)

    items = data.get("items", [])
    total = len(items)
    downloaded = 0
    total_bytes = 0

    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"})

    for idx, item in enumerate(items, 1):
        cdn_url = item.get("cdn_url")
        if not cdn_url:
            continue

        rel_path = item.get("relative_file_path", "")
        dest_path = os.path.join(output_dir, rel_path.replace(f"archives/{target_id}/", ""))
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)

        # Download binary if not already cached
        if not os.path.exists(dest_path) or os.path.getsize(dest_path) == 0:
            try:
                r = session.get(cdn_url, timeout=30, stream=True)
                if r.status_code == 200:
                    with open(dest_path, "wb") as f_out:
                        for chunk in r.iter_content(chunk_size=65536):
                            if chunk:
                                f_out.write(chunk)
                                total_bytes += len(chunk)
                    downloaded += 1
                else:
                    print(json.dumps({
                        "status": "warning",
                        "message": f"HTTP {r.status_code} for {item.get('shortcode')}"
                    }))
            except Exception as e:
                print(json.dumps({
                    "status": "warning",
                    "message": f"Download failed for {item.get('shortcode')}: {e}"
                }))
        else:
            downloaded += 1

        # Save sidecar caption and metadata
        base_no_ext = os.path.splitext(dest_path)[0]
        caption = item.get("caption", "")
        if caption:
            with open(f"{base_no_ext}_caption.txt", "w", encoding="utf-8") as f_cap:
                f_cap.write(caption)

        with open(f"{base_no_ext}_meta.json", "w", encoding="utf-8") as f_meta:
            json.dump(item, f_meta, indent=2, ensure_ascii=False)

        # Emit progress event
        print(json.dumps({
            "status": "progress",
            "current": idx,
            "total": total,
            "shortcode": item.get("shortcode"),
            "media_type": item.get("media_type")
        }), flush=True)

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
        action_hydrate_assets(args.target_id, args.input_json, args.output_dir)
    elif args.action == "check_highlights":
        action_check_highlights(args.target, args.output_dir, args.session)


if __name__ == "__main__":
    main()
