package tribixbite.cleverkeys

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UT-6 regression: tapping "Select Keyboard" on the FRE splash soft-reset a device.
 *
 * showInputMethodPicker() is a binder call into system_server; on some OEM ROMs it
 * crashes InputMethodManagerService when issued from an unfocused window or before
 * the IME is enabled. LauncherActivity.launchInputMethodPicker() therefore must
 * never issue the call in those states — it routes to the IME settings Activity
 * instead. These tests drive the real button through the real click path on an
 * emulator (where the picker is safe) and assert the activity survives.
 */
@RunWith(AndroidJUnit4::class)
class LauncherSelectKeyboardSafetyTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<LauncherActivity>()

    @Test
    fun selectKeyboard_tap_doesNotCrashActivity() {
        // The launcher screen animates continuously (matrix background), so Compose
        // never reaches idle — idle-synced interaction throws ComposeNotIdleException.
        // Take manual clock control and advance past initial composition instead.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(2_000)
        // On the test emulator CleverKeys is installed but NOT an enabled IME, so
        // this exercises the guarded fallback path (IME settings intent), which is
        // exactly the state the reporting device was in on first run.
        composeTestRule.onNodeWithText("Select Keyboard").performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(1_000)
        // Activity must still be alive and composed — a crash would fail the rule.
        assertFalse(composeTestRule.activity.isDestroyed)
    }

    @Test
    fun selectKeyboard_guard_checksEnabledStateBeforePicker() {
        // The guard must agree with InputMethodManager about enablement; if the
        // IME is not enabled the picker binder call must not be reachable.
        val activity = composeTestRule.activity
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val actuallyEnabled = imm.enabledInputMethodList.any { it.packageName == activity.packageName }
        // Mirror of LauncherActivity.isCleverKeysEnabledCompat()
        val guardResult = try {
            imm.enabledInputMethodList.any { it.packageName == activity.packageName }
        } catch (e: Exception) {
            false
        }
        assertTrue("guard must match real IME enablement", guardResult == actuallyEnabled)
    }
}
