package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.loadCurrentSettings
import tribixbite.cleverkeys.BackupRestoreManager
import tribixbite.cleverkeys.backup.BackupExportName
import tribixbite.cleverkeys.backup.BackupExportNaming
import tribixbite.cleverkeys.backup.SettingsImportPlan
import tribixbite.cleverkeys.backup.ShortSwipeImportMode
import tribixbite.cleverkeys.buildSettingsResultMessage

/**
 * Toast for a SAF picker that could not be launched (no DocumentsUI, activity finishing…).
 * Shared by every import/export entry point in `ui/settings/io`.
 */
internal fun SettingsActivity.toastFilePickerFailed(e: Exception) {
    Toast.makeText(
        this, getString(R.string.common_file_picker_failed, e.message.orEmpty()), Toast.LENGTH_SHORT
    ).show()
}

/** Show the shared Backup & Restore result dialog with already-localized text. */
internal fun SettingsActivity.showIoResult(title: String, message: String) {
    backupRestoreViewModel.resultTitle = title
    backupRestoreViewModel.resultMessage = message
    backupRestoreViewModel.showResultDialog = true
}

/**
 * Show a failure result dialog: [bodyRes] is a localized message with one `%1$s` slot, which
 * receives [detail] (an exception or manager message) or "Unknown error" when there is none.
 *
 * TODO(i18n): [detail] comes from BackupRestoreManager / backup.crypto exception messages and
 *  platform exceptions, which are English (or ROM-localized) today; only the wrapper is ours.
 */
internal fun SettingsActivity.showIoFailure(@StringRes titleRes: Int, @StringRes bodyRes: Int, detail: String?) {
    showIoResult(
        getString(titleRes),
        getString(bodyRes, detail ?: getString(R.string.common_unknown_error)),
    )
}

/** Join localized paragraphs with the blank line the result dialogs use between them. */
internal fun paragraphs(vararg parts: String): String = parts.joinToString("\n\n")

/**
 * Stage B (backup encryption): resolve the [BackupRestoreManager.EncryptionPolicy]
 * for an interactive EXPORT. [plaintextOptOut] is set by the "Export unencrypted…"
 * confirm dialog; otherwise UI exports encrypt whenever a passphrase is configured.
 */
internal fun SettingsActivity.exportPolicy(plaintextOptOut: Boolean): BackupRestoreManager.EncryptionPolicy =
    if (plaintextOptOut) BackupRestoreManager.EncryptionPolicy.UI_PLAINTEXT_OPTOUT
    else BackupRestoreManager.EncryptionPolicy.UI_DEFAULT

/**
 * ARC-035: seed the SAF create-document picker with the name and MIME type this export will
 * actually produce, so an encrypted export lands as `<name>.ckenc` — which the troubleshooting
 * wiki has promised since encryption shipped.
 *
 * The prediction must match [BackupRestoreManager]'s own `willEncrypt` exactly: encryption
 * happens when the policy wants it AND a passphrase exists. Both inputs are known here —
 * [SettingsActivity.pendingPlaintextExport] is armed by the "Export unencrypted…" confirm
 * *before* the export button is tapped (it is consumed by the launcher callback, so reading it
 * here does not clear it), and the passphrase check is the same prefs read the Backup & Restore
 * section already performs to decide whether to offer that opt-out at all.
 *
 * A mismatch is cosmetic, never corrupting: the container's `CKENC1` magic — not the extension —
 * is what import sniffs.
 */
internal fun SettingsActivity.exportName(plainName: String, plainMime: String): BackupExportName =
    BackupExportNaming.forExport(
        plainName = plainName,
        plainMime = plainMime,
        willEncrypt = backupPassphraseStore.hasPassphrase() && !pendingPlaintextExport,
    )

/**
 * Prime the manager for an interactive IMPORT: UI_DEFAULT policy + the stored
 * passphrase (any one-shot override is cleared unless a retry sets it).
 */
internal fun SettingsActivity.primeImport() {
    backupRestoreManager.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_DEFAULT
    backupRestoreManager.setImportPassphraseOverride(null)
}

