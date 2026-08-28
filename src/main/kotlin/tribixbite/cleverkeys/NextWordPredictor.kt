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
    /**
     * Maximum candidates surfaced in the bar.
     *
     * DECLINED KNOB (2026-08-28, ledger ARC-026 — audit §4.3 proposed a 1–5
     * `next_word_max_suggestions` preference). Kept a constant on purpose: the
     * 2026-08-26 next-word audit deliberately kept the user-facing surface minimal, and
     * 3 is what the bar can show without displacing the prefix predictions the user is
     * more likely to want. The interesting failure mode is noise, which the confidence
     * floors below control far better than a count does.
     */
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
     * store, so it must go dark with the master off), the **context-LM pref**
     * (audit 2026-08-26 — see below), the per-field incognito flag (M5 —
     * `IME_FLAG_NO_PERSONALIZED_LEARNING` fields must not surface personalized
     * predictions either), password mode, special prompts (autocorrect-undo /
     * add-to-dictionary), Termux/terminal fields, and requires non-empty
     * committed context.
     *
     * ## Why the context-LM pref is checked HERE and not only downstream
     *
     * Next-word candidates come exclusively from the learned n-gram stores, and
     * `WordPredictor.getNextWordCandidates` already fails closed via
     * `LearningGate.canUseLearnedContext` when `context_aware_predictions_enabled`
     * is off. So omitting it here could not surface a candidate — but it left
     * the settings model incoherent: the Settings UI hides this feature's
     * toggle when the context LM is off, so a user could carry a stale
     * `next_word_prediction_enabled = true` with NO visible control for it, and
     * this gate — the one place that documents itself as "every guard" — would
     * still say yes. Worse, the cursor-park path uses the CHEAP gates to decide
     * whether it may READ the editor text at all; without this parameter it
     * read text in a state where no candidate could ever be shown. The gate
     * must be the single honest answer, not "true, but a downstream layer will
     * save you".
     *
     * @param contextAwareEnabled the `context_aware_predictions_enabled` pref —
     *   the learned context LM this feature draws from. Required, no default:
     *   every caller must state it.
     * @param fieldAllowsPersonalizedLearning false when the active editor set
     *   `IME_FLAG_NO_PERSONALIZED_LEARNING` (see
     *   [LearningGate.fieldAllowsPersonalizedLearning])
     */
    fun shouldShow(
        featureEnabled: Boolean,
        onDeviceLearningEnabled: Boolean,
        contextAwareEnabled: Boolean,
        wordPredictionEnabled: Boolean,
        isPasswordMode: Boolean,
        specialPromptActive: Boolean,
        inTermuxApp: Boolean,
        hasContext: Boolean,
        fieldAllowsPersonalizedLearning: Boolean = true
    ): Boolean {
        return featureEnabled &&
            onDeviceLearningEnabled &&
            contextAwareEnabled &&
            fieldAllowsPersonalizedLearning &&
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
     * L5 (review 2026-08-06 — resolved): derive next-word context from the
     * EDITOR text preceding a parked cursor, so parking in text typed earlier
     * (or pre-existing in the field) predicts from the words actually before
     * the cursor instead of this session's committed-word history.
     *
     * Rules (kept consistent with the learn path):
     * - Only the segment AFTER the last sentence-final punctuation (`.` `?` `!`)
     *   or line break is considered — mirrors `WordPredictor.onSentenceBoundary`'s
     *   contract that learned context never spans a sentence boundary. Parking
     *   right after a sentence end therefore yields an EMPTY context (show
     *   nothing) rather than predicting across the boundary.
     * - Tokens are runs of letters plus word-internal apostrophes/hyphens
     *   ("don't", "co-op"), lowercased to match the stores' normalized keys.
     *   Digit/symbol runs separate tokens, matching the typing tracker (which
     *   only accumulates letters into words).
     * - The trailing [maxWords] tokens are returned oldest-first. The caller
     *   guarantees the cursor is not inside a partial word (the cursor-park
     *   branch only fires with an empty prefix), so the token touching the
     *   cursor is complete.
     *
     * @param textBeforeCursor editor text immediately before the cursor
     * @param maxWords context window cap (default [LearningGate.CONTEXT_WINDOW])
     * @return trailing complete words, oldest first; empty when the cursor is
     *   parked at a sentence start or the field is empty
     */
    fun contextFromEditorText(
        textBeforeCursor: CharSequence,
        maxWords: Int = LearningGate.CONTEXT_WINDOW
    ): List<String> {
        if (maxWords <= 0) return emptyList()

        // Start of the segment after the last sentence boundary.
        var segmentStart = 0
        for (i in textBeforeCursor.indices) {
            val c = textBeforeCursor[i]
            if (c == '.' || c == '?' || c == '!' || c == '\n') segmentStart = i + 1
        }

        val tokens = ArrayList<String>()
        val current = StringBuilder()
        fun flushToken() {
            // Trim edge apostrophes/hyphens ('quoted' → quoted); keep internal ones.
            val token = current.toString().trim('\'', '-')
            if (token.isNotEmpty() && token.any { it.isLetter() }) tokens.add(token.lowercase())
            current.setLength(0)
        }
        for (i in segmentStart until textBeforeCursor.length) {
            val c = textBeforeCursor[i]
            if (c.isLetter() || c == '\'' || c == '-') current.append(c) else flushToken()
        }
        flushToken()

        return if (tokens.size > maxWords) tokens.takeLast(maxWords) else tokens
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
