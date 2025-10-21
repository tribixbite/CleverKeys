package tribixbite.keyboard2.clipboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tribixbite.keyboard2.clipboard.ClipboardHistoryService

/**
 * ViewModel for clipboard history with MVVM architecture.
 *
 * Manages clipboard state reactively with:
 * - StateFlow for reactive UI updates
 * - Coroutine-based async operations
 * - Separation of concerns (business logic vs UI)
 * - Proper lifecycle management
 *
 * Fixes Clipboard Bug #2-5: MVVM architecture (was mixed View/Service logic)
 */
class ClipboardViewModel(
    private val context: Context
) : ViewModel() {

    private val service: ClipboardHistoryService =
        ClipboardHistoryService.getInstance(context)

    // State: clipboard history entries
    private val _history = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val history: StateFlow<List<ClipboardEntry>> = _history.asStateFlow()

    // State: loading indicator
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // State: error messages
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeClipboardHistory()
    }

    /**
     * Observe clipboard history changes from service.
     */
    private fun observeClipboardHistory() {
        viewModelScope.launch {
            service.observeHistory()
                .catch { e ->
                    _error.value = "Failed to load clipboard history: ${e.message}"
                }
                .collect { entries ->
                    _history.value = entries
                }
        }
    }

    /**
     * Add current clipboard content to history.
     */
    fun addCurrentClip() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                service.addCurrentClip()
            } catch (e: Exception) {
                _error.value = "Failed to add clipboard: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Paste clipboard entry.
     *
     * @param entry Entry to paste
     */
    fun paste(entry: ClipboardEntry) {
        viewModelScope.launch {
            try {
                // TODO: Implement paste through service
                // For now, just log
                android.util.Log.d("ClipboardViewModel", "Paste: ${entry.preview}")
            } catch (e: Exception) {
                _error.value = "Failed to paste: ${e.message}"
            }
        }
    }

    /**
     * Toggle pin status for entry.
     *
     * @param entry Entry to pin/unpin
     */
    fun togglePin(entry: ClipboardEntry) {
        viewModelScope.launch {
            try {
                service.setPinned(entry.id, !entry.isPinned)
            } catch (e: Exception) {
                _error.value = "Failed to pin: ${e.message}"
            }
        }
    }

    /**
     * Delete clipboard entry.
     *
     * @param entry Entry to delete
     */
    fun delete(entry: ClipboardEntry) {
        viewModelScope.launch {
            try {
                service.deleteEntry(entry.id)
            } catch (e: Exception) {
                _error.value = "Failed to delete: ${e.message}"
            }
        }
    }

    /**
     * Clear all clipboard history.
     */
    fun clearAll() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                service.clearHistory()
            } catch (e: Exception) {
                _error.value = "Failed to clear history: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Factory for creating ViewModel with context.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ClipboardViewModel::class.java)) {
                return ClipboardViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
