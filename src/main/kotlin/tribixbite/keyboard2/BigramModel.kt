package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Word-level bigram model for contextual predictions.
 * Provides P(word | previous_word) probabilities for context-aware word prediction.
 *
 * Features:
 * - 4-language support (en, es, fr, de) with language-specific bigram/unigram probabilities
 * - Linear interpolation smoothing (λ=0.95) between bigram and unigram probabilities
 * - Context multiplier (0.1-10.0x) for boosting/penalizing predictions based on previous word
 * - User adaptation via addBigram() for learning new bigram patterns
 * - File loading for comprehensive bigram data from assets
 * - Singleton pattern for global access
 *
 * Fix for Bug #259: NgramModel system missing (CATASTROPHIC)
 */
class BigramModel private constructor() {

    companion object {
        private const val TAG = "BigramModel"

        // Smoothing parameters
        private const val LAMBDA = 0.95f // Interpolation weight for bigram
        private const val MIN_PROB = 0.0001f // Minimum probability for unseen words

        @Volatile
        private var instance: BigramModel? = null

        /**
         * Get singleton instance for global access
         */
        @JvmStatic
        fun getInstance(context: Context? = null): BigramModel {
            return instance ?: synchronized(this) {
                instance ?: BigramModel().also { instance = it }
            }
        }
    }

    // Language-specific bigram models: "language" -> "prev_word|current_word" -> probability
    private val languageBigramProbs = mutableMapOf<String, MutableMap<String, Float>>()

    // Language-specific unigram models: "language" -> word -> probability
    private val languageUnigramProbs = mutableMapOf<String, MutableMap<String, Float>>()

    // Current active language
    private var currentLanguage: String = "en" // Default to English

    init {
        initializeLanguageModels()
    }

    /**
     * Initialize language models with common bigrams for supported languages
     */
    private fun initializeLanguageModels() {
        initializeEnglishModel()
        initializeSpanishModel()
        initializeFrenchModel()
        initializeGermanModel()
        // More languages can be added here
    }

    /**
     * Initialize English language model
     */
    private fun initializeEnglishModel() {
        val enBigrams = mutableMapOf<String, Float>()
        val enUnigrams = mutableMapOf<String, Float>()

        // After "the"
        enBigrams["the|end"] = 0.01f
        enBigrams["the|first"] = 0.015f
        enBigrams["the|last"] = 0.012f
        enBigrams["the|best"] = 0.010f
        enBigrams["the|world"] = 0.008f
        enBigrams["the|time"] = 0.007f
        enBigrams["the|day"] = 0.006f
        enBigrams["the|way"] = 0.005f

        // After "a"
        enBigrams["a|lot"] = 0.02f
        enBigrams["a|little"] = 0.015f
        enBigrams["a|few"] = 0.012f
        enBigrams["a|good"] = 0.010f
        enBigrams["a|great"] = 0.008f
        enBigrams["a|new"] = 0.007f
        enBigrams["a|long"] = 0.006f

        // After "to"
        enBigrams["to|be"] = 0.03f
        enBigrams["to|have"] = 0.02f
        enBigrams["to|do"] = 0.015f
        enBigrams["to|go"] = 0.012f
        enBigrams["to|get"] = 0.010f
        enBigrams["to|make"] = 0.008f
        enBigrams["to|see"] = 0.007f

        // After "of"
        enBigrams["of|the"] = 0.05f
        enBigrams["of|course"] = 0.02f
        enBigrams["of|all"] = 0.015f
        enBigrams["of|this"] = 0.012f
        enBigrams["of|his"] = 0.010f
        enBigrams["of|her"] = 0.008f

        // After "in"
        enBigrams["in|the"] = 0.04f
        enBigrams["in|a"] = 0.02f
        enBigrams["in|this"] = 0.015f
        enBigrams["in|order"] = 0.012f
        enBigrams["in|fact"] = 0.010f
        enBigrams["in|case"] = 0.008f

        // After "I"
        enBigrams["i|am"] = 0.03f
        enBigrams["i|have"] = 0.025f
        enBigrams["i|will"] = 0.02f
        enBigrams["i|was"] = 0.018f
        enBigrams["i|can"] = 0.015f
        enBigrams["i|would"] = 0.012f
        enBigrams["i|think"] = 0.010f
        enBigrams["i|know"] = 0.008f
        enBigrams["i|want"] = 0.007f

        // After "you"
        enBigrams["you|are"] = 0.025f
        enBigrams["you|can"] = 0.02f
        enBigrams["you|have"] = 0.018f
        enBigrams["you|will"] = 0.015f
        enBigrams["you|want"] = 0.012f
        enBigrams["you|know"] = 0.010f
        enBigrams["you|need"] = 0.008f

        // After "it"
        enBigrams["it|is"] = 0.04f
        enBigrams["it|was"] = 0.025f
        enBigrams["it|will"] = 0.015f
        enBigrams["it|would"] = 0.012f
        enBigrams["it|has"] = 0.010f
        enBigrams["it|can"] = 0.008f

        // After "that"
        enBigrams["that|is"] = 0.025f
        enBigrams["that|was"] = 0.02f
        enBigrams["that|the"] = 0.015f
        enBigrams["that|it"] = 0.012f
        enBigrams["that|you"] = 0.010f
        enBigrams["that|he"] = 0.008f

        // After "with"
        enBigrams["with|the"] = 0.03f
        enBigrams["with|a"] = 0.02f
        enBigrams["with|his"] = 0.015f
        enBigrams["with|her"] = 0.012f
        enBigrams["with|my"] = 0.010f
        enBigrams["with|your"] = 0.008f

        // Common unigram probabilities (fallback)
        enUnigrams["the"] = 0.07f
        enUnigrams["be"] = 0.04f
        enUnigrams["to"] = 0.035f
        enUnigrams["of"] = 0.03f
        enUnigrams["and"] = 0.028f
        enUnigrams["a"] = 0.025f
        enUnigrams["in"] = 0.022f
        enUnigrams["that"] = 0.02f
        enUnigrams["have"] = 0.018f
        enUnigrams["i"] = 0.017f
        enUnigrams["it"] = 0.015f
        enUnigrams["for"] = 0.014f
        enUnigrams["not"] = 0.013f
        enUnigrams["on"] = 0.012f
        enUnigrams["with"] = 0.011f
        enUnigrams["he"] = 0.010f
        enUnigrams["as"] = 0.009f
        enUnigrams["you"] = 0.009f
        enUnigrams["do"] = 0.008f
        enUnigrams["at"] = 0.008f

        // Store English language models
        languageBigramProbs["en"] = enBigrams
        languageUnigramProbs["en"] = enUnigrams
    }

