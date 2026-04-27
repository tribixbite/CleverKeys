package tribixbite.cleverkeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for NeuralSettingsActivity (advanced gesture-typing tuning).
 *
 * Covers:
 *  - Activity launches without crash
 *  - Header "Gesture Typing Settings"
 *  - Five parameter sections present:
 *      • Core Parameters
 *      • Advanced Beam Search
 *      • Inference Tuning
 *      • Model Configuration
 *      • Runtime Configuration
 *  - Specific sliders by name:
 *      • Beam Width, Max Sequence Length, Confidence Threshold
 *      • Length Penalty (Alpha), Pruning Confidence, Score Gap Threshold
 *      • Width Pruning Step, Early Stop Step
 *      • Temperature, Vocabulary Frequency Weight, Touch Smoothing
 *      • ONNX Threads
 *  - Toggles: Batch Processing, Greedy Search
 *  - Cancel + Save & Exit buttons
 */
@RunWith(AndroidJUnit4::class)
class NeuralSettingsActivityComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<NeuralSettingsActivity>()

    @Test
    fun activity_launches() {
        composeTestRule.onNodeWithText("Gesture Typing Settings", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun coreParameters_sectionPresent() {
        composeTestRule.onNodeWithText("Core Parameters", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun beamWidthSlider_present() {
        composeTestRule.onNodeWithText("Beam Width", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun maxSequenceLengthSlider_present() {
        composeTestRule.onNodeWithText("Max Sequence Length", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun confidenceThresholdSlider_present() {
        composeTestRule.onNodeWithText("Confidence Threshold", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun advancedBeamSearch_sectionPresent() {
        composeTestRule.onNodeWithText("Advanced Beam Search", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun lengthPenaltySlider_present() {
        composeTestRule.onNodeWithText("Length Penalty", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun pruningConfidenceSlider_present() {
        composeTestRule.onNodeWithText("Pruning Confidence", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun scoreGapThresholdSlider_present() {
        composeTestRule.onNodeWithText("Score Gap Threshold", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun widthPruningStepSlider_present() {
        composeTestRule.onNodeWithText("Width Pruning Step", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun earlyStopStepSlider_present() {
        composeTestRule.onNodeWithText("Early Stop Step", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun inferenceTuning_sectionPresent() {
        composeTestRule.onNodeWithText("Inference Tuning", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun temperatureSlider_present() {
        composeTestRule.onNodeWithText("Temperature", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun frequencyWeightSlider_present() {
        composeTestRule.onNodeWithText("Vocabulary Frequency Weight", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun touchSmoothingSlider_present() {
        composeTestRule.onNodeWithText("Touch Smoothing", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun modelConfiguration_sectionPresent() {
        composeTestRule.onNodeWithText("Model Configuration", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun trajectoryResamplingDropdown_present() {
        composeTestRule.onNodeWithText("Trajectory Resampling", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun runtimeConfiguration_sectionPresent() {
        composeTestRule.onNodeWithText("Runtime Configuration", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun batchProcessingToggle_present() {
        composeTestRule.onNodeWithText("Batch Processing", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun greedySearchToggle_present() {
        composeTestRule.onNodeWithText("Greedy Search", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun onnxThreadsSlider_present() {
        composeTestRule.onNodeWithText("ONNX Threads", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun cancelButton_present() {
        composeTestRule.onNodeWithText("Cancel", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun saveAndExitButton_present() {
        composeTestRule.onNodeWithText("Save & Exit", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
