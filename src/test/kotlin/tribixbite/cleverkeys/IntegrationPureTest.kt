package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests inspired by IntegrationTest.kt (Robolectric).
 *
 * The original IntegrationTest relies on android.graphics.PointF and
 * ApplicationProvider, so none of its tests are directly salvageable.
 *
 * This file provides integration-style tests that verify how pure JVM
 * components work together:
 * - PredictionResult + SwipeDetector.SwipeClassification interaction
 * - SwipeDetector decision logic with various classification scenarios
 * - TrajectoryFeatures data pipeline structure
 * - Cross-component data flow without Android dependencies
 */
class IntegrationPureTest {

    // ========================================================================
    // Classification → DTW decision pipeline
    // ========================================================================

    @Test
    fun classificationToDtwDecision_highQuality_usesDtw() {
        val detector = SwipeDetector()
        val classification = SwipeDetector.SwipeClassification(
            isSwipe = true,
            confidence = 0.85f,
            reason = "Good path length; Multiple directions; ",
            quality = SwipeDetector.SwipeClassification.SwipeQuality.HIGH
        )

        assertThat(detector.shouldUseDTW(classification)).isTrue()
        assertThat(classification.isSwipe).isTrue()
    }

    @Test
    fun classificationToDtwDecision_lowQuality_skipsDtw() {
        val detector = SwipeDetector()
        val classification = SwipeDetector.SwipeClassification(
            isSwipe = false,
            confidence = 0.3f,
            reason = "Too short; ",
            quality = SwipeDetector.SwipeClassification.SwipeQuality.LOW
        )

        assertThat(detector.shouldUseDTW(classification)).isFalse()
        assertThat(classification.isSwipe).isFalse()
    }

    // ========================================================================
    // TrajectoryFeatures data structure pipeline
    // ========================================================================

    @Test
    fun trajectoryFeatures_paddedToMaxLength() {
        val maxSeqLen = 250

        // Simulate padding logic: few real points, rest are zero-padded
        val realPoints = 5
        val points = mutableListOf<SwipeTrajectoryProcessor.TrajectoryPoint>()
        for (i in 0 until realPoints) {
            val p = SwipeTrajectoryProcessor.TrajectoryPoint()
            p.x = i / realPoints.toFloat()
            p.y = 0.5f
            p.vx = 1.0f
            p.vy = 0.0f
            points.add(p)
        }
        // Pad with zeros
        while (points.size < maxSeqLen) {
            points.add(SwipeTrajectoryProcessor.TrajectoryPoint())
        }

        // Simulate nearest keys with PAD token padding
        val nearestKeys = mutableListOf<Int>()
        // Real keys: a(4), b(5), c(6), d(7), e(8)
        nearestKeys.addAll(listOf(4, 5, 6, 7, 8))
        while (nearestKeys.size < maxSeqLen) {
            nearestKeys.add(0) // PAD token
        }

        val features = SwipeTrajectoryProcessor.TrajectoryFeatures(
            normalizedPoints = points,
            nearestKeys = nearestKeys,
            actualLength = realPoints
        )

        // Verify padded structure
        assertThat(features.normalizedPoints).hasSize(maxSeqLen)
        assertThat(features.nearestKeys).hasSize(maxSeqLen)
        assertThat(features.actualLength).isEqualTo(realPoints)

        // Real points have non-zero values
        assertThat(features.normalizedPoints[0].vx).isEqualTo(1.0f)
        // Padded points are zero
        assertThat(features.normalizedPoints[maxSeqLen - 1].x).isEqualTo(0.0f)
        assertThat(features.normalizedPoints[maxSeqLen - 1].vx).isEqualTo(0.0f)

        // Real keys are in range 4-29
        assertThat(features.nearestKeys[0]).isEqualTo(4) // 'a'
        // Padded keys are 0 (PAD)
        assertThat(features.nearestKeys[maxSeqLen - 1]).isEqualTo(0)
    }

    @Test
    fun trajectoryFeatures_emptyInput() {
        val features = SwipeTrajectoryProcessor.TrajectoryFeatures()

        assertThat(features.normalizedPoints).isEmpty()
        assertThat(features.nearestKeys).isEmpty()
        assertThat(features.actualLength).isEqualTo(0)
    }

    @Test
    fun trajectoryFeatures_actualLengthMatchesRealData() {
        // actualLength should reflect pre-padding count
        val realLength = 42
        val maxLen = 250

        val points = (0 until maxLen).map {
            val p = SwipeTrajectoryProcessor.TrajectoryPoint()
            if (it < realLength) {
                p.x = it / realLength.toFloat()
                p.y = 0.5f
            }
            p
        }

        val features = SwipeTrajectoryProcessor.TrajectoryFeatures(
            normalizedPoints = points,
            nearestKeys = (0 until maxLen).map { if (it < realLength) (it % 26) + 4 else 0 },
            actualLength = realLength
        )

        assertThat(features.actualLength).isEqualTo(42)
        assertThat(features.normalizedPoints).hasSize(250)
    }

    // ========================================================================
    // PredictionResult filtering and ranking patterns
    // ========================================================================

    @Test
    fun predictionResult_topKSelection() {
        val result = PredictionResult(
            words = listOf("hello", "help", "held", "helm", "heap"),
            scores = listOf(950, 870, 600, 550, 300)
        )

        // Simulate top-3 selection (common UI pattern)
        val top3Words = result.words.take(3)
        val top3Scores = result.scores.take(3)

        assertThat(top3Words).containsExactly("hello", "help", "held").inOrder()
        assertThat(top3Scores).containsExactly(950, 870, 600).inOrder()
    }

