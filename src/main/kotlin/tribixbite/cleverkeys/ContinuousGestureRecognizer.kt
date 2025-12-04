package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Continuous Gesture Recognizer Library (CGR)
 *
 * Port of the CGR library from Lua to Java, now migrated to Kotlin
 *
 * Original copyright notice:
 *
 * If you use this code for your research then please remember to cite our paper:
 *
 * Kristensson, P.O. and Denby, L.C. 2011. Continuous recognition and visualization
 * of pen strokes and touch-screen gestures. In Proceedings of the 8th Eurographics
 * Symposium on Sketch-Based Interfaces and Modeling (SBIM 2011). ACM Press: 95-102.
 *
 * Copyright (C) 2011 by Per Ola Kristensson, University of St Andrews, UK.
 */
class ContinuousGestureRecognizer {

    // Configurable parameters (KEYBOARD-OPTIMAL defaults, not paper defaults)
    private var currentESigma = 120.0  // Lower for keyboard constraints (was 200)
    private var currentBeta = 400.0    // Keep same variance ratio
    private var currentLambda = 0.65   // Higher Euclidean weight for predictable positions (was 0.4)
    private var currentKappa = 2.5     // Higher end-point bias for specific keys (was 1.0)
    private var currentLengthFilter = 0.70 // User-configurable length similarity threshold

    // Global pattern set with permanent partitioning for parallel processing
    private val patterns = mutableListOf<Pattern>()
    private val patternPartitions = mutableListOf<List<Pattern>>()

    /**
     * Point class (equivalent to vec2 in Lua)
     */
    data class Point(@JvmField var x: Double, @JvmField var y: Double) {
        constructor(other: Point) : this(other.x, other.y)
    }

    /**
     * Rectangle class
     */
    data class Rect(
        @JvmField var x: Double,
        @JvmField var y: Double,
        @JvmField var width: Double,
        @JvmField var height: Double
    )

    /**
     * Centroid class
     */
    data class Centroid(@JvmField var x: Double, @JvmField var y: Double)

    /**
     * Template class
     */
    data class Template(
        @JvmField val id: String,
        @JvmField val pts: MutableList<Point>
    )

    /**
     * Pattern class
     */
    data class Pattern(
        @JvmField val template: Template,
        @JvmField val segments: MutableList<List<Point>>
    )

    /**
     * IncrementalResult class
     */
    data class IncrementalResult(
        @JvmField val pattern: Pattern,
        @JvmField var prob: Double,
        @JvmField val indexOfMostLikelySegment: Int
    )

    /**
     * Result class
     */
    data class Result(
        @JvmField val template: Template,
        @JvmField val prob: Double,
        @JvmField val pts: List<Point>
    )

    companion object {
        private const val TAG = "CGR"

        // Default constants for fallback
        private const val DEFAULT_E_SIGMA = 200.0
        private const val DEFAULT_BETA = 400.0
        private const val DEFAULT_LAMBDA = 0.4
        private const val DEFAULT_KAPPA = 1.0

        private const val MAX_RESAMPLING_PTS = 5000 // Increased to support long words like 'wonderful' (was 3500)
        private const val SAMPLE_POINT_DISTANCE = 10 // Restored original value for accuracy

        // Normalized space
        private val NORMALIZED_SPACE = Rect(0.0, 0.0, 1000.0, 1000.0)

        // Global thread pool
        private val THREAD_COUNT = min(4, Runtime.getRuntime().availableProcessors())
        private val parallelExecutor: ExecutorService = Executors.newFixedThreadPool(THREAD_COUNT)

        /**
         * Create directional templates (compass points)
         */
        @JvmStatic
        fun createDirectionalTemplates(): List<Template> {
            return listOf(
                // North
                Template("North", mutableListOf(Point(0.0, 0.0), Point(0.0, -1.0))),
                // South
                Template("South", mutableListOf(Point(0.0, 0.0), Point(0.0, 1.0))),
                // West
                Template("West", mutableListOf(Point(0.0, 0.0), Point(-1.0, 0.0))),
                // East
                Template("East", mutableListOf(Point(0.0, 0.0), Point(1.0, 0.0))),
                // NorthWest
                Template("NorthWest", mutableListOf(Point(0.0, 0.0), Point(-1.0, -1.0))),
                // NorthEast
                Template("NorthEast", mutableListOf(Point(0.0, 0.0), Point(1.0, -1.0))),
                // SouthWest
                Template("SouthWest", mutableListOf(Point(0.0, 0.0), Point(-1.0, 1.0))),
                // SouthEast
                Template("SouthEast", mutableListOf(Point(0.0, 0.0), Point(1.0, 1.0)))
            )
        }

        /**
         * Create templates from PointF array (for Android integration)
         */
        @JvmStatic
        fun fromPointFList(pointFs: List<PointF>): List<Point> {
            return pointFs.map { Point(it.x.toDouble(), it.y.toDouble()) }
        }

        /**
         * Convert back to PointF array (for Android integration)
         */
        @JvmStatic
        fun toPointFList(points: List<Point>): List<PointF> {
            return points.map { PointF(it.x.toFloat(), it.y.toFloat()) }
        }
    }

