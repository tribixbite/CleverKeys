package tribixbite.cleverkeys

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PreferenceFragment that loads settings from res/xml/settings.xml
 *
 * This provides the traditional Android settings UI with full feature parity
 * from Unexpected-Keyboard, including:
 * - Two-button update system (Check for Updates + Install Update)
 * - All layout/typing/behavior preferences
 * - Backup and restore functionality
 * - Neural prediction settings
 * - Swipe calibration settings
 */
class SettingsPreferenceFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    // Request codes for external model loading
    private companion object {
        const val TAG = "SettingsPreferenceFragment"
        const val REQUEST_LOAD_ENCODER = 1001
        const val REQUEST_LOAD_DECODER = 1002
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Use settings_compat.xml which is AndroidX compatible
        // The full settings.xml uses custom preference classes that extend
        // deprecated android.preference.* classes which are incompatible with
        // PreferenceFragmentCompat (AndroidX)
        setPreferencesFromResource(R.xml.settings_compat, rootKey)

        // Set up click handlers for action preferences
        setupClickHandlers()

        // Update version info
        updateVersionInfo()
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)

        // Copy preferences to protected storage for direct boot
        context?.let { ctx ->
            preferenceScreen.sharedPreferences?.let { prefs ->
                DirectBootAwarePreferences.copy_preferences_to_protected_storage(ctx, prefs)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Update Config when preferences change
        android.util.Log.d(TAG, "Preference changed: $key")
    }

    private fun setupClickHandlers() {
        // Layout manager
        findPreference<Preference>("layout_manager")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LayoutManagerActivity::class.java))
            true
        }

        // Extra keys configuration
        findPreference<Preference>("extra_keys_config")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ExtraKeysConfigActivity::class.java))
            true
        }

        // Check for Updates button
        findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
            checkForUpdates()
            true
        }

        // Install Update button
        findPreference<Preference>("update_app")?.setOnPreferenceClickListener {
            installUpdateFromDefault()
            true
        }

        // Backup config - opens BackupRestoreActivity
        findPreference<Preference>("backup_config")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupRestoreActivity::class.java))
            true
        }

        // Restore config - opens BackupRestoreActivity
        findPreference<Preference>("restore_config")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupRestoreActivity::class.java))
            true
        }

        // Export custom dictionary - opens BackupRestoreActivity
        findPreference<Preference>("export_custom_dictionary")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupRestoreActivity::class.java))
            true
        }

        // Import custom dictionary - opens BackupRestoreActivity
        findPreference<Preference>("import_custom_dictionary")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupRestoreActivity::class.java))
            true
        }

        // Export clipboard history - opens BackupRestoreActivity
        findPreference<Preference>("export_clipboard_history")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupRestoreActivity::class.java))
            true
        }

        // Import clipboard history - opens BackupRestoreActivity
        findPreference<Preference>("import_clipboard_history")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupRestoreActivity::class.java))
            true
        }

        // Dictionary manager
        findPreference<Preference>("dictionary_manager")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DictionaryManagerActivity::class.java))
            true
        }

        // Swipe calibration
        findPreference<Preference>("swipe_calibration")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SwipeCalibrationActivity::class.java))
            true
        }

        // Neural performance stats
        findPreference<Preference>("neural_performance_stats")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), NeuralSettingsActivity::class.java))
            true
        }

        // Reset swipe corrections
        findPreference<Preference>("reset_swipe_corrections")?.setOnPreferenceClickListener {
            resetSwipeCorrections()
            true
        }

        // ===== A/B Testing Handlers =====
        findPreference<Preference>("ab_test_status")?.setOnPreferenceClickListener {
            showABTestStatus()
            true
        }

        findPreference<Preference>("ab_test_comparison")?.setOnPreferenceClickListener {
            showABTestComparison()
            true
        }

        findPreference<Preference>("ab_test_configure")?.setOnPreferenceClickListener {
            showABTestConfigure()
            true
        }

        findPreference<Preference>("ab_test_export")?.setOnPreferenceClickListener {
            exportABTestData()
            true
        }

        findPreference<Preference>("ab_test_reset")?.setOnPreferenceClickListener {
            resetABTest()
            true
        }

        // ===== Rollback/Version Handlers =====
        findPreference<Preference>("rollback_status")?.setOnPreferenceClickListener {
            showRollbackStatus()
            true
        }

        findPreference<Preference>("rollback_history")?.setOnPreferenceClickListener {
            showRollbackHistory()
            true
        }

        findPreference<Preference>("rollback_manual")?.setOnPreferenceClickListener {
            performManualRollback()
            true
        }

        findPreference<Preference>("rollback_pin_version")?.setOnPreferenceClickListener {
            toggleVersionPin()
            true
        }

        findPreference<Preference>("rollback_export")?.setOnPreferenceClickListener {
            exportRollbackHistory()
            true
        }

        findPreference<Preference>("rollback_reset")?.setOnPreferenceClickListener {
            resetRollbackHistory()
            true
        }

        // ===== Privacy Handlers =====
        findPreference<Preference>("privacy_status")?.setOnPreferenceClickListener {
            showPrivacyStatus()
            true
        }

        findPreference<Preference>("privacy_consent")?.setOnPreferenceClickListener {
            showPrivacyConsentDialog()
            true
        }

        findPreference<Preference>("privacy_delete_now")?.setOnPreferenceClickListener {
            deletePrivacyDataNow()
            true
        }

        findPreference<Preference>("privacy_export")?.setOnPreferenceClickListener {
            exportPrivacyData()
            true
        }

        findPreference<Preference>("privacy_audit")?.setOnPreferenceClickListener {
            showPrivacyAuditTrail()
            true
        }

        // ===== Neural Model Handlers =====
        findPreference<Preference>("neural_load_encoder")?.setOnPreferenceClickListener {
            loadExternalEncoder()
            true
        }

        findPreference<Preference>("neural_load_decoder")?.setOnPreferenceClickListener {
            loadExternalDecoder()
            true
        }

        findPreference<Preference>("neural_model_metadata")?.setOnPreferenceClickListener {
            showNeuralModelMetadata()
            true
        }
    }

    private fun updateVersionInfo() {
        findPreference<Preference>("version_info")?.apply {
            try {
                val ctx = requireContext()
                val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val versionName = packageInfo.versionName ?: "unknown"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                summary = "Version $versionName (build $versionCode)"
            } catch (e: Exception) {
                summary = "Version information unavailable"
            }
        }
    }

    /**
     * Get APK search locations using proper Android storage APIs
     */
    private fun getUpdateLocations(): List<File> {
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val externalStorage = android.os.Environment.getExternalStorageDirectory()
        val appFiles = requireContext().getExternalFilesDir(null)

        return listOf(
            File(externalStorage, "unexpected/debug-kb.apk"),
            File(downloadDir, "cleverkeys-debug.apk"),
            File(downloadDir, "tribixbite.cleverkeys.debug.apk"),
            File(externalStorage, "Download/cleverkeys-debug.apk"),
            appFiles?.let { File(it, "cleverkeys-debug.apk") }
        ).filterNotNull()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val updateLocations = getUpdateLocations()
                val updateApk = withContext(Dispatchers.IO) {
                    updateLocations.firstOrNull { it.exists() && it.canRead() }
                }

                if (updateApk != null) {
                    showUpdateFoundDialog(updateApk)
                } else {
                    val expectedPath = android.os.Environment.getExternalStorageDirectory()
                        .let { File(it, "unexpected/debug-kb.apk").absolutePath }
                    val downloadPath = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ).let { File(it, "cleverkeys-debug.apk").absolutePath }
                    Toast.makeText(
                        requireContext(),
                        "No update found. Place APK in:\n$expectedPath\nor\n$downloadPath",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking for updates", e)
                Toast.makeText(requireContext(), "Error checking for updates: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateFoundDialog(apkFile: File) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Update Found")
            .setMessage("Found: ${apkFile.name}\nSize: ${apkFile.length() / 1024} KB\n\nInstall now?")
            .setPositiveButton("Install") { _, _ ->
                installUpdate(apkFile)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installUpdateFromDefault() {
        val externalStorage = android.os.Environment.getExternalStorageDirectory()
        val defaultPath = File(externalStorage, "unexpected/debug-kb.apk")
        if (defaultPath.exists() && defaultPath.canRead()) {
            installUpdate(defaultPath)
        } else {
            // Try to find any available update using proper storage APIs
            val updateApk = getUpdateLocations().firstOrNull { it.exists() && it.canRead() }
            if (updateApk != null) {
                installUpdate(updateApk)
            } else {
                Toast.makeText(
                    requireContext(),
                    "No update APK found at ${defaultPath.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun installUpdate(apkFile: File) {
        try {
            val ctx = requireContext()
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    apkFile
                )
            } else {
                android.net.Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            startActivity(intent)
            android.util.Log.d(TAG, "Launched installer for: ${apkFile.absolutePath}")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error installing update", e)
            Toast.makeText(requireContext(), "Error installing update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetSwipeCorrections() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reset Swipe Settings")
            .setMessage("Reset all swipe correction settings to defaults?")
            .setPositiveButton("Reset") { _, _ ->
                preferenceScreen.sharedPreferences?.edit()?.apply {
                    // Reset swipe correction settings to defaults
                    putBoolean("swipe_beam_autocorrect_enabled", true)
                    putBoolean("swipe_final_autocorrect_enabled", true)
                    putString("swipe_correction_preset", "balanced")
                    putString("swipe_fuzzy_match_mode", "edit_distance")
                    putInt("autocorrect_max_length_diff", 2)
                    putInt("autocorrect_prefix_length", 2)
                    putInt("autocorrect_max_beam_candidates", 3)
                    putFloat("autocorrect_char_match_threshold", 0.67f)
                    putInt("autocorrect_confidence_min_frequency", 500)
                    putInt("swipe_prediction_source", 60)
                    putFloat("swipe_common_words_boost", 1.3f)
                    putFloat("swipe_top5000_boost", 1.0f)
                    putFloat("swipe_rare_words_penalty", 0.75f)
                    apply()
                }
                Toast.makeText(requireContext(), "Swipe settings reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===== A/B Testing Implementation =====

    private fun showABTestStatus() {
        val manager = ABTestManager.getInstance(requireContext())
        val status = manager.formatTestStatus()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("A/B Test Status")
            .setMessage(status)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showABTestComparison() {
        val manager = ABTestManager.getInstance(requireContext())
        val config = manager.getTestConfig()
        val status = manager.getTestStatus()
        val comparison = buildString {
            appendLine("Test Active: ${status.isActive}")
            appendLine("Days Running: ${status.daysRunning}")
            appendLine("Samples: ${status.samplesCollected}/${status.samplesNeeded}")
            appendLine()
            appendLine("Model A: ${config.modelAName} (${config.trafficSplitA}%)")
            appendLine("Model B: ${config.modelBName} (${100 - config.trafficSplitA}%)")
            appendLine()
            appendLine("Has enough data: ${status.hasEnoughData}")
            appendLine("Test expired: ${status.isExpired}")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Model Comparison")
            .setMessage(comparison)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showABTestConfigure() {
        val manager = ABTestManager.getInstance(requireContext())
        val config = manager.getTestConfig()

        // Show configuration options
        val items = arrayOf(
            "Traffic Split: ${config.trafficSplitA}% / ${100 - config.trafficSplitA}%",
            "Min Samples: ${config.minSamplesRequired}",
            "Duration: ${config.testDurationDays} days"
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("A/B Test Configuration")
            .setItems(items, null)
            .setPositiveButton("Start New Test") { _, _ ->
                manager.configureTest(
                    modelAId = "v2",
                    modelAName = "Model v2",
                    modelBId = "v3",
                    modelBName = "Model v3",
                    trafficSplitA = 50,
                    minSamples = 100
                )
                Toast.makeText(requireContext(), "A/B test started: v2 vs v3", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportABTestData() {
        lifecycleScope.launch {
            try {
                val manager = ABTestManager.getInstance(requireContext())
                val status = manager.formatTestStatus()
                val file = File(requireContext().getExternalFilesDir(null), "ab_test_data.txt")
                withContext(Dispatchers.IO) {
                    file.writeText(status)
                }
                Toast.makeText(requireContext(), "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetABTest() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reset A/B Test")
            .setMessage("This will clear all A/B test data. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                ABTestManager.getInstance(requireContext()).resetTest()
                Toast.makeText(requireContext(), "A/B test data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===== Rollback/Version Implementation =====

    private fun showRollbackStatus() {
        val manager = ModelVersionManager.getInstance(requireContext())
        val status = manager.formatStatus()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Model Version Status")
            .setMessage(status)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showRollbackHistory() {
        val manager = ModelVersionManager.getInstance(requireContext())
        val history = manager.getVersionHistory()
        val message = if (history.isEmpty()) {
            "No version history available"
        } else {
            history.takeLast(10).reversed().joinToString("\n")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Version History")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun performManualRollback() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Manual Rollback")
            .setMessage("Roll back to previous model version? This cannot be undone.")
            .setPositiveButton("Rollback") { _, _ ->
                val manager = ModelVersionManager.getInstance(requireContext())
                val success = manager.rollback()
                if (success) {
                    Toast.makeText(requireContext(), "Rolled back to previous version", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Rollback failed: no previous version", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleVersionPin() {
        val manager = ModelVersionManager.getInstance(requireContext())
        val current = manager.getCurrentVersion()
        if (current?.isPinned == true) {
            manager.unpinVersion()
            Toast.makeText(requireContext(), "Version unpinned", Toast.LENGTH_SHORT).show()
        } else {
            manager.pinCurrentVersion()
            Toast.makeText(requireContext(), "Current version pinned", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportRollbackHistory() {
        lifecycleScope.launch {
            try {
                val manager = ModelVersionManager.getInstance(requireContext())
                val json = manager.exportHistory()
                val file = File(requireContext().getExternalFilesDir(null), "version_history.json")
                withContext(Dispatchers.IO) {
                    file.writeText(json)
                }
                Toast.makeText(requireContext(), "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetRollbackHistory() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reset Version History")
            .setMessage("This will clear all version history. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                ModelVersionManager.getInstance(requireContext()).reset()
                Toast.makeText(requireContext(), "Version history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===== Privacy Implementation =====

    private fun showPrivacyStatus() {
        val manager = PrivacyManager.getInstance(requireContext())
        val status = manager.formatStatus()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Privacy Status")
            .setMessage(status)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPrivacyConsentDialog() {
        val manager = PrivacyManager.getInstance(requireContext())
        val hasConsent = manager.hasConsent()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Data Collection Consent")
            .setMessage(if (hasConsent) {
                "You have granted consent for data collection.\n\nThis includes swipe gesture data for improving predictions. All data stays on your device unless explicitly exported."
            } else {
                "Grant consent to collect anonymous usage data for improving predictions?\n\nData includes:\n- Swipe gesture patterns\n- Prediction accuracy\n- Error logs (optional)\n\nAll data stays on your device."
            })
            .setPositiveButton(if (hasConsent) "Revoke Consent" else "Grant Consent") { _, _ ->
                if (hasConsent) {
                    manager.revokeConsent(deleteData = true)
                    Toast.makeText(requireContext(), "Consent revoked, data deleted", Toast.LENGTH_SHORT).show()
                } else {
                    manager.grantConsent()
                    Toast.makeText(requireContext(), "Consent granted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePrivacyDataNow() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete All Data")
            .setMessage("Permanently delete all collected data? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                PrivacyManager.getInstance(requireContext()).resetAll()
                Toast.makeText(requireContext(), "All collected data deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportPrivacyData() {
        lifecycleScope.launch {
            try {
                val manager = PrivacyManager.getInstance(requireContext())
                if (!manager.isDataExportAllowed()) {
                    Toast.makeText(requireContext(), "Data export is disabled in privacy settings", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val json = manager.exportSettings()
                val file = File(requireContext().getExternalFilesDir(null), "privacy_settings.json")
                withContext(Dispatchers.IO) {
                    file.writeText(json)
                }
                Toast.makeText(requireContext(), "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPrivacyAuditTrail() {
        val manager = PrivacyManager.getInstance(requireContext())
        val audit = manager.formatAuditTrail()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Privacy Audit Trail")
            .setMessage(if (audit.isBlank()) "No audit entries" else audit)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ===== Neural Model Implementation =====

    private fun loadExternalEncoder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
        }
        try {
            startActivityForResult(intent, REQUEST_LOAD_ENCODER)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Cannot open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadExternalDecoder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
        }
        try {
            startActivityForResult(intent, REQUEST_LOAD_DECODER)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Cannot open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNeuralModelMetadata() {
        val config = Config.globalConfig()
        val metadata = buildString {
            appendLine("Model Version: ${config.neural_model_version}")
            appendLine("Quantized: ${config.neural_use_quantized}")
            appendLine("Beam Width: ${config.neural_beam_width}")
            appendLine("Max Length: ${config.neural_max_length}")
            appendLine("Confidence Threshold: ${config.neural_confidence_threshold}")
            appendLine()
            appendLine("Custom Encoder: ${config.neural_custom_encoder_path?.ifBlank { "None" } ?: "None"}")
            appendLine("Custom Decoder: ${config.neural_custom_decoder_path?.ifBlank { "None" } ?: "None"}")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Neural Model Metadata")
            .setMessage(metadata)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) return

        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_LOAD_ENCODER -> {
                preferenceScreen.sharedPreferences?.edit()?.apply {
                    putString("neural_custom_encoder_uri", uri.toString())
                    apply()
                }
                Toast.makeText(requireContext(), "Encoder model selected", Toast.LENGTH_SHORT).show()
            }
            REQUEST_LOAD_DECODER -> {
                preferenceScreen.sharedPreferences?.edit()?.apply {
                    putString("neural_custom_decoder_uri", uri.toString())
                    apply()
                }
                Toast.makeText(requireContext(), "Decoder model selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

}
