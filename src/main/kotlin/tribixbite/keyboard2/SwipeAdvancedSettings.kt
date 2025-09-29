package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced neural swipe settings for ONNX-based gesture recognition
 * These parameters affect neural prediction accuracy and trajectory processing
 *
 * Replaces legacy CGR/DTW parameters with neural-specific configuration:
 * - Trajectory preprocessing parameters
 * - Neural inference settings
 * - Gesture filtering and validation
 * - Performance optimization settings
 */
class SwipeAdvancedSettings private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: SwipeAdvancedSettings? = null

        fun getInstance(context: Context): SwipeAdvancedSettings {
            return instance ?: synchronized(this) {
                instance ?: SwipeAdvancedSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("neural_swipe_advanced", Context.MODE_PRIVATE)

    // Trajectory preprocessing parameters
    var trajectoryMinPoints = 8             // Minimum points for valid gesture
        set(value) { field = value.coerceIn(3, 50); saveSettings() }
    var trajectoryMaxPoints = 200           // Maximum points (more = higher quality, slower)
        set(value) { field = value.coerceIn(50, 1000); saveSettings() }
    var trajectorySmoothingWindow = 3       // Moving average window for smoothing
        set(value) { field = value.coerceIn(1, 10); saveSettings() }
    var trajectoryResamplingDistance = 5.0f // Distance between resampled points (pixels)
        set(value) { field = value.coerceIn(1.0f, 20.0f); saveSettings() }

    // Neural feature extraction
    var featureNormalizationRange = 1.0f    // Range for coordinate normalization
        set(value) { field = value.coerceIn(0.1f, 10.0f); saveSettings() }
    var velocityWindowSize = 5              // Window for velocity calculation
        set(value) { field = value.coerceIn(2, 20); saveSettings() }
    var curvatureWindowSize = 3             // Window for curvature calculation
        set(value) { field = value.coerceIn(2, 10); saveSettings() }
    var featureInterpolationPoints = 64     // Fixed-size feature vector length
        set(value) { field = value.coerceIn(16, 256); saveSettings() }

    // Gesture validation parameters
    var minGestureLength = 20.0f            // Minimum gesture length (pixels)
        set(value) { field = value.coerceIn(5.0f, 100.0f); saveSettings() }
    var maxGestureLength = 2000.0f          // Maximum gesture length (pixels)
        set(value) { field = value.coerceIn(500.0f, 5000.0f); saveSettings() }
    var minGestureDuration = 50L            // Minimum gesture duration (ms)
        set(value) { field = value.coerceIn(10L, 500L); saveSettings() }
    var maxGestureDuration = 3000L          // Maximum gesture duration (ms)
        set(value) { field = value.coerceIn(1000L, 10000L); saveSettings() }

    // Neural inference optimization
    var batchInferenceEnabled = true        // Enable batched inference
    var maxBatchSize = 4                    // Maximum batch size for inference
        set(value) { field = value.coerceIn(1, 16); saveSettings() }
    var tensorCacheSize = 8                 // Number of tensors to keep in cache
        set(value) { field = value.coerceIn(1, 32); saveSettings() }
    var memoryOptimizationLevel = 2         // 0=disabled, 1=basic, 2=aggressive
        set(value) { field = value.coerceIn(0, 3); saveSettings() }

    // Prediction filtering
    var minPredictionConfidence = 0.05f     // Minimum confidence to consider
        set(value) { field = value.coerceIn(0.001f, 0.5f); saveSettings() }
    var maxPredictions = 8                  // Maximum number of predictions to return
        set(value) { field = value.coerceIn(1, 20); saveSettings() }
    var duplicateFilterEnabled = true       // Filter duplicate predictions
    var lengthPenaltyFactor = 0.1f          // Penalty for very long/short words
        set(value) { field = value.coerceIn(0.0f, 1.0f); saveSettings() }

    // Performance tuning
    var enableParallelProcessing = true     // Use multiple threads for processing
    var workerThreadCount = 2               // Number of worker threads
        set(value) { field = value.coerceIn(1, 8); saveSettings() }
    var predictionTimeoutMs = 200L          // Maximum time for prediction (ms)
        set(value) { field = value.coerceIn(50L, 2000L); saveSettings() }
    var enableDebugLogging = false          // Enable detailed debug logs

    // Calibration and adaptation
    var adaptationLearningRate = 0.01f      // Rate for online adaptation
        set(value) { field = value.coerceIn(0.001f, 0.1f); saveSettings() }
    var calibrationDataWeight = 0.3f        // Weight for user calibration data
        set(value) { field = value.coerceIn(0.0f, 1.0f); saveSettings() }
    var enablePersonalization = true       // Enable user-specific optimization
    var personalizationDecayRate = 0.995f  // Decay rate for personalization data
        set(value) { field = value.coerceIn(0.9f, 0.999f); saveSettings() }

    init {
        loadSettings()
    }

    private fun loadSettings() {
        // Trajectory preprocessing
        trajectoryMinPoints = prefs.getInt("trajectory_min_points", 8)
        trajectoryMaxPoints = prefs.getInt("trajectory_max_points", 200)
        trajectorySmoothingWindow = prefs.getInt("trajectory_smoothing_window", 3)
        trajectoryResamplingDistance = prefs.getFloat("trajectory_resampling_distance", 5.0f)

        // Neural feature extraction
        featureNormalizationRange = prefs.getFloat("feature_normalization_range", 1.0f)
        velocityWindowSize = prefs.getInt("velocity_window_size", 5)
        curvatureWindowSize = prefs.getInt("curvature_window_size", 3)
        featureInterpolationPoints = prefs.getInt("feature_interpolation_points", 64)

        // Gesture validation
        minGestureLength = prefs.getFloat("min_gesture_length", 20.0f)
        maxGestureLength = prefs.getFloat("max_gesture_length", 2000.0f)
        minGestureDuration = prefs.getLong("min_gesture_duration", 50L)
        maxGestureDuration = prefs.getLong("max_gesture_duration", 3000L)

        // Neural inference optimization
        batchInferenceEnabled = prefs.getBoolean("batch_inference_enabled", true)
        maxBatchSize = prefs.getInt("max_batch_size", 4)
        tensorCacheSize = prefs.getInt("tensor_cache_size", 8)
        memoryOptimizationLevel = prefs.getInt("memory_optimization_level", 2)

        // Prediction filtering
        minPredictionConfidence = prefs.getFloat("min_prediction_confidence", 0.05f)
        maxPredictions = prefs.getInt("max_predictions", 8)
        duplicateFilterEnabled = prefs.getBoolean("duplicate_filter_enabled", true)
        lengthPenaltyFactor = prefs.getFloat("length_penalty_factor", 0.1f)

        // Performance tuning
        enableParallelProcessing = prefs.getBoolean("enable_parallel_processing", true)
        workerThreadCount = prefs.getInt("worker_thread_count", 2)
        predictionTimeoutMs = prefs.getLong("prediction_timeout_ms", 200L)
        enableDebugLogging = prefs.getBoolean("enable_debug_logging", false)

        // Calibration and adaptation
        adaptationLearningRate = prefs.getFloat("adaptation_learning_rate", 0.01f)
        calibrationDataWeight = prefs.getFloat("calibration_data_weight", 0.3f)
        enablePersonalization = prefs.getBoolean("enable_personalization", true)
        personalizationDecayRate = prefs.getFloat("personalization_decay_rate", 0.995f)
    }

    fun saveSettings() {
        prefs.edit().apply {
            // Trajectory preprocessing
            putInt("trajectory_min_points", trajectoryMinPoints)
            putInt("trajectory_max_points", trajectoryMaxPoints)
            putInt("trajectory_smoothing_window", trajectorySmoothingWindow)
            putFloat("trajectory_resampling_distance", trajectoryResamplingDistance)

            // Neural feature extraction
            putFloat("feature_normalization_range", featureNormalizationRange)
            putInt("velocity_window_size", velocityWindowSize)
            putInt("curvature_window_size", curvatureWindowSize)
            putInt("feature_interpolation_points", featureInterpolationPoints)

            // Gesture validation
            putFloat("min_gesture_length", minGestureLength)
            putFloat("max_gesture_length", maxGestureLength)
            putLong("min_gesture_duration", minGestureDuration)
            putLong("max_gesture_duration", maxGestureDuration)

            // Neural inference optimization
            putBoolean("batch_inference_enabled", batchInferenceEnabled)
            putInt("max_batch_size", maxBatchSize)
            putInt("tensor_cache_size", tensorCacheSize)
            putInt("memory_optimization_level", memoryOptimizationLevel)

            // Prediction filtering
            putFloat("min_prediction_confidence", minPredictionConfidence)
            putInt("max_predictions", maxPredictions)
            putBoolean("duplicate_filter_enabled", duplicateFilterEnabled)
            putFloat("length_penalty_factor", lengthPenaltyFactor)

            // Performance tuning
            putBoolean("enable_parallel_processing", enableParallelProcessing)
            putInt("worker_thread_count", workerThreadCount)
            putLong("prediction_timeout_ms", predictionTimeoutMs)
            putBoolean("enable_debug_logging", enableDebugLogging)

            // Calibration and adaptation
            putFloat("adaptation_learning_rate", adaptationLearningRate)
            putFloat("calibration_data_weight", calibrationDataWeight)
            putBoolean("enable_personalization", enablePersonalization)
            putFloat("personalization_decay_rate", personalizationDecayRate)

            apply()
        }
    }

    // Setters removed - using property custom setters instead

    /**
     * Reset all settings to optimized defaults for neural prediction
     */
    fun resetToDefaults() {
        // Trajectory preprocessing - optimized for neural models
        trajectoryMinPoints = 8
        trajectoryMaxPoints = 200
        trajectorySmoothingWindow = 3
        trajectoryResamplingDistance = 5.0f

        // Neural feature extraction - optimized for transformer architecture
        featureNormalizationRange = 1.0f
        velocityWindowSize = 5
        curvatureWindowSize = 3
        featureInterpolationPoints = 64

        // Gesture validation - balanced for accuracy and usability
        minGestureLength = 20.0f
        maxGestureLength = 2000.0f
        minGestureDuration = 50L
        maxGestureDuration = 3000L

        // Neural inference optimization - optimized for mobile performance
        batchInferenceEnabled = true
        maxBatchSize = 4
        tensorCacheSize = 8
        memoryOptimizationLevel = 2

        // Prediction filtering - balanced for quality and performance
        minPredictionConfidence = 0.05f
        maxPredictions = 8
        duplicateFilterEnabled = true
        lengthPenaltyFactor = 0.1f

        // Performance tuning - optimized for responsiveness
        enableParallelProcessing = true
        workerThreadCount = 2
        predictionTimeoutMs = 200L
        enableDebugLogging = false

        // Calibration and adaptation - conservative personalization
        adaptationLearningRate = 0.01f
        calibrationDataWeight = 0.3f
        enablePersonalization = true
        personalizationDecayRate = 0.995f

        saveSettings()
    }

    /**
     * Get performance preset configurations
     */
    fun applyPerformancePreset(preset: PerformancePreset) {
        when (preset) {
            PerformancePreset.ACCURACY -> {
                trajectoryMaxPoints = 300
                featureInterpolationPoints = 128
                maxBatchSize = 1
                memoryOptimizationLevel = 0
                maxPredictions = 12
                predictionTimeoutMs = 500L
            }
            PerformancePreset.BALANCED -> {
                trajectoryMaxPoints = 200
                featureInterpolationPoints = 64
                maxBatchSize = 4
                memoryOptimizationLevel = 2
                maxPredictions = 8
                predictionTimeoutMs = 200L
            }
            PerformancePreset.SPEED -> {
                trajectoryMaxPoints = 100
                featureInterpolationPoints = 32
                maxBatchSize = 8
                memoryOptimizationLevel = 3
                maxPredictions = 5
                predictionTimeoutMs = 100L
            }
        }
        saveSettings()
    }

    enum class PerformancePreset {
        ACCURACY,   // Best quality, slower performance
        BALANCED,   // Good balance of quality and speed
        SPEED       // Fastest performance, reduced quality
    }
}