package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.SmartAutoSpace.TrailingSpaceMode

/**
 * Tests for #82 — Auto-space after/before suggestion toggles.
 *
 * This test exercises the REAL production decision seam, not a mirror:
 *   - [SmartAutoSpace.decideTrailingSpace] / [SmartAutoSpace.addsTrailingSpace]
 *     is the exact function SuggestionHandler.onSuggestionSelected calls to
 *     pick the trailing-space branch and to arm the smart-punctuation swallow.
 *   - [SmartAutoSpace.needsLeadingSpace] is the char-level leading-space
 *     decision consumed by both SuggestionHandler and InputCoordinator.
 *
 * Historical note: an earlier version of this file re-implemented a 4-way
 * decision that included a `NO_SPACE_TERMUX` branch. That branch was removed
 * from production in #78 (Termux users disable auto_space_after_suggestion
 * instead), so the mirror was testing dead logic that could pass green forever
 * while diverging from the shipped code. The Termux branch is intentionally
 * absent below because it is absent in production.
 */
class AutoSpaceLogicTest {

    // =========================================================================
    // Config defaults
    // =========================================================================

    @Test
    fun `auto space after suggestion is enabled by default`() {
        assertThat(Defaults.AUTO_SPACE_AFTER_SUGGESTION).isTrue()
    }

    @Test
    fun `auto space before suggestion is enabled by default`() {
        assertThat(Defaults.AUTO_SPACE_BEFORE_SUGGESTION).isTrue()
    }

    @Test
    fun `termux mode is enabled by default`() {
        assertThat(Defaults.TERMUX_MODE_ENABLED).isTrue()
    }

    // =========================================================================
    // Branch 1: User disabled auto-space (#82 feature)
    // =========================================================================

