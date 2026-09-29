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
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.clipboard.sanitize.RulesetParser
import tribixbite.cleverkeys.clipboard.sanitize.SanitizationConfig
import tribixbite.cleverkeys.ui.settings.saveSetting

internal fun SettingsActivity.recomputeCustomRulesStatus() {
    val customFile = SanitizationConfig.customFile(this)
    clipboardCustomRulesStatus = when {
        customFile.exists() -> {
            try {
                val rs = RulesetParser.fromJson(customFile.readText())
                resources.getQuantityString(
                    R.plurals.clipboard_rules_providers_loaded, rs.providers.size, rs.providers.size
                )
            } catch (e: Exception) {
                // TODO(i18n): the parser's detail message is English.
                getString(R.string.clipboard_rules_saved_malformed, e.message.orEmpty())
            }
        }
        clipboardCustomRulesUri != null -> getString(R.string.clipboard_rules_uri_no_copy)
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
 *   - 0 providers       → status text only (R.string.clipboard_rules_zero_providers),
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
                    ?: run {
                        // The URI goes to logcat only; the exception message is shown in the
                        // status line (clipboard_rules_invalid_detail), so it is localized.
                        android.util.Log.w(SettingsActivity.TAG, "Could not open custom rules URI: $uri")
                        throw IOException(getString(R.string.clipboard_rules_open_failed))
                    }
            }

            // Validate by parsing — rejects malformed JSON / unsupported schema.
            val parsed = RulesetParser.fromJson(json)
            if (parsed.providers.size == 0) {
                // Direct write here: recomputeCustomRulesStatus reads on-disk
                // state, but we deliberately did NOT writeText for this branch
                // (parser accepted but content is useless), so the helper has
                // nothing to summarise. This is the one allowed deviation.
                clipboardCustomRulesStatus = getString(R.string.clipboard_rules_zero_providers)
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
            // TODO(i18n): the parser/IO detail message is English.
            clipboardCustomRulesStatus = getString(R.string.clipboard_rules_invalid_detail, e.message.orEmpty())
            Toast.makeText(_self, R.string.clipboard_rules_invalid, Toast.LENGTH_LONG).show()
        }
    }
}

/** The "• Imported: N entries" / "• Skipped: N duplicates" lines shared by both clipboard imports. */
private fun SettingsActivity.clipboardImportCountLines(imported: Int, skipped: Int): List<String> = listOf(
    resources.getQuantityString(R.plurals.clipboard_backup_imported_entries, imported, imported),
    resources.getQuantityString(R.plurals.clipboard_backup_skipped_duplicates, skipped, skipped),
)

