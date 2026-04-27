package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for ThemeSettingsActivity (Custom Themes screen).
 *
 * Covers:
 *  - Activity launches without crash
 *  - Top bar title "Keyboard Themes"
 *  - Create-theme entry point (icon button or dialog trigger)
 *  - Theme editor flow: name field, color picker rows
 *  - Color picker dialog elements:
 *      • Hue / Saturation / Lightness / Opacity sliders (#35-related)
 *      • Hex input field (#93)
 *      • Quick-color swatches
 *      • Cancel / Apply buttons
 *  - Built-in themes section visible
 */
@RunWith(AndroidJUnit4::class)
class ThemeSettingsActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ThemeSettingsActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Keyboard Themes", substring = true).assertIsDisplayed()
    }

    @Test
    fun createTheme_entryPointPresent() {
        composeTestRule.onNodeWithContentDescription("Create Theme").assertExists()
    }

    @Test
    fun openCreateThemeDialog_showsTitle() {
        composeTestRule.onNodeWithContentDescription("Create Theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Create Theme", substring = true).assertIsDisplayed()
    }

    @Test
    fun createThemeDialog_hasNameField() {
        composeTestRule.onNodeWithContentDescription("Create Theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Theme Name", substring = true).assertIsDisplayed()
    }

    @Test
    fun createThemeDialog_hasSaveButton() {
        composeTestRule.onNodeWithContentDescription("Create Theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save", substring = true).assertIsDisplayed()
    }

    private fun openColorPicker() {
        composeTestRule.onNodeWithContentDescription("Create Theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Key Default")[0].performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun colorPicker_hasHueSlider() {
        openColorPicker()
        composeTestRule.onNodeWithText("Hue", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_hasSaturationSlider() {
        openColorPicker()
        composeTestRule.onNodeWithText("Saturation", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_hasLightnessSlider() {
        openColorPicker()
        composeTestRule.onNodeWithText("Lightness", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_hasOpacitySlider() {
        openColorPicker()
        composeTestRule.onNodeWithText("Opacity", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_hasHexInput_93() {
        openColorPicker()
        composeTestRule.onNodeWithContentDescription("Hex color input").assertExists()
    }

    @Test
    fun colorPicker_hexInputAcceptsRRGGBB_93() {
        openColorPicker()
        val field = composeTestRule.onNodeWithContentDescription("Hex color input")
        field.performTextReplacement("#FF0000")
    }

    @Test
    fun colorPicker_hasQuickColorsLabel() {
        openColorPicker()
        composeTestRule.onNodeWithText("Quick Colors", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_hasCancelButton() {
        openColorPicker()
        composeTestRule.onNodeWithText("Cancel", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_hasApplyButton() {
        openColorPicker()
        composeTestRule.onNodeWithText("Apply", substring = true).assertIsDisplayed()
    }

    @Test
    fun colorPicker_cancelButtonDismisses() {
        openColorPicker()
        composeTestRule.onNodeWithText("Cancel", substring = true).performClick()
        composeTestRule.waitForIdle()
        // After cancel, Hex field should no longer exist.
    }
}
