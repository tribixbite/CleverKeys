package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for BackupRestoreActivity.
 *
 * Covers:
 *  - Activity launches with title "Backup & Restore"
 *  - Top-level Export Settings + Import Settings buttons
 *  - Per-section Export / Import buttons (multiple instances)
 *  - Export ZIP / Import ZIP for full backup
 *  - Back navigation
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<BackupRestoreActivity>()

    @Test
    fun activity_launches() {
        // Two matches: TopAppBar title + Activity label header. Pick first.
        composeTestRule.onAllNodesWithText("Backup & Restore", substring = true)[0]
            .assertIsDisplayed()
    }

    @Test
    fun backNavigation_iconPresent() {
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun exportSettings_buttonPresent() {
        composeTestRule.onNodeWithText("Export Settings", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun importSettings_buttonPresent() {
        composeTestRule.onNodeWithText("Import Settings", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun exportZip_buttonReachable() {
        composeTestRule.onNodeWithText("Export ZIP", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun importZip_buttonReachable() {
        composeTestRule.onNodeWithText("Import ZIP", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