internal fun SettingsActivity.exportClipboardHistory() {
    try {
        clipboardExportLauncher.launch(exportName("cleverkeys-clipboard.json", "application/json"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.importClipboardHistory() {
    try {
        clipboardImportLauncher.launch(arrayOf("application/json", "*/*"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.exportClipboardZip() {
    try {
        clipboardZipExportLauncher.launch(
            exportName("cleverkeys-clipboard-full.zip", "application/zip")
        )
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.importClipboardZip() {
    try {
        clipboardZipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.performClipboardExport(uri: Uri, plaintextOptOut: Boolean = false) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.exportClipboardHistory(uri)
            }
            val parts = buildList {
                add(
                    resources.getQuantityString(
                        R.plurals.clipboard_backup_exported_entries, result.exportedCount, result.exportedCount
                    )
                )
                add(getString(R.string.common_file_name, uri.lastPathSegment.orEmpty()))
                add(getString(R.string.clipboard_backup_json_scope))
                // #156: a plaintext export omits private entries; tell the user how to include them.
                if (result.privateSkipped > 0) {
                    add(
                        resources.getQuantityString(
                            R.plurals.clipboard_backup_private_excluded_export,
                            result.privateSkipped, result.privateSkipped,
                        )
                    )
                }
            }
            showIoResult(
                getString(R.string.clipboard_backup_export_success_title),
                paragraphs(*parts.toTypedArray()),
            )
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard export failed", e)
            showIoFailure(
                R.string.clipboard_backup_export_failed_title, R.string.clipboard_backup_export_failed, e.message
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performClipboardImport(uri: Uri, retryPassphrase: CharArray? = null) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        if (retryPassphrase == null) primeImport()
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.importClipboardHistory(uri)
            }
            val message = buildString {
                appendLine(getString(R.string.clipboard_backup_import_completed))
                appendLine()
                clipboardImportCountLines(result.importedCount, result.skippedCount).forEach { appendLine(it) }
                if (result.sourceVersion != "unknown") {
                    appendLine(getString(R.string.backup_result_source_version, result.sourceVersion))
                }
                appendLine()
                append(getString(R.string.clipboard_backup_import_merge_note))
            }
            showIoResult(getString(R.string.clipboard_backup_import_success_title), message)
        } catch (e: tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
            promptForPassphrase(e, retryPassphrase) { entered ->
                backupRestoreManager.setImportPassphraseOverride(entered)
                performClipboardImport(uri, entered)
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard import failed", e)
            showIoFailure(
                R.string.clipboard_backup_import_failed_title, R.string.clipboard_backup_import_failed, e.message
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performClipboardZipExport(uri: Uri, plaintextOptOut: Boolean = false) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.exportClipboardHistoryZip(uri)
            }
            val message = buildString {
                appendLine(getString(R.string.clipboard_backup_zip_saved))
                appendLine()
                appendLine(getString(R.string.common_file_name, uri.lastPathSegment.orEmpty()))
                appendLine()
                appendLine(
                    resources.getQuantityString(
                        R.plurals.clipboard_backup_zip_entries, result.exportedCount, result.exportedCount
                    )
                )
                appendLine(
                    resources.getQuantityString(
                        R.plurals.clipboard_backup_zip_media_files,
                        result.mediaFilesIncluded, result.mediaFilesIncluded,
                    )
                )
                // #156: a plaintext ZIP omits private entries; tell the user how to include them.
                if (result.privateSkipped > 0) {
                    appendLine()
                    appendLine(
                        resources.getQuantityString(
                            R.plurals.clipboard_backup_zip_private_excluded,
                            result.privateSkipped, result.privateSkipped,
                        )
                    )
                }
                appendLine()
                append(getString(R.string.clipboard_backup_zip_restore_hint))
            }
            showIoResult(getString(R.string.clipboard_backup_zip_export_success_title), message)
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard ZIP export failed", e)
            showIoFailure(
                R.string.clipboard_backup_zip_export_failed_title,
                R.string.clipboard_backup_zip_export_failed,
                e.message,
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performClipboardZipImport(uri: Uri, retryPassphrase: CharArray? = null) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        if (retryPassphrase == null) primeImport()
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.importClipboardHistoryZip(uri)
            }
            val message = buildString {
                appendLine(getString(R.string.clipboard_backup_zip_import_completed))
                appendLine()
                clipboardImportCountLines(result.importedCount, result.skippedCount).forEach { appendLine(it) }
                appendLine(getString(R.string.backup_result_media_restored, result.mediaFilesRestored))
                if (result.sourceVersion != "unknown") {
                    appendLine(getString(R.string.backup_result_source_version, result.sourceVersion))
                }
                appendLine()
                append(getString(R.string.clipboard_backup_zip_media_restored_note))
            }
            showIoResult(getString(R.string.clipboard_backup_zip_import_success_title), message)
        } catch (e: tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
            promptForPassphrase(e, retryPassphrase) { entered ->
                backupRestoreManager.setImportPassphraseOverride(entered)
                performClipboardZipImport(uri, entered)
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Clipboard ZIP import failed", e)
            showIoFailure(
                R.string.clipboard_backup_zip_import_failed_title,
                R.string.clipboard_backup_zip_import_failed,
                e.message,
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}
