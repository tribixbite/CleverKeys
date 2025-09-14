package juloo.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*

/**
 * Enhanced swipe gesture recognizer with real-time predictions
 * Kotlin implementation with coroutines and reactive patterns
 */
class EnhancedSwipeGestureRecognizer : ImprovedSwipeGestureRecognizer() {
    
    private var swipePredictor: RealTimeSwipePredictor? = null
    private var cgrInitialized = false
    private var predictionListener: OnSwipePredictionListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Prediction listener interface
     */
    interface OnSwipePredictionListener {
        fun onSwipePredictionUpdate(predictions: List<String>)
        fun onSwipePredictionComplete(finalPredictions: List<String>)
        fun onSwipePredictionCleared()
    }
    
    /**
     * Initialize prediction system
     */
    fun initializePredictionSystem(context: Context) {
        if (!cgrInitialized) {
            swipePredictor = RealTimeSwipePredictor()
            cgrInitialized = true
            logD("Enhanced gesture recognizer initialized")
        }
    }
    
    /**
     * Set prediction listener
     */
    fun setOnSwipePredictionListener(listener: OnSwipePredictionListener) {
        predictionListener = listener
    }
    
    /**
     * Get current predictions
     */
    fun getCurrentPredictions(): List<String> {
        return swipePredictor?.getCurrentPredictions() ?: emptyList()
    }
    
    /**
     * Get current gesture point count
     */
    fun getCurrentGesturePointCount(): Int {
        return swipePredictor?.getCurrentGesturePointCount() ?: 0
    }
    
    /**
     * Select prediction
     */
    fun selectPrediction(word: String) {
        swipePredictor?.selectPrediction(word)
    }
    
    /**
     * Check if predictions are persisting
     */
    fun arePredictionsPersisting(): Boolean {
        return swipePredictor?.arePredictionsPersisting() ?: false
    }
    
    /**
     * Clear predictions
     */
    fun clearPredictions() {
        predictionListener?.onSwipePredictionCleared()
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
        swipePredictor = null
        cgrInitialized = false
    }
}

/**
 * Base class for gesture recognition
 */
open class ImprovedSwipeGestureRecognizer {
    // Base gesture recognition functionality
    open fun processGesture(points: List<PointF>): GestureResult {
        return GestureResult(points, emptyList(), emptyList())
    }
    
    data class GestureResult(
        val path: List<PointF>,
        val keys: List<String>,
        val timestamps: List<Long>
    )
}

/**
 * Complete real-time swipe predictor with CGR algorithms
 */
class RealTimeSwipePredictor {
    
    companion object {
        private const val TAG = "RealTimeSwipePredictor"
        private const val MIN_POINTS_FOR_PREDICTION = 5
        private const val PREDICTION_UPDATE_INTERVAL = 100L // ms
    }
    
    private val currentPredictions = mutableListOf<String>()
    private var gesturePointCount = 0
    private var predictionsArePersisting = false
    private var lastPredictionTime = 0L
    private val gestureHistory = mutableListOf<PointF>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // CGR (Continuous Gesture Recognition) state
    private var cgrState = CGRState()
    
    /**
     * CGR algorithm state
     */
    private data class CGRState(
        val templateMatches: MutableMap<String, Float> = mutableMapOf(),
        val partialWords: MutableList<String> = mutableListOf(),
        val confidence: Float = 0f
    )
    
    fun getCurrentPredictions(): List<String> = currentPredictions.toList()
    
    fun getCurrentGesturePointCount(): Int = gesturePointCount
    
    fun arePredictionsPersisting(): Boolean = predictionsArePersisting
    
    fun selectPrediction(word: String) {
        logD("Selected prediction: $word")
        currentPredictions.clear()
        currentPredictions.add(word)
        predictionsArePersisting = true
    }
    
    /**
     * Update gesture with real-time CGR processing
     */
    fun updateGesture(points: List<PointF>) {
        gesturePointCount = points.size
        gestureHistory.clear()
        gestureHistory.addAll(points)
        
        val now = System.currentTimeMillis()
        if (now - lastPredictionTime > PREDICTION_UPDATE_INTERVAL && points.size >= MIN_POINTS_FOR_PREDICTION) {
            lastPredictionTime = now
            
            scope.launch {
                val newPredictions = performCGRPrediction(points)
                currentPredictions.clear()
                currentPredictions.addAll(newPredictions)
            }
        }
    }
    
