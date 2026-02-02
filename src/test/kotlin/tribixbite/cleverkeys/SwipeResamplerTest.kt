package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for SwipeResampler trajectory resampling.
 */
class SwipeResamplerTest {

    // =========================================================================
    // Null and empty input handling
    // =========================================================================

    @Test
    fun `resample null returns null`() {
        val result = SwipeResampler.resample(null, 10, SwipeResampler.ResamplingMode.TRUNCATE)
        assertThat(result).isNull()
    }

    @Test
    fun `resample empty array returns empty`() {
        val result = SwipeResampler.resample(emptyArray(), 10, SwipeResampler.ResamplingMode.TRUNCATE)
        assertThat(result).isEmpty()
    }

    @Test
    fun `resample shorter than target returns original`() {
        val data = createTrajectory(5, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.TRUNCATE)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun `resample equal to target returns original`() {
        val data = createTrajectory(10, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.TRUNCATE)

        assertThat(result).isEqualTo(data)
    }

    // =========================================================================
    // TRUNCATE mode tests
    // =========================================================================

    @Test
    fun `truncate mode keeps first N points`() {
        val data = createSequentialTrajectory(20, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.TRUNCATE)

        assertThat(result).hasLength(10)
        // First point should be [0, 0]
        assertThat(result!![0][0]).isEqualTo(0f)
        // 10th point should be [9, 9]
        assertThat(result[9][0]).isEqualTo(9f)
    }

    @Test
    fun `truncate preserves feature count`() {
        val data = createTrajectory(20, 5)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.TRUNCATE)

        assertThat(result).hasLength(10)
        assertThat(result!![0]).hasLength(5)
    }

    // =========================================================================
    // DISCARD mode tests
    // =========================================================================

    @Test
    fun `discard mode preserves first and last points`() {
        val data = createSequentialTrajectory(20, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.DISCARD)

        assertThat(result).hasLength(10)
        // First point preserved
        assertThat(result!![0][0]).isEqualTo(0f)
        // Last point preserved
        assertThat(result[9][0]).isEqualTo(19f)
    }

    @Test
    fun `discard mode with target 1 keeps first point`() {
        val data = createSequentialTrajectory(20, 2)
        val result = SwipeResampler.resample(data, 1, SwipeResampler.ResamplingMode.DISCARD)

        assertThat(result).hasLength(1)
        assertThat(result!![0][0]).isEqualTo(0f)
    }

    @Test
    fun `discard mode with target 2 keeps first and last`() {
        val data = createSequentialTrajectory(20, 2)
        val result = SwipeResampler.resample(data, 2, SwipeResampler.ResamplingMode.DISCARD)

        assertThat(result).hasLength(2)
        assertThat(result!![0][0]).isEqualTo(0f)
        assertThat(result[1][0]).isEqualTo(19f)
    }

    @Test
    fun `discard mode preserves feature count`() {
        val data = createTrajectory(20, 5)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.DISCARD)

