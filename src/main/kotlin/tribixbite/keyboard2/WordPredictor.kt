package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.provider.UserDictionary
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import tribixbite.keyboard2.data.BigramModel
import tribixbite.keyboard2.data.LanguageDetector
import tribixbite.keyboard2.data.UserAdaptationManager
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.ln1p
import kotlin.math.max

/**
 * Word Predictor for Tap Typing
 *
 * Provides word predictions based on typed prefix with unified scoring that combines:
 * - Prefix match quality (completion ratio, length heuristics)
 * - Dictionary frequency (log-scaled to prevent common word domination)
 * - User adaptation (personalized vocabulary learning)
 * - Context probability (bigram model for contextual predictions)
 *
 * Features:
 * - Prefix index for 100x speedup (50k iterations → ~100-500 per keystroke)
 * - Auto-correct with heuristics (same length, first 2 letters, 2/3+ char match)
 * - Language detection from recent words (auto-switch support)
 * - Custom words + Android UserDictionary integration
 * - Disabled words filtering
 *
 * Architecture:
 * - Suspending functions for async dictionary loading
 * - Synchronous prediction (fast path - no allocations)
 * - Early fusion scoring (combine signals before sorting)
 * - Incremental prefix index updates
 *
 * Performance:
 * - Dictionary: 50k words with frequencies (JSON format)
 * - Prefix index: 1-3 char prefixes → O(1) lookup
 * - Recent words: max 5 for context tracking
 *
 * Ported from Java WordPredictor.java with Kotlin improvements.
 *
 * @see BigramModel for context-aware predictions
 * @see LanguageDetector for multi-language support
 * @see UserAdaptationManager for personalization
 */
