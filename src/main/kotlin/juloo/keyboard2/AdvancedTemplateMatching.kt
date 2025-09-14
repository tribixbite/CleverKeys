package juloo.keyboard2

import android.graphics.PointF
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Advanced template matching algorithms for gesture recognition
 * Kotlin implementation with sophisticated pattern analysis
 */
class AdvancedTemplateMatching {
    
    companion object {
        private const val TAG = "AdvancedTemplateMatching"
        private const val DTW_WINDOW_SIZE = 10
        private const val TEMPLATE_CACHE_SIZE = 1000
    }
    
    private val templateCache = mutableMapOf<String, GestureTemplate>()
    private val dtwCache = mutableMapOf<Pair<Int, Int>, Float>()
    
    /**
     * Gesture template for matching
     */
    data class GestureTemplate(
        val word: String,
        val normalizedPath: List<PointF>,
        val features: TemplateFeatures,
        val frequency: Float
    )
    
    /**
     * Template features for matching
     */
    data class TemplateFeatures(
        val pathLength: Float,
        val duration: Float,
        val directionChanges: Int,
        val curvature: Float,
        val aspectRatio: Float,
        val centerOfMass: PointF,
        val boundingBox: Pair<PointF, PointF>
    )
    
    /**
     * Template matching result
     */
    data class MatchResult(
        val word: String,
        val score: Float,
        val method: MatchingMethod,
        val details: Map<String, Float>
    )
    
    /**
     * Matching methods
     */
    enum class MatchingMethod {
        DTW, // Dynamic Time Warping
        HAUSDORFF, // Hausdorff Distance
        FRECHET, // Fréchet Distance
        FEATURE_VECTOR, // Feature-based matching
        HYBRID // Combination of methods
    }
    
    /**
     * Generate gesture template from training data
     */
    suspend fun generateTemplate(word: String, trainingGestures: List<SwipeInput>): GestureTemplate = withContext(Dispatchers.Default) {
        // Normalize all training gestures
        val normalizedPaths = trainingGestures.map { gesture ->
            normalizeGesturePath(gesture.coordinates)
        }
        
        // Calculate average path
        val averagePath = calculateAveragePath(normalizedPaths)
        
        // Extract template features
        val features = extractTemplateFeatures(averagePath, trainingGestures)
        
        // Calculate frequency score
        val frequency = calculateWordFrequency(word)
        
        GestureTemplate(word, averagePath, features, frequency)
    }
    
    /**
     * Match gesture against template using multiple algorithms
     */
    suspend fun matchGesture(
        gesturePoints: List<PointF>, 
        template: GestureTemplate,
        method: MatchingMethod = MatchingMethod.HYBRID
    ): MatchResult = withContext(Dispatchers.Default) {
        
        val normalizedGesture = normalizeGesturePath(gesturePoints)
        
        val score = when (method) {
            MatchingMethod.DTW -> calculateDTWDistance(normalizedGesture, template.normalizedPath)
            MatchingMethod.HAUSDORFF -> calculateHausdorffDistance(normalizedGesture, template.normalizedPath)
            MatchingMethod.FRECHET -> calculateFrechetDistance(normalizedGesture, template.normalizedPath)
            MatchingMethod.FEATURE_VECTOR -> calculateFeatureVectorDistance(gesturePoints, template.features)
            MatchingMethod.HYBRID -> calculateHybridScore(gesturePoints, template)
        }
        
        MatchResult(
            word = template.word,
            score = score,
            method = method,
            details = getMatchingDetails(gesturePoints, template)
        )
    }
    
