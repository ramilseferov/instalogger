# Post Archiving & Multi-Provider Media Engine Architecture

This document specifies the design, execution flow, persistence schema, security controls, and fault-tolerance model of InstaLogger's post discovery and media archival subsystem across both the standalone Android application (`android/`) and the optional Python daemon engine (`engine/providers/`).

---

## 1. Design Rationale

Archiving Instagram publications reliably cannot depend on a single extraction tool:

- `yt-dlp` is specialized for video, Reels, and DASH audio/video muxing via FFmpeg, but explicitly refuses image-only publications and photo-only carousels (`There is no video in this post`).
- Direct HTTP/GraphQL endpoints handle single images, multi-slide carousels (`edge_sidecar_to_children` / `carousel_media`), and metadata rapidly, but fail on split DASH media streams and are vulnerable to dynamic CDN signature expiration.
- Any individual extractor may encounter transient `HTTP 429 Too Many Requests`, authentication walls, or schema changes.

To eliminate single-provider fragility, InstaLogger decouples **post discovery**, **media extraction**, **integrity verification**, and **encrypted persistence** behind a pluggable `InstagramMediaProvider` interface managed by a bounded, health-aware orchestrator.

---

## 2. High-Level Subsystem Topology

```mermaid
flowchart TD
    subgraph Discovery["Phase 1: Post Discovery (Zero-Block UI)"]
        PI["ProfileInspector.archiveHighlightsAndPosts"]
        T1["Tier A: Web Profile Info API (Session / Guest Cookie)"]
        T2["Tier B: GraphQL Timeline Query"]
        T3["Tier C: On-Device Headless WebView Scraper"]
        T4["Tier D: Public Profile HTML Extractor"]
        PI --> T1
        T1 --> T2
        T2 --> T3
        T3 --> T4
    end

    subgraph Orchestration["Phase 2: Multi-Provider Orchestration"]
        Orch["MediaDownloadOrchestrator (Semaphore: 2 Workers)"]
        Health["ProviderHealthTracker (Circuit Breaker + Room Persistence)"]
        Orch <--> Health
    end

    subgraph AndroidProviders["On-Device Android Providers"]
        YtDlp["YtDlpEmbeddedProvider (youtubedl-android 0.18.1 + FFmpeg)"]
        Native["NativeGraphProvider (Sidecar Carousel + Embed/OEmbed + HTTP)"]
        Daemon["DaemonBridgeProvider (Optional Local/Remote HTTP Bridge)"]
    end

    subgraph PythonProviders["Optional Python Daemon Stack (engine/providers/)"]
        PyOrch["PythonProviderOrchestrator"]
        PyYt["YtDlpProvider (CLI Subprocess)"]
        PyGdl["GalleryDlProvider (GPL-Isolated CLI Subprocess)"]
        PyHttp["InstaloaderHttpProvider (MIT Direct CDN Fallback)"]
        PyOrch --> PyYt
        PyOrch --> PyGdl
        PyOrch --> PyHttp
    end

    subgraph IntegrityAndStorage["Phase 3: Security, Integrity & Encrypted Persistence"]
        Cookie["NetscapeCookieBridge (0600 Ephemeral File -> Zero-Fill Wipe)"]
        Val["MediaUrlGuard + MediaFileValidator (Magic Bytes + SHA-256)"]
        Vault["VaultStorageManager (AES-256-GCM .enc Storage)"]
        DB["Room Database v7 (posts · media_assets · provider_health)"]
        Play["VaultPlaybackBridge (On-Demand Temp Decrypt + FileProvider)"]
    end

    Discovery -->|"Immediate Metadata Upsert"| DB
    Discovery -->|"enqueueBatch()"| Orch
    Orch --> YtDlp
    Orch --> Native
    Orch -. "daemonSyncEnabled = true" .-> Daemon
    YtDlp --> Cookie
    Daemon --> PyOrch
    YtDlp --> Val
    Native --> Val
    Daemon --> Val
    Val -->|"SHA-256 Deduplication"| Vault
    Vault --> DB
    DB -->|"Reactive Flow"| Play
```

---

## 3. Provider Capabilities and Routing Matrix

Each provider declares its capabilities through `ProviderCapability` (`PHOTO_POST`, `VIDEO_POST`, `REEL`, `CAROUSEL`, `METADATA`, `SELF_UPDATABLE`) and returns structured `ProviderOutcome` results (`Success`, `UnsupportedMedia`, `RateLimited`, `LoginRequired`, `Cancelled`, `Failure`).