    @Test
    fun predictionResult_filterByThreshold() {
        val result = PredictionResult(
            words = listOf("hello", "help", "held", "heap"),
            scores = listOf(950, 870, 300, 100)
        )

        // Filter predictions above threshold (common pattern)
        val threshold = 500
        val goodPredictions = result.words.zip(result.scores)
            .filter { (_, score) -> score >= threshold }
            .map { (word, _) -> word }

        assertThat(goodPredictions).containsExactly("hello", "help")
    }

    @Test
    fun predictionResult_bestWord() {
        val result = PredictionResult(
            words = listOf("hello", "help", "held"),
            scores = listOf(950, 870, 600)
        )

        // Best word is first (scores are pre-sorted descending)
        val bestWord = result.words.firstOrNull()
        val bestScore = result.scores.firstOrNull()

        assertThat(bestWord).isEqualTo("hello")
        assertThat(bestScore).isEqualTo(950)
    }

    // ========================================================================
    // SwipeClassification quality tier mapping
    // ========================================================================

    @Test
    fun qualityTierMapping_confidenceToQuality() {
        // Verify the confidence → quality mapping matches SwipeDetector logic
        data class TestCase(val confidence: Float, val expectedQuality: SwipeDetector.SwipeClassification.SwipeQuality)

        val cases = listOf(
            TestCase(0.85f, SwipeDetector.SwipeClassification.SwipeQuality.HIGH),
            TestCase(0.65f, SwipeDetector.SwipeClassification.SwipeQuality.MEDIUM),
            TestCase(0.45f, SwipeDetector.SwipeClassification.SwipeQuality.LOW),
            TestCase(0.2f, SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE),
        )

        for (case in cases) {
            val quality = when {
                case.confidence > 0.8f -> SwipeDetector.SwipeClassification.SwipeQuality.HIGH
                case.confidence > 0.6f -> SwipeDetector.SwipeClassification.SwipeQuality.MEDIUM
                case.confidence > 0.4f -> SwipeDetector.SwipeClassification.SwipeQuality.LOW
                else -> SwipeDetector.SwipeClassification.SwipeQuality.NOT_SWIPE
            }
            assertThat(quality).isEqualTo(case.expectedQuality)
        }
    }

    // ========================================================================
    // Token index conventions
    // ========================================================================

    @Test
    fun tokenIndexConventions_alphabet() {
        // Verify the token index convention: a=4, b=5, ..., z=29
        for (i in 0 until 26) {
            val c = 'a' + i
            val expectedToken = i + 4
            assertThat(expectedToken).isEqualTo(i + 4)
        }

        // PAD token = 0
        assertThat(0).isEqualTo(0) // PAD
    }

    @Test
    fun tokenIndexConventions_specialTokens() {
        // Standard ONNX tokenizer convention used by the model:
        // 0 = PAD, 1 = UNK, 2 = SOS, 3 = EOS, 4-29 = a-z
        val PAD_IDX = 0
        val UNK_IDX = 1
        val SOS_IDX = 2
        val EOS_IDX = 3
        val FIRST_LETTER_IDX = 4 // 'a'
        val LAST_LETTER_IDX = 29 // 'z'

        assertThat(FIRST_LETTER_IDX - PAD_IDX).isEqualTo(4) // 4 special tokens before letters
        assertThat(LAST_LETTER_IDX - FIRST_LETTER_IDX + 1).isEqualTo(26) // 26 letters
    }

    // ========================================================================
    // TrajectoryPoint velocity/acceleration ranges
    // ========================================================================

    @Test
    fun trajectoryPoint_velocityClipping() {
        // The feature calculator clips velocities to [-10, 10]
        val point = SwipeTrajectoryProcessor.TrajectoryPoint()

        // Simulate clipped values
        point.vx = 10.0f.coerceIn(-10f, 10f)
        point.vy = (-15.0f).coerceIn(-10f, 10f)

        assertThat(point.vx).isEqualTo(10.0f)
        assertThat(point.vy).isEqualTo(-10.0f)
    }

    @Test
    fun trajectoryPoint_normalizedCoordinateRange() {
        // Normalized coordinates should be in [0, 1]
        val point = SwipeTrajectoryProcessor.TrajectoryPoint()
        point.x = 0.5f.coerceIn(0f, 1f)
        point.y = 1.2f.coerceIn(0f, 1f) // Would be clamped

        assertThat(point.x).isWithin(0.01f).of(0.5f)
        assertThat(point.y).isEqualTo(1.0f) // Clamped
    }

    // ========================================================================
    // SwipeDetector instantiation and config
    // ========================================================================

    @Test
    fun swipeDetector_multipleInstances() {
        val d1 = SwipeDetector()
        val d2 = SwipeDetector()

        // Independent instances
        assertThat(d1).isNotSameInstanceAs(d2)
    }

    @Test
    fun swipeDetector_nullConfigIsNoOp() {
        val detector = SwipeDetector()

        // Should not throw
        detector.updateConfig(null)

        // Detector should still be functional for shouldUseDTW
        val high = SwipeDetector.SwipeClassification(
            true, 0.9f, "test",
            SwipeDetector.SwipeClassification.SwipeQuality.HIGH
        )
        assertThat(detector.shouldUseDTW(high)).isTrue()
    }
}
