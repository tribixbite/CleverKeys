package tribixbite.cleverkeys

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Issue93ThemeHexInputComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ThemeSettingsActivity>()

    @Test
    fun themeSettings_hasHexInputField() {
        composeTestRule.onNodeWithContentDescription("Hex color input").assertExists()
    }

    @Test
    fun themeSettings_hexInputAcceptsRRGGBB() {
        val field = composeTestRule.onNodeWithText("#", substring = true)
        field.assertExists()
        field.performTextInput("FF0000")
    }
}
