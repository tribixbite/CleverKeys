package tribixbite.cleverkeys

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Todo item 42 (found 2026-11-22 during item 41's device check): in an empty field, 123+ then ABC
 * gave lowercase letters. [Keyboard2View.setKeyboard] resets the view, which drops every modifier,
 * auto-capitalisation's Shift with them, and nothing asked auto-capitalisation again.
 *
 * Pinned at the receiver seam, mock tier: every switch that shows a LETTER layout (ABC back from a
 * pane, the next / previous layout) asks the service to show auto-capitalisation's Shift again,
 * after the new layout is in place. The number, symbol and Greek/Math panes do not: the number
 * pane has a Shift key of its own, and a Shift latched there would turn its arrow keys into
 * selection (Shift+arrow).
 *
 * Harness as in [KeyboardReceiverPaneHostTest]: the receiver allocated without its constructor,
 * collaborators injected.
 */
class LayoutSwitchAutocapTest {

    private val objenesis = ObjenesisStd()
    private lateinit var receiver: KeyboardReceiver
    private lateinit var service: CleverKeysService
    private lateinit var view: Keyboard2View
    private lateinit var layouts: LayoutManager
    private val letters = mockk<KeyboardData>(relaxed = true)
    private val nextLetters = mockk<KeyboardData>(relaxed = true)
    private val pane = mockk<KeyboardData>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        service = mockk(relaxed = true)
        view = mockk(relaxed = true)
        layouts = mockk(relaxed = true)
        every { layouts.clearSpecialLayout() } returns letters
        every { layouts.getLayoutCount() } returns 2
        every { layouts.incrTextLayout(any()) } returns nextLetters
        every { layouts.loadNumpad(any()) } returns pane
        every { layouts.loadSymbols(any()) } returns pane
        receiver = objenesis.newInstance(KeyboardReceiver::class.java)
        receiver.setField("keyboard2", service)
        receiver.setField("layoutManager", layouts)
        receiver.setField("keyboardViewProvider", { view })
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun abcBackToTheLettersShowsAutocapsShiftAgain() {
        receiver.handle_event_key(KeyValue.Event.SWITCH_TEXT)
        verifyOrder {
            view.setKeyboard(letters)
            service.restoreAutoCapitalisation()
        }
    }

    @Test
    fun theNextAndPreviousLayoutShowAutocapsShiftAgain() {
        for (event in listOf(KeyValue.Event.SWITCH_FORWARD, KeyValue.Event.SWITCH_BACKWARD)) {
            receiver.handle_event_key(event)
        }
        verify(exactly = 2) { view.setKeyboard(nextLetters) }
        verify(exactly = 2) { service.restoreAutoCapitalisation() }
    }

    @Test
    fun aSingleLayoutSwitchesNothingAndAsksNothing() {
        every { layouts.getLayoutCount() } returns 1
        receiver.handle_event_key(KeyValue.Event.SWITCH_FORWARD)
        verify(exactly = 0) { view.setKeyboard(any()) }
        verify(exactly = 0) { service.restoreAutoCapitalisation() }
    }

    /**
     * Pixel 2026-11-24 (release r7): in an empty field, opening the emoji pane (Fn's swipe) and
     * closing it without an emoji left the letters lowercase. The opener is a key like any other,
     * so its release spends the one-shot Shift, and closing a pane asked auto-capitalisation
     * nothing. Closing the emoji, clipboard or GIF pane asks again: the editor decides, so after an
     * inserted emoji the Shift stays off.
     */
    @Test
    fun closingAContentPaneShowsAutocapsShiftAgain() {
        // Closing a pane clears the clipboard pane's search routing first.
        receiver.setField("clipboardManager", mockk<ClipboardManager>(relaxed = true))
        for (event in listOf(
            KeyValue.Event.SWITCH_BACK_EMOJI, KeyValue.Event.SWITCH_BACK_CLIPBOARD, KeyValue.Event.SWITCH_BACK_GIF,
        )) {
            receiver.handle_event_key(event)
        }
        verify(exactly = 3) { service.restoreAutoCapitalisation() }
    }

    @Test
    fun theNumberSymbolAndGreekPanesDoNotLatchShift() {
        for (event in listOf(
            KeyValue.Event.SWITCH_NUMERIC, KeyValue.Event.SWITCH_SYMBOLS, KeyValue.Event.SWITCH_GREEKMATH,
        )) {
            receiver.handle_event_key(event)
        }
        verify(atLeast = 1) { view.setKeyboard(pane) }
        verify(exactly = 0) { service.restoreAutoCapitalisation() }
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }
}
