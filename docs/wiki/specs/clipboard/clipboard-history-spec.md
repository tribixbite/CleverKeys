---
title: Clipboard History - Technical Specification
user_guide: ../../clipboard/clipboard-history.md
status: implemented
version: v1.2.7
---

# Clipboard History Technical Specification

## Overview

The clipboard history system maintains a persistent list of copied text items with support for pinning, search, auto-expiry, and privacy protection.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| ClipboardManager | `ClipboardManager.kt` | History management |
| ClipboardStore | `ClipboardStore.kt` | Persistence layer |
| ClipboardHistoryView | `ClipboardHistoryView.kt` | UI panel |
| PrivacyDetector | `PrivacyDetector.kt` | Sensitive field detection |
| Config | `Config.kt` | Clipboard preferences |

## Data Model

### Clipboard Entry

```kotlin
// ClipboardEntry.kt
data class ClipboardEntry(
    val content: String,
    val timestamp: Long,
    val expiryTimestamp: Long,
    val isPinned: Boolean = false,
    val isTodo: Boolean = false
)
```

### Storage Structure

```kotlin
// ClipboardDatabase.kt (DATABASE_VERSION = 2)
// Stored in SQLite database
CREATE TABLE clipboard_history (
    content TEXT PRIMARY KEY,
    timestamp INTEGER NOT NULL,
    expiry_timestamp INTEGER NOT NULL,
    is_pinned INTEGER DEFAULT 0,
    is_todo INTEGER DEFAULT 0        -- Added in v2
);

CREATE INDEX idx_timestamp ON clipboard_history(timestamp DESC);
CREATE INDEX idx_pinned ON clipboard_history(is_pinned DESC);
CREATE INDEX idx_todo ON clipboard_history(is_todo DESC);
```

### Database Migration (v1 → v2)

```kotlin
// ClipboardDatabase.kt
override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
        // Add is_todo column with default value 0
        db.execSQL("ALTER TABLE clipboard_history ADD COLUMN is_todo INTEGER DEFAULT 0")
    }
}
```

## Tab System

The clipboard pane organizes items into three tabs:

| Tab | Enum | Icon | Query Method |
|-----|------|------|--------------|
| **History** | `ClipboardTab.HISTORY` | 📋 | `clearExpiredAndGetHistory()` |
| **Pinned** | `ClipboardTab.PINNED` | 📌 | `getPinnedEntries()` |
| **Todos** | `ClipboardTab.TODOS` | ✓ | `getTodoEntries()` |

```kotlin
// ClipboardHistoryView.kt
enum class ClipboardTab {
    HISTORY,  // Recent clipboard history (default)
    PINNED,   // Pinned items
    TODOS     // To-do items
}

private var currentTab = ClipboardTab.HISTORY

fun setTab(tab: ClipboardTab) {
    currentTab = tab
    expandedStates.clear()
    update_data()
}
```

### Tab Data Loading

```kotlin
// ClipboardHistoryView.kt
private fun update_data() {
    history = when (currentTab) {
        ClipboardTab.HISTORY -> service?.clearExpiredAndGetHistory() ?: emptyList()
        ClipboardTab.PINNED -> database.getPinnedEntries()
        ClipboardTab.TODOS -> database.getTodoEntries()
    }
    applyFilter()
}

// ClipboardDatabase.kt
fun getPinnedEntries(): List<ClipboardEntry> =
    queryEntries("SELECT * FROM clipboard_history WHERE is_pinned = 1 ORDER BY timestamp DESC")

fun getTodoEntries(): List<ClipboardEntry> =
    queryEntries("SELECT * FROM clipboard_history WHERE is_todo = 1 ORDER BY timestamp DESC")
```

## Pagination

For large histories (>100 items), pagination improves performance:

```kotlin
// ClipboardHistoryView.kt
companion object {
    const val ITEMS_PER_PAGE = 100
}

private var currentPage = 0
private var paginatedHistory: List<ClipboardEntry> = emptyList()
private var onPaginationChangeListener: ((needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit)? = null

private fun applyPagination() {
    val totalItems = filteredHistory.size
    val totalPages = getTotalPages()

    // Ensure current page is valid
    if (currentPage >= totalPages) {
        currentPage = maxOf(0, totalPages - 1)
    }

    // Apply pagination only if more than ITEMS_PER_PAGE
    paginatedHistory = if (totalItems > ITEMS_PER_PAGE) {
        val startIndex = currentPage * ITEMS_PER_PAGE
        val endIndex = minOf(startIndex + ITEMS_PER_PAGE, totalItems)
        filteredHistory.subList(startIndex, endIndex)
    } else {
        filteredHistory
    }

    // Notify listener about pagination state
    onPaginationChangeListener?.invoke(
        totalItems > ITEMS_PER_PAGE,
        currentPage + 1,  // 1-indexed for display
        totalPages
    )
}
```

### Search Across All Items

Search filters ALL items before pagination:

