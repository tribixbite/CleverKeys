package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Issue #78 — confirmed bug: tap suggestion after typing partial word.
 *
 * Reported scenario (verified by reporter on v1.4.0):
 *   1. User taps to focus a text field (Termux, Fennec address bar, Google Keep)
 *   2. User touch-types "hel"
 *   3. User taps the "hello" suggestion
 *   4. Expected: typed prefix is REPLACED → "hello"
 *   5. Actual: typed prefix is APPENDED → "helhello" (or "helhello " with trailing space)
 *
 * Secondary bug specific to Termux:
 *   • Reporter notes Fennec honors auto-space-before/after-suggestion settings,
 *     but Termux ignores them — Termux always skips trailing space regardless
 *     of the user's auto_space_after_suggestion preference.
 *
 * These tests are RED — they assert the EXPECTED-after-fix outcome against
 * helpers that mirror the CURRENT (buggy) impl, so they fail until the impl
 * is fixed. Do NOT modify the impl-mirror helpers below to make them pass
 * without an approved fix plan; they exist to document the symptom.
 *
 * Reference: SuggestionHandler.kt onSuggestionSelected:
 *   • Lines 560-595: replace logic for typed-prefix-then-tap
 *   • Lines 644-664: 4-way space-mode decision (#82 toggle, Termux override,
 *     mid-sentence, normal)
 *
 * Hypothesized root causes (for fix planning, not asserted by these tests):
 *   • Replace bug: contextTracker.getCurrentWordLength() returns 0 in apps that
 *     commit characters immediately without composing-text. SuggestionHandler
 *     line 560 guard `getCurrentWordLength() > 0` skips the deletion path.
 *   • Termux pref bug: SuggestionHandler line 649 unconditionally takes the
 *     "no trailing space" branch when `inTermuxApp && termux_mode_enabled`,
 *     overriding the user's auto_space_after_suggestion=true preference.
 */
class Issue78SuggestionReplaceTest {

    // =========================================================================
    // Impl mirror — replicates SuggestionHandler line 560-595 logic
    //
    // The bug: when the target app commits characters without using composing-
    // text (Termux, Fennec address bar, Google Keep), getCurrentWordLength()
    // is reported as 0 from the IME's perspective even though the chars are
    // visible in the editor. The deletion guard skips, and the suggestion is
    // appended after the existing text.
    // =========================================================================

    /**
     * Simulates onSuggestionSelected's commit phase for the partial-word-replace
     * scenario. Returns the final text in the editor.
     *
     * Mirrors SuggestionHandler line 560 guard: only deletes the typed prefix
     * if `currentWordLengthFromContext > 0`. In Termux/Fennec/Keep, that value
     * is 0 (composing-text not used), so deletion is skipped.
     */
    private fun simulateImplCommit(
        editorTextBeforeTap: String,
        currentWordLengthFromContext: Int,  // What ContextTracker reports
        tappedWord: String,
        addTrailingSpace: Boolean
    ): String {
        // Mirror line 560: `if (getCurrentWordLength() > 0 && !isSwipeAutoInsert)`
        val deleted = if (currentWordLengthFromContext > 0) {
            // Deletion path runs (works in apps that use composing-text)
            editorTextBeforeTap.dropLast(currentWordLengthFromContext)
        } else {
            // Deletion skipped — bug: typed prefix stays in editor
            editorTextBeforeTap
        }
        val tail = if (addTrailingSpace) " " else ""
        return deleted + tappedWord + tail
    }

    /**
     * Simulates SuggestionHandler line 644-664 4-way decision in CURRENT
     * (buggy) form — Termux branch overrides user pref unconditionally.
     */
    private fun decideSpaceModeBuggy(
        autoSpaceEnabled: Boolean,
        isSwipeAutoInsert: Boolean,
        termuxModeEnabled: Boolean,
        inTermuxApp: Boolean,
        hasSpaceAfter: Boolean
    ): AutoSpaceLogicTest.SpaceMode {
        return if (!autoSpaceEnabled && !isSwipeAutoInsert) {
            AutoSpaceLogicTest.SpaceMode.NO_SPACE_USER_DISABLED
        } else if (termuxModeEnabled && !isSwipeAutoInsert && inTermuxApp) {
            // BUG B: this branch fires regardless of autoSpaceEnabled
            AutoSpaceLogicTest.SpaceMode.NO_SPACE_TERMUX
        } else if (hasSpaceAfter) {
            AutoSpaceLogicTest.SpaceMode.NO_SPACE_MID_SENTENCE
        } else {
            AutoSpaceLogicTest.SpaceMode.TRAILING_SPACE
        }
    }

