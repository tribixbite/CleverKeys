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
 *
 * ## The static context LM supersedes both, per language (2026-09-26)
 *
 * When `assets/lm/<lang>.cklm` exists ([StaticContextLm], built by
 * `scripts/build_static_lm.py` from the Leipzig + Tatoeba corpora), it replaces
 * BOTH products for that language once loaded, and this class becomes an adapter:
 *
 *  - [getContextMultiplier] = `clamp(P(w|prev) / P(w), 0.1, 10)` — the same ratio
 *    and clamp the hardcoded tables produced, now from real corpus statistics
 *    (an unlisted word gets the previous word's backoff ratio, slightly below 1);
 *  - [getPredictions] serves [StaticContextLm.top], with the continuation's
 *    conditional probability as its rank.
 *
 * Both lookups resolve a REPLACE contraction key to its display form
 * ([StaticContextLm.withReplaceAliases], installed at load from the language's
 * REPLACE bucket): the tap candidate `dont` scores as the `don't` the bar shows.
 *
 * Languages without an LM asset keep the hardcoded tables and the JSON seed
 * unchanged. The LM is keyed by the REQUESTED language ([seedLanguage]), never the
 * English-fallback [currentLanguage], so Italian typing never reads the English LM.
 */
class BigramModel internal constructor() { // internal: a fresh instance per pure-JVM test
    companion object {
        private const val TAG = "BigramModel"

        // Smoothing parameters
        private const val LAMBDA = 0.95f // Interpolation weight for bigram
        private const val MIN_PROB = 0.0001f // Minimum probability for unseen words

        /** Clamp on [getContextMultiplier] — shared by the LM and the hardcoded tables. */
        const val MIN_CONTEXT_MULTIPLIER = 0.1f
        const val MAX_CONTEXT_MULTIPLIER = 10.0f

        /** Gap-filler rank as a fraction of the lowest LM rank (see [getPredictions]). */
        private const val GAP_FILL_DECAY = 0.5f

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

    /**
     * language → loaded static context LM. Absent until [loadStaticContinuations]
     * installs it; a language without an asset never gets one and keeps the
     * hardcoded/JSON path forever. Primitive-array backed, ~1 MB for en.
     */
    private val staticLms = ConcurrentHashMap<String, StaticContextLm>()

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
            return staticLms.containsKey(language) || seedIndexes.containsKey(language)
        }

        // The static context LM supersedes the hardcoded multiplier table and
        // LEADS the next-word seed for its language (class doc). The curated JSON
        // seed below still loads: it fills the seed only where the LM lists fewer
        // continuations than asked for (see getPredictions).
        val lmLoaded = loadStaticLm(context, language)

        val asset = assetNameFor(language)
        val json = try {
            context.assets.open(asset).use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            Log.d(TAG, "No static bigram asset for $language ($asset); keeping hardcoded pairs")
            return lmLoaded
        }

        val parsed = try {
            StaticBigramSeed.parseAsset(json)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Malformed static bigram asset $asset; keeping hardcoded pairs", e)
            return lmLoaded
        }
        if (parsed.isEmpty()) {
            Log.w(TAG, "Static bigram asset $asset held no usable pairs; keeping hardcoded pairs")
            return lmLoaded
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
     * Read and parse `assets/lm/<language>.cklm` ([StaticContextLm.assetNameFor]).
     * Blocking — runs on [SEED_LOADER] via [loadStaticContinuations], attempt-once
     * like the seed. A missing asset is the normal case for most languages; a
     * malformed one is logged and ignored (the hardcoded path stays in charge).
     *
     * @return true when an LM was installed for [language]
     */
    private fun loadStaticLm(context: Context, language: String): Boolean {
        val asset = StaticContextLm.assetNameFor(language)
        val bytes = try {
            context.assets.open(asset).use { it.readBytes() }
        } catch (e: IOException) {
            return false
        }
        val started = System.nanoTime()
        val parsed = try {
            StaticContextLm.parse(bytes)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Malformed static context LM $asset; keeping the built-in tables", e)
            return false
        }
        val lm = parsed.withReplaceAliases(replaceAliasesFor(context, language))
        installStaticLm(language, lm)
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(
                TAG,
                "Static context LM for $language: ${lm.pairCount} pairs over ${lm.prevCount} " +
                    "previous words, ${lm.aliasCount} contraction aliases, " +
                    "${lm.retainedBytes() / 1024} KiB, loaded in " +
                    "${(System.nanoTime() - started) / 1_000_000} ms"
            )
        }
        return true
    }

    /**
     * [language]'s REPLACE contraction bucket (apostrophe-free key → display form) for
     * [StaticContextLm.withReplaceAliases] — the 2026-09-29 lookup fix: the LM names `don't`, the
     * tap candidates are the dictionary key `dont`.
     *
     * Read through `ContractionManager.loadSwipeDisplayMappings`, the ONE-language load (English:
     * the pairing-reclassified base + `contractions_en.json`; others: the language's REPLACE file,
     * or an installed pack's file, which wins outright). That keeps a single model of "which keys
     * the bar shows as their display form" instead of a third copy of the English rules
     * (contraction-system skill §3). REPLACE only: `getNonPairedMapping` is null for a PAIRED
     * base, so `well`/`lune` never alias. Deliberately NOT the merged typing map, whose
     * cross-language demotions depend on the secondary language — the LM is per-language.
     *
     * Runs on [SEED_LOADER] with the LM load (attempt-once); the manager is discarded afterwards.
     * A failure only costs the aliases, never the LM.
     */
    private fun replaceAliasesFor(context: Context, language: String): Map<String, String> = try {
        val contractions = ContractionManager(context)
        contractions.loadSwipeDisplayMappings(language)
        val out = HashMap<String, String>()
        for (key in contractions.getAliasKeys()) {
            contractions.getNonPairedMapping(key)?.let { out[key] = it }
        }
        out
    } catch (e: RuntimeException) {
        Log.w(TAG, "Contraction aliases for the $language LM unavailable; keys score by backoff", e)
        emptyMap()
    }

    /**
     * Install a parsed LM for [language] (the asset loader's last step; also the
     * pure-JVM test seam, since asset I/O needs an Android `Context`).
     */
    internal fun installStaticLm(language: String, lm: StaticContextLm) {
        staticLms[language] = lm
    }

    /** The installed static context LM for [language], or null. */
    internal fun staticLmFor(language: String): StaticContextLm? = staticLms[language]

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
        staticLms.containsKey(language) ||
            (seedLoadAttempted.contains(language) && seedIndexes.containsKey(language))

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
        val index = seedIndexes[seedLanguage]
        staticLms[seedLanguage]?.let { lm ->
            // The conditional probability is the rank: comparable within one
            // previous word, which is all the seed's consumer compares.
            val fromLm = lm.top(prevWord, maxResults).map {
                StaticBigramSeed.Continuation(it.word, it.probability)
            }
            if (fromLm.size >= maxResults || index == null) return fromLm
            // Curated gap fillers (e.g. "good morning", which the corpus ranks
            // below its top 20) — only into slots the LM left empty, ranked
            // strictly below every LM entry so the order stays data-first.
            val floor = fromLm.lastOrNull()?.rank ?: 1f
            val fill = index.top(prevWord, maxResults)
                .filter { c -> fromLm.none { it.word == c.word } }
                .mapIndexed { i, c -> StaticBigramSeed.Continuation(c.word, floor * GAP_FILL_DECAY / (i + 1)) }
            return (fromLm + fill).take(maxResults)
        }
        if (index == null) return emptyList()
        return index.top(prevWord, maxResults)
    }

    /**
     * Get the probability of a word given the previous word(s)
     * Uses linear interpolation between bigram and unigram probabilities
     */
    fun getContextualProbability(word: String?, context: List<String>?): Float =
        contextualProbability(currentLanguage, word, context)

    /**
     * [getContextualProbability] over [language]'s hardcoded tables (English when it has none —
     * the same fallback [setLanguage] applies to [currentLanguage]).
     */
    private fun contextualProbability(language: String, word: String?, context: List<String>?): Float {
        if (word.isNullOrEmpty()) {
            return MIN_PROB
        }

        val normalizedWord = word.lowercase()

        // Get language-specific probability maps
        var bigramProbs = languageBigramProbs[language]
        var unigramProbs = languageUnigramProbs[language]

        // Fallback to English if the language has no table
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

        // Static context LM for the requested language: P(w|prev)/P(w), same clamp.
        staticLms[seedLanguage]?.let { lm ->
            return lm.contextRatio(context.last(), word)
                .coerceIn(MIN_CONTEXT_MULTIPLIER, MAX_CONTEXT_MULTIPLIER)
        }

        return hardcodedContextMultiplier(currentLanguage, word, context)
    }

    /**
     * The pre-LM multiplier: [language]'s hardcoded tables (English when it has none), ratio of
     * contextual to base probability, clamped. What [getContextMultiplier] returns for a language
     * with no `lm/<language>.cklm`; also the S1 eval's `legacy` arm (`StaticLmTapEvalTest`), so
     * the baseline it compares against is this code, not a copy of it.
     */
    internal fun hardcodedContextMultiplier(language: String, word: String, context: List<String>?): Float {
        if (context.isNullOrEmpty()) return 1.0f
        // Get language-specific unigram probabilities
        var unigramProbs = languageUnigramProbs[language]
        if (unigramProbs == null || languageBigramProbs[language] == null) {
            unigramProbs = languageUnigramProbs["en"] // Fallback to English
        }

        val contextProb = contextualProbability(language, word, context)
        val baseProb = unigramProbs?.get(word.lowercase()) ?: MIN_PROB

        // Return ratio of contextual to base probability
        // This gives a boost when context makes the word more likely
        val multiplier = contextProb / baseProb

        // Cap the multiplier to avoid extreme values
        return min(max(multiplier, MIN_CONTEXT_MULTIPLIER), MAX_CONTEXT_MULTIPLIER)
    }

    /**
     * Every word [hardcodedContextMultiplier] can give a non-neutral value for [language]
     * (its table's pair words and unigrams, English's when it has none) — eval support.
     */
    internal fun hardcodedTableWords(language: String): Set<String> {
        val lang = if (languageBigramProbs.containsKey(language)) language else "en"
        val out = HashSet<String>()
        languageBigramProbs[lang]?.keys?.forEach { k -> out.addAll(k.split('|')) }
        languageUnigramProbs[lang]?.keys?.let(out::addAll)
        return out
    }

    /** [language]'s hardcoded `prev|next` pairs (empty when it has none) — the seed's pre-asset fallback. */
    internal fun hardcodedPairs(language: String): Map<String, Float> =
        languageBigramProbs[language]?.toMap() ?: emptyMap()

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
