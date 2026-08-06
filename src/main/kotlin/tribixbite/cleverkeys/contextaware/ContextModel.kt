package tribixbite.cleverkeys.contextaware

import android.content.Context

/**
 * A learned next-word continuation with the raw statistics needed for
 * confidence floors, independent of whether it came from the trigram or the
 * bigram model (audit 2026-08-06 §4.2 backoff).
 *
 * @property word the predicted continuation
 * @property frequency how many times this continuation was observed in its model
 * @property probability conditional probability within its model
 * @property fromTrigram true when the sharper two-word context produced it
 */
data class ContextContinuation(
    val word: String,
    val frequency: Int,
    val probability: Float,
    val fromTrigram: Boolean = false
)

/**
 * Context-aware N-gram model for prediction enhancement.
 *
 * A thin, language-aware view over the process-wide [BigramStore] and
 * [TrigramStore] singletons (2026-08-06 persistence fix; trigram activation
 * per audit §1.3-D/§4.2). Each `WordPredictor` owns one ContextModel and
 * keeps [language] in sync with its active language; all reads/writes are
 * isolated to that language's learned data.
 *
 * Usage Example:
 * ```kotlin
 * val contextModel = ContextModel(context, "en")
 * contextModel.recordSequence(listOf("I", "want", "to"))  // Learn from typing
 *
 * // Later, when predicting after "want to"
 * val boost = contextModel.getContextBoost(
 *     candidateWord = "go",
 *     previousWords = listOf("I", "want", "to")
 * )
 * // boost might be 2.5x for likely phrase "I want to go"
 * ```
 *
 * Architecture:
 * - BigramStore: P(word2 | word1) — singleton, language-keyed, debounced persistence
 * - TrigramStore: P(word3 | word1, word2) — same discipline (activated 2026-08-06)
 * - Hybrid scoring: prefer trigrams when the two-word context has data, back
 *   off to bigrams otherwise
 *
 * The test-only two-store constructor passes `trigramStore = null`, which
 * exercises the pure bigram backoff path; production always wires both stores.
 */
