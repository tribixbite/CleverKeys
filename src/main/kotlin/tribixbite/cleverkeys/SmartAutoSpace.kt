package tribixbite.cleverkeys

/**
 * SAS-1 (v1.5.0): smart auto-space around punctuation — shared pure decision logic.
 *
 * Feature A — leading-space suppression after opening punctuation:
 *   Consumed by SuggestionHandler.onSuggestionSelected and
 *   InputCoordinator's suggestion-commit path (`needsSpaceBefore`).
 *   When a swiped word / tapped suggestion is committed right after an
 *   opening bracket or quote, the leading auto-space must be skipped:
 *   `("` + swipe "word" → `(word` / `"word`, never `( word`.
 *
 * Feature B — automatic-trailing-space swallowing before closing punctuation:
 *   Consumed by KeyEventHandler.sendText (smart punctuation, v1.2.7).
 *   After a swipe/suggestion commit auto-added a trailing space, the very
 *   next typed closing punctuation deletes that space (`word.` not `word .`).
 *   Only the AUTOMATIC space may be swallowed — eligibility is verified at
 *   use against the actual editor text AND a cursor-position stamp captured
 *   at commit time (see PredictionContextTracker.markAutoSpacePending).
 *
 * This object is pure Kotlin (no Android deps) so it runs under runPureTests.
 */
object SmartAutoSpace {

    /** Characters that ALWAYS open a group — never valid as closers. */
    private val UNAMBIGUOUS_OPENERS = setOf('(', '[', '{', '“', '‘', '¿', '¡')