```kotlin
private fun applyFilter() {
    // Filter ALL history items (not paginated)
    val filtered = history.filter { entry ->
        // Apply search filter
        if (searchFilter.isNotEmpty() && !entry.content.lowercase().contains(searchFilter)) {
            return@filter false
        }
        // Apply date filter
        if (dateFilterEnabled) {
            // ... date filter logic
        }
        true
    }
    filteredHistory = filtered

    // Reset to first page when filter changes
    currentPage = 0
    applyPagination()
}
```

## Clipboard Monitoring

### System Clipboard Listener

```kotlin
// ClipboardManager.kt
class ClipboardManager(context: Context) {
    private val systemClipboard = context.getSystemService(
        Context.CLIPBOARD_SERVICE
    ) as android.content.ClipboardManager

    init {
        systemClipboard.addPrimaryClipChangedListener {
            onClipboardChanged()
        }
    }

    private fun onClipboardChanged() {
        if (!config.clipboard_history_enabled) return
        if (isIncognitoMode) return

        val clip = systemClipboard.primaryClip ?: return
        val text = clip.getItemAt(0)?.text?.toString() ?: return

        // Check if from password field
        if (privacyDetector.isFromPasswordField()) return

        // Check for duplicates
        if (isDuplicate(text)) return

        // Add to history
        addToHistory(text, getCurrentPackage())
    }
}
```

### Privacy Detection

```kotlin
// PrivacyDetector.kt
class PrivacyDetector {
    private var lastInputType: Int = 0

    fun onInputTypeChanged(inputType: Int) {
        lastInputType = inputType
    }

    fun isFromPasswordField(): Boolean {
        return lastInputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 ||
               lastInputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0 ||
               lastInputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0
    }
}
```

## History Management

### Add to History

```kotlin
// ClipboardManager.kt
fun addToHistory(text: String, source: String?) {
    val trimmed = text.take(MAX_ITEM_LENGTH)
    val hash = trimmed.hashCode().toString()

    // Remove old duplicate if exists
    store.deleteByHash(hash)

    // Insert new item
    val item = ClipboardItem(
        id = 0,
        text = trimmed,
        timestamp = System.currentTimeMillis(),
        source = source
    )
    store.insert(item)

    // Enforce history limit (excluding pinned)
    enforceLimit()

    // Notify UI
    historyChangedListeners.forEach { it.onHistoryChanged() }
}

private fun enforceLimit() {
    val unpinnedCount = store.getUnpinnedCount()
    if (unpinnedCount > config.clipboard_history_size) {
        val excess = unpinnedCount - config.clipboard_history_size
        store.deleteOldestUnpinned(excess)
    }
}
```

### Pin/Unpin

```kotlin
// ClipboardManager.kt
fun pinItem(itemId: Long) {
    store.updatePinned(itemId, true)
    notifyHistoryChanged()
}

fun unpinItem(itemId: Long) {
    store.updatePinned(itemId, false)
    notifyHistoryChanged()
}
```

## Auto-Expiry

```kotlin
// ClipboardManager.kt
fun cleanExpiredItems() {
    val expiryMs = when (config.clipboard_expiry) {
        ClipboardExpiry.ONE_HOUR -> 60 * 60 * 1000L
        ClipboardExpiry.ONE_DAY -> 24 * 60 * 60 * 1000L
        ClipboardExpiry.ONE_WEEK -> 7 * 24 * 60 * 60 * 1000L
        ClipboardExpiry.NEVER -> return
    }

    val cutoff = System.currentTimeMillis() - expiryMs

    // Delete unpinned items older than cutoff
    store.deleteUnpinnedOlderThan(cutoff)
}

// Called on app start and periodically
init {
    cleanExpiredItems()
    schedulePeriodicCleanup()
}
```

## Search Implementation

```kotlin
// ClipboardManager.kt
fun search(query: String): List<ClipboardItem> {
    if (query.length < 2) return getHistory()

    return store.searchByText("%$query%")
        .sortedWith(
            compareByDescending<ClipboardItem> { it.isPinned }
                .thenByDescending { it.timestamp }
        )
}
```

## Clipboard Pane Layout

The clipboard pane combines tabs, search, date filter, and close button in a single header row:

```xml
<!-- clipboard_pane.xml -->
<!-- Combined row: Tabs + Search + Close (40dp height) -->
<LinearLayout android:id="@+id/clipboard_search_bar">
    <TextView android:id="@+id/tab_history" android:text="📋"/>    <!-- 36dp -->
    <TextView android:id="@+id/tab_pinned" android:text="📌"/>     <!-- 36dp -->
    <TextView android:id="@+id/tab_todos" android:text="✓"/>      <!-- 36dp -->
    <TextView android:id="@+id/clipboard_search" android:layout_weight="1"/>
    <TextView android:id="@+id/clipboard_date_filter" android:text="📅"/>
    <ImageButton android:id="@+id/clipboard_close_button"/>
</LinearLayout>

<!-- ClipboardHistoryView content area -->
<ScrollView>
    <ClipboardHistoryView android:id="@+id/clipboard_history_view"/>
</ScrollView>

<!-- Pagination bar (hidden when ≤100 items) -->
<LinearLayout android:id="@+id/clipboard_pagination_bar" android:visibility="gone">
    <TextView android:id="@+id/clipboard_page_prev" android:text="◀"/>
    <TextView android:id="@+id/clipboard_page_info" android:text="1 / 1"/>
    <TextView android:id="@+id/clipboard_page_next" android:text="▶"/>
</LinearLayout>
```

