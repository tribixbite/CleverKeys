package juloo.keyboard2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Clipboard history management with Kotlin coroutines
 * Reactive clipboard monitoring and history management
 */
class ClipboardHistoryService(private val context: Context) {
    
    companion object {
        private const val TAG = "ClipboardHistory"
        private const val MAX_HISTORY_SIZE = 50
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val historyItems = mutableListOf<ClipboardItem>()
    private val historyFlow = MutableSharedFlow<List<ClipboardItem>>()
    
    /**
     * Clipboard item data
     */
    data class ClipboardItem(
        val text: String,
        val timestamp: Long,
        val source: String,
        val isPinned: Boolean = false
    )
    
    /**
     * Clipboard paste callback
     */
    interface ClipboardPasteCallback {
        fun onClipboardPaste(text: String)
    }
    
    /**
     * Start monitoring clipboard
     */
    fun startMonitoring() {
        scope.launch {
            // Monitor clipboard changes
            while (isActive) {
                try {
                    val primaryClip = clipboardManager.primaryClip
                    if (primaryClip != null && primaryClip.itemCount > 0) {
                        val clipText = primaryClip.getItemAt(0)?.text?.toString()
                        if (!clipText.isNullOrBlank()) {
                            addToHistory(clipText, "system")
                        }
                    }
                    delay(1000) // Check every second
                } catch (e: Exception) {
                    logE("Clipboard monitoring error", e)
                    delay(5000) // Wait longer on error
                }
            }
        }
    }
    
    /**
     * Add item to history
     */
    fun addToHistory(text: String, source: String) {
        if (text.isBlank()) return
        
        // Remove existing duplicates
        historyItems.removeAll { it.text == text }
        
        // Add new item at the beginning
        historyItems.add(0, ClipboardItem(
            text = text,
            timestamp = System.currentTimeMillis(),
            source = source
        ))
        
        // Trim to max size (keep pinned items)
        while (historyItems.size > MAX_HISTORY_SIZE) {
            val lastIndex = historyItems.lastIndex
            if (!historyItems[lastIndex].isPinned) {
                historyItems.removeAt(lastIndex)
            } else {
                break
            }
        }
        
        // Emit updated history
        historyFlow.tryEmit(historyItems.toList())
        logD("Added clipboard item: ${text.take(50)}...")
    }
    
    /**
     * Get history as flow
     */
    fun getHistoryFlow(): Flow<List<ClipboardItem>> = historyFlow.asSharedFlow()
    
    /**
     * Get current history
     */
    fun getCurrentHistory(): List<ClipboardItem> = historyItems.toList()
    
    /**
     * Pin/unpin item
     */
    fun togglePin(item: ClipboardItem) {
        val index = historyItems.indexOf(item)
        if (index >= 0) {
            historyItems[index] = item.copy(isPinned = !item.isPinned)
            historyFlow.tryEmit(historyItems.toList())
        }
    }
    
    /**
     * Delete item
     */
    fun deleteItem(item: ClipboardItem) {
        historyItems.remove(item)
        historyFlow.tryEmit(historyItems.toList())
    }
    
    /**
     * Clear all history
     */
    fun clearHistory() {
        historyItems.removeAll { !it.isPinned }
        historyFlow.tryEmit(historyItems.toList())
    }
    
    /**
     * Copy text to clipboard
     */
    fun copyToClipboard(text: String, label: String = "CleverKeys") {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
        addToHistory(text, "manual")
    }
    
    /**
     * Paste from clipboard
     */
    fun pasteFromClipboard(callback: ClipboardPasteCallback) {
        val primaryClip = clipboardManager.primaryClip
        if (primaryClip != null && primaryClip.itemCount > 0) {
            val text = primaryClip.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                callback.onClipboardPaste(text)
            }
        }
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}