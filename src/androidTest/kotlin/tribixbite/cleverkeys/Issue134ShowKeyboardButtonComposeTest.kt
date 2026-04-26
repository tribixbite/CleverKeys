package tribixbite.cleverkeys

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Issue134ShowKeyboardButtonComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ShortSwipeCustomizationActivity>()

    @Test
    fun customizationScreen_hasShowKeyboardButton() {
        composeTestRule.onNodeWithContentDescription("Show keyboard").assertExists()
    }

    @Test
    fun showKeyboardButton_isClickable() {
        composeTestRule.onNodeWithContentDescription("Show keyboard").performClick()
    }
}
