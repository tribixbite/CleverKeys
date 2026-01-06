package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import android.util.Log
import org.json.JSONObject

/**
 * Observes changes to UserDictionary and custom words, providing incremental updates.
 *
 * OPTIMIZATION: Eliminates need for periodic reload checks
 * - ContentObserver detects UserDictionary changes immediately
 * - SharedPreferences listener detects custom word changes
 * - Caches data to avoid repeated content provider queries and JSON parsing
 * - Provides incremental word sets (added/removed) for efficient updates
 *
 * Usage:
 * ```
 * val observer = UserDictionaryObserver(context)
 * observer.setChangeListener(object : UserDictionaryObserver.ChangeListener {
 *   override fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>) {
 *     // Update predictor incrementally
 *   }
 *
 *   override fun onCustomWordsChanged(addedWords: Map<String, Int>,
 *                                      removedWords: Set<String>) {
 *     // Update predictor incrementally
 *   }
 * })
 * observer.start()
 *
 * // When done:
 * observer.stop()
 * ```
 */
class UserDictionaryObserver(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    companion object {
        private const val TAG = "UserDictionaryObserver"
    }

    private val handler = Handler(Looper.getMainLooper())

    // v1.1.91: Current language for filtering UserDictionary queries
    // Only words matching this language (or null locale) will be tracked
    private var currentLanguage: String = "en"

    // Cached user dictionary words (for detecting changes)
    private val cachedUserWords: MutableMap<String, Int> = mutableMapOf()

    // Cached custom words from SharedPreferences
    private val cachedCustomWords: MutableMap<String, Int> = mutableMapOf()

    // SharedPreferences listener
    // v1.1.92: Listen for language-specific custom words key (custom_words_${lang})
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val expectedKey = LanguagePreferenceKeys.customWordsKey(currentLanguage)
        if (key == expectedKey) {
            Log.d(TAG, "Custom words changed for '$currentLanguage' in SharedPreferences")
            checkCustomWordsChanges()
        }
    }

    // Change notification listener
    private var changeListener: ChangeListener? = null

    /**
     * Listener interface for dictionary change events.
     */
    interface ChangeListener {
        /**
         * Called when UserDictionary words are added or removed.
         *
         * @param addedWords Words added to UserDictionary (word -> frequency)
         * @param removedWords Words removed from UserDictionary
         */
        fun onUserDictionaryChanged(addedWords: Map<String, Int>, removedWords: Set<String>)

        /**
         * Called when custom words are added, removed, or modified.
         *
         * @param addedOrModified Words added or with frequency changed (word -> frequency)
         * @param removed Words removed from custom dictionary
         */
        fun onCustomWordsChanged(addedOrModified: Map<String, Int>, removed: Set<String>)
    }

    /**
     * Set the change listener for dictionary updates.
     */
    fun setChangeListener(listener: ChangeListener) {
        changeListener = listener
    }

    /**
     * Set the current language for filtering UserDictionary queries.
     * Only words matching this language (or null locale) will be tracked.
     *
     * Call this when the keyboard language changes to ensure only
     * relevant words are included in change notifications.
     *
     * @param language Language code (e.g., "en", "fr", "de")
     * @since v1.1.91
     * @since v1.1.92 Also reloads custom words cache for language-specific key
     */
    fun setLanguage(language: String) {
        if (currentLanguage != language) {
            currentLanguage = language
            Log.d(TAG, "Language set to: $language - reloading caches")
            // Reload caches with new language filter
            loadUserDictionaryCache()
            loadCustomWordsCache()  // v1.1.92: Custom words are now language-specific
        }
    }

    /**
     * Start observing dictionary changes.
     * Registers ContentObserver and SharedPreferences listener.
     */
    fun start() {
        // Register ContentObserver for UserDictionary
        context.contentResolver.registerContentObserver(
            UserDictionary.Words.CONTENT_URI,
            true,
            this
        )

        // Register SharedPreferences listener
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Initial load of cached data
        loadUserDictionaryCache()
        loadCustomWordsCache()

        Log.d(TAG, "Started observing dictionary changes")
    }

    /**
     * Stop observing dictionary changes.
     * Unregisters all observers and listeners.
     */
    fun stop() {
        context.contentResolver.unregisterContentObserver(this)

        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)

        Log.d(TAG, "Stopped observing dictionary changes")
    }

    /**
     * Called when UserDictionary content changes.
     */
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        Log.d(TAG, "UserDictionary changed: $uri")
        checkUserDictionaryChanges()
    }

    /**
     * Load UserDictionary into cache.
     *
     * v1.1.91: Filters by currentLanguage to prevent cross-language contamination.
     * Matches exact language code, locale prefix (e.g., fr_FR), or null locale (global).
     */
    private fun loadUserDictionaryCache() {
        cachedUserWords.clear()

        try {
            // v1.1.91: Filter by locale to prevent English contamination
            // Match: exact language code, locale starting with language (e.g., fr_FR), or null (global)
            val selection = "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ? OR ${UserDictionary.Words.LOCALE} IS NULL"
            val selectionArgs = arrayOf(currentLanguage, "$currentLanguage%")

            val cursor: Cursor? = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY
                ),
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)

                while (it.moveToNext()) {
                    val word = it.getString(wordIndex).lowercase()
                    val frequency = if (freqIndex >= 0) it.getInt(freqIndex) else 1000
                    cachedUserWords[word] = frequency
                }

                Log.d(TAG, "Loaded ${cachedUserWords.size} user dictionary words for locale '$currentLanguage' into cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user dictionary cache", e)
        }
    }

    /**
     * Load custom words from SharedPreferences into cache.
     *
     * v1.1.92: Uses language-specific key (custom_words_${lang}) instead of legacy global key.
     */
    private fun loadCustomWordsCache() {
        cachedCustomWords.clear()

        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            // v1.1.92: Use language-specific custom words key
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(currentLanguage)
            val customWordsJson = prefs.getString(customWordsKey, "{}") ?: "{}"

            if (customWordsJson != "{}") {
                val jsonObj = JSONObject(customWordsJson)
                val keys = jsonObj.keys()

                while (keys.hasNext()) {
                    val word = keys.next().lowercase()
                    val frequency = jsonObj.optInt(word, 1000)
                    cachedCustomWords[word] = frequency
                }

                Log.d(TAG, "Loaded ${cachedCustomWords.size} custom words for '$currentLanguage' into cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom words cache", e)
        }
    }

    /**
     * Check for UserDictionary changes and notify listener.
     *
     * v1.1.91: Filters by currentLanguage to prevent cross-language contamination.
     */
    private fun checkUserDictionaryChanges() {
        try {
            val currentWords = mutableMapOf<String, Int>()

            // v1.1.91: Filter by locale to prevent English contamination
            // Match: exact language code, locale starting with language (e.g., fr_FR), or null (global)
            val selection = "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ? OR ${UserDictionary.Words.LOCALE} IS NULL"
            val selectionArgs = arrayOf(currentLanguage, "$currentLanguage%")

            val cursor: Cursor? = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY
                ),
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)

                while (it.moveToNext()) {
                    val word = it.getString(wordIndex).lowercase()
                    val frequency = if (freqIndex >= 0) it.getInt(freqIndex) else 1000
                    currentWords[word] = frequency
                }
            }

            // Compute differences
            val addedWords = mutableMapOf<String, Int>()
            val removedWords = mutableSetOf<String>()

            // Find added words
            for ((word, freq) in currentWords) {
                if (!cachedUserWords.containsKey(word)) {
                    addedWords[word] = freq
                }
            }

            // Find removed words
            for (word in cachedUserWords.keys) {
                if (!currentWords.containsKey(word)) {
                    removedWords.add(word)
                }
            }

            // Update cache
            cachedUserWords.clear()
            cachedUserWords.putAll(currentWords)

            // Notify listener if there are changes
            if (addedWords.isNotEmpty() || removedWords.isNotEmpty()) {
                Log.i(TAG, "UserDictionary changed: +${addedWords.size} words, -${removedWords.size} words")

                changeListener?.onUserDictionaryChanged(addedWords, removedWords)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check user dictionary changes", e)
        }
    }

    /**
     * Check for custom words changes and notify listener.
     *
     * v1.1.92: Uses language-specific key (custom_words_${lang}) instead of legacy global key.
     */
    private fun checkCustomWordsChanges() {
        try {
            val currentWords = mutableMapOf<String, Int>()

            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            // v1.1.92: Use language-specific custom words key
            val customWordsKey = LanguagePreferenceKeys.customWordsKey(currentLanguage)
            val customWordsJson = prefs.getString(customWordsKey, "{}") ?: "{}"

            if (customWordsJson != "{}") {
                val jsonObj = JSONObject(customWordsJson)
                val keys = jsonObj.keys()

                while (keys.hasNext()) {
                    val word = keys.next().lowercase()
                    val frequency = jsonObj.optInt(word, 1000)
                    currentWords[word] = frequency
                }
            }

            // Compute differences
            val addedOrModified = mutableMapOf<String, Int>()
            val removed = mutableSetOf<String>()

            // Find added or modified words
            for ((word, freq) in currentWords) {
                val cachedFreq = cachedCustomWords[word]
                if (cachedFreq == null || cachedFreq != freq) {
                    addedOrModified[word] = freq
                }
            }

            // Find removed words
            for (word in cachedCustomWords.keys) {
                if (!currentWords.containsKey(word)) {
                    removed.add(word)
                }
            }

            // Update cache
            cachedCustomWords.clear()
            cachedCustomWords.putAll(currentWords)

            // Notify listener if there are changes
            if (addedOrModified.isNotEmpty() || removed.isNotEmpty()) {
                Log.i(TAG, "Custom words changed: +${addedOrModified.size} words, -${removed.size} words")

                changeListener?.onCustomWordsChanged(addedOrModified, removed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check custom words changes", e)
        }
    }

    /**
     * Get current cached user dictionary words.
     */
    fun getCachedUserWords(): Map<String, Int> {
        return cachedUserWords.toMap()
    }

    /**
     * Get current cached custom words.
     */
    fun getCachedCustomWords(): Map<String, Int> {
        return cachedCustomWords.toMap()
    }
}
