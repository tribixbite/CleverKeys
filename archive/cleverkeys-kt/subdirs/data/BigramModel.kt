package tribixbite.cleverkeys.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Bigram Model for Context-Aware Word Predictions
 *
 * Provides contextual word prediction using bigram (2-word) probabilities.
 * Given a previous word, predicts the likelihood of next words.
 *
 * Features:
 * - Multi-language support (loads language-specific bigram files)
 * - JSON format: {"word1 word2": probability, ...}
 * - Context multiplier for weighted scoring
 * - Configurable boost factor
 *
 * Performance:
 * - Lazy loading (loads on first use per language)
 * - Compact storage (probabilities as floats 0.0-1.0)
 *
 * File format:
 * ```json
 * {
 *   "the house": 0.85,
 *   "the car": 0.92,
 *   "the book": 0.78
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * val model = BigramModel(context)
 * model.setLanguage("en")
 * val multiplier = model.getContextMultiplier("house", listOf("the"))
 * // multiplier will be > 1.0 if "the house" is common
 * ```
 */
class BigramModel(private val context: Context) {

    companion object {
        private const val TAG = "BigramModel"
        private val SUPPORTED_LANGUAGES = setOf("en", "es", "fr", "de", "it", "pt")
    }

    // Bigram probabilities: "word1 word2" → probability (0.0-1.0)
    private val bigramProbs = mutableMapOf<String, Float>()
    private var currentLanguage: String? = null

    /**
     * Check if a language is supported
     */
    fun isLanguageSupported(language: String): Boolean {
        return language in SUPPORTED_LANGUAGES
    }

    /**
     * Set current language (triggers async loading if not already loaded)
     * Note: Loading happens asynchronously, so bigram data may not be immediately available
     */
    fun setLanguage(language: String) {
        if (language == currentLanguage) return // Already loaded

        if (!isLanguageSupported(language)) {
            Log.w(TAG, "Language $language not supported for bigram model")
            return
        }

        currentLanguage = language
        // Note: Actual loading should be triggered separately via loadBigramData()
        // This is intentionally non-blocking to allow use from non-suspend contexts
    }

    /**
     * Load bigram data for current language (call this from suspend context)
     */
    suspend fun loadCurrentLanguage() {
        val lang = currentLanguage ?: return
        loadBigramData(lang)
    }

    /**
     * Load bigram data from assets for a specific language
     */
    private suspend fun loadBigramData(language: String) = withContext(Dispatchers.IO) {
        bigramProbs.clear()

        val filename = "bigrams/${language}_bigrams.json"
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(filename)))
            val jsonBuilder = StringBuilder()
            reader.use { r ->
                r.forEachLine { line ->
                    jsonBuilder.append(line)
                }
            }

            // Parse JSON object
            val jsonObj = JSONObject(jsonBuilder.toString())
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val bigram = keys.next().lowercase()
                val probability = jsonObj.getDouble(bigram).toFloat()
                bigramProbs[bigram] = probability
            }

            Log.d(TAG, "Loaded bigram model for $language: ${bigramProbs.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bigram data for $language: ${e.message}")
        }
    }

    /**
     * Get context multiplier for a word given previous words
     *
     * Returns a multiplier > 1.0 if the word is likely given context,
     * or 1.0 if no context information available.
     *
     * @param word The word to score
     * @param context Previous words (uses last word for bigram)
     * @return Multiplier (typically 1.0-3.0, where 1.0 = no boost)
     */
    fun getContextMultiplier(word: String, context: List<String>): Float {
        if (bigramProbs.isEmpty() || context.isEmpty()) {
            return 1.0f
        }

        // Use last word from context for bigram lookup
        val previousWord = context.lastOrNull()?.lowercase() ?: return 1.0f
        val bigram = "$previousWord $word"

        // Get bigram probability (0.0-1.0)
        val probability = bigramProbs[bigram] ?: return 1.0f

        // Convert probability to multiplier
        // Probability 0.5 → multiplier 1.5 (50% boost)
        // Probability 0.9 → multiplier 2.8 (180% boost)
        // Formula: 1.0 + (probability * 2)
        return 1.0f + (probability * 2.0f)
    }

    /**
     * Get raw bigram probability (for debugging/analysis)
     */
    fun getBigramProbability(word1: String, word2: String): Float {
        val bigram = "${word1.lowercase()} ${word2.lowercase()}"
        return bigramProbs[bigram] ?: 0.0f
    }

    /**
     * Get number of loaded bigrams
     */
    fun getBigramCount(): Int = bigramProbs.size
}
