package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * gh #134 residual: the ShortSwipeCustomizationActivity entry path showed the IME with
 * `imm.toggleSoftInput(SHOW_FORCED, 0)`. Toggle semantics HIDE an already-visible IME —
 * so entering the screen while a keyboard was up reproduced the reported "keyboard
 * disappeared" state at entry — and SHOW_FORCED leaks a forced-shown keyboard past the
 * activity (both deprecated since API 31, the reporter's Android 12).
 *
 * The #134 reopen button (6ff48751, verified on-device by
 * Issue134ShowKeyboardButtonComposeTest) already uses the correct call:
 * `focusRequester.requestFocus()` + `imm.showSoftInput(rootView, 0)`. This contract pins
 * the entry path to the same mechanism.
 *
 * Source-contract tier for the same reason as BucketBSourceContractTest: no
 * compose-ui-test dependency, and InputMethodManager is unreachable off-device.
 */
class Issue134ImeEntryContractTest {

    private val source = File(
        "src/main/kotlin/tribixbite/cleverkeys/activities/ShortSwipeCustomizationActivity.kt"
    ).also { require(it.exists()) { "source moved: ${it.absolutePath}" } }.readText()

    @Test
    fun `entry path must not toggle the IME`() {
        assertWithMessage(
            "#134: toggleSoftInput has toggle semantics — if an IME is already visible when " +
                "the customization screen opens, it HIDES it (the reported vanish, at entry). " +
                "Show the keyboard with showSoftInput, exactly like the #134 reopen button."
        ).that(source.contains("toggleSoftInput(")).isFalse() // call site, not prose
    }

    @Test
    fun `entry path must not force-show the IME`() {
        assertWithMessage(
            "#134: SHOW_FORCED keeps the IME forced-visible after the user leaves the " +
                "activity (deprecated API 33+). The entry path must not use it."
        ).that(source.contains("InputMethodManager.SHOW_FORCED")).isFalse() // constant ref, not prose
    }

    @Test
    fun `entry path and reopen button share the showSoftInput mechanism`() {
        // Two call sites: the entry LaunchedEffect and the #134 TopAppBar reopen button.
        val calls = Regex("""showSoftInput\(""").findAll(source).count()
        assertThat(calls).isAtLeast(2)
        // Both must focus the hidden capture field first, or showSoftInput is a no-op.
        val focusRequests = Regex("""focusRequester\.requestFocus\(\)""").findAll(source).count()
        assertThat(focusRequests).isAtLeast(2)
    }
}
