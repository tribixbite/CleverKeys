package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for ShortSwipeCustomizationActivity.
 *
 * Covers:
 *  - Activity launches without crash
 *  - "Short Swipe Customization" title
 *  - #134: Show keyboard button (already covered separately, duplicated here for completeness)
 *  - Reset All icon button
 *  - Back navigation
 *  - Info card with instructional text + mapping count
 *  - "Type any key to customize it" prompt
 *  - Mappings counter mentions "commands available" — relies on CommandRegistry
 *
 * #129 (gesture editing broken): the actual editing flow requires a key event
 * to focus the hidden text field, which can't be triggered easily via
 * Compose UI alone — covered by JVM unit tests + manual testing.
 */
@RunWith(AndroidJUnit4::class)
class ShortSwipeCustomizationComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ShortSwipeCustomizationActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Short Swipe Customization", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun showKeyboardButton_present_134() {
        composeTestRule.onNodeWithContentDescription("Show keyboard").assertExists()
    }

    @Test
    fun resetAllButton_present() {
        composeTestRule.onNodeWithContentDescription("Reset All").assertExists()
    }

    @Test
    fun backNavigation_iconPresent() {
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun infoCard_promptsTapToCustomize() {
        composeTestRule.onNodeWithText("Tap any key", substring = true).assertIsDisplayed()
    }

    @Test
    fun infoCard_showsMappingCount() {
        composeTestRule.onNodeWithText("custom mappings", substring = true).assertIsDisplayed()
    }

    @Test
    fun infoCard_showsCommandsAvailable() {
        composeTestRule.onNodeWithText("commands available", substring = true).assertIsDisplayed()
    }

    @Test
    fun centerHint_typeAnyKey() {
        composeTestRule.onNodeWithText("Type any key", substring = true).assertIsDisplayed()
    }

    @Test
    fun centerHint_actualKeyboardExplanation() {
        composeTestRule.onNodeWithText("actual CleverKeys", substring = true).assertIsDisplayed()
    }
}
