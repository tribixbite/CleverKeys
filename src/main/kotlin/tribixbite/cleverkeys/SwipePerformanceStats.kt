package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlin.math.roundToInt

/**
 * Tracks and persists selection statistics for swipe prediction.
 *
 * Metrics tracked:
 * - Top-1 accuracy (user selected first suggestion)
 * - Top-3 accuracy (user selected any of top 3)
 * - Total selections
 *
 * The inference-time and model-load-time WRITERS died with the neural engine
 * (2026-08-18) — nothing measures a per-swipe inference cost any more. The stored
 * fields, their getters and the JSON export keys are retained so an existing
 * install's history and the export schema both stay readable; they simply stop
 * advancing. The human-readable summary no longer displays them.
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
class SwipePerformanceStats(context: Context) {

    // Use Device Encrypted storage for Direct Boot compatibility
    // Performance stats are non-sensitive aggregate metrics
    private val prefs: SharedPreferences = if (Build.VERSION.SDK_INT >= 24) {
        context.createDeviceProtectedStorageContext()
            // Pref FILE name kept as-is (renamed class, same storage): changing it would
            // orphan every existing install's accumulated selection history.
            .getSharedPreferences(STATS_PREFS_FILE, Context.MODE_PRIVATE)
    } else {
        context.getSharedPreferences(STATS_PREFS_FILE, Context.MODE_PRIVATE)
    }

    private val privacyManager = PrivacyManager.getInstance(context)

    companion object {
        /** Legacy pref-file name — see the constructor comment; do NOT rename. */
        private const val STATS_PREFS_FILE = "neural_performance_stats"

        private const val KEY_TOTAL_PREDICTIONS = "total_predictions"
        private const val KEY_TOTAL_INFERENCE_TIME = "total_inference_time_ms"
        private const val KEY_TOP1_SELECTIONS = "top1_selections"
        private const val KEY_TOP3_SELECTIONS = "top3_selections"
        private const val KEY_TOTAL_SELECTIONS = "total_selections"
        private const val KEY_MODEL_LOAD_TIME = "model_load_time_ms"
        private const val KEY_FIRST_STAT_TIME = "first_stat_timestamp"

        @Volatile
        private var instance: SwipePerformanceStats? = null

        fun getInstance(context: Context): SwipePerformanceStats {
            return instance ?: synchronized(this) {
                instance ?: SwipePerformanceStats(context.applicationContext).also {
                    instance = it
                }
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
            appendLine("📊 Swipe Prediction Statistics")
            appendLine()
            appendLine("Usage:")
            appendLine("  Total selections: ${getTotalSelections()}")
            appendLine("  Days tracked: ${getDaysSinceStart()}")
            appendLine()
            appendLine("Accuracy:")
            appendLine("  Top-1: ${getTop1Accuracy()}%")
            appendLine("  Top-3: ${getTop3Accuracy()}%")
        }
    }
}
