package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Comprehensive tests for #82 — Auto-space after suggestion toggle.
 *
 * Tests the 4-way decision logic from SuggestionHandler.kt lines 631-667:
 *
 * 1. !auto_space && !isSwipe → no trailing space (user disabled #82)
 * 2. termux_mode && !isSwipe && inTermuxApp → no trailing space (terminal)
 * 3. hasSpaceAfter → no trailing space (mid-sentence replacement)
 * 4. else → trailing space added (normal behavior)
 *
 * Also tests the addedTrailingSpace tracking logic at lines 662-664 which
 * determines whether to mark the space as auto-inserted for smart punctuation.
 */
class AutoSpaceLogicTest {

    /**
     * Determines if a trailing space should be added after word selection.
     * Mirrors SuggestionHandler.kt lines 634-655 decision logic.
     */
    enum class SpaceMode {
        NO_SPACE_USER_DISABLED,  // Branch 1: user turned off auto-space
        NO_SPACE_TERMUX,         // Branch 2: in terminal app
        NO_SPACE_MID_SENTENCE,   // Branch 3: already has space after cursor
        TRAILING_SPACE           // Branch 4: normal — add space after word
    }

    private fun decideSpaceMode(
        autoSpaceEnabled: Boolean,
        isSwipeAutoInsert: Boolean,
        termuxModeEnabled: Boolean,
        inTermuxApp: Boolean,
        hasSpaceAfter: Boolean
    ): SpaceMode {
        return if (!autoSpaceEnabled && !isSwipeAutoInsert) {
            SpaceMode.NO_SPACE_USER_DISABLED
        } else if (termuxModeEnabled && !isSwipeAutoInsert && inTermuxApp) {
            SpaceMode.NO_SPACE_TERMUX
        } else if (hasSpaceAfter) {
            SpaceMode.NO_SPACE_MID_SENTENCE
        } else {
            SpaceMode.TRAILING_SPACE
        }
    }

    /**
     * Mirrors addedTrailingSpace logic from SuggestionHandler.kt lines 662-664:
     *   !(!auto_space && !isSwipe) && !(termux_mode && !isSwipe && inTermux) && !hasSpaceAfter
     */
    private fun wasTrailingSpaceAdded(
        autoSpaceEnabled: Boolean,
        isSwipeAutoInsert: Boolean,
        termuxModeEnabled: Boolean,
        inTermuxApp: Boolean,
        hasSpaceAfter: Boolean
    ): Boolean {
        return !(!autoSpaceEnabled && !isSwipeAutoInsert) &&
            !(termuxModeEnabled && !isSwipeAutoInsert && inTermuxApp) &&
            !hasSpaceAfter
    }

    // =========================================================================
    // Config defaults
    // =========================================================================

    @Test
    fun `auto space after suggestion is enabled by default`() {
        assertThat(Defaults.AUTO_SPACE_AFTER_SUGGESTION).isTrue()
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
        assertThat(decideSpaceMode(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.NO_SPACE_USER_DISABLED)
    }

    @Test
    fun `user disabled auto-space — swipe still gets trailing space`() {
        // Even with auto-space off, swipe auto-insert bypasses the user preference
        assertThat(decideSpaceMode(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = true,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `user disabled auto-space — swipe in termux app gets trailing space`() {
        // Swipe bypasses both user preference AND termux mode
        assertThat(decideSpaceMode(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = true,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    // =========================================================================
    // Branch 2: Termux mode active and in terminal app
    // =========================================================================

    @Test
    fun `termux mode in terminal app — no trailing space on tap`() {
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.NO_SPACE_TERMUX)
    }

    @Test
    fun `termux mode enabled but NOT in terminal app — trailing space added`() {
        // Setting enabled but Chrome is not a terminal app
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `termux mode in terminal app — swipe gets trailing space`() {
        // Swipe auto-insert always gets trailing space even in terminal
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = true,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `termux mode disabled in terminal app — trailing space added`() {
        // Even if in a terminal app, if termux_mode_enabled is off, normal behavior
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = true,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    // =========================================================================
    // Branch 3: Mid-sentence replacement (hasSpaceAfter)
    // =========================================================================

    @Test
    fun `mid-sentence replacement — no trailing space`() {
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = true
        )).isEqualTo(SpaceMode.NO_SPACE_MID_SENTENCE)
    }

    @Test
    fun `mid-sentence with swipe — still no trailing space`() {
        // hasSpaceAfter takes priority when branches 1 and 2 don't match
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = true,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = true
        )).isEqualTo(SpaceMode.NO_SPACE_MID_SENTENCE)
    }

    @Test
    fun `mid-sentence in termux app — termux branch takes priority over mid-sentence`() {
        // Termux branch (2) is checked before mid-sentence (3)
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = true
        )).isEqualTo(SpaceMode.NO_SPACE_TERMUX)
    }

    // =========================================================================
    // Branch 4: Normal — trailing space added
    // =========================================================================

    @Test
    fun `normal mode — trailing space added`() {
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `normal swipe — trailing space added`() {
        assertThat(decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = true,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isEqualTo(SpaceMode.TRAILING_SPACE)
    }

    // =========================================================================
    // addedTrailingSpace tracking (for smart punctuation)
    // =========================================================================

    @Test
    fun `trailing space tracked when space is added in normal mode`() {
        assertThat(wasTrailingSpaceAdded(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isTrue()
    }

    @Test
    fun `trailing space NOT tracked when user disabled auto-space`() {
        assertThat(wasTrailingSpaceAdded(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isFalse()
    }

    @Test
    fun `trailing space NOT tracked in termux app`() {
        assertThat(wasTrailingSpaceAdded(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )).isFalse()
    }

    @Test
    fun `trailing space NOT tracked when hasSpaceAfter`() {
        assertThat(wasTrailingSpaceAdded(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = true
        )).isFalse()
    }

    @Test
    fun `trailing space tracked for swipe even with auto-space disabled`() {
        // Swipe bypasses user preference, so space IS added → tracking should be true
        assertThat(wasTrailingSpaceAdded(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = true,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = false
        )).isTrue()
    }

    @Test
    fun `trailing space tracked for swipe in termux`() {
        // Swipe bypasses termux suppression too
        assertThat(wasTrailingSpaceAdded(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = true,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )).isTrue()
    }

    // =========================================================================
    // Decision mode consistency with tracking
    // =========================================================================

    @Test
    fun `space mode and tracking agree — trailing space mode tracks space`() {
        val mode = decideSpaceMode(
            autoSpaceEnabled = true, isSwipeAutoInsert = false,
            termuxModeEnabled = false, inTermuxApp = false, hasSpaceAfter = false
        )
        val tracked = wasTrailingSpaceAdded(
            autoSpaceEnabled = true, isSwipeAutoInsert = false,
            termuxModeEnabled = false, inTermuxApp = false, hasSpaceAfter = false
        )
        assertThat(mode).isEqualTo(SpaceMode.TRAILING_SPACE)
        assertThat(tracked).isTrue()
    }

    @Test
    fun `space mode and tracking agree — no space modes do not track`() {
        // Branch 1: user disabled
        assertThat(decideSpaceMode(false, false, false, false, false))
            .isEqualTo(SpaceMode.NO_SPACE_USER_DISABLED)
        assertThat(wasTrailingSpaceAdded(false, false, false, false, false))
            .isFalse()

        // Branch 2: termux
        assertThat(decideSpaceMode(true, false, true, true, false))
            .isEqualTo(SpaceMode.NO_SPACE_TERMUX)
        assertThat(wasTrailingSpaceAdded(true, false, true, true, false))
            .isFalse()

        // Branch 3: mid-sentence
        assertThat(decideSpaceMode(true, false, false, false, true))
            .isEqualTo(SpaceMode.NO_SPACE_MID_SENTENCE)
        assertThat(wasTrailingSpaceAdded(true, false, false, false, true))
            .isFalse()
    }

    // =========================================================================
    // Priority/ordering tests
    // =========================================================================

    @Test
    fun `user disabled takes priority over termux mode`() {
        // Both branch 1 and 2 conditions met — branch 1 should win
        val mode = decideSpaceMode(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )
        assertThat(mode).isEqualTo(SpaceMode.NO_SPACE_USER_DISABLED)
    }

    @Test
    fun `user disabled takes priority over mid-sentence`() {
        val mode = decideSpaceMode(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,
            inTermuxApp = false,
            hasSpaceAfter = true
        )
        assertThat(mode).isEqualTo(SpaceMode.NO_SPACE_USER_DISABLED)
    }

    @Test
    fun `termux takes priority over mid-sentence`() {
        val mode = decideSpaceMode(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = true
        )
        assertThat(mode).isEqualTo(SpaceMode.NO_SPACE_TERMUX)
    }
}
