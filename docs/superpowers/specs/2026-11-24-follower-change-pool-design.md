# Follower Change Pool & High-Velocity Account Protection Design

Date: 2026-11-24
Project: InstaLogger (Android)

## 1. Problem Statement & Intent

Large or fast-growing Instagram accounts can gain or lose followers every few seconds. Under the current instant-alert pipeline in `ProfileInspector`, every `+1` or `-1` tick triggers both a `ChangeLogEntity` row and a status-bar/in-app notification, creating severe notification fatigue and noisy timelines. In addition:

1. Instagram CDN edge caches frequently oscillate by `+/-1` between consecutive polls (`15000 -> 14999 -> 15000`).
2. `inspectFollowersList()` / `inspectFollowingList()` fetch at most 50 users per call and diff them against the previous 50-user snapshot. On accounts with >50 followers (or when Instagram returns truncated/unordered 47-50 user slices), valid followers sliding off page 1 generate false `follower_lost` alerts.
3. `NotificationCategory.fromEventType()` currently checks `"follower_count_change"` instead of `"followers_change"`, routing follower alerts into `SYSTEM_ALERTS` instead of `ACTIVITY_UPDATES`.

## 2. Core Design Principle

**100% user-controlled and non-destructive:**
- Classic instant alerting (`OFF` / Instant mode) remains fully supported both globally and per profile.
- Every pooling, digest, cooldown, anti-flicker, spike-detection, audit-suppression, and completeness-guard mechanism is individually controllable by the user.
- Per-profile settings can inherit the global policy (`GLOBAL`), explicitly revert to classic instant mode (`OFF`), or define custom thresholds for a specific large account.

## 3. Architecture & Components

### 3.1. Pool Modes (`FollowerPoolMode`)
- `OFF`: Classic instant mode (alert on every count delta >= 1).
- `FIXED_COUNT`: Accumulate deltas until `|currentCount - poolBaseline| >= fixedThreshold`.
- `PERCENTAGE`: Accumulate deltas until `|currentCount - poolBaseline| >= max(1, ceil(currentCount * percent / 100.0))`.
- `AUTO_ADAPTIVE`: Automatically computes threshold from account size tier:
  - `< 1,000` -> `1`
  - `1,000 .. 9,999` -> `10`
  - `10,000 .. 99,999` -> `50`
  - `100,000 .. 999,999` -> `250`
  - `1,000,000 .. 9,999,999` -> `1,000`
  - `>= 10,000,000` -> `5,000`

### 3.2. Complementary Protection Mechanisms
1. **Time-Window Digest (`digestWindowMinutes`)**: Optional periodic flush (`0` = off, presets `15m / 1h / 6h / 24h`). If `netDelta != 0` and `(now - poolStartedAt) >= window`, emits a single summary alert and resets the baseline.
2. **Notification Cooldown (`cooldownMinutes`)**: Optional minimum interval (`0` = off, presets `1m / 5m / 15m / 60m`) between follower alerts for the same account. Changes continue accumulating cleanly during cooldown and flush on the next eligible check.
3. **CDN Anti-Flicker / Hysteresis (`antiFlickerEnabled`)**:
   - Inside Pool mode: oscillating values (`15000 -> 14999 -> 15000`) automatically cancel out to `netDelta = 0` without alerting.
   - Inside Classic (`OFF`) mode when enabled: a single-poll `+/-1` or `+/-2` change is held for 1 confirmation poll (`poolPendingFlickerCount`); if the next poll reverts, the jitter is silently discarded.
4. **Spike / Anomaly Breakthrough (`spikeAlertEnabled`, `spikeMultiplier`)**:
   - If a single-poll jump exceeds `max(threshold * spikeMultiplier, ceil(currentCount * 0.05))` (or `>= threshold * spikeMultiplier` when threshold >= 10), it bypasses cooldown and digest windows immediately and fires a dedicated `follower_spike` high-priority alert.
