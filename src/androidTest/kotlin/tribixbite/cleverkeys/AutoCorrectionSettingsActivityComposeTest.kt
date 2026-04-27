package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for AutoCorrectionSettingsActivity.
 *
 * Covers:
 *  - Activity launches without crash
 *  - "Auto-Correction Settings" top bar
 *  - Master Enable Auto-Correction switch present
 *  - About card with explanatory text
 *  - When enabled, Correction Parameters card with three sliders:
 *      • Minimum Word Length
 *      • Character Match Threshold
 *      • Minimum Frequency
 *  - Reset to Defaults action visible
 *  - Back navigation icon present
 *
 * Note: tests assume autocorrect-enabled is the persisted state for the
 * device. The "Settings (only visible when enabled)" branch is the richer
 * UI surface and is what most users see.
 */
@RunWith(AndroidJUnit4::class)
class AutoCorrectionSettingsActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<AutoCorrectionSettingsActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Auto-Correction Settings", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun backNavigation_iconPresent() {
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun masterSwitch_enableAutoCorrection_present() {
        composeTestRule.onNodeWithText("Enable Auto-Correction", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun aboutCard_present() {
        composeTestRule.onNodeWithText("About Auto-Correction", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aboutCard_mentionsLevenshtein() {
        composeTestRule.onNodeWithText("Levenshtein", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun correctionParameters_headingPresent() {
        composeTestRule.onNodeWithText("Correction Parameters", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun minWordLengthSlider_present() {
        composeTestRule.onNodeWithText("Minimum Word Length", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun charMatchThresholdSlider_present() {
        composeTestRule.onNodeWithText("Character Match Threshold", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun minFrequencySlider_present() {
        composeTestRule.onNodeWithText("Minimum Frequency", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun resetToDefaults_buttonPresent() {
        composeTestRule.onNodeWithText("Reset to Defaults", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun resetToDefaults_isClickable() {
        composeTestRule.onNodeWithText("Reset to Defaults", substring = true)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
    }
}