| Content Type | Primary Provider (Android) | Automatic Fallback (Android) | Primary Provider (Python Daemon) | Fallback Chain (Python Daemon) |
| :--- | :--- | :--- | :--- | :--- |
| Reel / Video Post | `yt-dlp` (Embedded + FFmpeg) | `native-graph` -> `daemon-bridge` | `yt-dlp` | `gallery-dl` -> `instaloader-http` |
| Static Photo Post | `native-graph` | `daemon-bridge` (or `yt-dlp` on manual retry) | `gallery-dl` | `instaloader-http` |
| Multi-Slide Carousel | `native-graph` (or `yt-dlp` on user retry) | `yt-dlp` / `native-graph` -> `daemon-bridge` | `gallery-dl` | `yt-dlp` -> `instaloader-http` |
| Zero-Login Public Listing | `HeadlessInstagramScraper` + `InstagramEngine` | Embed / OEmbed API | `InstagramClient` | `Instaloader` |

### Error Classification Rules (`YtDlpEmbeddedProvider`)

| Stderr / Runtime Pattern | Classified `ProviderOutcome` | Orchestrator Action |
| :--- | :--- | :--- |
| `There is no video in this post`, `no video formats found` | `UnsupportedMedia` | Advance immediately to `NativeGraphProvider` without penalizing health |
| `429`, `Too Many Requests`, `rate-limit` | `RateLimited` | Trigger circuit-breaker cooldown (`120s * multiplier`, capped at `15m`) and advance |
| `login required`, `checkpoint`, `empty media response` | `LoginRequired` | Mark `LOGIN_REQUIRED` (`30s` cooldown) and advance |
| Process destroyed / coroutine cancelled | `Cancelled` | Halt job cleanly, wipe staging directory and cookie file |
| Network / I/O / parsing error | `Failure` | Increment consecutive failures (`>= 3` triggers `60s` degraded cooldown) and advance |

---

## 4. End-to-End Execution Sequence

```mermaid
sequenceDiagram
    participant UI as ProfileDetail / PostViewer
    participant PI as ProfileInspector
    participant Orch as MediaDownloadOrchestrator
    participant Hlth as ProviderHealthTracker
    participant Yt as YtDlpEmbeddedProvider
    participant Ck as NetscapeCookieBridge
    participant Nat as NativeGraphProvider
    participant Val as MediaFileValidator
    participant Vlt as VaultStorageManager
    participant DB as Room (posts / media_assets)

    PI->>DB: insertPosts(discoveredPosts)
    Note over UI,DB: Post grid renders immediately with zero blocking
    PI->>Orch: enqueueBatch(username, postsWithSlideHints)
    Orch->>Orch: Acquire Semaphore permit (max 2 concurrent)
    Orch->>Hlth: canUseProvider("yt-dlp")

    alt Media hint is video or reel
        Orch->>Yt: downloadPost(request, stagingDir, onProgress)
        Yt->>Ck: withEphemeralCookieFile(encryptedSession)
        Note over Ck: Write 0600 Netscape cookies.txt in noBackupFilesDir
        Yt->>Yt: YoutubeDL.execute(request, processId)
        loop Stdout Progress Callback
            Yt-->>Orch: Downloading(progress%, etaSec, speed) / Merging
            Orch-->>UI: StateFlow<Map<String, PostDownloadState>>
        end
        Yt->>Ck: Zero-fill overwrite & delete cookies.txt (finally)
        alt Video extracted & muxed
            Yt-->>Orch: Success(videoFiles, thumbFiles, infoJsonMetadata)
            Orch->>Hlth: recordSuccess("yt-dlp")
        else UnsupportedMedia / RateLimited / Failure
            Yt-->>Orch: Non-success outcome
            Orch->>Hlth: recordOutcome("yt-dlp")
            Orch->>Nat: downloadPost(request, stagingDir, onProgress)
            Nat-->>Orch: Success(hdAssets, metadata)
        end
    else Media hint is image or carousel
        Orch->>Nat: downloadPost(request, stagingDir, onProgress)
        Nat-->>Orch: Success(orderedSlides[0..N-1], metadata)
    end

    Orch-->>UI: State = Encrypting
    loop For each staged media asset
        Orch->>Val: validateAndHash(file)
        Note over Val: Check size bounds, reject HTML/JSON, verify magic bytes, compute SHA-256
        Orch->>DB: mediaAssetDao.findByHash(username, sha256)
        alt Identical SHA-256 exists in Vault
            Note over Orch,Vlt: Reuse existing encrypted Vault path (zero duplicate writes)
        else New unique binary
            Orch->>Vlt: saveEncryptedFile(username, "posts", filename, bytes)
        end
    end
    Orch->>DB: upsertAssets(1..N) & insertPost(updatedPost)
    Orch->>Orch: Delete stagingDir (finally)
    Orch-->>UI: State = Completed(providerId)
```

