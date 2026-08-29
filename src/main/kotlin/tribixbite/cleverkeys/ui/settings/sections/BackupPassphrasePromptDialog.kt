package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.BackupRestoreViewModel
import tribixbite.cleverkeys.R

/**
 * Stage B: prompts for a backup passphrase when an encrypted import cannot be decrypted
 * with the stored passphrase (design §9). Rendered whenever
 * [BackupRestoreViewModel.passphrasePromptRetry] is non-null; invoking the retry lambda
 * re-runs the specific import that triggered the prompt. Cancel aborts the import,
 * touching nothing (the decrypt threw before any parse/apply).
 */
@Composable
internal fun BackupPassphrasePromptDialog(vm: BackupRestoreViewModel) {
    val retry = vm.passphrasePromptRetry ?: return
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    val transform: VisualTransformation =
        if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { vm.dismissPassphrasePrompt() },
        title = { Text(stringResource(R.string.backup_passphrase_prompt_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.backup_passphrase_prompt_body),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.backup_passphrase_prompt_label)) },
                    singleLine = true,
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = show, onCheckedChange = { show = it })
                    Text(stringResource(R.string.backup_password_show), fontSize = 13.sp)
                }
                vm.passphrasePromptError?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pass.isNotEmpty(),
                onClick = { retry(pass.toCharArray()) },
            ) { Text(stringResource(R.string.backup_passphrase_prompt_decrypt)) }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissPassphrasePrompt() }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
