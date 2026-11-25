package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.IoFailureClassifier
import tribixbite.cleverkeys.IoFailureReason
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
import tribixbite.cleverkeys.buildFullBackupResultMessage
import tribixbite.cleverkeys.buildSettingsResultMessage

/**
 * Toast for a SAF picker that could not be launched (no DocumentsUI, activity finishing…).
 * Shared by every import/export entry point in `ui/settings/io`.
 */
internal fun SettingsActivity.toastFilePickerFailed(e: Exception) {
    android.util.Log.w(SettingsActivity.TAG, "File picker could not be launched", e)
    Toast.makeText(
        this, getString(R.string.common_file_picker_failed, ioFailureText(e)), Toast.LENGTH_SHORT
    ).show()
}

/**
 * The localized reason for [error] (see [IoFailureClassifier]) to put in a "…failed: %1$s"
 * message. The exception's own message is English or ROM-localized developer text, so it is never
 * shown; callers log the throwable instead.
 */
internal fun SettingsActivity.ioFailureText(error: Throwable?): String =
    getString(IoFailureClassifier.classify(error).messageRes)

/** Show the shared Backup & Restore result dialog with already-localized text. */
internal fun SettingsActivity.showIoResult(title: String, message: String) {
    backupRestoreViewModel.resultTitle = title
    backupRestoreViewModel.resultMessage = message
    backupRestoreViewModel.showResultDialog = true
}

/**
 * Show a failure result dialog: [bodyRes] is a localized message with one `%1$s` slot, which
 * receives the localized [reason] ("Unknown error" when null). The raw exception text is only
 * logged by the caller: it is English or ROM-localized developer text.
 */
internal fun SettingsActivity.showIoFailure(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    reason: IoFailureReason?,
) {
    showIoResult(
        getString(titleRes),
        getString(bodyRes, getString((reason ?: IoFailureReason.UNKNOWN).messageRes)),
    )
}

/** [showIoFailure] for a caught exception, classified by [IoFailureClassifier]. */
internal fun SettingsActivity.showIoFailure(@StringRes titleRes: Int, @StringRes bodyRes: Int, error: Throwable) {
    showIoFailure(titleRes, bodyRes, IoFailureClassifier.classify(error))
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
    backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
    val appContext = applicationContext
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val count = manager.exportConfig(uri, prefs)
            val fileName = appContext.documentDisplayName(uri)
            followUp {
                showIoResult(
                    getString(R.string.backup_result_export_success_title),
                    paragraphs(
                        getString(R.string.backup_result_settings_exported, count),
                        getString(R.string.common_file_name, fileName),
                        getString(R.string.backup_result_config_export_hint),
                    ),
                )
            }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Config export failed", e)
            followUp {
                showIoFailure(
                    R.string.backup_result_export_failed_title, R.string.backup_result_config_export_failed, e
                )
            }
        },
    )
}

/**
 * Settings import: build a preview plan so the user can deselect keys
 * and choose a short-swipe import mode. If the file matches current
 * settings exactly, jump straight to the "No changes" result dialog.
 */
internal fun SettingsActivity.performConfigImport(uri: Uri, retryPassphrase: CharArray? = null) {
    if (retryPassphrase == null) primeImport()
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val plan = manager.buildSettingsImportPlan(uri, prefs)
            followUp {
                if (plan.hasNothingToImport) {
                    showIoResult(
                        getString(R.string.backup_result_no_changes_title),
                        getString(R.string.backup_result_settings_no_changes),
                    )
                } else {
                    backupRestoreViewModel.settingsPreviewPlan = plan
                }
            }
        },
        onFailure = { e ->
            if (e is BackupRestoreManager.BackupDecryptException) {
                followUp {
                    promptForPassphrase(
                        e, retryPassphrase,
                        R.string.backup_result_import_failed_title, R.string.backup_result_read_backup_failed,
                        tribixbite.cleverkeys.PendingPassphraseImport(tribixbite.cleverkeys.EncryptedImportKind.SETTINGS, uri),
                    )
                }
            } else {
                android.util.Log.e(SettingsActivity.TAG, "Build settings plan failed", e)
                followUp {
                    showIoFailure(
                        R.string.backup_result_import_failed_title, R.string.backup_result_read_backup_failed, e
                    )
                }
            }
        },
    )
}

/**
 * Stage B: show the passphrase-prompt dialog for an encrypted import that failed to
 * decrypt. On a WRONG-password retry ([retryPassphrase] non-null) the error text is
 * surfaced. [onEntered] re-runs the specific import with the entered passphrase.
 *
 * Only a missing or wrong password is worth a prompt. A header from a newer app version, a
 * damaged header or an encrypted backup of another kind cannot be fixed by typing a password,
 * so those show the import's failure dialog ([failedTitleRes]/[failedBodyRes]) instead —
 * previously they re-prompted forever without saying why.
 */
internal fun SettingsActivity.promptForPassphrase(
    e: BackupRestoreManager.BackupDecryptException,
    retryPassphrase: CharArray?,
    @StringRes failedTitleRes: Int,
    @StringRes failedBodyRes: Int,
    retry: tribixbite.cleverkeys.PendingPassphraseImport,
) {
    if (e.reason != IoFailureReason.NO_BACKUP_PASSWORD && e.reason != IoFailureReason.WRONG_PASSWORD_OR_CORRUPT) {
        android.util.Log.w(SettingsActivity.TAG, "Encrypted import refused: ${e.message}", e)
        showIoFailure(failedTitleRes, failedBodyRes, e.reason)
        return
    }
    // A failed retry means the entered password was wrong (or the file is corrupt).
    backupRestoreViewModel.passphrasePromptError =
        if (retryPassphrase != null) getString(IoFailureReason.WRONG_PASSWORD_OR_CORRUPT.messageRes) else null
    android.util.Log.i(SettingsActivity.TAG, "Prompting for backup passphrase: ${e.message}")
    backupRestoreViewModel.pendingPassphraseImport = retry
}