// Inline backup/restore functions - launch SAF file pickers
internal fun SettingsActivity.exportConfiguration() {
    try {
        configExportLauncher.launch(exportName("cleverkeys-config.json", "application/json"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.importConfiguration() {
    try {
        configImportLauncher.launch(arrayOf("application/json", "*/*"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
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
        fullBackupExportLauncher.launch(
            exportName("cleverkeys_full_backup_$date.zip", "application/zip")
        )
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.importFullBackup() {
    try {
        fullBackupImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.performConfigExport(uri: Uri, plaintextOptOut: Boolean = false) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
            val count = withContext(Dispatchers.IO) {
                backupRestoreManager.exportConfig(uri, prefs)
            }
            showIoResult(
                getString(R.string.backup_result_export_success_title),
                paragraphs(
                    getString(R.string.backup_result_settings_exported, count),
                    getString(R.string.common_file_name, uri.lastPathSegment.orEmpty()),
                    getString(R.string.backup_result_config_export_hint),
                ),
            )
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Config export failed", e)
            showIoFailure(
                R.string.backup_result_export_failed_title, R.string.backup_result_config_export_failed, e.message
            )
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
internal fun SettingsActivity.performConfigImport(uri: Uri, retryPassphrase: CharArray? = null) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        if (retryPassphrase == null) primeImport()
        try {
            val plan = withContext(Dispatchers.IO) {
                backupRestoreManager.buildSettingsImportPlan(uri, prefs)
            }
            val nothingToImport = plan.changes.isEmpty() &&
                plan.parseSkippedKeys.isEmpty() &&
                plan.shortSwipeImportSize == 0
            if (nothingToImport) {
                showIoResult(
                    getString(R.string.backup_result_no_changes_title),
                    getString(R.string.backup_result_settings_no_changes),
                )
            } else {
                backupRestoreViewModel.settingsPreviewPlan = plan
            }
        } catch (e: BackupRestoreManager.BackupDecryptException) {
            promptForPassphrase(e, retryPassphrase) { entered ->
                backupRestoreManager.setImportPassphraseOverride(entered)
                performConfigImport(uri, entered)
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Build settings plan failed", e)
            showIoFailure(
                R.string.backup_result_import_failed_title, R.string.backup_result_read_backup_failed, e.message
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

/**
 * Stage B: show the passphrase-prompt dialog for an encrypted import that failed to
 * decrypt. On a WRONG-password retry ([retryPassphrase] non-null) the error text is
 * surfaced. [onEntered] re-runs the specific import with the entered passphrase.
 */
internal fun SettingsActivity.promptForPassphrase(
    e: BackupRestoreManager.BackupDecryptException,
    retryPassphrase: CharArray?,
    onEntered: (CharArray) -> Unit,
) {
    // A failed retry means the entered password was wrong (or the file is corrupt).
    backupRestoreViewModel.passphrasePromptError =
        if (retryPassphrase != null) BackupRestoreManager.WRONG_PASSWORD_OR_CORRUPT else null
    android.util.Log.i(SettingsActivity.TAG, "Prompting for backup passphrase: ${e.message}")
    backupRestoreViewModel.passphrasePromptRetry = { entered ->
        backupRestoreViewModel.dismissPassphrasePrompt()
        onEntered(entered)
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
            showIoResult(
                getString(R.string.backup_result_import_success_title),
                buildSettingsResultMessage(result, ResourcesResultText(resources)),
            )
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Apply settings plan failed", e)
            showIoFailure(
                R.string.backup_result_import_failed_title, R.string.backup_result_apply_settings_failed, e.message
            )
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
internal fun SettingsActivity.performFullBackupExport(uri: Uri, plaintextOptOut: Boolean = false) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.exportFullBackup(uri, prefs)
            }
            if (result.success) {
                val message = buildString {
                    appendLine(getString(R.string.backup_result_full_backup_saved))
                    appendLine()
                    appendLine(getString(R.string.common_file_name, uri.lastPathSegment.orEmpty()))
                    appendLine()
                    appendLine(
                        getString(
                            if (result.configIncluded) R.string.backup_result_full_config_included
                            else R.string.backup_result_full_config_none
                        )
                    )
                    appendLine(getString(R.string.backup_result_full_dictionary_languages, result.dictionaryCount))
                    appendLine(getString(R.string.backup_result_full_clipboard_entries, result.clipboardEntryCount))
                    appendLine(getString(R.string.backup_result_full_media_files, result.mediaFileCount))
                    if (result.totalBytes > 0) {
                        appendLine(getString(R.string.backup_result_full_bytes_streamed, result.totalBytes))
                    }
                    // #156 F2: a plaintext full backup silently drops private clipboard entries.
                    // Warn the user (matching the clipboard-only export paths) so they don't lose
                    // privately-copied entries unknowingly and only discover it after a device wipe.
                    if (result.privateSkipped > 0) {
                        appendLine()
                        appendLine(
                            resources.getQuantityString(
                                R.plurals.backup_result_private_excluded_full_backup,
                                result.privateSkipped, result.privateSkipped,
                            )
                        )
                    }
                    appendLine()
                    append(getString(R.string.backup_result_full_restore_hint))
                }
                showIoResult(getString(R.string.backup_result_full_backup_success_title), message)
            } else {
                showIoFailure(
                    R.string.backup_result_full_backup_failed_title,
                    R.string.backup_result_full_backup_write_failed,
                    result.errorMessage,
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Full backup export failed", e)
            showIoFailure(
                R.string.backup_result_full_backup_failed_title, R.string.backup_result_full_backup_write_failed, e.message
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

internal fun SettingsActivity.performFullBackupImport(uri: Uri, retryPassphrase: CharArray? = null) {
    val _self = this
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        if (retryPassphrase == null) primeImport()
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
                val message = buildString {
                    appendLine(getString(R.string.backup_result_full_import_completed))
                    appendLine()
                    appendLine(getString(R.string.backup_result_full_settings_applied, result.configKeysApplied))
                    appendLine(getString(R.string.backup_result_full_custom_words, result.customWordsImported))
                    appendLine(getString(R.string.backup_result_full_disabled_words, result.disabledWordsImported))
                    appendLine(
                        getString(R.string.backup_result_full_clipboard_imported, result.clipboardEntriesImported)
                    )
                    appendLine(
                        getString(R.string.backup_result_full_clipboard_skipped, result.clipboardEntriesSkipped)
                    )
                    appendLine(getString(R.string.backup_result_media_restored, result.mediaFilesRestored))
                    result.sourceAppVersion?.let {
                        appendLine(getString(R.string.backup_result_source_version, it))
                    }
                    appendLine()
                    append(getString(R.string.backup_result_restart_keyboard_settings))
                }
                showIoResult(getString(R.string.backup_result_full_restored_title), message)
            } else {
                showIoFailure(
                    R.string.backup_result_full_import_failed_title,
                    R.string.backup_result_full_import_failed,
                    result.errorMessage,
                )
            }
        } catch (e: tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
            promptForPassphrase(e, retryPassphrase) { entered ->
                backupRestoreManager.setImportPassphraseOverride(entered)
                performFullBackupImport(uri, entered)
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Full backup import failed", e)
            showIoFailure(
                R.string.backup_result_full_import_failed_title, R.string.backup_result_full_import_failed, e.message
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}
