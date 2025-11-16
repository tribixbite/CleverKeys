package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages blacklisted words that should not appear in predictions
 *
 * Features:
 * - Add words to blacklist (disabled words)
 * - Remove words from blacklist
 * - Check if word is disabled
 * - Persist disabled words across app restarts
 * - Reactive state updates via Flow
 */
class DisabledWordsManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _disabledWords = MutableStateFlow<Set<String>>(emptySet())
    val disabledWords: StateFlow<Set<String>> = _disabledWords.asStateFlow()

    init {
        loadDisabledWords()
    }

    /**
     * Add a word to the disabled list
     * Word will not appear in predictions after this
     */
    fun addDisabledWord(word: String) {
        if (word.isBlank()) return

        val normalized = word.trim().lowercase()
        val updated = _disabledWords.value.toMutableSet()
        updated.add(normalized)
        _disabledWords.value = updated

        saveDisabledWords()
    }

    /**
     * Remove a word from the disabled list
     * Word will appear in predictions again after this
     */
    fun removeDisabledWord(word: String) {
        val normalized = word.trim().lowercase()
        val updated = _disabledWords.value.toMutableSet()
        updated.remove(normalized)
        _disabledWords.value = updated

        saveDisabledWords()
    }

    /**
     * Check if a word is currently disabled
     */
    fun isWordDisabled(word: String): Boolean {
        val normalized = word.trim().lowercase()
        return _disabledWords.value.contains(normalized)
    }

    /**
     * Get all disabled words as a sorted list
     */
    fun getDisabledWords(): List<String> {
        return _disabledWords.value.sorted()
    }

    /**
     * Clear all disabled words
     */
    fun clearAll() {
        _disabledWords.value = emptySet()
        saveDisabledWords()
    }

    /**
     * Get count of disabled words
     */
    fun getCount(): Int {
        return _disabledWords.value.size
    }

    /**
     * Load disabled words from SharedPreferences
     */
    private fun loadDisabledWords() {
        val words = prefs.getStringSet(KEY_DISABLED_WORDS, emptySet()) ?: emptySet()
        _disabledWords.value = words.toSet()
    }

    /**
     * Save disabled words to SharedPreferences
     */
    private fun saveDisabledWords() {
        prefs.edit()
            .putStringSet(KEY_DISABLED_WORDS, _disabledWords.value.toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "disabled_words"
        private const val KEY_DISABLED_WORDS = "words"

        @Volatile
        private var INSTANCE: DisabledWordsManager? = null

        /**
         * Get singleton instance of DisabledWordsManager
         */
        fun getInstance(context: Context): DisabledWordsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DisabledWordsManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
