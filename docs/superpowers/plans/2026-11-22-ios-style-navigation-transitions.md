# iOS-Style Navigation & Menu Transitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the application's navigation into a fluid, 120 FPS, iOS-style transition system featuring directional parallax tab switching for the bottom navigation bar and native `UINavigationController`-style Push/Pop hierarchy for sub-screens.

**Architecture:** Replace the current static 14% horizontal hopping with a dual-tier navigation system: (1) Sibling bottom tabs navigate via index-aware directional parallax slide & crossfade (animating rightwards when moving to higher tab indices and leftwards when returning to lower tab indices) with retained composable states; (2) Hierarchical push/pop routes (such as `ProfileDetailScreen`) execute a full-width 100% slide with a 33% parallax dimmed underlay and hardware-accelerated spring curves.

**Tech Stack:** Jetpack Compose Navigation (`androidx.navigation.compose`), Jetpack Compose Animation (`androidx.compose.animation`), Compose Physics Springs (`androidx.compose.animation.core.spring`), Kotlin Coroutines.

---

## Global Constraints

- **Platform Target:** Android 8.0+ (API 26), optimized for Android 12+ (API 31+) hardware-accelerated graphics layers.
- **Frame Rate Target:** Continuous 60/120 FPS with zero dropped frames during rapid transitions.
- **Zero Layout Shifts:** Transitions must preserve scroll offsets and list states across visited tabs without cold re-inflation.
- **Git Isolation:** Local build tools (`build.sh`, `scripts/`) must remain untracked and excluded from GitHub commits via `.gitignore`.
- **Zero-Mock Standard:** Navigation states must reflect authentic routes without synthetic delays or demo wrappers.

---

## Review Focus

1. **Rapid Tab Spamming:** User taps tabs in quick succession (e.g. Dashboard -> Settings -> Dashboard within 200ms). System must cancel or snap gracefully without overlapping ghost screens or corrupting backstack.
2. **Horizontal List / Card Conflict:** Horizontal scroll elements within screens (e.g. stats pills, avatar history carousels) must not conflict with tab transition gesture scopes.
3. **Hardware Acceleration & Overdraw:** Ensure `graphicsLayer` clipping and render passes avoid multi-layer overdraw when outgoing and incoming screens momentarily overlap during transitions.
4. **Deep Linking / Direct Route Jumps:** Navigating directly to `Screen.TargetDetail` from an external notification or shortcut must cleanly perform iOS Push without requiring tab-index comparison.
5. **Back Button Symmetry:** Pressing the Android system Back button or the in-app Back arrow from `ProfileDetailScreen` must always trigger the exact reverse parallax Pop animation (previous screen slides in from -33% to 0%, current screen exits right to 100%).

---

### Task 1: Refactor `MotionSpecs.kt` with iOS-Standard Physics

**Files:**
- Modify: `android/app/src/main/java/com/instalogger/app/ui/animation/MotionSpecs.kt`

**Details:**
1. Define official iOS Fluid Spring parameters:
   - `dampingRatio = 0.88f`
   - `stiffness = 380f`
2. Create `IosPushEnter`: Full-width 100% horizontal slide from right (`+fullWidth`) with spring curve.
3. Create `IosPushExit`: Underlay parallax slide to left by 33% (`-fullWidth / 3`) with subtle dimming fade.
4. Create `IosPopEnter`: Underlay parallax return from left by 33% (`-fullWidth / 3` -> `0`) with fade-in.
5. Create `IosPopExit`: Full-width 100% horizontal slide to right (`+fullWidth`) with spring curve.
6. Create `TabSlideForwardEnter`, `TabSlideForwardExit`, `TabSlideBackwardEnter`, `TabSlideBackwardExit` for index-aware bottom navigation tab changes.

- [ ] **Step 1.1:** Add iOS spring specs and push/pop transition definitions to `MotionSpecs.kt`.
- [ ] **Step 1.2:** Add directional tab slide helper functions accepting `(initialIndex, targetIndex)` into `MotionSpecs.kt`.
- [ ] **Step 1.3:** Verify Kotlin syntax with clean compilation check.

---

