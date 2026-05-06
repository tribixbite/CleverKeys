package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the URL handling subsection of the Clipboard
 * settings (Chunk 4 of clipboard URL sanitization feature).
 *
 * Verifies:
 *   1. Subsection title "URL handling" is reachable after expanding Clipboard.
 *   2. All three switches render with their canonical labels.
 *   3. The "Browse..." button stays hidden until the third toggle (custom rules)
 *      is enabled — gating prevents accidental SAF picks.
 *   4. The scope-note disclosure ("only applies to copies made via CleverKeys")
 *      is always visible to set user expectations.
 *
 * Section title uses substring matching because it carries an emoji prefix
 * ("📋 Clipboard"); switch labels are exact strings.
 */
@RunWith(AndroidJUnit4::class)
class UrlSanitizationSettingsComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun urlHandling_subsectionVisibleAfterExpandingClipboard() {
        composeRule.onNodeWithText("Clipboard", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("URL handling")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun threeSwitches_allDefaultOff() {
        composeRule.onNodeWithText("Clipboard", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sanitize tracking parameters")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Enrich embeds for sharing")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Use custom rules")
            .performScrollTo()
            .assertIsDisplayed()
        // Switches default OFF — verified indirectly: Browse button (which only
        // appears when "Use custom rules" is on) must not exist yet.
        composeRule.onNodeWithText("Browse for custom.substitutions.json")
            .assertDoesNotExist()
    }

    @Test
    fun browseButton_hiddenUntilCustomRulesEnabled() {
        composeRule.onNodeWithText("Clipboard", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        // Initially hidden — the third toggle defaults OFF
        composeRule.onNodeWithText("Browse for custom.substitutions.json")
            .assertDoesNotExist()
        // Enable custom rules
        composeRule.onNodeWithText("Use custom rules")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        // Now visible
        composeRule.onNodeWithText("Browse", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun scopeNoteVisible() {
        composeRule.onNodeWithText("Clipboard", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("only applies to copies made via CleverKeys", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
