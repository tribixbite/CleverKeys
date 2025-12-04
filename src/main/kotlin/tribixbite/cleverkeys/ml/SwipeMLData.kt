package tribixbite.cleverkeys.ml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ML data model for swipe typing training data.
 * Captures normalized swipe traces with metadata for neural network training.
 */
class SwipeMLData {
    companion object {
        private const val TAG = "SwipeMLData"
    }

    // Data fields matching ML requirements
    val traceId: String
    val targetWord: String
    val timestampUtc: Long
    val screenWidthPx: Int
    val screenHeightPx: Int
    val keyboardHeightPx: Int
    val collectionSource: String // "calibration" or "user_selection"
    private val tracePoints: MutableList<TracePoint> = mutableListOf()
    private val registeredKeys: MutableList<String> = mutableListOf()
    private var keyboardOffsetY = 0 // Y offset of keyboard from top of screen
    private var lastAbsoluteTimestamp: Long // Track last point's absolute timestamp for delta calculation

    // Constructor for new swipe data
    constructor(
        targetWord: String,
        collectionSource: String,
        screenWidth: Int,
        screenHeight: Int,
        keyboardHeight: Int
    ) {
        this.traceId = UUID.randomUUID().toString()
        this.targetWord = targetWord.lowercase()
        this.timestampUtc = System.currentTimeMillis()
        this.screenWidthPx = screenWidth
        this.screenHeightPx = screenHeight
        this.keyboardHeightPx = keyboardHeight
        this.collectionSource = collectionSource
        this.lastAbsoluteTimestamp = timestampUtc // Initialize to start time
    }

    // Constructor from JSON (for loading stored data)
    @Throws(JSONException::class)
    constructor(json: JSONObject) {
        this.traceId = json.getString("trace_id")
        this.targetWord = json.getString("target_word")

        val metadata = json.getJSONObject("metadata")
        this.timestampUtc = metadata.getLong("timestamp_utc")
        this.screenWidthPx = metadata.getInt("screen_width_px")
        this.screenHeightPx = metadata.getInt("screen_height_px")
        this.keyboardHeightPx = metadata.getInt("keyboard_height_px")
        this.collectionSource = metadata.getString("collection_source")

        // Load trace points
        val pointsArray = json.getJSONArray("trace_points")
        for (i in 0 until pointsArray.length()) {
            val point = pointsArray.getJSONObject(i)
            tracePoints.add(
                TracePoint(
                    point.getDouble("x").toFloat(),
                    point.getDouble("y").toFloat(),
                    point.getLong("t_delta_ms")
                )
            )
        }

        // Load registered keys
        val keysArray = json.getJSONArray("registered_keys")
        for (i in 0 until keysArray.length()) {
            registeredKeys.add(keysArray.getString(i))
        }

        // Reconstruct last absolute timestamp from deltas
        this.lastAbsoluteTimestamp = timestampUtc
        for (point in tracePoints) {
            lastAbsoluteTimestamp += point.tDeltaMs
        }
    }

    /**
     * Add a raw trace point (will be normalized)
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
     */
    fun addRegisteredKey(key: String) {
        // Avoid consecutive duplicates
        if (registeredKeys.isEmpty() || registeredKeys.last() != key) {
            registeredKeys.add(key.lowercase())
        }
    }

    /**
     * Set keyboard dimensions for accurate position tracking
     */
    fun setKeyboardDimensions(screenWidth: Int, keyboardHeight: Int, keyboardOffsetY: Int) {
        this.keyboardOffsetY = keyboardOffsetY
        // Note: screenWidth and keyboardHeight are already set in constructor
        // This method mainly records the Y offset for position normalization
    }

    /**
     * Convert to JSON for storage and export
     */
    @Throws(JSONException::class)
    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("trace_id", traceId)
        json.put("target_word", targetWord)

        // Metadata
        val metadata = JSONObject().apply {
            put("timestamp_utc", timestampUtc)
            put("screen_width_px", screenWidthPx)
            put("screen_height_px", screenHeightPx)
            put("keyboard_height_px", keyboardHeightPx)
            put("keyboard_offset_y", keyboardOffsetY)
            put("collection_source", collectionSource)
        }
        json.put("metadata", metadata)

        // Trace points
        val pointsArray = JSONArray()
        for (point in tracePoints) {
            val p = JSONObject().apply {
                put("x", point.x)
                put("y", point.y)
                put("t_delta_ms", point.tDeltaMs)
            }
            pointsArray.put(p)
        }
        json.put("trace_points", pointsArray)

        // Registered keys
        val keysArray = JSONArray()
        for (key in registeredKeys) {
            keysArray.put(key)
        }
        json.put("registered_keys", keysArray)

        return json
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
        if (targetWord.isEmpty()) return false

        // Check for reasonable normalized values
        for (point in tracePoints) {
            if (point.x !in 0f..1f || point.y !in 0f..1f) return false
        }

        return true
    }

    /**
     * Calculate statistics for this swipe
     */
    fun calculateStatistics(): SwipeStatistics? {
        if (tracePoints.size < 2) return null

        var totalDistance = 0f
        var totalTime = 0L

        for (i in 1 until tracePoints.size) {
            val prev = tracePoints[i - 1]
            val curr = tracePoints[i]

            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            totalDistance += sqrt(dx * dx + dy * dy)
            totalTime += curr.tDeltaMs
        }

        // Calculate straightness ratio
        val start = tracePoints[0]
        val end = tracePoints[tracePoints.size - 1]
        val directDistance = sqrt((end.x - start.x).pow(2) + (end.y - start.y).pow(2))
        val straightnessRatio = if (totalDistance > 0) directDistance / totalDistance else 0f

        return SwipeStatistics(
            tracePoints.size,
            totalDistance,
            totalTime,
            straightnessRatio,
            registeredKeys.size
        )
    }

    // Getters with defensive copies
    fun getTracePoints(): List<TracePoint> = tracePoints.toList()
    fun getRegisteredKeys(): List<String> = registeredKeys.toList()

    /**
     * Inner class for normalized trace points
     */
    data class TracePoint(
        @JvmField val x: Float,       // Normalized [0, 1]
        @JvmField val y: Float,       // Normalized [0, 1]
        @JvmField val tDeltaMs: Long  // Time delta from previous point
    )

    /**
     * Statistics for analysis
     */
    data class SwipeStatistics(
        @JvmField val pointCount: Int,
        @JvmField val totalDistance: Float,
        @JvmField val totalTimeMs: Long,
        @JvmField val straightnessRatio: Float,
        @JvmField val keyCount: Int
    )
}
