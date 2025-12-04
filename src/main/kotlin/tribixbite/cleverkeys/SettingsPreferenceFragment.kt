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

    companion object {
        private const val TAG = "SettingsPreferenceFragment"

        // APK search locations built dynamically using proper Android APIs
        // Note: UPDATE_LOCATIONS is generated at runtime in getUpdateLocations()
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
}
