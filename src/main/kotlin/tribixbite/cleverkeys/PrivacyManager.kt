package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Manages privacy controls and user consent for ML data collection.
 *
 * Provides comprehensive privacy features:
 * - User consent management (opt-in/opt-out)
 * - Data collection preferences
 * - Anonymization controls
 * - Local-only training mode
 * - Data retention policies
 * - Privacy dashboard and reporting
 *
 * Key Features:
 * - Granular consent controls for different data types
 * - Automatic data expiration
 * - Anonymization of sensitive information
 * - Audit trail of privacy decisions
 * - Export controls for user data
 *
 * Privacy Principles:
 * 1. **Consent First**: No data collection without explicit user consent
 * 2. **Transparency**: Clear explanations of what data is collected and why
 * 3. **User Control**: Easy opt-out and data deletion
 * 4. **Data Minimization**: Only collect what's necessary
 * 5. **Local by Default**: Keep data on-device unless user chooses otherwise
 *
 * @since v1.32.902 - Phase 6.5: Privacy Considerations
 */
class PrivacyManager(private val context: Context) {

    // Privacy-specific settings (consent, anonymization, etc.)
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "privacy_settings",
        Context.MODE_PRIVATE
    )

    // Main app preferences (where SettingsActivity stores collection toggles)
    private val mainPrefs: SharedPreferences = DirectBootAwarePreferences.get_shared_preferences(context)

    companion object {
        private const val TAG = "PrivacyManager"

        // Consent keys
        private const val KEY_CONSENT_GIVEN = "consent_given"
        private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp"
        private const val KEY_CONSENT_VERSION = "consent_version"

        // Data collection preferences
        private const val KEY_COLLECT_SWIPE_DATA = "collect_swipe_data"
        private const val KEY_COLLECT_PERFORMANCE_DATA = "collect_performance_data"
        private const val KEY_COLLECT_ERROR_LOGS = "collect_error_logs"

        // Anonymization settings
        private const val KEY_ANONYMIZE_DATA = "anonymize_data"
        private const val KEY_REMOVE_TIMESTAMPS = "remove_timestamps"
        private const val KEY_HASH_DEVICE_ID = "hash_device_id"

        // Training and export controls
        private const val KEY_LOCAL_ONLY_TRAINING = "local_only_training"
        private const val KEY_ALLOW_DATA_EXPORT = "allow_data_export"
        private const val KEY_ALLOW_MODEL_SHARING = "allow_model_sharing"

        // Data retention
        private const val KEY_DATA_RETENTION_DAYS = "data_retention_days"
        private const val KEY_AUTO_DELETE_ENABLED = "auto_delete_enabled"
        private const val KEY_LAST_CLEANUP_TIMESTAMP = "last_cleanup_timestamp"

        // Privacy audit
        private const val KEY_AUDIT_TRAIL = "audit_trail"

        // Defaults
        private const val DEFAULT_RETENTION_DAYS = 90
        private const val CURRENT_CONSENT_VERSION = 1
        private const val MAX_AUDIT_ENTRIES = 50

        @Volatile
        private var instance: PrivacyManager? = null

        fun getInstance(context: Context): PrivacyManager {
            return instance ?: synchronized(this) {
                instance ?: PrivacyManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Privacy consent status
     */
    data class ConsentStatus(
        val hasConsent: Boolean,
        val consentTimestamp: Long,
        val consentVersion: Int,
        val needsUpdate: Boolean
    )

    /**
     * Privacy settings summary
     */
    data class PrivacySettings(
        val collectSwipeData: Boolean,
        val collectPerformanceData: Boolean,
        val collectErrorLogs: Boolean,
        val anonymizeData: Boolean,
        val localOnlyTraining: Boolean,
        val allowDataExport: Boolean,
        val allowModelSharing: Boolean,
        val dataRetentionDays: Int,
        val autoDeleteEnabled: Boolean
    )

    /**
     * Audit trail entry
     */
    data class AuditEntry(
        val timestamp: Long,
        val action: String,
        val description: String
    )

    /**
     * Check if user has given consent for data collection
     */
    fun hasConsent(): Boolean {
        return prefs.getBoolean(KEY_CONSENT_GIVEN, false)
    }

    /**
     * Get detailed consent status
     */
    fun getConsentStatus(): ConsentStatus {
        val hasConsent = prefs.getBoolean(KEY_CONSENT_GIVEN, false)
        val timestamp = prefs.getLong(KEY_CONSENT_TIMESTAMP, 0)
        val version = prefs.getInt(KEY_CONSENT_VERSION, 0)
        val needsUpdate = version < CURRENT_CONSENT_VERSION

        return ConsentStatus(hasConsent, timestamp, version, needsUpdate)
    }

    /**
     * Grant consent for data collection
     */
    fun grantConsent() {
        prefs.edit().apply {
            putBoolean(KEY_CONSENT_GIVEN, true)
            putLong(KEY_CONSENT_TIMESTAMP, System.currentTimeMillis())
            putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)
            apply()
        }

        recordAudit("consent_granted", "User granted data collection consent")
        Log.i(TAG, "User consent granted (version $CURRENT_CONSENT_VERSION)")
    }

    /**
     * Revoke consent and optionally delete collected data
     */
    fun revokeConsent(deleteData: Boolean = true) {
        prefs.edit().apply {
            putBoolean(KEY_CONSENT_GIVEN, false)
            apply()
        }

        recordAudit("consent_revoked", "User revoked data collection consent" +
                   if (deleteData) " and requested data deletion" else "")

        Log.i(TAG, "User consent revoked" + if (deleteData) " (data deletion requested)" else "")
    }

    /**
     * Check if specific data collection type is allowed.
     * Reads from main app preferences where SettingsActivity stores the toggles.
     */
    fun canCollectSwipeData(): Boolean {
        // Read from main prefs using the key that SettingsActivity uses
        return hasConsent() && mainPrefs.getBoolean("privacy_collect_swipe", Defaults.PRIVACY_COLLECT_SWIPE)
    }

    fun canCollectPerformanceData(): Boolean {
        return hasConsent() && mainPrefs.getBoolean("privacy_collect_performance", Defaults.PRIVACY_COLLECT_PERFORMANCE)
    }

    fun canCollectErrorLogs(): Boolean {
        return hasConsent() && mainPrefs.getBoolean("privacy_collect_errors", Defaults.PRIVACY_COLLECT_ERRORS)
    }

    /**
     * Check if data should be anonymized
     */
    fun shouldAnonymizeData(): Boolean {
        return prefs.getBoolean(KEY_ANONYMIZE_DATA, true)
    }

    /**
     * Check if timestamps should be removed from data
     */
    fun shouldRemoveTimestamps(): Boolean {
        return prefs.getBoolean(KEY_REMOVE_TIMESTAMPS, false)
    }

    /**
     * Check if device ID should be hashed
     */
    fun shouldHashDeviceId(): Boolean {
        return prefs.getBoolean(KEY_HASH_DEVICE_ID, true)
    }

    /**
     * Check if training should be local-only
     */
    fun isLocalOnlyTraining(): Boolean {
        return prefs.getBoolean(KEY_LOCAL_ONLY_TRAINING, true)
    }

    /**
     * Check if data export is allowed
     */
    fun isDataExportAllowed(): Boolean {
        return prefs.getBoolean(KEY_ALLOW_DATA_EXPORT, false)
    }

    /**
     * Check if model sharing is allowed
     */
    fun isModelSharingAllowed(): Boolean {
        return prefs.getBoolean(KEY_ALLOW_MODEL_SHARING, false)
    }

    /**
     * Get data retention period in days
     */
    fun getDataRetentionDays(): Int {
        return prefs.getInt(KEY_DATA_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
    }

    /**
     * Check if auto-delete is enabled
     */
    fun isAutoDeleteEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DELETE_ENABLED, true)
    }

    /**
     * Update privacy settings
     */
    fun updateSettings(settings: PrivacySettings) {
        prefs.edit().apply {
            putBoolean(KEY_COLLECT_SWIPE_DATA, settings.collectSwipeData)
            putBoolean(KEY_COLLECT_PERFORMANCE_DATA, settings.collectPerformanceData)
            putBoolean(KEY_COLLECT_ERROR_LOGS, settings.collectErrorLogs)
            putBoolean(KEY_ANONYMIZE_DATA, settings.anonymizeData)
            putBoolean(KEY_LOCAL_ONLY_TRAINING, settings.localOnlyTraining)
            putBoolean(KEY_ALLOW_DATA_EXPORT, settings.allowDataExport)
            putBoolean(KEY_ALLOW_MODEL_SHARING, settings.allowModelSharing)
            putInt(KEY_DATA_RETENTION_DAYS, settings.dataRetentionDays)
            putBoolean(KEY_AUTO_DELETE_ENABLED, settings.autoDeleteEnabled)
            apply()
        }

        recordAudit("settings_updated", "Privacy settings modified")
        Log.i(TAG, "Privacy settings updated")
    }

    /**
     * Get current privacy settings
     */
    fun getSettings(): PrivacySettings {
        return PrivacySettings(
            collectSwipeData = prefs.getBoolean(KEY_COLLECT_SWIPE_DATA, false),
            collectPerformanceData = prefs.getBoolean(KEY_COLLECT_PERFORMANCE_DATA, false),
            collectErrorLogs = prefs.getBoolean(KEY_COLLECT_ERROR_LOGS, false),
            anonymizeData = prefs.getBoolean(KEY_ANONYMIZE_DATA, true),
            localOnlyTraining = prefs.getBoolean(KEY_LOCAL_ONLY_TRAINING, true),
            allowDataExport = prefs.getBoolean(KEY_ALLOW_DATA_EXPORT, false),
            allowModelSharing = prefs.getBoolean(KEY_ALLOW_MODEL_SHARING, false),
            dataRetentionDays = prefs.getInt(KEY_DATA_RETENTION_DAYS, DEFAULT_RETENTION_DAYS),
            autoDeleteEnabled = prefs.getBoolean(KEY_AUTO_DELETE_ENABLED, true)
        )
    }

    /**
     * Set individual privacy preference
     */
    fun setCollectSwipeData(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COLLECT_SWIPE_DATA, enabled).apply()
        recordAudit("swipe_data_collection", if (enabled) "Enabled" else "Disabled")
    }

    fun setCollectPerformanceData(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COLLECT_PERFORMANCE_DATA, enabled).apply()
        recordAudit("performance_data_collection", if (enabled) "Enabled" else "Disabled")
    }

    fun setCollectErrorLogs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COLLECT_ERROR_LOGS, enabled).apply()
        recordAudit("error_log_collection", if (enabled) "Enabled" else "Disabled")
    }

    fun setAnonymizeData(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANONYMIZE_DATA, enabled).apply()
        recordAudit("data_anonymization", if (enabled) "Enabled" else "Disabled")
    }

    fun setLocalOnlyTraining(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_ONLY_TRAINING, enabled).apply()
        recordAudit("local_only_training", if (enabled) "Enabled" else "Disabled")
    }

    fun setAllowDataExport(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_DATA_EXPORT, enabled).apply()
        recordAudit("data_export", if (enabled) "Allowed" else "Disabled")
    }

    fun setAllowModelSharing(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_MODEL_SHARING, enabled).apply()
        recordAudit("model_sharing", if (enabled) "Allowed" else "Disabled")
    }

    fun setDataRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_DATA_RETENTION_DAYS, days).apply()
        recordAudit("data_retention", "Set to $days days")
    }

    fun setAutoDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DELETE_ENABLED, enabled).apply()
        recordAudit("auto_delete", if (enabled) "Enabled" else "Disabled")
    }

    /**
     * Check if data cleanup is needed
     */
    fun shouldPerformCleanup(): Boolean {
        if (!isAutoDeleteEnabled()) return false

        val lastCleanup = prefs.getLong(KEY_LAST_CLEANUP_TIMESTAMP, 0)
        val oneDayMs = 24 * 60 * 60 * 1000L
        val timeSinceCleanup = System.currentTimeMillis() - lastCleanup

        return timeSinceCleanup > oneDayMs
    }

    /**
     * Record that cleanup was performed
     */
    fun recordCleanupPerformed() {
        prefs.edit().putLong(KEY_LAST_CLEANUP_TIMESTAMP, System.currentTimeMillis()).apply()
        recordAudit("data_cleanup", "Automatic data cleanup performed")
    }

    /**
     * Get cutoff timestamp for data retention
     */
    fun getDataRetentionCutoff(): Long {
        val retentionMs = getDataRetentionDays() * 24L * 60 * 60 * 1000
        return System.currentTimeMillis() - retentionMs
    }

    /**
     * Record action in audit trail
     */
    private fun recordAudit(action: String, description: String) {
        try {
            val auditJson = prefs.getString(KEY_AUDIT_TRAIL, "[]") ?: "[]"
            val auditArray = JSONArray(auditJson)

            // Add new entry
            val entry = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("action", action)
                put("description", description)
            }
            auditArray.put(entry)

            // Keep only last MAX_AUDIT_ENTRIES
            val trimmedArray = JSONArray()
            val startIndex = maxOf(0, auditArray.length() - MAX_AUDIT_ENTRIES)
            for (i in startIndex until auditArray.length()) {
                trimmedArray.put(auditArray.getJSONObject(i))
            }

            prefs.edit().putString(KEY_AUDIT_TRAIL, trimmedArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record audit entry", e)
        }
    }

    /**
     * Get audit trail
     */
    fun getAuditTrail(): List<AuditEntry> {
        try {
            val auditJson = prefs.getString(KEY_AUDIT_TRAIL, "[]") ?: "[]"
            val auditArray = JSONArray(auditJson)
            val entries = mutableListOf<AuditEntry>()

            for (i in 0 until auditArray.length()) {
                val obj = auditArray.getJSONObject(i)
                entries.add(
                    AuditEntry(
                        timestamp = obj.getLong("timestamp"),
                        action = obj.getString("action"),
                        description = obj.getString("description")
                    )
                )
            }

            return entries.reversed() // Most recent first
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audit trail", e)
            return emptyList()
        }
    }

    /**
     * Format privacy status for display
     */
    fun formatStatus(): String {
        val consent = getConsentStatus()
        val settings = getSettings()

        val sb = StringBuilder()
        sb.append("🔒 Privacy & Data Control\n\n")

        // Consent status
        sb.append("Consent Status:\n")
        if (consent.hasConsent) {
            sb.append("  ✅ Granted\n")
            sb.append("  Date: ${Date(consent.consentTimestamp)}\n")
            if (consent.needsUpdate) {
                sb.append("  ⚠️ Needs update to v${CURRENT_CONSENT_VERSION}\n")
            }
        } else {
            sb.append("  ❌ Not granted\n")
            sb.append("  Data collection is disabled\n")
        }
        sb.append("\n")

        if (consent.hasConsent) {
            // Data collection
            sb.append("Data Collection:\n")
            sb.append("  Swipe Data: ${if (settings.collectSwipeData) "✅ Enabled" else "❌ Disabled"}\n")
            sb.append("  Performance Data: ${if (settings.collectPerformanceData) "✅ Enabled" else "❌ Disabled"}\n")
            sb.append("  Error Logs: ${if (settings.collectErrorLogs) "✅ Enabled" else "❌ Disabled"}\n")
            sb.append("\n")

            // Privacy settings
            sb.append("Privacy Settings:\n")
            sb.append("  Anonymization: ${if (settings.anonymizeData) "✅ Enabled" else "❌ Disabled"}\n")
            sb.append("  Local-Only Training: ${if (settings.localOnlyTraining) "✅ Enabled" else "❌ Disabled"}\n")
            sb.append("  Data Export: ${if (settings.allowDataExport) "✅ Allowed" else "❌ Disabled"}\n")
            sb.append("  Model Sharing: ${if (settings.allowModelSharing) "✅ Allowed" else "❌ Disabled"}\n")
            sb.append("\n")

            // Data retention
            sb.append("Data Retention:\n")
            sb.append("  Retention Period: ${settings.dataRetentionDays} days\n")
            sb.append("  Auto-Delete: ${if (settings.autoDeleteEnabled) "✅ Enabled" else "❌ Disabled"}\n")
            if (settings.autoDeleteEnabled) {
                val cutoff = getDataRetentionCutoff()
                sb.append("  Data older than ${Date(cutoff)} will be deleted\n")
            }
        }

        return sb.toString()
    }

    /**
     * Format audit trail for display
     */
    fun formatAuditTrail(): String {
        val entries = getAuditTrail()

        if (entries.isEmpty()) {
            return "No privacy actions recorded yet"
        }

        val sb = StringBuilder()
        sb.append("📜 Privacy Audit Trail\n\n")
        sb.append("Recent Actions (last ${entries.size}):\n\n")

        for (entry in entries) {
            val date = Date(entry.timestamp)
            sb.append("${date}\n")
            sb.append("  Action: ${entry.action}\n")
            sb.append("  ${entry.description}\n\n")
        }

        return sb.toString()
    }

    /**
     * Export privacy settings as JSON
     */
    fun exportSettings(): String {
        val consent = getConsentStatus()
        val settings = getSettings()
        val auditTrail = getAuditTrail()

        val json = JSONObject().apply {
            put("consent", JSONObject().apply {
                put("granted", consent.hasConsent)
                put("timestamp", consent.consentTimestamp)
                put("version", consent.consentVersion)
            })

            put("settings", JSONObject().apply {
                put("collect_swipe_data", settings.collectSwipeData)
                put("collect_performance_data", settings.collectPerformanceData)
                put("collect_error_logs", settings.collectErrorLogs)
                put("anonymize_data", settings.anonymizeData)
                put("local_only_training", settings.localOnlyTraining)
                put("allow_data_export", settings.allowDataExport)
                put("allow_model_sharing", settings.allowModelSharing)
                put("data_retention_days", settings.dataRetentionDays)
                put("auto_delete_enabled", settings.autoDeleteEnabled)
            })

            put("audit_trail", JSONArray().apply {
                for (entry in auditTrail) {
                    put(JSONObject().apply {
                        put("timestamp", entry.timestamp)
                        put("action", entry.action)
                        put("description", entry.description)
                    })
                }
            })
        }

        return json.toString(2)
    }

    /**
     * Reset all privacy settings and revoke consent
     */
    fun resetAll() {
        prefs.edit().clear().apply()
        recordAudit("reset_all", "All privacy settings reset")
        Log.i(TAG, "Privacy settings reset")
    }
}
