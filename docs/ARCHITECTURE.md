# InstaLogger Architecture

InstaLogger is designed for 24/7 autonomous Instagram profile monitoring, event tracking, and permanent media archiving across Android (Termux), Linux, and Windows.

## System Design

```
+-----------------------------------------------------------------+
|                         Host Environment                        |
|              Android (Termux) / Linux / Windows / macOS         |
|                                                                 |
|   +---------------------------------------------------------+   |
|   |         CLI Controller: instalogger / instalogger.ps1   |   |
|   |              (daemon management, wake-lock)             |   |
|   +----------------------------+----------------------------+   |
|                                |                                |
|                                v                                |
|   +---------------------------------------------------------+   |
|   |                 Rust Core Daemon (Axum)                 |   |
|   |   - SQLite embedded storage (WAL mode)                  |   |
|   |   - Randomized jitter scheduler & turbo mode (30-60s)    |   |
|   |   - REST API & WebSocket broadcast hub                  |   |
|   |   - Static file server (SPA bundle & media assets)      |   |
|   |   - Alert dispatch (Termux API & Telegram Bot)          |   |
|   +-----------------+---------------------------+-----------+   |
|                     |                           |               |
|       JSON / IPC    |                           | HTTP / WS     |
|       Subprocess    v                           v               |
|   +--------------------------+    +-------------------------+   |
|   |   Python Worker Engine   |    |    Web Dashboard (TSX)  |   |
|   | - Two-phase blitz & CDN  |    | - React 19 + TypeScript |   |
|   | - Media asset hydration  |    | - Real-time WS feed     |   |
|   | - Highlights & stories   |    | - Media viewer modal    |   |
|   | - Session & cookie auth  |    | - Timeline & audit logs |   |
|   +--------------------------+    +-------------------------+   |
+-----------------------------------------------------------------+
```

## Key Components

### 1. Rust Core Daemon (`backend/`)
- **Web Framework:** Axum 0.8 on Tokio async runtime.
- **Database:** SQLite with WAL mode, busy timeout, and foreign key enforcement via Rusqlite.
- **Scheduler:** Randomized polling intervals (300s standard, 30-60s turbo) with organic jitter to avoid automated detection patterns.
- **WebSocket Hub:** Broadcasts instant state changes and privacy transition alerts to all active web clients.
- **Static File Serving:** Serves compiled SPA assets and mapped media archives directly.

### 2. Python Worker Engine (`engine/`)
- **Two-Phase Blitz Archiving:**
  - **Phase 1 (Blitz):** Fast collection of all post metadata and signed CDN URLs in seconds before privacy state can revert.
  - **Phase 2 (Hydration):** Resilient background download of high-resolution images, videos, and sidecar captions with sha256 deduplication.
- **Session Handling:** Supports optional authenticated Instagram sessions for monitored accounts that the user follows.

### 3. Web Dashboard (`web/`)
- **Frontend Stack:** React 19, TypeScript, Tailwind CSS, Lucide icons.
- **Fluid UI:** System font stack (`system-ui, "Segoe UI", Roboto, sans-serif`), responsive mobile-first cards, smooth transitions, and dark-mode compatibility.
- **Zero Data Loss Guarantee:** Media deleted from the Instagram profile remains permanently stored in local archives and is flagged as vanished.
