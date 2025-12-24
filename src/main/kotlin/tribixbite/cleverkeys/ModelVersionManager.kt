package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages model versions and provides rollback capability.
 *
 * Tracks model loading history, success/failure rates, and enables
 * automatic fallback to previous working versions when errors occur.
 *
 * Key features:
 * - Version history tracking
 * - Success/failure rate monitoring
 * - Automatic rollback on repeated failures
 * - Version pinning (prevent auto-updates)
 * - Compatibility validation
 *
 * @since v1.32.900 - Phase 6.4: Rollback Capability
 */
class ModelVersionManager(private val context: Context) {

    // Use Device Encrypted storage for Direct Boot compatibility
    // Model version info is non-sensitive metadata
    private val prefs: SharedPreferences = if (Build.VERSION.SDK_INT >= 24) {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("model_version_history", Context.MODE_PRIVATE)
    } else {
        context.getSharedPreferences("model_version_history", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "ModelVersionManager"

        // Configuration keys
        private const val KEY_CURRENT_VERSION = "current_version"
        private const val KEY_PREVIOUS_VERSION = "previous_version"
        private const val KEY_VERSION_HISTORY = "version_history"
        private const val KEY_FAILURE_COUNT = "failure_count"
        private const val KEY_SUCCESS_COUNT = "success_count"
        private const val KEY_LAST_ROLLBACK = "last_rollback_timestamp"
        private const val KEY_PINNED_VERSION = "pinned_version"
        private const val KEY_AUTO_ROLLBACK_ENABLED = "auto_rollback_enabled"

        // Rollback thresholds
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MIN_SUCCESS_RATE = 0.5f // 50%
        private const val ROLLBACK_COOLDOWN_MS = 60000L // 1 minute

        @Volatile
        private var instance: ModelVersionManager? = null

        fun getInstance(context: Context): ModelVersionManager {
            return instance ?: synchronized(this) {
                instance ?: ModelVersionManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Model version information
     */
    data class ModelVersion(
        val versionId: String,
        val versionName: String,
        val encoderPath: String,
        val decoderPath: String,
        val loadTimestamp: Long,
        val successCount: Int,
        val failureCount: Int,
        val isPinned: Boolean,
        val isBuiltin: Boolean
    ) {
        fun getSuccessRate(): Float {
            val total = successCount + failureCount
            return if (total > 0) successCount.toFloat() / total else 0f
        }

        fun isHealthy(): Boolean {
            // Consider healthy if success rate > 50% and not too many failures
            return getSuccessRate() >= MIN_SUCCESS_RATE && failureCount < MAX_CONSECUTIVE_FAILURES
        }
    }

    /**
     * Rollback decision result
     */
    data class RollbackDecision(
        val shouldRollback: Boolean,
        val reason: String,
        val targetVersion: String?,
        val currentFailures: Int,
        val isInCooldown: Boolean
    )

    /**
     * Record successful model load
     */
    fun recordSuccess(versionId: String) {
        val currentFailures = prefs.getInt("${versionId}_failures", 0)
        val currentSuccesses = prefs.getInt("${versionId}_successes", 0)

        prefs.edit().apply {
            putInt("${versionId}_successes", currentSuccesses + 1)
            putInt("${versionId}_failures", 0) // Reset failure count on success
            putLong("${versionId}_last_success", System.currentTimeMillis())
            apply()
        }

        Log.d(TAG, "Success recorded for version $versionId (total: ${currentSuccesses + 1})")
    }

    /**
     * Record failed model load
     */
    fun recordFailure(versionId: String, error: String) {
        val currentFailures = prefs.getInt("${versionId}_failures", 0)
        val newFailures = currentFailures + 1

        prefs.edit().apply {
            putInt("${versionId}_failures", newFailures)
            putLong("${versionId}_last_failure", System.currentTimeMillis())
            putString("${versionId}_last_error", error)
            apply()
        }

        Log.w(TAG, "Failure recorded for version $versionId (consecutive: $newFailures): $error")
    }

    /**
     * Register a new model version
     */
    fun registerVersion(
        versionId: String,
        versionName: String,
        encoderPath: String,
        decoderPath: String,
        isBuiltin: Boolean = true
    ) {
        // Save current as previous
        val currentVersion = prefs.getString(KEY_CURRENT_VERSION, null)
        if (currentVersion != null && currentVersion != versionId) {
            prefs.edit().putString(KEY_PREVIOUS_VERSION, currentVersion).apply()
        }

        // Update current version
        prefs.edit().apply {
            putString(KEY_CURRENT_VERSION, versionId)
            putString("${versionId}_name", versionName)
            putString("${versionId}_encoder", encoderPath)
            putString("${versionId}_decoder", decoderPath)
            putBoolean("${versionId}_builtin", isBuiltin)
            putLong("${versionId}_registered", System.currentTimeMillis())
            apply()
        }

        // Add to history
        addToHistory(versionId)

        Log.i(TAG, "Registered model version: $versionName ($versionId)")
    }

    /**
     * Get current active version
     */
    fun getCurrentVersion(): ModelVersion? {
        val versionId = prefs.getString(KEY_CURRENT_VERSION, null) ?: return null
        return getVersion(versionId)
    }

    /**
     * Get previous version (for rollback)
     */
    fun getPreviousVersion(): ModelVersion? {
        val versionId = prefs.getString(KEY_PREVIOUS_VERSION, null) ?: return null
        return getVersion(versionId)
    }

    /**
     * Get version information
     */
    fun getVersion(versionId: String): ModelVersion? {
        val versionName = prefs.getString("${versionId}_name", null) ?: return null
        val encoderPath = prefs.getString("${versionId}_encoder", null) ?: return null
        val decoderPath = prefs.getString("${versionId}_decoder", null) ?: return null

        return ModelVersion(
            versionId = versionId,
            versionName = versionName,
            encoderPath = encoderPath,
            decoderPath = decoderPath,
            loadTimestamp = prefs.getLong("${versionId}_registered", 0),
            successCount = prefs.getInt("${versionId}_successes", 0),
            failureCount = prefs.getInt("${versionId}_failures", 0),
            isPinned = prefs.getString(KEY_PINNED_VERSION, null) == versionId,
            isBuiltin = prefs.getBoolean("${versionId}_builtin", true)
        )
    }

    /**
     * Check if rollback should be performed
     */
    fun shouldRollback(): RollbackDecision {
        val autoRollbackEnabled = prefs.getBoolean(KEY_AUTO_ROLLBACK_ENABLED, true)
        if (!autoRollbackEnabled) {
            return RollbackDecision(
                shouldRollback = false,
                reason = "Auto-rollback disabled",
                targetVersion = null,
                currentFailures = 0,
                isInCooldown = false
            )
        }

        // Check if version is pinned
        val pinnedVersion = prefs.getString(KEY_PINNED_VERSION, null)
        if (pinnedVersion != null) {
            return RollbackDecision(
                shouldRollback = false,
                reason = "Version pinned: $pinnedVersion",
                targetVersion = null,
                currentFailures = 0,
                isInCooldown = false
            )
        }

        // Check cooldown period
        val lastRollback = prefs.getLong(KEY_LAST_ROLLBACK, 0)
        val timeSinceRollback = System.currentTimeMillis() - lastRollback
        if (timeSinceRollback < ROLLBACK_COOLDOWN_MS) {
            return RollbackDecision(
                shouldRollback = false,
                reason = "In cooldown period (${(ROLLBACK_COOLDOWN_MS - timeSinceRollback) / 1000}s remaining)",
                targetVersion = null,
                currentFailures = 0,
                isInCooldown = true
            )
        }

        // Check current version health
        val current = getCurrentVersion()
        if (current == null) {
            return RollbackDecision(
                shouldRollback = false,
                reason = "No current version registered",
                targetVersion = null,
                currentFailures = 0,
                isInCooldown = false
            )
        }

        // Check if we have too many consecutive failures
        if (current.failureCount >= MAX_CONSECUTIVE_FAILURES) {
            val previous = getPreviousVersion()
            if (previous != null && previous.isHealthy()) {
                return RollbackDecision(
                    shouldRollback = true,
                    reason = "Consecutive failures exceeded (${current.failureCount}/${MAX_CONSECUTIVE_FAILURES})",
                    targetVersion = previous.versionId,
                    currentFailures = current.failureCount,
                    isInCooldown = false
                )
            }
        }

        return RollbackDecision(
            shouldRollback = false,
            reason = "Current version healthy (${current.failureCount} failures)",
            targetVersion = null,
            currentFailures = current.failureCount,
            isInCooldown = false
        )
    }

    /**
     * Perform rollback to previous version
     */
    fun rollback(): Boolean {
        val decision = shouldRollback()
        if (!decision.shouldRollback || decision.targetVersion == null) {
            Log.w(TAG, "Rollback not needed: ${decision.reason}")
            return false
        }

        val targetVersion = getVersion(decision.targetVersion) ?: run {
            Log.e(TAG, "Target version not found: ${decision.targetVersion}")
            return false
        }

        // Swap current and previous
        prefs.edit().apply {
            putString(KEY_PREVIOUS_VERSION, prefs.getString(KEY_CURRENT_VERSION, null))
            putString(KEY_CURRENT_VERSION, targetVersion.versionId)
            putLong(KEY_LAST_ROLLBACK, System.currentTimeMillis())
            apply()
        }

        Log.i(TAG, "✅ Rolled back to version: ${targetVersion.versionName} (${targetVersion.versionId})")
        Log.i(TAG, "   Reason: ${decision.reason}")

        return true
    }

    /**
     * Pin current version (prevent rollback)
     */
    fun pinCurrentVersion() {
        val current = getCurrentVersion()
        if (current != null) {
            prefs.edit().putString(KEY_PINNED_VERSION, current.versionId).apply()
            Log.i(TAG, "Version pinned: ${current.versionName}")
        }
    }

    /**
     * Unpin version (allow rollback)
     */
    fun unpinVersion() {
        prefs.edit().remove(KEY_PINNED_VERSION).apply()
        Log.i(TAG, "Version unpinned")
    }

    /**
     * Enable/disable automatic rollback
     */
    fun setAutoRollbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ROLLBACK_ENABLED, enabled).apply()
        Log.i(TAG, "Auto-rollback ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get version history
     */
    fun getVersionHistory(): List<String> {
        val historyJson = prefs.getString(KEY_VERSION_HISTORY, "[]") ?: "[]"
        val history = mutableListOf<String>()

        try {
            val jsonArray = JSONArray(historyJson)
            for (i in 0 until jsonArray.length()) {
                history.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse version history", e)
        }

        return history
    }

    /**
     * Format status for display
     */
    fun formatStatus(): String {
        val current = getCurrentVersion()
        val previous = getPreviousVersion()
        val decision = shouldRollback()

        val sb = StringBuilder()
        sb.append("🔄 Model Version Status\n\n")

        // Current version
        if (current != null) {
            sb.append("Current Version:\n")
            sb.append("  ${current.versionName}\n")
            sb.append("  Successes: ${current.successCount}\n")
            sb.append("  Failures: ${current.failureCount}\n")
            sb.append("  Success Rate: ${String.format("%.1f", current.getSuccessRate() * 100)}%\n")
            sb.append("  Status: ${if (current.isHealthy()) "✅ Healthy" else "⚠️ Unhealthy"}\n")
            if (current.isPinned) {
                sb.append("  📌 PINNED\n")
            }
            sb.append("\n")
        } else {
            sb.append("No current version\n\n")
        }

        // Previous version
        if (previous != null) {
            sb.append("Previous Version:\n")
            sb.append("  ${previous.versionName}\n")
            sb.append("  Success Rate: ${String.format("%.1f", previous.getSuccessRate() * 100)}%\n")
            sb.append("\n")
        }

        // Rollback status
        sb.append("Rollback Status:\n")
        if (decision.shouldRollback) {
            sb.append("  ⚠️ ROLLBACK RECOMMENDED\n")
            sb.append("  Reason: ${decision.reason}\n")
        } else {
            sb.append("  ✅ No rollback needed\n")
            sb.append("  ${decision.reason}\n")
        }

        // Settings
        val autoRollback = prefs.getBoolean(KEY_AUTO_ROLLBACK_ENABLED, true)
        sb.append("\nSettings:\n")
        sb.append("  Auto-rollback: ${if (autoRollback) "Enabled" else "Disabled"}\n")
        sb.append("  Failure threshold: $MAX_CONSECUTIVE_FAILURES\n")

        return sb.toString()
    }

    /**
     * Export version history as JSON
     */
    fun exportHistory(): String {
        val jsonArray = JSONArray()

        for (versionId in getVersionHistory()) {
            val version = getVersion(versionId) ?: continue

            val jsonObject = JSONObject().apply {
                put("version_id", version.versionId)
                put("version_name", version.versionName)
                put("encoder_path", version.encoderPath)
                put("decoder_path", version.decoderPath)
                put("load_timestamp", version.loadTimestamp)
                put("success_count", version.successCount)
                put("failure_count", version.failureCount)
                put("success_rate", version.getSuccessRate())
                put("is_healthy", version.isHealthy())
                put("is_pinned", version.isPinned)
                put("is_builtin", version.isBuiltin)
            }

            jsonArray.put(jsonObject)
        }

        return jsonArray.toString(2)
    }

    /**
     * Reset all version data
     */
    fun reset() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Version history reset")
    }

    private fun addToHistory(versionId: String) {
        val history = getVersionHistory().toMutableList()
        if (!history.contains(versionId)) {
            history.add(0, versionId) // Add to beginning

            // Keep only last 10 versions
            if (history.size > 10) {
                history.subList(10, history.size).clear()
            }

            val jsonArray = JSONArray(history)
            prefs.edit().putString(KEY_VERSION_HISTORY, jsonArray.toString()).apply()
        }
    }
}
