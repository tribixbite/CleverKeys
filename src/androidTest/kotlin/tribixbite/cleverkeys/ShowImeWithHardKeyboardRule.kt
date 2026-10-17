package tribixbite.cleverkeys

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Lets the soft keyboard show while the device reports a hardware keyboard, for tests that need
 * the real CleverKeys input view on screen, and restores the exact prior value afterwards.
 *
 * Why (2026-10-17): the emulator.wtf Pixel7 images report `keyboard=qwerty` on API 35, 36 and 37.
 * AOSP's default `InputMethodService.onEvaluateInputViewShown()` returns false when a hardware
 * keyboard is configured unless Settings.Secure `show_ime_with_hard_keyboard` is on, and
 * `onShowInputRequested()` then refuses every show request (ImeTracker logs
 * `onFailed at PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE`). The API 35 image showed the IME and the 36/37
 * images did not, so every test that waits for `isInputViewShown` timed out there. This is
 * platform-standard behaviour shared by every IME, not a CleverKeys bug; a real phone without
 * a physical keyboard reports `keyboard=nokeys` and is unaffected.
 *
 * Uses the shell uid through UiAutomation, which may write secure settings. Each command's output
 * is read to EOF so the write has completed before the test body runs.
 */
class ShowImeWithHardKeyboardRule : ExternalResource() {

    private var original: String? = null

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
    }

    override fun before() {
        // "null" is what `settings get` prints for a key that has never been written.
        original = shell("settings get secure $KEY")
        Log.i(TAG, "$KEY was '$original'; forcing 1 for this test")
        shell("settings put secure $KEY 1")
    }

    override fun after() {
        when (val prior = original) {
            null -> return
            "null" -> shell("settings delete secure $KEY")
            else -> shell("settings put secure $KEY $prior")
        }
        Log.i(TAG, "$KEY restored to '$original'")
    }

    private companion object {
        const val KEY = "show_ime_with_hard_keyboard"
        const val TAG = "ShowImeWithHardKeyboard"
    }
}
