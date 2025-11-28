package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Locale

/**
 * Manages word dictionaries for different languages and user custom words
 */
class DictionaryManager(private val context: Context) {

    private val userDictPrefs: SharedPreferences =
        context.getSharedPreferences(USER_DICT_PREFS, Context.MODE_PRIVATE)
    private val predictors = mutableMapOf<String, WordPredictor>()
    private val userWords = mutableSetOf<String>()
    private var currentLanguage: String? = null
    private var currentPredictor: WordPredictor? = null

    init {
        loadUserWords()
        setLanguage(Locale.getDefault().language)
    }

    /**
     * Set the active language for prediction.
     *
     * OPTIMIZATION v3 (perftodos3.md Todo 1): Uses async loading to prevent UI freezes.
     */
    fun setLanguage(languageCode: String?) {
        val code = languageCode ?: "en"
        currentLanguage = code

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
     * Add a word to the user dictionary
     */
    fun addUserWord(word: String?) {
        if (word.isNullOrEmpty()) return

        userWords.add(word)
        saveUserWords()
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
     * Load user words from preferences
     */
    private fun loadUserWords() {
        val words = userDictPrefs.getStringSet(USER_WORDS_KEY, emptySet()) ?: emptySet()
        userWords.clear()
        userWords.addAll(words)
    }

    /**
     * Save user words to preferences
     */
    private fun saveUserWords() {
        userDictPrefs.edit()
            .putStringSet(USER_WORDS_KEY, userWords.toSet())
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
        private const val USER_DICT_PREFS = "user_dictionary"
        private const val USER_WORDS_KEY = "user_words"
    }
}