    /**
     * Closing punctuation that swallows a pending automatic trailing space.
     *
     * Straight double quote `"` is deliberately absent: it is ambiguous
     * (opening vs closing) and is disambiguated by the caller via quote-count
     * parity (KeyEventHandler.isClosingQuote). Straight apostrophe `'` IS
     * included: mid-word apostrophes (don't, it's) never reach the swallow
     * path (the char before the cursor is a letter, not the pending space),
     * so an apostrophe typed right after an auto-space is a possessive or
     * closing quote in virtually all real input (`kids ` + `'` → `kids'`).
     */
    private val CLOSING_PUNCTUATION =
        setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '”', '’', '…', '\'')

    /**
     * Feature A: is [prevChar] (the char immediately before the insertion
     * point) opening punctuation that should suppress the leading auto-space?
     *
     * Straight quotes `"` and `'` are ambiguous. Disambiguation by the char
     * BEFORE the quote ([charBeforePrev]):
     * - start of field, whitespace, or another opener → opening quote
     *   (`He said "` + swipe → `He said "word`)
     * - anything else (letter/digit/closer) → possessive or closing quote —
     *   keep the space (`kids'` + swipe → `kids' toys`, `said."` → `said." Next`)
     */
    fun isOpeningPunctuation(prevChar: Char, charBeforePrev: Char?): Boolean {
        if (prevChar in UNAMBIGUOUS_OPENERS) return true
        if (prevChar == '"' || prevChar == '\'') {
            return charBeforePrev == null ||
                charBeforePrev.isWhitespace() ||
                charBeforePrev in UNAMBIGUOUS_OPENERS
        }
        return false
    }

    /**
     * Feature A: should a leading space be inserted before the committed word,
     * given the character(s) before the insertion point? (Pref gating —
     * auto_space_before_suggestion, #151 sync-suppressed fields — is applied
     * by the callers BEFORE consulting this.)
     */
    fun needsLeadingSpace(prevChar: Char, charBeforePrev: Char?): Boolean =
        !prevChar.isWhitespace() && !isOpeningPunctuation(prevChar, charBeforePrev)

    /** Feature B: closing punctuation that attaches to the previous word. */
    fun isClosingPunctuation(c: Char): Boolean = c in CLOSING_PUNCTUATION

    /**
     * Feature B: may the pending automatic trailing space be swallowed?
     *
     * Verify-at-use, never trust-the-flag (the expectingSelectionUpdate-style
     * trust-the-flag approach caused cross-app leaks before — see CLAUDE.md):
     * - [autoSpacePending]: the transient flag set when the auto-space was committed
     * - [actualPrevChar]: the char actually before the cursor RIGHT NOW — must be
     *   exactly ' ' (a plain space; tabs/newlines/manual edits are never eaten)
     * - [stampedPosition]/[actualPosition]: cursor position expected right after
     *   the auto-space commit vs the cursor position now. A mismatch means the
     *   user moved the cursor — the space before the new position is NOT ours.
     *   Unknown positions (-1, editors without ExtractedText support) degrade
     *   to the legacy flag+space check rather than breaking those editors.
     */
    fun isSwallowEligible(
        autoSpacePending: Boolean,
        stampedPosition: Int,
        actualPrevChar: Char?,
        actualPosition: Int
    ): Boolean {
        if (!autoSpacePending) return false
        if (actualPrevChar != ' ') return false
        // Position stamp check — only enforced when both sides are known
        return stampedPosition < 0 || actualPosition < 0 || actualPosition == stampedPosition
    }

    /**
     * Trailing-space decision (#78/#82) — the outcome of committing a chosen
     * suggestion. This is the SINGLE source of truth for the trailing-space
     * branch that SuggestionHandler.onSuggestionSelected consumes.
     *
     * Note: the former Termux-app override was removed in #78 — Termux users who
     * want no trailing space disable [autoSpaceAfterEnabled] instead. Do NOT
     * re-add a termux branch here without re-adding it to SuggestionHandler.
     */
    enum class TrailingSpaceMode {
        /** Branch 1: user turned off auto-space (tap only — swipe bypasses it). */
        NO_SPACE_USER_DISABLED,

        /** Branch 2: a space already follows the cursor (mid-sentence replacement). */
        NO_SPACE_MID_SENTENCE,

        /** Branch 3: normal — a trailing space is appended after the word. */
        TRAILING_SPACE
    }

    /**
     * Decide whether (and why) a trailing space is appended after a committed
     * suggestion. Mirrors — and is CALLED BY — SuggestionHandler's
     * `textToInsert` branch:
     *
     *   if (!auto_space_after && !isSwipe) → NO_SPACE_USER_DISABLED   (#82)
     *   else if (hasSpaceAfter)            → NO_SPACE_MID_SENTENCE     (v1.2.6)
     *   else                               → TRAILING_SPACE           (normal/swipe)
     *
     * @param autoSpaceAfterEnabled config.auto_space_after_suggestion (#82 toggle)
     * @param isSwipeAutoInsert     true when the commit came from a swipe auto-insert
     * @param hasSpaceAfter         true when the char after the cursor is whitespace
     */
    fun decideTrailingSpace(
        autoSpaceAfterEnabled: Boolean,
        isSwipeAutoInsert: Boolean,
        hasSpaceAfter: Boolean
    ): TrailingSpaceMode = when {
        !autoSpaceAfterEnabled && !isSwipeAutoInsert -> TrailingSpaceMode.NO_SPACE_USER_DISABLED
        hasSpaceAfter -> TrailingSpaceMode.NO_SPACE_MID_SENTENCE
        else -> TrailingSpaceMode.TRAILING_SPACE
    }

    /**
     * Whether the commit actually added a trailing space (used to arm the
     * smart-punctuation "swallow" via markAutoSpacePending). Exactly the
     * complement of the two NO_SPACE modes — kept as a derived helper so the
     * `addedTrailingSpace` flag can never drift from [decideTrailingSpace].
     */
    fun addsTrailingSpace(
        autoSpaceAfterEnabled: Boolean,
        isSwipeAutoInsert: Boolean,
        hasSpaceAfter: Boolean
    ): Boolean =
        decideTrailingSpace(autoSpaceAfterEnabled, isSwipeAutoInsert, hasSpaceAfter) ==
            TrailingSpaceMode.TRAILING_SPACE
}