    init {
        Log.d(TAG, "Parallel executor initialized with $THREAD_COUNT threads (CPU cores: ${Runtime.getRuntime().availableProcessors()})")
    }

    // ============================================================================
    // UTILITY METHODS
    // ============================================================================

    /**
     * Convert points list to array
     */
    private fun toArray(points: List<Point>): DoubleArray {
        val out = DoubleArray(points.size * 2)
        points.forEachIndexed { i, pt ->
            out[i * 2] = pt.x
            out[i * 2 + 1] = pt.y
        }
        return out
    }

    /**
     * Deep copy points list
     */
    private fun deepCopyPts(p1: List<Point>): MutableList<Point> {
        return p1.map { Point(it.x, it.y) }.toMutableList()
    }

    /**
     * Get bounding box of points
     */
    private fun getBoundingBox(pts: List<Point>): Rect {
        var minX = 1000000.0
        var minY = 1000000.0
        var maxX = -1000000.0
        var maxY = -1000000.0

        for (pt in pts) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }

        return Rect(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * Get centroid of points
     */
    private fun getCentroid(pts: List<Point>): Centroid {
        val totalMass = pts.size.toDouble()
        var xIntegral = 0.0
        var yIntegral = 0.0

        for (pt in pts) {
            xIntegral += pt.x
            yIntegral += pt.y
        }

        return Centroid(xIntegral / totalMass, yIntegral / totalMass)
    }

    /**
     * Translate points by dx, dy
     */
    private fun translate(pts: MutableList<Point>, dx: Double, dy: Double) {
        for (pt in pts) {
            pt.x = floor(dx) + pt.x
            pt.y = floor(dy) + pt.y
        }
    }

    /**
     * Scale points
     */
    private fun scale(pts: MutableList<Point>, sx: Double, sy: Double) {
        for (pt in pts) {
            pt.x = pt.x * sx
            pt.y = pt.y * sy
        }
    }

    /**
     * Scale points with origin
     */
    private fun scale(pts: MutableList<Point>, sx: Double, sy: Double, originX: Double, originY: Double) {
        translate(pts, -originX, -originY)
        scale(pts, sx, sy)
        translate(pts, originX, originY)
    }

    /**
     * Calculate distance between two points
     */
    private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = abs(x2 - x1)
        val dy = abs(y2 - y1)
        val fac = min(dx, dy)
        return dx + dy - (fac / 2)
    }

    /**
     * Calculate distance between two points
     */
    private fun distance(p1: Point, p2: Point): Double {
        return distance(p1.x, p1.y, p2.x, p2.y)
    }

    /**
     * Get spatial length of path
     */
    private fun getSpatialLength(pts: List<Point>): Double {
        var len = 0.0
        var prev: Point? = null

        for (pt in pts) {
            if (prev != null) {
                len += distance(prev, pt)
            }
            prev = pt
        }

        return floor(len)
    }

    /**
     * Get spatial length of array path
     */
    private fun getSpatialLength(pat: DoubleArray, n: Int): Double {
        var l = 0.0
        val m = 2 * n

        if (m > 2) {
            var x1 = pat[0]
            var y1 = pat[1]

            for (i in 2 until m step 2) {
                val x2 = pat[i]
                val y2 = pat[i + 1]
                l += distance(x1, y1, x2, y2)
                x1 = x2
                y1 = y2
            }

            return floor(l)
        }

        return 0.0
    }

    /**
     * Get resampling point count
     */
    private fun getResamplingPointCount(pts: List<Point>, samplePointDistance: Int): Int {
        val len = getSpatialLength(pts)
        return floor(len / samplePointDistance + 1).toInt()
    }

