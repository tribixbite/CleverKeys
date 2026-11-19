package tribixbite.cleverkeys

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device pass 2026-11-18: rotating the launcher screen (the one with the test field) recreated
 * the Activity, so the field lost focus, its text and the keyboard. The launcher is a single
 * Compose screen that reads its size from `LocalConfiguration`, so it handles a size change
 * in place (`android:configChanges` in the manifest) and the focused field, its text and the
 * shown keyboard simply stay.
 *
 * The keyboard's own visibility is not asserted: emulator images differ in whether a soft
 * keyboard shows at all. What the keyboard depends on — the same window and the same focused
 * field across the rotation — is.
 */
@RunWith(AndroidJUnit4::class)
class LauncherRotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<LauncherActivity>()

    private fun currentActivity(): Activity {
        var activity: Activity? = null
        composeTestRule.activityRule.scenario.onActivity { activity = it }
        return activity!!
    }

    @Test
    fun rotatingKeepsTheActivityTheFocusedTestFieldAndItsText() {
        // The sparkle background animates forever, so Compose never idles: drive the clock.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(2_000)

        val field = composeTestRule.onNode(hasSetTextAction())
        field.performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(500)
        field.performTextInput("rotate me")
        composeTestRule.mainClock.advanceTimeBy(500)
        field.assertIsFocused()

        val before = currentActivity()
        val startOrientation = before.resources.configuration.orientation
        val target = if (startOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val expected = if (target == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        try {
            composeTestRule.activityRule.scenario.onActivity { it.requestedOrientation = target }
            // Wait for the rotation to land (or for a recreated Activity to report it).
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                currentActivity().resources.configuration.orientation != expected) {
                Thread.sleep(100)
            }
            assertEquals("the rotation must have happened", expected,
                currentActivity().resources.configuration.orientation)
            composeTestRule.mainClock.advanceTimeBy(1_000)

            assertSame("rotation must not recreate the launcher", before, currentActivity())
            val after = composeTestRule.onNode(hasSetTextAction())
            after.assertIsFocused()
            after.assertTextContains("rotate me")
        } finally {
            composeTestRule.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
