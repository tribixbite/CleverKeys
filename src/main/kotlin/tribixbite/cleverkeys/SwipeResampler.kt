package tribixbite.cleverkeys

import kotlin.math.ceil
import kotlin.math.min

/**
 * Utility class for resampling swipe trajectories to fit different model input sizes
 * Supports three resampling modes:
 * - TRUNCATE: Keep first N points (current behavior)
 * - DISCARD: Drop points uniformly with preference for keeping start/end
 * - MERGE: Average neighboring points to reduce count
 */
object SwipeResampler {
    private const val TAG = "SwipeResampler"

    enum class ResamplingMode {
        TRUNCATE,  // Keep first N points, discard rest
        DISCARD,   // Uniformly drop points with start/end preference
        MERGE      // Average neighboring points
    }

    /**
     * Resample trajectory data to target length
     *
     * @param trajectoryData Original data [N, features]
     * @param targetLength Desired output length
     * @param mode Resampling algorithm to use
     * @return Resampled data [targetLength, features]
     */
    @JvmStatic
    fun resample(
        trajectoryData: Array<FloatArray>?,
        targetLength: Int,
        mode: ResamplingMode
    ): Array<FloatArray>? {
        if (trajectoryData == null || trajectoryData.isEmpty()) {
            return trajectoryData
        }

        val originalLength = trajectoryData.size
        val numFeatures = trajectoryData[0].size

        // No resampling needed
        if (originalLength <= targetLength) {
            return trajectoryData
        }

        return when (mode) {
            ResamplingMode.TRUNCATE -> resampleTruncate(trajectoryData, targetLength)
            ResamplingMode.DISCARD -> resampleDiscard(trajectoryData, targetLength)
            ResamplingMode.MERGE -> resampleMerge(trajectoryData, targetLength)
        }
    }

    /**
     * TRUNCATE mode: Keep first targetLength points
     */
    private fun resampleTruncate(data: Array<FloatArray>, targetLength: Int): Array<FloatArray> {
        val numFeatures = data[0].size
        return Array(targetLength) { i ->
            data[i].copyOf()
        }
    }

    /**
     * DISCARD mode: Drop points semi-uniformly with preference for keeping start and end
     *
     * Strategy:
     * - Always keep first and last points
     * - For middle points, use weighted uniform spacing
     * - Weight more points toward start and end (crucial for word recognition)
     */
    private fun resampleDiscard(data: Array<FloatArray>, targetLength: Int): Array<FloatArray> {
        val originalLength = data.size
        val numFeatures = data[0].size
        val result = Array(targetLength) { FloatArray(numFeatures) }

        if (targetLength == 1) {
            // Edge case: keep first point
            data[0].copyInto(result[0])
            return result
        }

        // Always keep first point
        data[0].copyInto(result[0])

        // Always keep last point
        data[originalLength - 1].copyInto(result[targetLength - 1])

        if (targetLength == 2) {
            return result
        }

        // For middle points, use weighted selection
        // Preserve more points at start and end (first/last 20% of swipe)
        val numMiddle = targetLength - 2
        val selectedIndices = selectMiddleIndices(originalLength, numMiddle)

        for (i in 0 until numMiddle) {
            val sourceIdx = selectedIndices[i]
            data[sourceIdx].copyInto(result[i + 1])
        }

        return result
    }

    /**
     * Select middle indices with weighted preference for start and end
     */
    internal fun selectMiddleIndices(originalLength: Int, numMiddle: Int): List<Int> {
        val indices = mutableListOf<Int>()

        // Available indices (excluding first and last)
        val availableRange = originalLength - 2

        if (availableRange <= numMiddle) {
            // Keep all middle points
            for (i in 1 until originalLength - 1) {
                indices.add(i)
            }
            return indices
        }

        // Use weighted selection: more points at start/end
        // Split into 3 zones: start (30%), middle (40%), end (30%)
        val startZoneEnd = 1 + (availableRange * 0.3).toInt()
        val endZoneStart = originalLength - 1 - (availableRange * 0.3).toInt()

        val pointsInStart = (numMiddle * 0.35).toInt()
        val pointsInEnd = (numMiddle * 0.35).toInt()
        val pointsInMiddle = numMiddle - pointsInStart - pointsInEnd

        // Select from start zone
        for (i in 0 until pointsInStart) {
            val idx = 1 + (i * (startZoneEnd - 1)) / pointsInStart
            indices.add(idx)
        }

        // Select from middle zone
        val middleZoneSize = endZoneStart - startZoneEnd
        for (i in 0 until pointsInMiddle) {
            val idx = startZoneEnd + (i * middleZoneSize) / pointsInMiddle
            indices.add(idx)
        }

        // Select from end zone
        val endZoneSize = (originalLength - 1) - endZoneStart
        for (i in 0 until pointsInEnd) {
            val idx = endZoneStart + (i * endZoneSize) / pointsInEnd
            indices.add(idx)
        }

        return indices
    }

    /**
     * MERGE mode: Average neighboring points to reduce count
     *
     * Strategy:
     * - Calculate merge factor (how many original points per output point)
     * - For each output point, average the corresponding range of input points
     * - Preserves overall trajectory shape better than discard
     */
    private fun resampleMerge(data: Array<FloatArray>, targetLength: Int): Array<FloatArray> {
        val originalLength = data.size
        val numFeatures = data[0].size
        val result = Array(targetLength) { FloatArray(numFeatures) }

        // Calculate how many source points map to each target point
        val mergeFactor = originalLength.toFloat() / targetLength

        for (targetIdx in 0 until targetLength) {
            // Calculate source range for this target point
            val startFloat = targetIdx * mergeFactor
            val endFloat = (targetIdx + 1) * mergeFactor

            val startIdx = startFloat.toInt()
            var endIdx = ceil(endFloat).toInt()
            endIdx = min(endIdx, originalLength)

            // Average all points in this range
            val avgPoint = FloatArray(numFeatures)
            var count = 0

            for (sourceIdx in startIdx until endIdx) {
                for (f in 0 until numFeatures) {
                    avgPoint[f] += data[sourceIdx][f]
                }
                count++
            }

            // Compute average
            for (f in 0 until numFeatures) {
                result[targetIdx][f] = avgPoint[f] / count
            }
        }

        return result
    }

    /**
     * Parse resampling mode from string
     */
    @JvmStatic
    fun parseMode(modeString: String?): ResamplingMode {
        if (modeString == null) {
            return ResamplingMode.TRUNCATE
        }

        return when (modeString.lowercase()) {
            "discard" -> ResamplingMode.DISCARD
            "merge" -> ResamplingMode.MERGE
            "truncate" -> ResamplingMode.TRUNCATE
            else -> ResamplingMode.TRUNCATE
        }
    }
}
