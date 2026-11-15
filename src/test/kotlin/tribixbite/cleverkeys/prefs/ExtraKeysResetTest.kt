package tribixbite.cleverkeys.prefs

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Test

/**
 * Review 2026-11-14 M25: Extra Keys "Reset to defaults" wrote every one of the ~131 keys with its
 * own `apply()` (on a fresh install each was a new key, so the keyboard rebuilt its Config once
 * per key), asked nothing first and could not be undone.
 */
class ExtraKeysResetTest {

    private val activity = File("src/main/kotlin/tribixbite/cleverkeys/activities/ExtraKeysConfigActivity.kt")

    /**
     * Wiring (source scan: the Compose screen cannot be driven on the JVM). Covers: no per-key
     * write loop, a confirmation dialog, one batched write, Undo in a snackbar. Not covered: the
     * dialog text or the snackbar timing.
     */
    @Test
    fun resetAsksFirstWritesOnceAndOffersUndo() {
        val src = activity.readText()
        assertWithMessage("the per-key apply() loop must be gone")
            .that(src.contains("prefs.edit().putBoolean(prefKey, enabledKeys[keyName] ?: false).apply()")).isFalse()
        assertWithMessage("reset must ask first").that(src.contains("R.string.extra_keys_reset_title")).isTrue()
        assertWithMessage("the reset must write through ExtraKeysReset.write")
            .that(src.contains("ExtraKeysReset.write(")).isTrue()
        assertWithMessage("Undo must write the plan's previous values back")
            .that(src.contains("plan.undo")).isTrue()
        assertWithMessage("reset must offer Undo").that(src.contains("showSnackbar(")).isTrue()
    }

    // ------------------------------------------------------------------ behaviour

    private val defaults = mapOf("a" to true, "b" to false, "c" to true)
    private fun defaultOf(key: String) = defaults.getValue(key)

    @Test
    fun onlyKeysThatDifferFromTheirDefaultAreReset() {
        val current = mapOf("a" to false, "b" to false, "c" to true)
        val plan = ExtraKeysReset.plan(current, defaults.keys, ::defaultOf)
        assertThat(plan.reset).containsExactly("a", true)
        // Undo puts back exactly what the reset changed.
        assertThat(plan.undo).containsExactly("a", false)
    }

    @Test
    fun nothingToReset_givesAnEmptyPlan() {
        val plan = ExtraKeysReset.plan(defaults, defaults.keys, ::defaultOf)
        assertThat(plan.reset).isEmpty()
        assertThat(plan.undo).isEmpty()
    }

    /** A key absent from the screen's map reads as off (as the checkbox shows it). */
    @Test
    fun aKeyMissingFromTheMap_readsAsOff() {
        val plan = ExtraKeysReset.plan(emptyMap(), defaults.keys, ::defaultOf)
        assertThat(plan.reset).containsExactly("a", true, "c", true)
        assertThat(plan.undo).containsExactly("a", false, "c", false)
    }

    @Test
    fun writeUsesOneEditorAndOneApply() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences> { every { edit() } returns editor }

        ExtraKeysReset.write(prefs, mapOf("a" to true, "c" to false)) { "extra_key_$it" }

        verify(exactly = 1) { prefs.edit() }
        verify(exactly = 1) { editor.putBoolean("extra_key_a", true) }
        verify(exactly = 1) { editor.putBoolean("extra_key_c", false) }
        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun writingNothing_touchesNoPrefs() {
        val prefs = mockk<SharedPreferences>()
        ExtraKeysReset.write(prefs, emptyMap()) { it }
        verify(exactly = 0) { prefs.edit() }
    }
}
