package tribixbite.cleverkeys

import android.graphics.PointF
import android.util.Log

/**
 * Processes swipe trajectories for neural network input
 * CRITICAL FIX: Matches working cleverkeys implementation exactly
 * - Pads both coordinates AND nearestKeys to 250 (max sequence length)
 * - Uses integer token indices (not characters)
 * - Repeats last key for padding (not PAD tokens)
 * - Filters duplicate starting points
 */
class SwipeTrajectoryProcessor {
    companion object {
        private const val TAG = "SwipeTrajectoryProcessor"
    }

    // Keyboard layout for nearest key detection
    private var keyPositions: Map<Char, PointF>? = null

    @JvmField
    var keyboardWidth = 1.0f

    @JvmField
    var keyboardHeight = 1.0f

    // QWERTY area bounds for proper normalization (v1.32.463)
    // The model expects normalized coords over QWERTY keys only, not full view
    private var qwertyAreaTop = 0.0f      // Y offset where QWERTY starts (below suggestion bar, etc.)
    private var qwertyAreaHeight = 0.0f   // Height of QWERTY key area only

    // Touch Y-offset compensation (v1.32.466)
    // Users typically touch ~74 pixels above key center due to fat finger effect
    // This offset is added to raw Y coordinates before normalization
    private var touchYOffset = 0.0f

    // Resampling configuration
    // Default to DISCARD to preserve start/end of long swipes (matching Config.java default)
    private var resamplingMode: SwipeResampler.ResamplingMode = SwipeResampler.ResamplingMode.DISCARD

    // OPTIMIZATION Phase 2: Reusable lists to reduce GC pressure
    // These are cleared and reused on each call to extractFeatures()
    private val reusableNormalizedCoords = ArrayList<PointF>(250)
    private val reusableProcessedCoords = ArrayList<PointF>(250)
    private val reusableProcessedTimestamps = ArrayList<Long>(250)
    private val reusableProcessedKeys = ArrayList<Int>(250)
    private val reusablePoints = ArrayList<TrajectoryPoint>(250)

    /**
     * Set keyboard dimensions and key positions
     */
    fun setKeyboardLayout(
        keyPositions: Map<Char, PointF>,
        width: Float,
        height: Float
    ) {
        this.keyPositions = keyPositions
        this.keyboardWidth = width
        this.keyboardHeight = height
    }

    /**
     * Set QWERTY area bounds for proper coordinate normalization.
     * The neural model expects coordinates normalized over the QWERTY key area only,
     * not the full keyboard view (which may include suggestion bar, number row, etc.)
     *
     * @param qwertyTop Y offset in pixels where QWERTY keys start
     * @param qwertyHeight Height in pixels of the QWERTY key area
     */
    fun setQwertyAreaBounds(qwertyTop: Float, qwertyHeight: Float) {
        this.qwertyAreaTop = qwertyTop
        this.qwertyAreaHeight = qwertyHeight
        Log.d(TAG, "📐 QWERTY area bounds set: top=$qwertyTop, height=$qwertyHeight (full kb height=$keyboardHeight)")
    }

    /**
     * Set touch Y-offset compensation for fat finger effect.
     * Users typically touch ~74 pixels above key centers due to finger geometry.
     * This offset is added to raw Y coordinates before normalization.
     *
     * @param offset Pixels to add to Y coordinate (positive = shift down toward key center)
     */
    fun setTouchYOffset(offset: Float) {
        this.touchYOffset = offset
        Log.d(TAG, "📐 Touch Y-offset set: $offset pixels")
    }

    /**
     * Set resampling mode for trajectory processing
     */
    fun setResamplingMode(mode: SwipeResampler.ResamplingMode) {
        this.resamplingMode = mode
        Log.d(TAG, "Resampling mode set to: $mode")
    }

