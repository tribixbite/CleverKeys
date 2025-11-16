package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Locale

/**
 * Manages word dictionaries for different languages and user custom words.
 *
 * Features:
 * - Multi-language dictionary support with lazy loading
 * - User dictionary (add/remove custom words)
 * - Language switching with predictor caching
 * - Dictionary preloading for performance
 * - SharedPreferences persistence for user words
 * - Automatic default language detection
 *
 * Related to Bug #345: DictionaryLoader system missing (CATASTROPHIC)
 */
class DictionaryManager(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryManager"
        private const val USER_DICT_PREFS = "user_dictionary"
        private const val USER_WORDS_KEY = "user_words"
        private const val MAX_PREDICTIONS = 5
    }

    private val userDictPrefs: SharedPreferences =
        context.getSharedPreferences(USER_DICT_PREFS, Context.MODE_PRIVATE)

    private val predictors = mutableMapOf<String, TypingPredictionEngine>()
    private val userWords = mutableSetOf<String>()

    private var currentLanguage: String = "en"
    private var currentPredictor: TypingPredictionEngine? = null

    init {
        loadUserWords()
        setLanguage(Locale.getDefault().language)
    }

    /**
     * Set the active language for prediction
     */
    fun setLanguage(languageCode: String?) {
        val code = languageCode ?: "en"
        currentLanguage = code

        // Get or create predictor for this language
        currentPredictor = predictors.getOrPut(code) {
            Log.d(TAG, "Loading dictionary for language: $code")
            TypingPredictionEngine(context)
            // Note: TypingPredictionEngine initializes asynchronously
            // TODO: Add language-specific initialization if needed
        }

        Log.d(TAG, "Language set to: $code")
    }

    /**
     * Get word predictions for the given key sequence
     * Filters out disabled (blacklisted) words
     */
    fun getPredictions(keySequence: String): List<String> {
        val predictor = currentPredictor ?: return emptyList()

        val predictionResults = predictor.autocompleteWord(keySequence, MAX_PREDICTIONS)
        val predictions = predictionResults.map { it.word }.toMutableList()

        // Add user words that match
        val lowerSequence = keySequence.lowercase()
        for (userWord in userWords) {
            if (userWord.lowercase().startsWith(lowerSequence) && userWord !in predictions) {
                predictions.add(0, userWord) // Add at beginning
                if (predictions.size > MAX_PREDICTIONS) {
                    predictions.removeAt(predictions.size - 1)
                }
            }
        }

        // Filter out disabled (blacklisted) words
        val disabledWordsManager = DisabledWordsManager.getInstance(context)
        return predictions.filter { word ->
            !disabledWordsManager.isWordDisabled(word)
        }
    }

    /**
     * Add a word to the user dictionary
     */
    fun addUserWord(word: String?) {
        if (word.isNullOrEmpty()) {
            return
        }

        userWords.add(word)
        saveUserWords()
        Log.d(TAG, "Added user word: $word")
    }

    /**
     * Remove a word from the user dictionary
     */
    fun removeUserWord(word: String) {
        userWords.remove(word)
        saveUserWords()
        Log.d(TAG, "Removed user word: $word")
    }

    /**
     * Check if a word is in the user dictionary
     */
    fun isUserWord(word: String): Boolean {
        return userWords.contains(word)
    }

    /**
     * Get all user words
     */
    fun getUserWords(): Set<String> {
        return userWords.toSet()
    }

    /**
     * Clear the user dictionary
     */
    fun clearUserDictionary() {
        userWords.clear()
        saveUserWords()
        Log.d(TAG, "User dictionary cleared")
    }

    /**
     * Load user words from preferences
     */
    private fun loadUserWords() {
        val words = userDictPrefs.getStringSet(USER_WORDS_KEY, emptySet()) ?: emptySet()
        userWords.clear()
        userWords.addAll(words)
        Log.d(TAG, "Loaded ${userWords.size} user words")
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
    fun getCurrentLanguage(): String = currentLanguage

    /**
     * Get list of loaded languages
     */
    fun getLoadedLanguages(): List<String> {
        return predictors.keys.toList()
    }

    /**
     * Check if a language is loaded
     */
    fun isLanguageLoaded(languageCode: String): Boolean {
        return predictors.containsKey(languageCode)
    }

    /**
     * Preload dictionaries for given languages
     * Useful for warming up cache before language switches
     */
    fun preloadLanguages(languageCodes: Array<String>) {
        for (code in languageCodes) {
            if (!predictors.containsKey(code)) {
                Log.d(TAG, "Preloading dictionary for language: $code")
                val predictor = TypingPredictionEngine(context)
                // Note: TypingPredictionEngine initializes asynchronously
                // TODO: Add language-specific initialization if needed
                predictors[code] = predictor
            }
        }
        Log.d(TAG, "Preloaded ${languageCodes.size} languages")
    }

    /**
     * Unload a language dictionary to free memory
     */
    fun unloadLanguage(languageCode: String) {
        if (languageCode == currentLanguage) {
            Log.w(TAG, "Cannot unload current language: $languageCode")
            return
        }

        predictors.remove(languageCode)
        Log.d(TAG, "Unloaded language: $languageCode")
    }

    /**
     * Get dictionary statistics for debugging
     */
    fun getStats(): String {
        val stats = StringBuilder()
        stats.append("DictionaryManager Statistics:\n")
        stats.append("- Current Language: $currentLanguage\n")
        stats.append("- Loaded Languages: ${predictors.keys.joinToString(", ")}\n")
        stats.append("- User Words: ${userWords.size}\n")

        currentPredictor?.let {
            stats.append("- Current Dictionary: TypingPredictionEngine loaded\n")
            stats.append("- User Adaptation Stats: ${it.getUserAdaptationStats()}\n")
        }

        return stats.toString()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Cleanup all prediction engines
        predictors.values.forEach { it.cleanup() }
        predictors.clear()
        currentPredictor = null
        Log.d(TAG, "DictionaryManager cleaned up")
    }
}
