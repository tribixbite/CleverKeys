package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Word-based language detection using unigram frequency analysis.
 *
 * Detects which language the user is typing based on recent word patterns.
 * Uses word frequency lists (unigrams) rather than character n-grams for
 * better accuracy with swipe typing where individual characters are uncertain.
 *
 * ## Detection Algorithm
 * 1. Maintain a sliding window of recent words (e.g., last 10 words)
 * 2. For each language, count how many recent words appear in its top-N unigrams
 * 3. Weight by frequency rank (common words weighted higher)
 * 4. Return normalized scores per language
 *
 * ## Usage
 * ```kotlin
 * val detector = UnigramLanguageDetector(context)
 * detector.loadLanguages(listOf("en", "es"))
 *
 * // As user types...
 * detector.addWord("hello")
 * detector.addWord("world")
 *
 * val scores = detector.getLanguageScores()
 * // scores = {"en": 0.85, "es": 0.15}
 * ```
 *
 * @since v1.2.2 - Phase 3 multilanguage
 */
class UnigramLanguageDetector(private val context: Context) {

    companion object {
        private const val TAG = "UnigramLangDetector"

        // Configuration
        private const val WINDOW_SIZE = 10 // Recent words to consider
        private const val TOP_UNIGRAMS = 5000 // Only load top N words per language
        private const val MIN_WORD_LENGTH = 2 // Ignore very short words
    }

    /**
     * Unigram data for a language.
     * Maps normalized word → frequency rank (0 = most common)
     */
    data class LanguageUnigrams(
        val languageCode: String,
        val unigrams: Map<String, Int>, // word → rank
        val totalWords: Int
    )

    // Loaded language models
    private val languages = mutableMapOf<String, LanguageUnigrams>()

    // Recent words window (circular buffer)
    private val recentWords = ArrayDeque<String>(WINDOW_SIZE)

    // Cached scores (invalidated when recentWords changes)
    private var cachedScores: Map<String, Float>? = null

    /**
     * Load unigram frequency lists for specified languages.
     *
     * @param languageCodes List of language codes (e.g., ["en", "es", "fr"])
     * @return Number of languages successfully loaded
     */
    fun loadLanguages(languageCodes: List<String>): Int {
        var loaded = 0
        for (code in languageCodes) {
            if (loadLanguage(code)) {
                loaded++
            }
        }
        Log.i(TAG, "Loaded $loaded/${languageCodes.size} language unigram models")
        return loaded
    }

    /**
     * Load unigram frequency list for a single language.
     *
     * Expected file format: one word per line, ordered by frequency (most common first)
     * File location: assets/unigrams/{lang}_unigrams.txt
     *
     * @param languageCode Language code (e.g., "en", "es")
     * @return true if loaded successfully
     */
    fun loadLanguage(languageCode: String): Boolean {
        if (languages.containsKey(languageCode)) {
            Log.d(TAG, "Language already loaded: $languageCode")
            return true
        }

        try {
            val filename = "unigrams/${languageCode}_unigrams.txt"
            val inputStream = context.assets.open(filename)
            val reader = BufferedReader(InputStreamReader(inputStream))

            val unigrams = mutableMapOf<String, Int>()
            var rank = 0

            reader.useLines { lines ->
                for (line in lines) {
                    if (rank >= TOP_UNIGRAMS) break

                    val word = line.trim().lowercase()
                    if (word.isNotEmpty() && word.length >= MIN_WORD_LENGTH) {
                        // Normalize for lookup (remove accents for matching)
                        val normalized = AccentNormalizer.normalize(word)
                        unigrams[normalized] = rank
                        rank++
                    }
                }
            }

            if (unigrams.isNotEmpty()) {
                languages[languageCode] = LanguageUnigrams(
                    languageCode = languageCode,
                    unigrams = unigrams,
                    totalWords = rank
                )
                Log.i(TAG, "Loaded $languageCode unigrams: ${unigrams.size} words")
                return true
            }

            Log.w(TAG, "No unigrams found for $languageCode")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load unigrams for $languageCode", e)
            return false
        }
    }

