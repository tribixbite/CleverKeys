package tribixbite.keyboard2.ml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ML Data Model for Swipe Typing Training Data
 *
 * Captures normalized swipe traces with metadata for neural network training.
 * Designed for collecting and exporting training data to improve swipe prediction accuracy.
 *
 * Features:
 * - Normalized coordinates ([0,1] range) for device independence
 * - Time delta tracking for velocity analysis
 * - Registered keys sequence for path validation
 * - JSON serialization for storage and export
 * - Data quality validation
 * - Statistical analysis (distance, time, straightness)
 *
 * Usage:
 * ```kotlin
 * val data = SwipeMLData("hello", "user_selection", 1080, 1920, 500)
 * data.addRawPoint(200f, 1500f, System.currentTimeMillis())
 * data.addRegisteredKey("h")
 * if (data.isValid()) {
 *     val json = data.toJson()
 *     // Save to file or export
 * }
 * ```
 *
 * Ported from Java to Kotlin with improvements.
 */
class SwipeMLData private constructor(
    val traceId: String,
    val targetWord: String,
    val timestampUtc: Long,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val keyboardHeightPx: Int,
    val collectionSource: String,
    internal val tracePoints: MutableList<TracePoint>,
    internal val registeredKeys: MutableList<String>,
    private var keyboardOffsetY: Int,
    private var lastAbsoluteTimestamp: Long
) {

    companion object {
        /**
         * Create new swipe data for collection
         */
        @JvmStatic
        fun create(
            targetWord: String,
            collectionSource: String,
            screenWidth: Int,
            screenHeight: Int,
            keyboardHeight: Int
        ): SwipeMLData {
            val timestamp = System.currentTimeMillis()
            return SwipeMLData(
                traceId = UUID.randomUUID().toString(),
                targetWord = targetWord.lowercase(),
                timestampUtc = timestamp,
                screenWidthPx = screenWidth,
                screenHeightPx = screenHeight,
                keyboardHeightPx = keyboardHeight,
                collectionSource = collectionSource,
                tracePoints = mutableListOf(),
                registeredKeys = mutableListOf(),
                keyboardOffsetY = 0,
                lastAbsoluteTimestamp = timestamp
            )
        }

        /**
         * Load from JSON
         */
        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): SwipeMLData {
            val traceId = json.getString("trace_id")
            val targetWord = json.getString("target_word")

            val metadata = json.getJSONObject("metadata")
            val timestampUtc = metadata.getLong("timestamp_utc")
            val screenWidthPx = metadata.getInt("screen_width_px")
            val screenHeightPx = metadata.getInt("screen_height_px")
            val keyboardHeightPx = metadata.getInt("keyboard_height_px")
            val collectionSource = metadata.getString("collection_source")
            val keyboardOffsetY = metadata.optInt("keyboard_offset_y", 0)

            // Load trace points
            val tracePoints = mutableListOf<TracePoint>()
            val pointsArray = json.getJSONArray("trace_points")
            for (i in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(i)
                tracePoints.add(
                    TracePoint(
                        x = point.getDouble("x").toFloat(),
                        y = point.getDouble("y").toFloat(),
                        tDeltaMs = point.getLong("t_delta_ms")
                    )
                )
            }

            // Load registered keys
            val registeredKeys = mutableListOf<String>()
            val keysArray = json.getJSONArray("registered_keys")
            for (i in 0 until keysArray.length()) {
                registeredKeys.add(keysArray.getString(i))
            }

            // Reconstruct last absolute timestamp from deltas
            val lastAbsoluteTimestamp = timestampUtc + tracePoints.sumOf { it.tDeltaMs }

            return SwipeMLData(
                traceId, targetWord, timestampUtc, screenWidthPx, screenHeightPx,
                keyboardHeightPx, collectionSource, tracePoints, registeredKeys,
                keyboardOffsetY, lastAbsoluteTimestamp
            )
        }
    }

    /**
     * Secondary constructor for backward compatibility
     */
    constructor(
        targetWord: String,
        collectionSource: String,
        screenWidth: Int,
        screenHeight: Int,
        keyboardHeight: Int
    ) : this(
        traceId = UUID.randomUUID().toString(),
        targetWord = targetWord.lowercase(),
        timestampUtc = System.currentTimeMillis(),
        screenWidthPx = screenWidth,
        screenHeightPx = screenHeight,
        keyboardHeightPx = keyboardHeight,
        collectionSource = collectionSource,
        tracePoints = mutableListOf(),
        registeredKeys = mutableListOf(),
        keyboardOffsetY = 0,
        lastAbsoluteTimestamp = System.currentTimeMillis()
    )

    /**
     * Add a raw trace point (will be normalized to [0,1] range)
     */
    fun addRawPoint(rawX: Float, rawY: Float, timestamp: Long) {
        // Normalize coordinates to [0, 1] range
        val normalizedX = rawX / screenWidthPx
        val normalizedY = rawY / screenHeightPx

        // Calculate time delta from last absolute timestamp
        val deltaMs = timestamp - lastAbsoluteTimestamp

        // Update last absolute timestamp for next point
        lastAbsoluteTimestamp = timestamp

        tracePoints.add(TracePoint(normalizedX, normalizedY, deltaMs))
    }

    /**
     * Add a registered key from the swipe path
     * Avoids consecutive duplicates
     */
    fun addRegisteredKey(key: String) {
        val lowerKey = key.lowercase()
        // Avoid consecutive duplicates
        if (registeredKeys.isEmpty() || registeredKeys.last() != lowerKey) {
            registeredKeys.add(lowerKey)
        }
    }

    /**
     * Set keyboard dimensions for accurate position tracking
     */
    @Suppress("UNUSED_PARAMETER")
    fun setKeyboardDimensions(screenWidth: Int, keyboardHeight: Int, keyboardOffsetY: Int) {
        this.keyboardOffsetY = keyboardOffsetY
        // Note: screenWidth and keyboardHeight are already set in constructor
        // This method mainly records the Y offset for position normalization
    }

    /**
     * Convert to JSON for storage and export
     */
    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("trace_id", traceId)
            put("target_word", targetWord)

            // Metadata
            put("metadata", JSONObject().apply {
                put("timestamp_utc", timestampUtc)
                put("screen_width_px", screenWidthPx)
                put("screen_height_px", screenHeightPx)
                put("keyboard_height_px", keyboardHeightPx)
                put("keyboard_offset_y", keyboardOffsetY)
                put("collection_source", collectionSource)
            })

            // Trace points
            put("trace_points", JSONArray().apply {
                tracePoints.forEach { point ->
                    put(JSONObject().apply {
                        put("x", point.x)
                        put("y", point.y)
                        put("t_delta_ms", point.tDeltaMs)
                    })
                }
            })

            // Registered keys
            put("registered_keys", JSONArray(registeredKeys))
        }
    }

    /**
     * Validate data quality before storage
     */
    fun isValid(): Boolean {
        // Must have at least 2 points for a valid swipe
        if (tracePoints.size < 2) return false

        // Must have at least 2 registered keys
        if (registeredKeys.size < 2) return false

        // Target word must not be empty
        if (targetWord.isBlank()) return false

        // Check for reasonable normalized values
        return tracePoints.all { point ->
            point.x in 0f..1f && point.y in 0f..1f
        }
    }

    /**
     * Calculate statistics for this swipe
     */
    fun calculateStatistics(): SwipeStatistics? {
        if (tracePoints.size < 2) return null

        var totalDistance = 0f
        var totalTime = 0L

        // Calculate total distance and time
        for (i in 1 until tracePoints.size) {
            val prev = tracePoints[i - 1]
            val curr = tracePoints[i]

            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            totalDistance += sqrt(dx * dx + dy * dy)
            totalTime += curr.tDeltaMs
        }

        // Calculate straightness ratio
        val start = tracePoints.first()
        val end = tracePoints.last()
        val directDistance = sqrt(
            (end.x - start.x).pow(2) + (end.y - start.y).pow(2)
        )
        val straightnessRatio = if (totalDistance > 0) directDistance / totalDistance else 0f

        return SwipeStatistics(
            pointCount = tracePoints.size,
            totalDistance = totalDistance,
            totalTimeMs = totalTime,
            straightnessRatio = straightnessRatio,
            keyCount = registeredKeys.size
        )
    }

    /**
     * Get a defensive copy of trace points
     */
    fun getTracePoints(): List<TracePoint> = tracePoints.toList()

    /**
     * Get a defensive copy of registered keys
     */
    fun getRegisteredKeys(): List<String> = registeredKeys.toList()

    /**
     * Normalized trace point with time delta
     *
     * @property x Normalized X coordinate [0, 1]
     * @property y Normalized Y coordinate [0, 1]
     * @property tDeltaMs Time delta from previous point in milliseconds
     */
    data class TracePoint(
        val x: Float,
        val y: Float,
        val tDeltaMs: Long
    )

    /**
     * Swipe Statistics
     */
    data class SwipeStatistics(
        val pointCount: Int,
        val totalDistance: Float,
        val totalTimeMs: Long,
        val straightnessRatio: Float,
        val keyCount: Int
    )
}
