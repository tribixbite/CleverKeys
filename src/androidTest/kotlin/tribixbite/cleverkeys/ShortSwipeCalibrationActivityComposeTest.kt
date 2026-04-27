package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for ShortSwipeCalibrationActivity.
 *
 * Smoke tests only — calibration involves live touch sensing that the
 * Compose test framework can't simulate accurately.
 */
@RunWith(AndroidJUnit4::class)
class ShortSwipeCalibrationActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ShortSwipeCalibrationActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Short Swipe Calibration", substring = true)
            .assertIsDisplayed()
    }
}