/**
 * Re-run the import a passphrase prompt was shown for, with the entered passphrase — on the
 * CURRENT Activity (the prompt survives rotation as data, review 2026-11-14 M9).
 */
internal fun SettingsActivity.retryEncryptedImport(pending: tribixbite.cleverkeys.PendingPassphraseImport, entered: CharArray) {
    backupRestoreManager.setImportPassphraseOverride(entered)
    when (pending.kind) {
        tribixbite.cleverkeys.EncryptedImportKind.SETTINGS -> performConfigImport(pending.uri, entered)
        tribixbite.cleverkeys.EncryptedImportKind.FULL_BACKUP -> performFullBackupImport(pending.uri, entered)
        tribixbite.cleverkeys.EncryptedImportKind.CLIPBOARD -> performClipboardImport(pending.uri, entered)
        tribixbite.cleverkeys.EncryptedImportKind.CLIPBOARD_ZIP -> performClipboardZipImport(pending.uri, entered)
        tribixbite.cleverkeys.EncryptedImportKind.DICTIONARY -> performDictionaryImport(pending.uri, entered)
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
    customThemeMode: ShortSwipeImportMode,
) {
    val appContext = applicationContext
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val result = manager.applySettingsImportPlan(
                plan, excludedKeys, shortSwipeMode, prefs, customThemeMode,
            )
            DirectBootAwarePreferences.copy_preferences_to_protected_storage(appContext, prefs)
            // Words a 1.x settings file carried: the keyboard reloads its dictionary, as after a
            // dictionary restore.
            if (result.customWordsAdded + result.disabledWordsAdded > 0) {
                LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
            }
            followUp {
                loadCurrentSettings()
                showIoResult(
                    getString(R.string.backup_result_import_success_title),
                    buildSettingsResultMessage(result, ResourcesResultText(resources)),
                )
            }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Apply settings plan failed", e)
            followUp {
                // Part of the file may have been written (DIY themes, then the words, precede the
                // settings commit): show what is stored now.
                loadCurrentSettings()
                showIoFailure(
                    R.string.backup_result_import_failed_title, R.string.backup_result_apply_settings_failed, e
                )
            }
        },
    )
}

/**
 * GitHub #142: write a single dated ZIP containing config + dictionaries +
 * clipboard JSON + media. Reuses the shared [BackupRestoreManager] helpers
 * so output stays in lockstep with the per-section exporters.
 */
internal fun SettingsActivity.performFullBackupExport(uri: Uri, plaintextOptOut: Boolean = false) {
    backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
    val appContext = applicationContext
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val result = manager.exportFullBackup(uri, prefs)
            val fileName = appContext.documentDisplayName(uri)
            followUp { showFullBackupExportResult(fileName, result) }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Full backup export failed", e)
            followUp {
                showIoFailure(
                    R.string.backup_result_full_backup_failed_title, R.string.backup_result_full_backup_write_failed, e
                )
            }
        },
    )
}

/** The full-backup export's [result] (written to [fileName]), on the current Settings activity. */
private fun SettingsActivity.showFullBackupExportResult(fileName: String, result: BackupRestoreManager.FullBackupResult) {
    if (!result.success) {
        showIoFailure(
            R.string.backup_result_full_backup_failed_title,
            R.string.backup_result_full_backup_write_failed,
            result.errorReason,
        )
        return
    }
    val message = buildString {
        appendLine(getString(R.string.backup_result_full_backup_saved))
        appendLine()
        appendLine(getString(R.string.common_file_name, fileName))
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
}

internal fun SettingsActivity.performFullBackupImport(uri: Uri, retryPassphrase: CharArray? = null) {
    if (retryPassphrase == null) primeImport()
    val appContext = applicationContext
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val result = manager.importFullBackup(uri, prefs)
            if (result.success) {
                // Copy prefs to protected storage so imported values survive Direct Boot, and
                // tell the keyboard to reload its dictionary: both whether or not Settings is
                // still open when the restore ends.
                DirectBootAwarePreferences.copy_preferences_to_protected_storage(appContext, prefs)
                LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
            }
            followUp {
                if (result.success) {
                    loadCurrentSettings()
                    showIoResult(
                        getString(R.string.backup_result_full_restored_title),
                        buildFullBackupResultMessage(result, ResourcesResultText(resources)),
                    )
                } else {
                    showIoFailure(
                        R.string.backup_result_full_import_failed_title,
                        R.string.backup_result_full_import_failed,
                        result.errorReason,
                    )
                }
            }
        },
        onFailure = { e ->
            if (e is BackupRestoreManager.BackupDecryptException) {
                followUp {
                    promptForPassphrase(
                        e, retryPassphrase,
                        R.string.backup_result_full_import_failed_title, R.string.backup_result_full_import_failed,
                        tribixbite.cleverkeys.PendingPassphraseImport(tribixbite.cleverkeys.EncryptedImportKind.FULL_BACKUP, uri),
                    )
                }
            } else {
                android.util.Log.e(SettingsActivity.TAG, "Full backup import failed", e)
                followUp {
                    showIoFailure(
                        R.string.backup_result_full_import_failed_title, R.string.backup_result_full_import_failed, e
                    )
                }
            }
        },
    )
}
