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
    var keyboardWidth = 1.0f  // Key area width only (excluding margins)

    @JvmField
    var keyboardHeight = 1.0f

    // Margin offsets for coordinate normalization (touch coords include margin offsets)
    private var marginLeft = 0.0f
    private var marginRight = 0.0f

    // QWERTY area bounds for proper normalization (v1.32.463)
    // The model expects normalized coords over QWERTY keys only, not full view
    private var qwertyAreaTop = 0.0f      // Y offset where QWERTY starts (below suggestion bar, etc.)
    private var qwertyAreaHeight = 0.0f   // Height of QWERTY key area only

    // Touch Y-offset compensation (v1.32.466)
    // Users typically touch ~74 pixels above key center due to finger occlusion
    // (the fingertip obscures the target, causing touches to land higher)
    // This offset is added to raw Y coordinates before normalization
    private var touchYOffset = 0.0f

    // Resampling configuration
    // Default to DISCARD to preserve start/end of long swipes (matching Config.java default)
    private var resamplingMode: SwipeResampler.ResamplingMode = SwipeResampler.ResamplingMode.DISCARD

    // Debug logging callback (sends to SwipeDebugActivity)
    private var debugLogger: ((String) -> Unit)? = null
    @Volatile private var debugModeActive = false

    // OPTIMIZATION Phase 2: Reusable lists to reduce GC pressure
    // These are cleared and reused on each call to extractFeatures()
    private val reusableNormalizedCoords = ArrayList<PointF>(250)
    private val reusableProcessedCoords = ArrayList<PointF>(250)
    private val reusableProcessedTimestamps = ArrayList<Long>(250)
    private val reusableProcessedKeys = ArrayList<Int>(250)
    private val reusablePoints = ArrayList<TrajectoryPoint>(250)
    private val reusableDetectedKeys = ArrayList<Int>(250)  // For detectNearestKeysInto

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
     * Set margin offsets for X coordinate normalization.
     * Touch coordinates include margin offsets; left margin is subtracted and
     * the result is divided by key area width (total - left - right).
     *
     * @param left Left margin in pixels
     * @param right Right margin in pixels
     */
    fun setMargins(left: Float, right: Float) {
        this.marginLeft = left
        this.marginRight = right
        if (debugModeActive) logDebug("📐 Margins set: left=$left px, right=$right px")
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
        if (debugModeActive) logDebug("📐 QWERTY area bounds set: top=$qwertyTop, height=$qwertyHeight (full kb height=$keyboardHeight)")
    }

    /**
     * Set touch Y-offset compensation for finger occlusion.
     * Users typically touch ~74 pixels above key centers because the fingertip
     * obscures the visual target. This offset shifts raw Y coordinates down
     * before normalization to compensate.
     *
     * @param offset Pixels to add to Y coordinate (positive = shift down toward key center)
     */
    fun setTouchYOffset(offset: Float) {
        this.touchYOffset = offset
        if (debugModeActive) logDebug("📐 Touch Y-offset set: $offset pixels")
    }

    /**
     * Set resampling mode for trajectory processing
     */
    fun setResamplingMode(mode: SwipeResampler.ResamplingMode) {
        this.resamplingMode = mode
        if (debugModeActive) logDebug("Resampling mode set to: $mode")
    }

    /**
     * Set debug logger for detailed logging to SwipeDebugActivity
     */
    fun setDebugLogger(logger: ((String) -> Unit)?) {
        this.debugLogger = logger
    }

    /**
     * Set debug mode active state. When false, expensive debug logging is skipped.
     */
    fun setDebugModeActive(active: Boolean) {
        this.debugModeActive = active
    }

    private fun logDebug(message: String) {
        debugLogger?.invoke(message)
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
        if (debugModeActive) {
            logDebug("🔍 Resampling check: size=${reusableNormalizedCoords.size}, max=$maxSequenceLength, " +
                    "mode=$resamplingMode, needsResample=${reusableNormalizedCoords.size > maxSequenceLength && resamplingMode != SwipeResampler.ResamplingMode.TRUNCATE}")
        }

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
            if (debugModeActive) {
                logDebug("🔄 Resampled trajectory: ${reusableNormalizedCoords.size} → $maxSequenceLength points (mode: $resamplingMode)")
            }
        }

        // DEBUG: Verify resampling worked
        if (processedCoords.size > maxSequenceLength) {
            Log.e(TAG, "❌ RESAMPLING FAILED! Still have ${processedCoords.size} points after resampling, expected max $maxSequenceLength")
        }

        // 3. Detect nearest keys from FINAL processed coordinates (already normalized!)
        // CRITICAL: Must happen AFTER resampling to maintain point-key correspondence
        // OPTIMIZATION: Use reusable list to avoid allocation
        detectNearestKeysInto(processedCoords, reusableDetectedKeys)

        // 4. Calculate velocities and accelerations using TrajectoryFeatureCalculator (v1.32.472)
        // CRITICAL: Must match Python training code exactly!
        // - Velocity = position_change / time_change
        // - Acceleration = velocity_change / time_change
        // - All clipped to [-10, 10]
        val actualLength = processedCoords.size

        // OPTIMIZATION Phase 3: Recycle TrajectoryPoints and use streaming calculator
        // This eliminates 7 intermediate FloatArrays and List<FeaturePoint> allocation
        TrajectoryObjectPool.recycleTrajectoryPointList(reusablePoints)
        TrajectoryFeatureCalculator.calculateFeaturesStreaming(processedCoords, processedTimestamps, reusablePoints)

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
        val keysToCopy = minOf(reusableDetectedKeys.size, maxSequenceLength)
        for (i in 0 until keysToCopy) {
            reusableProcessedKeys.add(reusableDetectedKeys[i])
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
            if (debugModeActive) logDebug("📐 Inferred keyboard size: $keyboardWidth x $keyboardHeight")
        }

        // Determine normalization parameters
        // If QWERTY area bounds are set, use them for Y normalization
        // This ensures Y coordinates span [0,1] for just the QWERTY key area
        val yTop = qwertyAreaTop
        val yHeight = if (qwertyAreaHeight > 0) qwertyAreaHeight else keyboardHeight
        val usingQwertyBounds = qwertyAreaHeight > 0

        // Calculate key area width (excluding margins) for X normalization
        val keyAreaWidth = keyboardWidth - marginLeft - marginRight
        val effectiveKeyAreaWidth = if (keyAreaWidth > 0) keyAreaWidth else keyboardWidth

        // DEBUG: Log comprehensive normalization parameters (gated)
        if (debugModeActive && coordinates.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("\n📐 NORMALIZATION PARAMETERS:\n")
            sb.append("─────────────────────────────────────────────────────────\n")
            sb.append("   Keyboard dimensions: ${keyboardWidth.toInt()} × ${keyboardHeight.toInt()} px\n")
            sb.append("   Margins: left=${marginLeft.toInt()} px, right=${marginRight.toInt()} px\n")
            sb.append("   Effective key area width: ${effectiveKeyAreaWidth.toInt()} px\n")
            sb.append("   QWERTY bounds: ${if (usingQwertyBounds) "top=${yTop.toInt()}, height=${yHeight.toInt()} px" else "NOT SET (using full height)"}\n")
            sb.append("   Touch Y-offset: ${touchYOffset.toInt()} px\n")
            sb.append("─────────────────────────────────────────────────────────\n")
            logDebug(sb.toString())
        }

        // Track clamping for debug output
        var clampedCount = 0
        var minRawX = Float.MAX_VALUE
        var maxRawX = Float.MIN_VALUE
        var minRawY = Float.MAX_VALUE
        var maxRawY = Float.MIN_VALUE

        coordinates.forEach { point ->
            if (debugModeActive) {
                minRawX = minOf(minRawX, point.x)
                maxRawX = maxOf(maxRawX, point.x)
                minRawY = minOf(minRawY, point.y)
                maxRawY = maxOf(maxRawY, point.y)
            }

            // Subtract left margin and divide by key area width (not total width)
            var x = (point.x - marginLeft) / effectiveKeyAreaWidth

            // Apply touch Y-offset compensation (finger occlusion)
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

            // Track if clamping happened
            if (debugModeActive && (x < 0f || x > 1f || y < 0f || y > 1f)) {
                clampedCount++
            }

            // Clamp to [0,1]
            x = x.coerceIn(0f, 1f)
            y = y.coerceIn(0f, 1f)

            // OPTIMIZATION Phase 2: Use object pool for PointF
            outNormalized.add(TrajectoryObjectPool.obtainPointF(x, y))
        }

        // DEBUG: Log sample coordinate transformations (gated)
        if (debugModeActive && coordinates.isNotEmpty() && outNormalized.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("\n📐 COORDINATE NORMALIZATION:\n")
            sb.append("─────────────────────────────────────────────────────────\n")
            sb.append("   Raw X range: [${String.format("%.1f", minRawX)}, ${String.format("%.1f", maxRawX)}] px\n")
            sb.append("   Raw Y range: [${String.format("%.1f", minRawY)}, ${String.format("%.1f", maxRawY)}] px\n")

            // Show sample transformations (first, middle, last)
            val sampleIndices = listOf(0, coordinates.size / 2, coordinates.size - 1)
            sb.append("   Sample transformations:\n")
            for (i in sampleIndices) {
                val raw = coordinates[i]
                val norm = outNormalized[i]
                val label = when (i) {
                    0 -> "FIRST"
                    coordinates.size - 1 -> "LAST"
                    else -> "MID"
                }
                sb.append("     $label: raw=(${String.format("%.1f", raw.x)}, ${String.format("%.1f", raw.y)}) → norm=(${String.format("%.3f", norm.x)}, ${String.format("%.3f", norm.y)})\n")
            }

            if (clampedCount > 0) {
                sb.append("   ⚠️ CLAMPED $clampedCount/${coordinates.size} points (${100 * clampedCount / coordinates.size}%) to [0,1] range\n")
            } else {
                sb.append("   ✅ All ${coordinates.size} points within valid [0,1] range\n")
            }

            // Verify expected normalized values for QWERTY rows
            if (usingQwertyBounds) {
                sb.append("   Expected Y for rows: top=0.17, middle=0.50, bottom=0.83\n")
            }
            sb.append("─────────────────────────────────────────────────────────\n")
            logDebug(sb.toString())
        }

    }

    /**
     * Detect nearest key for each coordinate using KeyboardGrid.
     * OPTIMIZATION: Writes to pre-allocated output list to avoid allocation.
     * CRITICAL: Returns integer token indices (4-29 for a-z), NOT characters!
     *
     * @param normalizedCoordinates Input coordinates (must be normalized to [0,1])
     * @param outKeys Output list - will be cleared and populated with token indices
     */
    private fun detectNearestKeysInto(normalizedCoordinates: List<PointF>, outKeys: ArrayList<Int>) {
        outKeys.clear()
        val debugKeySeq = if (debugModeActive) StringBuilder() else null
        var lastDebugChar = '\u0000'

        // Log first few coordinates for debugging
        if (debugModeActive && normalizedCoordinates.isNotEmpty()) {
            val first = normalizedCoordinates.first()
            val last = normalizedCoordinates.last()
            logDebug("🔍 Detecting keys from ${normalizedCoordinates.size} normalized points: " +
                    "first=(${first.x},${first.y}) last=(${last.x},${last.y})")
        }

        normalizedCoordinates.forEach { point ->
            // Use Kotlin KeyboardGrid for nearest key detection
            val tokenIndex = KeyboardGrid.getNearestKeyToken(point.x, point.y)
            outKeys.add(tokenIndex)

            // Convert back to char for debug display
            if (debugKeySeq != null) {
                val debugChar = if (tokenIndex in 4..29) ('a' + (tokenIndex - 4)) else '?'
                if (debugChar != lastDebugChar) {
                    debugKeySeq.append(debugChar)
                    lastDebugChar = debugChar
                }
            }
        }

        // Log the deduplicated key sequence detected from trajectory
        if (debugKeySeq != null) {
            logDebug("🎯 DETECTED KEY SEQUENCE: \"$debugKeySeq\" (from ${normalizedCoordinates.size} points)")
        }
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
