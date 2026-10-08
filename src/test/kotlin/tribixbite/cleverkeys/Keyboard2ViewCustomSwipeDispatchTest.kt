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

    /**
     * Any single-character custom TEXT is typed through KeyEventHandler, like the key it
     * names — not only apostrophes (popover/palette audit, 2026-10-08). Before, a custom `"`
     * was committed raw and never reached typed-text bookkeeping.
     */
    @Test
    fun otherSingleCharacterCustomTextIsTypedThroughTheKeyPipeline() {
        val text = apostropheEditor("Bowie ", autoSpacePending = false)

        view.onCustomShortSwipe(textMapping("\""))

        assertEquals("Bowie \"", text.editor.toString())
        verify(exactly = 1) { text.receiver.handle_text_typed("\"") }
    }

    @Test
    fun clearClipboardCustomCommandDoesNotEditTheTargetField() {
        val cm = mockk<android.content.ClipboardManager>(relaxed = true)
        every { view.context.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        mockkStatic(android.content.ClipData::class)
        every { android.content.ClipData.newPlainText("", "") } returns mockk()
        every { view.context.getString(R.string.system_clipboard_cleared) } returns "System clipboard cleared"
        view.onCustomShortSwipe(commandMapping("clear_clipboard"))
        verify(exactly = 1) {
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(any())
        }
        verify(exactly = 1) { service.showSuggestionBarMessage("System clipboard cleared") }
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        verify(exactly = 0) { inputConnection.performContextMenuAction(any()) }
        verify(exactly = 0) { inputConnection.deleteSurroundingText(any(), any()) }
    }

    @Test
    fun clearClipboardOrdinaryKeyStillWorksDuringInlineEditing() {
        val text = apostropheEditor("Bowie ", autoSpacePending = true)
        val context = mockk<Context>(relaxed = true)
        val cm = mockk<android.content.ClipboardManager>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        every { context.getString(R.string.system_clipboard_cleared) } returns "System clipboard cleared"
        every { text.receiver.getContext() } returns context
        every { text.receiver.isClipboardEditMode() } returns true
        mockkStatic(android.content.ClipData::class)
        every { android.content.ClipData.newPlainText("", "") } returns mockk()
        Config.globalConfig().handler!!.key_up(
            KeyValue.getKeyByName("clear_clipboard"), Pointers.Modifiers.EMPTY, false
        )
        verify(exactly = 1) {
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(any())
        }
        verify(exactly = 1) { text.receiver.showPrivateCopyFeedback("System clipboard cleared") }
        verify(exactly = 0) { text.receiver.selectAllClipboardEdit() }
        verify(exactly = 0) { text.receiver.backspaceClipboardEdit() }
        assertEquals("Bowie ", text.editor.toString())
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
        every { PredictionContextTracker.currentSelection(inputConnection) } answers {
            editor.length to editor.length
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

    private fun explicitActionHandler(): Config.IKeyEventHandler {
        val handler = mockk<Config.IKeyEventHandler>(relaxed = true)
        view.setField("_config", mockk<Config>(relaxed = true).apply { setField("handler", handler) })
        return handler
    }
    private fun assertTemplateDispatch(accepted: Boolean) {
        val handler = explicitActionHandler()
        every { handler.execute_template("[{selection}]{cursor}") } returns accepted
        view.onCustomShortSwipe(ShortSwipeMapping("a", SwipeDirection.N, "wrap", ActionType.TEMPLATE, "[{selection}]{cursor}"))
        verify(exactly = 1) { handler.execute_template("[{selection}]{cursor}") }
        verify(exactly = if (accepted) 1 else 0) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        verify(exactly = 0) { executor.execute(any(), any(), any()) }
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
        verify(exactly = 0) { service.triggerKeyboardEvent(any()) }
    }
    @Test fun templateUsesSharedHandlerAndOnlyOneSuccessHaptic() = assertTemplateDispatch(true)
    @Test fun rejectedTemplateNeverFallsBackToLiteralPayload() = assertTemplateDispatch(false)
    private fun assertSuffixDispatch(accepted: Boolean) {
        val handler = explicitActionHandler()
        every { handler.execute_suffix(any()) } returns accepted
        view.onCustomShortSwipe(commandMapping("append_possessive"))
        view.onCustomShortSwipe(commandMapping("append_apostrophe"))
        verify(exactly = 1) { handler.execute_suffix("'s") }
        verify(exactly = 1) { handler.execute_suffix("'") }
        verify(exactly = if (accepted) 2 else 0) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        verify(exactly = 0) { executor.execute(any(), any(), any()) }
        verify(exactly = 0) { inputConnection.commitText(any(), any()) }
    }
    @Test fun suffixCommandsUseSharedHandlerAndSuccessHaptic() = assertSuffixDispatch(true)
    @Test fun rejectedSuffixCommandsNeverFallBackToRawEditorWrites() = assertSuffixDispatch(false)

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
