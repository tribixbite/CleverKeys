package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [RotationModifierCarry]: what the keyboard held at a rotation (a latched Ctrl, caps lock …) is
 * offered back to the same field's keyboard for [RotationModifierCarry.WINDOW_MS] after it, through
 * every reset the rotation causes (the IME's own restart, its configuration refresh and, in an app
 * that recreates its window, the new window's input start), and never otherwise: not after the
 * user touched the keyboard, not to another field or app, not once the window has passed
 * (maintainer report 2026-11-22: Shift and Ctrl were lost on rotation). Pure tier.
 */
class RotationModifierCarryTest {

    private val chat = EditorRef("com.example.chat", fieldId = 2131230900, typeNull = false)
    private val chatOtherField = EditorRef("com.example.chat", fieldId = 2131230901, typeNull = false)
    private val chatFallback = EditorRef("com.example.chat", fieldId = -1, typeNull = true)
    private val other = EditorRef("com.example.other", fieldId = 3, typeNull = false)

    private fun armed(held: String? = "ctrl+caps") =
        RotationModifierCarry<String>().apply { onOrientationChanged(chat, held, nowMs = 0) }

    @Test
    fun theSameFieldGetsWhatWasHeldThroughEveryResetInsideTheWindow() {
        val carry = armed()
        assertThat(carry.heldFor(chat, nowMs = 5)).isEqualTo("ctrl+caps")
        assertWithMessage("the configuration refresh's reset right after the restart")
            .that(carry.heldFor(chat, nowMs = 6)).isEqualTo("ctrl+caps")
        assertWithMessage("an app that recreates its window starts input again later")
            .that(carry.heldFor(chat, nowMs = 400)).isEqualTo("ctrl+caps")
        assertThat(carry.isArmed).isTrue()
    }

    @Test
    fun theWindowEnds() {
        val carry = armed()
        assertThat(carry.heldFor(chat, nowMs = RotationModifierCarry.WINDOW_MS + 1)).isNull()
        assertThat(carry.isArmed).isFalse()
        assertWithMessage("once ended it stays ended")
            .that(carry.heldFor(chat, nowMs = 10)).isNull()
    }

    @Test
    fun nothingHeldOrNoEditorArmsNothing() {
        assertThat(armed(held = null).isArmed).isFalse()
        val noEditor = RotationModifierCarry<String>().apply { onOrientationChanged(null, "ctrl", nowMs = 0) }
        assertThat(noEditor.isArmed).isFalse()
        assertThat(noEditor.heldFor(chat, nowMs = 5)).isNull()
    }

    @Test
    fun aTouchOnTheKeyboardEndsTheCarry() {
        // The user typed on the keyboard shown after the rotation: what they see is what they get.
        // A later restart (the app's relaunch) must not bring back a Ctrl they already used.
        val carry = armed()
        assertThat(carry.heldFor(chat, nowMs = 5)).isEqualTo("ctrl+caps")
        carry.onUserInput()
        assertThat(carry.isArmed).isFalse()
        assertThat(carry.heldFor(chat, nowMs = 300)).isNull()
    }

    @Test
    fun anotherFieldGetsNothingButTheRotatedFieldStillDoes() {
        // A recreated window can pass through the platform's fallback connection (no editor) or
        // focus another field first; neither gets the modifiers, and neither ends the carry.
        val carry = armed()
        assertThat(carry.heldFor(chatFallback, nowMs = 100)).isNull()
        assertThat(carry.heldFor(chatOtherField, nowMs = 150)).isNull()
        assertThat(carry.heldFor(null, nowMs = 160)).isNull()
        assertThat(carry.heldFor(chat, nowMs = 300)).isEqualTo("ctrl+caps")
    }

    @Test
    fun inputInAnotherAppEndsTheCarry() {
        val carry = armed()
        carry.onStartInput(chat.packageName)
        assertWithMessage("input starting again in the same app keeps it")
            .that(carry.isArmed).isTrue()
        carry.onStartInput(other.packageName)
        assertThat(carry.isArmed).isFalse()
        assertThat(carry.heldFor(other, nowMs = 100)).isNull()
        assertThat(carry.heldFor(chat, nowMs = 100)).isNull()
    }

    @Test
    fun aNewRotationReplacesTheEarlierCarry() {
        val carry = armed(held = "ctrl")
        carry.onOrientationChanged(chat, "caps", nowMs = 1_500)
        assertThat(carry.heldFor(chat, nowMs = 1_600)).isEqualTo("caps")
        assertWithMessage("the window runs from the latest rotation")
            .that(carry.heldFor(chat, nowMs = RotationModifierCarry.WINDOW_MS + 1_000)).isEqualTo("caps")
        carry.onOrientationChanged(chat, null, nowMs = 2_000)
        assertWithMessage("a rotation with nothing held clears the earlier one")
            .that(carry.isArmed).isFalse()
    }

    @Test
    fun cancelEndsTheCarry() {
        val carry = armed()
        carry.cancel()
        assertThat(carry.heldFor(chat, nowMs = 5)).isNull()
    }
}
