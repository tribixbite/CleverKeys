package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Word-level bigram model for contextual predictions.
 *
 * Two INDEPENDENT products, deliberately kept on separate data (ARC-010):
 *
 *  1. **The scoring multiplier** ([getContextualProbability] → [getContextMultiplier],
 *     consumed live by `WordPredictor.resolveScoreBreakdown`) runs off the
 *     hardcoded per-language tables below. Their bigram values sit on the same
 *     joint/marginal scale as the unigram table (`0.005…0.05` vs `0.008…0.07`),
 *     which is what the `λ·P(w|prev) + (1−λ)·P(w)` interpolation requires.
 *  2. **The static next-word seed** ([getPredictions], ARC-020's cold start)
 *     runs off the SHIPPED `assets/bigrams/<lang>_bigrams.json` files, whose
 *     values are per-previous-word RANK scores, not probabilities (they sum
 *     well past 1 inside a group — see [StaticBigramSeed]).
 *
 * Feeding (2)'s rank scores into (1)'s interpolation would pin
 * [getContextMultiplier] at its 10× clamp for every listed pair and rewrite the
 * live tap ranking, so the assets deliberately do NOT reach the multiplier.
 */
class BigramModel private constructor() {
    companion object {
        private const val TAG = "BigramModel"

        // Smoothing parameters
        private const val LAMBDA = 0.95f // Interpolation weight for bigram
        private const val MIN_PROB = 0.0001f // Minimum probability for unseen words

        /** Default seed size when a caller does not state one. */
        const val DEFAULT_SEED_RESULTS = 5

        /** Shipped static bigram asset for a language, e.g. `bigrams/en_bigrams.json`. */
        @JvmStatic
        fun assetNameFor(language: String): String = "bigrams/${language}_bigrams.json"

        /**
         * Background loader for the static seed assets.
         *
         * Mirrors [AsyncDictionaryLoader]'s single-thread, below-normal-priority
         * executor: the seed is never needed synchronously (an unloaded language
         * simply falls back to the hardcoded pairs), so it must never contend
         * with, or block, the prediction path. Daemon so it cannot hold the
         * process alive.
         */
        private val SEED_LOADER: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "BigramSeedLoader").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
            }
        }

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

    // Current active language for the SCORING tables. Rewritten to "en" by
    // [setLanguage] when the requested language has no hardcoded table, because
    // the multiplier must always have a unigram denominator to divide by.
    private var currentLanguage: String = "en" // Default to English

    /**
     * Active language for the STATIC SEED, recorded verbatim by [setLanguage].
     *
     * Deliberately NOT folded into [currentLanguage]: the seed's language
     * coverage is the six shipped assets (de/en/es/fr/it/pt), which is a
     * superset of the four hardcoded tables. Falling `it`/`pt` back to "en" —
     * as the multiplier must — would offer English continuations while the user
     * types Italian.
     */
    @Volatile
    private var seedLanguage: String = "en"

    /**
     * language → active seed index (ARC-010). Seeded at construction with the
     * hardcoded pairs so the seed works BEFORE any asset load, and overwritten
     * with the asset-merged index once [loadStaticContinuations] succeeds. A
     * failed or absent asset therefore leaves the hardcoded index in place as
     * the permanent fallback.
     */
    private val seedIndexes = ConcurrentHashMap<String, StaticBigramSeed.Index>()

    /** Languages whose asset load has been attempted (success or failure). */
    private val seedLoadAttempted: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        initializeLanguageModels()
        initializeSeedFallbacks()
    }

    /**
     * Pre-load state for the static seed: an index over the hardcoded pairs
     * alone. Superseded per language by the shipped asset when it loads.
     */
    private fun initializeSeedFallbacks() {
        for ((language, pairs) in languageBigramProbs) {
            seedIndexes[language] = StaticBigramSeed.build(emptyMap(), pairs)
        }
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
        // The seed follows the requested language exactly — its asset coverage
        // (6 languages) is wider than the hardcoded tables' (4), so the "fall
        // back to English" rule below must not reach it.
        seedLanguage = language
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
     * Load the shipped static bigram asset for [language] into the seed index
     * (ARC-010 — this replaces the never-called `loadFromFile`, whose
     * whitespace-delimited plain-text parser did not match the JSON files that
     * have shipped since 2025-11).
     *
     * Blocking asset I/O — call from [loadStaticContinuationsAsync], not the
     * main thread. Idempotent and attempt-once: a language whose asset is
     * missing or malformed keeps its hardcoded pre-load index forever rather
     * than re-reading a file that will not appear.
     *
     * The asset does NOT reach the scoring tables ([languageBigramProbs]); see
     * the class doc for why the two scales must not mix.
     *
     * @return true when the asset was parsed and installed
     */
    fun loadStaticContinuations(context: Context, language: String): Boolean {
        if (!seedLoadAttempted.add(language)) {
            // Already attempted; a successful load left its index in place.
            return seedIndexes.containsKey(language)
        }

        val asset = assetNameFor(language)
        val json = try {
            context.assets.open(asset).use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            Log.d(TAG, "No static bigram asset for $language ($asset); keeping hardcoded pairs")
            return false
        }

        val parsed = try {
            StaticBigramSeed.parseAsset(json)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Malformed static bigram asset $asset; keeping hardcoded pairs", e)
            return false
        }
        if (parsed.isEmpty()) {
            Log.w(TAG, "Static bigram asset $asset held no usable pairs; keeping hardcoded pairs")
            return false
        }

        // Asset wins on conflict, hardcoded pairs fill the gaps (ARC-010 merge policy).
        val index = StaticBigramSeed.build(parsed, languageBigramProbs[language] ?: emptyMap())
        seedIndexes[language] = index
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(
                TAG,
                "Static bigram seed for $language: ${index.pairCount} pairs over " +
                    "${index.prevWordCount} previous words (asset ${parsed.size})"
            )
        }
        return true
    }

    /**
     * Queue [loadStaticContinuations] on the shared background loader.
     *
     * Returns immediately. Until the load lands, [getPredictions] serves the
     * hardcoded pairs, so there is no loading state for callers to observe.
     */
    fun loadStaticContinuationsAsync(context: Context, language: String) {
        if (seedLoadAttempted.contains(language)) return
        val appContext = context.applicationContext ?: context
        SEED_LOADER.execute { loadStaticContinuations(appContext, language) }
    }

    /** True once [language]'s shipped asset has been parsed and installed. */
    fun isStaticSeedLoaded(language: String): Boolean =
        seedLoadAttempted.contains(language) && seedIndexes.containsKey(language)

    /**
     * Static next-word seed (ARC-020): the most common continuations of the last
     * word of [context], best first.
     *
     * Cold-start data ONLY — this is shipped, non-personal content, and it is
     * the caller's job to run it inside the next-word gate (see
     * `WordPredictor.getStaticNextWordSeed`). Returns an empty list for a
     * language with neither an asset nor a hardcoded table, and for an unknown
     * previous word.
     *
     * @param context the preceding text; only its last whitespace-separated
     *   token is used, so both `"the"` and `"i want the"` resolve to `the`
     * @param maxResults hard cap on the returned continuations
     */
    fun getPredictions(
        context: String,
        maxResults: Int = DEFAULT_SEED_RESULTS
    ): List<StaticBigramSeed.Continuation> {
        if (maxResults <= 0) return emptyList()
        val prevWord = context.trim().substringAfterLast(' ').trim().lowercase()
        if (prevWord.isEmpty()) return emptyList()
        val index = seedIndexes[seedLanguage] ?: return emptyList()
        return index.top(prevWord, maxResults)
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
            java.util.Locale.ROOT,
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
