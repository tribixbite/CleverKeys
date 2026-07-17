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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.BackupRestoreActivity
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
    var showSetDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var isChange by remember { mutableStateOf(false) }
    var allowIntentPassphrase by remember {
        mutableStateOf(prefs.getBoolean(BackupRestoreActivity.PREF_ALLOW_INTENT_PASSPHRASE, false))
    }

    Text(
        text = "Backup Password",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    if (hasPassphrase) {
        Text(
            text = "Set ✓ — exports are encrypted 🔒",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    } else {
        Text(
            text = "Not set. Required for automation (am start) export/import; " +
                "encrypts all backups.",
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
            Text(if (hasPassphrase) "Change Password" else "Set Password")
        }
        if (hasPassphrase) {
            OutlinedButton(
                onClick = { showRemoveDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text("Remove")
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
            Text("Allow password via automation intent", fontSize = 13.sp)
            Text(
                text = "Off by default. When on, IMPORT via am start may pass " +
                    "--es passphrase. Leaks via shell history — use HISTIGNORE / a leading space.",
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
                backupPassphraseStore.setPassphrase(newPass)
                java.util.Arrays.fill(newPass, ' ')
                hasPassphrase = true
                showSetDialog = false
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
    onConfirm: (CharArray) -> Unit,
    verifyCurrent: (CharArray) -> Boolean,
) {
    var current by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val transform: VisualTransformation =
        if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChange) "Change Backup Password" else "Set Backup Password") },
        text = {
            Column {
                if (isChange) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it; error = null },
                        label = { Text("Current password") },
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
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = show, onCheckedChange = { show = it })
                    Text("Show password", fontSize = 13.sp)
                }
                if (pass.isNotEmpty() && pass.length in MIN_LEN until WEAK_LEN) {
                    Text(
                        "Weak: under $WEAK_LEN characters is easier to brute-force. Consider a longer passphrase.",
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
                    isChange && !verifyCurrent(current.toCharArray()) ->
                        error = "Current password is incorrect."
                    pass.length < MIN_LEN ->
                        error = "Password must be at least $MIN_LEN characters."
                    pass != confirm ->
                        error = "Passwords do not match."
                    else -> onConfirm(pass.toCharArray())
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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

    val transform: VisualTransformation =
        if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Backup Password") },
        text = {
            Column {
                Text(
                    "Automation backup/restore will stop working, and new exports will " +
                        "be unencrypted only via the manual opt-out. Enter the current " +
                        "password to confirm.",
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; error = null },
                    label = { Text("Current password") },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = show, onCheckedChange = { show = it })
                    Text("Show password", fontSize = 13.sp)
                }
                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onConfirm(current.toCharArray())) {
                    error = "Current password is incorrect."
                }
            }) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