    /**
     * Initialize Spanish language model
     */
    private fun initializeSpanishModel() {
        val esBigrams = mutableMapOf<String, Float>()
        val esUnigrams = mutableMapOf<String, Float>()

        // Common Spanish bigrams
        esBigrams["de|la"] = 0.04f
        esBigrams["de|los"] = 0.025f
        esBigrams["en|el"] = 0.035f
        esBigrams["en|la"] = 0.03f
        esBigrams["el|mundo"] = 0.012f
        esBigrams["la|vida"] = 0.015f
        esBigrams["que|es"] = 0.02f
        esBigrams["que|se"] = 0.018f
        esBigrams["no|es"] = 0.015f
        esBigrams["se|puede"] = 0.012f
        esBigrams["por|favor"] = 0.025f
        esBigrams["muchas|gracias"] = 0.03f
        esBigrams["muy|bien"] = 0.02f
        esBigrams["todo|el"] = 0.015f

        // Spanish unigrams
        esUnigrams["de"] = 0.05f
        esUnigrams["la"] = 0.04f
        esUnigrams["que"] = 0.035f
        esUnigrams["el"] = 0.03f
        esUnigrams["en"] = 0.025f
        esUnigrams["y"] = 0.022f
        esUnigrams["a"] = 0.02f
        esUnigrams["es"] = 0.018f
        esUnigrams["se"] = 0.015f
        esUnigrams["no"] = 0.014f
        esUnigrams["te"] = 0.012f
        esUnigrams["lo"] = 0.011f
        esUnigrams["le"] = 0.01f
        esUnigrams["da"] = 0.009f
        esUnigrams["su"] = 0.008f

        languageBigramProbs["es"] = esBigrams
        languageUnigramProbs["es"] = esUnigrams
    }

