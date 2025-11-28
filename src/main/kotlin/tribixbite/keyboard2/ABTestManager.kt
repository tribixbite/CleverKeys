package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.random.Random

/**
 * Manages A/B testing of neural swipe models.
 *
 * Coordinates model selection, traffic splitting, and test lifecycle management.
 * Integrates with ModelComparisonTracker for performance tracking.
 *
 * Features:
 * - Configurable traffic split (e.g., 50/50, 80/20)
 * - Session-based or per-prediction randomization
 * - Test duration management
 * - Automatic winner selection
 *
 * @since v1.32.899 - Phase 6.3: A/B Testing Framework
 */
class ABTestManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ab_test_config",
        Context.MODE_PRIVATE
    )

    private val comparisonTracker = ModelComparisonTracker.getInstance(context)

    companion object {
        private const val TAG = "ABTestManager"

        // Configuration keys
        private const val KEY_TEST_ENABLED = "test_enabled"
        private const val KEY_MODEL_A_ID = "model_a_id"
        private const val KEY_MODEL_B_ID = "model_b_id"
        private const val KEY_MODEL_A_NAME = "model_a_name"
        private const val KEY_MODEL_B_NAME = "model_b_name"
        private const val KEY_TRAFFIC_SPLIT_A = "traffic_split_a" // Percentage for model A (0-100)
        private const val KEY_SESSION_BASED = "session_based" // True = pick once per session
        private const val KEY_SELECTED_MODEL_SESSION = "selected_model_session"
        private const val KEY_TEST_START_TIME = "test_start_time"
        private const val KEY_TEST_DURATION_DAYS = "test_duration_days"
        private const val KEY_MIN_SAMPLES_REQUIRED = "min_samples_required"
        private const val KEY_AUTO_SELECT_WINNER = "auto_select_winner"

        @Volatile
        private var instance: ABTestManager? = null

        fun getInstance(context: Context): ABTestManager {
            return instance ?: synchronized(this) {
                instance ?: ABTestManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * A/B test configuration
     */
    data class TestConfig(
        val enabled: Boolean,
        val modelAId: String,
        val modelBId: String,
        val modelAName: String,
        val modelBName: String,
        val trafficSplitA: Int, // 0-100 percentage for model A
        val sessionBased: Boolean,
        val testDurationDays: Int,
        val minSamplesRequired: Int,
        val autoSelectWinner: Boolean
    )

    /**
     * Test status information
     */
    data class TestStatus(
        val isActive: Boolean,
        val daysRunning: Int,
        val daysRemaining: Int,
        val samplesCollected: Int,
        val samplesNeeded: Int,
        val currentModelId: String?,
        val hasEnoughData: Boolean,
        val isExpired: Boolean
    )

    /**
     * Configure an A/B test
     */
    fun configureTest(
        modelAId: String,
        modelAName: String,
        modelBId: String,
        modelBName: String,
        trafficSplitA: Int = 50,
        sessionBased: Boolean = true,
        durationDays: Int = 7,
        minSamples: Int = 100,
        autoSelectWinner: Boolean = false
    ) {
        prefs.edit().apply {
            putBoolean(KEY_TEST_ENABLED, true)
            putString(KEY_MODEL_A_ID, modelAId)
            putString(KEY_MODEL_B_ID, modelBId)
            putString(KEY_MODEL_A_NAME, modelAName)
            putString(KEY_MODEL_B_NAME, modelBName)
            putInt(KEY_TRAFFIC_SPLIT_A, trafficSplitA.coerceIn(0, 100))
            putBoolean(KEY_SESSION_BASED, sessionBased)
            putLong(KEY_TEST_START_TIME, System.currentTimeMillis())
            putInt(KEY_TEST_DURATION_DAYS, durationDays)
            putInt(KEY_MIN_SAMPLES_REQUIRED, minSamples)
            putBoolean(KEY_AUTO_SELECT_WINNER, autoSelectWinner)
            apply()
        }

        // Register models with comparison tracker
        comparisonTracker.registerModel(modelAId, modelAName)
        comparisonTracker.registerModel(modelBId, modelBName)

        Log.i(TAG, "A/B test configured: $modelAName vs $modelBName (${trafficSplitA}/${100-trafficSplitA} split, ${durationDays}d)")
    }

    /**
     * Get current test configuration
     */
    fun getTestConfig(): TestConfig {
        return TestConfig(
            enabled = prefs.getBoolean(KEY_TEST_ENABLED, false),
            modelAId = prefs.getString(KEY_MODEL_A_ID, "") ?: "",
            modelBId = prefs.getString(KEY_MODEL_B_ID, "") ?: "",
            modelAName = prefs.getString(KEY_MODEL_A_NAME, "Model A") ?: "Model A",
            modelBName = prefs.getString(KEY_MODEL_B_NAME, "Model B") ?: "Model B",
            trafficSplitA = prefs.getInt(KEY_TRAFFIC_SPLIT_A, 50),
            sessionBased = prefs.getBoolean(KEY_SESSION_BASED, true),
            testDurationDays = prefs.getInt(KEY_TEST_DURATION_DAYS, 7),
            minSamplesRequired = prefs.getInt(KEY_MIN_SAMPLES_REQUIRED, 100),
            autoSelectWinner = prefs.getBoolean(KEY_AUTO_SELECT_WINNER, false)
        )
    }

    /**
     * Get current test status
     */
    fun getTestStatus(): TestStatus {
        val config = getTestConfig()
        val startTime = prefs.getLong(KEY_TEST_START_TIME, 0)
        val now = System.currentTimeMillis()
        val daysRunning = if (startTime > 0) {
            ((now - startTime) / (24 * 60 * 60 * 1000)).toInt()
        } else 0

        val daysRemaining = (config.testDurationDays - daysRunning).coerceAtLeast(0)
        val isExpired = daysRunning >= config.testDurationDays

        // Get sample counts
        val perfA = comparisonTracker.getModelPerformance(config.modelAId)
        val perfB = comparisonTracker.getModelPerformance(config.modelBId)
        val samplesCollected = (perfA?.totalSelections ?: 0) + (perfB?.totalSelections ?: 0)
        val hasEnoughData = samplesCollected >= config.minSamplesRequired

        val currentModel = if (config.sessionBased) {
            prefs.getString(KEY_SELECTED_MODEL_SESSION, null)
        } else null

        return TestStatus(
            isActive = config.enabled && !isExpired,
            daysRunning = daysRunning,
            daysRemaining = daysRemaining,
            samplesCollected = samplesCollected,
            samplesNeeded = config.minSamplesRequired,
            currentModelId = currentModel,
            hasEnoughData = hasEnoughData,
            isExpired = isExpired
        )
    }

    /**
     * Select which model to use for this prediction/session
     *
     * @return Model ID to use, or null if test not active
     */
    fun selectModel(): String? {
        val config = getTestConfig()
        if (!config.enabled) return null

        val status = getTestStatus()
        if (status.isExpired) {
            // Auto-select winner if configured
            if (config.autoSelectWinner && status.hasEnoughData) {
                return selectWinnerAndEndTest()
            }
            return null
        }

        // Session-based: pick once and stick with it
        if (config.sessionBased) {
            val existing = prefs.getString(KEY_SELECTED_MODEL_SESSION, null)
            if (existing != null) {
                return existing
            }
        }

        // Random selection based on traffic split
        val randomValue = Random.nextInt(100)
        val selectedModel = if (randomValue < config.trafficSplitA) {
            config.modelAId
        } else {
            config.modelBId
        }

        // Cache for session if session-based
        if (config.sessionBased) {
            prefs.edit().putString(KEY_SELECTED_MODEL_SESSION, selectedModel).apply()
        }

        return selectedModel
    }

    /**
     * Record prediction for the active model
     */
    fun recordPrediction(modelId: String, latencyMs: Long) {
        if (!getTestConfig().enabled) return
        comparisonTracker.recordPrediction(modelId, latencyMs)
    }

    /**
     * Record selection for the active model
     */
    fun recordSelection(modelId: String, selectedIndex: Int) {
        if (!getTestConfig().enabled) return
        comparisonTracker.recordSelection(modelId, selectedIndex)
    }

    /**
     * End the A/B test and select winner
     *
     * @return ID of winning model, or null if no clear winner
     */
    fun selectWinnerAndEndTest(): String? {
        val config = getTestConfig()
        val comparison = comparisonTracker.compareModels(config.modelAId, config.modelBId)

        val winner = comparison?.winnerModelId

        // Disable test
        prefs.edit().putBoolean(KEY_TEST_ENABLED, false).apply()

        if (winner != null) {
            Log.i(TAG, "A/B test ended. Winner: ${if (winner == config.modelAId) config.modelAName else config.modelBName}")
        } else {
            Log.i(TAG, "A/B test ended. No clear winner.")
        }

        return winner
    }

    /**
     * Stop the current test without selecting a winner
     */
    fun stopTest() {
        prefs.edit().apply {
            putBoolean(KEY_TEST_ENABLED, false)
            remove(KEY_SELECTED_MODEL_SESSION)
            apply()
        }
        Log.i(TAG, "A/B test stopped")
    }

    /**
     * Reset test and clear all data
     */
    fun resetTest() {
        val config = getTestConfig()
        comparisonTracker.resetModelData(config.modelAId)
        comparisonTracker.resetModelData(config.modelBId)
        prefs.edit().clear().apply()
        Log.i(TAG, "A/B test reset - all data cleared")
    }

    /**
     * Clear session selection (for session-based tests)
     */
    fun clearSessionSelection() {
        prefs.edit().remove(KEY_SELECTED_MODEL_SESSION).apply()
    }

    /**
     * Format test status for display
     */
    fun formatTestStatus(): String {
        val config = getTestConfig()
        val status = getTestStatus()

        if (!config.enabled) {
            return "No A/B test currently active"
        }

        val sb = StringBuilder()
        sb.append("🧪 Active A/B Test\n\n")
        sb.append("Models:\n")
        sb.append("  A: ${config.modelAName} (${config.trafficSplitA}%)\n")
        sb.append("  B: ${config.modelBName} (${100 - config.trafficSplitA}%)\n\n")

        sb.append("Progress:\n")
        sb.append("  Days running: ${status.daysRunning}/${config.testDurationDays}\n")
        sb.append("  Samples: ${status.samplesCollected}/${status.samplesNeeded}\n\n")

        if (status.isExpired) {
            sb.append("⚠️ Test expired! ")
            if (status.hasEnoughData) {
                sb.append("Ready for analysis.")
            } else {
                sb.append("Need more samples.")
            }
        } else if (status.hasEnoughData) {
            sb.append("✅ Enough data collected (${status.daysRemaining}d remaining)")
        } else {
            val needed = status.samplesNeeded - status.samplesCollected
            sb.append("📊 Need $needed more samples")
        }

        return sb.toString()
    }
}