    // =========================================================================
    // BUG A — replace, not append (Termux + Fennec + Keep)
    // =========================================================================

    @Test
    fun `BUG A — Termux tap suggestion REPLACES typed prefix not appends`() {
        // User typed "hel" in Termux. Termux doesn't use composing-text, so
        // ContextTracker reports currentWordLength=0 even though "hel" is in
        // the editor. The SuggestionHandler skips deletion → bug.
        val result = simulateImplCommit(
            editorTextBeforeTap = "hel",
            currentWordLengthFromContext = 0,  // What Termux/Fennec/Keep report
            tappedWord = "hello",
            addTrailingSpace = false  // Termux: no trailing
        )
        // Expected after fix: typed prefix replaced.
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `BUG A — Fennec address bar tap suggestion REPLACES typed prefix`() {
        // Fennec URL bar similarly commits without composing-text.
        // currentWordLength=0 → deletion skipped → "googoogle " bug.
        val result = simulateImplCommit(
            editorTextBeforeTap = "goo",
            currentWordLengthFromContext = 0,
            tappedWord = "google",
            addTrailingSpace = true  // Fennec: normal app, trailing space
        )
        assertThat(result).isEqualTo("google ")
    }

    @Test
    fun `BUG A — Google Keep tap suggestion REPLACES typed prefix`() {
        val result = simulateImplCommit(
            editorTextBeforeTap = "remi",
            currentWordLengthFromContext = 0,
            tappedWord = "remind",
            addTrailingSpace = true
        )
        assertThat(result).isEqualTo("remind ")
    }

    @Test
    fun `BUG A control — apps with composing-text correctly REPLACE prefix`() {
        // Sanity: when ContextTracker correctly reports the typed length
        // (composing-text-aware app), the deletion path runs and the result
        // is correct. Documents the WORKING case for contrast.
        val result = simulateImplCommit(
            editorTextBeforeTap = "hel",
            currentWordLengthFromContext = 3,  // Composing-aware app
            tappedWord = "hello",
            addTrailingSpace = true
        )
        assertThat(result).isEqualTo("hello ")
    }

    // =========================================================================
    // BUG B — Termux ignores auto_space_after_suggestion=true preference
    // =========================================================================

    @Test
    fun `BUG B — Termux should honor auto_space_after_suggestion=true and add trailing space`() {
        // User explicitly enabled auto_space_after_suggestion. In Termux app,
        // they should still get the trailing space they asked for.
        // Current impl: returns NO_SPACE_TERMUX, ignoring the user's pref.
        val current = decideSpaceModeBuggy(
            autoSpaceEnabled = true,    // user wants space
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )
        // Expected after fix: TRAILING_SPACE (user pref takes precedence).
        assertThat(current).isEqualTo(AutoSpaceLogicTest.SpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `BUG B — Termux should honor auto_space_after_suggestion=false`() {
        // Control: user pref OFF. This case happens to work today because
        // the NO_SPACE_USER_DISABLED branch fires before the Termux branch.
        val current = decideSpaceModeBuggy(
            autoSpaceEnabled = false,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = true,
            hasSpaceAfter = false
        )
        assertThat(current).isEqualTo(AutoSpaceLogicTest.SpaceMode.NO_SPACE_USER_DISABLED)
    }

    @Test
    fun `BUG B — Fennec correctly honors auto_space_after_suggestion (control)`() {
        // Reporter confirms Fennec honors user pref correctly.
        val current = decideSpaceModeBuggy(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = true,
            inTermuxApp = false,  // Fennec — not Termux
            hasSpaceAfter = false
        )
        assertThat(current).isEqualTo(AutoSpaceLogicTest.SpaceMode.TRAILING_SPACE)
    }

    @Test
    fun `BUG B — Termux with termux_mode disabled honors user preference`() {
        // If user disables termux_mode globally, Termux app should behave normally.
        val current = decideSpaceModeBuggy(
            autoSpaceEnabled = true,
            isSwipeAutoInsert = false,
            termuxModeEnabled = false,  // user disabled termux mode globally
            inTermuxApp = true,
            hasSpaceAfter = false
        )
        assertThat(current).isEqualTo(AutoSpaceLogicTest.SpaceMode.TRAILING_SPACE)
    }
}
