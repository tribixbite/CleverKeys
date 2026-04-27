package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for ExtraKeysConfigActivity.
 *
 * Covers:
 *  - Activity launches with title "Extra Keys Configuration"
 *  - Search field with placeholder "Search extra keys..."
 *  - Reset to Defaults button reachable
 *  - Search field accepts text
 */
@RunWith(AndroidJUnit4::class)
class ExtraKeysConfigActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ExtraKeysConfigActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Extra Keys Configuration", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun searchField_present() {
        composeTestRule.onNodeWithText("Search extra keys", substring = true).assertIsDisplayed()
    }

    @Test
    fun searchField_acceptsInput() {
        composeTestRule.onNodeWithText("Search extra keys", substring = true)
            .performTextInput("emoji")
        composeTestRule.waitForIdle()
    }

    @Test
    fun resetToDefaults_buttonReachable() {
        // ExtraKeysConfig has no verticalScroll wrapper — assertExists, not scrollTo.
        composeTestRule.onNodeWithText("Reset to Defaults", substring = true).assertExists()
    }
}
