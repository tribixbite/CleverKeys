package tribixbite.cleverkeys

import android.graphics.PointF
import android.util.Log

/**
 * Handles coordinate normalization for swipe typing neural network input.
 *
 * The neural model expects coordinates normalized to [0,1] over the QWERTY key area.
 * This class centralizes all normalization logic for testability and debugging.
 *
 * Coordinate System:
 * - Raw coordinates: pixels from keyboard view touch events
 * - Normalized coordinates: [0,1] range for neural network
 * - X: normalized over full keyboard width
 * - Y: normalized over QWERTY area (3 rows of letter keys)
 *
 * Created: v1.32.468 - Extracted from SwipeTrajectoryProcessor for better testability
 */
object CoordinateNormalizer {
    private const val TAG = "CoordinateNormalizer"

    /**
     * QWERTY area bounds for normalization.
     * These define the pixel region that maps to [0,1] for Y coordinate.
     */
    data class QwertyBounds(
        val top: Float,      // Y pixel where QWERTY area starts
        val height: Float,   // Height of QWERTY area in pixels
        val rowHeight: Float // Height of single row in pixels
    ) {
        companion object {
            val INVALID = QwertyBounds(0f, 0f, 0f)
        }

        val isValid: Boolean
            get() = height > 0 && rowHeight > 0

        /**
         * Get expected normalized Y for each row center.
         * Row 0 (top): 0.167, Row 1 (middle): 0.5, Row 2 (bottom): 0.833
         */
        fun getRowCenterY(row: Int): Float {
            require(row in 0..2) { "Row must be 0, 1, or 2" }
            val rowHeight = 1f / 3f  // 3 rows
            return (row * rowHeight) + (rowHeight / 2f)
        }

        /**
         * Get expected pixel Y for row center.
         */
        fun getRowCenterPixelY(row: Int): Float {
            return top + (getRowCenterY(row) * height)
        }

        override fun toString(): String {
            return "QwertyBounds(top=${"%.0f".format(top)}, height=${"%.0f".format(height)}, rowHeight=${"%.0f".format(rowHeight)})"
        }
    }

    /**
     * Normalized coordinate with debug info.
     */
    data class NormalizedPoint(
        val x: Float,        // Normalized X [0,1]
        val y: Float,        // Normalized Y [0,1]
        val rawX: Float,     // Original pixel X
        val rawY: Float,     // Original pixel Y
        val nearestKey: Char // Detected key at this point
    ) {
        val expectedRow: Int
            get() = when {
                y < 0.333f -> 0
                y < 0.667f -> 1
                else -> 2
            }

        override fun toString(): String {
            return "(${"%.3f".format(x)},${"%.3f".format(y)}) -> '$nearestKey' [raw:(${"%.0f".format(rawX)},${"%.0f".format(rawY)})]"
        }
    }

