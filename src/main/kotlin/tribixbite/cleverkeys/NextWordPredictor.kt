package tribixbite.cleverkeys

import tribixbite.cleverkeys.contextaware.BigramEntry

/**
 * Pure next-word candidate generation + gating (audit 2026-08-06 §4).
 *
 * Turns the learned context LM's ranked bigram continuations
 * ([tribixbite.cleverkeys.contextaware.ContextModel.getNextWordCandidates])
 * into suggestion-bar candidates. Deliberately the same signal family as
 * `WordPredictor.calculateUnifiedScore` minus the prefix term: learned
 * conditional probability × personalization multiplier.
 *
 * Everything here is pure JVM (no Android deps) so the filters, floors, and
 * gating rules are unit-testable; `SuggestionHandler.maybeShowNextWordPredictions`
 * owns the impure wiring (threads, bar posting, config reads).
 */
object NextWordPredictor {
    /** Maximum candidates surfaced in the bar. */
    const val MAX_SUGGESTIONS = 3

    /**
     * Confidence floor (§4.2-6): an empty next-word bar must be a common,
     * acceptable outcome — show nothing rather than noise.
     */
    const val MIN_LEARNED_FREQUENCY = 2
    const val MIN_LEARNED_PROBABILITY = 0.05f

    /** A ranked next-word candidate ready for the suggestion bar. */
    data class Candidate(val word: String, val score: Int)

    /**
     * Gate: should next-word prediction run at all right now?
     *
     * Inherits every existing suggestion-bar guard (§4.4): the feature pref,
     * password mode, special prompts (autocorrect-undo / add-to-dictionary),
     * Termux/terminal fields, and requires non-empty committed context.
     */
    fun shouldShow(
        featureEnabled: Boolean,
        wordPredictionEnabled: Boolean,
        isPasswordMode: Boolean,
        specialPromptActive: Boolean,
        inTermuxApp: Boolean,
        hasContext: Boolean
    ): Boolean {
        return featureEnabled &&
            wordPredictionEnabled &&
            !isPasswordMode &&
            !specialPromptActive &&
            !inTermuxApp &&
            hasContext
    }

    /**
     * Generate ranked next-word candidates from learned bigram continuations.
     *
     * Filters (§4.2-4):
     * - confidence floor: learned frequency ≥ [MIN_LEARNED_FREQUENCY] AND
     *   conditional probability ≥ [MIN_LEARNED_PROBABILITY]
     * - self-repetition: drop the just-committed word
     * - [isWordAllowed] hook: dictionary-or-user-vocabulary membership AND not
     *   disabled in Dictionary Manager (blocks typo'd garbage the bigram store
     *   may have absorbed — recordSequence learns whatever was committed)
     * - dedup (first occurrence wins; input is probability-ranked)
     *
     * Ranking score = probability × personalization multiplier
     * (`1 + boost/4`, the same conversion as WordPredictor.calculateUnifiedScore),
     * scaled to an int for the bar's parallel score list.
     *
     * @param learned probability-ranked continuations for the last context word
     * @param lastCommittedWord the word just committed (self-repetition filter)
     * @param personalizationBoost word → 0..6 boost (0 when disabled/unknown)
     * @param isWordAllowed word → allowed in suggestions for the active language
     * @param maxSuggestions bar slot cap
     */
    fun generate(
        learned: List<BigramEntry>,
        lastCommittedWord: String?,
        personalizationBoost: (String) -> Float,
        isWordAllowed: (String) -> Boolean,
        maxSuggestions: Int = MAX_SUGGESTIONS
    ): List<Candidate> {
        if (learned.isEmpty() || maxSuggestions <= 0) return emptyList()

        val lastLower = lastCommittedWord?.lowercase()?.trim()
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>(maxSuggestions)

        for (entry in learned) {
            if (out.size >= maxSuggestions) break
            val word = entry.word2
            if (word.isEmpty()) continue
            if (entry.frequency < MIN_LEARNED_FREQUENCY) continue
            if (entry.probability < MIN_LEARNED_PROBABILITY) continue
            if (word == lastLower) continue // self-repetition
            if (!seen.add(word)) continue
            if (!isWordAllowed(word)) continue

            // Same personalization conversion as calculateUnifiedScore (:1771)
            val personalizationMultiplier = 1.0f + (personalizationBoost(word) / 4.0f)
            val score = (entry.probability * personalizationMultiplier * 1000f).toInt()
            out.add(Candidate(word, score))
        }

        // Personalization can reorder within the surfaced set
        out.sortByDescending { it.score }
        return out
    }
}