class WordPredictor(
    private val context: Context,
    private val config: Config
) {
    companion object {
        private const val TAG = "WordPredictor"
        private const val PREFIX_INDEX_MAX_LENGTH = 3
        private const val MAX_RECENT_WORDS = 5
    }

    // Dependencies (optional - can be null if features disabled)
    private var bigramModel: BigramModel? = null
    private var languageDetector: LanguageDetector? = null
    private var adaptationManager: UserAdaptationManager? = null

    // Core data structures
    private val dictionary = mutableMapOf<String, Int>()
    private val prefixIndex = mutableMapOf<String, MutableSet<String>>()
    private val recentWords = mutableListOf<String>()
    private val disabledWords = mutableSetOf<String>()

    // State
    private var currentLanguage = "en"

    // Shared preferences for custom words and disabled words
    private val prefs: SharedPreferences by lazy {
        DirectBootAwarePreferences.get_shared_preferences(context)
    }

    init {
        Log.d(TAG, "WordPredictor initialized")
    }

    /**
     * Set bigram model for context-aware predictions
     */
    fun setBigramModel(model: BigramModel?) {
        bigramModel = model
    }

    /**
     * Set language detector for auto language switching
     */
    fun setLanguageDetector(detector: LanguageDetector?) {
        languageDetector = detector
    }

    /**
     * Set user adaptation manager for personalized predictions
     */
    fun setUserAdaptationManager(manager: UserAdaptationManager?) {
        adaptationManager = manager
    }

    /**
     * Set current language (triggers bigram model language switch)
     */
    fun setLanguage(language: String) {
        currentLanguage = language
        bigramModel?.setLanguage(language)
        Log.d(TAG, "Language set to: $language")
    }

    /**
     * Get current language
     */
    fun getCurrentLanguage(): String = currentLanguage

    /**
     * Check if a language is supported by the bigram model
     */
    fun isLanguageSupported(language: String): Boolean {
        return bigramModel?.isLanguageSupported(language) ?: false
    }

    /**
     * Load disabled words from SharedPreferences
     */
    fun loadDisabledWords() {
        disabledWords.clear()

        try {
            val disabledJson = prefs.getString("disabled_words", "[]") ?: "[]"
            val jsonArray = org.json.JSONArray(disabledJson)

            for (i in 0 until jsonArray.length()) {
                val word = jsonArray.getString(i).lowercase()
                disabledWords.add(word)
            }

            Log.d(TAG, "Loaded ${disabledWords.size} disabled words")
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse disabled words JSON", e)
        }
    }

    /**
     * Reload disabled words (called when settings change)
     */
    fun reloadDisabledWords() {
        loadDisabledWords()
    }

    /**
     * Check if a word is disabled
     */
    private fun isWordDisabled(word: String): Boolean {
        return disabledWords.contains(word.lowercase())
    }

    /**
     * Reload custom and user words (called when user dictionary changes)
     */
    suspend fun reloadCustomAndUserWords() = withContext(Dispatchers.IO) {
        // Remove old custom/user words (they have negative frequencies as markers)
        dictionary.entries.removeIf { it.value < 0 }

        // Reload from sources
        loadCustomAndUserWords()

        // Rebuild prefix index
        buildPrefixIndex()

        Log.d(TAG, "Reloaded custom and user words")
    }

    /**
     * Add word to recent words context (for language detection and bigram predictions)
     */
    fun addWordToContext(word: String) {
        val lowerWord = word.lowercase()

        // Add to recent words (circular buffer with max size)
        recentWords.add(lowerWord)
        if (recentWords.size > MAX_RECENT_WORDS) {
            recentWords.removeAt(0)
        }

        // Try to detect language change if we have enough words
        if (recentWords.size >= 5) {
            tryAutoLanguageDetection()
        }
    }

    /**
     * Try to automatically detect and switch language based on recent words
     */
    private fun tryAutoLanguageDetection() {
        val detector = languageDetector ?: return

        val detectedLanguage = detector.detectLanguageFromWords(recentWords)
        if (detectedLanguage != null && detectedLanguage != currentLanguage) {
            // Only switch if the detected language is supported by our bigram model
            if (bigramModel?.isLanguageSupported(detectedLanguage) == true) {
                Log.d(TAG, "Auto-detected language change from $currentLanguage to $detectedLanguage")
                setLanguage(detectedLanguage)
            }
        }
    }

    /**
     * Manually detect language from a text sample
     */
    fun detectLanguage(text: String): String? {
        return languageDetector?.detectLanguage(text)
    }

    /**
     * Get the list of recent words used for language detection
     */
    fun getRecentWords(): List<String> {
        return recentWords.toList()
    }

    /**
     * Clear the recent words context
     */
    fun clearContext() {
        recentWords.clear()
    }

    /**
     * Load dictionary from assets (suspending function for async loading)
     */
    suspend fun loadDictionary(language: String) = withContext(Dispatchers.IO) {
        dictionary.clear()

        // Try JSON format first (50k words with frequencies)
        val jsonFilename = "dictionaries/${language}_enhanced.json"
        var loaded = false

        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(jsonFilename)))
            val jsonBuilder = StringBuilder()
            reader.use { r ->
                r.forEachLine { line ->
                    jsonBuilder.append(line)
                }
            }

            // Parse JSON object
            val jsonDict = JSONObject(jsonBuilder.toString())
            val keys = jsonDict.keys()
            while (keys.hasNext()) {
                val word = keys.next().lowercase()
                val frequency = jsonDict.getInt(word)
                // Frequency is 128-255, scale to 100-10000 range for better scoring
                val scaledFreq = 100 + ((frequency - 128) / 127.0 * 9900).toInt()
                dictionary[word] = scaledFreq
            }

            Log.d(TAG, "Loaded JSON dictionary: $jsonFilename with ${dictionary.size} words")
            loaded = true
        } catch (e: Exception) {
            Log.w(TAG, "JSON dictionary not found, trying text format: ${e.message}")
        }

        // Fall back to text format (word-per-line)
        if (!loaded) {
            val textFilename = "dictionaries/${language}_enhanced.txt"
            try {
                val reader = BufferedReader(InputStreamReader(context.assets.open(textFilename)))
                reader.use { r ->
                    r.forEachLine { line ->
                        val word = line.trim().lowercase()
                        if (word.isNotEmpty()) {
                            dictionary[word] = 1000 // Default frequency
                        }
                    }
                }
                Log.d(TAG, "Loaded text dictionary: $textFilename with ${dictionary.size} words")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to load dictionary: ${e.message}")
            }
        }

        // Load custom words and user dictionary (additive to main dictionary)
        loadCustomAndUserWords()

        // Build prefix index for fast lookup (100x speedup over iteration)
        buildPrefixIndex()
        Log.d(TAG, "Built prefix index: ${prefixIndex.size} prefixes for ${dictionary.size} words")

        // Set the bigram model language to match the dictionary
        setLanguage(language)
    }

    /**
     * Build prefix index for fast word lookup during predictions
     * Creates mapping from prefixes (1-3 chars) to sets of matching words
     * Performance: Reduces 50k iterations per keystroke to ~100-500
     */
    private fun buildPrefixIndex() {
        prefixIndex.clear()

        for (word in dictionary.keys) {
            // Index prefixes of length 1 to PREFIX_INDEX_MAX_LENGTH (3)
            val maxLen = minOf(PREFIX_INDEX_MAX_LENGTH, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len)
                prefixIndex.getOrPut(prefix) { mutableSetOf() }.add(word)
            }
        }
    }

    /**
     * Add words to prefix index (for incremental updates)
     */
    private fun addToPrefixIndex(words: Set<String>) {
        for (word in words) {
            val maxLen = minOf(PREFIX_INDEX_MAX_LENGTH, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len)
                prefixIndex.getOrPut(prefix) { mutableSetOf() }.add(word)
            }
        }
    }

    /**
     * Load custom words and Android user dictionary into predictions
     * Called during dictionary initialization for performance
     */
    private fun loadCustomAndUserWords() {
        try {
            // 1. Load custom words from SharedPreferences
            val customWordsJson = prefs.getString("custom_words", "{}") ?: "{}"
            if (customWordsJson != "{}") {
                try {
                    // Parse JSON map: {"word": frequency, ...}
                    val jsonObj = JSONObject(customWordsJson)
                    val keys = jsonObj.keys()
                    var customCount = 0
                    while (keys.hasNext()) {
                        val word = keys.next().lowercase()
                        val frequency = jsonObj.optInt(word, 1000)
                        dictionary[word] = frequency
                        customCount++
                    }
                    Log.d(TAG, "Loaded $customCount custom words")
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse custom words JSON", e)
                }
            }

            // 2. Load Android user dictionary
            try {
                val cursor = context.contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    arrayOf(
                        UserDictionary.Words.WORD,
                        UserDictionary.Words.FREQUENCY
                    ),
                    null,
                    null,
                    null
                )

                cursor?.use { c ->
                    val wordIndex = c.getColumnIndex(UserDictionary.Words.WORD)
                    val freqIndex = c.getColumnIndex(UserDictionary.Words.FREQUENCY)
                    var userCount = 0

                    while (c.moveToNext()) {
                        val word = c.getString(wordIndex).lowercase()
                        val frequency = if (freqIndex >= 0) c.getInt(freqIndex) else 1000
                        dictionary[word] = frequency
                        userCount++
                    }

                    Log.d(TAG, "Loaded $userCount user dictionary words")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load user dictionary", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom/user words", e)
        }
    }

    /**
     * Reset the predictor state - called after space/punctuation
     */
    fun reset() {
        // Dictionary remains loaded, just clears any internal state if needed
        Log.d(TAG, "===== PREDICTOR RESET CALLED =====")
    }

    /**
     * Get candidate words from prefix index
     * Returns all words starting with the given prefix
     * Performance: O(1) lookup instead of O(n) iteration
     */
    private fun getPrefixCandidates(prefix: String): Set<String> {
        if (prefix.isEmpty()) {
            // For empty prefix, return all words (fallback to full dictionary)
            return dictionary.keys
        }

        // Use prefix as-is if <= 3 chars, otherwise use first 3 chars
        val lookupPrefix = if (prefix.length <= PREFIX_INDEX_MAX_LENGTH) {
            prefix
        } else {
            prefix.substring(0, PREFIX_INDEX_MAX_LENGTH)
        }

        val candidates = prefixIndex[lookupPrefix] ?: return emptySet()

        // If typed prefix is longer than indexed prefix, filter further
        if (prefix.length > PREFIX_INDEX_MAX_LENGTH) {
            return candidates.filter { it.startsWith(prefix) }.toSet()
        }

        return candidates
    }

    /**
     * Predict words based on the sequence of touched keys
     * Returns list of predictions (for backward compatibility)
     */
    fun predictWords(keySequence: String, maxResults: Int = 10): PredictionResult {
        return predictWordsWithContext(keySequence, emptyList(), maxResults)
    }

    /**
     * Predict words based on key sequence with context for better accuracy
     * Context-aware prediction using bigram model
     *
     * @param keySequence The typed prefix
     * @param context Previous words for contextual prediction
     * @param maxResults Maximum number of predictions to return
     * @return PredictionResult with words and scores
     */
    fun predictWordsWithContext(
        keySequence: String,
        context: List<String>,
        maxResults: Int = 10
    ): PredictionResult {
        if (keySequence.isEmpty()) {
            return PredictionResult(emptyList(), emptyList())
        }

        return predictInternal(keySequence, context, maxResults)
    }

    /**
     * Predict words with scores (for analysis and debugging)
     */
    fun predictWordsWithScores(
        keySequence: String,
        context: List<String>,
        maxResults: Int = 10
    ): PredictionResult {
        return predictWordsWithContext(keySequence, context, maxResults)
    }

    /**
     * Core prediction algorithm with UNIFIED SCORING (early fusion)
     *
     * Combines ALL signals into one score BEFORE selection:
     * - Prefix match quality
     * - Dictionary frequency (log-scaled)
     * - User adaptation multiplier
     * - Context probability boost
     *
     * Performance: Prefix index reduces 50k iterations to ~100-500 (100x speedup)
     */
    private fun predictInternal(
        keySequence: String,
        context: List<String>,
        maxResults: Int
    ): PredictionResult {
        val lowerSequence = keySequence.lowercase()

        // PERFORMANCE: Prefix index reduces 50k iterations to ~100-500 (100x speedup)
        val candidateWords = getPrefixCandidates(lowerSequence)

        if (candidateWords.isEmpty()) {
            return PredictionResult(emptyList(), emptyList())
        }

        // UNIFIED SCORING with EARLY FUSION
        val candidates = mutableListOf<WordCandidate>()

        for (word in candidateWords) {
            // SKIP DISABLED WORDS
            if (isWordDisabled(word)) continue

            // Get dictionary frequency
            val frequency = dictionary[word] ?: 0
            if (frequency == 0) continue

            // UNIFIED SCORING: Combine ALL signals into one score BEFORE selection
            val score = calculateUnifiedScore(word, lowerSequence, frequency, context)

            if (score > 0) {
                candidates.add(WordCandidate(word, score))
            }
        }

        // Sort by score (descending) and take top N
        candidates.sortByDescending { it.score }
        val topCandidates = candidates.take(maxResults)

        val words = topCandidates.map { it.word }
        val scores = topCandidates.map { it.score }

        return PredictionResult(words, scores)
    }

    /**
     * UNIFIED SCORING - Combines all prediction signals (early fusion)
     *
     * Combines: prefix quality + frequency + user adaptation + context probability
     * Context is evaluated for ALL candidates, not just top N (key improvement)
     *
     * @param word The word being scored
     * @param keySequence The typed prefix
     * @param frequency Dictionary frequency (higher = more common)
     * @param context Previous words for contextual prediction (can be empty)
     * @return Combined score
     */
    private fun calculateUnifiedScore(
        word: String,
        keySequence: String,
        frequency: Int,
        context: List<String>
    ): Int {
        // 1. Base score from prefix match quality
        val prefixScore = calculatePrefixScore(word, keySequence)
        if (prefixScore == 0) return 0 // Should not happen if caller does prefix check

        // 2. User adaptation multiplier (learns user's vocabulary)
        val adaptationMultiplier = adaptationManager?.getAdaptationMultiplier(word) ?: 1.0f

        // 3. Context multiplier (bigram probability boost)
        val model = bigramModel  // Local variable for smart cast
        val contextMultiplier = if (model != null && context.isNotEmpty()) {
            model.getContextMultiplier(word, context)
        } else {
            1.0f
        }

        // 4. Frequency scaling (log to prevent common words from dominating)
        // Using log1p helps balance: "the" (freq ~10000) vs "think" (freq ~100)
        // Without log: "the" would always win. With log: context can override frequency
        // Scale factor is configurable (default: 1000.0)
        val frequencyScale = config.prediction_frequency_scale
        val frequencyFactor = 1.0f + ln1p(frequency / frequencyScale).toFloat()

        // COMBINE ALL SIGNALS
        // Formula: prefixScore × adaptation × (1 + boosted_context) × freq_factor
        // Context boost is configurable (default: 2.0)
        // Higher boost = context has more influence on predictions
        val contextBoost = config.prediction_context_boost
        val finalScore = prefixScore *
                adaptationMultiplier *
                (1.0f + (contextMultiplier - 1.0f) * contextBoost) *
                frequencyFactor

        return finalScore.toInt()
    }

    /**
     * Calculate base score for prefix-based matching (used by unified scoring)
     */
    private fun calculatePrefixScore(word: String, keySequence: String): Int {
        // Direct match is highest score
        if (word == keySequence) {
            return 1000
        }

        // Word starts with sequence (this is guaranteed by caller, but score based on completion ratio)
        if (word.startsWith(keySequence)) {
            // Higher score for more completion, but prefer shorter completions
            val baseScore = 800

            // Bonus for more typed characters (longer prefix = more specific)
            val prefixBonus = keySequence.length * 50

            // Slight penalty for very long words to prefer common shorter words
            val lengthPenalty = max(0, (word.length - 6) * 10)

            return baseScore + prefixBonus - lengthPenalty
        }

        return 0 // Should not reach here due to prefix check in caller
    }

    /**
     * Auto-correct a typed word after user presses space/punctuation.
     *
     * Finds dictionary words with:
     * - Same length
     * - Same first 2 letters
     * - High positional character match (default: 2/3 chars)
     *
     * Example: "teh" → "the", "Teh" → "The", "TEH" → "THE"
     *
     * @param typedWord The word user just finished typing
     * @return Corrected word, or original if no suitable correction found
     */
    fun autoCorrect(typedWord: String): String {
        if (!config.autocorrect_enabled || typedWord.isEmpty()) {
            return typedWord
        }

        val lowerTypedWord = typedWord.lowercase()

        // 1. Do not correct words already in dictionary or user's vocabulary
        if (dictionary.containsKey(lowerTypedWord) ||
            (adaptationManager?.getAdaptationMultiplier(lowerTypedWord) ?: 1.0f) > 1.0f
        ) {
            return typedWord
        }

        // 2. Enforce minimum word length for correction
        if (lowerTypedWord.length < config.autocorrect_min_word_length) {
            return typedWord
        }

        // 3. "Same first 2 letters" rule requires at least 2 characters
        if (lowerTypedWord.length < 2) {
            return typedWord
        }

        val prefix = lowerTypedWord.substring(0, 2)
        val wordLength = lowerTypedWord.length
        var bestCandidate: WordCandidate? = null

        // 4. Iterate through dictionary to find candidates
        for ((dictWord, frequency) in dictionary) {
            // Heuristic 1: Must have same length
            if (dictWord.length != wordLength) {
                continue
            }

            // Heuristic 2: Must start with same first two letters
            if (!dictWord.startsWith(prefix)) {
                continue
            }

            // Heuristic 3: Calculate positional character match ratio
            var matchCount = 0
            for (i in dictWord.indices) {
                if (lowerTypedWord[i] == dictWord[i]) {
                    matchCount++
                }
            }

            val matchRatio = matchCount.toFloat() / wordLength
            if (matchRatio >= config.autocorrect_char_match_threshold) {
                // Valid candidate - select if better than current best
                // "Better" = higher dictionary frequency
                if (bestCandidate == null || frequency > bestCandidate.score) {
                    bestCandidate = WordCandidate(dictWord, frequency)
                }
            }
        }

        // 5. Apply correction only if confident candidate found
        if (bestCandidate != null && bestCandidate.score >= config.autocorrect_confidence_min_frequency) {
            // Preserve original capitalization (e.g., "Teh" → "The")
            val corrected = preserveCapitalization(typedWord, bestCandidate.word)
            Log.d(TAG, "AUTO-CORRECT: '$typedWord' → '$corrected' (freq=${bestCandidate.score})")
            return corrected
        }

        return typedWord // No suitable correction found
    }

    /**
     * Preserve capitalization of original word when applying correction.
     *
     * Examples:
     * - "teh" + "the" → "the"
     * - "Teh" + "the" → "The"
     * - "TEH" + "the" → "THE"
     */
    private fun preserveCapitalization(originalWord: String, correctedWord: String): String {
        if (originalWord.isEmpty() || correctedWord.isEmpty()) {
            return correctedWord
        }

        // Check if ALL uppercase
        val isAllUpper = originalWord.all { it.isUpperCase() || !it.isLetter() }

        if (isAllUpper) {
            return correctedWord.uppercase()
        }

        // Check if first letter uppercase (Title Case)
        if (originalWord[0].isUpperCase()) {
            return correctedWord.replaceFirstChar { it.uppercase() }
        }

        return correctedWord
    }

    /**
     * Get dictionary size
     */
    fun getDictionarySize(): Int = dictionary.size

    /**
     * Get dictionary (for SwipePruner and other components)
     * Returns an immutable copy to prevent external modification
     */
    fun getDictionary(): Map<String, Int> = dictionary.toMap()

    /**
     * Helper data class to store word candidates with scores
     */
    private data class WordCandidate(val word: String, val score: Int)

    /**
     * Result class containing predictions and their scores
     */
    data class PredictionResult(val words: List<String>, val scores: List<Int>)
}