    /**
     * Unload a language model to free memory.
     */
    fun unloadLanguage(languageCode: String) {
        languages.remove(languageCode)
        invalidateCache()
        Log.i(TAG, "Unloaded language: $languageCode")
    }

    /**
     * Add a word to the recent words window.
     * Call this after the user commits a word (tap suggestion or space).
     *
     * @param word The word the user typed/selected
     */
    fun addWord(word: String) {
        val normalized = AccentNormalizer.normalize(word.lowercase().trim())

        if (normalized.length < MIN_WORD_LENGTH) return
        if (!normalized.matches("^[a-z]+$".toRegex())) return

        // Add to window (circular buffer)
        if (recentWords.size >= WINDOW_SIZE) {
            recentWords.removeFirst()
        }
        recentWords.addLast(normalized)

        invalidateCache()
    }

    /**
     * Clear the recent words window.
     * Call this when starting a new text field or conversation.
     */
    fun clearHistory() {
        recentWords.clear()
        invalidateCache()
    }

    /**
     * Get language confidence scores based on recent words.
     *
     * @return Map of language code → confidence score (0.0-1.0)
     */
    fun getLanguageScores(): Map<String, Float> {
        // Return cached if valid
        cachedScores?.let { return it }

        if (languages.isEmpty() || recentWords.isEmpty()) {
            // No data - return equal scores
            val equalScore = if (languages.isEmpty()) 1.0f else 1.0f / languages.size
            return languages.keys.associateWith { equalScore }
        }

        // Calculate raw scores per language
        val rawScores = mutableMapOf<String, Float>()

        for ((code, langData) in languages) {
            var score = 0f

            for (word in recentWords) {
                val rank = langData.unigrams[word]
                if (rank != null) {
                    // Weight by frequency (common words = higher weight)
                    // rank 0 (most common) → weight 1.0
                    // rank 4999 → weight ~0.0
                    val weight = 1.0f - (rank.toFloat() / TOP_UNIGRAMS)
                    score += weight
                }
            }

            rawScores[code] = score
        }

        // Normalize to 0-1 range
        val totalScore = rawScores.values.sum()
        val normalizedScores = if (totalScore > 0) {
            rawScores.mapValues { (_, score) -> score / totalScore }
        } else {
            // No matches - return equal scores
            val equalScore = 1.0f / languages.size
            languages.keys.associateWith { equalScore }
        }

        cachedScores = normalizedScores
        return normalizedScores
    }

    /**
     * Get the most likely language based on recent words.
     *
     * @return Language code with highest score, or null if no languages loaded
     */
    fun getPrimaryLanguage(): String? {
        val scores = getLanguageScores()
        return scores.maxByOrNull { it.value }?.key
    }

    /**
     * Get language score for a specific language.
     *
     * @param languageCode Language code
     * @return Confidence score (0.0-1.0), or 0.0 if language not loaded
     */
    fun getLanguageScore(languageCode: String): Float {
        return getLanguageScores()[languageCode] ?: 0f
    }

    /**
     * Check if a language is loaded.
     */
    fun isLanguageLoaded(languageCode: String): Boolean {
        return languages.containsKey(languageCode)
    }

    /**
     * Get list of loaded language codes.
     */
    fun getLoadedLanguages(): Set<String> {
        return languages.keys.toSet()
    }

    /**
     * Get statistics about the detector state.
     */
    fun getStats(): DetectorStats {
        return DetectorStats(
            loadedLanguages = languages.keys.toList(),
            recentWordCount = recentWords.size,
            windowSize = WINDOW_SIZE,
            currentScores = getLanguageScores()
        )
    }

    data class DetectorStats(
        val loadedLanguages: List<String>,
        val recentWordCount: Int,
        val windowSize: Int,
        val currentScores: Map<String, Float>
    )

    private fun invalidateCache() {
        cachedScores = null
    }
}