    /**
     * Get segment points
     */
    private fun getSegmentPoints(pts: DoubleArray, n: Int, length: Double, buffer: DoubleArray): Double {
        val m = n * 2
        var rest = 0.0
        var x1 = pts[0]
        var y1 = pts[1]

        for (i in 2 until m step 2) {
            val x2 = pts[i]
            val y2 = pts[i + 1]
            var currentLen = distance(x1, y1, x2, y2)
            currentLen += rest
            rest = 0.0
            var ps = currentLen / length

            if (ps == 0.0) {
                rest += currentLen
            } else {
                rest += currentLen - (ps * length)
            }

            if (i == 2 && ps == 0.0) {
                ps = 1.0
            }

            buffer[(i / 2) - 1] = ps
            x1 = x2
            y1 = y2
        }

        return rest
    }

    /**
     * Resample points (two parameter version)
     */
    fun resample(points: List<Point>, numTargetPoints: Int): MutableList<Point> {
        val r = mutableListOf<Point>()
        val inArray = toArray(points)
        val outArray = DoubleArray(numTargetPoints * 2)
        resample(inArray, outArray, points.size, numTargetPoints)

        for (i in outArray.indices step 2) {
            r.add(Point(outArray[i], outArray[i + 1]))
        }

        return r
    }

    /**
     * Resample points (four parameter version)
     */
    private fun resample(template: DoubleArray, buffer: DoubleArray, n: Int, numTargetPoints: Int) {
        val segmentBuf = DoubleArray(n)
        val m = n * 2
        val l = getSpatialLength(template, n)
        val segmentLen = l / (numTargetPoints - 1)
        getSegmentPoints(template, n, segmentLen, segmentBuf)

        var horizRest = 0.0
        var verticRest = 0.0
        var x1 = template[0]
        var y1 = template[1]
        var a = 0
        val maxOutputs = numTargetPoints * 2

        for (i in 2 until m step 2) {
            val x2 = template[i]
            val y2 = template[i + 1]
            val segmentPoints = segmentBuf[(i / 2) - 1]
            var dx = -1.0
            var dy = -1.0

            if ((segmentPoints - 1) <= 0) {
                dx = 0.0
                dy = 0.0
            } else {
                dx = (x2 - x1) / segmentPoints
                dy = (y2 - y1) / segmentPoints
            }

            if (segmentPoints > 0) {
                for (j in 1..segmentPoints.toInt()) {
                    if (j == 1) {
                        if (a < maxOutputs - 1) {
                            buffer[a] = x1 + horizRest
                            buffer[a + 1] = y1 + verticRest
                            horizRest = 0.0
                            verticRest = 0.0
                            a += 2
                        }
                    } else {
                        if (a < maxOutputs - 1) {
                            buffer[a] = x1 + j * dx
                            buffer[a + 1] = y1 + j * dy
                            a += 2
                        }
                    }
                }
            }

            x1 = x2
            y1 = y2
        }

        val theEnd = (numTargetPoints * 2) - 2
        if (a < theEnd && a >= 2) {
            for (i in a until theEnd step 2) {
                // Add bounds checking to prevent array access errors
                if (i >= 2) {
                    buffer[i] = (buffer[i - 2] + template[m - 2]) / 2
                    buffer[i + 1] = (buffer[i - 1] + template[m - 1]) / 2
                }
            }
        }

        buffer[maxOutputs - 2] = template[m - 2]
        buffer[maxOutputs - 1] = template[m - 1]
    }

    /**
     * Generate equidistant progressive subsequences
     */
    private fun generateEquiDistantProgressiveSubSequences(pts: List<Point>, ptSpacing: Int): List<List<Point>> {
        val sequences = mutableListOf<List<Point>>()
        val nSamplePoints = getResamplingPointCount(pts, ptSpacing)
        val resampledPts = resample(pts, nSamplePoints)

        for (i in 1..resampledPts.size) {
            val seq = deepCopyPts(resampledPts.subList(0, i))
            sequences.add(seq)
        }

        return sequences
    }

    /**
     * Scale to target bounds
     */
    private fun scaleTo(pts: MutableList<Point>, targetBounds: Rect) {
        val bounds = getBoundingBox(pts)
        val a1 = targetBounds.width
        val a2 = targetBounds.height
        val b1 = bounds.width
        val b2 = bounds.height
        val scaleValue = sqrt(a1 * a1 + a2 * a2) / sqrt(b1 * b1 + b2 * b2)
        scale(pts, scaleValue, scaleValue, bounds.x, bounds.y)
    }

