package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Audit A-5 (2026-09-06) — double-space-to-period must verify a space ACTUALLY precedes
 * the cursor before deleting anything.
 *
 * The branch trusted `lastTypedChar == ' '`, a KeyEventHandler-local memory that only
 * sendText writes — backspace, cursor moves, and swipe/suggestion commits never reset it.
 * With field text "hix" (the user typed space, backspaced it, typed space again within the
 * 500 ms threshold), the guard read `getTextBeforeCursor(2,0) = "ix"`, checked only
 * position [0] ('i', alphanumeric), then `deleteSurroundingText(1,0)` — deleting the 'x'
 * the user just typed — and committed ". ": "hix" became "hi. " instead of "hix ".
 *
 * The fix verifies at use: the char adjacent to the cursor (textBefore[1]) must BE a
 * space. These tests drive the real sendText through key_up with a scripted
 * InputConnection fake holding the editor text (cursor at end).
 *
 * RED (2026-09-06, pre-fix): doubleSpaceAfterExternalDeletionMustNotEatALetter failed —
 * final text "hi. " (letter eaten, unrequested period).
 */
class DoubleSpacePeriodTest {

    private lateinit var recv: KeyEventHandler.IReceiver
    private lateinit var conn: InputConnection
    private lateinit var handler: KeyEventHandler
    private val text = StringBuilder()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkObject(Config.Companion)
        val cfg = mockk<Config>(relaxed = true)
        every { Config.globalConfig() } returns cfg
        cfg.double_space_to_period = true
        cfg.double_space_threshold = 5_000L // generous: the test types "instantly"
        cfg.smart_punctuation = false
        cfg.backspace_undo_swipe = false
        cfg.backspace_undo_autocorrect = false

        // Scripted editor: cursor pinned at end of [text].
        conn = mockk(relaxed = true)
        every { conn.getTextBeforeCursor(any(), any()) } answers {
            text.takeLast(firstArg<Int>()).toString()
        }
        every { conn.deleteSurroundingText(any(), any()) } answers {
            repeat(firstArg<Int>()) { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1) }
            true
        }
        every { conn.commitText(any(), any()) } answers {
            text.append(firstArg<CharSequence>())
            true
        }

        recv = mockk(relaxed = true)
        every { recv.getHandler() } returns mockk<Handler>(relaxed = true)
        every { recv.getCurrentInputConnection() } returns conn
        every { recv.takeOwedTrailingSpace() } returns null

        handler = KeyEventHandler(recv)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun type(c: Char) {
        handler.key_up(KeyValue.makeCharKey(c), Pointers.Modifiers.EMPTY, false)
    }

    @Test
    fun doubleSpaceAfterExternalDeletionMustNotEatALetter() {
        text.append("hi")
        type('x')          // "hix", lastTypedChar = 'x'
        type(' ')          // "hix ", lastTypedChar = ' '
        // The editor loses the space behind sendText's back — exactly what a backspace
        // (send_key_down_up path), a cursor move, or an app-side edit does. lastTypedChar
        // still says ' '.
        text.deleteCharAt(text.length - 1) // "hix"
        type(' ')          // second space within threshold

        assertWithMessage(
            "double-space fired without a space before the cursor — it deleted a LETTER " +
                "and inserted an unrequested period (audit A-5)"
        ).that(text.toString()).isEqualTo("hix ")
    }

    @Test
    fun genuineDoubleSpaceStillProducesPeriod() {
        text.append("hi")
        type('x')
        type(' ')
        type(' ')

        assertWithMessage("the intended double-space-to-period behavior must survive the guard")
            .that(text.toString()).isEqualTo("hix. ")
    }

    @Test
    fun doubleSpaceAfterPunctuationStillDeclines() {
        text.append("hi,")
        type(' ')
        type(' ')

        assertWithMessage("non-alphanumeric before the first space must not trigger a period")
            .that(text.toString()).isEqualTo("hi,  ")
    }
}
