package tribixbite.cleverkeys

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Issue93ThemeHexInputComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ThemeSettingsActivity>()

    /**
     * The hex input only renders inside ColorPickerDialog, which itself only
     * shows after entering the theme editor + tapping a ColorAttributeRow.
     * Navigation: Themes screen → "Create Theme" → editor → "Key Default" row.
     */
    private fun openColorPicker() {
        composeTestRule.onNodeWithContentDescription("Create Theme").performClick()
        composeTestRule.waitForIdle()
        // Tap the first "Key Default" row to open the color picker dialog
        composeTestRule.onAllNodesWithText("Key Default")[0].performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun themeSettings_hasHexInputField() {
        openColorPicker()
        composeTestRule.onNodeWithContentDescription("Hex color input").assertExists()
    }

    @Test
    fun themeSettings_hexInputAcceptsRRGGBB() {
        openColorPicker()
        val field = composeTestRule.onNodeWithContentDescription("Hex color input")
        field.assertExists()
        field.performTextReplacement("#FF0000")
    }
}
