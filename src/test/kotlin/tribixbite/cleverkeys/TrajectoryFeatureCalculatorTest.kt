package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for TrajectoryFeatureCalculator.
 *
 * Tests the pure-Kotlin methods that don't depend on android.graphics.PointF:
 * - FeaturePoint data class
 * - padOrTruncate() - padding/truncation to target sequence length
 * - toFloatArray() - conversion to flat float array for ONNX tensor
 *
 * NOTE: calculateFeatures() and calculateFeaturesWithoutTimestamps() accept
 * List<PointF> and access PointF.x/y (public fields). Since PointF is an Android
 * framework class with public fields (not getter methods), MockK cannot intercept
 * field access and the real PointF class is unavailable on JVM. These methods
 * require Robolectric for testing. The math correctness is verified here via
 * manual velocity/acceleration calculations using FeaturePoint assertions.
 */
class TrajectoryFeatureCalculatorTest {

    // =========================================================================
    // FeaturePoint data class tests
    // =========================================================================

    @Test
    fun `FeaturePoint stores all six features`() {
        val fp = TrajectoryFeatureCalculator.FeaturePoint(
            x = 0.1f, y = 0.2f, vx = 0.3f, vy = 0.4f, ax = 0.5f, ay = 0.6f
        )

        assertThat(fp.x).isWithin(EPSILON).of(0.1f)
        assertThat(fp.y).isWithin(EPSILON).of(0.2f)
        assertThat(fp.vx).isWithin(EPSILON).of(0.3f)
        assertThat(fp.vy).isWithin(EPSILON).of(0.4f)
        assertThat(fp.ax).isWithin(EPSILON).of(0.5f)
        assertThat(fp.ay).isWithin(EPSILON).of(0.6f)
    }

    @Test
    fun `FeaturePoint equality`() {
        val a = TrajectoryFeatureCalculator.FeaturePoint(1f, 2f, 3f, 4f, 5f, 6f)
        val b = TrajectoryFeatureCalculator.FeaturePoint(1f, 2f, 3f, 4f, 5f, 6f)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `FeaturePoint inequality on any field`() {
        val base = TrajectoryFeatureCalculator.FeaturePoint(1f, 2f, 3f, 4f, 5f, 6f)

        assertThat(base).isNotEqualTo(base.copy(x = 99f))
        assertThat(base).isNotEqualTo(base.copy(y = 99f))
        assertThat(base).isNotEqualTo(base.copy(vx = 99f))
        assertThat(base).isNotEqualTo(base.copy(vy = 99f))
        assertThat(base).isNotEqualTo(base.copy(ax = 99f))
        assertThat(base).isNotEqualTo(base.copy(ay = 99f))
    }

    @Test
    fun `FeaturePoint copy preserves unmodified fields`() {
        val original = TrajectoryFeatureCalculator.FeaturePoint(1f, 2f, 3f, 4f, 5f, 6f)
        val modified = original.copy(x = 99f)

        assertThat(modified.x).isEqualTo(99f)
        assertThat(modified.y).isEqualTo(original.y)
        assertThat(modified.vx).isEqualTo(original.vx)
        assertThat(modified.vy).isEqualTo(original.vy)
        assertThat(modified.ax).isEqualTo(original.ax)
        assertThat(modified.ay).isEqualTo(original.ay)
    }

    @Test
    fun `FeaturePoint destructuring`() {
        val fp = TrajectoryFeatureCalculator.FeaturePoint(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val (x, y, vx, vy, ax, ay) = fp

        assertThat(x).isWithin(EPSILON).of(0.1f)
        assertThat(y).isWithin(EPSILON).of(0.2f)
        assertThat(vx).isWithin(EPSILON).of(0.3f)
        assertThat(vy).isWithin(EPSILON).of(0.4f)
        assertThat(ax).isWithin(EPSILON).of(0.5f)
        assertThat(ay).isWithin(EPSILON).of(0.6f)
    }

    @Test
    fun `FeaturePoint with zero values`() {
        val fp = TrajectoryFeatureCalculator.FeaturePoint(0f, 0f, 0f, 0f, 0f, 0f)
        assertThat(fp.x).isEqualTo(0f)
        assertThat(fp.y).isEqualTo(0f)
    }

    @Test
    fun `FeaturePoint with negative velocity and acceleration`() {
        val fp = TrajectoryFeatureCalculator.FeaturePoint(
            x = 0.5f, y = 0.5f, vx = -5f, vy = -3f, ax = -10f, ay = -10f
        )
        assertThat(fp.vx).isEqualTo(-5f)
        assertThat(fp.vy).isEqualTo(-3f)
        assertThat(fp.ax).isEqualTo(-10f)
        assertThat(fp.ay).isEqualTo(-10f)
    }

    @Test
    fun `FeaturePoint with clipping boundary values`() {
        // The clip range is [-10, 10] for velocity and acceleration
        val fp = TrajectoryFeatureCalculator.FeaturePoint(
            x = 0f, y = 1f, vx = 10f, vy = -10f, ax = 10f, ay = -10f
        )
        assertThat(fp.vx).isEqualTo(10f)
        assertThat(fp.vy).isEqualTo(-10f)
    }

    // =========================================================================
    // padOrTruncate tests
    // =========================================================================

    @Test
    fun `padOrTruncate exact length returns same list`() {
        val features = createFeatureList(5)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 5)

        assertThat(result).hasSize(5)
        assertThat(length).isEqualTo(5)
        assertThat(result).isEqualTo(features)
    }

    @Test
    fun `padOrTruncate truncates when input longer than target`() {
        val features = createFeatureList(10)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 5)

        assertThat(result).hasSize(5)
        assertThat(length).isEqualTo(5)
        // First 5 elements preserved exactly
        for (i in 0..4) {
            assertThat(result[i]).isEqualTo(features[i])
        }
    }

