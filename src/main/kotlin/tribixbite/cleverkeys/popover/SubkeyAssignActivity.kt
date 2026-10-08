package tribixbite.cleverkeys.popover

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SubLabelSizing
import tribixbite.cleverkeys.Theme
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandPaletteDialog
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.ShortSwipeAssignment
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.theme.CleverKeysTheme

/**
 * The subkey popover's assign/edit screen (docs/specs/subkey-popover.md "Assign / edit screens").
 *
 * A transparent activity the keyboard starts over the app being typed in, in its own task so
 * finishing it returns there. It is built from the same pieces as Settings → Customize Per-Key
 * Actions: [CommandPaletteDialog] picks or edits the action and [ShortSwipeAssignment] stores it.
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

        /** Start the screen from the keyboard (a non-activity context, hence a new task). */
        fun launch(context: Context, request: SubkeyAssignRequest) {
            val intent = Intent(context, SubkeyAssignActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            SubkeyAssignExtras.write(
                request,
                putString = { key, value -> intent.putExtra(key, value) },
                putBoolean = { key, value -> intent.putExtra(key, value) },
            )
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Never let a failed launch take the keyboard down with it.
                Log.e(TAG, "Could not open the subkey assign screen", e)
            }
        }

        internal fun requestFrom(intent: Intent): SubkeyAssignRequest? =
            SubkeyAssignExtras.read(intent::getStringExtra, intent::getBooleanExtra)
    }
}

/** Which way the palette is open: picking a new action, or editing the stored one in place. */
private enum class PaletteMode { NEW, EDIT }

/** The slot's stored mapping, once loaded (null inside: the slot has no custom mapping). */
private class LoadedMapping(val mapping: ShortSwipeMapping?)

/**
 * ASSIGN on a plain empty slot goes straight to the palette. ASSIGN on a slot whose layout
 * subkey was removed, and EDIT, first show the slot's details and a choice of actions.
 */
