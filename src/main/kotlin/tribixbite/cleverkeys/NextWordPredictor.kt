package tribixbite.cleverkeys

import tribixbite.cleverkeys.contextaware.ContextContinuation

/**
 * Pure next-word candidate generation + gating (audit 2026-08-06 §4).
 *
 * Turns the learned context LM's ranked continuations
 * ([tribixbite.cleverkeys.contextaware.ContextModel.getNextWordCandidates] —
 * trigram-preferred with bigram backoff since the §1.3-D activation)
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

    /** Maximum candidates APPENDED after swipe alternates (call-site 3, §4.4). */
    const val MAX_SWIPE_APPEND = 2

    /**
     * Confidence floor (§4.2-6): an empty next-word bar must be a common,
     * acceptable outcome — show nothing rather than noise.
     */
    const val MIN_LEARNED_FREQUENCY = 2
    const val MIN_LEARNED_PROBABILITY = 0.05f

    /** A ranked next-word candidate ready for the suggestion bar. */
    data class Candidate(
        val word: String,
        val score: Int,
        /** Learned statistics carried into the provenance meta (Task B). */
        val frequency: Int,
        val probability: Float,
        val fromTrigram: Boolean
    )

    /**
     * Gate: should next-word prediction run at all right now?
     *
     * Inherits every existing suggestion-bar guard (§4.4): the feature pref,
     * the MASTER on-device-learning gate (Task A — next-word reads the learned
     * store, so it must go dark with the master off), password mode, special
     * prompts (autocorrect-undo / add-to-dictionary), Termux/terminal fields,
     * and requires non-empty committed context.
     */
    fun shouldShow(
        featureEnabled: Boolean,
        onDeviceLearningEnabled: Boolean,
        wordPredictionEnabled: Boolean,
        isPasswordMode: Boolean,
        specialPromptActive: Boolean,
        inTermuxApp: Boolean,
        hasContext: Boolean
    ): Boolean {
        return featureEnabled &&
            onDeviceLearningEnabled &&
            wordPredictionEnabled &&
            !isPasswordMode &&
            !specialPromptActive &&
            !inTermuxApp &&
            hasContext
    }

    /**
     * Generate ranked next-word candidates from learned continuations.
     *
     * Filters (§4.2-4):
     * - confidence floor: learned frequency ≥ [MIN_LEARNED_FREQUENCY] AND
     *   conditional probability ≥ [MIN_LEARNED_PROBABILITY]
     * - self-repetition: drop the just-committed word
     * - [isWordAllowed] hook: dictionary-or-user-vocabulary membership AND not
     *   disabled in Dictionary Manager (blocks typo'd garbage the n-gram stores
     *   may have absorbed — recordSequence learns whatever was committed)
     * - dedup (first occurrence wins; input is probability-ranked)
     *
     * Ranking score = probability × personalization multiplier
     * (`1 + boost/4`, the same conversion as WordPredictor.calculateUnifiedScore),
     * scaled to an int for the bar's parallel score list.
     *
     * @param learned probability-ranked continuations for the current context
     * @param lastCommittedWord the word just committed (self-repetition filter)
     * @param personalizationBoost word → 0..6 boost (0 when disabled/unknown)
     * @param isWordAllowed word → allowed in suggestions for the active language
     * @param maxSuggestions bar slot cap
     */
    fun generate(
        learned: List<ContextContinuation>,
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
            val word = entry.word
            if (word.isEmpty()) continue
            if (entry.frequency < MIN_LEARNED_FREQUENCY) continue
            if (entry.probability < MIN_LEARNED_PROBABILITY) continue
            if (word == lastLower) continue // self-repetition
            if (!seen.add(word)) continue
            if (!isWordAllowed(word)) continue

            // Same personalization conversion as calculateUnifiedScore
            val personalizationMultiplier = 1.0f + (personalizationBoost(word) / 4.0f)
            val score = (entry.probability * personalizationMultiplier * 1000f).toInt()
            out.add(Candidate(word, score, entry.frequency, entry.probability, entry.fromTrigram))
        }

        // Personalization can reorder within the surfaced set
        out.sortByDescending { it.score }
        return out
    }

    /**
     * Provenance note for a next-word candidate (Task B): the learned
     * statistics behind the suggestion, e.g. `after "want to": seen 14×, 63%`.
     */
    fun provenanceNote(candidate: Candidate, contextWords: List<String>): String {
        val contextShown = if (candidate.fromTrigram && contextWords.size >= 2) {
            contextWords.takeLast(2).joinToString(" ")
        } else {
            contextWords.lastOrNull() ?: ""
        }
        val pct = (candidate.probability * 100).toInt()
        return "After “$contextShown”: seen ${candidate.frequency}×, $pct%"
    }
}