    /**
     * Normalize points
     */
    private fun normalize(pts: MutableList<Point>, x: Double?, y: Double?, width: Double?, height: Double?): MutableList<Point>? {
        return if (x != null) {
            val out = deepCopyPts(pts)
            scaleTo(out, Rect(0.0, 0.0, width!! - x, height!! - y!!))
            val c = getCentroid(out)
            translate(out, -c.x, -c.y)
            translate(out, width - x, height - y)
            out
        } else {
            scaleTo(pts, NORMALIZED_SPACE)
            val c = getCentroid(pts)
            translate(pts, -c.x, -c.y)
            null
        }
    }

    /**
     * Normalize points (simple version)
     */
    private fun normalize(pts: MutableList<Point>) {
        normalize(pts, null, null, null, null)
    }

    /**
     * Complete normalization per research paper (centering + bounding box scaling)
     */
    private fun normalizeCompletely(pts: MutableList<Point>) {
        if (pts.isEmpty()) return

        // Step 1: Calculate centroid and translate to origin
        val centroid = getCentroid(pts)
        translate(pts, -centroid.x, -centroid.y)

        // Step 2: Scale to unit bounding box (CRITICAL - was missing!)
        val bounds = getBoundingBox(pts)
        val maxDimension = max(bounds.width, bounds.height)

        if (maxDimension > 0) {
            val scaleFactor = 1.0 / maxDimension // Scale to unit bounding box
            scale(pts, scaleFactor, scaleFactor)
        }
    }

    // ============================================================================
    // PATTERN MATCHING & RECOGNITION
    // ============================================================================

    /**
     * Set template set (simplified for parallel processing)
     */
    fun setTemplateSet(templates: List<Template>) {
        patterns.clear()

        Log.d(TAG, "Processing ${templates.size} templates for parallel recognition...")

        for (t in templates) {
            // FIX: Don't normalize templates here - they should already be in correct coordinate space
            // normalize(t.pts) // REMOVED: This was corrupting template coordinates

            // MEMORY OPTIMIZATION: Choose between real-time vs memory efficiency
            val segments: MutableList<List<Point>>

            // OPTION 1: Real-time predictions (COMMENTED OUT due to memory constraints)
            // TO RE-ENABLE REAL-TIME PREDICTIONS:
            // 1. Uncomment the line below
            // 2. Comment out the single segment option
            // 3. Test on device with more memory or reduce vocabulary size
            // segments = generateEquiDistantProgressiveSubSequences(t.pts, 400).toMutableList()

            // OPTION 2: Memory-efficient single segment (CURRENT - prevents OutOfMemoryError)
            segments = mutableListOf()
            segments.add(deepCopyPts(t.pts)) // Use copy to preserve original template

            val pattern = Pattern(t, segments)
            patterns.add(pattern)
        }

        // FIX: Skip template preprocessing that corrupts coordinates
        // Templates should be used as-is from WordGestureTemplateGenerator
        // The paper's approach normalizes during comparison, not during template creation

        // Create permanent partitions for parallel processing (no copying during recognition)
        patternPartitions.clear()
        val partitionSize = (patterns.size + THREAD_COUNT - 1) / THREAD_COUNT // Round up division

        for (i in 0 until THREAD_COUNT) {
            val startIdx = i * partitionSize
            val endIdx = min(startIdx + partitionSize, patterns.size)

            if (startIdx < patterns.size) {
                val partition = patterns.subList(startIdx, endIdx)
                patternPartitions.add(partition)
            }
        }

        Log.d(TAG, "Created ${patternPartitions.size} permanent partitions for ${patterns.size} patterns")
    }

    /**
     * Marginalize incremental results
     */
    private fun marginalizeIncrementalResults(results: List<IncrementalResult>) {
        var totalMass = 0.0
        for (r in results) {
            totalMass += r.prob
        }

        for (r in results) {
            r.prob = r.prob / totalMass
        }
    }

    /**
     * Get squared Euclidean distance
     */
    private fun getSquaredEuclideanDistance(pt1: Point, pt2: Point): Double {
        return (pt1.x - pt2.x) * (pt1.x - pt2.x) + (pt1.y - pt2.y) * (pt1.y - pt2.y)
    }

