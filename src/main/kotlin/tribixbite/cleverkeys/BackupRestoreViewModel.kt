package tribixbite.cleverkeys

import android.net.Uri
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
     * (or none was stored); the entered passphrase retries that import. [passphrasePromptError]
     * carries the "wrong password" message on a failed retry (null on first prompt).
     *
     * DATA, not a callback (review 2026-11-14 M9): this VM outlives the Activity across a
     * rotation, and a stored lambda captured the destroyed one — the retry then ran against its
     * dead lifecycleScope and nothing happened. The live Activity turns it into a call.
     */
    var pendingPassphraseImport by mutableStateOf<PendingPassphraseImport?>(null)
    var passphrasePromptError by mutableStateOf<String?>(null)

    /** Dismiss the passphrase prompt (user cancelled — import aborted, nothing touched). */
    fun dismissPassphrasePrompt() {
        pendingPassphraseImport = null
        passphrasePromptError = null
    }
}

/** Which encrypted import asked for a passphrase. */
enum class EncryptedImportKind { SETTINGS, FULL_BACKUP, CLIPBOARD, CLIPBOARD_ZIP, DICTIONARY }

/** The import a passphrase prompt retries: what it was and which file. */
data class PendingPassphraseImport(val kind: EncryptedImportKind, val uri: Uri)