    @Test
    fun `user disabled auto-space — no trailing space on tap selection`() {
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = false,
                isSwipeAutoInsert = false,
                hasSpaceAfter = false
            )
        ).isEqualTo(TrailingSpaceMode.NO_SPACE_USER_DISABLED)
    }

    @Test
    fun `user disabled auto-space — swipe still gets trailing space`() {
        // Even with auto-space off, swipe auto-insert bypasses the user preference
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = false,
                isSwipeAutoInsert = true,
                hasSpaceAfter = false
            )
        ).isEqualTo(TrailingSpaceMode.TRAILING_SPACE)
    }

    // =========================================================================
    // Branch 2: Mid-sentence replacement (hasSpaceAfter)
    // =========================================================================

    @Test
    fun `mid-sentence replacement — no trailing space`() {
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = true,
                isSwipeAutoInsert = false,
                hasSpaceAfter = true
            )
        ).isEqualTo(TrailingSpaceMode.NO_SPACE_MID_SENTENCE)
    }

    @Test
    fun `mid-sentence with swipe — still no trailing space`() {
        // hasSpaceAfter takes priority once the user-disabled branch doesn't match
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = true,
                isSwipeAutoInsert = true,
                hasSpaceAfter = true
            )
        ).isEqualTo(TrailingSpaceMode.NO_SPACE_MID_SENTENCE)
    }

    // =========================================================================
    // Branch 3: Normal — trailing space added
    // =========================================================================

    @Test
    fun `normal mode — trailing space added`() {
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = true,
                isSwipeAutoInsert = false,
                hasSpaceAfter = false
            )
        ).isEqualTo(TrailingSpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `normal swipe — trailing space added`() {
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = true,
                isSwipeAutoInsert = true,
                hasSpaceAfter = false
            )
        ).isEqualTo(TrailingSpaceMode.TRAILING_SPACE)
    }

    // =========================================================================
    // Priority/ordering: user-disabled branch is checked before mid-sentence
    // =========================================================================

    @Test
    fun `user disabled takes priority over mid-sentence`() {
        // Both branch-1 and branch-2 conditions met — branch 1 wins.
        assertThat(
            SmartAutoSpace.decideTrailingSpace(
                autoSpaceAfterEnabled = false,
                isSwipeAutoInsert = false,
                hasSpaceAfter = true
            )
        ).isEqualTo(TrailingSpaceMode.NO_SPACE_USER_DISABLED)
    }

    // =========================================================================
    // addsTrailingSpace tracking (arms the smart-punctuation swallow)
    // =========================================================================

    @Test
    fun `trailing space tracked when space is added in normal mode`() {
        assertThat(
            SmartAutoSpace.addsTrailingSpace(
                autoSpaceAfterEnabled = true,
                isSwipeAutoInsert = false,
                hasSpaceAfter = false
            )
        ).isTrue()
    }

    @Test
    fun `trailing space NOT tracked when user disabled auto-space`() {
        assertThat(
            SmartAutoSpace.addsTrailingSpace(
                autoSpaceAfterEnabled = false,
                isSwipeAutoInsert = false,
                hasSpaceAfter = false
            )
        ).isFalse()
    }

    @Test
    fun `trailing space NOT tracked when hasSpaceAfter`() {
        assertThat(
            SmartAutoSpace.addsTrailingSpace(
                autoSpaceAfterEnabled = true,
                isSwipeAutoInsert = false,
                hasSpaceAfter = true
            )
        ).isFalse()
    }

    @Test
    fun `trailing space tracked for swipe even with auto-space disabled`() {
        // Swipe bypasses the user preference, so a space IS added → tracking is true
        assertThat(
            SmartAutoSpace.addsTrailingSpace(
                autoSpaceAfterEnabled = false,
                isSwipeAutoInsert = true,
                hasSpaceAfter = false
            )
        ).isTrue()
    }

    // =========================================================================
    // Decision/tracking consistency — addsTrailingSpace is exactly the
    // complement of the two NO_SPACE modes (the invariant SuggestionHandler
    // relies on to keep addedTrailingSpace from drifting).
    // =========================================================================

    @Test
    fun `addsTrailingSpace agrees with decideTrailingSpace for every input`() {
        val bools = listOf(false, true)
        for (autoSpace in bools) {
            for (isSwipe in bools) {
                for (hasSpaceAfter in bools) {
                    val mode = SmartAutoSpace.decideTrailingSpace(autoSpace, isSwipe, hasSpaceAfter)
                    val added = SmartAutoSpace.addsTrailingSpace(autoSpace, isSwipe, hasSpaceAfter)
                    assertThat(added).isEqualTo(mode == TrailingSpaceMode.TRAILING_SPACE)
                }
            }
        }
    }

    // =========================================================================
    // Leading space (SmartAutoSpace.needsLeadingSpace) — the char-level seam.
    //
    // The pref/#151 gating (auto_space_before_suggestion, sync-suppressed
    // fields, swipe-always-spaces) is applied by SuggestionHandler /
    // InputCoordinator BEFORE consulting this; here we test the pure char rule:
    // "add a leading space unless the previous char is whitespace or an opener."
    // =========================================================================

    @Test
    fun `leading space — tap after colon gets space before`() {
        // "this:" + tap "english" → "this: english" (':' is neither whitespace nor an opener)
        assertThat(SmartAutoSpace.needsLeadingSpace(':', 's')).isTrue()
    }

    @Test
    fun `leading space — no space when previous char is whitespace`() {
        // Already preceded by whitespace → no double space
        assertThat(SmartAutoSpace.needsLeadingSpace(' ', 'a')).isFalse()
    }

    @Test
    fun `leading space — no space after opening bracket`() {
        // "(" + swipe "word" → "(word", never "( word"
        assertThat(SmartAutoSpace.needsLeadingSpace('(', null)).isFalse()
    }

    @Test
    fun `leading space — no space after opening quote at start of quote`() {
        // He said " + swipe → He said "word (opening quote disambiguated by preceding space)
        assertThat(SmartAutoSpace.needsLeadingSpace('"', ' ')).isFalse()
    }

    @Test
    fun `leading space — space after possessive apostrophe`() {
        // kids' + swipe "toys" → kids' toys (apostrophe after a letter is possessive/closing)
        assertThat(SmartAutoSpace.needsLeadingSpace('\'', 's')).isTrue()
    }
}
