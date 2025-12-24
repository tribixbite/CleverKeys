package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlin.math.roundToInt

/**
 * Tracks and persists performance statistics for neural swipe prediction.
 *
 * Metrics tracked:
 * - Total predictions made
 * - Average inference time
 * - Top-1 accuracy (user selected first suggestion)
 * - Top-3 accuracy (user selected any of top 3)
 * - Model load time
 *
 * Statistics are persisted in SharedPreferences and can be reset.
 *
 * Privacy controls (Phase 6.5):
 * - Respects user consent for performance data collection
 * - Can be disabled via privacy settings
 *
 * @since v1.32.896
 * @since v1.32.902 - Phase 6.5: Privacy considerations integrated
 */
class NeuralPerformanceStats(context: Context) {

    // Use Device Encrypted storage for Direct Boot compatibility
    // Performance stats are non-sensitive aggregate metrics
    private val prefs: SharedPreferences = if (Build.VERSION.SDK_INT >= 24) {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("neural_performance_stats", Context.MODE_PRIVATE)
    } else {
        context.getSharedPreferences("neural_performance_stats", Context.MODE_PRIVATE)
    }

    private val privacyManager = PrivacyManager.getInstance(context)

    companion object {
        private const val KEY_TOTAL_PREDICTIONS = "total_predictions"
        private const val KEY_TOTAL_INFERENCE_TIME = "total_inference_time_ms"
        private const val KEY_TOP1_SELECTIONS = "top1_selections"
        private const val KEY_TOP3_SELECTIONS = "top3_selections"
        private const val KEY_TOTAL_SELECTIONS = "total_selections"
        private const val KEY_MODEL_LOAD_TIME = "model_load_time_ms"
        private const val KEY_FIRST_STAT_TIME = "first_stat_timestamp"

        @Volatile
        private var instance: NeuralPerformanceStats? = null

        fun getInstance(context: Context): NeuralPerformanceStats {
            return instance ?: synchronized(this) {
                instance ?: NeuralPerformanceStats(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Record a prediction inference.
     * Privacy: Checks canCollectPerformanceData() before recording.
     * @param inferenceTimeMs Time taken for neural network inference
     */
    fun recordPrediction(inferenceTimeMs: Long) {
        // Privacy check
        if (!privacyManager.canCollectPerformanceData()) {
            return
        }

        synchronized(this) {
            prefs.edit().apply {
                putLong(KEY_TOTAL_PREDICTIONS, getTotalPredictions() + 1)
                putLong(KEY_TOTAL_INFERENCE_TIME, getTotalInferenceTime() + inferenceTimeMs)
                if (!prefs.contains(KEY_FIRST_STAT_TIME)) {
                    putLong(KEY_FIRST_STAT_TIME, System.currentTimeMillis())
                }
                apply()
            }
        }
    }

    /**
     * Record user selection of a predicted word.
     * Privacy: Checks canCollectPerformanceData() before recording.
     * @param selectedIndex Index of selected word (0 = first, 1 = second, etc.)
     */
    fun recordSelection(selectedIndex: Int) {
        // Privacy check
        if (!privacyManager.canCollectPerformanceData()) {
            return
        }

        synchronized(this) {
            prefs.edit().apply {
                putLong(KEY_TOTAL_SELECTIONS, getTotalSelections() + 1)
                if (selectedIndex == 0) {
                    putLong(KEY_TOP1_SELECTIONS, getTop1Selections() + 1)
                }
                if (selectedIndex < 3) {
                    putLong(KEY_TOP3_SELECTIONS, getTop3Selections() + 1)
                }
                apply()
            }
        }
    }

    /**
     * Record model load time (one-time event).
     */
    fun recordModelLoadTime(loadTimeMs: Long) {
        synchronized(this) {
            prefs.edit().putLong(KEY_MODEL_LOAD_TIME, loadTimeMs).apply()
        }
    }

    // Getters

    fun getTotalPredictions(): Long = prefs.getLong(KEY_TOTAL_PREDICTIONS, 0)

    fun getTotalInferenceTime(): Long = prefs.getLong(KEY_TOTAL_INFERENCE_TIME, 0)

    fun getTop1Selections(): Long = prefs.getLong(KEY_TOP1_SELECTIONS, 0)

    fun getTop3Selections(): Long = prefs.getLong(KEY_TOP3_SELECTIONS, 0)

    fun getTotalSelections(): Long = prefs.getLong(KEY_TOTAL_SELECTIONS, 0)

    fun getModelLoadTime(): Long = prefs.getLong(KEY_MODEL_LOAD_TIME, 0)

    fun getFirstStatTimestamp(): Long = prefs.getLong(KEY_FIRST_STAT_TIME, 0)

    // Computed metrics

    /**
     * @return Average inference time in milliseconds, or 0 if no predictions
     */
    fun getAverageInferenceTime(): Int {
        val total = getTotalPredictions()
        return if (total > 0) {
            (getTotalInferenceTime().toDouble() / total).roundToInt()
        } else {
            0
        }
    }

    /**
     * @return Top-1 accuracy as percentage (0-100), or 0 if no selections
     */
    fun getTop1Accuracy(): Int {
        val total = getTotalSelections()
        return if (total > 0) {
            ((getTop1Selections().toDouble() / total) * 100).roundToInt()
        } else {
            0
        }
    }

    /**
     * @return Top-3 accuracy as percentage (0-100), or 0 if no selections
     */
    fun getTop3Accuracy(): Int {
        val total = getTotalSelections()
        return if (total > 0) {
            ((getTop3Selections().toDouble() / total) * 100).roundToInt()
        } else {
            0
        }
    }

    /**
     * @return Days since first statistic was recorded
     */
    fun getDaysSinceStart(): Int {
        val firstTime = getFirstStatTimestamp()
        return if (firstTime > 0) {
            val daysSince = (System.currentTimeMillis() - firstTime) / (1000 * 60 * 60 * 24)
            daysSince.toInt()
        } else {
            0
        }
    }

    /**
     * Reset all statistics to zero.
     */
    fun reset() {
        synchronized(this) {
            prefs.edit().clear().apply()
        }
    }

    /**
     * Check if any statistics have been recorded.
     */
    fun hasStats(): Boolean {
        return getTotalPredictions() > 0 || getTotalSelections() > 0
    }

    /**
     * Format statistics as human-readable string for display.
     */
    fun formatSummary(): String {
        if (!hasStats()) {
            return "No statistics available yet.\nStart using swipe typing to collect data!"
        }

        return buildString {
            appendLine("📊 Neural Prediction Statistics")
            appendLine()
            appendLine("Usage:")
            appendLine("  Total predictions: ${getTotalPredictions()}")
            appendLine("  Total selections: ${getTotalSelections()}")
            appendLine("  Days tracked: ${getDaysSinceStart()}")
            appendLine()
            appendLine("Performance:")
            appendLine("  Avg inference: ${getAverageInferenceTime()}ms")
            appendLine("  Model load time: ${getModelLoadTime()}ms")
            appendLine()
            appendLine("Accuracy:")
            appendLine("  Top-1: ${getTop1Accuracy()}%")
            appendLine("  Top-3: ${getTop3Accuracy()}%")
        }
    }
}
