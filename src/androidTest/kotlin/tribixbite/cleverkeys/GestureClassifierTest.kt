package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for GestureClassifier.
 * Tests TAP vs SWIPE classification logic.
 */
@RunWith(AndroidJUnit4::class)
class GestureClassifierTest {

    private lateinit var context: Context
    private lateinit var classifier: GestureClassifier
    private lateinit var config: Config

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()
        classifier = GestureClassifier(context)
    }

    // =========================================================================
    // Basic TAP classification tests
    // =========================================================================

    @Test
    fun testShortTapClassifiedAsTap() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = false,
            totalDistance = 10f,
            timeElapsed = 50L,
            keyWidth = 100f
        )
        assertEquals("Short tap should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    @Test
    fun testTapWithinKeyBoundary() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = false,
            totalDistance = 20f,
            timeElapsed = 100L,
            keyWidth = 100f
        )
        assertEquals("Tap within key should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    @Test
    fun testZeroDistanceTap() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = false,
            totalDistance = 0f,
            timeElapsed = 10L,
            keyWidth = 100f
        )
        assertEquals("Zero distance should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    @Test
    fun testZeroTimeGesture() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = false,
            totalDistance = 5f,
            timeElapsed = 0L,
            keyWidth = 100f
        )
        assertEquals("Zero time tap should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    // =========================================================================
    // Basic SWIPE classification tests
    // =========================================================================

    @Test
    fun testLongDistanceSwipe() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 200f,  // > keyWidth/2
            timeElapsed = 100L,
            keyWidth = 100f
        )
        assertEquals("Long distance should be SWIPE", GestureClassifier.GestureType.SWIPE, classifier.classify(gesture))
    }

    @Test
    fun testSwipeExactlyAtThreshold() {
        val keyWidth = 100f
        val threshold = keyWidth / 2  // 50f
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = threshold,
            timeElapsed = 100L,
            keyWidth = keyWidth
        )
        assertEquals("Distance at threshold should be SWIPE", GestureClassifier.GestureType.SWIPE, classifier.classify(gesture))
    }

    @Test
    fun testSwipeJustAboveThreshold() {
        val keyWidth = 100f
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 51f,  // Just above keyWidth/2
            timeElapsed = 100L,
            keyWidth = keyWidth
        )
        assertEquals("Distance above threshold should be SWIPE", GestureClassifier.GestureType.SWIPE, classifier.classify(gesture))
    }

    // =========================================================================
    // hasLeftStartingKey requirement tests
    // =========================================================================

    @Test
    fun testDistanceWithoutLeavingKey() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = false,  // Did not leave key
            totalDistance = 200f,
            timeElapsed = 100L,
            keyWidth = 100f
        )
        assertEquals("Long distance without leaving key should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    @Test
    fun testLongTimeWithoutLeavingKey() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = false,
            totalDistance = 10f,
            timeElapsed = 5000L,  // Very long time
            keyWidth = 100f
        )
        assertEquals("Long time without leaving key should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    // =========================================================================
    // Time-based classification tests
    // =========================================================================

    @Test
    fun testLongDurationBecomesSwipe() {
        val tapThreshold = config.tap_duration_threshold
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 10f,  // Small distance
            timeElapsed = tapThreshold + 100,  // Exceeds tap duration
            keyWidth = 100f
        )
        assertEquals("Long duration should be SWIPE", GestureClassifier.GestureType.SWIPE, classifier.classify(gesture))
    }

    @Test
    fun testShortDurationBelowTapThreshold() {
        val tapThreshold = config.tap_duration_threshold
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 10f,  // Below distance threshold
            timeElapsed = tapThreshold / 2,  // Below time threshold
            keyWidth = 100f
        )
        // Short time AND small distance with left key = TAP
        assertEquals("Short duration with small distance should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testVerySmallKeyWidth() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 10f,
            timeElapsed = 100L,
            keyWidth = 10f  // Small key, threshold = 5f
        )
        assertEquals("10f distance with 5f threshold should be SWIPE", GestureClassifier.GestureType.SWIPE, classifier.classify(gesture))
    }

    @Test
    fun testVeryLargeKeyWidth() {
        val gesture = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 100f,
            timeElapsed = 100L,
            keyWidth = 500f  // Large key, threshold = 250f
        )
        assertEquals("100f distance with 250f threshold should be TAP", GestureClassifier.GestureType.TAP, classifier.classify(gesture))
    }

    @Test
    fun testGestureDataFields() {
        val data = GestureClassifier.GestureData(
            hasLeftStartingKey = true,
            totalDistance = 123.45f,
            timeElapsed = 500L,
            keyWidth = 75f
        )
        assertTrue("hasLeftStartingKey should be true", data.hasLeftStartingKey)
        assertEquals("totalDistance should match", 123.45f, data.totalDistance, 0.001f)
        assertEquals("timeElapsed should match", 500L, data.timeElapsed)
        assertEquals("keyWidth should match", 75f, data.keyWidth, 0.001f)
    }

    // =========================================================================
    // Config integration tests
    // =========================================================================

    @Test
    fun testTapDurationThresholdAccessible() {
        val threshold = config.tap_duration_threshold
        assertTrue("Tap duration threshold should be positive", threshold > 0)
    }

    @Test
    fun testGestureTypesExist() {
        val tap = GestureClassifier.GestureType.TAP
        val swipe = GestureClassifier.GestureType.SWIPE
        assertNotNull("TAP type should exist", tap)
        assertNotNull("SWIPE type should exist", swipe)
        assertNotEquals("TAP and SWIPE should be different", tap, swipe)
    }
}