    /**
     * Extract trajectory features - MATCHES WORKING CLEVERKEYS
     * Takes SwipeInput for compatibility but processes like cleverkeys
     */
    fun extractFeatures(input: SwipeInput, maxSequenceLength: Int): TrajectoryFeatures {
        val coordinates = input.coordinates
        val timestamps = input.timestamps

        if (coordinates.isEmpty()) {
            return TrajectoryFeatures()
        }

        // CRITICAL: Use raw coordinates directly - model was trained on raw data
        // DO NOT filter duplicates - it corrupts actual_length and changes what model sees
        // (v1.32.470 fix)

        // OPTIMIZATION Phase 2: Recycle PointFs from previous call
        TrajectoryObjectPool.recyclePointFList(reusableNormalizedCoords)

        // 1. Normalize coordinates (0-1 range) - uses reusable list to reduce GC pressure
        normalizeCoordinates(coordinates, reusableNormalizedCoords)
        var processedCoords: List<PointF> = reusableNormalizedCoords

        // 2. Apply resampling if sequence exceeds maxSequenceLength
        var processedTimestamps: List<Long> = timestamps

        // DEBUG: Log resampling decision
        Log.d(TAG, "🔍 Resampling check: size=${reusableNormalizedCoords.size}, max=$maxSequenceLength, " +
                "mode=$resamplingMode, needsResample=${reusableNormalizedCoords.size > maxSequenceLength && resamplingMode != SwipeResampler.ResamplingMode.TRUNCATE}")

        if (reusableNormalizedCoords.size > maxSequenceLength && resamplingMode != SwipeResampler.ResamplingMode.TRUNCATE) {
            // OPTIMIZATION Phase 2: Recycle previous resampled coords before creating new ones
            TrajectoryObjectPool.recyclePointFList(reusableProcessedCoords)
            reusableProcessedCoords.clear()

            // Convert to 2D array for resampling (only x,y for coordinate resampling)
            val coordArray = Array(reusableNormalizedCoords.size) { i ->
                floatArrayOf(reusableNormalizedCoords[i].x, reusableNormalizedCoords[i].y)
            }

            // Resample coordinates
            val resampledArray = SwipeResampler.resample(coordArray, maxSequenceLength, resamplingMode)

            // Convert back to PointF list using object pool
            resampledArray?.forEach { point ->
                reusableProcessedCoords.add(TrajectoryObjectPool.obtainPointF(point[0], point[1]))
            } ?: run {
                // Fallback if resampling returns null
                Log.e(TAG, "❌ Resampling returned null!")
                return TrajectoryFeatures()
            }
            processedCoords = reusableProcessedCoords

            // Resample timestamps as well to maintain correspondence
            reusableProcessedTimestamps.clear()
            val origSize = timestamps.size
            val newSize = processedCoords.size
            for (i in 0 until newSize) {
                val origIdx = (i.toLong() * (origSize - 1) / (newSize - 1)).toInt()
                reusableProcessedTimestamps.add(timestamps[origIdx])
            }
            processedTimestamps = reusableProcessedTimestamps

            // DEBUG: Always log resampling (remove isLoggable check for debugging)
            Log.d(TAG, "🔄 Resampled trajectory: ${reusableNormalizedCoords.size} → $maxSequenceLength points (mode: $resamplingMode)")
        }

        // DEBUG: Verify resampling worked
        if (processedCoords.size > maxSequenceLength) {
            Log.e(TAG, "❌ RESAMPLING FAILED! Still have ${processedCoords.size} points after resampling, expected max $maxSequenceLength")
        }

        // 3. Detect nearest keys from FINAL processed coordinates (already normalized!)
        // CRITICAL: Must happen AFTER resampling to maintain point-key correspondence
        val processedKeys = detectNearestKeys(processedCoords)

        // 4. Calculate velocities and accelerations using TrajectoryFeatureCalculator (v1.32.472)
        // CRITICAL: Must match Python training code exactly!
        // - Velocity = position_change / time_change
        // - Acceleration = velocity_change / time_change
        // - All clipped to [-10, 10]
        val actualLength = processedCoords.size

        // OPTIMIZATION Phase 2: Recycle TrajectoryPoints from previous call
        TrajectoryObjectPool.recycleTrajectoryPointList(reusablePoints)
        reusablePoints.clear()

        // Use Kotlin TrajectoryFeatureCalculator for correct feature calculation
        // CRITICAL: Use processedCoords and processedTimestamps
        val featurePoints = TrajectoryFeatureCalculator.calculateFeatures(processedCoords, processedTimestamps)

        // Convert to TrajectoryPoint list using object pool
        featurePoints.forEach { fp ->
            val point = TrajectoryObjectPool.obtainTrajectoryPoint()
            point.x = fp.x
            point.y = fp.y
            point.vx = fp.vx
            point.vy = fp.vy
            point.ax = fp.ax
            point.ay = fp.ay
            reusablePoints.add(point)
        }

        // 5. Truncate or pad features to maxSequenceLength
        // Training: traj_features = np.pad(traj_features, ((0, pad_len), (0, 0)), mode="constant")
        when {
            reusablePoints.size > maxSequenceLength -> {
                // Truncate - recycle excess points
                while (reusablePoints.size > maxSequenceLength) {
                    TrajectoryObjectPool.recycleTrajectoryPoint(reusablePoints.removeAt(reusablePoints.size - 1))
                }
            }
            reusablePoints.size < maxSequenceLength -> {
                // Pad with zeros [0, 0, 0, 0, 0, 0] using pooled objects
                while (reusablePoints.size < maxSequenceLength) {
                    val zeroPadding = TrajectoryObjectPool.obtainTrajectoryPoint()
                    // obtainTrajectoryPoint returns zero-initialized object from pool
                    reusablePoints.add(zeroPadding)
                }
            }
        }
        val points = reusablePoints

        // 6. Truncate or pad nearest_keys with PAD token (0)
        // Training: nearest_keys = nearest_keys + [self.tokenizer.pad_idx] * pad_len
        // OPTIMIZATION Phase 2: Use reusable list for keys
        reusableProcessedKeys.clear()
        val keysToCopy = minOf(processedKeys.size, maxSequenceLength)
        for (i in 0 until keysToCopy) {
            reusableProcessedKeys.add(processedKeys[i])
        }
        while (reusableProcessedKeys.size < maxSequenceLength) {
            reusableProcessedKeys.add(0)  // PAD token
        }
        val finalNearestKeys = reusableProcessedKeys

        // Verification logging (first 3 points)
        if (points.isNotEmpty()) {
            // Log.d(TAG, "🔬 Feature calculation (first 3 points):")
            for (i in 0 until minOf(3, points.size)) {
                val p = points[i]
                val key = finalNearestKeys[i]
                // Log.d(TAG, "   Point[$i]: x=${p.x}, y=${p.y}, vx=${p.vx}, vy=${p.vy}, ax=${p.ax}, ay=${p.ay}, nearest_key=$key")
            }
        }

        return TrajectoryFeatures(
            normalizedPoints = points,
            nearestKeys = finalNearestKeys,
            actualLength = actualLength
        )
    }

