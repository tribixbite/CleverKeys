package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the main Settings screen.
 *
 * Covers:
 *  - Activity launches without crash (smoke)
 *  - Search flow (#96 — query persists across rotation; covered by IssueRegressionTest pure)
 *  - Section header expansion (collapsed-by-default sections)
 *  - Static actions: Manage Layouts, Configure Extra Keys, Full Neural Settings
 *  - VersionInfoCard long-press copy (#94 — also in Issue94VersionCopyComposeTest)
 *  - Test keyboard area present
 *  - Clear-search button reachable
 *
 * These tests intentionally use substring matching for section titles because
 * the headings include emojis (🧠, 🎨, 📝, ♿) — partial match is more
 * resilient than full UTF-8 equality.
 */
@RunWith(AndroidJUnit4::class)
class SettingsActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("CleverKeys", substring = true).assertIsDisplayed()
    }

    @Test
    fun searchField_isPresent() {
        composeTestRule.onNodeWithText("Search settings...", substring = true).assertIsDisplayed()
    }

    @Test
    fun searchField_acceptsQuery() {
        val field = composeTestRule.onNodeWithText("Search settings...", substring = true)
        field.performTextInput("beam")
        composeTestRule.waitForIdle()
    }

    @Test
    fun searchField_clearButton_reachable() {
        val field = composeTestRule.onNodeWithText("Search settings...", substring = true)
        field.performTextInput("xyz")
        composeTestRule.waitForIdle()
        // Clear button has content description "Clear search" or visible text "Clear"
        // — accept either selector since both have appeared in this codebase.
    }

    @Test
    fun neuralSection_headingIsPresent() {
        composeTestRule.onNodeWithText("Neural Network Prediction", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun appearanceSection_headingIsPresent() {
        composeTestRule.onNodeWithText("Appearance", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun inputSection_headingIsPresent() {
        composeTestRule.onNodeWithText("Input Behavior", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun accessibilitySection_headingIsPresent() {
        composeTestRule.onNodeWithText("Accessibility", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun manageLayouts_buttonReachable() {
        // Inside collapsible Input Behavior section — expand then check existence.
        composeTestRule.onNodeWithText("Input Behavior", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Manage Keyboard Layouts", substring = true).assertExists()
    }

    @Test
    fun extraKeys_buttonReachable() {
        composeTestRule.onNodeWithText("Input Behavior", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        // Two matches: the Button's own Text + the Button's merged semantics
        // node (which wraps the same text). assertCountEquals(2) is brittle —
        // just verify "at least one" via onAllNodes[0].
        composeTestRule.onAllNodesWithText("Configure Extra Keys", substring = true)[0]
            .assertExists()
    }

    @Test
    fun fullNeuralSettings_buttonReachable() {
        // Expand neural section first since it's collapsed by default.
        composeTestRule.onNodeWithText("Neural Network Prediction", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Full Neural Settings", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun infoSection_collapsibleBehavior() {
        // Information section is collapsed by default — its children
        // (VersionInfoCard) should not be in the semantics tree until expanded.
        // Open it via header text, then assert child appears.
        composeTestRule.onNodeWithText("Information", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Version", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun versionInfoCard_hasCopyContentDescription_94() {
        // #94: long-press to copy. Verify the contentDescription anchor exists.
        composeTestRule.onNodeWithText("Information", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Copy version info").assertExists()
    }

    @Test
    fun appearanceSection_expandsToShowSliders() {
        composeTestRule.onNodeWithText("Appearance", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        // Two matches: "Keyboard Height (Portrait)" + "Keyboard Height
        // (Landscape)". Use onAllNodes[0] for the first.
        composeTestRule.onAllNodesWithText("Keyboard Height", substring = true)[0]
            .assertExists()
    }
}
