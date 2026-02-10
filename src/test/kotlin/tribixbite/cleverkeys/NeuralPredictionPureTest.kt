package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests salvaged from NeuralPredictionTest.kt (Robolectric).
 *
 * SwipeInput tests cannot be extracted because SwipeInput relies on android.graphics.PointF
 * which is a stub on JVM (all coordinates become 0,0). Instead, this file tests:
 * - PredictionResult data class (pure Kotlin, no android deps)
 * - SwipeDetector data classes and decision logic
 * - SwipeTrajectoryProcessor inner data classes (TrajectoryPoint, TrajectoryFeatures)
 */
class NeuralPredictionPureTest {

    // ========================================================================
    // PredictionResult tests (salvaged from NeuralPredictionTest)
    // ========================================================================

    @Test
    fun predictionResult_basics() {
        val result = PredictionResult(
            words = listOf("hello", "world", "test"),
            scores = listOf(900, 800, 700)
        )

        assertThat(result.words).hasSize(3)
        assertThat(result.scores).hasSize(3)
        assertThat(result.words[0]).isEqualTo("hello")
        assertThat(result.scores[0]).isEqualTo(900)
    }

    @Test
    fun predictionResult_empty() {
        val result = PredictionResult(
            words = emptyList(),
            scores = emptyList()
        )

        assertThat(result.words).isEmpty()
        assertThat(result.scores).isEmpty()
    }