5. **Audit Timeline Control (`suppressMinorAuditLogs`)**:
   - When `true` (default), micro-changes inside an open pool do not spam `ChangeLogEntity`; a consolidated entry is written on flush (showing baseline -> current, net delta, and gross `+gain / -loss`).
   - When `false`, micro-changes are silently recorded in `ChangeLogEntity` while push notifications wait for the pool flush.
6. **List Completeness Safety Guard (`listCompletenessGuardEnabled`)**:
   - Evaluates fetched list completeness (`COMPLETE`, `PARTIAL`, `EMPTY_UNEXPECTED`).
   - On `EMPTY_UNEXPECTED`, skips diffing entirely.
   - On `PARTIAL` (e.g., 50 users fetched on a 15,000-follower account), suppresses false `follower_lost` / `following_lost` alerts caused by pagination window shifts.

### 3.3. Data Persistence
- **Global defaults** in `AppSettingsManager` (`SharedPreferences` + `StateFlow`).
- **Per-target overrides & live accumulator state** in `TargetEntity` (Room DB `v8 -> v9`, lossless `MIGRATION_8_9`):
  - Override config: `poolOverrideMode` (`"GLOBAL"` default), `poolCustomThreshold`, `poolCustomPercent`, `poolCustomDigestMinutes` (`-1` = inherit), `poolCustomCooldownMinutes` (`-1` = inherit), `poolApplyToFollowing` (`-1` = inherit).
  - Live accumulator state: `poolFollowerBaseline`, `poolFollowerStartedAt`, `poolFollowerLastAlertAt`, `poolFollowerGrossGain`, `poolFollowerGrossLoss`, `poolFollowingBaseline`, `poolFollowingStartedAt`, `poolFollowingLastAlertAt`, `poolPendingFlickerCount`.
- Existing targets migrate with `poolOverrideMode = 'GLOBAL'` and global default `OFF`, preserving exact classic behavior until the user opts in.

### 3.4. Domain Engines (Pure Kotlin, Unit-Tested)
- `engine/pool/FollowerPoolEngine.kt`: Stateless evaluation engine (`resolveEffectiveThreshold`, `evaluateChange`, `manualFlush`) returning deterministic `PoolEvaluationResult`.
- `engine/pool/ListCompletenessGuard.kt`: Completeness classification and safe diff gating.

### 3.5. User Interface
- **Global controls**: New collapsible/structured **"Follower Pool & Protection"** section in `NotificationSettingsBottomSheet.kt` with mode chips, threshold presets + custom input, digest/cooldown chips, and toggles for every protection layer.
- **Per-profile controls**:
  - Top-bar Pool Settings button (`Icons.Default.Tune`) in `ProfileDetailScreen.kt` opening `TargetPoolSettingsBottomSheet.kt`.
  - Compact clickable **Live Pool Status Pill** in `ProfileHeroSection` showing accumulated progress (e.g., `Pool: +34 / 50` or `Instant`) with a gentle one-tap suggestion chip on large accounts (`>= 10K` followers) when still in Instant mode.
  - Inside `TargetPoolSettingsBottomSheet.kt`: live pool progress card (`baseline`, `current`, `netDelta`, `+gain / -loss`, `effectiveThreshold`), one-tap **Flush / Reset Pool** button, and full per-account overrides.
- **Localization**: Full strings in `values/`, `values-az/`, `values-en/`, and `values-ru/`.

## 4. Verification Plan
- Pure JUnit tests in `FollowerPoolEngineTest.kt` covering:
  - Classic (`OFF`) mode with and without anti-flicker hysteresis
  - `FIXED_COUNT`, `PERCENTAGE`, and `AUTO_ADAPTIVE` thresholds
  - Accumulation across multiple polls, cancellation of CDN oscillation, threshold flush, digest flush, cooldown suppression, spike breakthrough, and manual flush
  - `ListCompletenessGuard` (`COMPLETE`, `PARTIAL`, `EMPTY_UNEXPECTED`)
- Build APK, install via Shizuku (`bash build.sh`), and run `./gradlew testDebugUnitTest`.