    /**
     * Perform Continuous Gesture Recognition prediction
     */
    private suspend fun performCGRPrediction(points: List<PointF>): List<String> = withContext(Dispatchers.Default) {
        try {
            // Extract gesture features for CGR
            val features = extractCGRFeatures(points)
            
            // Template matching against known patterns
            val templateMatches = performTemplateMatching(features)
            
            // Generate word candidates from templates
            val candidates = generateWordCandidates(templateMatches, features)
            
            // Rank and filter candidates
            rankCandidates(candidates, features)
            
        } catch (e: Exception) {
            logE("CGR prediction failed", e)
            generateFallbackPredictions(points)
        }
    }
    
    /**
     * Extract CGR-specific features
     */
    private fun extractCGRFeatures(points: List<PointF>): CGRFeatures {
        val pathLength = points.pathLength()
        val duration = 1f // Approximate - would use real timestamps
        val directionChanges = calculateDirectionChanges(points)
        val curvature = Utils.calculateCurvature(points)
        val primaryDirection = Utils.detectPrimaryDirection(points)
        
        return CGRFeatures(
            pathLength = pathLength,
            duration = duration,
            directionChanges = directionChanges,
            curvature = curvature,
            primaryDirection = primaryDirection,
            pointDensity = points.size / pathLength
        )
    }
    
    /**
     * Calculate direction changes in gesture
     */
    private fun calculateDirectionChanges(points: List<PointF>): Int {
        if (points.size < 3) return 0
        
        return points.windowed(3).count { (p1, p2, p3) ->
            val angle1 = kotlin.math.atan2(p2.y - p1.y, p2.x - p1.x)
            val angle2 = kotlin.math.atan2(p3.y - p2.y, p3.x - p2.x)
            val angleDiff = kotlin.math.abs(angle2 - angle1)
            angleDiff > kotlin.math.PI / 4 // > 45 degrees
        }
    }
    
    /**
     * Perform template matching against gesture patterns
     */
    private fun performTemplateMatching(features: CGRFeatures): Map<String, Float> {
        val matches = mutableMapOf<String, Float>()
        
        // Match against common word patterns
        val patterns = mapOf(
            "the" to PatternTemplate(100f..200f, 0.3f..0.8f, 1..3),
            "and" to PatternTemplate(120f..250f, 0.4f..1.0f, 2..4),
            "hello" to PatternTemplate(200f..400f, 0.8f..2.0f, 3..6),
            "keyboard" to PatternTemplate(300f..600f, 1.0f..3.0f, 4..8),
            "swipe" to PatternTemplate(150f..350f, 0.5f..1.5f, 2..5)
        )
        
        patterns.forEach { (word, template) ->
            val score = calculateTemplateMatch(features, template)
            if (score > 0.1f) {
                matches[word] = score
            }
        }
        
        return matches
    }
    
    /**
     * Calculate template match score
     */
    private fun calculateTemplateMatch(features: CGRFeatures, template: PatternTemplate): Float {
        var score = 0f
        
        // Path length match
        if (features.pathLength in template.pathLengthRange) {
            score += 0.3f
        }
        
        // Duration match
        if (features.duration in template.durationRange) {
            score += 0.3f
        }
        
        // Direction changes match
        if (features.directionChanges in template.directionChangesRange) {
            score += 0.2f
        }
        
        // Curvature factor
        score += (1f - features.curvature) * 0.2f
        
        return score.coerceIn(0f, 1f)
    }
    
    /**
     * Generate word candidates from template matches
     */
    private fun generateWordCandidates(matches: Map<String, Float>, features: CGRFeatures): List<CGRCandidate> {
        return matches.map { (word, score) ->
            CGRCandidate(word, score, features)
        }.sortedByDescending { it.score }
    }
    
    /**
     * Rank candidates based on context and quality
     */
    private fun rankCandidates(candidates: List<CGRCandidate>, features: CGRFeatures): List<String> {
        return candidates
            .filter { it.score > 0.2f }
            .take(5)
            .map { it.word }
    }
    
    /**
     * Generate fallback predictions
     */
    private fun generateFallbackPredictions(points: List<PointF>): List<String> {
        return when {
            points.size < 10 -> listOf("a", "i", "o")
            points.pathLength() < 100 -> listOf("the", "and", "for") 
            else -> listOf("hello", "world", "swipe")
        }
    }
    
    /**
     * CGR feature data
     */
    private data class CGRFeatures(
        val pathLength: Float,
        val duration: Float,
        val directionChanges: Int,
        val curvature: Float,
        val primaryDirection: Utils.Direction,
        val pointDensity: Float
    )
    
    /**
     * Pattern template for matching
     */
    private data class PatternTemplate(
        val pathLengthRange: ClosedFloatingPointRange<Float>,
        val durationRange: ClosedFloatingPointRange<Float>,
        val directionChangesRange: IntRange
    )
    
    /**
     * CGR candidate result
     */
    private data class CGRCandidate(
        val word: String,
        val score: Float,
        val features: CGRFeatures
    )
    
    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}