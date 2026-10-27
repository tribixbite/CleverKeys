package tribixbite.cleverkeys

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saga 2026-10-27: in landscape, Layout Manager's Add Layout dialog showed two entries of the
 * Predefined list and the rest could not be reached. Rotates the device to landscape, opens the
 * dialog, and requires (a) at least four entries on screen at once and (b) the LAST predefined
 * layout reachable by finger swipes on the list (touch input, not a semantics scroll, which
 * would reach it even through a two-row window).
 */
@RunWith(AndroidJUnit4::class)
class LayoutManagerLandscapeAddLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<LayoutManagerActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun displayed(text: String): Boolean =
        runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess

    @Test
    fun predefinedListShowsSeveralEntriesAndScrollsToTheLastOneInLandscape() {
        val res = instrumentation.targetContext.resources
        val names = res.getStringArray(R.array.pref_layout_values).toList()
        val labels = res.getStringArray(R.array.pref_layout_entries).toList()
        val predefined = names.indices.filter { names[it] != "system" && names[it] != "custom" }.map { labels[it] }
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Add Layout").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNode(hasText("Predefined") and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            val onScreen = predefined.take(6).count(::displayed)
            assertTrue("at least four predefined layouts on screen in landscape, saw $onScreen", onScreen >= 4)
            // The dialog's list (the Layout Manager list behind it scrolls too).
            val list = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            var swipes = 0
            while (!displayed(predefined.last()) && swipes < 120) {
                list.performTouchInput { swipeUp() }
                compose.waitForIdle()
                swipes++
            }
            assertTrue("the last predefined layout '${predefined.last()}' is reachable by swiping ($swipes swipes)",
                displayed(predefined.last()))
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
