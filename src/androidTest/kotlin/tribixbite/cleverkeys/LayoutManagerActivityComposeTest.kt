package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for LayoutManagerActivity.
 *
 * Smoke + structural checks. The "Add Layout" extended FAB cannot be
 * reliably clicked from compose-test due to inset/positioning issues at
 * the orchestrator's test resolution — its dialog tabs (System / Predefined
 * / Custom) are exercised manually + via the underlying state model in
 * other tests. Here we just verify the entry-points exist.
 */
@RunWith(AndroidJUnit4::class)
class LayoutManagerActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<LayoutManagerActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Keyboard Layouts", substring = true).assertIsDisplayed()
    }

    @Test
    fun addLayout_fabPresent() {
        // ExtendedFAB merges its Text into the FAB's onClick semantics — the
        // standalone Text isn't reachable. Use the Icon's contentDescription
        // which is always exposed regardless of merge strategy.
        composeTestRule.onNodeWithContentDescription("Add Layout").assertExists()
    }
}
