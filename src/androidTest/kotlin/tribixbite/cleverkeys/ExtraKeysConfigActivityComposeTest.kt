package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
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
    fun clearSystemClipboardIsVisibleAndOptIn() {
        val search = composeTestRule.onNode(hasSetTextAction())
        search.performTextInput("clear_clipboard")
        composeTestRule.onNodeWithText("Editing", substring = false).assertExists()
        // Scroll by the stable key identifier: a recycled row can expose its new key ID
        // while incorrectly retaining the old key's title and description.
        composeTestRule.onNode(hasText("clear_clipboard") and !hasSetTextAction(), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(hasText("Clear system clipboard") and !hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeTestRule.onNode(isToggleable()).assertIsOff()

        // A user can search the displayed label, not only the internal identifier.
        search.performTextClearance()
        search.performTextInput("Clear system clipboard")
        composeTestRule.onNode(hasText("clear_clipboard") and !hasSetTextAction(), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(hasText("Clear system clipboard") and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun resetToDefaults_buttonReachable() {
        // ExtraKeysConfig has no verticalScroll wrapper — assertExists, not scrollTo.
        composeTestRule.onNodeWithText("Reset to Defaults", substring = true).assertExists()
    }

    @Test
    fun autofillIsVisibleInSystemCategoryWithoutChangingItsPreference() {
        composeTestRule.onNode(hasSetTextAction()).performTextInput("autofill")
        composeTestRule.onNodeWithText("System", substring = true).assertExists()
        composeTestRule.onNode(hasText("autofill") and !hasSetTextAction(), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(hasText("Autofill") and !hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onAllNodes(isToggleable()).assertCountEquals(1)
    }
}
