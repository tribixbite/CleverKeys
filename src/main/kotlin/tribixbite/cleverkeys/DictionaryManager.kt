package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/**
 * Manages word dictionaries for different languages and user custom words
 *
 * v1.2.2: Uses same storage as CustomDictionarySource (custom_words_{lang} in DirectBootAwarePreferences)
 * so words added here appear in Dictionary Manager UI and can be deleted.
 */
class DictionaryManager(private val context: Context) {

    // Use DirectBootAwarePreferences for consistency with CustomDictionarySource
    private val prefs: SharedPreferences = DirectBootAwarePreferences.get_shared_preferences(context)
    private val gson = Gson()
    private val predictors = mutableMapOf<String, WordPredictor>()
    private val userWords = mutableSetOf<String>()
    private var currentLanguage: String = "en"
    private var currentPredictor: WordPredictor? = null

    init {
        // Migrate legacy custom words BEFORE loading (one-time migration)
        migrateLegacyCustomWords()
        setLanguage(Locale.getDefault().language)
        loadUserWords()
    }

    /**
     * Migrate custom words from legacy storage format to new format.
     *
     * Legacy format (pre-v1.2.2):
     *   - SharedPreferences file: "user_dictionary"
     *   - Key: "user_words"
     *   - Format: StringSet
     *
     * New format (v1.2.2+):
     *   - SharedPreferences: DirectBootAwarePreferences
     *   - Key: "custom_words_{lang}" (e.g., "custom_words_en")
     *   - Format: JSON map {"word": frequency}
     *
     * This migration runs once and clears the legacy data after successful migration.
     */
    private fun migrateLegacyCustomWords() {
        try {
            val legacyPrefs = context.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE)
            val legacyWords = legacyPrefs.getStringSet("user_words", null)

            if (legacyWords.isNullOrEmpty()) {
                // No legacy data to migrate
                return
            }

            Log.i(TAG, "Found ${legacyWords.size} legacy custom words to migrate")

            // Migrate to the default language (usually "en", but use system locale)
            val migrationLang = Locale.getDefault().language
            val targetKey = LanguagePreferenceKeys.customWordsKey(migrationLang)

            // Load any existing words in the new format
            val existingJson = prefs.getString(targetKey, null)
            val existingWords: MutableMap<String, Int> = if (existingJson != null) {
                try {
                    val type = object : TypeToken<MutableMap<String, Int>>() {}.type
                    gson.fromJson(existingJson, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }

            // Merge legacy words (don't overwrite existing words with same key)
            var migratedCount = 0
            for (word in legacyWords) {
                if (!existingWords.containsKey(word)) {
                    existingWords[word] = 100 // Default frequency
                    migratedCount++
                }
            }

            // Save merged words to new format
            prefs.edit()
                .putString(targetKey, gson.toJson(existingWords))
                .apply()

            // Clear legacy data after successful migration
            legacyPrefs.edit()
                .remove("user_words")
                .apply()

            Log.i(TAG, "Migrated $migratedCount legacy words to '$targetKey' (${existingWords.size} total)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy custom words", e)
        }
    }

    /**
     * Set the active language for prediction.
     *
     * OPTIMIZATION v3 (perftodos3.md Todo 1): Uses async loading to prevent UI freezes.
     */
    fun setLanguage(languageCode: String?) {
        val code = languageCode ?: "en"
        val languageChanged = currentLanguage != code
        currentLanguage = code

        // Reload user words if language changed
        if (languageChanged) {
            loadUserWords()
        }

        // Get or create predictor for this language
        currentPredictor = predictors.getOrPut(code) {
            WordPredictor().apply {
                setContext(context) // Enable disabled words filtering

                // CRITICAL: Use async loading to prevent UI freeze during language switching
                loadDictionaryAsync(context, code) {
                    // This runs on the main thread when loading is complete
                    // CRITICAL: Activate the UserDictionaryObserver now that dictionary is loaded
                    startObservingDictionaryChanges()
                    Log.i(TAG, "Dictionary loaded and observer activated for: $code")
                }
            }
        }
    }

    /**
     * Get word predictions for the given key sequence.
     *
     * Returns empty list if dictionary is still loading.
     */
    fun getPredictions(keySequence: String): List<String> {
        val predictor = currentPredictor ?: return emptyList()

        // OPTIMIZATION v3: Return empty list while dictionary is loading asynchronously
        if (predictor.isLoading()) {
            return emptyList()
        }

        val predictions = predictor.predictWords(keySequence).toMutableList()

        // Add user words that match
        val lowerSequence = keySequence.lowercase()
        for (userWord in userWords) {
            if (userWord.lowercase().startsWith(lowerSequence) && userWord !in predictions) {
                predictions.add(0, userWord) // Add at beginning
                if (predictions.size > 5) {
                    predictions.removeAt(predictions.size - 1)
                }
            }
        }

        return predictions
    }

    /**
     * Add a word to the user dictionary for current language.
     * Uses same storage as CustomDictionarySource so words appear in Dictionary Manager.
     */
    fun addUserWord(word: String?) {
        if (word.isNullOrEmpty()) return

        userWords.add(word)
        saveUserWords()
        Log.d(TAG, "Added '$word' to custom words for '$currentLanguage'")
    }

    /**
     * Remove a word from the user dictionary
     */
    fun removeUserWord(word: String) {
        userWords.remove(word)
        saveUserWords()
    }

    /**
     * Check if a word is in the user dictionary
     */
    fun isUserWord(word: String): Boolean = word in userWords

    /**
     * Clear the user dictionary
     */
    fun clearUserDictionary() {
        userWords.clear()
        saveUserWords()
    }

    /**
     * Get the custom words key for current language.
     * Uses LanguagePreferenceKeys for consistency with CustomDictionarySource.
     */
    private fun getCustomWordsKey(): String {
        return LanguagePreferenceKeys.customWordsKey(currentLanguage)
    }

    /**
     * Load user words from preferences (JSON format matching CustomDictionarySource)
     */
    private fun loadUserWords() {
        val key = getCustomWordsKey()
        val jsonString = prefs.getString(key, null)
        userWords.clear()

        if (jsonString != null) {
            try {
                val type = object : TypeToken<MutableMap<String, Int>>() {}.type
                val wordsMap: MutableMap<String, Int>? = gson.fromJson(jsonString, type)
                wordsMap?.keys?.let { userWords.addAll(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse custom words JSON", e)
            }
        }
        Log.d(TAG, "Loaded ${userWords.size} custom words for '$currentLanguage'")
    }

    /**
     * Save user words to preferences (JSON format matching CustomDictionarySource)
     */
    private fun saveUserWords() {
        val key = getCustomWordsKey()
        // Convert to map with default frequency of 100
        val wordsMap = userWords.associateWith { 100 }
        prefs.edit()
            .putString(key, gson.toJson(wordsMap))
            .apply()
    }

    /**
     * Get the current language code
     */
    fun getCurrentLanguage(): String? = currentLanguage

    /**
     * Check if the current predictor is loading.
     *
     * @return true if dictionary is loading asynchronously, false otherwise
     */
    fun isLoading(): Boolean = currentPredictor?.isLoading() == true

    /**
     * Preload dictionaries for given languages.
     *
     * OPTIMIZATION v3 (perftodos3.md Todo 1): Uses async loading for all languages.
     */
    fun preloadLanguages(languageCodes: Array<String>) {
        for (code in languageCodes) {
            predictors.getOrPut(code) {
                WordPredictor().apply {
                    setContext(context) // Enable disabled words filtering

                    // CRITICAL: Use async loading to prevent UI freeze during preloading
                    loadDictionaryAsync(context, code) {
                        // This runs on the main thread when loading is complete
                        // CRITICAL: Activate the UserDictionaryObserver for preloaded language
                        startObservingDictionaryChanges()
                        Log.i(TAG, "Preloaded dictionary and activated observer for: $code")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DictionaryManager"
    }
}
