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
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val length: Int,
        val avgX: Float,
        val avgY: Float
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
                    startX = start.x,
                    startY = start.y,
                    endX = end.x,
                    endY = end.y,
                    length = coords.size,
                    avgX = avgX,
                    avgY = avgY
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
            val startDist = distance(startX, startY, other.startX, other.startY)
            val endDist = distance(endX, endY, other.endX, other.endY)
            val avgDist = distance(avgX, avgY, other.avgX, other.avgY)

            return startDist < distanceThreshold &&
                   endDist < distanceThreshold &&
                   avgDist < distanceThreshold
        }

        private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }
    }

    private data class CacheEntry(
        val key: CacheKey,
        val result: PredictionResult,
        var lastAccessTime: Long = System.currentTimeMillis()
    )

    private val cache = mutableListOf<CacheEntry>()
    private val cacheLock = Any()

    // Cache metrics (Bug #186 fix)
    private var hitCount = 0L
    private var missCount = 0L

    /**
     * Get cached prediction if similar gesture exists
     * Bug #184 fix: Thread-safe access with synchronization
     * Bug #186 fix: Track hit/miss metrics
     */
    fun get(coordinates: List<PointF>): PredictionResult? {
        val queryKey = CacheKey.fromCoordinates(coordinates) ?: return null

        synchronized(cacheLock) {
            // Find similar entry in cache
            val entry = cache.find { it.key.isSimilarTo(queryKey) }

            if (entry != null) {
                // Update access time (LRU)
                entry.lastAccessTime = System.currentTimeMillis()
                hitCount++
                logD("Cache hit! Returning cached prediction (hit rate: ${getHitRate()}%)")
                return entry.result
            }

            missCount++
            return null
        }
    }

    /**
     * Put prediction result in cache
     * Bug #184 fix: Thread-safe access with synchronization
     */
    fun put(coordinates: List<PointF>, result: PredictionResult) {
        val key = CacheKey.fromCoordinates(coordinates) ?: return

        synchronized(cacheLock) {
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
    }

    /**
     * Clear all cached predictions
     * Bug #184 fix: Thread-safe access with synchronization
     */
    fun clear() {
        synchronized(cacheLock) {
            cache.clear()
            logD("Prediction cache cleared")
        }
    }

    /**
     * Reset cache metrics
     * Bug #186 fix: Allow resetting hit/miss counters
     */
    fun resetMetrics() {
        synchronized(cacheLock) {
            hitCount = 0L
            missCount = 0L
            logD("Cache metrics reset")
        }
    }

    /**
     * Get current cache statistics
     * Bug #184 fix: Thread-safe access with synchronization
     * Bug #186 fix: Include hit/miss metrics
     */
    fun getStats(): CacheStats {
        synchronized(cacheLock) {
            return CacheStats(
                size = cache.size,
                maxSize = maxSize,
                hits = hitCount,
                misses = missCount,
                totalRequests = hitCount + missCount,
                hitRate = getHitRate()
            )
        }
    }

    /**
     * Calculate hit rate percentage
     * Bug #186 fix: Helper for hit rate calculation
     */
    private fun getHitRate(): Double {
        val total = hitCount + missCount
        return if (total > 0) (hitCount.toDouble() / total.toDouble()) * 100.0 else 0.0
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hits: Long,
        val misses: Long,
        val totalRequests: Long,
        val hitRate: Double
    )

    companion object {
        private const val TAG = "PredictionCache"
    }

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
    }
}
