package tribixbite.keyboard2.clipboard

import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clipboard history service with reactive state management.
 *
 * Simple in-memory implementation for Phase 2.1.
 * TODO: Implement persistent storage (Room database)
 * TODO: Implement clipboard monitoring (ClipboardManager.OnPrimaryClipChangedListener)
 *
 * Fixes Clipboard Bug #5: Proper service API
 */
class ClipboardHistoryService(private val context: Context) {

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // In-memory history storage
    private val _history = MutableStateFlow<List<ClipboardEntry>>(emptyList())

    /**
     * Observable clipboard history.
     */
    fun observeHistory(): Flow<List<ClipboardEntry>> = _history.asStateFlow()

    /**
     * Add current clipboard content to history.
     */
    fun addCurrentClip() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).text?.toString() ?: return
        if (text.isBlank()) return

        // Add to history if not duplicate
        val currentHistory = _history.value
        if (currentHistory.firstOrNull()?.text != text) {
            val entry = ClipboardEntry.from(text)
            _history.value = listOf(entry) + currentHistory.take(49) // Keep last 50
        }
    }

    /**
     * Set pin status for entry.
     */
    fun setPinned(entryId: String, pinned: Boolean) {
        _history.value = _history.value.map {
            if (it.id == entryId) it.copy(isPinned = pinned) else it
        }
    }

    /**
     * Delete entry from history.
     */
    fun deleteEntry(entryId: String) {
        _history.value = _history.value.filter { it.id != entryId }
    }

    /**
     * Clear all history.
     */
    fun clearHistory() {
        _history.value = emptyList()
    }

    companion object {
        private var instance: ClipboardHistoryService? = null

        /**
         * Get singleton service instance.
         */
        fun getInstance(context: Context): ClipboardHistoryService {
            return instance ?: synchronized(this) {
                instance ?: ClipboardHistoryService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
