package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            backupRestoreManager.encryptionPolicy = exportPolicy(plaintextOptOut)
            val summary = withContext(Dispatchers.IO) {
                backupRestoreManager.exportDictionaries(uri)
            }
            val counts = resources.getQuantityString(
                R.plurals.dictionary_backup_export_custom_words,
                summary.languageCount, summary.customWordsCount, summary.languageCount,
            ) + "\n" + getString(R.string.dictionary_backup_export_disabled_words, summary.disabledWordsCount)
            showIoResult(
                getString(R.string.dictionary_backup_export_success_title),
                paragraphs(counts, getString(R.string.common_file_name, uri.lastPathSegment.orEmpty())),
            )
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Dictionary export failed", e)
            showIoFailure(
                R.string.dictionary_backup_export_failed_title, R.string.dictionary_backup_export_failed, e
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

/**
 * Dictionary import: build a per-language plan so the user can deselect
 * specific words. If no new words to import, jump straight to the result
 * dialog.
 */
internal fun SettingsActivity.performDictionaryImport(uri: Uri, retryPassphrase: CharArray? = null) {
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        if (retryPassphrase == null) primeImport()
        try {
            val plan = withContext(Dispatchers.IO) {
                backupRestoreManager.buildDictImportPlan(uri, prefs)
            }
            val nothingToImport = !plan.learnedData.hasEffect && plan.perLanguage.values.all {
                it.newCustomWords.isEmpty() && it.newDisabledWords.isEmpty()
            }
            if (nothingToImport) {
                showIoResult(
                    getString(R.string.backup_result_no_changes_title),
                    getString(R.string.dictionary_backup_no_new_words),
                )
            } else {
                backupRestoreViewModel.dictPreviewPlan = plan
            }
        } catch (e: tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
            promptForPassphrase(
                e, retryPassphrase,
                R.string.backup_result_import_failed_title, R.string.dictionary_backup_read_failed,
            ) { entered ->
                backupRestoreManager.setImportPassphraseOverride(entered)
                performDictionaryImport(uri, entered)
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Build dictionary plan failed", e)
            showIoFailure(
                R.string.backup_result_import_failed_title, R.string.dictionary_backup_read_failed, e
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}

/**
 * Apply a previously-shown dictionary preview. Single editor.commit()
 * across all per-language word lists; broadcasts ACTION_DICTIONARY_IMPORTED
 * so DictionaryManagerActivity refreshes its view.
 */
internal fun SettingsActivity.applyPlannedDictionaries(
    plan: DictImportPlan,
    excludedCustom: Set<LangWord>,
    excludedDisabled: Set<LangWord>,
) {
    val _self = this
    lifecycleScope.launch {
        backupRestoreViewModel.isProcessing = true
        try {
            val result = withContext(Dispatchers.IO) {
                backupRestoreManager.applyDictImportPlan(plan, excludedCustom, excludedDisabled, prefs)
            }
            showIoResult(
                getString(R.string.dictionary_backup_import_success_title),
                buildDictResultMessage(result, ResourcesResultText(resources)),
            )
            LocalBroadcastManager.getInstance(_self)
                .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Apply dictionary plan failed", e)
            showIoFailure(
                R.string.backup_result_import_failed_title, R.string.dictionary_backup_apply_failed, e
            )
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}
