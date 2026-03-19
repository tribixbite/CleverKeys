# Clipboard Codebase Analysis — Performance, Safety, Edge Cases

**Date**: 2026-03-18
**Scope**: ~1500 LOC across 8 core clipboard files

## Fix Queue (priority order)

### 1. CRITICAL: UI Thread DB Blocking (#71) — `ClipboardHistoryView`
- **Status**: DONE (e159dca56)
- **File**: `ClipboardHistoryView.kt:51-58, 254-263`
- **Problem**: `init{}` and `update_data()` call `clearExpiredAndGetHistory()` synchronously on UI thread. With 10K+ entries = 2-3s freeze.
- **Also affects**: `ClipboardPinView.kt:28-31` — `refresh_pinned_items()` same pattern.
- **Fix**: CoroutineScope in onAttachedToWindow, cancel in onDetachedFromWindow. DB calls via `withContext(Dispatchers.IO)`. Replace history reference atomically on Main thread.
- **Risk**: Medium. Must handle pre-load empty state for callbacks.
- **LOE**: Medium

### 2. CRITICAL: `getStorageStats()` Full Table Scan + String Allocation Storm
- **Status**: DONE (8c5a1c4c5)
- **File**: `ClipboardDatabase.kt:264-283`
- **Problem**: SELECTs ALL content for ALL rows, loads full text into JVM heap, creates byte[] per row just to measure size. 10K entries → ~10MB+ transient allocations.
- **Fix**: Use `SELECT LENGTH(content), is_pinned, expiry_timestamp` — SQLite LENGTH() returns UTF-8 byte count without loading content.
- **Risk**: Low
- **LOE**: Small

### 3. HIGH: Hash Collision Risk — `String.hashCode()` as Dedup Key
- **Status**: PENDING
- **File**: `ClipboardDatabase.kt:50, 395, 425, 456`
- **Problem**: 32-bit hash → 50% collision probability at ~77K entries. JVM-specific (not portable across ART versions). Dedup query does also compare content text, so no data loss, but index effectiveness degrades.
- **Fix**: SHA-256 truncated to hex prefix (16 chars = 64 bits), or at minimum a longer hash.
- **Risk**: Low (needs migration for existing data)
- **LOE**: Small

### 4. HIGH: `NonScrollListView` Measures ALL Children — O(n) Layout
- **Status**: PENDING
- **File**: `NonScrollListView.kt:19-25`
- **Problem**: `MeasureSpec(Int.MAX_VALUE shr 2, AT_MOST)` forces ListView to measure ALL items on every layout pass. 100 items/page = 100 getView() + measure() calls per notifyDataSetChanged().
- **Fix**: Consider RecyclerView migration, or cap max measured height to visible area.
- **Risk**: Medium (layout change)
- **LOE**: Medium

### 5. HIGH: `applySizeLimitBytes` — Unbounded SQL IN Clause + Heap Load
- **Status**: DONE (8c5a1c4c5)
- **File**: `ClipboardDatabase.kt:316-343`
- **Problem**: Loads ALL non-pinned content into JVM heap to measure sizes, builds potentially 49K-element IN clause. Exceeds SQLITE_MAX_SQL_LENGTH with enough entries.
- **Fix**: Use SQL-side LENGTH(), batch deletes in chunks of 500, or use subquery DELETE.
- **Risk**: Low
- **LOE**: Small

### 6. MEDIUM: `importFromJSON` — No Transaction, No Batching
- **Status**: DONE (2d283f2eb)
- **File**: `ClipboardDatabase.kt:386-486`
- **Problem**: Individual INSERT + SELECT per entry with no wrapping transaction. 2000 entries = 2000 fsync() calls = 30-60 seconds on flash.
- **Fix**: Wrap in beginTransaction/setTransactionSuccessful/endTransaction.
- **Risk**: Low
- **LOE**: Small

### 7. MEDIUM: `ClipboardEntry.getFormattedText()` — Allocations Per Render
- **Status**: DONE (90d126a5b)
- **File**: `ClipboardEntry.kt:52-68`
- **Problem**: 4 allocations per item per render (String concat + SpannableString + ForegroundColorSpan + deprecated getColor). Called 100x per notifyDataSetChanged().
- **Fix**: Cache color value, use StringBuilder, or cache formatted text with TTL.
- **Risk**: Low
- **LOE**: Small

### 8. MEDIUM: Clipboard Listener Never Actually Removed
- **Status**: DONE (cda7b96f9)
- **File**: `ClipboardHistoryService.kt:46, 64-75`
- **Problem**: `addPrimaryClipChangedListener(SystemListener())` but `unregisterClipboardListener()` just sets flag to false, never calls `removePrimaryClipChangedListener()`.
- **Fix**: Store listener instance, call `_cm.removePrimaryClipChangedListener(_systemListener)`.
- **Risk**: Low
- **LOE**: Tiny

### 9. MEDIUM: `_service` Singleton Not Synchronized
- **Status**: DONE (cda7b96f9)
- **File**: `ClipboardHistoryService.kt:453-455`
- **Problem**: Plain null check, no synchronization. Double init possible from multiple threads.
- **Fix**: Use `synchronized(this)` or `@Volatile` + double-checked locking.
- **Risk**: Low
- **LOE**: Tiny

### 10. MEDIUM: `expandedStates` Keyed by Position, Not ID
- **Status**: DONE (90d126a5b)
- **Files**: `ClipboardHistoryView.kt:36`, `ClipboardPinView.kt:18`
- **Problem**: Position-based keys invalidated on delete/reorder. States migrate to wrong entries after structural changes.
- **Fix**: Key by entry content hash or database ID.
- **Risk**: Low
- **LOE**: Small

### 11. MEDIUM: TODO Items Appear in Both History and Todos Tabs
- **Status**: DONE (a9010cb84)
- **File**: `ClipboardDatabase.kt:93-94`
- **Problem**: `getActiveClipboardEntries()` doesn't filter `is_todo=0`, so TODO items show in History tab too.
- **Fix**: Add `AND is_todo = 0` to the WHERE clause.
- **Risk**: Low
- **LOE**: Tiny

### 12. LOW: Slider 100 ≠ Unlimited (0) Mismatch
- **Status**: DONE (a9010cb84)
- **File**: `ClipboardSettingsActivity.kt:330`
- **Problem**: Slider shows "Unlimited" at 100 but saves 100 to prefs. Config uses 0 as unlimited sentinel. So "Unlimited" actually enforces a 100-entry cap.
- **Fix**: Map slider max to 0 (unlimited) in onValueChange, or adjust sentinel.
- **Risk**: Low
- **LOE**: Tiny

### 13. LOW: `SimpleDateFormat` Created Per Call
- **Status**: DONE (90d126a5b)
- **File**: `ClipboardEntry.kt:44`
- **Problem**: Expensive construction (parses format, loads locale) on every call.
- **Fix**: Companion object constant or ThreadLocal.
- **Risk**: Low
- **LOE**: Tiny

### 14. LOW: Deprecated `getColor()` in ClipboardEntry
- **Status**: DONE (90d126a5b)
- **File**: `ClipboardEntry.kt:59`
- **Problem**: `context.resources.getColor()` deprecated, allocates per call.
- **Fix**: Use `ContextCompat.getColor()` and cache.
- **Risk**: Low
- **LOE**: Tiny
