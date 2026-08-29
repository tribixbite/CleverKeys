package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.BackupRestoreActivity
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.saveSetting

/** Minimum passphrase length (design §9); a soft warning fires below [WEAK_LEN]. */
private const val MIN_LEN = 8
private const val WEAK_LEN = 12

/**
 * Stage B (backup encryption) — the "Backup password" UI block (design §9). Shows
 * the current status, Set/Change/Remove dialogs, an "Encrypt exports" indicator, and
 * the default-off "Allow passphrase via automation intent" toggle. All passphrase
 * writes go through [SettingsActivity.backupPassphraseStore].
 */
@Composable
internal fun SettingsActivity.BackupPasswordBlock() {
    // `remember` on a mutableState so status re-reads after a Set/Remove without a
    // full recomposition trigger from elsewhere. hasPassphrase is cheap (prefs read).
    var hasPassphrase by remember { mutableStateOf(backupPassphraseStore.hasPassphrase()) }
    var protectionState by remember { mutableStateOf(backupPassphraseStore.protectionState()) }
    var showSetDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var isChange by remember { mutableStateOf(false) }
    var allowIntentPassphrase by remember {
        mutableStateOf(prefs.getBoolean(BackupRestoreActivity.PREF_ALLOW_INTENT_PASSPHRASE, false))
    }

    Text(
        text = stringResource(R.string.backup_password_title),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    if (hasPassphrase) {
        Text(
            text = stringResource(BackupRestoreActivity.protectionStateLabelRes(protectionState)),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    } else {
        Text(
            text = stringResource(R.string.backup_password_not_set_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { isChange = false; showSetDialog = true },
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(
                    if (hasPassphrase) R.string.backup_password_change
                    else R.string.backup_password_set
                )
            )
        }
        if (hasPassphrase) {
            OutlinedButton(
                onClick = { showRemoveDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_password_remove))
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Default-off automation escape hatch (design §4.2 / §8). IMPORT only, never export.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.backup_password_allow_intent_title), fontSize = 13.sp)
            Text(
                text = stringResource(R.string.backup_password_allow_intent_desc),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = allowIntentPassphrase,
            onCheckedChange = {
                allowIntentPassphrase = it
                saveSetting(BackupRestoreActivity.PREF_ALLOW_INTENT_PASSPHRASE, it)
            },
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (showSetDialog) {
        BackupPasswordSetDialog(
            isChange = hasPassphrase,
            onDismiss = { showSetDialog = false },
            onConfirm = { newPass ->
                try {
                    backupPassphraseStore.setPassphrase(newPass)
                    hasPassphrase = true
                    protectionState = backupPassphraseStore.protectionState()
                    showSetDialog = false
                    null
                } catch (e: tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore.StorageUnavailableException) {
                    // The store's own message is a diagnostic detail (not localized); it is kept
                    // as the formatted arg because this dialog has always surfaced it, and it is
                    // the only signal distinguishing a Keystore wrap failure from a commit
                    // failure. The sentence around it is localized (CK-150-030).
                    getString(
                        R.string.backup_passphrase_storage_unavailable,
                        e.message.orEmpty(),
                    ).trim()
                } finally {
                    java.util.Arrays.fill(newPass, ' ')
                }
            },
            verifyCurrent = { candidate ->
                val stored = backupPassphraseStore.getPassphrase()
                val ok = stored != null && stored.contentEquals(candidate)
                stored?.let { java.util.Arrays.fill(it, ' ') }
                ok
            },
        )
    }

    if (showRemoveDialog) {
        BackupPasswordRemoveDialog(
            onDismiss = { showRemoveDialog = false },
            onConfirm = { candidate ->
                val stored = backupPassphraseStore.getPassphrase()
                val ok = stored != null && stored.contentEquals(candidate)
                stored?.let { java.util.Arrays.fill(it, ' ') }
                if (ok) {
                    backupPassphraseStore.clear()
                    hasPassphrase = false
                    protectionState = backupPassphraseStore.protectionState()
                    showRemoveDialog = false
                }
                ok
            },
        )
    }
}

/**
 * Set / Change dialog: enter + confirm (min 8 chars, weak-warning under 12), with a
 * show/hide toggle. When [isChange] is true a current-password field is verified first
 * via [verifyCurrent].
 */
@Composable
private fun BackupPasswordSetDialog(
    isChange: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> String?,
    verifyCurrent: (CharArray) -> Boolean,
) {
    var current by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Validation messages are assigned from a non-composable onClick lambda, so they
    // must be resolved here, in composable scope.
    val errIncorrect = stringResource(R.string.backup_password_error_incorrect)
    val errTooShort = stringResource(R.string.backup_password_error_too_short, MIN_LEN)
    val errMismatch = stringResource(R.string.backup_password_error_mismatch)

    val transform: VisualTransformation =
        if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isChange) R.string.backup_password_change_dialog_title
                    else R.string.backup_password_set_dialog_title
                )
            )
        },
        text = {
            Column {
                if (isChange) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it; error = null },
                        label = { Text(stringResource(R.string.backup_password_current_label)) },
                        singleLine = true,
                        visualTransformation = transform,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_new_label)) },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_confirm_label)) },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = show, onCheckedChange = { show = it })
                    Text(stringResource(R.string.backup_password_show), fontSize = 13.sp)
                }
                if (pass.isNotEmpty() && pass.length in MIN_LEN until WEAK_LEN) {
                    Text(
                        stringResource(R.string.backup_password_weak_warning, WEAK_LEN),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    isChange && !verifyCurrent(current.toCharArray()) -> error = errIncorrect
                    pass.length < MIN_LEN -> error = errTooShort
                    pass != confirm -> error = errMismatch
                    else -> onConfirm(pass.toCharArray())?.let { error = it }
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Remove dialog: requires typing the current password. Warns that automation
 * backup/restore will stop working (design §9).
 */
@Composable
private fun BackupPasswordRemoveDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Boolean,
) {
    var current by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Assigned from the non-composable confirm lambda — resolve in composable scope.
    val errIncorrect = stringResource(R.string.backup_password_error_incorrect)

    val transform: VisualTransformation =
        if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_remove_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.backup_password_remove_dialog_body),
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_current_label)) },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = show, onCheckedChange = { show = it })
                    Text(stringResource(R.string.backup_password_show), fontSize = 13.sp)
                }
                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onConfirm(current.toCharArray())) {
                    error = errIncorrect
                }
            }) { Text(stringResource(R.string.backup_password_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
