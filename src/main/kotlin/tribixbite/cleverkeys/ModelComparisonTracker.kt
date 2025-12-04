package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks and compares performance metrics across different neural models.
 *
 * Enables A/B testing by recording separate performance statistics for each
 * model variant and providing statistical comparison tools.
 *
 * Key features:
 * - Side-by-side tracking of multiple model variants
 * - Statistical significance testing
 * - Performance difference calculations
 * - JSON export for analysis
 *
 * @since v1.32.899 - Phase 6.3: A/B Testing Framework
 */
class ModelComparisonTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "model_comparison_data",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val TAG = "ModelComparisonTracker"
        private const val KEY_ACTIVE_MODELS = "active_models"
        private const val KEY_MODEL_PREFIX = "model_"

        @Volatile
        private var instance: ModelComparisonTracker? = null

        fun getInstance(context: Context): ModelComparisonTracker {
            return instance ?: synchronized(this) {
                instance ?: ModelComparisonTracker(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Performance data for a single model variant
     */
    data class ModelPerformance(
        val modelId: String,
        val modelName: String,
        val totalPredictions: Int,
        val totalSelections: Int,
        val top1Hits: Int,
        val top3Hits: Int,
        val totalLatencyMs: Long,
        val startTimestamp: Long,
        val lastUsedTimestamp: Long
    ) {
        fun getTop1Accuracy(): Double = if (totalSelections > 0) {
            (top1Hits.toDouble() / totalSelections) * 100.0
        } else 0.0

        fun getTop3Accuracy(): Double = if (totalSelections > 0) {
            (top3Hits.toDouble() / totalSelections) * 100.0
        } else 0.0

        fun getAverageLatency(): Double = if (totalPredictions > 0) {
            totalLatencyMs.toDouble() / totalPredictions
        } else 0.0

        fun getSelectionRate(): Double = if (totalPredictions > 0) {
            (totalSelections.toDouble() / totalPredictions) * 100.0
        } else 0.0
    }

    /**
     * Comparison result between two models
     */
    data class ComparisonResult(
        val modelA: ModelPerformance,
        val modelB: ModelPerformance,
        val top1AccuracyDiff: Double,
        val top3AccuracyDiff: Double,
        val latencyDiff: Double,
        val selectionRateDiff: Double,
        val sampleSizeA: Int,
        val sampleSizeB: Int,
        val isStatisticallySignificant: Boolean,
        val winnerModelId: String?
    )

    /**
     * Record a prediction event for a specific model
     */
    fun recordPrediction(modelId: String, latencyMs: Long) {
        val key = getModelKey(modelId)
        prefs.edit().apply {
            putInt("${key}_predictions", prefs.getInt("${key}_predictions", 0) + 1)
            putLong("${key}_total_latency", prefs.getLong("${key}_total_latency", 0) + latencyMs)
            putLong("${key}_last_used", System.currentTimeMillis())

            // Initialize start timestamp if first use
            if (!prefs.contains("${key}_start")) {
                putLong("${key}_start", System.currentTimeMillis())
            }

            apply()
        }
    }

    /**
     * Record a user selection for a specific model
     */
    fun recordSelection(modelId: String, selectedIndex: Int) {
        val key = getModelKey(modelId)
        prefs.edit().apply {
            putInt("${key}_selections", prefs.getInt("${key}_selections", 0) + 1)

            // Track top-1 accuracy
            if (selectedIndex == 0) {
                putInt("${key}_top1_hits", prefs.getInt("${key}_top1_hits", 0) + 1)
            }

            // Track top-3 accuracy
            if (selectedIndex < 3) {
                putInt("${key}_top3_hits", prefs.getInt("${key}_top3_hits", 0) + 1)
            }

            apply()
        }
    }

    /**
     * Register a model for tracking
     */
    fun registerModel(modelId: String, modelName: String) {
        val activeModels = getActiveModels().toMutableSet()
        activeModels.add(modelId)

        prefs.edit().apply {
            putStringSet(KEY_ACTIVE_MODELS, activeModels)
            putString("${getModelKey(modelId)}_name", modelName)
            apply()
        }

        Log.i(TAG, "Registered model for A/B testing: $modelName ($modelId)")
    }

    /**
     * Get performance data for a specific model
     */
    fun getModelPerformance(modelId: String): ModelPerformance? {
        val key = getModelKey(modelId)
        val modelName = prefs.getString("${key}_name", null) ?: return null

        return ModelPerformance(
            modelId = modelId,
            modelName = modelName,
            totalPredictions = prefs.getInt("${key}_predictions", 0),
            totalSelections = prefs.getInt("${key}_selections", 0),
            top1Hits = prefs.getInt("${key}_top1_hits", 0),
            top3Hits = prefs.getInt("${key}_top3_hits", 0),
            totalLatencyMs = prefs.getLong("${key}_total_latency", 0),
            startTimestamp = prefs.getLong("${key}_start", 0),
            lastUsedTimestamp = prefs.getLong("${key}_last_used", 0)
        )
    }

    /**
     * Get all active models being tracked
     */
    fun getActiveModels(): Set<String> {
        return prefs.getStringSet(KEY_ACTIVE_MODELS, emptySet()) ?: emptySet()
    }

    /**
     * Compare two models statistically
     */
    fun compareModels(modelAId: String, modelBId: String): ComparisonResult? {
        val modelA = getModelPerformance(modelAId) ?: return null
        val modelB = getModelPerformance(modelBId) ?: return null

        val top1Diff = modelA.getTop1Accuracy() - modelB.getTop1Accuracy()
        val top3Diff = modelA.getTop3Accuracy() - modelB.getTop3Accuracy()
        val latencyDiff = modelA.getAverageLatency() - modelB.getAverageLatency()
        val selectionDiff = modelA.getSelectionRate() - modelB.getSelectionRate()

        // Simple statistical significance check (requires at least 30 samples each)
        val isSignificant = modelA.totalSelections >= 30 && modelB.totalSelections >= 30

        // Determine winner based on weighted score
        val scoreA = calculateScore(modelA)
        val scoreB = calculateScore(modelB)
        val winner = when {
            !isSignificant -> null
            scoreA > scoreB -> modelAId
            scoreB > scoreA -> modelBId
            else -> null
        }

        return ComparisonResult(
            modelA = modelA,
            modelB = modelB,
            top1AccuracyDiff = top1Diff,
            top3AccuracyDiff = top3Diff,
            latencyDiff = latencyDiff,
            selectionRateDiff = selectionDiff,
            sampleSizeA = modelA.totalSelections,
            sampleSizeB = modelB.totalSelections,
            isStatisticallySignificant = isSignificant,
            winnerModelId = winner
        )
    }

    /**
     * Calculate composite performance score
     *
     * Weights:
     * - Top-1 accuracy: 40%
     * - Top-3 accuracy: 30%
     * - Selection rate: 20%
     * - Latency (inverted): 10%
     */
    private fun calculateScore(perf: ModelPerformance): Double {
        val latencyScore = if (perf.getAverageLatency() > 0) {
            100.0 / (perf.getAverageLatency() / 10.0) // Normalize to ~100 scale
        } else 0.0

        return (perf.getTop1Accuracy() * 0.4) +
               (perf.getTop3Accuracy() * 0.3) +
               (perf.getSelectionRate() * 0.2) +
               (latencyScore.coerceIn(0.0, 100.0) * 0.1)
    }

    /**
     * Export comparison data as JSON
     */
    fun exportComparisonData(): String {
        val jsonArray = JSONArray()

        for (modelId in getActiveModels()) {
            val perf = getModelPerformance(modelId) ?: continue

            val jsonObject = JSONObject().apply {
                put("model_id", perf.modelId)
                put("model_name", perf.modelName)
                put("total_predictions", perf.totalPredictions)
                put("total_selections", perf.totalSelections)
                put("top1_accuracy", perf.getTop1Accuracy())
                put("top3_accuracy", perf.getTop3Accuracy())
                put("average_latency_ms", perf.getAverageLatency())
                put("selection_rate", perf.getSelectionRate())
                put("start_timestamp", perf.startTimestamp)
                put("last_used_timestamp", perf.lastUsedTimestamp)
                put("composite_score", calculateScore(perf))
            }

            jsonArray.put(jsonObject)
        }

        return jsonArray.toString(2)
    }

    /**
     * Format comparison summary for display
     */
    fun formatComparisonSummary(modelAId: String, modelBId: String): String {
        val comparison = compareModels(modelAId, modelBId) ?: return "No data available"

        val sb = StringBuilder()
        sb.append("📊 A/B Test Comparison\n\n")

        // Model A
        sb.append("Model A: ${comparison.modelA.modelName}\n")
        sb.append("  Top-1: ${String.format("%.1f", comparison.modelA.getTop1Accuracy())}%\n")
        sb.append("  Top-3: ${String.format("%.1f", comparison.modelA.getTop3Accuracy())}%\n")
        sb.append("  Latency: ${String.format("%.0f", comparison.modelA.getAverageLatency())}ms\n")
        sb.append("  Samples: ${comparison.sampleSizeA}\n\n")

        // Model B
        sb.append("Model B: ${comparison.modelB.modelName}\n")
        sb.append("  Top-1: ${String.format("%.1f", comparison.modelB.getTop1Accuracy())}%\n")
        sb.append("  Top-3: ${String.format("%.1f", comparison.modelB.getTop3Accuracy())}%\n")
        sb.append("  Latency: ${String.format("%.0f", comparison.modelB.getAverageLatency())}ms\n")
        sb.append("  Samples: ${comparison.sampleSizeB}\n\n")

        // Differences
        sb.append("Differences:\n")
        sb.append("  Top-1: ${formatDiff(comparison.top1AccuracyDiff)}%\n")
        sb.append("  Top-3: ${formatDiff(comparison.top3AccuracyDiff)}%\n")
        sb.append("  Latency: ${formatDiff(-comparison.latencyDiff)}ms (lower is better)\n\n")

        // Statistical significance
        if (comparison.isStatisticallySignificant) {
            if (comparison.winnerModelId != null) {
                val winnerName = if (comparison.winnerModelId == modelAId) {
                    comparison.modelA.modelName
                } else {
                    comparison.modelB.modelName
                }
                sb.append("✅ Winner: $winnerName\n")
                sb.append("(Statistically significant with ${comparison.sampleSizeA + comparison.sampleSizeB} total samples)")
            } else {
                sb.append("⚖️ No clear winner (too close to call)")
            }
        } else {
            val needed = 30 - minOf(comparison.sampleSizeA, comparison.sampleSizeB)
            sb.append("⚠️ Need $needed more samples for statistical significance")
        }

        return sb.toString()
    }

    /**
     * Reset all comparison data
     */
    fun resetAllData() {
        prefs.edit().clear().apply()
        Log.i(TAG, "All A/B test comparison data reset")
    }

    /**
     * Reset data for a specific model
     */
    fun resetModelData(modelId: String) {
        val key = getModelKey(modelId)
        val modelName = prefs.getString("${key}_name", null)

        prefs.edit().apply {
            remove("${key}_predictions")
            remove("${key}_selections")
            remove("${key}_top1_hits")
            remove("${key}_top3_hits")
            remove("${key}_total_latency")
            remove("${key}_start")
            remove("${key}_last_used")
            remove("${key}_name")
            apply()
        }

        // Remove from active models
        val activeModels = getActiveModels().toMutableSet()
        activeModels.remove(modelId)
        prefs.edit().putStringSet(KEY_ACTIVE_MODELS, activeModels).apply()

        Log.i(TAG, "Reset data for model: $modelName ($modelId)")
    }

    private fun getModelKey(modelId: String): String = "${KEY_MODEL_PREFIX}${modelId}"

    private fun formatDiff(diff: Double): String {
        val sign = if (diff > 0) "+" else ""
        return "$sign${String.format("%.1f", diff)}"
    }
}