    /**
     * Calculate QWERTY bounds from key positions.
     *
     * Uses 'q' (row 0) and 'm' (row 2) to determine vertical extent.
     * The distance from q to m spans 2 rows, so rowHeight = (m.y - q.y) / 2.
     *
     * @param keyPositions Map of character to pixel position (key centers)
     * @return QwertyBounds or INVALID if calculation fails
     */
    fun calculateQwertyBounds(keyPositions: Map<Char, PointF>): QwertyBounds {
        val qPos = keyPositions['q']
        val mPos = keyPositions['m']

        if (qPos == null || mPos == null) {
            Log.e(TAG, "Cannot calculate bounds - missing 'q' or 'm' key positions")
            return QwertyBounds.INVALID
        }

        // q is in row 0, m is in row 2 -> 2 row gaps between them
        val rowHeight = (mPos.y - qPos.y) / 2.0f

        if (rowHeight <= 0) {
            Log.e(TAG, "Invalid row height: $rowHeight (q.y=${qPos.y}, m.y=${mPos.y})")
            return QwertyBounds.INVALID
        }

        // Top of QWERTY area is top of row 0 (half a row above q center)
        var qwertyTop = qPos.y - (rowHeight / 2.0f)
        var qwertyHeight = 3.0f * rowHeight

        // Handle edge case where qwertyTop is negative
        // This can happen if 'q' is very close to top of view
        if (qwertyTop < 0) {
            Log.w(TAG, "qwertyTop is negative ($qwertyTop), clamping to 0")
            qwertyHeight += qwertyTop  // Reduce height by the negative amount
            qwertyTop = 0f
        }

        val bounds = QwertyBounds(qwertyTop, qwertyHeight, rowHeight)

        // Detailed logging for debugging
        Log.d(TAG, "📐 Calculated QWERTY bounds: $bounds")
        Log.d(TAG, "   Key positions: q=(${qPos.x.toInt()},${qPos.y.toInt()}) m=(${mPos.x.toInt()},${mPos.y.toInt()})")
        Log.d(TAG, "   Expected row centers: row0=${bounds.getRowCenterPixelY(0).toInt()}px, " +
                "row1=${bounds.getRowCenterPixelY(1).toInt()}px, row2=${bounds.getRowCenterPixelY(2).toInt()}px")

        // Verification: check if 'a' (row 1) is where expected
        val aPos = keyPositions['a']
        if (aPos != null) {
            val expectedAY = bounds.getRowCenterPixelY(1)
            val diff = Math.abs(aPos.y - expectedAY)
            if (diff > rowHeight * 0.2f) {
                Log.w(TAG, "⚠️ 'a' position mismatch: actual=${aPos.y.toInt()}px, expected=${expectedAY.toInt()}px (diff=${diff.toInt()}px)")
            } else {
                Log.d(TAG, "   ✓ 'a' position verified: actual=${aPos.y.toInt()}px, expected=${expectedAY.toInt()}px")
            }
        }

        return bounds
    }

    /**
     * Normalize a single coordinate from pixels to [0,1].
     *
     * @param rawX Raw pixel X coordinate
     * @param rawY Raw pixel Y coordinate
     * @param keyboardWidth Full keyboard width in pixels
     * @param bounds QWERTY bounds for Y normalization
     * @return NormalizedPoint with normalized coordinates and detected key
     */
    fun normalizeCoordinate(
        rawX: Float,
        rawY: Float,
        keyboardWidth: Float,
        bounds: QwertyBounds
    ): NormalizedPoint {
        // X: normalize over full keyboard width
        var nx = rawX / keyboardWidth

        // Y: normalize over QWERTY area
        var ny = if (bounds.isValid) {
            (rawY - bounds.top) / bounds.height
        } else {
            // Fallback if bounds invalid
            rawY / bounds.height.coerceAtLeast(1f)
        }

        // Clamp to [0,1]
        nx = nx.coerceIn(0f, 1f)
        ny = ny.coerceIn(0f, 1f)

        // Detect nearest key
        val nearestKey = KeyboardGrid.getNearestKey(nx, ny)

        return NormalizedPoint(nx, ny, rawX, rawY, nearestKey)
    }

    /**
     * Normalize a list of coordinates.
     *
     * @param coordinates Raw pixel coordinates
     * @param keyboardWidth Keyboard width in pixels
     * @param bounds QWERTY bounds for Y normalization
     * @return List of normalized points with detected keys
     */
    fun normalizeCoordinates(
        coordinates: List<PointF>,
        keyboardWidth: Float,
        bounds: QwertyBounds
    ): List<NormalizedPoint> {
        return coordinates.map { point ->
            normalizeCoordinate(point.x, point.y, keyboardWidth, bounds)
        }
    }

    /**
     * Get the detected key sequence from normalized points (deduplicated).
     */
    fun getKeySequence(normalizedPoints: List<NormalizedPoint>): String {
        val sb = StringBuilder()
        var lastKey = '\u0000'
        for (point in normalizedPoints) {
            if (point.nearestKey != lastKey) {
                sb.append(point.nearestKey)
                lastKey = point.nearestKey
            }
        }
        return sb.toString()
    }