    /**
     * Get Euclidean distance
     */
    private fun getEuclideanDistance(pt1: Point, pt2: Point): Double {
        return sqrt(getSquaredEuclideanDistance(pt1, pt2))
    }

    /**
     * Get Euclidean distance by list
     */
    private fun getEuclideanDistanceByList(pts1: List<Point>, pts2: List<Point>): Double {
        val n = min(pts1.size, pts2.size)

        var td = 0.0
        for (i in 0 until n) {
            td += getEuclideanDistance(pts1[i], pts2[i])
        }

        return td / n
    }

    /**
     * Get turning angle distance between two line segments
     */
    private fun getTurningAngleDistance(ptA1: Point, ptA2: Point, ptB1: Point, ptB2: Point): Double {
        val lenA = getEuclideanDistance(ptA1, ptA2)
        val lenB = getEuclideanDistance(ptB1, ptB2)

        if (lenA == 0.0 || lenB == 0.0) {
            return 0.0
        }

        val cos = (((ptA1.x - ptA2.x) * (ptB1.x - ptB2.x) + (ptA1.y - ptA2.y) * (ptB1.y - ptB2.y)) / (lenA * lenB))

        if (abs(cos) > 1.0) {
            return 0.0
        }

        return acos(cos)
    }

    /**
     * Get turning angle distance between point lists
     */
    private fun getTurningAngleDistance(pts1: List<Point>, pts2: List<Point>): Double {
        val n = min(pts1.size, pts2.size)

        var td = 0.0
        for (i in 0 until n - 1) {
            td += abs(getTurningAngleDistance(pts1[i], pts1[i + 1], pts2[i], pts2[i + 1]))
        }

        if (td.isNaN()) {
            return 0.0
        }

        return td / (n - 1)
    }

    /**
     * Get likelihood of match
     */
    private fun getLikelihoodOfMatch(pts1: List<Point>, pts2: List<Point>, eSigma: Double, aSigma: Double, lambda: Double): Double {
        require(eSigma > 0) { "eSigma must be positive" }
        require(aSigma > 0) { "aSigma must be positive" }
        require(lambda in 0.0..1.0) { "lambda must be in the range between zero and one" }

        // DEBUG: Check if turning angle calculation is causing zero probabilities
        val xE = getEuclideanDistanceByList(pts1, pts2)
        val xA = getTurningAngleDistance(pts1, pts2)

        Log.d(TAG, String.format("Distance calc: x_e=%.6f, x_a=%.6f, eSigma=%.1f, aSigma=%.1f", xE, xA, eSigma, aSigma))

        // Temporarily restore combined distance to debug
        val result = exp(-(xE * xE / (eSigma * eSigma) * lambda + xA * xA / (aSigma * aSigma) * (1 - lambda)))
        Log.d(TAG, String.format("Probability result: %.6f", result))

        return result
    }

    /**
     * Get incremental result (ORIGINAL - kept for compatibility)
     */
    private fun getIncrementalResult(unkPts: List<Point>, pattern: Pattern, beta: Double, lambda: Double, eSigma: Double): IncrementalResult {
        val segments = pattern.segments
        var maxProb = 0.0
        var maxIndex = -1

        for (i in segments.indices) {
            val templatePts = segments[i]
            // PAPER'S APPROACH: Resample user gesture to template size, then center both (no resizing)
            val userResampledToTemplate = resample(unkPts, templatePts.size)

            // Apply paper's normalization: "translating them so their centroids are at origin"
            // Template bounding box already matches keyboard - just center both for translation invariance
            val centeredUser = deepCopyPts(userResampledToTemplate)
            val centeredTemplate = deepCopyPts(templatePts)

            // Complete normalization per research paper (centering + unit bounding box)
            normalizeCompletely(centeredUser)
            normalizeCompletely(centeredTemplate)

            val prob = getLikelihoodOfMatch(centeredUser, centeredTemplate, eSigma, eSigma / beta, lambda)

            if (prob > maxProb) {
                maxProb = prob
                maxIndex = i
            }
        }

        return IncrementalResult(pattern, maxProb, maxIndex)
    }

