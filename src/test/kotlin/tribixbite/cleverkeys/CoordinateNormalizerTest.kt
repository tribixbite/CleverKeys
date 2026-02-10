package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for CoordinateNormalizer.
 *
 * Tests the QwertyBounds data class, NormalizedPoint data class, and
 * getKeySequence() which operate without Android framework dependencies.
 *
 * NOTE: calculateQwertyBounds(), normalizeCoordinate(), normalizeCoordinates(),
 * and analyzeSwipe() depend on android.graphics.PointF and KeyboardGrid (which
 * initializes PointF at class load), so they require MockK or Robolectric.
 * Those are covered in TrajectoryFeatureCalculatorTest via MockK.
 */
class CoordinateNormalizerTest {

    // =========================================================================
    // QwertyBounds data class tests
    // =========================================================================

    @Test
    fun `QwertyBounds INVALID has zero dimensions`() {
        val invalid = CoordinateNormalizer.QwertyBounds.INVALID
        assertThat(invalid.top).isEqualTo(0f)
        assertThat(invalid.height).isEqualTo(0f)
        assertThat(invalid.rowHeight).isEqualTo(0f)
    }

    @Test
    fun `QwertyBounds INVALID is not valid`() {
        assertThat(CoordinateNormalizer.QwertyBounds.INVALID.isValid).isFalse()
    }

