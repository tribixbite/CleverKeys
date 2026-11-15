package tribixbite.cleverkeys

import android.content.Context
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import tribixbite.cleverkeys.stt.SpeechHypothesis
import tribixbite.cleverkeys.stt.VoiceFieldKind
import tribixbite.cleverkeys.stt.VoiceTextOp
import tribixbite.cleverkeys.stt.VoiceTextPlanner

/**
 * Review 2026-11-14 (Voice, low): an editor that does not answer `getTextBeforeCursor` (null:
 * a busy app, a timeout, a dead connection) is NOT an empty field.
 *
 * [VoiceInputBridge.textBefore] mapped that null to "", which [VoiceTextPlanner] reads as the
 * start of the field, i.e. a sentence start: dictation in the middle of a sentence came out
 * capitalised ("I went to the " + "store" gave "Store"). Unknown context is now reported as
 * unknown (null), and the planner keeps the engine's own casing, as it does for a blind field.
 */
class VoiceInputBridgeTextBeforeTest {

    private fun bridge(ic: InputConnection?) = VoiceInputBridge(
        context = mockk<Context>(relaxed = true),
        prefsProvider = { mockk(relaxed = true) },
        editorInfo = { null },
        inputConnection = { ic },
        inlineTarget = { null },
        suggestionBar = { null },
        announcer = { null },
        config = { null },
        onExternalEdit = {},
    )

    @Test
    fun `an editor that does not answer gives unknown context, not an empty field`() {
        val ic = mockk<InputConnection>()
        every { ic.getTextBeforeCursor(any(), any()) } returns null
        val before = bridge(ic).textBefore(VoiceFieldKind.TEXT)
        assertWithMessage("textBefore for an unanswered read").that(before).isNull()

        // End to end with the planner: the engine's capital is left alone (no sentence start
        // is invented), so "Store" is not forced; a lower-case word is not capitalised either.
        val planner = VoiceTextPlanner(VoiceFieldKind.TEXT, before)
        val ops = planner.onHypothesis(SpeechHypothesis("store", 1, isFinal = true))
        assertThat(ops.filterIsInstance<VoiceTextOp.Commit>().joinToString("") { it.text }).isEqualTo("store")
    }

    @Test
    fun `an answered read is passed through`() {
        val ic = mockk<InputConnection>()
        every { ic.getTextBeforeCursor(any(), any()) } returns "I went to the "
        assertThat(bridge(ic).textBefore(VoiceFieldKind.TEXT)).isEqualTo("I went to the ")
    }
}
