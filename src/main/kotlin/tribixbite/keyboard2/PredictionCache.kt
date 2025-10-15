package tribixbite.keyboard2

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * LRU cache for neural prediction results to avoid redundant ONNX inference
 * Caches predictions based on gesture similarity
 */
class PredictionCache(
    private val maxSize: Int = 20
) {

    private data class CacheKey(
        val startPoint: PointF,
        val endPoint: PointF,
        val length: Int,
        val averagePoint: PointF
    ) {
        companion object {
            /**
             * Create cache key from swipe coordinates with simplified representation
             */
            fun fromCoordinates(coords: List<PointF>): CacheKey? {
                if (coords.size < 2) return null

                val start = coords.first()
                val end = coords.last()
                val avgX = coords.map { it.x }.average().toFloat()
                val avgY = coords.map { it.y }.average().toFloat()

                return CacheKey(
                    startPoint = start,
                    endPoint = end,
                    length = coords.size,
                    averagePoint = PointF(avgX, avgY)
                )
            }
        }

        /**
         * Check if this key is similar to another (within tolerance)
         */
        fun isSimilarTo(other: CacheKey, distanceThreshold: Float = 50f): Boolean {
            // Check if lengths are similar (within 20%)
            val lengthRatio = length.toFloat() / other.length.toFloat()
            if (lengthRatio < 0.8f || lengthRatio > 1.2f) return false

            // Check if start/end points are close
            val startDist = distance(startPoint, other.startPoint)
            val endDist = distance(endPoint, other.endPoint)
            val avgDist = distance(averagePoint, other.averagePoint)

            return startDist < distanceThreshold &&
                   endDist < distanceThreshold &&
                   avgDist < distanceThreshold
        }

        private fun distance(p1: PointF, p2: PointF): Float {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return sqrt(dx * dx + dy * dy)
        }
    }

    private data class CacheEntry(
        val key: CacheKey,
        val result: PredictionResult,
        var lastAccessTime: Long = System.currentTimeMillis()
    )

    private val cache = mutableListOf<CacheEntry>()

    /**
     * Get cached prediction if similar gesture exists
     */
    fun get(coordinates: List<PointF>): PredictionResult? {
        val queryKey = CacheKey.fromCoordinates(coordinates) ?: return null

        // Find similar entry in cache
        val entry = cache.find { it.key.isSimilarTo(queryKey) }

        if (entry != null) {
            // Update access time (LRU)
            entry.lastAccessTime = System.currentTimeMillis()
            logD("Cache hit! Returning cached prediction")
            return entry.result
        }

        return null
    }

    /**
     * Put prediction result in cache
     */
    fun put(coordinates: List<PointF>, result: PredictionResult) {
        val key = CacheKey.fromCoordinates(coordinates) ?: return

        // Remove similar existing entry if present
        cache.removeAll { it.key.isSimilarTo(key) }

        // Add new entry
        cache.add(CacheEntry(key, result))

        // Evict oldest if cache is full (LRU)
        if (cache.size > maxSize) {
            val oldest = cache.minByOrNull { it.lastAccessTime }
            oldest?.let { cache.remove(it) }
        }

        logD("Cached prediction (cache size: ${cache.size})")
    }

    /**
     * Clear all cached predictions
     */
    fun clear() {
        cache.clear()
        logD("Prediction cache cleared")
    }

    /**
     * Get current cache statistics
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            maxSize = maxSize
        )
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int
    )

    companion object {
        private const val TAG = "PredictionCache"
    }

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }
}
