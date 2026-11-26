#!/usr/bin/env python3
"""
InstaLogger — Safe GitHub Sync & Push Utility

Designed for both local checkouts and ephemeral cloud workspaces (such as
Google AI Studio) where `~/.git-credentials` or `.git/` may not be present.

Token lookup order (never written to `.git/config`, never printed):
  1. `GITHUB_TOKEN` environment variable
  2. `.github-token` file in the repository root (gitignored)
  3. `~/.git-credentials`
"""

from __future__ import annotations

import argparse
import base64
import os
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_REPO = "ramilseferov/instalogger"
DEFAULT_BRANCH = "main"

FORBIDDEN_STAGE_PATTERNS = (
    re.compile(r"(^|/)\.github-token$"),
    re.compile(r"(^|/)\.env(\..+)?$"),
    re.compile(r"\.(keystore|jks|pem|key)$", re.IGNORECASE),
    re.compile(r"(^|/)sessions/.*\.(json|session)$"),
    re.compile(r"\.db(-wal|-shm|-journal)?$", re.IGNORECASE),
)


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def load_github_token(root: Path) -> str | None:
    env_tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if env_tok:
        return env_tok

    token_file = root / ".github-token"
    if token_file.is_file():
        tok = token_file.read_text(encoding="utf-8").strip()
        if tok:
            return tok

    cred_file = Path.home() / ".git-credentials"
    if cred_file.is_file():
        try:
            lines = [ln.strip() for ln in cred_file.read_text(encoding="utf-8").splitlines() if "@github.com" in ln]
            for matcher in (lambda l: "instalogger" in l.lower(), lambda _: True):
                for line in lines:
                    if matcher(line):
                        m = re.search(r":([^:@/]+)@github\.com", line)
                        if m:
                            return m.group(1).strip()
        except OSError:
            pass

    return None


def build_auth_header(token: str) -> str:
    encoded = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
    return f"http.extraHeader=AUTHORIZATION: Basic {encoded}"


def run_git(root: Path, args: list[str], token: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    cmd = ["git", "-C", str(root)]
    if token:
        cmd.extend(["-c", build_auth_header(token)])
    cmd.extend(args)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if check and proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip()
        # Redact any accidental credential substring before displaying
        err = re.sub(r"Basic\s+[A-Za-z0-9+/=]+", "Basic [REDACTED]", err)
        err = re.sub(r"(ghp_|github_pat_)[A-Za-z0-9_]+", "[REDACTED]", err)
        print(f"[error] git {' '.join(args)} failed ({proc.returncode}): {err}", file=sys.stderr)
        sys.exit(proc.returncode)
    return proc


def resolve_repo_slug(root: Path) -> str:
    if (root / ".git").exists():
        res = run_git(root, ["config", "--get", "remote.origin.url"], check=False)
        url = res.stdout.strip()
        m = re.search(r"github\.com[:/]([^/]+/[^/.]+)(?:\.git)?$", url)
        if m:
            return m.group(1)
    return os.environ.get("GITHUB_REPO", DEFAULT_REPO).strip()


def bootstrap_git_if_needed(root: Path, repo: str, branch: str, token: str | None) -> None:
    clean_url = f"https://github.com/{repo}.git"
    if not (root / ".git").exists():
        print(f"[init] Initializing git repository for {repo} ({branch})")
        run_git(root, ["init", "-b", branch])
        run_git(root, ["remote", "add", "origin", clean_url])
        run_git(root, ["fetch", "--depth=1", "origin", branch], token=token)
        run_git(root, ["reset", "--mixed", "FETCH_HEAD"])
        print("[init] Synchronized working tree with origin/main without overwriting local changes.")
    else:
        # Ensure origin URL never stores an inline token
        cur = run_git(root, ["config", "--get", "remote.origin.url"], check=False).stdout.strip()
        if "@" in cur and "github.com" in cur:
            run_git(root, ["remote", "set-url", "origin", clean_url])
            print("[security] Stripped inline credentials from remote.origin.url.")


def assert_no_forbidden_staged(root: Path) -> None:
    staged = run_git(root, ["diff", "--cached", "--name-only"]).stdout.splitlines()
    violations = [
        path for path in staged
        if any(pat.search(path) for pat in FORBIDDEN_STAGE_PATTERNS)
    ]
    if violations:
        print("[error] Refusing to commit sensitive or forbidden files:", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        sys.exit(2)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="InstaLogger safe GitHub sync and push helper")
    parser.add_argument("--repo", default="", help=f"GitHub owner/repo (default: auto-detected or {DEFAULT_REPO})")
    parser.add_argument("--branch", default=DEFAULT_BRANCH, help=f"Target branch (default: {DEFAULT_BRANCH})")
    parser.add_argument("--init-sync", action="store_true", help="Bootstrap .git in a cloud workspace from origin/main")
    parser.add_argument("--status", action="store_true", help="Show git status, token source availability, and ignore check")
    parser.add_argument("-m", "--commit", default="", help="Stage specified paths (or tracked changes) and create a commit")
    parser.add_argument("--paths", nargs="*", default=[], help="Explicit file paths to stage with --commit")
    parser.add_argument("--push", action="store_true", help="Push current branch to origin over HTTPS without modifying .git/config")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = repo_root()
    repo = args.repo.strip() or resolve_repo_slug(root)
    token = load_github_token(root)

    if args.init_sync:
        bootstrap_git_if_needed(root, repo, args.branch, token)

    if args.status:
        if not (root / ".git").exists():
            print("[status] No .git directory found. Run with --init-sync first.")
            sys.exit(0)
        ign = run_git(root, ["check-ignore", "-v", ".github-token"], check=False)
        tok_state = "available" if token else "missing"
        print(f"[status] Repository: {repo}")
        print(f"[status] Auth token: {tok_state}")
        print(f"[status] .github-token ignored: {'yes' if ign.returncode == 0 else 'NO (add to .gitignore!)'}")
        st = run_git(root, ["status", "--short"]).stdout.strip()
        print(st if st else "[status] Working tree clean.")
        return

    if not (root / ".git").exists():
        print("[error] Not a git repository. Re-run with --init-sync to bootstrap from GitHub.", file=sys.stderr)
        sys.exit(1)

    if args.commit:
        if args.paths:
            run_git(root, ["add", "--"] + args.paths)
        else:
            run_git(root, ["add", "-u"])
        assert_no_forbidden_staged(root)
        staged = run_git(root, ["diff", "--cached", "--name-only"]).stdout.strip()
        if not staged:
            print("[error] Nothing staged to commit.", file=sys.stderr)
            sys.exit(1)
        run_git(root, ["commit", "-m", args.commit.strip()])
        sha = run_git(root, ["rev-parse", "--short", "HEAD"]).stdout.strip()
        print(f"[commit] Created {sha}: {args.commit.strip()}")

    if args.push:
        if not token:
            print(
                "[error] GitHub token not found. Provide GITHUB_TOKEN, a gitignored .github-token file, or ~/.git-credentials.",
                file=sys.stderr,
            )
            sys.exit(1)
        # Keep remote URL clean before pushing
        bootstrap_git_if_needed(root, repo, args.branch, token)
        run_git(root, ["push", "origin", args.branch], token=token)
        sha = run_git(root, ["rev-parse", "--short", "HEAD"]).stdout.strip()
        print(f"[push] Pushed {sha} -> origin/{args.branch} ({repo})")

    if not (args.init_sync or args.status or args.commit or args.push):
        print("Specify one of: --status, --init-sync, --commit <msg>, --push")
        sys.exit(2)


if __name__ == "__main__":
    main()