    @Test
    fun `QwertyBounds with positive dimensions is valid`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f,
            height = 300f,
            rowHeight = 100f
        )
        assertThat(bounds.isValid).isTrue()
    }

    @Test
    fun `QwertyBounds with zero height is not valid`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f,
            height = 0f,
            rowHeight = 100f
        )
        assertThat(bounds.isValid).isFalse()
    }

    @Test
    fun `QwertyBounds with zero rowHeight is not valid`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f,
            height = 300f,
            rowHeight = 0f
        )
        assertThat(bounds.isValid).isFalse()
    }

    @Test
    fun `QwertyBounds with negative height is not valid`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f,
            height = -50f,
            rowHeight = 100f
        )
        assertThat(bounds.isValid).isFalse()
    }

    // =========================================================================
    // QwertyBounds.getRowCenterY tests
    // =========================================================================

    @Test
    fun `getRowCenterY row 0 returns ~0_167`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 0f, height = 300f, rowHeight = 100f
        )
        // Row 0: (0 * 1/3) + (1/3 / 2) = 1/6 ≈ 0.1667
        assertThat(bounds.getRowCenterY(0)).isWithin(0.001f).of(1f / 6f)
    }

    @Test
    fun `getRowCenterY row 1 returns 0_5`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 0f, height = 300f, rowHeight = 100f
        )
        // Row 1: (1 * 1/3) + (1/3 / 2) = 1/3 + 1/6 = 1/2 = 0.5
        assertThat(bounds.getRowCenterY(1)).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `getRowCenterY row 2 returns ~0_833`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 0f, height = 300f, rowHeight = 100f
        )
        // Row 2: (2 * 1/3) + (1/3 / 2) = 2/3 + 1/6 = 5/6 ≈ 0.8333
        assertThat(bounds.getRowCenterY(2)).isWithin(0.001f).of(5f / 6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getRowCenterY rejects negative row`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 0f, height = 300f, rowHeight = 100f
        )
        bounds.getRowCenterY(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getRowCenterY rejects row 3`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 0f, height = 300f, rowHeight = 100f
        )
        bounds.getRowCenterY(3)
    }

    // =========================================================================
    // QwertyBounds.getRowCenterPixelY tests
    // =========================================================================

    @Test
    fun `getRowCenterPixelY row 0 with top=100 height=300`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f, height = 300f, rowHeight = 100f
        )
        // top + (getRowCenterY(0) * height) = 100 + (0.1667 * 300) = 100 + 50 = 150
        assertThat(bounds.getRowCenterPixelY(0)).isWithin(0.1f).of(150f)
    }

    @Test
    fun `getRowCenterPixelY row 1 with top=100 height=300`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f, height = 300f, rowHeight = 100f
        )
        // top + (0.5 * 300) = 100 + 150 = 250
        assertThat(bounds.getRowCenterPixelY(1)).isWithin(0.1f).of(250f)
    }

    @Test
    fun `getRowCenterPixelY row 2 with top=100 height=300`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 100f, height = 300f, rowHeight = 100f
        )
        // top + (0.833 * 300) = 100 + 250 = 350
        assertThat(bounds.getRowCenterPixelY(2)).isWithin(0.1f).of(350f)
    }

    @Test
    fun `getRowCenterPixelY with zero top`() {
        val bounds = CoordinateNormalizer.QwertyBounds(
            top = 0f, height = 600f, rowHeight = 200f
        )
        // Row centers at: 100, 300, 500
        assertThat(bounds.getRowCenterPixelY(0)).isWithin(0.1f).of(100f)
        assertThat(bounds.getRowCenterPixelY(1)).isWithin(0.1f).of(300f)
        assertThat(bounds.getRowCenterPixelY(2)).isWithin(0.1f).of(500f)
    }

    // =========================================================================
    // QwertyBounds equality and data class tests
    // =========================================================================

    @Test
    fun `QwertyBounds equality`() {
        val a = CoordinateNormalizer.QwertyBounds(100f, 300f, 100f)
        val b = CoordinateNormalizer.QwertyBounds(100f, 300f, 100f)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `QwertyBounds inequality`() {
        val a = CoordinateNormalizer.QwertyBounds(100f, 300f, 100f)
        val b = CoordinateNormalizer.QwertyBounds(200f, 300f, 100f)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `QwertyBounds toString is readable`() {
        val bounds = CoordinateNormalizer.QwertyBounds(100f, 300f, 100f)
        val str = bounds.toString()
        assertThat(str).contains("100")
        assertThat(str).contains("300")
    }

    // =========================================================================
    // NormalizedPoint data class tests
    // =========================================================================

    @Test
    fun `NormalizedPoint expectedRow top row`() {
        val point = CoordinateNormalizer.NormalizedPoint(
            x = 0.5f, y = 0.1f, rawX = 50f, rawY = 10f, nearestKey = 'e'
        )
        assertThat(point.expectedRow).isEqualTo(0)
    }

    @Test
    fun `NormalizedPoint expectedRow middle row`() {
        val point = CoordinateNormalizer.NormalizedPoint(
            x = 0.5f, y = 0.5f, rawX = 50f, rawY = 50f, nearestKey = 'f'
        )
        assertThat(point.expectedRow).isEqualTo(1)
    }

    @Test
    fun `NormalizedPoint expectedRow bottom row`() {
        val point = CoordinateNormalizer.NormalizedPoint(
            x = 0.5f, y = 0.9f, rawX = 50f, rawY = 90f, nearestKey = 'b'
        )
        assertThat(point.expectedRow).isEqualTo(2)
    }

    @Test
    fun `NormalizedPoint expectedRow boundary at 0_333`() {
        // y < 0.333 -> row 0
        val below = CoordinateNormalizer.NormalizedPoint(
            x = 0f, y = 0.332f, rawX = 0f, rawY = 0f, nearestKey = 'a'
        )
        assertThat(below.expectedRow).isEqualTo(0)

        // y == 0.333 -> row 1
        val at = CoordinateNormalizer.NormalizedPoint(
            x = 0f, y = 0.333f, rawX = 0f, rawY = 0f, nearestKey = 'a'
        )
        assertThat(at.expectedRow).isEqualTo(1)
    }

    @Test
    fun `NormalizedPoint expectedRow boundary at 0_667`() {
        // y < 0.667 -> row 1
        val below = CoordinateNormalizer.NormalizedPoint(
            x = 0f, y = 0.666f, rawX = 0f, rawY = 0f, nearestKey = 'a'
        )
        assertThat(below.expectedRow).isEqualTo(1)

        // y >= 0.667 -> row 2
        val at = CoordinateNormalizer.NormalizedPoint(
            x = 0f, y = 0.667f, rawX = 0f, rawY = 0f, nearestKey = 'a'
        )
        assertThat(at.expectedRow).isEqualTo(2)
    }

    @Test
    fun `NormalizedPoint toString contains coordinates and key`() {
        val point = CoordinateNormalizer.NormalizedPoint(
            x = 0.123f, y = 0.456f, rawX = 50f, rawY = 200f, nearestKey = 'g'
        )
        val str = point.toString()
        assertThat(str).contains("0.123")
        assertThat(str).contains("0.456")
        assertThat(str).contains("g")
    }

    @Test
    fun `NormalizedPoint at extremes`() {
        // y = 0.0 -> row 0
        val atZero = CoordinateNormalizer.NormalizedPoint(
            x = 0f, y = 0f, rawX = 0f, rawY = 0f, nearestKey = 'q'
        )
        assertThat(atZero.expectedRow).isEqualTo(0)

        // y = 1.0 -> row 2
        val atOne = CoordinateNormalizer.NormalizedPoint(
            x = 1f, y = 1f, rawX = 100f, rawY = 100f, nearestKey = 'm'
        )
        assertThat(atOne.expectedRow).isEqualTo(2)
    }

    // =========================================================================
    // getKeySequence tests
    // =========================================================================

    @Test
    fun `getKeySequence deduplicates consecutive same keys`() {
        val points = listOf(
            makeNormalizedPoint('h'),
            makeNormalizedPoint('h'),
            makeNormalizedPoint('e'),
            makeNormalizedPoint('e'),
            makeNormalizedPoint('e'),
            makeNormalizedPoint('l'),
            makeNormalizedPoint('l'),
            makeNormalizedPoint('l'),
            makeNormalizedPoint('l'),
            makeNormalizedPoint('o')
        )
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("helo")
    }

    @Test
    fun `getKeySequence preserves non-consecutive duplicates`() {
        val points = listOf(
            makeNormalizedPoint('a'),
            makeNormalizedPoint('b'),
            makeNormalizedPoint('a'),
            makeNormalizedPoint('b')
        )
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("abab")
    }

    @Test
    fun `getKeySequence single point`() {
        val points = listOf(makeNormalizedPoint('z'))
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("z")
    }

    @Test
    fun `getKeySequence empty list`() {
        assertThat(CoordinateNormalizer.getKeySequence(emptyList())).isEmpty()
    }

    @Test
    fun `getKeySequence all same key`() {
        val points = List(10) { makeNormalizedPoint('x') }
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("x")
    }

    @Test
    fun `getKeySequence all unique keys`() {
        // "hello" has adjacent duplicate 'l' which getKeySequence deduplicates → "helo"
        val points = "hello".map { makeNormalizedPoint(it) }
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("helo")
    }

    @Test
    fun `getKeySequence with truly unique characters`() {
        val points = "abcde".map { makeNormalizedPoint(it) }
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("abcde")
    }

    @Test
    fun `getKeySequence realistic swipe pattern`() {
        // Simulates a swipe for "the" passing through: t, t, h, h, h, e
        val points = listOf(
            makeNormalizedPoint('t'),
            makeNormalizedPoint('t'),
            makeNormalizedPoint('h'),
            makeNormalizedPoint('h'),
            makeNormalizedPoint('h'),
            makeNormalizedPoint('e')
        )
        assertThat(CoordinateNormalizer.getKeySequence(points)).isEqualTo("the")
    }

    // =========================================================================
    // SwipeAnalysis data class tests
    // =========================================================================

    @Test
    fun `SwipeAnalysis EMPTY has default values`() {
        val empty = CoordinateNormalizer.SwipeAnalysis.EMPTY
        assertThat(empty.keySequence).isEmpty()
        assertThat(empty.pointCount).isEqualTo(0)
        assertThat(empty.firstPoint).isNull()
        assertThat(empty.lastPoint).isNull()
        assertThat(empty.bounds.isValid).isFalse()
        assertThat(empty.keyboardWidth).isEqualTo(0f)
        assertThat(empty.issues).isEmpty()
    }

    @Test
    fun `SwipeAnalysis toString is descriptive`() {
        val analysis = CoordinateNormalizer.SwipeAnalysis(
            keySequence = "hello",
            pointCount = 50,
            firstPoint = makeNormalizedPoint('h'),
            lastPoint = makeNormalizedPoint('o'),
            bounds = CoordinateNormalizer.QwertyBounds(100f, 300f, 100f),
            keyboardWidth = 1080f,
            issues = listOf("test issue")
        )
        val str = analysis.toString()
        assertThat(str).contains("hello")
        assertThat(str).contains("50")
        assertThat(str).contains("test issue")
    }

    @Test
    fun `SwipeAnalysis toDebugString contains key sequence`() {
        val analysis = CoordinateNormalizer.SwipeAnalysis(
            keySequence = "world",
            pointCount = 30,
            firstPoint = makeNormalizedPoint('w'),
            lastPoint = makeNormalizedPoint('d'),
            bounds = CoordinateNormalizer.QwertyBounds.INVALID,
            keyboardWidth = 1080f,
            issues = emptyList()
        )
        val debug = analysis.toDebugString()
        assertThat(debug).contains("world")
    }

    // =========================================================================
    // ROW_HEIGHT constant test
    // =========================================================================

    @Test
    fun `ROW_HEIGHT is one third`() {
        assertThat(CoordinateNormalizer.ROW_HEIGHT).isWithin(0.0001f).of(1f / 3f)
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun makeNormalizedPoint(key: Char): CoordinateNormalizer.NormalizedPoint {
        return CoordinateNormalizer.NormalizedPoint(
            x = 0.5f, y = 0.5f,
            rawX = 500f, rawY = 500f,
            nearestKey = key
        )
    }
}
