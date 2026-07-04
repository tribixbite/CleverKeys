package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.clipboard.sanitize.RulesetParser
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig

internal fun SettingsActivity.recomputeCustomRulesStatus() {
    val customFile = SanitizationConfig.customFile(this)
    clipboardCustomRulesStatus = when {
        customFile.exists() -> {
            try {
                val rs = RulesetParser.fromJson(customFile.readText())
                "${rs.providers.size} providers loaded."
            } catch (e: Exception) {
                "Saved file is malformed: ${e.message}"
            }
        }
        clipboardCustomRulesUri != null -> "URI persisted but no copy on disk yet."
        else -> ""
    }
}

/**
 * Notify the running ClipboardHistoryService that the active sanitization
 * ruleset has changed (toggle flip OR custom-rules import). The service
 * invalidates its cached SanitizationConfig so the next clipboard insert
 * sees the fresh state.
 */
internal fun SettingsActivity.notifySanitizationRulesChanged() {
    LocalBroadcastManager.getInstance(this)
        .sendBroadcast(Intent(SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED))
}

/**
 * SAF picker callback — reads the user-selected JSON, validates by parsing,
 * copies into app private dir for resilience against grant revocation,
 * persists the URI, and broadcasts a cache-invalidation signal.
 *
 * Failure modes:
 *   - Stream open fails → status text + Toast, on-disk copy untouched
 *   - JSON malformed    → status text + Toast, on-disk copy untouched
 *   - 0 providers       → status text only ("File parsed but contained 0 providers."),
 *                         on-disk copy untouched (parser accepted but content useless)
 *
 * In every failure case the previous valid file (if any) is retained.
 */
internal fun SettingsActivity.handleCustomRulesPicked(uri: Uri) {
    val _self = this
    lifecycleScope.launch {
        try {
            val json = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("Could not open URI: $uri")
            }

            // Validate by parsing — rejects malformed JSON / unsupported schema.
            val parsed = RulesetParser.fromJson(json)
            if (parsed.providers.size == 0) {
                // Direct write here: recomputeCustomRulesStatus reads on-disk
                // state, but we deliberately did NOT writeText for this branch
                // (parser accepted but content is useless), so the helper has
                // nothing to summarise. This is the one allowed deviation.
                clipboardCustomRulesStatus = "File parsed but contained 0 providers."
                return@launch
            }

            // Copy to app-private dir for resilience against SAF grant revocation.
            // CRITICAL: parent dir doesn't exist by default — must mkdirs() first.
            val customFile = SanitizationConfig.customFile(_self)
            withContext(Dispatchers.IO) {
                customFile.parentFile?.mkdirs()
                customFile.writeText(json)
            }

            // Persist URI for diagnostics + reload.
            saveSetting("clipboard_custom_rules_uri", uri.toString())
            clipboardCustomRulesUri = uri.toString()
            // Single source of truth for status text — same on-disk file
            // produces the same status regardless of code path (picker
            // success vs. activity recreate). In-session filename display
            // dropped for consistency; user sees the filename in the SAF
            // picker UI itself.
            recomputeCustomRulesStatus()

            notifySanitizationRulesChanged()
        } catch (e: Exception) {
            android.util.Log.w(SettingsActivity.TAG, "Custom rules import failed", e)
            clipboardCustomRulesStatus = "Invalid rules file: ${e.message}"
            Toast.makeText(_self, "Invalid rules file", Toast.LENGTH_LONG).show()
        }
    }
}

internal fun SettingsActivity.exportClipboardHistory() {
    try {
        clipboardExportLauncher.launch("cleverkeys-clipboard.json")
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.importClipboardHistory() {
    try {
        clipboardImportLauncher.launch(arrayOf("application/json", "*/*"))
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.exportClipboardZip() {
    try {
        clipboardZipExportLauncher.launch("cleverkeys-clipboard-full.zip")
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.importClipboardZip() {
    try {
        clipboardZipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.performClipboardExport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.exportClipboardHistory(uri)
            }
            backupRestoreViewModel.resultTitle = "Clipboard Export Successful"
            backupRestoreViewModel.resultMessage = "Exported ${result.exportedCount} clipboard entries.\n\n" +
                    "File: ${uri.lastPathSegment}\n\n" +
                    "Includes timestamps, expiry, and pinned/todo status. " +
                    "Media files are NOT included — use Export ZIP for a full backup."
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard export failed", e)
            backupRestoreViewModel.resultTitle = "Clipboard Export Failed"
            backupRestoreViewModel.resultMessage = "Failed to export clipboard history:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performClipboardImport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.importClipboardHistory(uri)
            }
            backupRestoreViewModel.resultTitle = "Clipboard Import Successful"
            backupRestoreViewModel.resultMessage = buildString {
                appendLine("Clipboard import completed.\n")
                appendLine("• Imported: ${result.importedCount} entries")
                appendLine("• Skipped: ${result.skippedCount} duplicates")
                if (result.sourceVersion != "unknown") {
                    appendLine("• Source version: ${result.sourceVersion}")
                }
                appendLine()
                append("Imports merge with existing history without overwriting.")
            }
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard import failed", e)
            backupRestoreViewModel.resultTitle = "Clipboard Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to import clipboard history:\n\n${e.message}\n\n" +
                    "Make sure the file is a valid CleverKeys clipboard backup."
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performClipboardZipExport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.exportClipboardHistoryZip(uri)
            }
            backupRestoreViewModel.resultTitle = "Full Clipboard Export Successful"
            backupRestoreViewModel.resultMessage = "Clipboard backup with media saved.\n\n" +
                    "File: ${uri.lastPathSegment}\n\n" +
                    "• ${result.exportedCount} clipboard entries\n" +
                    "• ${result.mediaFilesIncluded} media files\n\n" +
                    "Restore via Import ZIP to recover all entries including images, videos, and other media."
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard ZIP export failed", e)
            backupRestoreViewModel.resultTitle = "Clipboard ZIP Export Failed"
            backupRestoreViewModel.resultMessage = "Failed to export clipboard history with media:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performClipboardZipImport(uri: Uri) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.importClipboardHistoryZip(uri)
            }
            backupRestoreViewModel.resultTitle = "Full Clipboard Import Successful"
            backupRestoreViewModel.resultMessage = buildString {
                appendLine("Full clipboard import completed.\n")
                appendLine("• Imported: ${result.importedCount} entries")
                appendLine("• Skipped: ${result.skippedCount} duplicates")
                appendLine("• Media files restored: ${result.mediaFilesRestored}")
                if (result.sourceVersion != "unknown") {
                    appendLine("• Source version: ${result.sourceVersion}")
                }
                appendLine()
                append("All media files and thumbnails have been restored.")
            }
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard ZIP import failed", e)
            backupRestoreViewModel.resultTitle = "Clipboard ZIP Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to import clipboard ZIP:\n\n${e.message}\n\n" +
                    "Make sure the file is a valid CleverKeys clipboard ZIP backup."
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}