    /**
     * Normalize coordinates to [0, 1] range
     * Uses QWERTY area bounds if set, otherwise falls back to full keyboard dimensions
     * OPTIMIZATION Phase 2: Modified to use pre-allocated destination list to reduce GC pressure
     */
    private fun normalizeCoordinates(coordinates: List<PointF>, outNormalized: ArrayList<PointF>) {
        outNormalized.clear()

        // CRITICAL: Check if keyboard dimensions are set correctly
        // If still at default 1.0f, coordinates won't normalize properly
        if (keyboardWidth <= 1.0f || keyboardHeight <= 1.0f) {
            Log.w(TAG, "⚠️ Keyboard dimensions not set! Using defaults: $keyboardWidth x $keyboardHeight")
            // Try to infer from coordinates
            var maxX = 0f
            var maxY = 0f
            coordinates.forEach { p ->
                maxX = maxOf(maxX, p.x)
                maxY = maxOf(maxY, p.y)
            }
            if (maxX > 1.0f) keyboardWidth = maxX * 1.1f  // Add 10% margin
            if (maxY > 1.0f) keyboardHeight = maxY * 1.1f
            Log.d(TAG, "📐 Inferred keyboard size: $keyboardWidth x $keyboardHeight")
        }

        // Determine normalization parameters
        // If QWERTY area bounds are set, use them for Y normalization
        // This ensures Y coordinates span [0,1] for just the QWERTY key area
        val yTop = qwertyAreaTop
        val yHeight = if (qwertyAreaHeight > 0) qwertyAreaHeight else keyboardHeight
        val usingQwertyBounds = qwertyAreaHeight > 0

        coordinates.forEach { point ->
            var x = point.x / keyboardWidth

            // Apply touch Y-offset compensation (fat finger effect)
            // Users typically touch ~74 pixels above key centers
            val adjustedY = point.y + touchYOffset

            // For Y: normalize over QWERTY area if bounds are set
            var y = if (usingQwertyBounds) {
                // Map QWERTY area [yTop, yTop+yHeight] to [0, 1]
                (adjustedY - yTop) / yHeight
            } else {
                // Fall back to full keyboard height
                adjustedY / keyboardHeight
            }

            // Clamp to [0,1]
            x = x.coerceIn(0f, 1f)
            y = y.coerceIn(0f, 1f)

            // OPTIMIZATION Phase 2: Use object pool for PointF
            outNormalized.add(TrajectoryObjectPool.obtainPointF(x, y))
        }

        // Log normalization info for first and last points
        if (coordinates.isNotEmpty() && outNormalized.isNotEmpty()) {
            val rawFirst = coordinates.first()
            val normFirst = outNormalized.first()
            val rawLast = coordinates.last()
            val normLast = outNormalized.last()

            if (usingQwertyBounds) {
                Log.d(TAG, "📐 QWERTY NORMALIZATION: top=$yTop, height=$yHeight (kb=${keyboardWidth}x$keyboardHeight)")
                Log.d(TAG, "📐 RAW first=(${rawFirst.x},${rawFirst.y}) last=(${rawLast.x},${rawLast.y})")
                Log.d(TAG, "📐 NORMALIZED first=(${normFirst.x},${normFirst.y}) last=(${normLast.x},${normLast.y})")
                // Show expected Y for z row (should be ~0.833)
                Log.d(TAG, "📐 For z at pixel y=496: normalized y = ${(496 - yTop) / yHeight}")
            } else {
                Log.d(TAG, "📐 Normalization: kb=${keyboardWidth}x$keyboardHeight, raw=(${rawFirst.x},${rawFirst.y}) → norm=(${normFirst.x},${normFirst.y})")
            }
        }
    }