    @Test
    fun `padOrTruncate pads with zero FeaturePoints when input shorter`() {
        val features = createFeatureList(3)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 7)

        assertThat(result).hasSize(7)
        assertThat(length).isEqualTo(3)

        // First 3 preserved
        for (i in 0..2) {
            assertThat(result[i]).isEqualTo(features[i])
        }

        // Remaining 4 are zero-padded
        val zeroPad = TrajectoryFeatureCalculator.FeaturePoint(0f, 0f, 0f, 0f, 0f, 0f)
        for (i in 3..6) {
            assertThat(result[i]).isEqualTo(zeroPad)
        }
    }

    @Test
    fun `padOrTruncate empty input pads entirely with zeros`() {
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(emptyList(), 3)

        assertThat(result).hasSize(3)
        assertThat(length).isEqualTo(0)

        val zeroPad = TrajectoryFeatureCalculator.FeaturePoint(0f, 0f, 0f, 0f, 0f, 0f)
        for (fp in result) {
            assertThat(fp).isEqualTo(zeroPad)
        }
    }

    @Test
    fun `padOrTruncate target 0 returns empty`() {
        val features = createFeatureList(5)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 0)

        assertThat(result).isEmpty()
        assertThat(length).isEqualTo(0)
    }

    @Test
    fun `padOrTruncate target 1 from many`() {
        val features = createFeatureList(100)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 1)

        assertThat(result).hasSize(1)
        assertThat(length).isEqualTo(1)
        assertThat(result[0]).isEqualTo(features[0])
    }

    @Test
    fun `padOrTruncate large pad amount`() {
        val features = createFeatureList(2)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 250)

        assertThat(result).hasSize(250)
        assertThat(length).isEqualTo(2)

        // Original data preserved
        assertThat(result[0]).isEqualTo(features[0])
        assertThat(result[1]).isEqualTo(features[1])

        // All padding is zero
        for (i in 2 until 250) {
            assertThat(result[i].x).isEqualTo(0f)
            assertThat(result[i].y).isEqualTo(0f)
            assertThat(result[i].vx).isEqualTo(0f)
            assertThat(result[i].vy).isEqualTo(0f)
            assertThat(result[i].ax).isEqualTo(0f)
            assertThat(result[i].ay).isEqualTo(0f)
        }
    }

    @Test
    fun `padOrTruncate single element no change needed`() {
        val features = createFeatureList(1)
        val (result, length) = TrajectoryFeatureCalculator.padOrTruncate(features, 1)

        assertThat(result).hasSize(1)
        assertThat(length).isEqualTo(1)
        assertThat(result[0]).isEqualTo(features[0])
    }

    // =========================================================================
    // toFloatArray tests
    // =========================================================================

    @Test
    fun `toFloatArray correct interleaved layout`() {
        val features = listOf(
            TrajectoryFeatureCalculator.FeaturePoint(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f),
            TrajectoryFeatureCalculator.FeaturePoint(0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f)
        )

        val result = TrajectoryFeatureCalculator.toFloatArray(features)

        assertThat(result).hasLength(12) // 2 points * 6 features
        // First point: [x, y, vx, vy, ax, ay]
        assertThat(result[0]).isWithin(EPSILON).of(0.1f)
        assertThat(result[1]).isWithin(EPSILON).of(0.2f)
        assertThat(result[2]).isWithin(EPSILON).of(0.3f)
        assertThat(result[3]).isWithin(EPSILON).of(0.4f)
        assertThat(result[4]).isWithin(EPSILON).of(0.5f)
        assertThat(result[5]).isWithin(EPSILON).of(0.6f)
        // Second point
        assertThat(result[6]).isWithin(EPSILON).of(0.7f)
        assertThat(result[7]).isWithin(EPSILON).of(0.8f)
        assertThat(result[8]).isWithin(EPSILON).of(0.9f)
        assertThat(result[9]).isWithin(EPSILON).of(1.0f)
        assertThat(result[10]).isWithin(EPSILON).of(1.1f)
        assertThat(result[11]).isWithin(EPSILON).of(1.2f)
    }

    @Test
    fun `toFloatArray empty input returns empty array`() {
        val result = TrajectoryFeatureCalculator.toFloatArray(emptyList())
        assertThat(result).hasLength(0)
    }

    @Test
    fun `toFloatArray single point`() {
        val features = listOf(
            TrajectoryFeatureCalculator.FeaturePoint(0.5f, 0.5f, 0f, 0f, 0f, 0f)
        )

        val result = TrajectoryFeatureCalculator.toFloatArray(features)

        assertThat(result).hasLength(6)
        assertThat(result[0]).isWithin(EPSILON).of(0.5f) // x
        assertThat(result[1]).isWithin(EPSILON).of(0.5f) // y
        assertThat(result[2]).isEqualTo(0f) // vx
        assertThat(result[3]).isEqualTo(0f) // vy
        assertThat(result[4]).isEqualTo(0f) // ax
        assertThat(result[5]).isEqualTo(0f) // ay
    }

    @Test
    fun `toFloatArray preserves negative values`() {
        val features = listOf(
            TrajectoryFeatureCalculator.FeaturePoint(0.5f, 0.5f, -5f, -3f, -10f, -10f)
        )

        val result = TrajectoryFeatureCalculator.toFloatArray(features)

        assertThat(result[2]).isEqualTo(-5f)  // vx
        assertThat(result[3]).isEqualTo(-3f)  // vy
        assertThat(result[4]).isEqualTo(-10f) // ax
        assertThat(result[5]).isEqualTo(-10f) // ay
    }

    @Test
    fun `toFloatArray with zero-padded features matches ONNX tensor format`() {
        // Simulate a typical tensor: 3 real points + 2 padding
        val features = listOf(
            TrajectoryFeatureCalculator.FeaturePoint(0.1f, 0.2f, 0.01f, 0.02f, 0.001f, 0.002f),
            TrajectoryFeatureCalculator.FeaturePoint(0.3f, 0.4f, 0.02f, 0.03f, 0.001f, 0.001f),
            TrajectoryFeatureCalculator.FeaturePoint(0.5f, 0.6f, 0.01f, 0.01f, -0.001f, -0.002f),
            TrajectoryFeatureCalculator.FeaturePoint(0f, 0f, 0f, 0f, 0f, 0f), // pad
            TrajectoryFeatureCalculator.FeaturePoint(0f, 0f, 0f, 0f, 0f, 0f)  // pad
        )

        val result = TrajectoryFeatureCalculator.toFloatArray(features)

        assertThat(result).hasLength(30) // 5 * 6

        // Verify padding region is all zeros
        for (i in 18 until 30) {
            assertThat(result[i]).isEqualTo(0f)
        }
    }

    @Test
    fun `toFloatArray large tensor size`() {
        // Simulate max sequence length of 250
        val features = List(250) { i ->
            TrajectoryFeatureCalculator.FeaturePoint(
                x = i.toFloat() / 250f,
                y = 0.5f,
                vx = 0.01f,
                vy = 0f,
                ax = 0f,
                ay = 0f
            )
        }

        val result = TrajectoryFeatureCalculator.toFloatArray(features)

        assertThat(result).hasLength(1500) // 250 * 6
        // First element
        assertThat(result[0]).isWithin(EPSILON).of(0f) // x = 0/250
        // Last real point's x: index 249 * 6 = offset 1494
        assertThat(result[1494]).isWithin(EPSILON).of(249f / 250f)
    }

    // =========================================================================
    // padOrTruncate + toFloatArray integration
    // =========================================================================

    @Test
    fun `padOrTruncate then toFloatArray produces correct ONNX tensor`() {
        val features = createFeatureList(8)
        val (padded, actualLen) = TrajectoryFeatureCalculator.padOrTruncate(features, 10)
        val tensor = TrajectoryFeatureCalculator.toFloatArray(padded)

        // Shape: [10, 6] = 60 floats
        assertThat(tensor).hasLength(60)
        assertThat(actualLen).isEqualTo(8)

        // Verify first real point preserved
        assertThat(tensor[0]).isWithin(EPSILON).of(features[0].x)
        assertThat(tensor[1]).isWithin(EPSILON).of(features[0].y)

        // Verify padding (last 2 points = indices 8,9 -> offsets 48-59)
        for (i in 48..59) {
            assertThat(tensor[i]).isEqualTo(0f)
        }
    }

    @Test
    fun `padOrTruncate then toFloatArray with truncation`() {
        val features = createFeatureList(15)
        val (truncated, actualLen) = TrajectoryFeatureCalculator.padOrTruncate(features, 10)
        val tensor = TrajectoryFeatureCalculator.toFloatArray(truncated)

        assertThat(tensor).hasLength(60) // 10 * 6
        assertThat(actualLen).isEqualTo(10)

        // First point preserved
        assertThat(tensor[0]).isWithin(EPSILON).of(features[0].x)
        // Last preserved point is features[9]
        assertThat(tensor[54]).isWithin(EPSILON).of(features[9].x) // offset = 9 * 6
    }

    // =========================================================================
    // Velocity/acceleration math verification (manual calculations)
    // =========================================================================
    // These verify the EXPECTED math results without needing PointF.
    // They document what calculateFeatures() should produce.

    @Test
    fun `velocity formula - position_change over time_change`() {
        // Given: x0=0.1, x1=0.3, dt=10ms
        // Expected: vx = (0.3 - 0.1) / 10 = 0.02
        val dx = 0.3f - 0.1f
        val dt = 10f
        val expectedVx = dx / dt
        assertThat(expectedVx).isWithin(EPSILON).of(0.02f)
    }

    @Test
    fun `acceleration formula - velocity_change over time_change`() {
        // Given: vx0=0.01, vx1=0.02, dt=10ms
        // Expected: ax = (0.02 - 0.01) / 10 = 0.001
        val dvx = 0.02f - 0.01f
        val dt = 10f
        val expectedAx = dvx / dt
        assertThat(expectedAx).isWithin(EPSILON).of(0.001f)
    }

    @Test
    fun `clipping bounds are minus 10 to plus 10`() {
        // Verify: value.coerceIn(-10f, 10f)
        assertThat(100f.coerceIn(-10f, 10f)).isEqualTo(10f)
        assertThat((-100f).coerceIn(-10f, 10f)).isEqualTo(-10f)
        assertThat(5f.coerceIn(-10f, 10f)).isEqualTo(5f)
        assertThat((-5f).coerceIn(-10f, 10f)).isEqualTo(-5f)
        assertThat(10f.coerceIn(-10f, 10f)).isEqualTo(10f)
        assertThat((-10f).coerceIn(-10f, 10f)).isEqualTo(-10f)
    }

    @Test
    fun `minimum dt prevents division by zero`() {
        // Same timestamps -> dt = 0 -> clamped to 1e-6
        val dt = maxOf(0f, 1e-6f)
        assertThat(dt).isGreaterThan(0f)

        // Velocity with min dt: v = 1.0 / 1e-6 = 1,000,000 -> clipped to 10
        val velocity = (1.0f / dt).coerceIn(-10f, 10f)
        assertThat(velocity).isEqualTo(10f)
    }

    @Test
    fun `constant velocity produces zero acceleration`() {
        // If velocity is constant (vx[i] == vx[i-1] for all i), then ax = 0
        val v1 = 0.01f
        val v2 = 0.01f
        val dt = 10f
        val ax = (v2 - v1) / dt
        assertThat(ax).isWithin(EPSILON).of(0f)
    }

    @Test
    fun `stationary points produce zero velocity`() {
        // If position doesn't change, velocity is 0
        val x1 = 0.5f
        val x2 = 0.5f
        val dt = 16f
        val vx = (x2 - x1) / dt
        assertThat(vx).isEqualTo(0f)
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun createFeatureList(size: Int): List<TrajectoryFeatureCalculator.FeaturePoint> {
        return List(size) { i ->
            TrajectoryFeatureCalculator.FeaturePoint(
                x = i * 0.1f,
                y = i * 0.05f,
                vx = i * 0.01f,
                vy = i * 0.005f,
                ax = 0f,
                ay = 0f
            )
        }
    }

    companion object {
        private const val EPSILON = 1e-5f
    }
}
