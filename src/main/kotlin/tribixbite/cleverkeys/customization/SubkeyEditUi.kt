package tribixbite.cleverkeys.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R

/*
 * Compose pieces shared by the two screens that edit subkeys — the per-key dialog
 * (`KeyCustomizationDialog`) and the subkey popover's assign/edit screen (`SubkeyAssignActivity`) —
 * so both show a slot's source and ask the copy question the same way
 * (docs/specs/subkey-customization-current.md "One policy for both screens").
 */

/** Small tinted badge naming a slot's [SubkeySource] ("Layout", "Custom" …); nothing for NONE. */
@Composable
fun SubkeySourceBadge(source: SubkeySource) {
    if (source == SubkeySource.NONE) return
    val (bg, fg) = when (source) {
        SubkeySource.LAYOUT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SubkeySource.CUSTOM -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SubkeySource.REMOVED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = stringResource(source.labelRes), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

/**
 * "Change a subkey of the layout?" — asked before a BUILT-IN layout's own subkey is changed or
 * removed ([SubkeyEditPolicy.Route.ASK_COPY]). [onAnswer] gets the target the answer picks
 * ([SubkeyEditPolicy.targetAfterCopyQuestion]): **Create copy** writes the modified copy
 * [copyName] of [layoutName]; **Change in all layouts** makes a global custom mapping. Compose it
 * AFTER any parent `Dialog` so it stacks above it (CLAUDE.md "Compose dialog stacking order").
 */
@Composable
fun LayoutCopyQuestionDialog(
    layoutName: String,
    copyName: String,
    onAnswer: (SlotEditTarget) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.subkey_layout_copy_title)) },
        text = { Text(stringResource(R.string.subkey_layout_copy_body, layoutName, copyName)) },
        confirmButton = {
            TextButton(onClick = { onAnswer(SubkeyEditPolicy.targetAfterCopyQuestion(createCopy = true)) }) {
                Text(stringResource(R.string.subkey_layout_copy_confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = { onAnswer(SubkeyEditPolicy.targetAfterCopyQuestion(createCopy = false)) }) {
                    Text(stringResource(R.string.subkey_layout_copy_global))
                }
            }
        }
    )
}
