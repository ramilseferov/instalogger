# InstaLogger

InstaLogger is an automated, continuous Instagram profile monitoring and digital archival system. It operates as an asynchronous background daemon, tracking account state transitions, calculating cryptographic media digests, and archiving media before account state or post visibility changes.

[![License: MIT](https://img.shields.io/badge/license-MIT-333333.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Linux%20%7C%20Android%20%7C%20Windows%20%7C%20macOS-333333.svg?style=flat-square)](#)
[![Backend](https://img.shields.io/badge/daemon-Rust%20%2B%20Axum%20%2B%20SQLite%20WAL-333333.svg?style=flat-square)](#)
[![Engine](https://img.shields.io/badge/worker-Python%203.11%20%2B%20GraphQL-333333.svg?style=flat-square)](#)
[![Android](https://img.shields.io/badge/android-Kotlin%20%2B%20Compose-333333.svg?style=flat-square)](#)

---

## Overview

Public and private accounts frequently modify metadata, delete publications, or toggle privacy states. InstaLogger provides automated observation with local-first persistence:

- **Two-Phase Asset Blitz:** When a monitored private account becomes public, signed CDN media URLs and metadata are cataloged within seconds, followed by prioritized asynchronous binary streaming to disk.
- **Dual-Layer Hash Verification:** Differentiates genuine profile picture updates from ephemeral CDN token rotations by comparing cryptographic SHA-256 byte digests alongside GraphQL query hashes.
- **Differential Audit Trail:** Chronologically captures changes in usernames, display names, biographies, follower/following counts, and individual follow list differentials (`followers.json`, `following.json`).
- **Non-Destructive Preservation:** Posts deleted or archived on Instagram remain preserved locally with metadata preserved in sidecar format.
- **Cross-Platform Daemon:** Runs natively as a lightweight system service on Android (Termux or Native App), Linux servers, macOS, and Windows.

---

## Architecture

```mermaid
flowchart TD
    subgraph HostSystem["System Environment"]
        Daemon["Rust Daemon (Axum :3000)<br/>Scheduler • SQLite WAL • WebSocket Hub"]
        Worker["Python Worker Engine<br/>Web API • GraphQL Hash • CDN Blitz"]
        AndroidApp["Native Android App (Compose)<br/>Foreground Service • Room DB • Hardware Blur"]
        WebUI["Web Management Console<br/>React 19 • TypeScript • Tailwind"]
    end

    subgraph TargetWorld["Instagram Target"]
        IGMeta["Profile Metadata & Privacy State"]
        IGMedia["Posts, Reels, Highlights, Stories"]
        IGGraph["Followers & Following Graphs"]
    end

    Daemon -->|"IPC Task Dispatch"| Worker
    Worker -->|"Extract & Blitz"| TargetWorld
    Worker -->|"Binary Streaming"| DiskStorage["Lokal Arxiv Qovluğu<br/>/archives/{target_id}/"]
    Daemon <-->|"REST API / WebSocket"| AndroidApp
    Daemon <-->|"HTTP / WS Stream"| WebUI
    AndroidApp -->|"Direct Inspection (Fallback)"| TargetWorld
```

---

## Installation & Deployment

### Android (Termux)

Root access is not required.

```bash
pkg update && pkg install -y git rust python nodejs-lts termux-api
pip install requests instaloader urllib3

git clone https://github.com/ramilseferov/instalogger.git
cd instalogger
cargo build --release
```

### Linux (Ubuntu / Debian / Arch / Fedora)

```bash
git clone https://github.com/ramilseferov/instalogger.git
cd instalogger
pip install requests instaloader urllib3
cargo build --release
```

### Windows (PowerShell)

```powershell
git clone https://github.com/ramilseferov/instalogger.git
cd instalogger
pip install requests instaloader urllib3
cargo build --release
```

---

## CLI Reference

Control the daemon and background scheduler through the host script:

| Bash Command | PowerShell Command | Purpose |
| :--- | :--- | :--- |
| `instalogger start` | `.\instalogger.ps1 start` | Starts daemon in background with wake-lock |
| `instalogger status` | `.\instalogger.ps1 status` | Queries process health, uptime, and target metrics |
| `instalogger logs` | `.\instalogger.ps1 logs` | Tails live scheduler and engine stdout |
| `instalogger stop` | `.\instalogger.ps1 stop` | Signals graceful termination to daemon and workers |
| `instalogger restart` | `.\instalogger.ps1 restart` | Restarts the running service and refreshes scheduler |

The local web dashboard binds to `http://127.0.0.1:3000`.

---

## File and Archive Layout

```
instalogger/
├── data/
│   └── instalogger.db               # SQLite database (WAL journal mode)
├── sessions/
│   └── instagram_session.json      # Optional authenticated session tokens
├── logs/
│   └── instalogger.log              # Persistent execution and inspection logs
└── archives/
    └── <target_username_or_id>/
        ├── followers.json           # Cached follower accounts with diff metadata
        ├── following.json           # Cached following accounts with diff metadata
        ├── profile/                 # Historical avatars and metadata snapshots
        │   ├── avatar_current.jpg
        │   └── <timestamp>_<hash>.jpg
        ├── posts/                   # Static photo publications with sidecar metadata
        ├── reels/                   # MP4 video streams
        └── highlights/              # Organized highlight collections
```

---

## Native Android Application

The `android/` directory contains an independent native Android application developed with Jetpack Compose:

- **Framework:** Kotlin, Jetpack Compose, Material 3, Room SQLite, Coroutines & Flow.
- **Foreground Monitoring:** Background service (`InstaLoggerForegroundService`) executes scheduled profile checks with battery optimization exclusion (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`).
- **Rendering & Aesthetics:** Hardware-accelerated frosted glass blur transitions via `RenderEffect` on Android 12+, dynamic SVG avatar fallbacks, and low-latency interaction handling.
- **Multilingual Support:** Localized string catalogs for Azerbaijani (`AZ`), English (`EN`), and Russian (`RU`).

---

## License

Distributed under the [MIT License](LICENSE). Maintained by [Ramil Seferov](https://github.com/ramilseferov).
