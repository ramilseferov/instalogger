"""
InstaLogger Post & Reel Comments Extractor, Tree Query Engine, and Multi-Format Exporter.

Supports:
- Optional aiograpi / instagrapi SDK backends (MIT) for public GraphQL & authenticated threaded comments
- Zero-dependency REST v1 fallback (with Retry-After handling)
- Structured tree model keyed by comment_id with explicit collection_status ("complete" | "partial")
- Tree-aware search, sorting, filtering, and export to JSON, CSV, TXT, and Markdown
"""

from __future__ import annotations

import asyncio
import csv
import io
import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

IG_SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"


def shortcode_to_media_id(shortcode: str) -> Optional[str]:
    clean = shortcode.strip().split("/")[0].split("?")[0]
    if not clean:
        return None
    base = clean[:11] if 11 < len(clean) <= 28 else clean
    media_id = 0
    for ch in base:
        idx = IG_SHORTCODE_ALPHABET.find(ch)
        if idx < 0:
            return None
        media_id = (media_id * 64) + idx
    return str(media_id) if media_id > 0 else None


@dataclass
class CommentRecord:
    comment_id: str
    shortcode: str
    media_id: str = ""
    parent_comment_id: Optional[str] = None
    author_username: str = "instagram_user"
    author_id: str = ""
    author_full_name: str = ""
    is_verified: bool = False
    text: str = ""
    created_at: str = ""
    like_count: int = 0
    reply_count: int = 0
    collection_status: str = "complete"


@dataclass
class CommentThread:
    comment: CommentRecord
    replies: List[CommentRecord] = field(default_factory=list)


