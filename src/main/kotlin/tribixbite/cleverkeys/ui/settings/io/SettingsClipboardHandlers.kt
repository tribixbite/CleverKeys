package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.buildClipboardImportResultMessage
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
    backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
    val appContext = applicationContext
    val manager = backupRestoreManager
    runSettingsFileOperation(
        work = {
            val result = manager.exportClipboardHistory(uri)
            val fileName = appContext.documentDisplayName(uri)
            followUp {
                val parts = buildList {
                    add(
                        resources.getQuantityString(
                            R.plurals.clipboard_backup_exported_entries, result.exportedCount, result.exportedCount
                        )
                    )
                    add(getString(R.string.common_file_name, fileName))
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
            }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Clipboard export failed", e)
            followUp {
                showIoFailure(
                    R.string.clipboard_backup_export_failed_title, R.string.clipboard_backup_export_failed, e
                )
            }
        },
    )
}

/**
 * The failure follow-up shared by the clipboard imports: a passphrase prompt for an encrypted
 * file the stored passphrase does not open ([retry] re-runs it), the import's failure dialog
 * otherwise.
 */
private fun clipboardImportFailure(
    e: Exception,
    retryPassphrase: CharArray?,
    retry: tribixbite.cleverkeys.PendingPassphraseImport,
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    logMessage: String,
): SettingsFollowUp =
    if (e is tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
        followUp { promptForPassphrase(e, retryPassphrase, titleRes, bodyRes, retry) }
    } else {
        android.util.Log.e(SettingsActivity.TAG, logMessage, e)
        followUp { showIoFailure(titleRes, bodyRes, e) }
    }

internal fun SettingsActivity.performClipboardImport(uri: Uri, retryPassphrase: CharArray? = null) {
    if (retryPassphrase == null) primeImport()
    val manager = backupRestoreManager
    runSettingsFileOperation(
        work = {
            val result = manager.importClipboardHistory(uri)
            followUp {
                showIoResult(
                    getString(R.string.clipboard_backup_import_success_title),
                    buildClipboardImportResultMessage(result, withMedia = false, text = ResourcesResultText(resources)),
                )
            }
        },
        onFailure = { e ->
            clipboardImportFailure(
                e, retryPassphrase,
                tribixbite.cleverkeys.PendingPassphraseImport(tribixbite.cleverkeys.EncryptedImportKind.CLIPBOARD, uri),
                R.string.clipboard_backup_import_failed_title, R.string.clipboard_backup_import_failed,
                "Clipboard import failed",
            )
        },
    )
}

internal fun SettingsActivity.performClipboardZipExport(uri: Uri, plaintextOptOut: Boolean = false) {
    backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
    val appContext = applicationContext
    val manager = backupRestoreManager
    runSettingsFileOperation(
        work = {
            val result = manager.exportClipboardHistoryZip(uri)
            val fileName = appContext.documentDisplayName(uri)
            followUp {
                val message = buildString {
                    appendLine(getString(R.string.clipboard_backup_zip_saved))
                    appendLine()
                    appendLine(getString(R.string.common_file_name, fileName))
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
            }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Clipboard ZIP export failed", e)
            followUp {
                showIoFailure(
                    R.string.clipboard_backup_zip_export_failed_title,
                    R.string.clipboard_backup_zip_export_failed,
                    e,
                )
            }
        },
    )
}

internal fun SettingsActivity.performClipboardZipImport(uri: Uri, retryPassphrase: CharArray? = null) {
    if (retryPassphrase == null) primeImport()
    val manager = backupRestoreManager
    runSettingsFileOperation(
        work = {
            val result = manager.importClipboardHistoryZip(uri)
            followUp {
                showIoResult(
                    getString(R.string.clipboard_backup_zip_import_success_title),
                    buildClipboardImportResultMessage(result, withMedia = true, text = ResourcesResultText(resources)),
                )
            }
        },
        onFailure = { e ->
            clipboardImportFailure(
                e, retryPassphrase,
                tribixbite.cleverkeys.PendingPassphraseImport(tribixbite.cleverkeys.EncryptedImportKind.CLIPBOARD_ZIP, uri),
                R.string.clipboard_backup_zip_import_failed_title, R.string.clipboard_backup_zip_import_failed,
                "Clipboard ZIP import failed",
            )
        },
    )
}
