package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-language dictionary manager with user dictionary support.
 *
 * Provides comprehensive dictionary management for multiple languages
 * with user-added words, frequency tracking, and language switching.
 *
 * Features:
 * - 20 supported languages (en, es, fr, de, it, pt, ru, zh, ja, ko, ar, he, hi, th, el, tr, pl, nl, sv, da)
 * - System dictionaries (pre-loaded word lists)
 * - User dictionaries (personal words, learned from typing)
 * - Language switching with automatic dictionary reload
 * - Word frequency tracking
 * - User word persistence (SharedPreferences + JSON)
 * - Common words and top-N fast paths
 * - OOV (out-of-vocabulary) handling
 * - Multi-language stats and diagnostics
 *
 * Bug #277 - HIGH: Implement missing multi-language support and user dictionary
 *
 * @param context Application context
 * @param config Dictionary configuration
 */
class MultiLanguageDictionaryManager(
    private val context: Context,
    private val config: DictionaryConfig = DictionaryConfig()
) {
    /**
     * Supported languages with dictionary files.
     */
    enum class Language(val code: String, val displayName: String, val dictionaryFile: String) {
        ENGLISH("en", "English", "en.txt"),
        SPANISH("es", "Spanish", "es.txt"),
        FRENCH("fr", "French", "fr.txt"),
        GERMAN("de", "German", "de.txt"),
        ITALIAN("it", "Italian", "it.txt"),
        PORTUGUESE("pt", "Portuguese", "pt.txt"),
        RUSSIAN("ru", "Russian", "ru.txt"),
        CHINESE("zh", "Chinese", "zh.txt"),
        JAPANESE("ja", "Japanese", "ja.txt"),
        KOREAN("ko", "Korean", "ko.txt"),
        ARABIC("ar", "Arabic", "ar.txt"),
        HEBREW("he", "Hebrew", "he.txt"),
        HINDI("hi", "Hindi", "hi.txt"),
        THAI("th", "Thai", "th.txt"),
        GREEK("el", "Greek", "el.txt"),
        TURKISH("tr", "Turkish", "tr.txt"),
        POLISH("pl", "Polish", "pl.txt"),
        DUTCH("nl", "Dutch", "nl.txt"),
        SWEDISH("sv", "Swedish", "sv.txt"),
        DANISH("da", "Danish", "da.txt");

        companion object {
            fun fromCode(code: String): Language? {
                return values().find { it.code.equals(code, ignoreCase = true) }
            }

            fun fromLocale(locale: Locale): Language? {
                return fromCode(locale.language)
            }
        }
    }

    companion object {
        private const val TAG = "MultiLanguageDictionary"
        private const val PREFS_NAME = "user_dictionary"
        private const val PREFS_KEY_PREFIX = "user_words_"
    }

    /**
     * Configuration for dictionary tuning.
     */
    data class DictionaryConfig(
        val maxSystemWords: Int = 150_000,
        val maxUserWords: Int = 10_000,
        val commonWordsCount: Int = 100,
        val top5000Count: Int = 5000,
        val frequencyBoostMultiplier: Float = 1000f,
        val commonWordBoost: Float = 2.0f,
        val top5000Boost: Float = 1.5f,
        val userWordBoost: Float = 3.0f,  // User words get highest priority
        val longWordLengthThreshold: Int = 12,
        val longWordPenalty: Float = 0.5f,
        val swipePathLengthDivisor: Float = 50f,
        val typingSpeedMultiplier: Float = 0.15f,
        val oovPenalty: Float = 0.3f,
        val oovMinConfidence: Float = 0.5f,
        val minWordLength: Int = 2,
        val maxWordLength: Int = 20
    )

    /**
     * Callback interface for dictionary events.
     */
    interface Callback {
        /**
         * Called when language changes.
         */
        fun onLanguageChanged(language: Language)

        /**
         * Called when dictionary loading completes.
         */
        fun onDictionaryLoaded(language: Language, wordCount: Int)

        /**
         * Called when user word is added.
         */
        fun onUserWordAdded(word: String, language: Language)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _currentLanguage = MutableStateFlow(Language.ENGLISH)
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private var callback: Callback? = null

    // Dictionary data structures (thread-safe)
    private val systemWordFrequencies = ConcurrentHashMap<String, Float>()
    private val userWordFrequencies = ConcurrentHashMap<String, Float>()
    private val commonWords = ConcurrentHashMap.newKeySet<String>()
    private val top5000 = ConcurrentHashMap.newKeySet<String>()
    private val wordsByLength = ConcurrentHashMap<Int, MutableSet<String>>()

    // SharedPreferences for user dictionary persistence
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        logD("MultiLanguageDictionaryManager initialized")
    }

    /**
     * Set current language and load dictionary.
     *
     * @param language Language to switch to
     * @return True if successful
     */
    suspend fun setLanguage(language: Language): Boolean = withContext(Dispatchers.Default) {
        if (_currentLanguage.value == language && _isLoaded.value) {
            logD("Language already loaded: ${language.displayName}")
            return@withContext true
        }

        logD("Switching to language: ${language.displayName}")

        // Clear current dictionaries
        clearDictionaries()

        // Load new language
        val success = loadSystemDictionary(language)
        if (success) {
            loadUserDictionary(language)
            createFastPathSets()
            createLengthBasedLookup()

            _currentLanguage.value = language
            _isLoaded.value = true

            callback?.onLanguageChanged(language)
            callback?.onDictionaryLoaded(language, getTotalWordCount())

            logD("✅ Language loaded: ${language.displayName} (${getTotalWordCount()} words)")
        } else {
            logE("Failed to load language: ${language.displayName}", null)
        }

        success
    }

    /**
     * Load system dictionary for language.
     *
     * @param language Language to load
     * @return True if successful
     */
    private suspend fun loadSystemDictionary(language: Language): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Loading system dictionary: ${language.dictionaryFile}")

            // Try to load primary dictionary
            var wordCount = 0
            try {
                context.assets.open("dictionaries/${language.dictionaryFile}").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val word = line.trim().lowercase()
                        if (isValidWordFormat(word)) {
                            val frequency = 1.0f / (wordCount + 1.0f)
                            systemWordFrequencies[word] = frequency
                            wordCount++

                            if (wordCount >= config.maxSystemWords) return@forEach
                        }
                    }
                }
                logD("Loaded ${wordCount} words from ${language.dictionaryFile}")
            } catch (e: Exception) {
                logW("Primary dictionary not found: ${language.dictionaryFile} - ${e.message}")

                // Fallback to English if non-English dictionary missing
                if (language != Language.ENGLISH) {
                    logW("Falling back to English dictionary")
                    context.assets.open("dictionaries/en.txt").bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val word = line.trim().lowercase()
                            if (isValidWordFormat(word)) {
                                val frequency = 1.0f / (wordCount + 1.0f)
                                systemWordFrequencies[word] = frequency
                                wordCount++

                                if (wordCount >= config.maxSystemWords) return@forEach
                            }
                        }
                    }
                }
            }

            // Try to load enhanced dictionary
            try {
                val enhancedFile = "dictionaries/${language.code}_enhanced.txt"
                context.assets.open(enhancedFile).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val word = line.trim().lowercase()
                        if (isValidWordFormat(word) && !systemWordFrequencies.containsKey(word)) {
                            val frequency = 1.0f / (wordCount + 1.0f)
                            systemWordFrequencies[word] = frequency
                            wordCount++
                        }
                    }
                }
                logD("Enhanced dictionary loaded: ${enhancedFile}")
            } catch (e: Exception) {
                logD("Enhanced dictionary not available for ${language.code}")
            }

            wordCount > 0
        } catch (e: Exception) {
            logE("Failed to load system dictionary for ${language.displayName}", e)
            false
        }
    }

    /**
     * Load user dictionary for language from SharedPreferences.
     *
     * @param language Language to load user words for
     */
    private suspend fun loadUserDictionary(language: Language) {
        withContext(Dispatchers.IO) {
            try {
                val key = PREFS_KEY_PREFIX + language.code
                val json = prefs.getString(key, null)

                if (json != null) {
                    val jsonArray = JSONArray(json)
                    var loadedCount = 0

                    for (i in 0 until jsonArray.length()) {
                        val wordObj = jsonArray.getJSONObject(i)
                        val word = wordObj.getString("word").lowercase()
                        val frequency = wordObj.optDouble("frequency", 1.0).toFloat()

                        if (isValidWordFormat(word)) {
                            userWordFrequencies[word] = frequency
                            loadedCount++
                        }
                    }

                    logD("Loaded ${loadedCount} user words for ${language.displayName}")
                } else {
                    logD("No user dictionary found for ${language.displayName}")
                }
            } catch (e: Exception) {
                logE("Failed to load user dictionary for ${language.displayName}", e)
            }
        }
    }

    /**
     * Save user dictionary for current language to SharedPreferences.
     */
    private suspend fun saveUserDictionary() = withContext(Dispatchers.IO) {
        try {
            val language = _currentLanguage.value
            val key = PREFS_KEY_PREFIX + language.code

            val jsonArray = JSONArray()
            userWordFrequencies.forEach { (word, frequency) ->
                val wordObj = JSONObject()
                wordObj.put("word", word)
                wordObj.put("frequency", frequency.toDouble())
                jsonArray.put(wordObj)
            }

            prefs.edit().putString(key, jsonArray.toString()).apply()
            logD("Saved ${userWordFrequencies.size} user words for ${language.displayName}")
        } catch (e: Exception) {
            logE("Failed to save user dictionary", e)
        }
    }

    /**
     * Add word to user dictionary.
     *
     * @param word Word to add
     * @param frequency Optional frequency (default: high priority)
     * @return True if added
     */
    suspend fun addUserWord(word: String, frequency: Float = 0.9f): Boolean = withContext(Dispatchers.Default) {
        val normalizedWord = word.trim().lowercase()

        if (!isValidWordFormat(normalizedWord)) {
            logW("Invalid word format: $word")
            return@withContext false
        }

        if (userWordFrequencies.size >= config.maxUserWords) {
            logW("User dictionary full (${config.maxUserWords} words)")
            return@withContext false
        }

        userWordFrequencies[normalizedWord] = frequency
        saveUserDictionary()

        // Rebuild fast paths if significant change
        if (userWordFrequencies.size % 100 == 0) {
            createFastPathSets()
        }

        callback?.onUserWordAdded(normalizedWord, _currentLanguage.value)
        logD("Added user word: $normalizedWord")

        true
    }

    /**
     * Remove word from user dictionary.
     *
     * @param word Word to remove
     * @return True if removed
     */
    suspend fun removeUserWord(word: String): Boolean = withContext(Dispatchers.Default) {
        val normalizedWord = word.trim().lowercase()
        val removed = userWordFrequencies.remove(normalizedWord) != null

        if (removed) {
            saveUserDictionary()
            logD("Removed user word: $normalizedWord")
        }

        removed
    }

    /**
     * Clear user dictionary for current language.
     */
    suspend fun clearUserDictionary() = withContext(Dispatchers.Default) {
        userWordFrequencies.clear()
        saveUserDictionary()
        logD("Cleared user dictionary for ${_currentLanguage.value.displayName}")
    }

    /**
     * Check if word format is valid.
     *
     * @param word Word to validate
     * @return True if valid
     */
    private fun isValidWordFormat(word: String): Boolean {
        return word.length in config.minWordLength..config.maxWordLength &&
                word.isNotBlank() &&
                word.all { it.isLetter() || it == '\'' || it == '-' }
    }

    /**
     * Clear all dictionaries.
     */
    private fun clearDictionaries() {
        systemWordFrequencies.clear()
        // Don't clear user dictionary - it's language-specific
        commonWords.clear()
        top5000.clear()
        wordsByLength.clear()
        _isLoaded.value = false
    }

    /**
     * Create fast-path sets for performance.
     */
    private fun createFastPathSets() {
        commonWords.clear()
        top5000.clear()

        // Combine system and user words, prioritizing user words
        val allWords = mutableMapOf<String, Float>()
        allWords.putAll(systemWordFrequencies)
        userWordFrequencies.forEach { (word, freq) ->
            // User words get boosted frequency
            allWords[word] = freq * config.userWordBoost
        }

        val sortedWords = allWords.toList().sortedByDescending { it.second }

        // Top words are common
        commonWords.addAll(sortedWords.take(config.commonWordsCount).map { it.first })

        // Top N most frequent
        top5000.addAll(sortedWords.take(config.top5000Count).map { it.first })
    }

    /**
     * Create length-based lookup for fast filtering.
     */
    private fun createLengthBasedLookup() {
        wordsByLength.clear()

        systemWordFrequencies.keys.forEach { word ->
            wordsByLength.getOrPut(word.length) { ConcurrentHashMap.newKeySet() }.add(word)
        }

        userWordFrequencies.keys.forEach { word ->
            wordsByLength.getOrPut(word.length) { ConcurrentHashMap.newKeySet() }.add(word)
        }
    }

    /**
     * Filter and rank neural predictions with vocabulary scoring.
     *
     * @param rawPredictions Raw predictions from neural model
     * @param swipeStats Swipe gesture statistics
     * @return Filtered and ranked predictions
     */
    fun filterPredictions(
        rawPredictions: List<CandidateWord>,
        swipeStats: SwipeStats
    ): List<FilteredPrediction> {
        if (!_isLoaded.value) {
            logW("Dictionary not loaded, returning raw predictions")
            return rawPredictions.map { FilteredPrediction(it.word, it.confidence) }
        }

        return rawPredictions.mapNotNull { candidate ->
            val word = candidate.word.lowercase()

            // Check user dictionary first (highest priority)
            val userFrequency = userWordFrequencies[word]
            val systemFrequency = systemWordFrequencies[word]

            val vocabularyScore = when {
                userFrequency != null -> {
                    // User word: highest priority
                    calculateVocabularyScore(word, userFrequency, isUserWord = true)
                }
                systemFrequency != null -> {
                    // System word: normal priority
                    calculateVocabularyScore(word, systemFrequency, isUserWord = false)
                }
                else -> {
                    // OOV: apply penalty or filter
                    if (candidate.confidence < config.oovMinConfidence) {
                        return@mapNotNull null
                    }
                    config.oovPenalty
                }
            }

            // Calculate combined score
            val contextScore = calculateContextScore(word, swipeStats)
            val combinedScore = candidate.confidence * vocabularyScore * contextScore

            FilteredPrediction(word, combinedScore)
        }.sortedByDescending { it.score }
    }

    /**
     * Calculate vocabulary-based scoring.
     *
     * @param word Word to score
     * @param frequency Word frequency
     * @param isUserWord Whether word is from user dictionary
     * @return Vocabulary score
     */
    private fun calculateVocabularyScore(word: String, frequency: Float, isUserWord: Boolean): Float {
        var score = 1.0f

        // Frequency boost
        score *= (frequency * config.frequencyBoostMultiplier + 1.0f)

        // User word boost (highest priority)
        if (isUserWord) {
            score *= config.userWordBoost
        }

        // Common word boost
        if (word in commonWords) {
            score *= config.commonWordBoost
        }

        // Top N boost
        if (word in top5000) {
            score *= config.top5000Boost
        }

        // Length penalty for very long words
        if (word.length > config.longWordLengthThreshold) {
            score *= config.longWordPenalty
        }

        return score
    }

    /**
     * Calculate context-based scoring.
     *
     * @param word Word to score
     * @param swipeStats Swipe statistics
     * @return Context score
     */
    private fun calculateContextScore(word: String, swipeStats: SwipeStats): Float {
        var score = 1.0f

        // Length vs swipe path correlation
        val expectedLength = swipeStats.pathLength / config.swipePathLengthDivisor
        val lengthDiff = kotlin.math.abs(word.length - expectedLength)
        score *= kotlin.math.max(0.5f, 1.0f - lengthDiff * 0.1f)

        // Duration correlation
        val expectedDuration = word.length * config.typingSpeedMultiplier
        val durationDiff = kotlin.math.abs(swipeStats.duration - expectedDuration)
        score *= kotlin.math.max(0.7f, 1.0f - durationDiff * 0.2f)

        return score
    }

    /**
     * Check if word is valid (in any dictionary).
     *
     * @param word Word to check
     * @return True if valid
     */
    fun isValidWord(word: String): Boolean {
        val normalizedWord = word.lowercase()
        return systemWordFrequencies.containsKey(normalizedWord) ||
                userWordFrequencies.containsKey(normalizedWord)
    }

    /**
     * Get word frequency (combines system and user).
     *
     * @param word Word to check
     * @return Frequency (0 if not found)
     */
    fun getWordFrequency(word: String): Float {
        val normalizedWord = word.lowercase()
        return userWordFrequencies[normalizedWord]
            ?: systemWordFrequencies[normalizedWord]
            ?: 0f
    }

    /**
     * Get total word count (system + user).
     *
     * @return Total words
     */
    fun getTotalWordCount(): Int {
        return systemWordFrequencies.size + userWordFrequencies.size
    }

    /**
     * Get user dictionary words.
     *
     * @return List of user words
     */
    fun getUserWords(): List<String> {
        return userWordFrequencies.keys.sorted()
    }

    /**
     * Get dictionary statistics.
     *
     * @return Dictionary statistics
     */
    fun getStats(): DictionaryStats {
        return DictionaryStats(
            language = _currentLanguage.value,
            systemWords = systemWordFrequencies.size,
            userWords = userWordFrequencies.size,
            totalWords = getTotalWordCount(),
            commonWords = commonWords.size,
            top5000Words = top5000.size,
            averageLength = if (systemWordFrequencies.isNotEmpty()) {
                systemWordFrequencies.keys.map { it.length }.average().toFloat()
            } else 0f,
            isLoaded = _isLoaded.value
        )
    }

    /**
     * Set callback for dictionary events.
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing MultiLanguageDictionaryManager resources...")

        try {
            scope.cancel()
            callback = null

            logD("✅ MultiLanguageDictionaryManager resources released")
        } catch (e: Exception) {
            logE("Error releasing dictionary manager resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logW(message: String) = Log.w(TAG, message)
    private fun logE(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    /**
     * Data classes.
     */
    data class CandidateWord(val word: String, val confidence: Float)
    data class FilteredPrediction(val word: String, val score: Float)
    data class SwipeStats(val pathLength: Float, val duration: Float, val straightnessRatio: Float)
    data class DictionaryStats(
        val language: Language,
        val systemWords: Int,
        val userWords: Int,
        val totalWords: Int,
        val commonWords: Int,
        val top5000Words: Int,
        val averageLength: Float,
        val isLoaded: Boolean
    )
}
