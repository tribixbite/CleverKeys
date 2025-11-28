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
 * Provides P(word | previous_word) probabilities.
 */
class BigramModel private constructor() {
    companion object {
        private const val TAG = "BigramModel"

        // Smoothing parameters
        private const val LAMBDA = 0.95f // Interpolation weight for bigram
        private const val MIN_PROB = 0.0001f // Minimum probability for unseen words

        @Volatile
        private var instance: BigramModel? = null

        @JvmStatic
        fun getInstance(context: Context?): BigramModel {
            return instance ?: synchronized(this) {
                instance ?: BigramModel().also { instance = it }
            }
        }
    }

    // Language-specific bigram models: "language" -> "prev_word|current_word" -> probability
    private val languageBigramProbs: MutableMap<String, MutableMap<String, Float>> = mutableMapOf()

    // Language-specific unigram models: "language" -> word -> probability
    private val languageUnigramProbs: MutableMap<String, MutableMap<String, Float>> = mutableMapOf()

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
        val enBigrams = mutableMapOf(
            // After "the"
            "the|end" to 0.01f,
            "the|first" to 0.015f,
            "the|last" to 0.012f,
            "the|best" to 0.010f,
            "the|world" to 0.008f,
            "the|time" to 0.007f,
            "the|day" to 0.006f,
            "the|way" to 0.005f,

            // After "a"
            "a|lot" to 0.02f,
            "a|little" to 0.015f,
            "a|few" to 0.012f,
            "a|good" to 0.010f,
            "a|great" to 0.008f,
            "a|new" to 0.007f,
            "a|long" to 0.006f,

            // After "to"
            "to|be" to 0.03f,
            "to|have" to 0.02f,
            "to|do" to 0.015f,
            "to|go" to 0.012f,
            "to|get" to 0.010f,
            "to|make" to 0.008f,
            "to|see" to 0.007f,

            // After "of"
            "of|the" to 0.05f,
            "of|course" to 0.02f,
            "of|all" to 0.015f,
            "of|this" to 0.012f,
            "of|his" to 0.010f,
            "of|her" to 0.008f,

            // After "in"
            "in|the" to 0.04f,
            "in|a" to 0.02f,
            "in|this" to 0.015f,
            "in|order" to 0.012f,
            "in|fact" to 0.010f,
            "in|case" to 0.008f,

            // After "I"
            "i|am" to 0.03f,
            "i|have" to 0.025f,
            "i|will" to 0.02f,
            "i|was" to 0.018f,
            "i|can" to 0.015f,
            "i|would" to 0.012f,
            "i|think" to 0.010f,
            "i|know" to 0.008f,
            "i|want" to 0.007f,

            // After "you"
            "you|are" to 0.025f,
            "you|can" to 0.02f,
            "you|have" to 0.018f,
            "you|will" to 0.015f,
            "you|want" to 0.012f,
            "you|know" to 0.010f,
            "you|need" to 0.008f,

            // After "it"
            "it|is" to 0.04f,
            "it|was" to 0.025f,
            "it|will" to 0.015f,
            "it|would" to 0.012f,
            "it|has" to 0.010f,
            "it|can" to 0.008f,

            // After "that"
            "that|is" to 0.025f,
            "that|was" to 0.02f,
            "that|the" to 0.015f,
            "that|it" to 0.012f,
            "that|you" to 0.010f,
            "that|he" to 0.008f,

            // After "with"
            "with|the" to 0.03f,
            "with|a" to 0.02f,
            "with|his" to 0.015f,
            "with|her" to 0.012f,
            "with|my" to 0.010f,
            "with|your" to 0.008f
        )

        val enUnigrams = mutableMapOf(
            "the" to 0.07f,
            "be" to 0.04f,
            "to" to 0.035f,
            "of" to 0.03f,
            "and" to 0.028f,
            "a" to 0.025f,
            "in" to 0.022f,
            "that" to 0.02f,
            "have" to 0.018f,
            "i" to 0.017f,
            "it" to 0.015f,
            "for" to 0.014f,
            "not" to 0.013f,
            "on" to 0.012f,
            "with" to 0.011f,
            "he" to 0.010f,
            "as" to 0.009f,
            "you" to 0.009f,
            "do" to 0.008f,
            "at" to 0.008f
        )