    /**
     * Dynamic Time Warping distance calculation
     */
    private suspend fun calculateDTWDistance(gesture: List<PointF>, template: List<PointF>): Float = withContext(Dispatchers.Default) {
        val m = gesture.size
        val n = template.size
        
        // Use cache for performance
        val cacheKey = gesture.hashCode() to template.hashCode()
        dtwCache[cacheKey]?.let { return@withContext it }
        
        // Initialize DTW matrix
        val dtw = Array(m + 1) { FloatArray(n + 1) { Float.MAX_VALUE } }
        dtw[0][0] = 0f
        
        // Fill DTW matrix with windowed approach for efficiency
        for (i in 1..m) {
            val windowStart = maxOf(1, i - DTW_WINDOW_SIZE)
            val windowEnd = minOf(n, i + DTW_WINDOW_SIZE)
            
            for (j in windowStart..windowEnd) {
                val cost = euclideanDistance(gesture[i - 1], template[j - 1])
                dtw[i][j] = cost + minOf(
                    dtw[i - 1][j],     // insertion
                    dtw[i][j - 1],     // deletion
                    dtw[i - 1][j - 1]  // match
                )
            }
        }
        
        val distance = dtw[m][n] / maxOf(m, n) // Normalize by path length
        
        // Cache result
        if (dtwCache.size < TEMPLATE_CACHE_SIZE) {
            dtwCache[cacheKey] = distance
        }
        
        1f - (distance / 100f).coerceAtMost(1f) // Convert to similarity score
    }
    
    /**
     * Hausdorff distance calculation
     */
    private fun calculateHausdorffDistance(gesture: List<PointF>, template: List<PointF>): Float {
        if (gesture.isEmpty() || template.isEmpty()) return 0f
        
        // Forward Hausdorff distance
        val maxMinDist1 = gesture.maxOf { gesturePoint ->
            template.minOf { templatePoint ->
                euclideanDistance(gesturePoint, templatePoint)
            }
        }
        
        // Backward Hausdorff distance
        val maxMinDist2 = template.maxOf { templatePoint ->
            gesture.minOf { gesturePoint ->
                euclideanDistance(templatePoint, gesturePoint)
            }
        }
        
        val hausdorffDist = maxOf(maxMinDist1, maxMinDist2)
        return 1f - (hausdorffDist / 200f).coerceAtMost(1f) // Convert to similarity
    }
    
    /**
     * Fréchet distance calculation (simplified)
     */
    private fun calculateFrechetDistance(gesture: List<PointF>, template: List<PointF>): Float {
        // Simplified Fréchet distance - full implementation is complex
        val avgDistance = gesture.zip(template.take(gesture.size)).map { (g, t) ->
            euclideanDistance(g, t)
        }.average().toFloat()
        
        return 1f - (avgDistance / 100f).coerceAtMost(1f)
    }
    
    /**
     * Feature vector distance calculation
     */
    private fun calculateFeatureVectorDistance(gesturePoints: List<PointF>, templateFeatures: TemplateFeatures): Float {
        val gestureFeatures = extractGestureFeatures(gesturePoints)
        
        // Calculate weighted feature distances
        val pathLengthDiff = abs(gestureFeatures.pathLength - templateFeatures.pathLength) / 500f
        val durationDiff = abs(gestureFeatures.duration - templateFeatures.duration) / 2f
        val directionDiff = abs(gestureFeatures.directionChanges - templateFeatures.directionChanges) / 10f
        val curvatureDiff = abs(gestureFeatures.curvature - templateFeatures.curvature)
        val aspectRatioDiff = abs(gestureFeatures.aspectRatio - templateFeatures.aspectRatio)
        
        // Weighted combination
        val totalDiff = pathLengthDiff * 0.3f + 
                       durationDiff * 0.2f + 
                       directionDiff * 0.2f + 
                       curvatureDiff * 0.15f + 
                       aspectRatioDiff * 0.15f
        
        return (1f - totalDiff.coerceAtMost(1f))
    }
    
