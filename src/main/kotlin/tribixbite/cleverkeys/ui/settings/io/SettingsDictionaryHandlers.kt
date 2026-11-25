package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.ResourcesResultText
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.LangWord
import tribixbite.cleverkeys.buildDictResultMessage

internal fun SettingsActivity.exportCustomDictionary() {
    try {
        dictionaryExportLauncher.launch(
            exportName("cleverkeys-dictionary.json", "application/json")
        )
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.importCustomDictionary() {
    try {
        dictionaryImportLauncher.launch(arrayOf("application/json", "*/*"))
    } catch (e: Exception) {
        toastFilePickerFailed(e)
    }
}

internal fun SettingsActivity.performDictionaryExport(uri: Uri, plaintextOptOut: Boolean = false) {
    backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
    val appContext = applicationContext
    val manager = backupRestoreManager
    runSettingsFileOperation(
        work = {
            val summary = manager.exportDictionaries(uri)
            val fileName = appContext.documentDisplayName(uri)
            followUp {
                val counts = resources.getQuantityString(
                    R.plurals.dictionary_backup_export_custom_words,
                    summary.languageCount, summary.customWordsCount, summary.languageCount,
                ) + "\n" + getString(R.string.dictionary_backup_export_disabled_words, summary.disabledWordsCount)
                showIoResult(
                    getString(R.string.dictionary_backup_export_success_title),
                    paragraphs(counts, getString(R.string.common_file_name, fileName)),
                )
            }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Dictionary export failed", e)
            followUp {
                showIoFailure(
                    R.string.dictionary_backup_export_failed_title, R.string.dictionary_backup_export_failed, e
                )
            }
        },
    )
}

/**
 * Dictionary import: build a per-language plan so the user can deselect
 * specific words. If no new words to import, jump straight to the result
 * dialog.
 */
internal fun SettingsActivity.performDictionaryImport(uri: Uri, retryPassphrase: CharArray? = null) {
    if (retryPassphrase == null) primeImport()
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val plan = manager.buildDictImportPlan(uri, prefs)
            val nothingToImport = !plan.learnedData.hasEffect && plan.perLanguage.values.all {
                it.newCustomWords.isEmpty() && it.newDisabledWords.isEmpty()
            }
            followUp {
                if (nothingToImport) {
                    showIoResult(
                        getString(R.string.backup_result_no_changes_title),
                        getString(R.string.dictionary_backup_no_new_words),
                    )
                } else {
                    backupRestoreViewModel.dictPreviewPlan = plan
                }
            }
        },
        onFailure = { e ->
            if (e is tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
                followUp {
                    promptForPassphrase(
                        e, retryPassphrase,
                        R.string.backup_result_import_failed_title, R.string.dictionary_backup_read_failed,
                        tribixbite.cleverkeys.PendingPassphraseImport(tribixbite.cleverkeys.EncryptedImportKind.DICTIONARY, uri),
                    )
                }
            } else {
                android.util.Log.e(SettingsActivity.TAG, "Build dictionary plan failed", e)
                followUp {
                    showIoFailure(
                        R.string.backup_result_import_failed_title, R.string.dictionary_backup_read_failed, e
                    )
                }
            }
        },
    )
}

/**
 * Apply a previously-shown dictionary preview. Single editor.commit()
 * across all per-language word lists; broadcasts ACTION_DICTIONARY_IMPORTED
 * so the keyboard and DictionaryManagerActivity reload (from the work itself, so it goes out even
 * when Settings is closed before the import ends).
 */
internal fun SettingsActivity.applyPlannedDictionaries(
    plan: DictImportPlan,
    excludedCustom: Set<LangWord>,
    excludedDisabled: Set<LangWord>,
) {
    val appContext = applicationContext
    val manager = backupRestoreManager
    val prefs = this.prefs
    runSettingsFileOperation(
        work = {
            val result = manager.applyDictImportPlan(plan, excludedCustom, excludedDisabled, prefs)
            LocalBroadcastManager.getInstance(appContext)
                .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
            followUp {
                showIoResult(
                    getString(R.string.dictionary_backup_import_success_title),
                    buildDictResultMessage(result, ResourcesResultText(resources)),
                )
            }
        },
        onFailure = { e ->
            android.util.Log.e(SettingsActivity.TAG, "Apply dictionary plan failed", e)
            followUp {
                showIoFailure(
                    R.string.backup_result_import_failed_title, R.string.dictionary_backup_apply_failed, e
                )
            }
        },
    )
}