    /**
     * Detect nearest key for each coordinate using KeyboardGrid
     * CRITICAL: Returns integer token indices (4-29 for a-z), NOT characters!
     *
     * Input coordinates MUST be normalized to [0,1] range.
     */
    private fun detectNearestKeys(normalizedCoordinates: List<PointF>): List<Int> {
        val nearestKeys = ArrayList<Int>()
        val debugKeySeq = StringBuilder()
        var lastDebugChar = '\u0000'

        // Log first few coordinates for debugging
        if (normalizedCoordinates.isNotEmpty()) {
            val first = normalizedCoordinates.first()
            val last = normalizedCoordinates.last()
            Log.d(TAG, "🔍 Detecting keys from ${normalizedCoordinates.size} normalized points: " +
                    "first=(${first.x},${first.y}) last=(${last.x},${last.y})")
        }

        normalizedCoordinates.forEach { point ->
            // Use Kotlin KeyboardGrid for nearest key detection
            val tokenIndex = KeyboardGrid.getNearestKeyToken(point.x, point.y)
            nearestKeys.add(tokenIndex)

            // Convert back to char for debug display
            val debugChar = if (tokenIndex in 4..29) ('a' + (tokenIndex - 4)) else '?'
            if (debugChar != lastDebugChar) {
                debugKeySeq.append(debugChar)
                lastDebugChar = debugChar
            }
        }

        // Log the deduplicated key sequence detected from trajectory
        Log.d(TAG, "🎯 DETECTED KEY SEQUENCE: \"$debugKeySeq\" (from ${normalizedCoordinates.size} points)")

        return nearestKeys
    }

