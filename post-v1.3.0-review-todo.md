# Post-v1.3.0 Technical Review TODO

This document tracks technical debt, performance bottlenecks, and architectural risks identified during the review of changes between v1.3.0 and v1.15.0.

---

## 1. UI Performance & Memory (High Priority)
- [ ] **RecyclerView Migration:** `ClipboardHistoryView` (currently `NonScrollListView`) MUST be migrated to `RecyclerView` or a recycled `ListView`. Disabling recycling while decoding bitmaps in `getView` is a major performance risk.
- [x] **Thumbnail Caching:** Implement a simple LRU cache for media thumbnails in `ClipboardHistoryView`. Currently, `BitmapFactory.decodeByteArray` is called on every frame during scrolling/drawing.
  **Commit:** da40475c0 — 30-entry count-based LruCache keyed by timestamp, evicted on tab/page switch.
- [x] **Media Cleanup:** Update `ClipboardDatabase.applySizeLimitBytes` to include `file_size_bytes` in its calculation. Currently, it only prunes based on text length, allowing unlimited media storage growth.
  **Commit:** da40475c0 — SQL now sums `LENGTH(content) + LENGTH(thumbnail_blob)` and stats media files on disk. Returns deleted media paths; service deletes orphaned files.

## 2. Input Routing & State Machine
- [ ] **Explicit State Machine:** Refactor `KeyboardReceiver` and `KeyEventReceiverBridge` to use an explicit `enum` for the active Modal State (NONE, CLIP_SEARCH, CLIP_EDIT, TAG_PANEL, EMOJI, GIF). This prevents multiple modes from being "active" simultaneously.
- [x] **Tag Panel Naming:** Rename `MatrixSwipeRainBackground` and related functions in `LauncherActivity.kt` to `SparkleMagicBackground` to match the current wizard aesthetic and avoid developer confusion.
  **Commit:** 18ec94094

## 3. Architecture & Reliability
- [x] **Reactive Status Checks:** In `LauncherActivity.kt`, replace the 500ms polling loop for `isKeyboardEnabled` with a reactive listener or a `LifecycleResume` check to save battery.
  **Commit:** 9c3f7a912 — ContentObserver on Settings.Secure ENABLED_INPUT_METHODS + DEFAULT_INPUT_METHOD.
- [x] **Bridge Diagnostics:** Remove the "Diagnostic Toasts" in `ClipboardManager.kt` (e.g., `"Tag callback: tab=..."`) before the next stable release.
  **Commit:** 18ec94094 — Removed toast and Log.d, plus unused Toast import.
- [x] **Expanded State Memory:** `ClipboardHistoryView.expandedStates` uses the full entry content string as a key. For very large clips, this duplicates memory. Switch to using the entry's primary key (ID) or a hash.
  **Commit:** 18ec94094 — Keyed by entry timestamp (Long) instead of full content string.

## 4. Edge Case Validation
- [x] **Direct am Import Limit:** ~~Verify the 128KB limit for `am start` intents.~~ **N/A** — import uses direct DB call via `clipboardDb.importFromJSON()`, not `am start` intents. No intent size limit applies.
- [x] **Direct Boot DB Access:** Ensure `ClipboardMediaManager` doesn't attempt to create directories in `filesDir` before the user has unlocked the device (Device Protected vs Credential Protected storage).
  **Commit:** 18ec94094 — `isDeviceUnlocked()` guard on saveMedia, deleteMedia, cleanupOrphans, clearAll. Defense-in-depth (service already guards initialization).

## 5. Build System
- [x] **Resource Duplication:** Verify that `build.gradle`'s new `copyLayoutDefinitions` task doesn't create duplicate entries in the APK's `res/raw` which could bloat the install size.
  **Commit:** 18ec94094 — Removed redundant `copyRawQwertyUS` task; `copyLayoutDefinitions` already copies all layouts from `src/main/layouts/` including `latn_qwerty_us.xml`.

---

## Remaining Open Items

### Item 1: RecyclerView Migration (Heavy)
`ClipboardHistoryView` extends `NonScrollListView` (a `ListView` subclass that forces full height in `onMeasure`). Migration to `RecyclerView` would improve scroll performance with many entries, but is a significant refactor: custom adapter, ViewHolder pattern, layout manager, and all interaction callbacks (drag, swipe, long-press, edit mode) need reimplementation.

### Item 4: Explicit State Machine (Heavy)
`KeyboardReceiver` has `PaneType` enum for pane tracking, but supplementary booleans (`isContentPaneShowing`, `gifSearchActive`) and `ClipboardManager` sub-mode booleans (`searchMode`, `tagMode`, `isEditing()`) remain. A single `ModalState` enum covering all sub-modes would prevent impossible state combinations but requires careful refactoring of the routing logic.