### Task 2: Implement Direction-Aware Tab Navigation in `MainActivity.kt`

**Files:**
- Modify: `android/app/src/main/java/com/instalogger/app/MainActivity.kt`

**Details:**
1. Track the current and previous bottom navigation route indices:
   - `Screen.Dashboard` = Index 0
   - `Screen.Notifications` = Index 1
   - `Screen.Session` = Index 2
   - `Screen.Settings` = Index 3
2. In `NavHost`:
   - Inspect `initialState.destination.route` and `targetState.destination.route`.
   - If transitioning between sibling tabs:
     - Compare indices: if `targetIndex > initialIndex`, apply `TabSlideForwardEnter` and `TabSlideForwardExit`.
     - If `targetIndex < initialIndex`, apply `TabSlideBackwardEnter` and `TabSlideBackwardExit`.
   - If transitioning to/from `Screen.TargetDetail`:
     - Apply hierarchical `IosPushEnter` / `IosPushExit` and `IosPopEnter` / `IosPopExit`.

- [ ] **Step 2.1:** Implement route index resolution helper in `MainActivity.kt`.
- [ ] **Step 2.2:** Configure `enterTransition`, `exitTransition`, `popEnterTransition`, and `popExitTransition` in `NavHost` using the dynamic index comparator.
- [ ] **Step 2.3:** Add `saveState = true` and `restoreState = true` to bottom navigation item clicks to guarantee zero state loss when switching tabs.

---

### Task 3: Smooth Pill Interpolation in `AnimatedBottomBar.kt`

**Files:**
- Modify: `android/app/src/main/java/com/instalogger/app/ui/components/AnimatedBottomBar.kt`

**Details:**
1. Update `AnimatedBottomBar`'s sliding active capsule pill to use iOS-tuned spring physics:
   - `dampingRatio = 0.85f, stiffness = 420f`
2. Ensure tab icon scaling and haptic feedback trigger instantly on touch down for tactile responsiveness.
3. Optimize background clip and border to avoid any redraw stutter while the capsule glides across tabs.

- [ ] **Step 3.1:** Adjust capsule pill spring physics in `AnimatedBottomBar.kt`.
- [ ] **Step 3.2:** Verify tab clicks deliver immediate tactile haptic pulse and silky visual tracking.

---

### Task 4: Hierarchical UINavigationController Polish on `ProfileDetailScreen.kt`

**Files:**
- Modify: `android/app/src/main/java/com/instalogger/app/ui/screens/ProfileDetailScreen.kt`

**Details:**
1. Ensure the top app bar back button (`onBack`) calls `navController.popBackStack()` which triggers `IosPopExit` and `IosPopEnter`.
2. Add subtle shadow/scrim to the left border of `ProfileDetailScreen` during slide-in to create authentic iOS depth layering against the dimmed dashboard underlay.

- [ ] **Step 4.1:** Verify back button and route navigation integration.
- [ ] **Step 4.2:** Test edge-to-edge rendering and transition shadow overlay.

---

### Task 5: Compilation, Build, Device Installation & Verification

**Files:**
- Execute: Build script (`./build.sh`)
- Verify: Android device via Shizuku (`rish`)

**Details:**
1. Sync files to high-performance build server (`130.162.166.123`).
2. Run `./gradlew assembleDebug --parallel --max-workers=12`.
3. Fetch APK to `/storage/emulated/0/instalogger/InstaLogger.apk`.
4. Install on device via `rish -c "pm install -r ..."`.
5. Verify on real device:
   - Switching tabs (0 -> 1 -> 2 -> 3) slides smoothly rightwards.
   - Switching tabs backwards (3 -> 2 -> 1 -> 0) slides smoothly leftwards.
   - Clicking a target card pushes `ProfileDetailScreen` from right edge with 33% parallax dashboard underlay.
   - Clicking back pops `ProfileDetailScreen` to right, restoring dashboard without re-query delay.

- [ ] **Step 5.1:** Compile and build on remote server.
- [ ] **Step 5.2:** Install on device and confirm smooth 60/120 FPS transitions.
- [ ] **Step 5.3:** Commit clean implementation to Git and push to GitHub `origin/main`.
