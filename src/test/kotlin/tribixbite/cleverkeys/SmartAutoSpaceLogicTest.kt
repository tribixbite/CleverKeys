package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SAS-1 (v1.5.0, user-requested): smart auto-space around punctuation.
 *
 * Pure JVM tests for the [SmartAutoSpace] decision object shared by
 * SuggestionHandler / InputCoordinator (leading-space suppression after
 * opening punctuation) and KeyEventHandler (auto-space swallowing before
 * closing punctuation).
 *
 * Feature A — no leading auto-space after opening punctuation:
 *   `("` + swipe "word" must produce `(word` / `"word`, not `( word`.
 *   Openers: ( [ { “ ‘ ¿ ¡ unconditionally; straight " and ' only when
 *   NOT preceded by a letter/digit-adjacent context (possessive/closing
 *   quote reading wins after a word: `kids'` + swipe → `kids' word`).
 *
 * Feature B — closing punctuation swallows the AUTOMATIC trailing space:
 *   swallow eligibility = pending flag AND actual prev char is a space
 *   AND the cursor sits exactly where the auto-space commit stamped it
 *   (unknown positions degrade to the legacy flag+space verify-at-use).
 */
class SmartAutoSpaceLogicTest {

    // =========================================================================
    // Feature A — opening punctuation set (unambiguous openers)
    // =========================================================================

    @Test
    fun `unambiguous openers suppress leading space regardless of preceding char`() {
        for (opener in listOf('(', '[', '{', '“', '‘', '¿', '¡')) {
            for (beforePrev in listOf(null, ' ', 'a', '9', '.', '(')) {
                assertThat(SmartAutoSpace.isOpeningPunctuation(opener, beforePrev)).isTrue()
                assertThat(SmartAutoSpace.needsLeadingSpace(opener, beforePrev)).isFalse()
            }
        }
    }

    @Test
    fun `letters and digits before cursor need a leading space`() {
        for (prev in listOf('a', 'Z', '9')) {
            assertThat(SmartAutoSpace.needsLeadingSpace(prev, 'a')).isTrue()
        }
    }

    @Test
    fun `whitespace before cursor never needs a leading space`() {
        for (prev in listOf(' ', '\n', '\t')) {
            assertThat(SmartAutoSpace.needsLeadingSpace(prev, 'a')).isFalse()
        }
    }

    @Test
    fun `closing punctuation before cursor still gets a leading space`() {
        // "word." + swipe "next" → "word. next" — closers are not openers
        for (prev in listOf('.', ',', ')', ']', '}', '”', '’', '…', ':', ';', '!', '?')) {
            assertThat(SmartAutoSpace.needsLeadingSpace(prev, 'd')).isTrue()
        }
    }

    // =========================================================================
    // Feature A — ambiguous straight quotes " and '
    // =========================================================================

    @Test
    fun `straight quote at start of field is an opener`() {
        assertThat(SmartAutoSpace.isOpeningPunctuation('"', null)).isTrue()
        assertThat(SmartAutoSpace.isOpeningPunctuation('\'', null)).isTrue()
    }

    @Test
    fun `straight quote after whitespace is an opener`() {
        // `He said "` + swipe → `He said "word`
        assertThat(SmartAutoSpace.isOpeningPunctuation('"', ' ')).isTrue()
        assertThat(SmartAutoSpace.isOpeningPunctuation('\'', ' ')).isTrue()
        assertThat(SmartAutoSpace.needsLeadingSpace('"', ' ')).isFalse()
    }

    @Test
    fun `straight quote after an unambiguous opener is an opener`() {
        // `("` + swipe → `("word`
        assertThat(SmartAutoSpace.isOpeningPunctuation('"', '(')).isTrue()
        assertThat(SmartAutoSpace.isOpeningPunctuation('\'', '“')).isTrue()
    }

    @Test
    fun `apostrophe after a letter is NOT an opener - possessive keeps the space`() {
        // `kids'` + swipe "toys" → `kids' toys` (NOT `kids'toys`)
        assertThat(SmartAutoSpace.isOpeningPunctuation('\'', 's')).isFalse()
        assertThat(SmartAutoSpace.needsLeadingSpace('\'', 's')).isTrue()
    }

    @Test
    fun `straight double quote after letter or period is NOT an opener - closing quote keeps the space`() {
        // `word"` (closing) + swipe → `word" next`; `said."` + swipe → `said." next`
        assertThat(SmartAutoSpace.isOpeningPunctuation('"', 'd')).isFalse()
        assertThat(SmartAutoSpace.isOpeningPunctuation('"', '.')).isFalse()
        assertThat(SmartAutoSpace.needsLeadingSpace('"', 'd')).isTrue()
    }

    @Test
    fun `apostrophe after a digit is NOT an opener`() {
        // `90'` (feet/minutes) + swipe → `90' next`
        assertThat(SmartAutoSpace.isOpeningPunctuation('\'', '0')).isFalse()
    }

    @Test
    fun `curly closing quotes are never openers`() {
        assertThat(SmartAutoSpace.isOpeningPunctuation('”', ' ')).isFalse()
        assertThat(SmartAutoSpace.isOpeningPunctuation('’', ' ')).isFalse()
    }

    // =========================================================================
    // Feature B — closing punctuation set
    // =========================================================================

    @Test
    fun `closer set matches spec`() {
        for (closer in listOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '”', '’', '…', '\'')) {
            assertThat(SmartAutoSpace.isClosingPunctuation(closer)).isTrue()
        }
    }

    @Test
    fun `straight double quote is parity-disambiguated by the caller, not a blanket closer`() {
        // KeyEventHandler resolves " via isClosingQuote() quote-count parity:
        // odd prior count → closing (swallow), even → opening (keep space).
        assertThat(SmartAutoSpace.isClosingPunctuation('"')).isFalse()
    }

    @Test
    fun `openers and letters are not closers`() {
        for (c in listOf('(', '[', '{', '“', '‘', '¿', '¡', 'a', '9', ' ', '-')) {
            assertThat(SmartAutoSpace.isClosingPunctuation(c)).isFalse()
        }
    }

    // =========================================================================
    // Feature B — swallow eligibility (flag + position stamp, verify-at-use)
    // =========================================================================

    @Test
    fun `not eligible when no auto-space is pending`() {
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = false, stampedPosition = 5, actualPrevChar = ' ', actualPosition = 5
        )).isFalse()
    }

    @Test
    fun `not eligible when char before cursor is not a space`() {
        // Backspace ate the space, or text changed — never delete a non-space
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 5, actualPrevChar = 'd', actualPosition = 5
        )).isFalse()
    }

    @Test
    fun `not eligible when there is no char before cursor`() {
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 5, actualPrevChar = null, actualPosition = 5
        )).isFalse()
    }

    @Test
    fun `eligible when pending, prev char is space, and position matches stamp`() {
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 5, actualPrevChar = ' ', actualPosition = 5
        )).isTrue()
    }

    @Test
    fun `not eligible when cursor moved away from the stamped position`() {
        // User tapped elsewhere after a manually-typed space — that space is NOT ours
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 9, actualPrevChar = ' ', actualPosition = 4
        )).isFalse()
    }

    @Test
    fun `unknown stamp degrades to legacy flag-plus-space check`() {
        // Editors that don't support getExtractedText: stamp is -1 → verify-at-use
        // still requires the pending flag AND a real space before the cursor.
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = -1, actualPrevChar = ' ', actualPosition = 7
        )).isTrue()
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = -1, actualPrevChar = 'x', actualPosition = 7
        )).isFalse()
    }

    @Test
    fun `unknown actual position degrades to legacy flag-plus-space check`() {
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 5, actualPrevChar = ' ', actualPosition = -1
        )).isTrue()
    }

    @Test
    fun `tab or newline before cursor is not a swallowable space`() {
        // Only a plain auto-inserted ' ' may be deleted — never other whitespace
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 5, actualPrevChar = '\n', actualPosition = 5
        )).isFalse()
        assertThat(SmartAutoSpace.isSwallowEligible(
            autoSpacePending = true, stampedPosition = 5, actualPrevChar = '\t', actualPosition = 5
        )).isFalse()
    }
}
