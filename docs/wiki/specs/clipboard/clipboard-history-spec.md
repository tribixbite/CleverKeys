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

### Clipboard Item

```kotlin
// ClipboardManager.kt
data class ClipboardItem(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val source: String? = null,     // Package name
    val isEncrypted: Boolean = false
)
```

### Storage Structure

```kotlin
// ClipboardStore.kt
// Stored in SQLite database
CREATE TABLE clipboard_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    text TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    is_pinned INTEGER DEFAULT 0,
    source TEXT,
    hash TEXT  -- For duplicate detection
);

CREATE INDEX idx_timestamp ON clipboard_history(timestamp DESC);
CREATE INDEX idx_pinned ON clipboard_history(is_pinned DESC);
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

## History View UI

```kotlin
// ClipboardHistoryView.kt
class ClipboardHistoryView : FrameLayout {
    private val adapter = ClipboardAdapter()

    fun show() {
        val items = clipboardManager.getHistory()

        // Group by pinned status
        val grouped = items.groupBy { it.isPinned }
        val pinned = grouped[true] ?: emptyList()
        val recent = grouped[false] ?: emptyList()

        adapter.submitList(pinned + recent)

        visibility = VISIBLE
        requestFocus()
    }

    fun onItemClick(item: ClipboardItem) {
        // Paste the item
        inputConnection?.commitText(item.text, 1)
        hide()
    }

    fun onItemLongClick(item: ClipboardItem) {
        showContextMenu(item)
    }

    fun onItemSwipe(item: ClipboardItem) {
        clipboardManager.deleteItem(item.id)
    }
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
