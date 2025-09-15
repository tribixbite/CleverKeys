package tribixbite.keyboard2.ml

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * ML data model for swipe typing training data
 * Captures normalized swipe traces with metadata for neural network training
 * 
 * Kotlin data class with JSON serialization and builder pattern
 */
data class SwipeMLData(
    val traceId: String = UUID.randomUUID().toString(),
    val targetWord: String,
    val timestampUtc: Long = System.currentTimeMillis(),
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val keyboardHeightPx: Int,
    val collectionSource: String, // "calibration" or "user_selection"
    val tracePoints: MutableList<TracePoint> = mutableListOf(),
    val registeredKeys: MutableList<String> = mutableListOf(),
    var keyboardOffsetY: Int = 0
) {
    
    /**
     * Normalized trace point with timing
     */
    data class TracePoint(
        val x: Float, // Normalized [0, 1]
        val y: Float, // Normalized [0, 1] 
        val tDeltaMs: Long // Time delta from gesture start
    )
    
    /**
     * Add a raw trace point (will be normalized)
     */
    fun addRawPoint(rawX: Float, rawY: Float, timestamp: Long) {
        val normalizedX = rawX / screenWidthPx
        val normalizedY = rawY / screenHeightPx
        
        // Calculate time delta from first point
        val tDeltaMs = if (tracePoints.isEmpty()) 0L 
        else timestamp - (timestampUtc - tracePoints.first().tDeltaMs)
        
        tracePoints.add(TracePoint(normalizedX, normalizedY, tDeltaMs))
    }
    
    /**
     * Add a registered key that was touched during swipe
     */
    fun addRegisteredKey(key: String) {
        if (key.isNotBlank()) {
            registeredKeys.add(key)
        }
    }
    
    /**
     * Convert to JSON for export/storage
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("trace_id", traceId)
        put("target_word", targetWord)
        
        put("metadata", JSONObject().apply {
            put("timestamp_utc", timestampUtc)
            put("screen_width_px", screenWidthPx)
            put("screen_height_px", screenHeightPx)
            put("keyboard_height_px", keyboardHeightPx)
            put("keyboard_offset_y", keyboardOffsetY)
            put("collection_source", collectionSource)
        })
        
        put("trace_points", JSONArray().apply {
            tracePoints.forEach { point ->
                put(JSONObject().apply {
                    put("x", point.x)
                    put("y", point.y)
                    put("t_delta_ms", point.tDeltaMs)
                })
            }
        })
        
        put("registered_keys", JSONArray().apply {
            registeredKeys.forEach { put(it) }
        })
    }
    
    /**
     * Computed properties for analysis
     */
    val pathLength: Float by lazy {
        tracePoints.zipWithNext { p1, p2 ->
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            kotlin.math.sqrt(dx * dx + dy * dy)
        }.sum()
    }
    
    val duration: Float by lazy {
        if (tracePoints.size < 2) 0f
        else (tracePoints.last().tDeltaMs - tracePoints.first().tDeltaMs) / 1000f
    }
    
    val averageVelocity: Float by lazy {
        if (duration > 0) pathLength / duration else 0f
    }
    
    companion object {
        /**
         * Create from JSON object
         */
        fun fromJson(json: JSONObject): SwipeMLData {
            val metadata = json.getJSONObject("metadata")
            
            val data = SwipeMLData(
                traceId = json.getString("trace_id"),
                targetWord = json.getString("target_word"),
                timestampUtc = metadata.getLong("timestamp_utc"),
                screenWidthPx = metadata.getInt("screen_width_px"),
                screenHeightPx = metadata.getInt("screen_height_px"),
                keyboardHeightPx = metadata.getInt("keyboard_height_px"),
                collectionSource = metadata.getString("collection_source")
            )
            
            // Load trace points
            val pointsArray = json.getJSONArray("trace_points")
            for (i in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(i)
                data.tracePoints.add(TracePoint(
                    x = point.getDouble("x").toFloat(),
                    y = point.getDouble("y").toFloat(),
                    tDeltaMs = point.getLong("t_delta_ms")
                ))
            }
            
            // Load registered keys
            val keysArray = json.getJSONArray("registered_keys")
            for (i in 0 until keysArray.length()) {
                data.registeredKeys.add(keysArray.getString(i))
            }
            
            if (metadata.has("keyboard_offset_y")) {
                data.keyboardOffsetY = metadata.getInt("keyboard_offset_y")
            }
            
            return data
        }
    }
}