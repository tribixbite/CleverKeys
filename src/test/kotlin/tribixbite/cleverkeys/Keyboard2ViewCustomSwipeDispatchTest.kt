package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
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
 * executor is a mock that declines (exactly what the production executor does for these
 * commands); the service records dispatches.
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
