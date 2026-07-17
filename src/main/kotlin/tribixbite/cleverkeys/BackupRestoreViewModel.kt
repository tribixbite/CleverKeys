package tribixbite.cleverkeys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.SettingsImportPlan

/**
 * Holds preview-flow state across configuration changes (rotation, fold).
 * Without this VM, plans + user toggle state would be discarded on rotate
 * — see spec §Architecture > Persistence on rotation.
 */
class BackupRestoreViewModel : ViewModel() {
    var settingsPreviewPlan by mutableStateOf<SettingsImportPlan?>(null)
    var dictPreviewPlan by mutableStateOf<DictImportPlan?>(null)
    var isProcessing by mutableStateOf(false)
    var resultTitle by mutableStateOf("")
    var resultMessage by mutableStateOf("")
    var showResultDialog by mutableStateOf(false)

    /**
     * Stage B (backup encryption): when non-null, a passphrase-prompt dialog is shown
     * for an encrypted import that could not be decrypted with the stored passphrase
     * (or none was stored). The lambda is invoked with the entered passphrase to retry
     * the specific import that triggered it. [passphrasePromptError] carries the
     * "wrong password" message on a failed retry (null on first prompt).
     */
    var passphrasePromptRetry by mutableStateOf<((CharArray) -> Unit)?>(null)
    var passphrasePromptError by mutableStateOf<String?>(null)

    /** Dismiss the passphrase prompt (user cancelled — import aborted, nothing touched). */
    fun dismissPassphrasePrompt() {
        passphrasePromptRetry = null
        passphrasePromptError = null
    }
}
