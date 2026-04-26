package tribixbite.cleverkeys

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Issue94VersionCopyComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<SettingsActivity>()

    /**
     * Expand the "Information & Actions" CollapsibleSettingsSection so its
     * children (VersionInfoCard, etc.) enter the semantics tree. The section
     * header text contains "Information" — scroll to it and click to expand.
     */
    private fun expandInfoSection() {
        val header = composeTestRule.onNodeWithText("Information", substring = true)
        header.performScrollTo()
        header.performClick()
    }

    @Test
    fun versionInfoCard_hasCopyContentDescription() {
        expandInfoSection()
        composeTestRule.onNodeWithContentDescription("Copy version info").assertExists()
    }

    @Test
    fun versionInfoCard_isVisible() {
        expandInfoSection()
        val versionRow = composeTestRule.onNodeWithText("Version", substring = true)
        versionRow.performScrollTo()
        versionRow.assertExists()
    }
}