        // Store English language models
        languageBigramProbs["en"] = enBigrams
        languageUnigramProbs["en"] = enUnigrams
    }

    /**
     * Initialize Spanish language model
     */
    private fun initializeSpanishModel() {
        val esBigrams = mutableMapOf(
            // Common Spanish bigrams
            "de|la" to 0.04f,
            "de|los" to 0.025f,
            "en|el" to 0.035f,
            "en|la" to 0.03f,
            "el|mundo" to 0.012f,
            "la|vida" to 0.015f,
            "que|es" to 0.02f,
            "que|se" to 0.018f,
            "no|es" to 0.015f,
            "se|puede" to 0.012f,
            "por|favor" to 0.025f,
            "muchas|gracias" to 0.03f,
            "muy|bien" to 0.02f,
            "todo|el" to 0.015f
        )

        val esUnigrams = mutableMapOf(
            "de" to 0.05f,
            "la" to 0.04f,
            "que" to 0.035f,
            "el" to 0.03f,
            "en" to 0.025f,
            "y" to 0.022f,
            "a" to 0.02f,
            "es" to 0.018f,
            "se" to 0.015f,
            "no" to 0.014f,
            "te" to 0.012f,
            "lo" to 0.011f,
            "le" to 0.01f,
            "da" to 0.009f,
            "su" to 0.008f
        )

        languageBigramProbs["es"] = esBigrams
        languageUnigramProbs["es"] = esUnigrams
    }

    /**
     * Initialize French language model
     */
    private fun initializeFrenchModel() {
        val frBigrams = mutableMapOf(
            // Common French bigrams
            "de|la" to 0.045f,
            "de|le" to 0.03f,
            "dans|le" to 0.025f,
            "sur|le" to 0.02f,
            "avec|le" to 0.018f,
            "pour|le" to 0.015f,
            "il|y" to 0.025f,
            "y|a" to 0.03f,
            "c'est|le" to 0.02f,
            "je|suis" to 0.025f,
            "tu|es" to 0.02f,
            "nous|sommes" to 0.015f,
            "très|bien" to 0.018f,
            "tout|le" to 0.022f
        )

        val frUnigrams = mutableMapOf(
            "de" to 0.06f,
            "le" to 0.045f,
            "et" to 0.035f,
            "à" to 0.03f,
            "un" to 0.025f,
            "il" to 0.022f,
            "être" to 0.02f,
            "en" to 0.016f,
            "avoir" to 0.014f,
            "que" to 0.012f,
            "pour" to 0.011f,
            "dans" to 0.01f,
            "ce" to 0.009f,
            "son" to 0.008f
        )

        languageBigramProbs["fr"] = frBigrams
        languageUnigramProbs["fr"] = frUnigrams
    }

    /**
     * Initialize German language model
     */
    private fun initializeGermanModel() {
        val deBigrams = mutableMapOf(
            // Common German bigrams
            "der|die" to 0.03f,
            "in|der" to 0.035f,
            "von|der" to 0.025f,
            "mit|der" to 0.02f,
            "auf|der" to 0.018f,
            "zu|der" to 0.015f,
            "ich|bin" to 0.025f,
            "du|bist" to 0.02f,
            "er|ist" to 0.022f,
            "wir|sind" to 0.018f,
            "das|ist" to 0.03f,
            "sehr|gut" to 0.02f,
            "vielen|dank" to 0.025f,
            "guten|tag" to 0.015f
        )

        val deUnigrams = mutableMapOf(
            "der" to 0.055f,
            "die" to 0.045f,
            "und" to 0.035f,
            "in" to 0.03f,
            "den" to 0.025f,
            "von" to 0.022f,
            "zu" to 0.02f,
            "das" to 0.018f,
            "mit" to 0.016f,
            "sich" to 0.014f,
            "auf" to 0.012f,
            "für" to 0.011f,
            "ist" to 0.01f,
            "im" to 0.009f,
            "dem" to 0.008f
        )

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
    fun getCurrentLanguage(): String {
        return currentLanguage
    }

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
        var bigramProbs = languageBigramProbs[currentLanguage]
        if (bigramProbs == null) {
            bigramProbs = mutableMapOf()
            languageBigramProbs[currentLanguage] = bigramProbs
        }

        try {
            val reader = BufferedReader(
                InputStreamReader(context.assets.open(filename))
            )
            reader.useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        val bigram = "${parts[0].lowercase()}|${parts[1].lowercase()}"
                        val prob = parts[2].toFloat()
                        bigramProbs[bigram] = prob
                    }
                }
            }
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

        val normalizedWord = word.lowercase()

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
            return unigramProbs?.get(normalizedWord) ?: MIN_PROB
        }

        // Get the previous word
        val prevWord = context.last().lowercase()
        val bigramKey = "$prevWord|$normalizedWord"

        // Look up bigram probability
        val bigramProb = bigramProbs?.get(bigramKey) ?: 0.0f

        // Look up unigram probability (fallback)
        val unigramProb = unigramProbs?.get(normalizedWord) ?: MIN_PROB

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
        return ln(prob)
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
        var bigramProbs = languageBigramProbs[currentLanguage]
        if (bigramProbs == null) {
            bigramProbs = languageBigramProbs["en"] // Fallback to English
        }

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

        val totalBigramCount = languageBigramProbs.values.sumOf { it.size }
        val totalUnigramCount = languageUnigramProbs.values.sumOf { it.size }

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
        val unigramMap = languageUnigramProbs[currentLanguage]
        return unigramMap?.keys?.toList() ?: emptyList()
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
