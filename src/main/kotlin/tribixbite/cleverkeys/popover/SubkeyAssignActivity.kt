package tribixbite.cleverkeys.popover

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.customization.CommandPaletteDialog
import tribixbite.cleverkeys.customization.ShortSwipeAssignment
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.theme.CleverKeysTheme

/**
 * The subkey popover's assign/edit screen (docs/specs/subkey-popover.md "Assign / edit screens").
 *
 * A transparent activity the keyboard starts over the app being typed in, in its own task so
 * finishing it returns there. It is built from the same pieces as Settings → Customize Per-Key
 * Actions: [CommandPaletteDialog] picks the action and [ShortSwipeAssignment] stores it.
 * An activity rather than an in-keyboard dialog because the palette has a search field, and an
 * IME cannot type into its own window.
 */
class SubkeyAssignActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = requestFrom(intent)
        if (request == null) {
            Log.w(TAG, "Missing or invalid extras; closing")
            finish()
            return
        }
        setContent {
            CleverKeysTheme {
                SubkeyAssignScreen(request, onDone = { finish() })
            }
        }
    }

    companion object {
        private const val TAG = "SubkeyAssign"
        private const val EXTRA_KEY = "key_code"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_HAS_DEFAULT = "has_default"
        private const val EXTRA_IS_CUSTOM = "is_custom"
        private const val EXTRA_LABEL = "current_label"

        /** Start the screen from the keyboard (a non-activity context, hence a new task). */
        fun launch(context: Context, request: SubkeyAssignRequest) {
            val intent = Intent(context, SubkeyAssignActivity::class.java)
                .putExtra(EXTRA_KEY, request.keyCode)
                .putExtra(EXTRA_DIRECTION, request.direction.name)
                .putExtra(EXTRA_MODE, request.mode.name)
                .putExtra(EXTRA_HAS_DEFAULT, request.hasDefault)
                .putExtra(EXTRA_IS_CUSTOM, request.isCustom)
                .putExtra(EXTRA_LABEL, request.currentLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Never let a failed launch take the keyboard down with it.
                Log.e(TAG, "Could not open the subkey assign screen", e)
            }
        }

        internal fun requestFrom(intent: Intent): SubkeyAssignRequest? {
            val keyCode = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotEmpty() } ?: return null
            val direction = intent.getStringExtra(EXTRA_DIRECTION)
                ?.let { name -> SwipeDirection.entries.firstOrNull { it.name == name } } ?: return null
            val mode = intent.getStringExtra(EXTRA_MODE)
                ?.let { name -> SubkeyAssignRequest.Mode.entries.firstOrNull { it.name == name } } ?: return null
            return SubkeyAssignRequest(
                keyCode = keyCode,
                direction = direction,
                mode = mode,
                hasDefault = intent.getBooleanExtra(EXTRA_HAS_DEFAULT, false),
                isCustom = intent.getBooleanExtra(EXTRA_IS_CUSTOM, false),
                currentLabel = intent.getStringExtra(EXTRA_LABEL),
            )
        }
    }
}

/**
 * ASSIGN on a plain empty slot goes straight to the palette. ASSIGN on a slot whose layout
 * subkey was removed, and EDIT, first show a small choice dialog.
 */
@Composable
private fun SubkeyAssignScreen(request: SubkeyAssignRequest, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ShortSwipeCustomizationManager.getInstance(context) }
    LaunchedEffect(Unit) { manager.loadMappings() }

    val startWithPalette = request.mode == SubkeyAssignRequest.Mode.ASSIGN && !request.hasDefault
    var showPalette by remember { mutableStateOf(startWithPalette) }
    val title = stringResource(
        R.string.subkey_assign_title,
        request.keyCode.uppercase(),
        stringResource(request.direction.displayNameRes)
    )

    if (showPalette) {
        CommandPaletteDialog(
            onDismiss = onDone,
            onCommandSelected = { /* onMappingSelected below carries label + action */ },
            onTextSelected = { /* onMappingSelected below carries label + action */ },
            onMappingSelected = { selection ->
                scope.launch {
                    ShortSwipeAssignment.apply(context, manager, request.keyCode, request.direction, selection)
                    onDone()
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (request.currentLabel != null)
                        stringResource(R.string.subkey_edit_current, request.currentLabel)
                    else stringResource(R.string.subkey_assign_default_removed),
                    style = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(onClick = { showPalette = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (request.mode == SubkeyAssignRequest.Mode.EDIT) R.string.subkey_edit_reassign
                            else R.string.subkey_assign_choose
                        )
                    )
                }
                // Restore: a custom mapping (or a `removed` blank) hides the layout's subkey.
                if (request.hasDefault && (request.isCustom || request.mode == SubkeyAssignRequest.Mode.ASSIGN)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                ShortSwipeAssignment.restoreDefault(context, manager, request.keyCode, request.direction)
                                onDone()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.subkey_edit_restore)) }
                }
                if (request.mode == SubkeyAssignRequest.Mode.EDIT) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                ShortSwipeAssignment.remove(
                                    context, manager, request.keyCode, request.direction, request.hasDefault
                                )
                                onDone()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.subkey_edit_remove)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
