package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for Config.Defaults values.
 *
 * Tests validate that default values are within expected ranges
 * and match documented behavior for v1.2.9 features.
 */
class ConfigDefaultsTest {

    // =========================================================================
    // ONNX XNNPACK Thread Settings (v1.2.9)
    // =========================================================================

    @Test
    fun `XNNPACK threads default is 2`() {
        assertThat(Defaults.ONNX_XNNPACK_THREADS).isEqualTo(2)
    }

    @Test
    fun `XNNPACK threads default is within valid range 1-8`() {
        assertThat(Defaults.ONNX_XNNPACK_THREADS).isAtLeast(1)
        assertThat(Defaults.ONNX_XNNPACK_THREADS).isAtMost(8)
    }

    // =========================================================================
    // Short Gesture Settings (v1.2.9)
    // =========================================================================

    @Test
    fun `short gestures enabled by default`() {
        assertThat(Defaults.SHORT_GESTURES_ENABLED).isTrue()
    }

    @Test
    fun `short gesture min distance default is 28`() {
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE).isEqualTo(28)
    }

    @Test
    fun `short gesture max distance default is 141`() {
        assertThat(Defaults.SHORT_GESTURE_MAX_DISTANCE).isEqualTo(141)
    }

    @Test
    fun `short gesture min is less than max`() {
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE)
            .isLessThan(Defaults.SHORT_GESTURE_MAX_DISTANCE)
    }

    @Test
    fun `short gesture min distance is within valid range 10-95`() {
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE).isAtLeast(10)
        assertThat(Defaults.SHORT_GESTURE_MIN_DISTANCE).isAtMost(95)
    }

    @Test
    fun `short gesture max distance is within valid range 50-200`() {
        assertThat(Defaults.SHORT_GESTURE_MAX_DISTANCE).isAtLeast(50)
        assertThat(Defaults.SHORT_GESTURE_MAX_DISTANCE).isAtMost(200)
    }

    // =========================================================================
    // Neural Prediction Defaults
    // =========================================================================

    @Test
    fun `neural beam width default is 6`() {
        assertThat(Defaults.NEURAL_BEAM_WIDTH).isEqualTo(6)
    }

    @Test
    fun `neural max length default is 20`() {
        assertThat(Defaults.NEURAL_MAX_LENGTH).isEqualTo(20)
    }

    @Test
    fun `neural batch beams disabled by default`() {
        assertThat(Defaults.NEURAL_BATCH_BEAMS).isFalse()
    }

    @Test
    fun `neural frequency weight default is 0_57`() {
        assertThat(Defaults.NEURAL_FREQUENCY_WEIGHT).isWithin(0.01f).of(0.57f)
    }

    // =========================================================================
    // Swipe Typing Defaults
    // =========================================================================

    @Test
    fun `swipe typing enabled by default`() {
        assertThat(Defaults.SWIPE_TYPING_ENABLED).isTrue()
    }

    @Test
    fun `swipe trail enabled by default`() {
        assertThat(Defaults.SWIPE_TRAIL_ENABLED).isTrue()
    }

    @Test
    fun `word prediction enabled by default`() {
        assertThat(Defaults.WORD_PREDICTION_ENABLED).isTrue()
    }

    // =========================================================================
    // Backup/Restore compatibility (v1.2.9)
    // =========================================================================

    @Test
    fun `all neural defaults have valid values for backup restore`() {
        // These are used in BackupRestoreManager and must be reasonable
        assertThat(Defaults.NEURAL_BEAM_WIDTH).isAtLeast(1)
        assertThat(Defaults.NEURAL_BEAM_WIDTH).isAtMost(20)
        assertThat(Defaults.NEURAL_MAX_LENGTH).isAtLeast(5)
        assertThat(Defaults.NEURAL_MAX_LENGTH).isAtMost(50)
        assertThat(Defaults.NEURAL_CONFIDENCE_THRESHOLD).isAtLeast(0f)
        assertThat(Defaults.NEURAL_CONFIDENCE_THRESHOLD).isAtMost(1f)
    }
}