    /**
     * Find nearest key using real key positions
     */
    private fun findNearestKey(point: PointF): Char {
        val positions = keyPositions ?: return 'a'

        var nearestKey = 'a'
        var minDistance = Float.MAX_VALUE

        positions.forEach { (key, keyPos) ->
            val dx = point.x - keyPos.x
            val dy = point.y - keyPos.y
            val distance = dx * dx + dy * dy

            if (distance < minDistance) {
                minDistance = distance
                nearestKey = key
            }
        }

        return nearestKey
    }

    /**
     * Detect nearest key using grid-based approach with QWERTY layout
     * MUST MATCH Python KeyboardGrid exactly:
     * - 3 rows (height = 1/3 each)
     * - key_w = 0.1
     * - row offsets: top=0.0, mid=0.05, bot=0.15
     *
     * NOTE: Input point must already be normalized to [0,1] range!
     */
    private fun detectKeyFromQwertyGrid(normalizedPoint: PointF): Int {
        // QWERTY layout rows
        val row0 = "qwertyuiop"  // 10 keys, x starts at 0.0
        val row1 = "asdfghjkl"   // 9 keys, x starts at 0.05
        val row2 = "zxcvbnm"     // 7 keys, x starts at 0.15

        // Input is already normalized - just clamp for safety
        val nx = normalizedPoint.x.coerceIn(0f, 1f)
        val ny = normalizedPoint.y.coerceIn(0f, 1f)

        // Grid dimensions matching Python
        val keyWidth = 0.1f   // 1/10
        val rowHeight = 1.0f / 3.0f  // 3 rows only!

        // Row offsets (absolute in normalized space)
        val row0X0 = 0.0f
        val row1X0 = 0.05f
        val row2X0 = 0.15f

        // Find nearest key by checking distance to each key center
        var nearestKey = 'a'
        var minDist = Float.MAX_VALUE

        // Check row 0 (qwertyuiop)
        row0.forEachIndexed { i, c ->
            val cx = row0X0 + i * keyWidth + keyWidth / 2.0f
            val cy = 0.0f * rowHeight + rowHeight / 2.0f
            val dist = (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)
            if (dist < minDist) {
                minDist = dist
                nearestKey = c
            }
        }

        // Check row 1 (asdfghjkl)
        row1.forEachIndexed { i, c ->
            val cx = row1X0 + i * keyWidth + keyWidth / 2.0f
            val cy = 1.0f * rowHeight + rowHeight / 2.0f
            val dist = (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)
            if (dist < minDist) {
                minDist = dist
                nearestKey = c
            }
        }

        // Check row 2 (zxcvbnm)
        row2.forEachIndexed { i, c ->
            val cx = row2X0 + i * keyWidth + keyWidth / 2.0f
            val cy = 2.0f * rowHeight + rowHeight / 2.0f
            val dist = (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)
            if (dist < minDist) {
                minDist = dist
                nearestKey = c
            }
        }

        return charToTokenIndex(nearestKey)
    }

    /**
     * Convert character to token index (a-z → 4-29, others → 0)
     */
    private fun charToTokenIndex(c: Char): Int {
        return if (c in 'a'..'z') {
            (c - 'a') + 4  // a=4, b=5, ..., z=29
        } else {
            0  // PAD_IDX for unknown characters
        }
    }

    /**
     * Trajectory point with position, velocity, and acceleration
     */
    class TrajectoryPoint {
        @JvmField var x = 0.0f
        @JvmField var y = 0.0f
        @JvmField var vx = 0.0f
        @JvmField var vy = 0.0f
        @JvmField var ax = 0.0f
        @JvmField var ay = 0.0f

        constructor()

        constructor(other: TrajectoryPoint) {
            this.x = other.x
            this.y = other.y
            this.vx = other.vx
            this.vy = other.vy
            this.ax = other.ax
            this.ay = other.ay
        }
    }

    /**
     * Complete feature set for neural network input
     * CRITICAL FIX: nearestKeys is now List<Integer> (token indices)
     */
    data class TrajectoryFeatures(
        val normalizedPoints: List<TrajectoryPoint> = ArrayList(),
        val nearestKeys: List<Int> = ArrayList(),  // Changed from List<Character>!
        val actualLength: Int = 0
    )
}
