# Clipboard System: Bug Fixes & Improvements TODO

This document outlines the necessary changes to resolve identified issues with the "Unlimited History" setting, media deduplication during import, and UI/UX inconsistencies in the clipboard system.

---

## 1. Sync "Duration" and "Limit" in UI
**Issue:** Users can set "Entry Duration" to "Never expire" without realizing that the "History Limit" (default: 50) will still prune their history.
- [x] In `SettingsActivity.kt` (NOT dead ClipboardSettingsActivity), when Entry Duration slider moves to "-1" (Never expire), auto-set History Limit to 0 (Unlimited).
- [x] Contextual description warns in both mismatch directions: "count unlimited but entries expire" and "entries never expire but capped at N".
- [x] Auto-link: `if (clipboardHistoryDuration == -1 && clipboardHistoryLimit > 0)` → sets limit to 0.
  **Commits:** cc61f9f5d

## 2. Fix Content Hash Preservation in Import
**Issue:** `ClipboardDatabase.importFromJSON` recomputed `content_hash` using `String.hashCode()`. This broke SHA-256 based deduplication for v4 media entries.
- [x] Modified `importHistoryEntries`, `importPinnedEntries`, and `importTodoEntries` in `ClipboardDatabase.kt`.
- [x] Uses exported `content_hash` from JSON when available via `entry.optString("content_hash", "").ifEmpty { ... }`.
- [x] Falls back to `content.hashCode().toString()` only for older (v1/v2) exports or if hash is missing.
- [x] History export now includes `content_hash` column (was previously omitted).
- [x] Media dedup uses hash-only query (matches live capture); text dedup uses hash+content.
  **Commits:** 81e4d9e41

## 3. Refine "Unlimited" Logic (0 vs 100)
**Issue:** `ClipboardSettingsActivity` uses `100` as a UI sentinel for "Unlimited" but the underlying preference uses `0`.
- [x] SettingsActivity uses clean 0..500 range where 0 = "Unlimited" (no sentinel mapping needed).
- [x] `Config.kt` Defaults block documents `0 = Unlimited` convention with explicit warnings about guard conditions.
- [x] Initial mutableStateOf value changed from hardcoded 6 to `Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK` (50).
  **Commits:** 81e4d9e41, cc61f9f5d

## 4. Proactive Expiry Rescue
**Issue:** `rescueExpiredEntries()` was only called in the `ClipboardHistoryService` `init` block (startup).
- [x] Added `onDurationSettingChanged()` static method to ClipboardHistoryService — called from SettingsActivity when duration slider changes. Re-reads fresh pref via `Config.reloadClipboardDuration()`.
- [x] Added rescue call in `set_history_enabled(true)` path.
- [x] Mid-session changes take effect immediately without keyboard restart.
  **Commits:** cc61f9f5d

## 5. Verification Tasks
- [ ] **Test v2 Import:** Import a JSON export with `export_version: 2` (containing `active_entries`, `pinned_entries`, and `todo_entries`). Verify all items land in the correct modern tables.
- [ ] **Test Media Dedup:** Export media entries, delete them, import them, and then copy the *same* media file again. Verify the system moves the imported entry to the top instead of creating a duplicate.
- [ ] **Test Unlimited Pruning:** Set Limit to 5 and Duration to Never. Copy 6 items. Verify the 1st item is deleted. Set Limit to Unlimited. Copy 10 items. Verify all 10 remain.