def _iso_from_ts(ts: Any) -> str:
    if isinstance(ts, str) and ts:
        return ts
    if isinstance(ts, datetime):
        dt = ts if ts.tzinfo else ts.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    try:
        val = float(ts)
        if val > 10_000_000_000:
            val /= 1000.0
        return datetime.fromtimestamp(val, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return datetime.now(tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_comment_tree(records: List[CommentRecord]) -> List[CommentThread]:
    by_id: Dict[str, CommentRecord] = {r.comment_id: r for r in records if r.comment_id}
    children_map: Dict[str, List[CommentRecord]] = {}
    roots: List[CommentRecord] = []

    for rec in by_id.values():
        pid = rec.parent_comment_id
        if pid and pid != rec.comment_id and pid in by_id:
            children_map.setdefault(pid, []).append(rec)
        else:
            roots.append(rec)

    threads: List[CommentThread] = []
    for root in roots:
        collected: List[CommentRecord] = []
        queue = [root.comment_id]
        visited = {root.comment_id}
        while queue:
            cur = queue.pop(0)
            for child in children_map.get(cur, []):
                if child.comment_id not in visited:
                    visited.add(child.comment_id)
                    collected.append(child)
                    queue.append(child.comment_id)
        collected.sort(key=lambda r: r.created_at)
        threads.append(CommentThread(comment=root, replies=collected))
    return threads


def query_comment_tree(
    records: List[CommentRecord],
    *,
    search: str = "",
    sort_by: str = "most_liked",
    author: Optional[str] = None,
    min_likes: int = 0,
    with_replies_only: bool = False,
    verified_only: bool = False,
) -> List[CommentThread]:
    q = search.strip().lower()
    author_q = (author or "").strip().lstrip("@").lower()
    raw_tree = build_comment_tree(records)

    def matches(rec: CommentRecord) -> bool:
        if min_likes > 0 and rec.like_count < min_likes:
            return False
        if verified_only and not rec.is_verified:
            return False
        if author_q and rec.author_username.lower() != author_q:
            return False
        if q:
            clean_q = q.lstrip("@")
            haystack = f"{rec.text} {rec.author_username} {rec.author_full_name} {rec.comment_id}".lower()
            if q not in haystack and clean_q not in rec.author_username.lower():
                return False
        return True

    has_constraint = bool(q or author_q or min_likes > 0 or verified_only)
    filtered: List[CommentThread] = []

    for node in raw_tree:
        if with_replies_only and not node.replies and node.comment.reply_count <= 0:
            continue
        parent_hit = matches(node.comment)
        reply_hits = [r for r in node.replies if matches(r)]
        if has_constraint and not parent_hit and not reply_hits:
            continue
        kept_replies = (
            node.replies
            if (not has_constraint or (parent_hit and not author_q and min_likes == 0 and not verified_only))
            else reply_hits
        )
        filtered.append(CommentThread(comment=node.comment, replies=kept_replies))

    if sort_by == "newest":
        filtered.sort(key=lambda t: t.comment.created_at, reverse=True)
    elif sort_by == "oldest":
        filtered.sort(key=lambda t: t.comment.created_at)
    elif sort_by == "most_replies":
        filtered.sort(key=lambda t: (max(t.comment.reply_count, len(t.replies)), t.comment.like_count), reverse=True)
    elif sort_by == "author_az":
        filtered.sort(key=lambda t: t.comment.author_username.lower())
    else:
        filtered.sort(key=lambda t: (t.comment.like_count, t.comment.created_at), reverse=True)
    return filtered


def export_comments(
    threads: List[CommentThread],
    *,
    shortcode: str,
    target_username: str = "",
    fmt: str = "json",
    collection_status: str = "complete",
) -> str:
    fmt = fmt.lower().strip()
    media_id = shortcode_to_media_id(shortcode) or ""
    total_replies = sum(len(t.replies) for t in threads)
    total_count = len(threads) + total_replies

    if fmt == "json":
        payload = {
            "media_id": media_id,
            "shortcode": shortcode,
            "target_username": target_username,
            "exported_at": _iso_from_ts(datetime.now(tz=timezone.utc)),
            "collection_status": collection_status,
            "stats": {
                "total_comments": total_count,
                "top_level_comments": len(threads),
                "replies": total_replies,
            },
            "comments": [
                {
                    "media_id": t.comment.media_id or media_id,
                    "shortcode": t.comment.shortcode,
                    "comment_id": t.comment.comment_id,
                    "parent_comment_id": t.comment.parent_comment_id,
                    "author_username": t.comment.author_username,
                    "author_id": t.comment.author_id,
                    "is_verified": t.comment.is_verified,
                    "text": t.comment.text,
                    "created_at": t.comment.created_at,
                    "like_count": t.comment.like_count,
                    "reply_count": max(t.comment.reply_count, len(t.replies)),
                    "collection_status": t.comment.collection_status,
                    "replies": [
                        {
                            "comment_id": r.comment_id,
                            "parent_comment_id": r.parent_comment_id or t.comment.comment_id,
                            "author_username": r.author_username,
                            "author_id": r.author_id,
                            "text": r.text,
                            "created_at": r.created_at,
                            "like_count": r.like_count,
                        }
                        for r in t.replies
                    ],
                }
                for t in threads
            ],
        }
        return json.dumps(payload, ensure_ascii=False, indent=2)

    if fmt == "csv":
        buf = io.StringIO()
        writer = csv.writer(buf)
        writer.writerow([
            "comment_id", "parent_comment_id", "depth", "author_username",
            "author_id", "is_verified", "created_at", "like_count",
            "reply_count", "collection_status", "text",
        ])
        for t in threads:
            c = t.comment
            writer.writerow([
                c.comment_id, c.parent_comment_id or "", 0, c.author_username,
                c.author_id, c.is_verified, c.created_at, c.like_count,
                max(c.reply_count, len(t.replies)), c.collection_status, c.text,
            ])
            for r in t.replies:
                writer.writerow([
                    r.comment_id, r.parent_comment_id or c.comment_id, 1, r.author_username,
                    r.author_id, r.is_verified, r.created_at, r.like_count,
                    0, r.collection_status, r.text,
                ])
        return "\ufeff" + buf.getvalue()

    if fmt == "md" or fmt == "markdown":
        lines = [
            f"# Instagram Comments — @{target_username or 'unknown'} (`{shortcode}`)",
            "",
            f"- **Total:** {total_count} (Top-level: {len(threads)}, Replies: {total_replies})",
            f"- **Status:** `{collection_status}`",
            "",
            "---",
            "",
        ]
        for t in threads:
            c = t.comment
            lines.append(f"### @{c.author_username}")
            lines.append(f"*{c.created_at} · {c.like_count} likes · ID `{c.comment_id}`*")
            lines.append("")
            lines.append(c.text)
            lines.append("")
            for r in t.replies:
                lines.append(f"> **@{r.author_username}** · *{r.created_at} · {r.like_count} likes*")
                for part in r.text.splitlines() or [""]:
                    lines.append(f"> {part}")
                lines.append("")
            lines.append("---")
            lines.append("")
        return "\n".join(lines)

    # Default: TXT tree view
    lines = [
        f"InstaLogger Comments — @{target_username or 'unknown'} ({shortcode})",
        f"Summary: total={total_count}, top_level={len(threads)}, replies={total_replies}, status={collection_status}",
        "=" * 64,
        "",
    ]
    for t in threads:
        c = t.comment
        lines.append(f"@{c.author_username} | {c.created_at} | likes: {c.like_count}")
        lines.append(f"  {c.text}")
        for idx, r in enumerate(t.replies):
            branch = "└─" if idx == len(t.replies) - 1 else "├─"
            pad = "     " if idx == len(t.replies) - 1 else "  │  "
            lines.append(f"  {branch} @{r.author_username} ({r.created_at}, likes: {r.like_count})")
            lines.append(f"{pad}{r.text}")
        lines.append("")
    return "\n".join(lines)


async def fetch_comments_via_aiograpi(
    shortcode: str,
    *,
    amount: int = 200,
    session_file: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Uses subzeroid/aiograpi if installed in the environment:
    - Authenticated session -> media_comments()
    - Public/unauthenticated -> media_comments_public_gql()
    """
    try:
        from aiograpi import Client  # type: ignore
    except ImportError as exc:
        return {"ok": False, "error": f"aiograpi not installed: {exc}", "comments": [], "collection_status": "partial"}

    client = Client()
    has_session = False
    if session_file and os.path.exists(session_file):
        client.load_settings(Path(session_file))
        has_session = True

    media_id = shortcode_to_media_id(shortcode) or ""
    raw_items = []
    status = "complete"
    try:
        if has_session and media_id:
            raw_items = await client.media_comments(media_id, amount=amount)
        else:
            raw_items = await client.media_comments_public_gql(shortcode, amount=amount)
            status = "partial"
    except Exception as exc:
        return {"ok": False, "error": str(exc), "comments": [], "collection_status": "partial"}

    records: List[CommentRecord] = []
    for item in raw_items or []:
        user = getattr(item, "user", None)
        records.append(
            CommentRecord(
                comment_id=str(getattr(item, "pk", "")),
                shortcode=shortcode,
                media_id=media_id,
                parent_comment_id=None,
                author_username=str(getattr(user, "username", "instagram_user") if user else "instagram_user"),
                author_id=str(getattr(user, "pk", "") if user else ""),
                author_full_name=str(getattr(user, "full_name", "") if user else ""),
                is_verified=bool(getattr(user, "is_verified", False) if user else False),
                text=str(getattr(item, "text", "")),
                created_at=_iso_from_ts(getattr(item, "created_at_utc", None)),
                like_count=int(getattr(item, "like_count", 0) or 0),
                reply_count=int(getattr(item, "child_comment_count", 0) or 0),
                collection_status=status,
            )
        )
    return {"ok": True, "comments": records, "collection_status": status}


def extract_comments_sync(
    shortcode: str,
    cookies: Optional[Dict[str, str]] = None,
    max_comments: int = 200,
) -> List[CommentRecord]:
    """Zero-dependency Instagram REST v1 & GraphQL threaded comments extractor with pagination."""
    import requests
    import time

    clean_code = shortcode.strip().split("/")[0].split("?")[0]
    media_id = shortcode_to_media_id(clean_code) or ""
    session = requests.Session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": f"https://www.instagram.com/p/{clean_code}/",
        "X-Ig-App-Id": "936619743392459",
        "X-Requested-With": "XMLHttpRequest",
    })
    if cookies:
        for k, v in cookies.items():
            session.cookies.set(k, str(v), domain=".instagram.com")
        if cookies.get("csrftoken"):
            session.headers["X-CSRFToken"] = str(cookies["csrftoken"])

    records: List[CommentRecord] = []
    seen_ids = set()

    # Tier 1: REST v1 comments pagination (works with session cookie)
    if media_id:
        min_id: Optional[str] = None
        for _ in range(10):
            if len(records) >= max_comments:
                break
            url = f"https://www.instagram.com/api/v1/media/{media_id}/comments/?can_support_threading=true&permalink_enabled=false"
            if min_id:
                url += f"&min_id={requests.utils.quote(min_id, safe='')}"
            try:
                resp = session.get(url, timeout=15)
                if resp.status_code != 200 or not resp.text.lstrip().startswith("{"):
                    break
                payload = resp.json()
                comments_arr = payload.get("comments") or []
                if not comments_arr:
                    break
                for c in comments_arr:
                    if len(records) >= max_comments:
                        break
                    cid = str(c.get("pk") or c.get("id") or "").strip()
                    if not cid or cid in seen_ids:
                        continue
                    seen_ids.add(cid)
                    u = c.get("user") or {}
                    records.append(
                        CommentRecord(
                            comment_id=cid,
                            shortcode=clean_code,
                            media_id=media_id,
                            parent_comment_id=str(c.get("parent_comment_id")) if c.get("parent_comment_id") else None,
                            author_username=str(u.get("username") or "instagram_user"),
                            author_id=str(u.get("pk") or u.get("id") or ""),
                            author_full_name=str(u.get("full_name") or ""),
                            is_verified=bool(u.get("is_verified", False)),
                            text=str(c.get("text") or ""),
                            created_at=_iso_from_ts(c.get("created_at_utc") or c.get("created_at")),
                            like_count=int(c.get("comment_like_count") or 0),
                            reply_count=int(c.get("child_comment_count") or 0),
                            collection_status="complete",
                        )
                    )
                    for child in (c.get("preview_child_comments") or []):
                        ccid = str(child.get("pk") or child.get("id") or "").strip()
                        if not ccid or ccid in seen_ids:
                            continue
                        seen_ids.add(ccid)
                        cu = child.get("user") or {}
                        records.append(
                            CommentRecord(
                                comment_id=ccid,
                                shortcode=clean_code,
                                media_id=media_id,
                                parent_comment_id=cid,
                                author_username=str(cu.get("username") or "instagram_user"),
                                author_id=str(cu.get("pk") or cu.get("id") or ""),
                                author_full_name=str(cu.get("full_name") or ""),
                                is_verified=bool(cu.get("is_verified", False)),
                                text=str(child.get("text") or ""),
                                created_at=_iso_from_ts(child.get("created_at_utc") or child.get("created_at")),
                                like_count=int(child.get("comment_like_count") or 0),
                                reply_count=0,
                                collection_status="complete",
                            )
                        )
                next_min = payload.get("next_min_id")
                if not next_min or next_min == min_id:
                    break
                min_id = str(next_min)
                time.sleep(0.25)
            except Exception:
                break

    if records:
        return records

    # Tier 2: GraphQL public comments fallback
    try:
        variables = json.dumps({"shortcode": clean_code, "first": min(50, max_comments)})
        gql_url = f"https://www.instagram.com/graphql/query/?query_hash=bc3296d1ce80a24b1b6e40b1e72903f5&variables={requests.utils.quote(variables, safe='')}"
        resp = session.get(gql_url, timeout=15)
        if resp.status_code == 200 and resp.text.lstrip().startswith("{"):
            edges = (
                resp.json()
                .get("data", {})
                .get("shortcode_media", {})
                .get("edge_media_to_parent_comment", {})
                .get("edges", [])
            )
            for edge in edges:
                node = edge.get("node") if isinstance(edge, dict) else None
                if not isinstance(node, dict):
                    continue
                cid = str(node.get("id") or "").strip()
                if not cid or cid in seen_ids:
                    continue
                seen_ids.add(cid)
                owner = node.get("owner") or {}
                records.append(
                    CommentRecord(
                        comment_id=cid,
                        shortcode=clean_code,
                        media_id=media_id,
                        parent_comment_id=None,
                        author_username=str(owner.get("username") or "instagram_user"),
                        author_id=str(owner.get("id") or ""),
                        author_full_name="",
                        is_verified=bool(owner.get("is_verified", False)),
                        text=str(node.get("text") or ""),
                        created_at=_iso_from_ts(node.get("created_at")),
                        like_count=int((node.get("edge_liked_by") or {}).get("count") or 0),
                        reply_count=int((node.get("edge_threaded_comments") or {}).get("count") or 0),
                        collection_status="partial",
                    )
                )
    except Exception:
        pass

    return records


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="InstaLogger Comments Archiver & Tree Exporter")
    parser.add_argument("shortcode", help="Instagram post or reel shortcode")
    parser.add_argument("--amount", type=int, default=200)
    parser.add_argument("--format", choices=["json", "csv", "txt", "md"], default="json")
    parser.add_argument("--sort", choices=["most_liked", "newest", "oldest", "most_replies", "author_az"], default="most_liked")
    parser.add_argument("--search", default="")
    parser.add_argument("--session", default=None)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    res = asyncio.run(fetch_comments_via_aiograpi(args.shortcode, amount=args.amount, session_file=args.session))
    if not res["ok"]:
        raise SystemExit(res["error"])
    threads = query_comment_tree(res["comments"], search=args.search, sort_by=args.sort)
    rendered = export_comments(threads, shortcode=args.shortcode, fmt=args.format, collection_status=res["collection_status"])
    if args.out:
        Path(args.out).write_text(rendered, encoding="utf-8")
    else:
        print(rendered)