    /**
     * Initialize French language model
     */
    private fun initializeFrenchModel() {
        val frBigrams = mutableMapOf<String, Float>()
        val frUnigrams = mutableMapOf<String, Float>()

        // Common French bigrams
        frBigrams["de|la"] = 0.045f
        frBigrams["de|le"] = 0.03f
        frBigrams["dans|le"] = 0.025f
        frBigrams["sur|le"] = 0.02f
        frBigrams["avec|le"] = 0.018f
        frBigrams["pour|le"] = 0.015f
        frBigrams["il|y"] = 0.025f
        frBigrams["y|a"] = 0.03f
        frBigrams["c'est|le"] = 0.02f
        frBigrams["je|suis"] = 0.025f
        frBigrams["tu|es"] = 0.02f
        frBigrams["nous|sommes"] = 0.015f
        frBigrams["très|bien"] = 0.018f
        frBigrams["tout|le"] = 0.022f

        // French unigrams
        frUnigrams["de"] = 0.06f
        frUnigrams["le"] = 0.045f
        frUnigrams["et"] = 0.035f
        frUnigrams["à"] = 0.03f
        frUnigrams["un"] = 0.025f
        frUnigrams["il"] = 0.022f
        frUnigrams["être"] = 0.02f
        frUnigrams["en"] = 0.016f
        frUnigrams["avoir"] = 0.014f
        frUnigrams["que"] = 0.012f
        frUnigrams["pour"] = 0.011f
        frUnigrams["dans"] = 0.01f
        frUnigrams["ce"] = 0.009f
        frUnigrams["son"] = 0.008f

        languageBigramProbs["fr"] = frBigrams
        languageUnigramProbs["fr"] = frUnigrams
    }

    /**
     * Initialize German language model
     */
    private fun initializeGermanModel() {
        val deBigrams = mutableMapOf<String, Float>()
        val deUnigrams = mutableMapOf<String, Float>()

        // Common German bigrams
        deBigrams["der|die"] = 0.03f
        deBigrams["in|der"] = 0.035f
        deBigrams["von|der"] = 0.025f
        deBigrams["mit|der"] = 0.02f
        deBigrams["auf|der"] = 0.018f
        deBigrams["zu|der"] = 0.015f
        deBigrams["ich|bin"] = 0.025f
        deBigrams["du|bist"] = 0.02f
        deBigrams["er|ist"] = 0.022f
        deBigrams["wir|sind"] = 0.018f
        deBigrams["das|ist"] = 0.03f
        deBigrams["sehr|gut"] = 0.02f
        deBigrams["vielen|dank"] = 0.025f
        deBigrams["guten|tag"] = 0.015f

        // German unigrams
        deUnigrams["der"] = 0.055f
        deUnigrams["die"] = 0.045f
        deUnigrams["und"] = 0.035f
        deUnigrams["in"] = 0.03f
        deUnigrams["den"] = 0.025f
        deUnigrams["von"] = 0.022f
        deUnigrams["zu"] = 0.02f
        deUnigrams["das"] = 0.018f
        deUnigrams["mit"] = 0.016f
        deUnigrams["sich"] = 0.014f
        deUnigrams["auf"] = 0.012f
        deUnigrams["für"] = 0.011f
        deUnigrams["ist"] = 0.01f
        deUnigrams["im"] = 0.009f
        deUnigrams["dem"] = 0.008f

        languageBigramProbs["de"] = deBigrams
        languageUnigramProbs["de"] = deUnigrams
    }

    /**
     * Set the active language for predictions
     */
    fun setLanguage(language: String) {
        if (languageBigramProbs.containsKey(language)) {
            currentLanguage = language
            Log.d(TAG, "Language set to: $language")
        } else {
            Log.w(TAG, "Language not supported: $language, falling back to English")
            currentLanguage = "en"
        }
    }

    /**
     * Get the current active language
     */
    fun getCurrentLanguage(): String = currentLanguage

    /**
     * Check if a language is supported
     */
    fun isLanguageSupported(language: String): Boolean {
        return languageBigramProbs.containsKey(language)
    }

