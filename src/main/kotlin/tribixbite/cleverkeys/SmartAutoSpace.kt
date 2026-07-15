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
}