### ClipboardManager Tab Wiring

```kotlin
// ClipboardManager.kt
fun getClipboardPane(layoutInflater: LayoutInflater): ViewGroup {
    // Set up tab buttons
    tabHistory = clipboardPane?.findViewById(R.id.tab_history)
    tabPinned = clipboardPane?.findViewById(R.id.tab_pinned)
    tabTodos = clipboardPane?.findViewById(R.id.tab_todos)

    tabHistory?.setOnClickListener { switchToTab(ClipboardTab.HISTORY) }
    tabPinned?.setOnClickListener { switchToTab(ClipboardTab.PINNED) }
    tabTodos?.setOnClickListener { switchToTab(ClipboardTab.TODOS) }

    // Set up pagination controls
    pagePrev?.setOnClickListener { clipboardHistoryView?.previousPage() }
    pageNext?.setOnClickListener { clipboardHistoryView?.nextPage() }

    clipboardHistoryView?.setOnPaginationChangeListener { needsPagination, currentPage, totalPages ->
        paginationBar?.visibility = if (needsPagination) View.VISIBLE else View.GONE
        pageInfo?.text = "$currentPage / $totalPages"
        pagePrev?.alpha = if (clipboardHistoryView?.hasPreviousPage() == true) 1.0f else 0.3f
        pageNext?.alpha = if (clipboardHistoryView?.hasNextPage() == true) 1.0f else 0.3f
    }
}

private fun updateTabHighlighting() {
    val activeAlpha = 1.0f
    val inactiveAlpha = 0.5f
    tabHistory?.alpha = if (currentTab == ClipboardTab.HISTORY) activeAlpha else inactiveAlpha
    tabPinned?.alpha = if (currentTab == ClipboardTab.PINNED) activeAlpha else inactiveAlpha
    tabTodos?.alpha = if (currentTab == ClipboardTab.TODOS) activeAlpha else inactiveAlpha
}
```

### Close Button Callback

```kotlin
// ClipboardManager.kt
private var onCloseCallback: (() -> Unit)? = null

fun setOnCloseCallback(callback: () -> Unit) {
    onCloseCallback = callback
}

// In getClipboardPane():
clipboardPane?.findViewById<ImageButton>(R.id.clipboard_close_button)?.setOnClickListener {
    onCloseCallback?.invoke()
}

// KeyboardReceiver.kt
clipboardManager.setOnCloseCallback {
    handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
}
```

## Import/Export with Todos

### Export Format (JSON)

```json
{
  "exportVersion": 2,
  "exportedAt": "2025-01-22T12:00:00Z",
  "count": 500,
  "entries": [
    {
      "content": "clipboard text",
      "timestamp": 1705939200000,
      "isPinned": false,
      "isTodo": true
    }
  ]
}
```

### Import with Fresh Expiry

```kotlin
// ClipboardDatabase.kt
fun importEntry(entry: JSONObject, addedCounts: IntArray): Boolean {
    // Use fresh expiry timestamp so imported entries don't expire immediately
    val freshExpiry = System.currentTimeMillis() + HISTORY_TTL_MS

    val values = ContentValues().apply {
        put(COLUMN_CONTENT, content)
        put(COLUMN_TIMESTAMP, entry.getLong("timestamp"))
        put(COLUMN_EXPIRY_TIMESTAMP, freshExpiry)  // Fresh expiry, not imported one
        put(COLUMN_IS_PINNED, if (isPinned) 1 else 0)
        put(COLUMN_IS_TODO, if (isTodo) 1 else 0)
    }

    // Track what was added: [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
    if (isPinned) addedCounts[1]++
    if (isTodo) addedCounts[2]++
    if (!isPinned && !isTodo) addedCounts[0]++
}
```

### BackupRestoreManager Result Handling

```kotlin
// BackupRestoreManager.kt
fun importClipboard(context: Context, uri: Uri): ClipboardImportResult {
    val importResult = database.importFromJson(jsonArray)
    // importResult = [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]

    result.importedCount = importResult[0] + importResult[1] + importResult[2]
    result.skippedCount = importResult[3]
    return result
}

fun exportClipboard(context: Context, uri: Uri): ClipboardExportResult {
    val entries = database.getAllEntriesForExport()
    // ... write to JSON ...
    return ClipboardExportResult(exportedCount = entries.size)
}
```

## Configuration

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Enable History** | `clipboard_history_enabled` | true | bool |
| **History Size** | `clipboard_history_size` | 25 | 10-50 |
| **Auto-Expiry** | `clipboard_expiry` | ONE_DAY | 1hr/24hr/7d/never |
| **Password Detection** | `clipboard_detect_password` | true | bool |
| **Show Icon** | `clipboard_show_icon` | true | bool |
| **Max Item Length** | - | 10000 | chars |

## Related Specifications

- [Privacy Settings](../../../specs/settings-system.md) - Privacy controls
- [Text Selection](text-selection-spec.md) - Selection integration