        assertThat(result).hasLength(10)
        assertThat(result!![0]).hasLength(5)
    }

    // =========================================================================
    // MERGE mode tests
    // =========================================================================

    @Test
    fun `merge mode reduces to target length`() {
        val data = createSequentialTrajectory(20, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.MERGE)

        assertThat(result).hasLength(10)
    }

    @Test
    fun `merge mode averages neighboring points`() {
        // Create trajectory where each point has value equal to its index
        val data = createSequentialTrajectory(10, 1)
        val result = SwipeResampler.resample(data, 5, SwipeResampler.ResamplingMode.MERGE)

        assertThat(result).hasLength(5)
        // Each output point should be average of 2 input points
        // Point 0: avg of [0, 1] = 0.5
        assertThat(result!![0][0]).isWithin(0.01f).of(0.5f)
        // Point 1: avg of [2, 3] = 2.5
        assertThat(result[1][0]).isWithin(0.01f).of(2.5f)
    }

    @Test
    fun `merge mode preserves feature count`() {
        val data = createTrajectory(20, 5)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.MERGE)

        assertThat(result).hasLength(10)
        assertThat(result!![0]).hasLength(5)
    }

    @Test
    fun `merge mode with high reduction ratio`() {
        // 100 points down to 5 = 20:1 merge ratio
        val data = createSequentialTrajectory(100, 1)
        val result = SwipeResampler.resample(data, 5, SwipeResampler.ResamplingMode.MERGE)

        assertThat(result).hasLength(5)
        // Each point averages ~20 source points
        // Point 0: avg of [0..19] ≈ 9.5
        assertThat(result!![0][0]).isWithin(1f).of(9.5f)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `resample single point input returns original`() {
        val data = createTrajectory(1, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.DISCARD)
        // Single point is shorter than target, should return original
        assertThat(result).isEqualTo(data)
    }

    @Test
    fun `discard mode target near input length`() {
        // 11 points down to 10 — minimal reduction
        val data = createSequentialTrajectory(11, 2)
        val result = SwipeResampler.resample(data, 10, SwipeResampler.ResamplingMode.DISCARD)
        assertThat(result).hasLength(10)
        // First and last preserved
        assertThat(result!![0][0]).isEqualTo(0f)
        assertThat(result[9][0]).isEqualTo(10f)
    }

    // =========================================================================
    // parseMode tests
    // =========================================================================

    @Test
    fun `parseMode handles null`() {
        assertThat(SwipeResampler.parseMode(null))
            .isEqualTo(SwipeResampler.ResamplingMode.TRUNCATE)
    }

    @Test
    fun `parseMode handles valid modes`() {
        assertThat(SwipeResampler.parseMode("truncate"))
            .isEqualTo(SwipeResampler.ResamplingMode.TRUNCATE)
        assertThat(SwipeResampler.parseMode("discard"))
            .isEqualTo(SwipeResampler.ResamplingMode.DISCARD)
        assertThat(SwipeResampler.parseMode("merge"))
            .isEqualTo(SwipeResampler.ResamplingMode.MERGE)
    }

    @Test
    fun `parseMode is case insensitive`() {
        assertThat(SwipeResampler.parseMode("TRUNCATE"))
            .isEqualTo(SwipeResampler.ResamplingMode.TRUNCATE)
        assertThat(SwipeResampler.parseMode("Discard"))
            .isEqualTo(SwipeResampler.ResamplingMode.DISCARD)
        assertThat(SwipeResampler.parseMode("MERGE"))
            .isEqualTo(SwipeResampler.ResamplingMode.MERGE)
    }

    @Test
    fun `parseMode defaults to truncate for unknown`() {
        assertThat(SwipeResampler.parseMode("unknown"))
            .isEqualTo(SwipeResampler.ResamplingMode.TRUNCATE)
        assertThat(SwipeResampler.parseMode(""))
            .isEqualTo(SwipeResampler.ResamplingMode.TRUNCATE)
    }

    // =========================================================================
    // Real-world scenario tests
    // =========================================================================

    @Test
    fun `typical swipe resampling scenario`() {
        // Simulate a 100-point swipe being resampled to 32 for model input
        val swipe = createRealisticSwipe(100)
        val resampled = SwipeResampler.resample(swipe, 32, SwipeResampler.ResamplingMode.MERGE)

        assertThat(resampled).hasLength(32)
        // Features preserved
        assertThat(resampled!![0]).hasLength(4) // x, y, dx, dy
    }

    // =========================================================================
    // selectMiddleIndices weighted distribution tests
    // =========================================================================

    @Test
    fun `selectMiddleIndices returns all when fewer available than requested`() {
        // originalLength=5 means indices 1,2,3 available (3 middle), request 10
        val indices = SwipeResampler.selectMiddleIndices(5, 10)
        assertThat(indices).containsExactly(1, 2, 3)
    }

    @Test
    fun `selectMiddleIndices returns correct count`() {
        val indices = SwipeResampler.selectMiddleIndices(100, 10)
        assertThat(indices).hasSize(10)
    }

    @Test
    fun `selectMiddleIndices all within valid range`() {
        val indices = SwipeResampler.selectMiddleIndices(50, 8)
        for (idx in indices) {
            assertThat(idx).isAtLeast(1)
            assertThat(idx).isAtMost(48) // originalLength - 2
        }
    }

    @Test
    fun `selectMiddleIndices weighted toward edges`() {
        // With 100 points and selecting 12, start/end zones should have more density
        val indices = SwipeResampler.selectMiddleIndices(100, 12)
        // 35% of 12 = 4 points in start zone (indices ~1..30)
        // 35% of 12 = 4 points in end zone (indices ~70..98)
        val startCount = indices.count { it <= 30 }
        val endCount = indices.count { it >= 70 }
        val middleCount = indices.count { it in 31..69 }
        // Start and end combined should have more points than middle
        assertThat(startCount + endCount).isAtLeast(middleCount)
    }

    @Test
    fun `selectMiddleIndices are sorted ascending`() {
        val indices = SwipeResampler.selectMiddleIndices(80, 15)
        for (i in 1 until indices.size) {
            assertThat(indices[i]).isAtLeast(indices[i - 1])
        }
    }

    // =========================================================================
    // Helper functions
    // =========================================================================

    private fun createTrajectory(length: Int, features: Int): Array<FloatArray> {
        return Array(length) { FloatArray(features) { 0f } }
    }

    private fun createSequentialTrajectory(length: Int, features: Int): Array<FloatArray> {
        return Array(length) { i ->
            FloatArray(features) { i.toFloat() }
        }
    }

    private fun createRealisticSwipe(length: Int): Array<FloatArray> {
        // Create a swipe with x, y, dx, dy features
        return Array(length) { i ->
            val t = i.toFloat() / length
            floatArrayOf(
                t * 300f,           // x: 0 to 300
                100f + t * 50f,     // y: 100 to 150
                3f,                 // dx: constant velocity
                0.5f                // dy: slight upward
            )
        }
    }
}
