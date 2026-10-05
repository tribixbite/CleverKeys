package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CustomShortSwipeExecutor
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Audit 2026-09-06, H-1 + H-7: the execution chain of [Keyboard2View.onCustomShortSwipe]
 * for Command-Palette mappings.
 *
 * ## H-1 — `switch_forward`/`switch_backward` executed TWICE
 *
 * A palette-created mapping stores `actionType=COMMAND, actionValue="switch_forward"`.
 * The executor declines it (`executeCommand` returns false — "requires keyboard service
 * handling"), the view resolves it as a `Kind.Event` KeyValue and dispatches
 * `service.triggerKeyboardEvent(SWITCH_FORWARD)` — and then FELL THROUGH into the legacy
 * `mapping.getCommand()` block, which dispatched the very same event AGAIN. With exactly
 * two layouts enabled the switch wraps back and the swipe looks dead. Scope is exactly
 * `switch_forward`/`switch_backward` — the only names that are both a KeyValue name and
 * an `AvailableCommand` (legacy SCREAMING_SNAKE mappings miss the case-sensitive
 * `getKeyByName` and execute once via the legacy block only).
 *
 * Red (pre-fix): `verify(exactly = 1)` fails with 2 recorded `triggerKeyboardEvent` calls.
 *
 * ## H-7 — the five custom text-action commands skipped the success haptic
 *
 * `primaryLangToggle`/`secondaryLangToggle`/`textAssist`/`replaceText`/`showTextMenu`
 * `return`ed before the `performHapticFeedback(KEYBOARD_TAP)` that every other
 * successful custom swipe reaches ("Provide haptic feedback for successful gesture").
 *
 * Red (pre-fix): `verify(exactly = 1)` on the haptic fails with 0 calls.
 *
 * ## Harness
 *
 * The [ClipboardMediaDeleteAffordanceTest] idiom: a REAL [Keyboard2View] allocated
 * without its constructor (Objenesis) and spied, so the real `onCustomShortSwipe` body
 * runs while `context`/`performHapticFeedback` (android.jar stubs) are stubbed. The
 * command executor is a mock that declines (exactly what production does for these
 * commands); the service records dispatches. Apostrophe cases instead run the real
 * executor and KeyEventHandler against an editable InputConnection buffer and real
 * automatic-space tracker, covering text output, inline routing and success haptics.
 */
class Keyboard2ViewCustomSwipeDispatchTest {

    private val objenesis = ObjenesisStd()

    private lateinit var view: Keyboard2View
    private lateinit var service: CleverKeysService
    private lateinit var executor: CustomShortSwipeExecutor
    private lateinit var inputConnection: InputConnection

    @Before
    fun setup() {
        // Keyboard2View's companion constructs a RectF at class-init time — served by the
        // functional test-source shadow in src/test/kotlin/android/graphics/RectF.kt
        // (the android.jar stub constructor throws, and constructor-mocking android.jar
        // classes does not take effect in this tier).
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        inputConnection = mockk(relaxed = true)
        service = mockk(relaxed = true)
        every { service.currentInputConnection } returns inputConnection
        every { service.currentInputEditorInfo } returns mockk<EditorInfo>(relaxed = true)

        executor = mockk()
        every { executor.execute(any(), any(), any()) } returns false

        view = spyk(objenesis.newInstance(Keyboard2View::class.java))
        every { view.context } returns mockk<Context>(relaxed = true)
        every { view.performHapticFeedback(any()) } returns true
        view.setField("_keyboard2", service)
        view.setField("_customSwipeExecutor", executor)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun commandMapping(actionValue: String) = ShortSwipeMapping(
        keyCode = "q",
        direction = SwipeDirection.NE,
        displayText = "→",
        actionType = ActionType.COMMAND,
        actionValue = actionValue,
    )

    // ----------------------------------------------------------------- H-1: exactly once

    @Test
    fun switchForward_dispatchesExactlyOnce() {
        view.onCustomShortSwipe(commandMapping("switch_forward"))

        verify(exactly = 1) { service.triggerKeyboardEvent(KeyValue.Event.SWITCH_FORWARD) }
    }

    @Test
    fun switchBackward_dispatchesExactlyOnce() {
        view.onCustomShortSwipe(commandMapping("switch_backward"))

        verify(exactly = 1) { service.triggerKeyboardEvent(KeyValue.Event.SWITCH_BACKWARD) }
    }

    /** The legacy block must still carry names ONLY it understands (SCREAMING_SNAKE). */
    @Test
    fun legacyUppercaseMapping_stillDispatchesOnce() {
        view.onCustomShortSwipe(commandMapping("SWITCH_FORWARD"))

        verify(exactly = 1) { service.triggerKeyboardEvent(KeyValue.Event.SWITCH_FORWARD) }
    }

    // ----------------------------------------------------------------- H-7: success haptic

    @Test
    fun textAssistCommand_getsTheSuccessHaptic() {
        // Selection empty → the handler shows the "no text selected" suggestion-bar
        // message; the gesture still executed and must vibrate like every other one.
        every { inputConnection.getSelectedText(0) } returns null

        view.onCustomShortSwipe(commandMapping("textAssist"))

        verify(exactly = 1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    @Test
    fun primaryLangToggleCommand_getsTheSuccessHaptic() {
        view.onCustomShortSwipe(commandMapping("primaryLangToggle"))

        verify(exactly = 1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** Sanity: the H-1 fix must not eat the haptic the Event path already had. */
    @Test
    fun switchForward_stillGetsTheSuccessHaptic() {
        view.onCustomShortSwipe(commandMapping("switch_forward"))

        verify(exactly = 1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // ------------------------------------------- custom apostrophe parity (2026-10-05)

    @Test
    fun customApostropheAttachesToTheOwnedSwipeSpaceAndNotifiesTyping() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)

        view.onCustomShortSwipe(textMapping("'"))

        assertEquals("Bowie'", text.editor.toString())
        verify(exactly = 1) { text.receiver.handle_text_typed("'") }
        verify(exactly = 1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    @Test
    fun customCurlyApostropheUsesTheSameOwnedSpaceRule() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)

        view.onCustomShortSwipe(textMapping("’"))

        assertEquals("Bowie’", text.editor.toString())
        verify(exactly = 1) { text.receiver.handle_text_typed("’") }
    }

    @Test
    fun customApostrophePreservesAManuallyTypedSpace() {
        val text = apostropheEditor("Bowie ", autoSpacePending = false)

        view.onCustomShortSwipe(textMapping("'"))

        assertEquals("Bowie '", text.editor.toString())
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun customApostropheRespectsDisabledSmartPunctuation() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true, smartPunctuation = false)

        view.onCustomShortSwipe(textMapping("'"))

        assertEquals("Bowie '", text.editor.toString())
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun customApostropheDoesNotSwallowASpaceAtADifferentCursorPosition() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)
        text.tracker.markAutoSpacePending(text.editor.length + 5)

        view.onCustomShortSwipe(textMapping("'"))

        assertEquals("Bowie '", text.editor.toString())
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun customApostropheAtTheStartOfAnEditorRemainsALiteralQuote() {
        val text = apostropheEditor("", autoSpacePending = false)

        view.onCustomShortSwipe(textMapping("'"))

        assertEquals("'", text.editor.toString())
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun customApostropheTargetsTheActiveInlineSearchWithoutEditingTheApp() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)
        every { text.receiver.isClipboardSearchMode() } returns true

        view.onCustomShortSwipe(textMapping("'"))

        assertEquals("Bowie ", text.editor.toString())
        verify(exactly = 1) { text.receiver.appendToClipboardSearch("'") }
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun multiCharacterApostropheSMacroRemainsLiteralAndIsNotSplitIntoKeys() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)

        view.onCustomShortSwipe(textMapping("'s"))

        assertEquals("Bowie 's", text.editor.toString())
        verify(exactly = 1) { inputConnection.commitText("'s", 1) }
        verify(exactly = 0) { text.receiver.handle_text_typed(any()) }
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun otherCustomQuoteTextKeepsItsExistingLiteralBehavior() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)

        view.onCustomShortSwipe(textMapping("\""))

        assertEquals("Bowie \"", text.editor.toString())
        verify(exactly = 0) { text.receiver.handle_text_typed(any()) }
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    private data class ApostropheEditor(
        val editor: StringBuilder,
        val tracker: PredictionContextTracker,
        val receiver: KeyEventHandler.IReceiver,
    )

    /**
     * Real view dispatch → real custom executor OR real KeyEventHandler → an editable IC
     * buffer. The tracker owns the automatic-space stamp just as on the swipe commit path.
     * No model of the punctuation rule is duplicated in this test.
     */
    private fun apostropheEditor(
        initialText: String,
        autoSpacePending: Boolean,
        smartPunctuation: Boolean = true,
    ): ApostropheEditor {
        val editor = StringBuilder(initialText)
        val tracker = PredictionContextTracker()
        if (autoSpacePending) tracker.markAutoSpacePending(editor.length)
        every { inputConnection.getTextBeforeCursor(any(), any()) } answers {
            editor.takeLast(firstArg<Int>()).toString()
        }
        every { inputConnection.deleteSurroundingText(any(), any()) } answers {
            editor.setLength(editor.length - firstArg<Int>())
            true
        }
        every { inputConnection.commitText(any(), any()) } answers {
            editor.append(firstArg<CharSequence>())
            true
        }

        mockkObject(PredictionContextTracker.Companion)
        every { PredictionContextTracker.currentCursorPosition(inputConnection) } answers {
            editor.length
        }
        val receiver = mockk<KeyEventHandler.IReceiver>(relaxed = true)
        every { receiver.getCurrentInputConnection() } returns inputConnection
        every { receiver.wasLastSpaceAutoInserted() } answers { tracker.lastSpaceWasAutoInserted }
        every { receiver.getAutoSpaceStampedPosition() } answers { tracker.autoSpaceStampedPosition }
        every { receiver.setLastSpaceAutoInserted(false) } answers { tracker.invalidateAutoSpacePending() }
        every { receiver.takeOwedTrailingSpace() } returns null

        val handler = objenesis.newInstance(KeyEventHandler::class.java).apply {
            setField("recv", receiver)
            setField("autocap", mockk<Autocapitalisation>(relaxed = true))
            setField("mods", Pointers.Modifiers.EMPTY)
        }
        val config = mockk<Config>(relaxed = true).apply {
            smart_punctuation = smartPunctuation
            double_space_to_period = false
            setField("handler", handler)
        }
        mockkObject(Config.Companion)
        every { Config.globalConfig() } returns config

        view.setField("_config", config)
        view.setField("_customSwipeExecutor", CustomShortSwipeExecutor(view.context))
        return ApostropheEditor(editor, tracker, receiver)
    }

    private fun textMapping(text: String) = ShortSwipeMapping(
        keyCode = "m",
        direction = SwipeDirection.SW,
        displayText = text,
        actionType = ActionType.TEXT,
        actionValue = text,
    )

    // ----------------------------------------------------------------- helpers

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field $name not found on ${javaClass.name}")
    }
}
