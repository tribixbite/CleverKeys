package tribixbite.keyboard2.emoji

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tribixbite.keyboard2.Emoji

/**
 * ViewModel for emoji selection with Material 3 support
 *
 * Manages emoji state, groups, search, and recent usage with reactive StateFlows.
 * Follows MVVM architecture for proper separation of concerns.
 */
class EmojiViewModel(private val context: Context) : ViewModel() {

    private val emojiService: Emoji = Emoji.getInstance(context)

    // State: Current emoji list to display
    private val _emojis = MutableStateFlow<List<Emoji.EmojiData>>(emptyList())
    val emojis: StateFlow<List<Emoji.EmojiData>> = _emojis.asStateFlow()

    // State: Available emoji groups
    private val _groups = MutableStateFlow<List<String>>(emptyList())
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    // State: Currently selected group index (-1 for recent)
    private val _selectedGroupIndex = MutableStateFlow(-1)
    val selectedGroupIndex: StateFlow<Int> = _selectedGroupIndex.asStateFlow()

    // State: Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // State: Loading indicator
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // State: Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadEmojis()
    }

    /**
     * Load emoji data and initialize recent emojis
     */
    private fun loadEmojis() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val success = emojiService.loadEmojis()
                if (success) {
                    // Load available groups
                    _groups.value = emojiService.getGroups()

                    // Show recent emojis by default
                    showRecentEmojis()
                } else {
                    _errorMessage.value = "Failed to load emoji data"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error loading emojis: ${e.message}"
                android.util.Log.e("EmojiViewModel", "Failed to load emojis", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Show recent/frequently used emojis
     */
    fun showRecentEmojis() {
        viewModelScope.launch {
            try {
                val recent = emojiService.getRecentEmojis(context)
                _emojis.value = recent
                _selectedGroupIndex.value = -1 // -1 indicates "Recent" group
                _searchQuery.value = ""
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load recent emojis"
                android.util.Log.e("EmojiViewModel", "Failed to load recent emojis", e)
            }
        }
    }

    /**
     * Switch to a specific emoji group by index
     */
    fun selectGroup(groupIndex: Int) {
        viewModelScope.launch {
            try {
                if (groupIndex == -1) {
                    showRecentEmojis()
                } else {
                    val groupEmojis = emojiService.getEmojisByGroupIndex(groupIndex)
                    _emojis.value = groupEmojis
                    _selectedGroupIndex.value = groupIndex
                    _searchQuery.value = ""
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load emoji group"
                android.util.Log.e("EmojiViewModel", "Failed to load group $groupIndex", e)
            }
        }
    }

    /**
     * Search emojis by keyword
     */
    fun search(query: String) {
        viewModelScope.launch {
            try {
                _searchQuery.value = query

                if (query.isBlank()) {
                    // Return to current group or recent
                    if (_selectedGroupIndex.value == -1) {
                        showRecentEmojis()
                    } else {
                        selectGroup(_selectedGroupIndex.value)
                    }
                } else {
                    // Perform search
                    val results = emojiService.searchEmojis(query)
                    _emojis.value = results
                }
            } catch (e: Exception) {
                _errorMessage.value = "Search failed"
                android.util.Log.e("EmojiViewModel", "Search failed for query: $query", e)
            }
        }
    }

    /**
     * Record emoji usage and update recent list
     */
    fun onEmojiSelected(emoji: Emoji.EmojiData) {
        viewModelScope.launch {
            try {
                emojiService.recordEmojiUsage(context, emoji)

                // Refresh recent emojis if we're viewing the recent tab
                if (_selectedGroupIndex.value == -1 && _searchQuery.value.isBlank()) {
                    showRecentEmojis()
                }
            } catch (e: Exception) {
                android.util.Log.e("EmojiViewModel", "Failed to record emoji usage", e)
            }
        }
    }

    /**
     * Get first emoji of a group for display on group button
     */
    fun getFirstEmojiOfGroup(groupIndex: Int): Emoji.EmojiData? {
        return emojiService.getFirstEmojiOfGroup(groupIndex)
    }

    /**
     * Get number of emoji groups
     */
    fun getNumGroups(): Int {
        return _groups.value.size
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
}
