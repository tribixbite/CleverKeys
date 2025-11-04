package tribixbite.keyboard2

import android.util.Log

/**
 * Utility for resampling swipe trajectories to fit neural model input requirements.
 * Provides three resampling strategies for reducing trajectory point counts while
 * preserving gesture shape and critical start/end information.
 *
 * Features:
 * - TRUNCATE: Simple first-N points (fastest, loses end information)
 * - DISCARD: Weighted uniform sampling (preserves start/end, good for most cases)
 * - MERGE: Averaging neighboring points (best shape preservation, slower)
 *
 * Performance:
 * - TRUNCATE: O(N) with minimal overhead
 * - DISCARD: O(N) with weighted selection
 * - MERGE: O(N*F) where F = feature count
 *
 * Use Cases:
 * - TRUNCATE: Fast preview, real-time visualization
 * - DISCARD: Production swipe recognition (recommended)
 * - MERGE: High-accuracy mode, post-processing
 *
 * Ported from Java to Kotlin with improvements.
 */
object SwipeResampler {

    private const val TAG = "SwipeResampler"

    /**
     * Resampling strategies for trajectory downsampling
     */
    enum class ResamplingMode {
        /** Keep first N points, discard rest - fastest but loses end information */
        TRUNCATE,

        /** Uniformly drop points with weighted preference for start/end - recommended */
        DISCARD,

        /** Average neighboring points for smooth reduction - best quality */
        MERGE
    }

