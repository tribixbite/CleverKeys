package tribixbite.cleverkeys.customization

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tribixbite.cleverkeys.R

/**
 * Dialog for creating or editing an IntentDefinition.
 *
 * @param initialIntent Optional intent to edit. If null, creates a new intent.
 * @param onDismiss Called when the dialog is dismissed without saving.
 * @param onConfirm Called with the created/edited IntentDefinition.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IntentEditorDialog(
    initialIntent: IntentDefinition? = null,
    onDismiss: () -> Unit,
    onConfirm: (IntentDefinition) -> Unit
) {
    val isEditMode = initialIntent != null

    // rememberSaveable: rotating mid-edit keeps the draft (audit 2026-10-08).
    var name by rememberSaveable { mutableStateOf(initialIntent?.name ?: "") }
    var targetType by rememberSaveable { mutableStateOf(initialIntent?.targetType ?: IntentTargetType.ACTIVITY) }
    var action by rememberSaveable { mutableStateOf(initialIntent?.action ?: "") }
    var data by rememberSaveable { mutableStateOf(initialIntent?.data ?: "") }
    var type by rememberSaveable { mutableStateOf(initialIntent?.type ?: "") }
    var packageName by rememberSaveable { mutableStateOf(initialIntent?.packageName ?: "") }
    var className by rememberSaveable { mutableStateOf(initialIntent?.className ?: "") }

    // Simple key-value pairs for extras
    var extrasList by rememberSaveable(stateSaver = ExtrasSaver) {
        mutableStateOf(initialIntent?.extras?.toList() ?: emptyList())
    }
    var newExtraKey by rememberSaveable { mutableStateOf("") }
    var newExtraValue by rememberSaveable { mutableStateOf("") }

    var expandedTypeDropdown by remember { mutableStateOf(false) }
    var showPresets by rememberSaveable { mutableStateOf(!isEditMode) } // Show presets only for new intents

    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (isEditMode) R.string.intent_editor_title_edit
                                else R.string.intent_editor_title_create
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                val intentDef = IntentDefinition(
                                    name = name,
                                    targetType = targetType,
                                    action = action.ifBlank { null },
                                    data = data.ifBlank { null },
                                    type = type.ifBlank { null },
                                    packageName = packageName.ifBlank { null },
                                    className = className.ifBlank { null },
                                    extras = if (extrasList.isNotEmpty()) extrasList.toMap() else null
                                )
                                onConfirm(intentDef)
                            },
                            enabled = name.isNotBlank() && (action.isNotBlank() || packageName.isNotBlank())
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Presets section (only for new intents)
                    if (showPresets && !isEditMode) {
                        Text(
                            stringResource(R.string.intent_editor_quick_presets),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Preset chips in a flow layout
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IntentDefinition.PRESET_ENTRIES.forEach { entry ->
                                val preset = entry.definition
                                // Localized chip label; it also pre-fills the user-editable
                                // name field (the preset's English name is only an identifier).
                                val presetLabel = stringResource(entry.labelRes)
                                FilterChip(
                                    onClick = {
                                        // Apply preset values
                                        name = presetLabel
                                        targetType = preset.targetType
                                        action = preset.action ?: ""
                                        data = preset.data ?: ""
                                        type = preset.type ?: ""
                                        packageName = preset.packageName ?: ""
                                        className = preset.className ?: ""
                                        extrasList = preset.extras?.toList() ?: emptyList()
                                        showPresets = false
                                    },
                                    label = { Text(presetLabel, fontSize = 12.sp) },
                                    selected = false
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { showPresets = false },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.intent_editor_create_custom))
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.intent_editor_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Target Type Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = targetType.name,
                            onValueChange = {},
                            label = { Text(stringResource(R.string.intent_editor_target_type_label)) },
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { expandedTypeDropdown = true }) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = stringResource(
                                            R.string.intent_editor_select_target_type_desc
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = expandedTypeDropdown,
                            onDismissRequest = { expandedTypeDropdown = false }
                        ) {
                            IntentTargetType.entries.forEach { targetTypeOption ->
                                DropdownMenuItem(
                                    text = { Text(targetTypeOption.name) },
                                    onClick = {
                                        targetType = targetTypeOption
                                        expandedTypeDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.intent_editor_details_header),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text(stringResource(R.string.intent_editor_package_label)) },
                        placeholder = { Text("com.example.app") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = className,
                        onValueChange = { className = it },
                        label = { Text(stringResource(R.string.intent_editor_class_label)) },
                        placeholder = { Text("com.example.app.MainActivity") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it },
                        label = { Text(stringResource(R.string.intent_editor_action_label)) },
                        placeholder = { Text("android.intent.action.VIEW") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = data,
                        onValueChange = { data = it },
                        label = { Text(stringResource(R.string.intent_editor_data_label)) },
                        placeholder = { Text("https://google.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text(stringResource(R.string.intent_editor_mime_label)) },
                        placeholder = { Text("text/plain") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.intent_editor_extras_header),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Add Extra Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newExtraKey,
                            onValueChange = { newExtraKey = it },
                            label = { Text(stringResource(R.string.intent_editor_extra_key_label)) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = newExtraValue,
                            onValueChange = { newExtraValue = it },
                            label = { Text(stringResource(R.string.intent_editor_extra_value_label)) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (newExtraKey.isNotBlank()) {
                                    // Replace existing key or add new one (prevents duplicates)
                                    extrasList = extrasList.filter { it.first != newExtraKey } +
                                        (newExtraKey to newExtraValue)
                                    newExtraKey = ""
                                    newExtraValue = ""
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.intent_editor_add_extra_desc)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // List Extras
                    extrasList.forEach { (k, v) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.intent_editor_extra_row, k, v),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { extrasList = extrasList - (k to v) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.common_remove)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Intent extras as a flat key, value, key, value… list of Strings, which a Bundle can hold. */
private val ExtrasSaver = listSaver<List<Pair<String, String>>, String>(
    save = { extras -> extras.flatMap { (k, v) -> listOf(k, v) } },
    restore = { flat -> flat.chunked(2).map { it[0] to it[1] } },
)