    /**
     * Load bigram data from a file (future enhancement)
     */
    fun loadFromFile(context: Context, filename: String) {
        // Load comprehensive bigram data from assets for current language
        // Format: prev_word current_word probability
        val bigramProbs = languageBigramProbs.getOrPut(currentLanguage) { mutableMapOf() }

        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open(filename)))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val bigram = "${parts[0].lowercase()}|${parts[1].lowercase()}"
                    val prob = parts[2].toFloatOrNull() ?: continue
                    bigramProbs[bigram] = prob
                }
            }
            reader.close()
            Log.d(TAG, "Loaded ${bigramProbs.size} bigrams for $currentLanguage from $filename")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load bigram file: $filename", e)
        }
    }

    /**
     * Get the probability of a word given the previous word(s)
     * Uses linear interpolation between bigram and unigram probabilities
     */
    fun getContextualProbability(word: String?, context: List<String>?): Float {
        if (word.isNullOrEmpty()) {
            return MIN_PROB
        }

        val lowerWord = word.lowercase()

        // Get language-specific probability maps
        var bigramProbs = languageBigramProbs[currentLanguage]
        var unigramProbs = languageUnigramProbs[currentLanguage]

        // Fallback to English if current language not available
        if (bigramProbs == null || unigramProbs == null) {
            bigramProbs = languageBigramProbs["en"]
            unigramProbs = languageUnigramProbs["en"]
        }

        // If no context, return unigram probability
        if (context.isNullOrEmpty()) {
            return unigramProbs?.get(lowerWord) ?: MIN_PROB
        }

        // Get the previous word
        val prevWord = context.last().lowercase()
        val bigramKey = "$prevWord|$lowerWord"

        // Look up bigram probability
        val bigramProb = bigramProbs?.get(bigramKey) ?: 0.0f

        // Look up unigram probability (fallback)
        val unigramProb = unigramProbs?.get(lowerWord) ?: MIN_PROB

        // Linear interpolation: λ * P(word|prev) + (1-λ) * P(word)
        val interpolatedProb = LAMBDA * bigramProb + (1 - LAMBDA) * unigramProb

        // Ensure minimum probability
        return max(interpolatedProb, MIN_PROB)
    }

    /**
     * Score a word based on context (returns log probability for numerical stability)
     */
    fun scoreWord(word: String, context: List<String>?): Float {
        val prob = getContextualProbability(word, context)
        // Return log probability to avoid underflow
        return ln(prob.toDouble()).toFloat()
    }

    /**
     * Get a multiplier for prediction scoring (1.0 = neutral, >1.0 = boost, <1.0 = penalty)
     */
    fun getContextMultiplier(word: String, context: List<String>?): Float {
        if (context.isNullOrEmpty()) {
            return 1.0f
        }

        // Get language-specific unigram probabilities
        var unigramProbs = languageUnigramProbs[currentLanguage]
        if (unigramProbs == null) {
            unigramProbs = languageUnigramProbs["en"] // Fallback to English
        }

        val contextProb = getContextualProbability(word, context)
        val baseProb = unigramProbs?.get(word.lowercase()) ?: MIN_PROB

        // Return ratio of contextual to base probability
        // This gives a boost when context makes the word more likely
        val multiplier = contextProb / baseProb

        // Cap the multiplier to avoid extreme values
        return min(max(multiplier, 0.1f), 10.0f)
    }

    /**
     * Add a bigram observation (for user adaptation)
     */
    fun addBigram(prevWord: String, word: String, weight: Float) {
        val bigramProbs = languageBigramProbs[currentLanguage]
            ?: languageBigramProbs["en"] // Fallback to English

        val bigramKey = "${prevWord.lowercase()}|${word.lowercase()}"
        val currentProb = bigramProbs?.get(bigramKey) ?: 0.0f
        // Simple exponential smoothing for adaptation
        val newProb = 0.9f * currentProb + 0.1f * weight
        bigramProbs?.put(bigramKey, newProb)
    }

    /**
     * Get statistics about the model
     */
    fun getStatistics(): String {
        val currentBigrams = languageBigramProbs[currentLanguage]
        val currentUnigrams = languageUnigramProbs[currentLanguage]

        var totalBigramCount = 0
        var totalUnigramCount = 0

        for (bigramMap in languageBigramProbs.values) {
            totalBigramCount += bigramMap.size
        }

        for (unigramMap in languageUnigramProbs.values) {
            totalUnigramCount += unigramMap.size
        }

        return String.format(
            "BigramModel: Current Language: %s (%d bigrams, %d unigrams), Total: %d languages, %d bigrams, %d unigrams",
            currentLanguage,
            currentBigrams?.size ?: 0,
            currentUnigrams?.size ?: 0,
            languageBigramProbs.size,
            totalBigramCount,
            totalUnigramCount
        )
    }

    /**
     * Get all words from current language dictionary
     * Used by Dictionary Manager UI
     * @return List of all words in current language
     */
    fun getAllWords(): List<String> {
        val unigramMap = languageUnigramProbs[currentLanguage] ?: return emptyList()
        return unigramMap.keys.toList()
    }

    /**
     * Get frequency for a specific word (0-1000 scale)
     * @param word Word to look up
     * @return Frequency score (probability * 1000)
     */
    fun getWordFrequency(word: String): Int {
        val unigramMap = languageUnigramProbs[currentLanguage] ?: return 0
        val prob = unigramMap[word.lowercase()] ?: return 0
        // Convert probability (0.0-1.0) to frequency score (0-1000)
        return (prob * 1000.0f).toInt()
    }
}
