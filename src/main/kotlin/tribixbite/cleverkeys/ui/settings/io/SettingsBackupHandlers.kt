package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.LangWord
import tribixbite.cleverkeys.backup.SettingsImportPlan
import tribixbite.cleverkeys.backup.ShortSwipeImportMode
import tribixbite.cleverkeys.buildDictResultMessage
import tribixbite.cleverkeys.buildSettingsResultMessage

// Inline backup/restore functions - launch SAF file pickers
internal fun SettingsActivity.exportConfiguration() {
    try {
        configExportLauncher.launch("cleverkeys-config.json")
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.importConfiguration() {
    try {
        configImportLauncher.launch(arrayOf("application/json", "*/*"))
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * GitHub #142: launch SAF picker for the dated full-backup ZIP. The default
 * filename baked into the picker is `cleverkeys_full_backup_<YYYY-MM-DD>.zip`
 * so users get the requested dated-history filesystem layout with zero typing.
 */
internal fun SettingsActivity.exportFullBackup() {
    try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        fullBackupExportLauncher.launch("cleverkeys_full_backup_$date.zip")
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.importFullBackup() {
    try {
        fullBackupImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.performConfigExport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val count = withContext(Dispatchers.IO) {
                backupRestoreManager.exportConfig(uri, prefs)
            }
            backupRestoreViewModel.resultTitle = "Export Successful"
            backupRestoreViewModel.resultMessage = "Settings exported: $count\n\n" +
                    "File: ${uri.lastPathSegment}\n\n" +
                    "Transfer the file to another device or keep it as a backup."
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Config export failed", e)
            backupRestoreViewModel.resultTitle = "Export Failed"
            backupRestoreViewModel.resultMessage = "Failed to export configuration:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

/**
 * Settings import: build a preview plan so the user can deselect keys
 * and choose a short-swipe import mode. If the file matches current
 * settings exactly, jump straight to the "No changes" result dialog.
 */
internal fun SettingsActivity.performConfigImport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val plan = withContext(Dispatchers.IO) {
                backupRestoreManager.buildSettingsImportPlan(uri, prefs)
            }
            val nothingToImport = plan.changes.isEmpty() &&
                plan.parseSkippedKeys.isEmpty() &&
                plan.shortSwipeImportSize == 0
            if (nothingToImport) {
                backupRestoreViewModel.resultTitle = "No changes"
                backupRestoreViewModel.resultMessage = "Backup file matches current settings."
                backupRestoreViewModel.showResultDialog = true
            } else {
                backupRestoreViewModel.settingsPreviewPlan = plan
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Build settings plan failed", e)
            backupRestoreViewModel.resultTitle = "Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to read backup file:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

/**
 * Apply a previously-shown settings preview. Reloads in-memory state via
 * loadCurrentSettings() so the UI reflects imported values immediately,
 * and copies prefs to protected storage for Direct Boot survival.
 */
internal fun SettingsActivity.applyPlannedSettings(
    plan: SettingsImportPlan,
    excludedKeys: Set<String>,
    shortSwipeMode: ShortSwipeImportMode,
) {
    val _self = this
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.applySettingsImportPlan(plan, excludedKeys, shortSwipeMode, prefs)
            }
            DirectBootAwarePreferences.copy_preferences_to_protected_storage(_self, prefs)
            loadCurrentSettings()
            backupRestoreViewModel.resultTitle = "Import Successful"
            backupRestoreViewModel.resultMessage = buildSettingsResultMessage(result)
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Apply settings plan failed", e)
            backupRestoreViewModel.resultTitle = "Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to apply imported settings:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

/**
 * GitHub #142: write a single dated ZIP containing config + dictionaries +
 * clipboard JSON + media. Reuses the shared [BackupRestoreManager] helpers
 * so output stays in lockstep with the per-section exporters.
 */
internal fun SettingsActivity.performFullBackupExport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.exportFullBackup(uri, prefs)
            }
            if (result.success) {
                backupRestoreViewModel.resultTitle = "Full Backup Successful"
                backupRestoreViewModel.resultMessage = buildString {
                    appendLine("Full backup saved.\n")
                    appendLine("File: ${uri.lastPathSegment}")
                    appendLine()
                    appendLine("• Config: ${if (result.configIncluded) "included" else "(none)"}")
                    appendLine("• Dictionary languages: ${result.dictionaryCount}")
                    appendLine("• Clipboard entries: ${result.clipboardEntryCount}")
                    appendLine("• Media files: ${result.mediaFileCount}")
                    if (result.totalBytes > 0) {
                        appendLine("• Bytes streamed: ${result.totalBytes}")
                    }
                    appendLine()
                    append("Restore via Import Full Backup to recover everything.")
                }
            } else {
                backupRestoreViewModel.resultTitle = "Full Backup Failed"
                backupRestoreViewModel.resultMessage =
                    "Failed to write full backup:\n\n${result.errorMessage ?: "Unknown error"}"
            }
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Full backup export failed", e)
            backupRestoreViewModel.resultTitle = "Full Backup Failed"
            backupRestoreViewModel.resultMessage = "Failed to write full backup:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performFullBackupImport(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.importFullBackup(uri, prefs)
            }
            if (result.success) {
                // Refresh in-memory state and copy prefs to protected storage so
                // imported values survive Direct Boot — same housekeeping as the
                // single-file config import.
                DirectBootAwarePreferences.copy_preferences_to_protected_storage(
                    _self, prefs
                )
                loadCurrentSettings()
                LocalBroadcastManager.getInstance(_self)
                    .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
                backupRestoreViewModel.resultTitle = "Full Backup Restored"
                backupRestoreViewModel.resultMessage = buildString {
                    appendLine("Full backup import completed.\n")
                    appendLine("• Settings applied: ${result.configKeysApplied}")
                    appendLine("• Custom words: ${result.customWordsImported}")
                    appendLine("• Disabled words: ${result.disabledWordsImported}")
                    appendLine("• Clipboard imported: ${result.clipboardEntriesImported}")
                    appendLine("• Clipboard skipped: ${result.clipboardEntriesSkipped} (duplicates)")
                    appendLine("• Media files restored: ${result.mediaFilesRestored}")
                    result.sourceAppVersion?.let { appendLine("• Source version: $it") }
                    appendLine()
                    append("Restart the keyboard for settings changes to take effect.")
                }
            } else {
                backupRestoreViewModel.resultTitle = "Full Backup Import Failed"
                backupRestoreViewModel.resultMessage =
                    "Failed to import full backup:\n\n${result.errorMessage ?: "Unknown error"}"
            }
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Full backup import failed", e)
            backupRestoreViewModel.resultTitle = "Full Backup Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to import full backup:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}
