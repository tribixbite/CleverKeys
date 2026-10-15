package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.TerminalSuggestionPolicy.BarRows
import tribixbite.cleverkeys.TerminalSuggestionPolicy.TapPlan

/**
 * The terminal-style editor tap/bar rule ([TerminalSuggestionPolicy]): a tap appends what follows
 * the tracked word and never deletes; the bar offers only extensions. The device replays (Saga,
 * Termux 0.118.3, 2026-10-15) live in `TerminalTypedWordTrackingTest`.
 */
class TerminalSuggestionPolicyTest {

    @Test
    fun anExtensionAppendsOnlyTheRemainder() {
        assertThat(TerminalSuggestionPolicy.planTap("hel", "hello")).isEqualTo(TapPlan.Append("lo", "hello"))
    }

    @Test
    fun theTypedCaseIsKept() {
        assertThat(TerminalSuggestionPolicy.planTap("HEl", "Hello")).isEqualTo(TapPlan.Append("lo", "HEllo"))
        assertThat(TerminalSuggestionPolicy.planTap("i", "I'm")).isEqualTo(TapPlan.Append("'m", "i'm"))
    }

    @Test
    fun theTrackedWordItselfAppendsNothing() {
        assertThat(TerminalSuggestionPolicy.planTap("ls", "ls")).isEqualTo(TapPlan.Append("", "ls"))
    }

    @Test
    fun withNoTrackedWordTheWholeWordIsAppended() {
        assertThat(TerminalSuggestionPolicy.planTap("", "echo")).isEqualTo(TapPlan.Append("echo", "echo"))
    }

    @Test
    fun anythingThatWouldNeedADeletionIsRefused() {
        // Corrections, contractions and the D2/D4 stale-tracker chips.
        for ((tracked, selected) in listOf(
            "foocbar" to "foodborne", "ecx" to "except", "dont" to "don't", "teh" to "the", "hello" to "hell",
        )) {
            assertWithMessage("$tracked -> $selected")
                .that(TerminalSuggestionPolicy.planTap(tracked, selected)).isEqualTo(TapPlan.Refuse)
        }
    }

    @Test
    fun theBarKeepsExtensionsInTypedCaseAndTheExactAddChip() {
        val dict = SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX)
        val exact = SuggestionMeta(SuggestionOrigin.EXACT_ADD)
        val rows = BarRows(
            words = listOf("Hello", "help", "hex", "HELLO", Suggestion.ExactAdd("HEl").wire),
            scores = listOf(5, 4, 3, 2, 0),
            metas = listOf(dict, dict, dict, dict, exact),
        )
        val bar = TerminalSuggestionPolicy.restrictBar("HEl", rows)
        assertThat(bar.words).containsExactly("HEllo", "HElp", Suggestion.ExactAdd("HEl").wire).inOrder()
        assertThat(bar.scores).containsExactly(5, 4, 0).inOrder()
        assertThat(bar.metas).containsExactly(dict, dict, exact).inOrder()
    }
}