---

## 5. Circuit Breaker and Job State Machines

### Provider Health Lifecycle (`ProviderHealthTracker`)

```mermaid
stateDiagram-v2
    [*] --> HEALTHY
    HEALTHY --> DEGRADED: consecutiveFailures >= 3 (60s cooldown)
    HEALTHY --> RATE_LIMITED: HTTP 429 (2m -> 4m -> 10m -> 15m cap)
    HEALTHY --> LOGIN_REQUIRED: Auth / Checkpoint wall (30s cooldown)
    DEGRADED --> HEALTHY: Cooldown expired + Success or Manual Reset
    RATE_LIMITED --> HEALTHY: Cooldown expired + Success or Manual Reset
    LOGIN_REQUIRED --> HEALTHY: Session updated / Cooldown expired + Success
```

### Per-Post Download Job Lifecycle (`PostDownloadState`)

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Queued: enqueuePost / enqueueBatch
    Queued --> Extracting: Semaphore permit acquired
    Extracting --> Downloading: Stream headers / yt-dlp progress
    Downloading --> Merging: FFmpeg muxing ([Merger] / [ffmpeg])
    Downloading --> Encrypting: Single-stream complete
    Merging --> Encrypting: Muxed MP4 ready
    Encrypting --> Completed: Magic-byte + SHA-256 + AES-256-GCM Vault OK
    Queued --> Cancelled: cancelDownload()
    Extracting --> Cancelled: destroyProcessById()
    Downloading --> Cancelled: destroyProcessById()
    Merging --> Cancelled: destroyProcessById()
    Extracting --> Failed: All eligible providers exhausted
    Downloading --> Failed: All eligible providers exhausted
    Encrypting --> Failed: Integrity validation rejected payload
    Completed --> Queued: User manual Re-download
    Failed --> Queued: User manual Retry
    Cancelled --> Queued: User manual Retry
