#!/usr/bin/env python3
"""
InstaLogger — Android Version Management Utility

Reads or updates `versionCode` and `versionName` in `android/app/build.gradle.kts`.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def gradle_file() -> Path:
    root = Path(__file__).resolve().parent.parent
    for rel in ("android/app/build.gradle.kts", "app/build.gradle.kts"):
        candidate = root / rel
        if candidate.is_file():
            return candidate
    print("[error] Could not locate app build.gradle.kts.", file=sys.stderr)
    sys.exit(1)


def read_version(path: Path) -> tuple[str, int]:
    text = path.read_text(encoding="utf-8")
    name_m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code_m = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name_m or not code_m:
        print(f"[error] versionName or versionCode not found in {path}", file=sys.stderr)
        sys.exit(1)
    return name_m.group(1), int(code_m.group(1))


def bump_semver(version: str, part: str) -> str:
    parts = version.split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        print(f"[error] Non-SemVer versionName '{version}'; use --set X.Y.Z instead.", file=sys.stderr)
        sys.exit(1)
    major, minor, patch = (int(p) for p in parts)
    if part == "major":
        return f"{major + 1}.0.0"
    if part == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def write_version(path: Path, new_name: str, new_code: int) -> None:
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r'(versionName\s*=\s*)"[^"]+"', rf'\1"{new_name}"', text, count=1)
    updated = re.sub(r"(versionCode\s*=\s*)\d+", rf"\g<1>{new_code}", updated, count=1)
    path.write_text(updated, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Inspect or bump InstaLogger Android versionCode/versionName")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--show", action="store_true", help="Print current version and exit (default)")
    group.add_argument("--patch", action="store_true", help="Increment patch version (X.Y.Z+1) and versionCode+1")
    group.add_argument("--minor", action="store_true", help="Increment minor version (X.Y+1.0) and versionCode+1")
    group.add_argument("--major", action="store_true", help="Increment major version (X+1.0.0) and versionCode+1")
    group.add_argument("--set", metavar="X.Y.Z", help="Set explicit versionName and increment versionCode+1")
    parser.add_argument("--code", type=int, default=None, help="Override explicit versionCode when using --set")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    path = gradle_file()
    cur_name, cur_code = read_version(path)

    if args.patch:
        new_name, new_code = bump_semver(cur_name, "patch"), cur_code + 1
    elif args.minor:
        new_name, new_code = bump_semver(cur_name, "minor"), cur_code + 1
    elif args.major:
        new_name, new_code = bump_semver(cur_name, "major"), cur_code + 1
    elif args.set:
        new_name = args.set.strip()
        new_code = args.code if args.code is not None else cur_code + 1
    else:
        print(f"v{cur_name}-b{cur_code} (versionName={cur_name}, versionCode={cur_code})")
        return

    write_version(path, new_name, new_code)
    print(f"[bump] {cur_name} (b{cur_code}) -> {new_name} (b{new_code}) in {path.relative_to(path.parent.parent.parent)}")


if __name__ == "__main__":
    main()