    /**
     * Get incremental result (OPTIMIZED - no repeated resampling)
     */
    private fun getIncrementalResultOptimized(standardizedInput: List<Point>, pattern: Pattern, beta: Double, lambda: Double, eSigma: Double): IncrementalResult {
        val segments = pattern.segments
        var maxProb = 0.0
        var maxIndex = -1

        for (i in segments.indices) {
            val pts = segments[i]
            // OPTIMIZATION: Use pre-resampled standardized input (all segments now have FIXED_POINT_COUNT)
            val prob = getLikelihoodOfMatch(standardizedInput, pts, eSigma, eSigma / beta, lambda)

            if (prob > maxProb) {
                maxProb = prob
                maxIndex = i
            }
        }

        return IncrementalResult(pattern, maxProb, maxIndex)
    }

    /**
     * Get results from incremental results
     */
    fun getResults(incrResults: List<IncrementalResult>): List<Result> {
        return incrResults.map { ir ->
            Result(ir.pattern.template, ir.prob, ir.pattern.segments[ir.indexOfMostLikelySegment])
        }
    }

    // ============================================================================
    // PARALLEL PROCESSING
    // ============================================================================

    /**
     * Get incremental results (MEMORY OPTIMIZED - permanent partitions)
     */
    private fun getIncrementalResults(input: List<Point>, beta: Double, lambda: Double, kappa: Double, eSigma: Double): List<IncrementalResult> {
        val incrResults = mutableListOf<IncrementalResult>()
        // FIX: Don't normalize input here - normalize during comparison per paper's approach
        val unkPts = deepCopyPts(input)

        // KEYBOARD OPTIMIZATION: Pre-filter by length before expensive recognition
        val userGestureLength = getSpatialLength(unkPts)
        val lengthFilteredPartitions = mutableListOf<List<Pattern>>()

        for (partition in patternPartitions) {
            val lengthFiltered = partition.filter { pattern ->
                val templateLength = getSpatialLength(pattern.template.pts)
                val lengthRatio = min(templateLength, userGestureLength) / max(templateLength, userGestureLength)

                // Only process templates with user-configurable length similarity (keyboard constraint)
                lengthRatio > currentLengthFilter // User-configurable threshold (default 70%)
            }
            lengthFilteredPartitions.add(lengthFiltered)
        }

        // Use permanent partitions (no copying) for memory efficiency
        val futures = mutableListOf<Future<List<IncrementalResult>>>()

        val totalCandidates = lengthFilteredPartitions.sumOf { it.size }
        Log.d(TAG, "Length-filtered ${patterns.size} to $totalCandidates candidates (user length: ${String.format("%.0f", userGestureLength)})")

        // Submit each filtered partition to thread pool (no data copying)
        for ((partitionId, partition) in lengthFilteredPartitions.withIndex()) {
            val future = parallelExecutor.submit<List<IncrementalResult>> {
                Log.d(TAG, "Thread $partitionId processing ${partition.size} patterns")
                processPartition(unkPts, partition, beta, lambda, kappa, eSigma)
            }
            futures.add(future)
        }

        // Collect results from all partitions
        try {
            for (future in futures) {
                val partitionResults = future.get(2000, TimeUnit.MILLISECONDS) // 2 second timeout
                incrResults.addAll(partitionResults)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parallel processing error: ${e.message}")
            // Fallback to single-threaded processing
            return getIncrementalResultsSingleThreaded(input, beta, lambda, kappa, eSigma)
        }

        marginalizeIncrementalResults(incrResults)
        return incrResults
    }

    /**
     * Process a permanent partition of patterns (no copying)
     */
    private fun processPartition(unkPts: List<Point>, partition: List<Pattern>, beta: Double, lambda: Double, kappa: Double, eSigma: Double): List<IncrementalResult> {
        val batchResults = mutableListOf<IncrementalResult>()

        for (pattern in partition) {
            val result = getIncrementalResult(unkPts, pattern, beta, lambda, eSigma)
            val lastSegmentPts = pattern.segments[pattern.segments.size - 1]
            // PAPER'S APPROACH: Resample user gesture to match pre-computed template size
            val userResampledToTemplate = resample(unkPts, lastSegmentPts.size)
            val completeProb = getLikelihoodOfMatch(userResampledToTemplate, lastSegmentPts, eSigma, eSigma / beta, lambda)
            val x = 1 - completeProb
            result.prob = (1 + kappa * exp(-x * x)) * result.prob
            batchResults.add(result)
        }

        return batchResults
    }

    /**
     * Create batches of patterns for parallel processing
     */
    private fun createBatches(allPatterns: List<Pattern>, batchSize: Int): List<List<Pattern>> {
        val batches = mutableListOf<List<Pattern>>()

        for (i in allPatterns.indices step batchSize) {
            val endIndex = min(i + batchSize, allPatterns.size)
            batches.add(allPatterns.subList(i, endIndex).toList())
        }

        return batches
    }

    /**
     * Fallback single-threaded processing (FIXED: Now includes length filtering)
     */
    private fun getIncrementalResultsSingleThreaded(input: List<Point>, beta: Double, lambda: Double, kappa: Double, eSigma: Double): List<IncrementalResult> {
        val incrResults = mutableListOf<IncrementalResult>()
        // FIX: Don't normalize input here - normalize during comparison per paper's approach
        val unkPts = deepCopyPts(input)

        // CRITICAL FIX: Add same length filtering as parallel path
        val userGestureLength = getSpatialLength(unkPts)
        val lengthFilteredPatterns = patterns.filter { pattern ->
            val templateLength = getSpatialLength(pattern.template.pts)
            val lengthRatio = min(templateLength, userGestureLength) / max(templateLength, userGestureLength)

            // Apply same length filter as parallel processing
            lengthRatio > currentLengthFilter
        }

        Log.d(TAG, "FALLBACK: Length-filtered ${patterns.size} to ${lengthFilteredPatterns.size} candidates (filter=${String.format("%.0f", currentLengthFilter * 100)}%)")

        for (pattern in lengthFilteredPatterns) {
            val result = getIncrementalResult(unkPts, pattern, beta, lambda, eSigma)
            val lastSegmentPts = pattern.segments[pattern.segments.size - 1]
            // PAPER'S APPROACH: Resample user gesture to match pre-computed template size
            val userResampledToTemplate = resample(unkPts, lastSegmentPts.size)
            val completeProb = getLikelihoodOfMatch(userResampledToTemplate, lastSegmentPts, eSigma, eSigma / beta, lambda)
            val x = 1 - completeProb
            result.prob = (1 + kappa * exp(-x * x)) * result.prob
            incrResults.add(result)
        }

        return incrResults
    }

    // ============================================================================
    // CONFIGURATION & PUBLIC API
    // ============================================================================

    /**
     * Load CGR parameters from preferences (called automatically)
     */
    fun loadParametersFromPreferences(context: Context) {
        try {
            // Use DirectBootAwarePreferences to match settings system
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // Load CGR algorithm parameters with immediate effect
            currentESigma = prefs.getInt("cgr_e_sigma", 120).toDouble()  // Keyboard-optimal default
            currentBeta = prefs.getInt("cgr_beta", 400).toDouble()
            currentLambda = prefs.getInt("cgr_lambda", 65) / 100.0 // Keyboard-optimal: 65%
            currentKappa = prefs.getInt("cgr_kappa", 25) / 10.0    // Keyboard-optimal: 2.5
            currentLengthFilter = prefs.getInt("cgr_length_filter", 70) / 100.0 // User-configurable filter

            Log.d(TAG, String.format("Parameters loaded from settings: σₑ=%.1f, β=%.1f, λ=%.2f, κ=%.1f, LengthFilter=%.1f%%",
                currentESigma, currentBeta, currentLambda, currentKappa, currentLengthFilter * 100))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load parameters, using defaults: ${e.message}")
            // Keep default values if loading fails
        }
    }

    /**
     * Set CGR parameters for tuning (called from settings)
     */
    fun setCGRParameters(eSigma: Double, beta: Double, lambda: Double, kappa: Double) {
        currentESigma = eSigma
        currentBeta = beta
        currentLambda = lambda
        currentKappa = kappa

        Log.d(TAG, String.format("Parameters updated: σₑ=%.1f, β=%.1f, λ=%.2f, κ=%.1f", eSigma, beta, lambda, kappa))
    }

    /**
     * Main recognition function (uses current configurable parameters)
     */
    fun recognize(input: List<Point>): List<Result> {
        return recognize(input, currentBeta, currentLambda, currentKappa, currentESigma)
    }

    /**
     * Main recognition function with parameters
     */
    fun recognize(input: List<Point>, beta: Double, lambda: Double, kappa: Double, eSigma: Double): List<Result> {
        require(input.size >= 2) { "CGR_recognize: Input must consist of at least two points" }

        val incResults = getIncrementalResults(input, beta, lambda, kappa, eSigma)
        val results = getResults(incResults)

        return results.sortedByDescending { it.prob }
    }
}
