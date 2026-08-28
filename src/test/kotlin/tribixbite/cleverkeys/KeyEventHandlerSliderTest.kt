package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK-based JVM tests for `KeyEventHandler`'s SELECTION-SLIDER path
 * (`Slider.Selection_cursor_left` / `_right` → the private `moveCursorSel`).
 *
 * ## The invariant
 * `moveCursorSel` ends in a `do { … } while (selStart == selEnd)` loop whose ONLY mutation
 * is `+= d`. With `d == 0` and an empty selection (`selStart == selEnd` — the common case:
 * a plain caret), the loop can never make the two ends differ, so it spins **forever on the
 * IME's main thread** — the keyboard is dead until the process is killed. A `d == 0` slider
 * event is reachable: `key_down` fires `handleSlider(…, key.getSliderRepeat(), true)` after
 * the trigger distance is travelled, and the repeat value is carried in the key's 16-bit
 * value field, so any layout/short-swipe definition (or a hand-written XML `sliderKey`) that
 * yields 0 arms the hang. The guard is a single `if (d == 0) return` at the top of
 * `moveCursorSel`.
 *
 * ## Why these three tests
 *  1. [sliderRepeatZeroReturnsBeforeTouchingTheInputConnection] — the guard sits immediately
 *     BEFORE `recv.getCurrentInputConnection()`, so "connection never fetched" pins the
 *     early return at exactly the right line, deterministically and with no timing.
 *  2. [sliderRepeatZeroDoesNotSpinOnAnEmptySelection] — the hang itself, with the full
 *     `getExtractedText` wiring in place so the loop is genuinely reachable. Run on a
 *     **daemon** thread with a join deadline: a regression must fail this test, not wedge
 *     the whole `runMockTests` JVM (a non-daemon spinner would keep the JVM alive forever
 *     and turn a red test into a hung build).
 *  3. [nonZeroSliderRepeatMovesTheSelectionThroughTheSameWiring] — the liveness control. The
 *     first two tests assert things do NOT happen; without this one they would both pass
 *     against a completely dead slider path.
 *
 * Setup notes: `getCursorPos` lazily builds an [ExtractedTextRequest], whose android.jar stub
 * constructor throws `RuntimeException("Stub!")` (MockK cannot intercept framework
 * constructors — see `DebugLoggingManagerTest`). The companion-object `moveCursorReq` cache is
 * therefore pre-seeded by reflection so the real code path skips that construction.
 */
class KeyEventHandlerSliderTest {

    private lateinit var recv: KeyEventHandler.IReceiver
    private lateinit var conn: InputConnection
    private lateinit var handler: KeyEventHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        conn = mockk(relaxed = true)
        recv = mockk(relaxed = true)
        // KeyEventHandler's constructor builds an Autocapitalisation from this Handler.
        every { recv.getHandler() } returns mockk<Handler>(relaxed = true)
        every { recv.isClipboardEditMode() } returns false
        every { recv.getCurrentInputConnection() } returns conn

        handler = KeyEventHandler(recv)
    }

    @After
    fun teardown() {
        setMoveCursorRequest(null) // static cache — do not leak into other classes
        unmockkStatic(Log::class)
    }

    @Test
    fun sliderRepeatZeroReturnsBeforeTouchingTheInputConnection() {
        // Both directions, both entry points (key_down fires mid-gesture, key_up on release).
        handler.key_down(KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_left, 0), false)
        handler.key_down(KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_right, 0), false)
        handler.key_up(
            KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_left, 0),
            Pointers.Modifiers.EMPTY, false
        )
        handler.key_up(
            KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_right, 0),
            Pointers.Modifiers.EMPTY, false
        )

        verify(exactly = 0) { recv.getCurrentInputConnection() }
        verify(exactly = 0) { conn.setSelection(any(), any()) }
    }

    @Test
    fun sliderRepeatZeroDoesNotSpinOnAnEmptySelection() {
        seedCursorPos(selectionStart = 5, selectionEnd = 5) // empty selection: the hang shape
        every { conn.setSelection(any(), any()) } returns true

        val finished = CountDownLatch(1)
        val worker = Thread {
            handler.key_down(KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_left, 0), false)
            finished.countDown()
        }
        // DAEMON: if the guard regresses, the loop is uninterruptible and would otherwise
        // keep this JVM (and the whole test run) alive forever instead of failing.
        worker.isDaemon = true
        worker.start()

        assertWithMessage(
            "moveCursorSel(d=0) never returned on an empty selection — the `if (d == 0) " +
                "return` guard is gone and the do/while spins forever on the IME main thread"
        ).that(finished.await(JOIN_DEADLINE_MS, TimeUnit.MILLISECONDS)).isTrue()

        verify(exactly = 0) { conn.setSelection(any(), any()) }
    }

    @Test
    fun nonZeroSliderRepeatMovesTheSelectionThroughTheSameWiring() {
        seedCursorPos(selectionStart = 5, selectionEnd = 5)
        every { conn.setSelection(any(), any()) } returns true

        // selLeft: the LEFT end moves; one step is enough to make the ends differ.
        handler.key_down(KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_left, 1), false)
        verify(exactly = 1) { conn.setSelection(6, 5) }

        // selRight: the RIGHT end moves instead.
        handler.key_up(
            KeyValue.sliderKey(KeyValue.Slider.Selection_cursor_right, 1),
            Pointers.Modifiers.EMPTY, false
        )
        verify(exactly = 1) { conn.setSelection(5, 6) }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Make `getCursorPos` succeed: pre-seed the companion-object [ExtractedTextRequest]
     * cache (its android.jar constructor throws "Stub!") and hand back an [ExtractedText]
     * carrying the given selection. [ExtractedText]'s selection bounds are public FIELDS,
     * so the MockK instance — allocated without running the stub constructor — carries them
     * for real.
     */
    private fun seedCursorPos(selectionStart: Int, selectionEnd: Int) {
        setMoveCursorRequest(mockk<ExtractedTextRequest>(relaxed = true))
        val extracted = mockk<ExtractedText>(relaxed = true)
        extracted.selectionStart = selectionStart
        extracted.selectionEnd = selectionEnd
        every { conn.getExtractedText(any(), any()) } returns extracted
    }

    /** Write `KeyEventHandler`'s companion-object `moveCursorReq` static cache. */
    private fun setMoveCursorRequest(value: ExtractedTextRequest?) {
        val field = KeyEventHandler::class.java.declaredFields.firstOrNull {
            it.name == "moveCursorReq"
        }
        assertWithMessage(
            "KeyEventHandler.moveCursorReq static cache not found — the companion field was " +
                "renamed; this test's ExtractedTextRequest workaround needs updating"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(null, value)
    }

    private companion object {
        /**
         * Generous: the guarded path returns in microseconds, so any value large enough to
         * survive a loaded CI box is fine. Only a genuine infinite loop can exhaust it.
         */
        const val JOIN_DEADLINE_MS = 5_000L
    }
}