```

---

## 6. Persistence Schema (`Room Database v7`)

Lossless `MIGRATION_6_7` upgrades existing installations without discarding targets or previously archived posts.

```mermaid
erDiagram
    POSTS {
        string id PK "Instagram shortcode"
        string username "Indexed target handle"
        string caption
        string displayUrl
        string localPath "Primary Vault .enc path"
        string localThumbPath "Encrypted thumbnail .enc path"
        boolean isVideo
        string mediaType "image | video | carousel"
        int slideCount
        int durationSeconds
        int likeCount
        int commentCount
        long postedAt
        string downloadEngine "yt-dlp | native-graph | daemon-bridge"
        string downloadStatus "pending | complete | partial | failed"
    }

    MEDIA_ASSETS {
        string id PK "postId_position"
        string postId "Indexed FK to posts.id"
        string username "Composite index with sha256Hash"
        int position "Carousel slide order (0..N-1)"
        string mediaType "image | video"
        string remoteUrl
        string localVaultPath "AES-256-GCM Vault path"
        string thumbVaultPath "Thumbnail Vault path"
        string mimeType
        long fileSize
        string sha256Hash "64-char hex digest"
        int width
        int height
        int durationSeconds
        string providerName
        long archivedAt
    }

    PROVIDER_HEALTH {
        string providerId PK "yt-dlp | native-graph | daemon-bridge"
        string status "HEALTHY | DEGRADED | RATE_LIMITED | LOGIN_REQUIRED"
        int consecutiveFailures
        long cooldownUntil "Epoch ms"
        long successCount
        long failureCount
        string lastError "Sanitized diagnostic string"
        long updatedAt
    }

    POSTS ||--o{ MEDIA_ASSETS : "contains 1..N ordered slides"
```

---

## 7. Security, Privacy, and Integrity Controls

1. **Ephemeral Cookie Isolation (`NetscapeCookieBridge`):**
   - Session cookies remain encrypted at rest inside `SessionManager` (Android KeyStore AES-256-GCM).
   - For `yt-dlp` execution, a temporary Netscape HTTP Cookie File is created inside `context.noBackupFilesDir/ytdlp_cookies/` with owner-only read/write permissions (`0600`).
   - A mandatory `finally` block overwrites the file with zero bytes, invokes `fd.sync()`, and unlinks it. Any orphaned files from hard process kills are purged on application startup (`warmUpAsync()`).
2. **Log Sanitization (`LogSanitizer`):**
   - Strips `sessionid`, `csrftoken`, `ds_user_id`, `rur`, `mid`, `ig_did`, `Authorization`, `Cookie`, and signed CDN query parameters (`oh`, `oe`, `_nc_sid`, `signature`, `token`) before logging or persisting `provider_health.lastError`.
3. **CDN Host Allowlist (`MediaUrlGuard`):**
   - Rejects non-HTTPS external URLs and restricts remote downloads to verified Meta/Instagram domains (`instagram.com`, `cdninstagram.com`, `fbcdn.net`, `fbsbx.com`). Local HTTP is permitted solely for the explicitly enabled `DaemonBridgeProvider`.
4. **Binary Magic-Byte Validation (`MediaFileValidator`):**
   - Enforces file size boundaries (`128 B` to `250 MB`), inspects leading file headers (`JPEG`, `PNG`, `GIF`, `WEBP`, `ISO BMFF / MP4 ftyp`, `WebM/MKV`), and explicitly rejects HTML/XML/JSON payloads returned by login redirects with `HTTP 200`.
5. **SHA-256 Deduplication & Vault Encryption:**
   - Computes streaming SHA-256 digests prior to encryption. If an identical binary digest already exists for the target user, the existing Vault `.enc` path is referenced instead of duplicating ciphertext on disk.
6. **Controlled Playback & Export (`VaultPlaybackBridge`):**
   - External video players and Android Share sheets cannot read `.enc` ciphertext directly. `VaultPlaybackBridge` decrypts the selected asset on demand into `cacheDir/playback_tmp/`, exposes a scoped `FileProvider` URI (`${applicationId}.fileprovider`), and prunes temporary files older than 30 minutes.

---

## 8. Native Packaging, Runtime Updates, and Licensing

- **Android Native Packaging (`android/app/build.gradle.kts`):**
  - Integrates `io.github.junkfood02.youtubedl-android:library:0.18.1` and `ffmpeg:0.18.1`.
  - Scopes native libraries to `arm64-v8a` (`libpython.zip.so`, `libffmpeg.zip.so`, `libffmpeg.so`, `libffprobe.so`, `libqjs.so`) with `packaging.jniLibs.useLegacyPackaging = true` for Android 14/15 compatibility.
- **In-App Binary Self-Updater:**
  - `YtDlpEmbeddedProvider.updateBinary(channel)` supports runtime `STABLE` and `NIGHTLY` updates from `SettingsScreen` without rebuilding or reinstalling the APK.
- **License Isolation:**
  - The Android APK bundles only permissive / unencumbered media components (`youtubedl-android`, `yt-dlp`, Kotlin/OkHttp).
  - In the optional Python daemon (`engine/providers/gallerydl_provider.py`), `gallery-dl` (GPL-2.0) is invoked strictly as an external CLI subprocess and is never statically linked into the Android application.

---

## 9. Component Reference Map

| Layer | File | Responsibility |
| :--- | :--- | :--- |
| Android Provider Interface | `android/.../engine/media/MediaProvider.kt` | Contracts, capabilities, quality presets, state/outcome models |
| Android Security & Validation | `android/.../engine/media/SecurityIntegrity.kt` | `LogSanitizer`, `MediaUrlGuard`, `MediaFileValidator` (magic bytes + SHA-256) |
| Android Cookie Bridge | `android/.../engine/media/NetscapeCookieBridge.kt` | Ephemeral `0600` Netscape cookie generation and secure wipe |
| Android Video/Reel Provider | `android/.../engine/media/YtDlpEmbeddedProvider.kt` | Embedded `yt-dlp` + FFmpeg execution, progress telemetry, cancellation, self-update |
| Android Photo/Carousel Provider | `android/.../engine/media/NativeGraphProvider.kt` | Sidecar carousel extraction, Embed/OEmbed enrichment, HD HTTP fallback |
| Android Daemon Provider | `android/.../engine/media/DaemonBridgeProvider.kt` | Optional bridge to local/remote Rust+Python daemon |
| Android Circuit Breaker | `android/.../engine/media/ProviderHealthTracker.kt` | Persistent cooldown and failure state tracking |
| Android Orchestrator | `android/.../engine/media/MediaDownloadOrchestrator.kt` | Bounded queue (`Semaphore(2)`), routing, SHA-256 dedup, atomic Vault ingestion |
| Android Playback Bridge | `android/.../util/VaultPlaybackBridge.kt` | On-demand Vault decryption + `FileProvider` URI provisioning |
| Python Daemon Stack | `engine/providers/{base,ytdlp,gallerydl,instaloader,orchestrator}.py` | Cross-platform pluggable provider protocol, GPL-isolated CLI execution, SHA-256 index |
