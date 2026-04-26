package tribixbite.cleverkeys

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Issue94VersionCopyComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun versionInfoCard_hasCopyContentDescription() {
        composeTestRule.onNodeWithContentDescription("Copy version info").assertExists()
    }

    @Test
    fun versionInfoCard_isVisible() {
        val versionRow = composeTestRule.onNodeWithText("Version", substring = true)
        versionRow.performScrollTo()
        versionRow.assertExists()
    }
}