    /**
     * Debug: Analyze a swipe trajectory and return detailed analysis.
     */
    fun analyzeSwipe(
        coordinates: List<PointF>,
        keyboardWidth: Float,
        bounds: QwertyBounds
    ): SwipeAnalysis {
        if (coordinates.isEmpty()) {
            return SwipeAnalysis.EMPTY
        }

        val normalized = normalizeCoordinates(coordinates, keyboardWidth, bounds)
        val keySequence = getKeySequence(normalized)

        val firstPoint = normalized.first()
        val lastPoint = normalized.last()

        // Check for potential issues
        val issues = mutableListOf<String>()

        // Issue 1: Y coordinate outside expected range for detected row
        for ((index, point) in normalized.withIndex()) {
            if (index == 0 || index == normalized.lastIndex) {
                val expectedRow = getKeyRow(point.nearestKey)
                val actualRow = point.expectedRow
                if (expectedRow != actualRow && expectedRow != -1) {
                    issues.add("Point $index: detected '${point.nearestKey}' (row $expectedRow) but Y=${"%.3f".format(point.y)} suggests row $actualRow")
                }
            }
        }

        // Issue 2: Very short or very long trajectory
        if (coordinates.size < 3) {
            issues.add("Very short trajectory: only ${coordinates.size} points")
        } else if (coordinates.size > 200) {
            issues.add("Very long trajectory: ${coordinates.size} points may be truncated")
        }

        return SwipeAnalysis(
            keySequence = keySequence,
            pointCount = coordinates.size,
            firstPoint = firstPoint,
            lastPoint = lastPoint,
            bounds = bounds,
            keyboardWidth = keyboardWidth,
            issues = issues
        )
    }

    /**
     * Result of swipe trajectory analysis.
     */
    data class SwipeAnalysis(
        val keySequence: String,
        val pointCount: Int,
        val firstPoint: NormalizedPoint?,
        val lastPoint: NormalizedPoint?,
        val bounds: QwertyBounds,
        val keyboardWidth: Float,
        val issues: List<String>
    ) {
        companion object {
            val EMPTY = SwipeAnalysis("", 0, null, null, QwertyBounds.INVALID, 0f, emptyList())
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("SwipeAnalysis:\n")
            sb.append("  Key sequence: \"$keySequence\" ($pointCount points)\n")
            sb.append("  Keyboard width: ${keyboardWidth.toInt()}px\n")
            sb.append("  Bounds: $bounds\n")
            firstPoint?.let { sb.append("  First: $it\n") }
            lastPoint?.let { sb.append("  Last: $it\n") }
            if (issues.isNotEmpty()) {
                sb.append("  Issues:\n")
                issues.forEach { sb.append("    - $it\n") }
            }
            return sb.toString()
        }

        /**
         * Format for debug output.
         */
        fun toDebugString(): String {
            val sb = StringBuilder()
            sb.append("🎯 KEY SEQUENCE: \"$keySequence\"\n")
            firstPoint?.let { sb.append("📍 First: $it\n") }
            lastPoint?.let { sb.append("📍 Last: $it\n") }
            if (issues.isNotEmpty()) {
                sb.append("⚠️ Issues: ${issues.joinToString("; ")}\n")
            }
            return sb.toString()
        }
    }

    // Row height constant for calculations
    const val ROW_HEIGHT = 1f / 3f

    /**
     * Get the row a character belongs to.
     * @return 0 for top row, 1 for middle, 2 for bottom, -1 for unknown
     */
    private fun getKeyRow(c: Char): Int {
        return when {
            "qwertyuiop".contains(c) -> 0
            "asdfghjkl".contains(c) -> 1
            "zxcvbnm".contains(c) -> 2
            else -> -1
        }
    }
}
