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
}
