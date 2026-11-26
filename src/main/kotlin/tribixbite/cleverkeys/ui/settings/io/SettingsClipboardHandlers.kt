package tribixbite.cleverkeys.ui.settings.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
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
 *
 * A [Context] receiver (any context of this process reaches the same local broadcast manager)
 * so the custom-rules import can send it from its work, with no activity.
 */
internal fun Context.notifySanitizationRulesChanged() {
    LocalBroadcastManager.getInstance(this)
        .sendBroadcast(Intent(SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED))
}

/**
 * SAF picker callback — reads the user-selected JSON, validates by parsing,
 * copies into app private dir for resilience against grant revocation,
 * persists the URI, and broadcasts a cache-invalidation signal.
 *
 * Runs in [SettingsImports.clipboardRules], not this activity's `lifecycleScope`: a rotation
 * cancelled it between its writes (rules file copied, but URI, status and the rules-changed
 * broadcast skipped). Every write and the broadcast happen in the work, so they complete even
 * when the follow-up is dropped (Settings left for good); the follow-up only updates what this
 * screen shows, on whichever Settings activity is in front when the import ends.
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
    val appContext = applicationContext
    // The starting activity's preferences: the work writes the URI there itself (see above).
    val rulesPrefs = prefs
    val started = SettingsImports.clipboardRules.start(null) {
        val followUp = catchingFailures({
            val json = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: run {
                    // The URI goes to logcat only; the exception message is shown in the
                    // status line (clipboard_rules_invalid_detail), so it is localized.
                    android.util.Log.w(SettingsActivity.TAG, "Could not open custom rules URI: $uri")
                    throw IOException(appContext.getString(R.string.clipboard_rules_open_failed))
                }

            // Validate by parsing — rejects malformed JSON / unsupported schema.
            val parsed = RulesetParser.fromJson(json)
            if (parsed.providers.isEmpty()) {
                // Direct write here: recomputeCustomRulesStatus reads on-disk state, but we
                // deliberately did NOT writeText for this branch (parser accepted but content
                // is useless), so the helper has nothing to summarise. The one allowed deviation.
                followUp { clipboardCustomRulesStatus = getString(R.string.clipboard_rules_zero_providers) }
            } else {
                // Copy to app-private dir for resilience against SAF grant revocation.
                // CRITICAL: parent dir doesn't exist by default — must mkdirs() first.
                val customFile = SanitizationConfig.customFile(appContext)
                customFile.parentFile?.mkdirs()
                customFile.writeText(json)

                // Persist URI for diagnostics + reload, here rather than only in the follow-up.
                rulesPrefs.edit().putString("clipboard_custom_rules_uri", uri.toString()).apply()
                appContext.notifySanitizationRulesChanged()

                followUp {
                    // saveSetting re-writes the same value and refreshes the in-memory config.
                    saveSetting("clipboard_custom_rules_uri", uri.toString())
                    clipboardCustomRulesUri = uri.toString()
                    // Single source of truth for status text — same on-disk file produces the
                    // same status regardless of code path (picker success vs. activity
                    // recreate). In-session filename display dropped for consistency; the user
                    // sees the filename in the SAF picker UI itself.
                    recomputeCustomRulesStatus()
                }
            }
        }) { e ->
            android.util.Log.w(SettingsActivity.TAG, "Custom rules import failed", e)
            followUp {
                // TODO(i18n): the parser/IO detail message is English.
                clipboardCustomRulesStatus = getString(R.string.clipboard_rules_invalid_detail, e.message.orEmpty())
                Toast.makeText(this, R.string.clipboard_rules_invalid, Toast.LENGTH_LONG).show()
            }
        }
        ImportTracker.Outcome(null, followUp)
    }
    if (!started) {
        // The SAF picker is modal, so a second pick while one rules file imports (a few KB) is
        // not reachable from the UI; the running import's result follows.
        android.util.Log.w(SettingsActivity.TAG, "Custom rules import already running; $uri not imported")
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
