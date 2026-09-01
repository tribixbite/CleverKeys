package tribixbite.cleverkeys.ui.settings.io

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.BackupRestoreActivity
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
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.importCustomDictionary() {
    try {
        dictionaryImportLauncher.launch(arrayOf("application/json", "*/*"))
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
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
            backupRestoreViewModel.resultTitle = "Dictionary Export Successful"
            backupRestoreViewModel.resultMessage = "Custom words: ${summary.customWordsCount} " +
                    "(across ${summary.languageCount} languages)\n" +
                    "Disabled words: ${summary.disabledWordsCount}\n\n" +
                    "File: ${uri.lastPathSegment}"
            backupRestoreViewModel.showResultDialog = true
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Dictionary export failed", e)
            backupRestoreViewModel.resultTitle = "Dictionary Export Failed"
            backupRestoreViewModel.resultMessage = "Failed to export dictionaries:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
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
                backupRestoreViewModel.resultTitle = "No changes"
                backupRestoreViewModel.resultMessage = "Dictionary file has no new words to import."
                backupRestoreViewModel.showResultDialog = true
            } else {
                backupRestoreViewModel.dictPreviewPlan = plan
            }
        } catch (e: tribixbite.cleverkeys.BackupRestoreManager.BackupDecryptException) {
            promptForPassphrase(e, retryPassphrase) { entered ->
                backupRestoreManager.setImportPassphraseOverride(entered)
                performDictionaryImport(uri, entered)
            }
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Build dictionary plan failed", e)
            backupRestoreViewModel.resultTitle = "Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to read dictionary file:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
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
            backupRestoreViewModel.resultTitle = "Dictionary Import Successful"
            backupRestoreViewModel.resultMessage = buildDictResultMessage(result)
            backupRestoreViewModel.showResultDialog = true
            LocalBroadcastManager.getInstance(_self)
                .sendBroadcast(Intent(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED))
        } catch (e: Exception) {
            android.util.Log.e(SettingsActivity.TAG, "Apply dictionary plan failed", e)
            backupRestoreViewModel.resultTitle = "Import Failed"
            backupRestoreViewModel.resultMessage = "Failed to apply dictionary import:\n\n${e.message}"
            backupRestoreViewModel.showResultDialog = true
        } finally {
            backupRestoreViewModel.isProcessing = false
        }
    }
}