    /**
     * Resample trajectory data to target length using specified strategy
     *
     * @param trajectoryData Original data [N, features] where N > targetLength
     * @param targetLength Desired output length (must be > 0)
     * @param mode Resampling algorithm to use
     * @return Resampled data [targetLength, features] or original if no resampling needed
     */
    fun resample(
        trajectoryData: Array<FloatArray>,
        targetLength: Int,
        mode: ResamplingMode = ResamplingMode.DISCARD
    ): Array<FloatArray> {
        if (trajectoryData.isEmpty()) {
            return trajectoryData
        }

        val originalLength = trajectoryData.size

        // No resampling needed if already at or below target
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
     *
     * Fastest resampling method but loses end-of-swipe information.
     * Only recommended for real-time visualization or when end points
     * are not critical for recognition.
     *
     * Time complexity: O(N*F) where N = targetLength, F = features
     * Space complexity: O(N*F)
     */
    private fun resampleTruncate(
        data: Array<FloatArray>,
        targetLength: Int
    ): Array<FloatArray> {
        val numFeatures = data[0].size
        val result = Array(targetLength) { FloatArray(numFeatures) }

        for (i in 0 until targetLength) {
            data[i].copyInto(result[i])
        }

        return result
    }

    /**
     * DISCARD mode: Drop points with weighted preference for start and end
     *
     * Recommended for production use. Preserves critical start/end information
     * while uniformly sampling middle trajectory. Uses 35/30/35 weighting for
     * start/middle/end zones to ensure gesture extremities are well-represented.
     *
     * Strategy:
     * - Always keep first and last points
     * - Split trajectory into 3 zones: start (30%), middle (40%), end (30%)
     * - Allocate 35% of output points to start zone
     * - Allocate 30% of output points to middle zone
     * - Allocate 35% of output points to end zone
     *
     * Time complexity: O(N*F) where N = originalLength, F = features
     * Space complexity: O(N*F)
     */
    private fun resampleDiscard(
        data: Array<FloatArray>,
        targetLength: Int
    ): Array<FloatArray> {
        val originalLength = data.size
        val numFeatures = data[0].size
        val result = Array(targetLength) { FloatArray(numFeatures) }

        // Edge case: single point
        if (targetLength == 1) {
            data[0].copyInto(result[0])
            return result
        }

        // Always keep first point
        data[0].copyInto(result[0])

        // Always keep last point
        data[originalLength - 1].copyInto(result[targetLength - 1])

        // Edge case: two points only
        if (targetLength == 2) {
            return result
        }

        // Select middle points with weighted preference for start/end
        val numMiddle = targetLength - 2
        val selectedIndices = selectMiddleIndices(originalLength, numMiddle)

        for (i in 0 until numMiddle) {
            val sourceIdx = selectedIndices[i]
            data[sourceIdx].copyInto(result[i + 1])
        }

        return result
    }

    /**
     * Select middle indices with weighted preference for start and end zones
     *
     * Ensures that the beginning and end of swipe gestures (which contain
     * critical letter information) are better represented in the resampled
     * trajectory than the middle transitional movement.
     */
    private fun selectMiddleIndices(originalLength: Int, numMiddle: Int): List<Int> {
        val indices = mutableListOf<Int>()

        // Available indices (excluding first and last)
        val availableRange = originalLength - 2

        // Keep all middle points if there aren't too many
        if (availableRange <= numMiddle) {
            for (i in 1 until originalLength - 1) {
                indices.add(i)
            }
            return indices
        }

        // Weighted selection: more points at start/end
        // Zone boundaries: start (30%), middle (40%), end (30%)
        val startZoneEnd = 1 + (availableRange * 0.3).toInt()
        val endZoneStart = originalLength - 1 - (availableRange * 0.3).toInt()

        // Point allocation: start (35%), middle (30%), end (35%)
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
     * Best quality resampling that preserves trajectory shape by averaging
     * groups of neighboring points. Slower than other methods but produces
     * smoothest output with best geometric fidelity to original gesture.
     *
     * Strategy:
     * - Calculate merge factor (ratio of original to target length)
     * - For each output point, average all input points in its window
     * - Windows overlap to ensure smooth transitions
     *
     * Time complexity: O(N*M*F) where N = originalLength, M = mergeFactor, F = features
     * Space complexity: O(T*F) where T = targetLength
     */
    private fun resampleMerge(
        data: Array<FloatArray>,
        targetLength: Int
    ): Array<FloatArray> {
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
            val endIdx = kotlin.math.ceil(endFloat).toInt().coerceAtMost(originalLength)

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
     * Parse resampling mode from string (case-insensitive)
     *
     * @param modeString String representation: "truncate", "discard", or "merge"
     * @return Corresponding ResamplingMode, defaults to TRUNCATE for invalid input
     */
    fun parseMode(modeString: String?): ResamplingMode {
        return when (modeString?.lowercase()) {
            "discard" -> ResamplingMode.DISCARD
            "merge" -> ResamplingMode.MERGE
            "truncate" -> ResamplingMode.TRUNCATE
            else -> {
                if (modeString != null) {
                    Log.w(TAG, "Unknown resampling mode '$modeString', defaulting to TRUNCATE")
                }
                ResamplingMode.TRUNCATE
            }
        }
    }

    /**
     * Get statistics about resampling operation
     *
     * @param original Original trajectory size
     * @param resampled Resampled trajectory size
     * @return Human-readable statistics string
     */
    fun getResamplingStats(original: Int, resampled: Int): String {
        val reductionPercent = ((original - resampled).toFloat() / original * 100).toInt()
        val compressionRatio = original.toFloat() / resampled

        return buildString {
            append("SwipeResampler Statistics:\n")
            append("- Original points: $original\n")
            append("- Resampled points: $resampled\n")
            append("- Reduction: $reductionPercent%\n")
            append("- Compression ratio: ${"%.2f".format(compressionRatio)}:1\n")
        }
    }

    /**
     * Validate trajectory data before resampling
     *
     * @param data Trajectory data to validate
     * @param targetLength Desired target length
     * @return true if data is valid for resampling
     */
    fun isValidTrajectory(data: Array<FloatArray>?, targetLength: Int): Boolean {
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "Invalid trajectory: null or empty")
            return false
        }

        if (targetLength <= 0) {
            Log.w(TAG, "Invalid target length: $targetLength")
            return false
        }

        val numFeatures = data[0].size
        if (numFeatures == 0) {
            Log.w(TAG, "Invalid trajectory: no features")
            return false
        }

        // Check all rows have same feature count
        for (i in data.indices) {
            if (data[i].size != numFeatures) {
                Log.w(TAG, "Invalid trajectory: inconsistent feature count at row $i")
                return false
            }
        }

        return true
    }
}