@Composable
private fun SubkeyAssignScreen(request: SubkeyAssignRequest, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ShortSwipeCustomizationManager.getInstance(context) }
    // The stored mapping: the edit screen shows what it does and edits it, not just its label.
    // A LaunchedEffect rather than produceState: lint's ProduceStateDoesNotAssignValue rejected
    // the produceState form of this load and failed CI (run on a9d8cb1d).
    var loaded by remember { mutableStateOf<LoadedMapping?>(null) }
    LaunchedEffect(request.keyCode, request.direction) {
        manager.loadMappings()
        loaded = LoadedMapping(manager.getMapping(request.keyCode, request.direction))
    }

    val startWithPalette = request.mode == SubkeyAssignRequest.Mode.ASSIGN && !request.hasDefault
    // Saveable: rotating with the palette open keeps it open, with its own draft (audit 2026-10-08).
    var palette by rememberSaveable { mutableStateOf(if (startWithPalette) PaletteMode.NEW else null) }
    val title = stringResource(
        R.string.subkey_assign_title,
        request.keyCode.uppercase(),
        stringResource(request.direction.displayNameRes)
    )

    val current = loaded ?: return  // a local file read: shows within a frame or two
    val custom = current.mapping?.takeUnless { it.isRemoval }

    palette?.let { mode ->
        CommandPaletteDialog(
            // Opened straight from the popover: closing it is closing the screen. Opened from
            // the details: closing it goes back there.
            onDismiss = { if (startWithPalette) onDone() else palette = null },
            onMappingSelected = { selection ->
                scope.launch {
                    ShortSwipeAssignment.apply(context, manager, request.keyCode, request.direction, selection)
                    onDone()
                }
            },
            subtitle = title,
            initialMapping = if (mode == PaletteMode.EDIT) custom else null,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SlotDetails(request, custom)
                // Edit: change the stored action itself (its text, intent, pattern or label).
                // A raw key event has no editor, so it is only reassigned.
                val canEdit = custom != null && custom.actionType != ActionType.KEY_EVENT
                if (canEdit) {
                    FilledTonalButton(onClick = { palette = PaletteMode.EDIT }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_edit))
                    }
                }
                val reassignLabel = stringResource(
                    if (request.mode == SubkeyAssignRequest.Mode.EDIT) R.string.subkey_edit_reassign
                    else R.string.subkey_assign_choose
                )
                if (canEdit) {
                    OutlinedButton(onClick = { palette = PaletteMode.NEW }, modifier = Modifier.fillMaxWidth()) {
                        Text(reassignLabel)
                    }
                } else {
                    FilledTonalButton(onClick = { palette = PaletteMode.NEW }, modifier = Modifier.fillMaxWidth()) {
                        Text(reassignLabel)
                    }
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
                        modifier = Modifier.fillMaxWidth(),
                        // Destructive: drawn in the error colour so it does not read like Edit.
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
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

/**
 * What the slot does now: its label as drawn on the popover, the kind of action, and the action
 * itself — the command's name and description, the full custom text, the intent's target, or the
 * timestamp pattern with a live preview. A layout subkey says it comes from the layout.
 */
@Composable
private fun SlotDetails(request: SubkeyAssignRequest, custom: ShortSwipeMapping?) {
    val labelText = custom?.displayText?.ifEmpty { null } ?: request.currentLabel
    val labelKeyFont = custom?.useKeyFont ?: request.labelUsesKeyFont
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (labelText != null) LabelBadge(labelText, labelKeyFont)
        Text(
            text = when {
                custom != null -> stringResource(custom.actionType.displayNameRes)
                request.currentLabel != null -> stringResource(R.string.subkey_edit_layout_default)
                else -> stringResource(R.string.subkey_assign_default_removed)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (custom == null) return
    val detail = MaterialTheme.typography.bodyMedium
    val mono = detail.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    when (custom.actionType) {
        ActionType.COMMAND -> {
            val command = CommandRegistry.getByName(custom.actionValue)
            if (command != null) {
                Text(stringResource(command.nameRes), style = detail, fontWeight = FontWeight.Medium)
                Text(stringResource(command.descriptionRes), style = detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(custom.actionValue, style = mono)
            }
        }
        ActionType.TEMPLATE -> DetailBox { Text(custom.actionValue, style = mono, maxLines = 8, overflow = TextOverflow.Ellipsis) }
        ActionType.TEXT -> DetailBox { Text(custom.actionValue, style = detail, maxLines = 8, overflow = TextOverflow.Ellipsis) }
        ActionType.INTENT -> {
            val intent = custom.getIntentDefinition()
            if (intent == null) {
                Text(stringResource(R.string.short_swipe_action_desc_intent_invalid), style = detail)
            } else {
                Text(intent.name, style = detail, fontWeight = FontWeight.Medium)
                // The intent's own fields, as written in the intent editor (API identifiers,
                // so they are shown verbatim rather than translated).
                DetailBox {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(intent.targetType.name, style = mono)
                        listOfNotNull(
                            intent.action, intent.data, intent.type,
                            intent.packageName?.let { pkg -> intent.className?.let { "$pkg/$it" } ?: pkg },
                        ).filter { it.isNotBlank() }.forEach { Text(it, style = mono) }
                        intent.extras.orEmpty().forEach { (k, v) -> Text("$k = $v", style = mono) }
                    }
                }
            }
        }
        ActionType.TIMESTAMP -> {
            DetailBox { Text(custom.actionValue, style = mono) }
            val preview = remember(custom.actionValue) {
                runCatching {
                    java.text.SimpleDateFormat(custom.actionValue, java.util.Locale.getDefault()).format(java.util.Date())
                }.getOrNull()
            }
            if (preview != null) {
                Text(stringResource(R.string.command_palette_pattern_preview, preview), style = detail)
            }
        }
        ActionType.KEY_EVENT -> Text(stringResource(R.string.short_swipe_action_desc_key_event, custom.actionValue), style = detail)
    }
}

/** The slot label as the popover draws it; key-font icons go through the key font. */
@Composable
private fun LabelBadge(text: String, keyFont: Boolean) {
    Box(
        modifier = Modifier
            .widthIn(min = 44.dp)
            .heightIn(min = 44.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Icons and emoji take the keyboard's glyph factor against the badge's text size
        // (SubLabelSizing), the same rule the command palette's label preview uses.
        val textSp = MaterialTheme.typography.titleLarge.fontSize.value
        if (keyFont) {
            val color = MaterialTheme.colorScheme.onSecondaryContainer.toArgb()
            AndroidView(factory = { ctx ->
                android.widget.TextView(ctx).apply {
                    typeface = Theme.getKeyFont(ctx)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SubLabelSizing.previewSp(textSp, true, text))
                    setTextColor(color)
                    this.text = text
                }
            })
        } else {
            Text(
                text,
                style = MaterialTheme.typography.titleLarge,
                fontSize = SubLabelSizing.previewSp(textSp, false, text).sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
        }
    }
}

/** A tinted box for literal values (custom text, intent fields, patterns). */
@Composable
private fun DetailBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) { content() }
}