class ContextModel internal constructor(
    private val bigramStore: BigramStore,
    private val trigramStore: TrigramStore?,
    language: String
) {
    companion object {
        // Boost multipliers for context probabilities
        private const val MAX_BOOST = 5.0f  // Maximum 5x boost for very likely words
        private const val MIN_BOOST = 1.0f  // No boost (neutral)
        private const val BOOST_EXPONENT = 2.0f  // Non-linear boost: boost = (1 + prob)^2

        // Context window sizes
        private const val BIGRAM_WINDOW = 1  // Use 1 previous word
        private const val TRIGRAM_WINDOW = 2  // Use 2 previous words

        // Minimum probability thresholds
        private const val MIN_BIGRAM_PROB = 0.01f  // 1% minimum
        private const val MIN_TRIGRAM_PROB = 0.001f  // 0.1% minimum
    }

    /**
     * Test-compat constructor: bigram-only view (trigram path disabled → pure
     * bigram backoff). Production uses the [Context]-based constructor below.
     */
    internal constructor(bigramStore: BigramStore, language: String) :
        this(bigramStore, null, language)

    /**
     * Production constructor: language-keyed view over the singleton stores.
     */
    constructor(context: Context, language: String = "en") :
        this(BigramStore.getInstance(context), TrigramStore.getInstance(context), language)

    /**
     * Active language for this model's view of the store. WordPredictor updates
     * this on language switch / auto-detection so learning and lookups stay
     * language-isolated.
     */
    @Volatile
    var language: String = BigramStore.normalizeLanguage(language)
        set(value) {
            field = BigramStore.normalizeLanguage(value)
        }

    /**
     * Record a word sequence from user typing.
     * Extracts and records all bigrams AND trigrams into the active language's
     * stores. Persistence is handled by the stores' debounced write-back — call
     * [save] at lifecycle boundaries to checkpoint explicitly.
     *
     * PRIVACY: the only production caller is the gated learning funnel
     * (`LearningGate.learnCommittedWord` via `WordPredictor.addWordToContext`),
     * so the master `on_device_learning_enabled` gate and the per-feature
     * context-aware gate are both enforced BEFORE this method runs.
     *
     * Example: ["I", "want", "to", "go"] records:
     * - Bigrams: (I, want), (want, to), (to, go)
     * - Trigrams: (I, want, to), (want, to, go)
     *
     * @param words List of words in order (should be normalized/lowercase)
     */
    fun recordSequence(words: List<String>) {
        if (words.size < 2) return  // Need at least 2 words for bigrams

        // Record bigrams
        for (i in 0 until words.size - 1) {
            bigramStore.recordBigram(language, words[i], words[i + 1])
        }

        // Record trigrams (audit §1.3-D activation — the learn path already
        // supplied 4-word windows, so the extra order costs no extra plumbing)
        if (trigramStore != null && words.size >= 3) {
            for (i in 0 until words.size - 2) {
                trigramStore.recordTrigram(language, words[i], words[i + 1], words[i + 2])
            }
        }
    }

    /**
     * Record ONLY the newest n-grams ending at the just-committed word: the
     * final bigram (last two words) and the final trigram (last three words).
     *
     * M3 (review 2026-08-06): this is THE per-commit hot-path entry
     * (`LearningGate.learnCommittedWord` via `WordPredictor.addWordToContext`).
     * The previous behavior replayed [recordSequence] over the whole rolling
     * window on EVERY commit, so each earlier pair was re-recorded once per
     * subsequent commit — a single typing of "the cat sat" yielded
     * freq(the→cat)=2, silently defeating the "seen ≥2×" confidence floors
     * (`BigramStore.DEFAULT_MIN_FREQUENCY`, `NextWordPredictor.MIN_LEARNED_FREQUENCY`).
     * With newest-only recording, frequency == number of times the user
     * actually typed the pair.
     *
     * [recordSequence] remains for bulk paths (import replay, tests) where the
     * whole sequence is genuinely new.
     *
     * @param words rolling committed-word window, most recent last (only the
     *   trailing 3 words are consulted)
     */
    fun recordCommit(words: List<String>) {
        val n = words.size
        if (n < 2) return
        bigramStore.recordBigram(language, words[n - 2], words[n - 1])
        if (trigramStore != null && n >= 3) {
            trigramStore.recordTrigram(language, words[n - 3], words[n - 2], words[n - 1])
        }
    }

    /**
     * Inverse of [recordCommit] (autocorrect-undo rollback, 2026-08-06): decrement
     * the NEWEST bigram/trigram ending at the just-rejected word. Called with the
     * SAME window [recordCommit] consumed, so exactly the n-grams that commit
     * recorded are un-recorded. Unknown n-grams no-op (safe when the original
     * record was gate-suppressed).
     *
     * @param words rolling committed-word window, most recent (the rejected
     *   word) last — only the trailing 3 words are consulted
     */
    fun rollbackCommit(words: List<String>) {
        val n = words.size
        if (n < 2) return
        bigramStore.unrecordBigram(language, words[n - 2], words[n - 1])
        if (trigramStore != null && n >= 3) {
            trigramStore.unrecordTrigram(language, words[n - 3], words[n - 2], words[n - 1])
        }
    }

    /**
     * Get context-based boost for a candidate word.
     *
     * Calculates a multiplier (≥1.0) based on how likely the candidate is given previous words.
     * Higher boost for more likely words in context.
     *
     * Algorithm:
     * 1. Try trigram if we have 2+ previous words (future)
     * 2. Fall back to bigram if we have 1+ previous word
     * 3. Return 1.0 (no boost) if no context available
     *
     * Boost formula: boost = (1 + probability)^BOOST_EXPONENT
     * - P=0.0 → boost=1.0 (no change)
     * - P=0.1 → boost=1.21
     * - P=0.5 → boost=2.25
     * - P=0.9 → boost=3.61
     * - P=1.0 → boost=4.0 (max realistic)
     *
     * @param candidateWord The word being predicted
     * @param previousWords List of previous words (most recent last)
     * @return Boost multiplier (1.0 to MAX_BOOST)
     */
    fun getContextBoost(candidateWord: String, previousWords: List<String>): Float {
        if (previousWords.isEmpty() || candidateWord.isEmpty()) {
            return MIN_BOOST
        }

        // Prefer the trigram model when two words of context are available —
        // P(word | w1, w2) is a sharper signal than P(word | w2) (audit §4.2).
        // L2 (review 2026-08-06): use the CONFIDENT probability (0 below the
        // store's min-frequency floor) — a once-seen n-gram must not hand a
        // candidate the near-max boost just because its conditional probability
        // is 1.0 on a single observation.
        if (trigramStore != null && previousWords.size >= TRIGRAM_WINDOW) {
            val w1 = previousWords[previousWords.size - 2]
            val w2 = previousWords.last()
            val trigramProb = trigramStore.getConfidentProbability(language, w1, w2, candidateWord)
            if (trigramProb >= MIN_TRIGRAM_PROB) {
                return calculateBoost(trigramProb)
            }
        }

        // Back off to bigram with most recent previous word
        val prevWord = previousWords.last()
        val bigramProb = bigramStore.getConfidentProbability(language, prevWord, candidateWord)

        if (bigramProb >= MIN_BIGRAM_PROB) {
            return calculateBoost(bigramProb)
        }

        return MIN_BOOST  // No context match
    }

    /**
     * Get top N predictions given context words.
     *
     * Returns candidate words ranked by context probability from the active
     * language's learned data. Used by next-word prediction (§4) and for showing
     * contextually relevant suggestions.
     *
     * @param previousWords List of previous words (most recent last)
     * @param maxResults Maximum number of predictions
     * @return List of candidate words with their context boost values
     */
    fun getTopPredictions(
        previousWords: List<String>,
        maxResults: Int = 10
    ): List<Pair<String, Float>> {
        return getNextWordCandidates(previousWords, maxResults).map { c ->
            c.word to calculateBoost(c.probability)
        }
    }

    /**
     * Get ranked next-word candidates with raw learned statistics
     * (frequency + conditional probability) for filtering/floors.
     *
     * Backoff order (audit §4.2): trigram continuations of the last TWO context
     * words first (sharper), then bigram continuations of the last word for the
     * remaining slots (deduplicated — a word surfaced by the trigram model keeps
     * its trigram statistics).
     */
    fun getNextWordCandidates(
        previousWords: List<String>,
        maxResults: Int = 10
    ): List<ContextContinuation> {
        if (previousWords.isEmpty()) return emptyList()

        val out = ArrayList<ContextContinuation>(maxResults)
        val seen = HashSet<String>()

        if (trigramStore != null && previousWords.size >= TRIGRAM_WINDOW) {
            val w1 = previousWords[previousWords.size - 2]
            val w2 = previousWords.last()
            for (entry in trigramStore.getPredictions(language, w1, w2, maxResults)) {
                if (seen.add(entry.word3)) {
                    out.add(ContextContinuation(entry.word3, entry.frequency, entry.probability, fromTrigram = true))
                }
            }
        }

        if (out.size < maxResults) {
            for (entry in bigramStore.getPredictions(language, previousWords.last(), maxResults)) {
                if (out.size >= maxResults) break
                if (seen.add(entry.word2)) {
                    out.add(ContextContinuation(entry.word2, entry.frequency, entry.probability, fromTrigram = false))
                }
            }
        }

        return out
    }

    /**
     * Check if context model has data for a given previous word.
     *
     * @param previousWord The context word to check
     * @return True if we have predictions for this word
     */
    fun hasContextFor(previousWord: String): Boolean {
        return bigramStore.getAllBigrams(language, previousWord).isNotEmpty()
    }

    /**
     * Get the raw probability (not boost) for a word pair.
     *
     * @param previousWord Previous word
     * @param candidateWord Predicted word
     * @return Probability (0.0 to 1.0)
     */
    fun getProbability(previousWord: String, candidateWord: String): Float {
        return bigramStore.getProbability(language, previousWord, candidateWord)
    }

    /**
     * Clear this language's context data (for testing or user reset).
     */
    fun clear() {
        bigramStore.clear(language)
        trigramStore?.clear(language)
    }

    /**
     * Checkpoint learned context data to persistent storage.
     *
     * NOT called per record — [recordSequence] only marks the store dirty and the
     * store's debounced persister writes back in the background. This method
     * requests an immediate asynchronous flush and is invoked from lifecycle
     * boundaries (input-view finish, predictor eviction, coordinator shutdown).
     * No-op when there are no unflushed changes.
     */
    fun save() {
        bigramStore.requestFlush()
        trigramStore?.requestFlush()
    }

    /**
     * Synchronous flush for tests and hard-teardown paths.
     */
    fun saveBlocking() {
        bigramStore.flush()
        trigramStore?.flush()
    }

    /**
     * Get statistics about the context model for the active language.
     */
    data class ContextStats(
        val bigramStats: BigramStore.BigramStats,
        val totalContextWords: Int,
        val averageBoostPotential: Float,
        val trigramCount: Int = 0
    )

    fun getStatistics(): ContextStats {
        val bigramStats = bigramStore.getStatistics(language)

        // Calculate average boost potential (average of all bigram probabilities)
        val allProbabilities = mutableListOf<Float>()
        for (word1 in bigramStats.topContextWords.map { it.first }) {
            val bigrams = bigramStore.getAllBigrams(language, word1)
            allProbabilities.addAll(bigrams.map { it.probability })
        }

        val avgProb = if (allProbabilities.isNotEmpty()) {
            allProbabilities.average().toFloat()
        } else 0f

        val avgBoost = calculateBoost(avgProb)

        return ContextStats(
            bigramStats = bigramStats,
            totalContextWords = bigramStats.uniqueContextWords,
            averageBoostPotential = avgBoost,
            trigramCount = trigramStore?.getTotalTrigramCount(language) ?: 0
        )
    }

    /**
     * Export the active language's context data as JSON.
     */
    fun exportToJson(): String {
        // NOTE: the backup payload format carries bigrams only
        // (`learned_bigrams_by_language`); trigrams re-learn quickly from typing
        // and are deliberately excluded to keep the format stable.
        return bigramStore.exportToJson(language)
    }

    /**
     * Import context data for the active language from JSON.
     */
    fun importFromJson(jsonString: String) {
        bigramStore.importFromJson(language, jsonString)
    }

    /**
     * Calculate boost multiplier from probability.
     *
     * Uses non-linear formula to amplify high probabilities:
     * boost = (1 + probability)^BOOST_EXPONENT
     *
     * Clamped between MIN_BOOST and MAX_BOOST.
     *
     * @param probability Value between 0.0 and 1.0
     * @return Boost multiplier between MIN_BOOST and MAX_BOOST
     */
    private fun calculateBoost(probability: Float): Float {
        if (probability <= 0f) return MIN_BOOST

        val rawBoost = Math.pow((1.0 + probability).toDouble(), BOOST_EXPONENT.toDouble()).toFloat()
        return rawBoost.coerceIn(MIN_BOOST, MAX_BOOST)
    }

    /**
     * Set minimum frequency threshold for bigrams.
     * Useful for filtering noise in production usage.
     *
     * @param minFrequency Minimum occurrence count (default: 2)
     */
    fun setMinimumFrequency(minFrequency: Int) {
        bigramStore.setMinimumFrequency(minFrequency)
    }
}