    /**
     * Hybrid scoring combining multiple methods
     */
    private suspend fun calculateHybridScore(gesturePoints: List<PointF>, template: GestureTemplate): Float {
        val normalizedGesture = normalizeGesturePath(gesturePoints)
        
        // Get scores from different methods
        val dtwScore = calculateDTWDistance(normalizedGesture, template.normalizedPath)
        val hausdorffScore = calculateHausdorffDistance(normalizedGesture, template.normalizedPath)
        val featureScore = calculateFeatureVectorDistance(gesturePoints, template.features)
        
        // Weighted combination with frequency boost
        val combinedScore = dtwScore * 0.4f + 
                           hausdorffScore * 0.3f + 
                           featureScore * 0.3f
        
        // Apply frequency boost
        val frequencyBoost = template.frequency * 0.1f
        
        return (combinedScore + frequencyBoost).coerceAtMost(1f)
    }
    
    /**
     * Normalize gesture path to standard size and position
     */
    private fun normalizeGesturePath(points: List<PointF>): List<PointF> {
        if (points.isEmpty()) return emptyList()
        
        // Find bounding box
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        
        val width = maxX - minX
        val height = maxY - minY
        val scale = maxOf(width, height)
        
        if (scale == 0f) return points
        
        // Normalize to [0, 1] range
        return points.map { point ->
            PointF(
                (point.x - minX) / scale,
                (point.y - minY) / scale
            )
        }
    }
    
    /**
     * Calculate average path from multiple gestures
     */
    private fun calculateAveragePath(paths: List<List<PointF>>): List<PointF> {
        if (paths.isEmpty()) return emptyList()
        
        val maxLength = paths.maxOf { it.size }
        val averagePath = mutableListOf<PointF>()
        
        for (i in 0 until maxLength) {
            val pointsAtIndex = paths.mapNotNull { path ->
                if (i < path.size) path[i] else null
            }
            
            if (pointsAtIndex.isNotEmpty()) {
                val avgX = pointsAtIndex.map { it.x }.average().toFloat()
                val avgY = pointsAtIndex.map { it.y }.average().toFloat()
                averagePath.add(PointF(avgX, avgY))
            }
        }
        
        return averagePath
    }
    
    /**
     * Extract comprehensive template features
     */
    private fun extractTemplateFeatures(path: List<PointF>, trainingGestures: List<SwipeInput>): TemplateFeatures {
        val pathLength = path.pathLength()
        val duration = trainingGestures.map { it.duration }.average().toFloat()
        val directionChanges = trainingGestures.map { it.directionChanges }.average().toInt()
        val curvature = Utils.calculateCurvature(path)
        
        val (minPoint, maxPoint) = path.boundingBox()
        val width = maxPoint.x - minPoint.x
        val height = maxPoint.y - minPoint.y
        val aspectRatio = if (height > 0) width / height else 1f
        
        val centerOfMass = PointF(
            path.map { it.x }.average().toFloat(),
            path.map { it.y }.average().toFloat()
        )
        
        return TemplateFeatures(
            pathLength = pathLength,
            duration = duration,
            directionChanges = directionChanges,
            curvature = curvature,
            aspectRatio = aspectRatio,
            centerOfMass = centerOfMass,
            boundingBox = minPoint to maxPoint
        )
    }
    
    /**
     * Extract features from gesture
     */
    private fun extractGestureFeatures(points: List<PointF>): TemplateFeatures {
        val pathLength = points.pathLength()
        val directionChanges = Utils.calculateCurvature(points) // Approximate
        val curvature = Utils.calculateCurvature(points)
        
        val (minPoint, maxPoint) = points.boundingBox()
        val width = maxPoint.x - minPoint.x
        val height = maxPoint.y - minPoint.y
        val aspectRatio = if (height > 0) width / height else 1f
        
        val centerOfMass = PointF(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        )
        
        return TemplateFeatures(
            pathLength = pathLength,
            duration = 1f, // Would need actual duration
            directionChanges = directionChanges.toInt(),
            curvature = curvature,
            aspectRatio = aspectRatio,
            centerOfMass = centerOfMass,
            boundingBox = minPoint to maxPoint
        )
    }
    