    @Test
    fun predictionResult_dataClassEquality() {
        val a = PredictionResult(listOf("hello"), listOf(900))
        val b = PredictionResult(listOf("hello"), listOf(900))
        val c = PredictionResult(listOf("world"), listOf(800))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun predictionResult_copy() {
        val original = PredictionResult(listOf("hello", "world"), listOf(900, 800))
        val modified = original.copy(words = listOf("changed"))

        assertThat(modified.words).containsExactly("changed")
        assertThat(modified.scores).isEqualTo(original.scores)
    }

    @Test
    fun predictionResult_singleWord() {
        val result = PredictionResult(
            words = listOf("keyboard"),
            scores = listOf(1000)
        )

        assertThat(result.words).containsExactly("keyboard")
        assertThat(result.scores).containsExactly(1000)
    }

    @Test
    fun predictionResult_scoresRange() {
        // Scores are 0-1000 range per documentation
        val result = PredictionResult(
            words = listOf("a", "b", "c"),
            scores = listOf(1000, 500, 0)
        )

        assertThat(result.scores).containsExactly(1000, 500, 0).inOrder()
    }

    @Test
    fun predictionResult_manyPredictions() {
        val words = (1..20).map { "word$it" }
        val scores = (1..20).map { it * 50 }
        val result = PredictionResult(words, scores)

        assertThat(result.words).hasSize(20)
        assertThat(result.scores).hasSize(20)
        assertThat(result.words.first()).isEqualTo("word1")
        assertThat(result.words.last()).isEqualTo("word20")
    }

    @Test
    fun predictionResult_destructuring() {
        val result = PredictionResult(listOf("hello"), listOf(950))
        val (words, scores) = result

        assertThat(words).containsExactly("hello")
        assertThat(scores).containsExactly(950)
    }

    // ========================================================================
    // SwipeDetector data class tests
    // ========================================================================

    @Test
    fun swipeClassification_notSwipe() {
        val classification = SwipeDetector.SwipeClassification(
            isSwipe = false,
            confidence = 0.0f,
            reason = "Too few points",
            quality = SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE
        )

        assertThat(classification.isSwipe).isFalse()
        assertThat(classification.confidence).isEqualTo(0.0f)
        assertThat(classification.reason).isEqualTo("Too few points")
        assertThat(classification.quality)
            .isEqualTo(SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE)
    }

    @Test
    fun swipeClassification_highQualitySwipe() {
        val classification = SwipeDetector.SwipeClassification(
            isSwipe = true,
            confidence = 0.95f,
            reason = "Good path length; Multiple directions; Consistent velocity; ",
            quality = SwipeDetector.SwipeClassification.SwipeQuality.HIGH
        )

        assertThat(classification.isSwipe).isTrue()
        assertThat(classification.confidence).isGreaterThan(0.8f)
        assertThat(classification.quality)
            .isEqualTo(SwipeDetector.SwipeClassification.SwipeQuality.HIGH)
    }

    @Test
    fun swipeClassification_mediumQuality() {
        val classification = SwipeDetector.SwipeClassification(
            isSwipe = true,
            confidence = 0.7f,
            reason = "Good path length; ",
            quality = SwipeDetector.SwipeClassification.SwipeQuality.MEDIUM
        )

        assertThat(classification.quality)
            .isEqualTo(SwipeDetector.SwipeClassification.SwipeQuality.MEDIUM)
    }

    @Test
    fun swipeClassification_dataClassEquality() {
        val a = SwipeDetector.SwipeClassification(
            true, 0.9f, "test",
            SwipeDetector.SwipeClassification.SwipeQuality.HIGH
        )
        val b = SwipeDetector.SwipeClassification(
            true, 0.9f, "test",
            SwipeDetector.SwipeClassification.SwipeQuality.HIGH
        )

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun swipeQuality_enumValues() {
        val values = SwipeDetector.SwipeClassification.SwipeQuality.values()

        assertThat(values).hasLength(4)
        assertThat(values).asList().containsExactly(
            SwipeDetector.SwipeClassification.SwipeQuality.HIGH,
            SwipeDetector.SwipeClassification.SwipeQuality.MEDIUM,
            SwipeDetector.SwipeClassification.SwipeQuality.LOW,
            SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE
        ).inOrder()
    }

    @Test
    fun swipeQuality_valueOf() {
        assertThat(
            SwipeDetector.SwipeClassification.SwipeQuality.valueOf("HIGH")
        ).isEqualTo(SwipeDetector.SwipeClassification.SwipeQuality.HIGH)

        assertThat(
            SwipeDetector.SwipeClassification.SwipeQuality.valueOf("NOT_SWIPE")
        ).isEqualTo(SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE)
    }

    // ========================================================================
    // SwipeDetector.shouldUseDTW logic tests
    // ========================================================================

    @Test
    fun shouldUseDTW_highQuality_returnsTrue() {
        val detector = SwipeDetector()
        val classification = SwipeDetector.SwipeClassification(
            true, 0.9f, "Good swipe",
            SwipeDetector.SwipeClassification.SwipeQuality.HIGH
        )

        assertThat(detector.shouldUseDTW(classification)).isTrue()
    }

    @Test
    fun shouldUseDTW_mediumQuality_returnsTrue() {
        val detector = SwipeDetector()
        val classification = SwipeDetector.SwipeClassification(
            true, 0.65f, "Acceptable swipe",
            SwipeDetector.SwipeClassification.SwipeQuality.MEDIUM
        )

        assertThat(detector.shouldUseDTW(classification)).isTrue()
    }

    @Test
    fun shouldUseDTW_lowQuality_returnsFalse() {
        val detector = SwipeDetector()
        val classification = SwipeDetector.SwipeClassification(
            false, 0.45f, "Ambiguous",
            SwipeDetector.SwipeClassification.SwipeQuality.LOW
        )

        assertThat(detector.shouldUseDTW(classification)).isFalse()
    }

    @Test
    fun shouldUseDTW_notSwipe_returnsFalse() {
        val detector = SwipeDetector()
        val classification = SwipeDetector.SwipeClassification(
            false, 0.1f, "Not a swipe",
            SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE
        )

        assertThat(detector.shouldUseDTW(classification)).isFalse()
    }

    @Test
    fun swipeDetector_instantiation() {
        // Verify SwipeDetector can be instantiated without android context
        val detector = SwipeDetector()
        assertThat(detector).isNotNull()
    }

    @Test
    fun swipeDetector_updateConfig_nullSafe() {
        // updateConfig(null) should not throw
        val detector = SwipeDetector()
        detector.updateConfig(null)
        // If it didn't throw, the test passes
    }

    // ========================================================================
    // SwipeTrajectoryProcessor inner class tests
    // ========================================================================

    @Test
    fun trajectoryPoint_defaultValues() {
        val point = SwipeTrajectoryProcessor.TrajectoryPoint()

        assertThat(point.x).isEqualTo(0.0f)
        assertThat(point.y).isEqualTo(0.0f)
        assertThat(point.vx).isEqualTo(0.0f)
        assertThat(point.vy).isEqualTo(0.0f)
        assertThat(point.ax).isEqualTo(0.0f)
        assertThat(point.ay).isEqualTo(0.0f)
    }

    @Test
    fun trajectoryPoint_setValues() {
        val point = SwipeTrajectoryProcessor.TrajectoryPoint()
        point.x = 0.5f
        point.y = 0.3f
        point.vx = 1.2f
        point.vy = -0.8f
        point.ax = 0.1f
        point.ay = -0.05f

        assertThat(point.x).isEqualTo(0.5f)
        assertThat(point.y).isEqualTo(0.3f)
        assertThat(point.vx).isEqualTo(1.2f)
        assertThat(point.vy).isEqualTo(-0.8f)
        assertThat(point.ax).isEqualTo(0.1f)
        assertThat(point.ay).isEqualTo(-0.05f)
    }

    @Test
    fun trajectoryPoint_copyConstructor() {
        val original = SwipeTrajectoryProcessor.TrajectoryPoint()
        original.x = 0.5f
        original.y = 0.3f
        original.vx = 1.0f
        original.vy = 2.0f
        original.ax = 0.1f
        original.ay = 0.2f

        val copy = SwipeTrajectoryProcessor.TrajectoryPoint(original)

        assertThat(copy.x).isEqualTo(original.x)
        assertThat(copy.y).isEqualTo(original.y)
        assertThat(copy.vx).isEqualTo(original.vx)
        assertThat(copy.vy).isEqualTo(original.vy)
        assertThat(copy.ax).isEqualTo(original.ax)
        assertThat(copy.ay).isEqualTo(original.ay)
    }

    @Test
    fun trajectoryPoint_copyIsIndependent() {
        val original = SwipeTrajectoryProcessor.TrajectoryPoint()
        original.x = 1.0f

        val copy = SwipeTrajectoryProcessor.TrajectoryPoint(original)
        copy.x = 2.0f

        // Modifying copy should not affect original
        assertThat(original.x).isEqualTo(1.0f)
        assertThat(copy.x).isEqualTo(2.0f)
    }

    @Test
    fun trajectoryFeatures_defaultEmpty() {
        val features = SwipeTrajectoryProcessor.TrajectoryFeatures()

        assertThat(features.normalizedPoints).isEmpty()
        assertThat(features.nearestKeys).isEmpty()
        assertThat(features.actualLength).isEqualTo(0)
    }

    @Test
    fun trajectoryFeatures_withData() {
        val point = SwipeTrajectoryProcessor.TrajectoryPoint()
        point.x = 0.5f
        point.y = 0.3f

        val features = SwipeTrajectoryProcessor.TrajectoryFeatures(
            normalizedPoints = listOf(point),
            nearestKeys = listOf(4, 5, 6), // a=4, b=5, c=6
            actualLength = 1
        )

        assertThat(features.normalizedPoints).hasSize(1)
        assertThat(features.nearestKeys).containsExactly(4, 5, 6).inOrder()
        assertThat(features.actualLength).isEqualTo(1)
    }

    @Test
    fun trajectoryFeatures_dataClassEquality() {
        val a = SwipeTrajectoryProcessor.TrajectoryFeatures(
            normalizedPoints = emptyList(),
            nearestKeys = listOf(4, 5),
            actualLength = 3
        )
        val b = SwipeTrajectoryProcessor.TrajectoryFeatures(
            normalizedPoints = emptyList(),
            nearestKeys = listOf(4, 5),
            actualLength = 3
        )

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun trajectoryFeatures_tokenIndexRange() {
        // Token indices: 4-29 for a-z, 0 for PAD
        val keys = listOf(0, 4, 10, 29, 0) // PAD, a, g, z, PAD
        val features = SwipeTrajectoryProcessor.TrajectoryFeatures(
            normalizedPoints = emptyList(),
            nearestKeys = keys,
            actualLength = 3
        )

        assertThat(features.nearestKeys[0]).isEqualTo(0) // PAD
        assertThat(features.nearestKeys[1]).isEqualTo(4) // 'a'
        assertThat(features.nearestKeys[2]).isEqualTo(10) // 'g'
        assertThat(features.nearestKeys[3]).isEqualTo(29) // 'z'
    }
}