    /**
     * Get detailed matching information
     */
    private fun getMatchingDetails(gesturePoints: List<PointF>, template: GestureTemplate): Map<String, Float> {
        val gestureFeatures = extractGestureFeatures(gesturePoints)
        
        return mapOf(
            "path_length_similarity" to calculatePathLengthSimilarity(gestureFeatures.pathLength, template.features.pathLength),
            "curvature_similarity" to calculateCurvatureSimilarity(gestureFeatures.curvature, template.features.curvature),
            "aspect_ratio_similarity" to calculateAspectRatioSimilarity(gestureFeatures.aspectRatio, template.features.aspectRatio),
            "direction_similarity" to calculateDirectionSimilarity(gestureFeatures.directionChanges, template.features.directionChanges),
            "frequency_boost" to template.frequency
        )
    }
    
    /**
     * Calculate path length similarity
     */
    private fun calculatePathLengthSimilarity(gesture: Float, template: Float): Float {
        val diff = abs(gesture - template)
        val avgLength = (gesture + template) / 2f
        return if (avgLength > 0) 1f - (diff / avgLength).coerceAtMost(1f) else 0f
    }
    
    /**
     * Calculate curvature similarity
     */
    private fun calculateCurvatureSimilarity(gesture: Float, template: Float): Float {
        val diff = abs(gesture - template)
        return 1f - diff.coerceAtMost(1f)
    }
    
    /**
     * Calculate aspect ratio similarity
     */
    private fun calculateAspectRatioSimilarity(gesture: Float, template: Float): Float {
        val ratio = minOf(gesture, template) / maxOf(gesture, template)
        return ratio.coerceIn(0f, 1f)
    }
    
    /**
     * Calculate direction change similarity
     */
    private fun calculateDirectionSimilarity(gesture: Int, template: Int): Float {
        val diff = abs(gesture - template)
        val maxChanges = maxOf(gesture, template)
        return if (maxChanges > 0) 1f - (diff.toFloat() / maxChanges) else 1f
    }
    
    /**
     * Calculate word frequency score
     */
    private fun calculateWordFrequency(word: String): Float {
        // Common English word frequencies (simplified)
        val frequencies = mapOf(
            "the" to 0.9f, "and" to 0.8f, "you" to 0.7f, "that" to 0.6f,
            "hello" to 0.5f, "world" to 0.4f, "keyboard" to 0.3f, "swipe" to 0.3f
        )
        
        return frequencies[word.lowercase()] ?: 0.1f
    }
    
    /**
     * Euclidean distance between points
     */
    private fun euclideanDistance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
    
    /**
     * Add template to cache
     */
    fun addTemplate(template: GestureTemplate) {
        if (templateCache.size >= TEMPLATE_CACHE_SIZE) {
            // Remove least frequently used template
            val lfu = templateCache.minByOrNull { it.value.frequency }
            lfu?.let { templateCache.remove(it.key) }
        }
        
        templateCache[template.word] = template
        logD("Added template for '${template.word}' (cache size: ${templateCache.size})")
    }
    
    /**
     * Get all cached templates
     */
    fun getCachedTemplates(): List<GestureTemplate> = templateCache.values.toList()
    
    /**
     * Clear template cache
     */
    fun clearCache() {
        templateCache.clear()
        dtwCache.clear()
        logD("Template cache cleared")
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            templateCount = templateCache.size,
            dtwCacheSize = dtwCache.size,
            memoryUsageBytes = estimateMemoryUsage()
        )
    }
    
    /**
     * Estimate memory usage
     */
    private fun estimateMemoryUsage(): Long {
        val templateMemory = templateCache.values.sumOf { template ->
            template.normalizedPath.size * 8L + // 8 bytes per PointF
            100L // Approximate overhead
        }
        
        val dtwCacheMemory = dtwCache.size * 12L // Approximate per entry
        
        return templateMemory + dtwCacheMemory
    }
    
    /**
     * Cache statistics
     */
    data class CacheStats(
        val templateCount: Int,
        val dtwCacheSize: Int,
        val memoryUsageBytes: Long
    )
}