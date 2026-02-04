package tribixbite.cleverkeys.customization

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.Gson

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

    var name by remember { mutableStateOf(initialIntent?.name ?: "") }
    var targetType by remember { mutableStateOf(initialIntent?.targetType ?: IntentTargetType.ACTIVITY) }
    var action by remember { mutableStateOf(initialIntent?.action ?: "") }
    var data by remember { mutableStateOf(initialIntent?.data ?: "") }
    var type by remember { mutableStateOf(initialIntent?.type ?: "") }
    var packageName by remember { mutableStateOf(initialIntent?.packageName ?: "") }
    var className by remember { mutableStateOf(initialIntent?.className ?: "") }

    // Simple key-value pairs for extras
    var extrasList by remember {
        mutableStateOf(initialIntent?.extras?.toList() ?: emptyList())
    }
    var newExtraKey by remember { mutableStateOf("") }
    var newExtraValue by remember { mutableStateOf("") }

    var expandedTypeDropdown by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(!isEditMode) } // Show presets only for new intents

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
                    title = { Text(if (isEditMode) "Edit Intent Action" else "Create Intent Action") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
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
                            Text("Save")
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
                            "Quick Presets",
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
                            IntentDefinition.PRESETS.forEach { preset ->
                                FilterChip(
                                    onClick = {
                                        // Apply preset values
                                        name = preset.name
                                        targetType = preset.targetType
                                        action = preset.action ?: ""
                                        data = preset.data ?: ""
                                        type = preset.type ?: ""
                                        packageName = preset.packageName ?: ""
                                        className = preset.className ?: ""
                                        extrasList = preset.extras?.toList() ?: emptyList()
                                        showPresets = false
                                    },
                                    label = { Text(preset.name, fontSize = 12.sp) },
                                    selected = false
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { showPresets = false },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Create Custom")
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Macro Name (Required)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Target Type Dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = targetType.name,
                            onValueChange = {},
                            label = { Text("Target Type") },
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { expandedTypeDropdown = true }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = expandedTypeDropdown,
                            onDismissRequest = { expandedTypeDropdown = false }
                        ) {
                            IntentTargetType.values().forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.name) },
                                    onClick = {
                                        targetType = type
                                        expandedTypeDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Intent Details", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Package Name") },
                        placeholder = { Text("com.example.app") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = className,
                        onValueChange = { className = it },
                        label = { Text("Class Name (Optional)") },
                        placeholder = { Text("com.example.app.MainActivity") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it },
                        label = { Text("Action") },
                        placeholder = { Text("android.intent.action.VIEW") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = data,
                        onValueChange = { data = it },
                        label = { Text("Data URI (Optional)") },
                        placeholder = { Text("https://google.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("MIME Type (Optional)") },
                        placeholder = { Text("text/plain") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Extras (Key-Value Strings)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Add Extra Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newExtraKey,
                            onValueChange = { newExtraKey = it },
                            label = { Text("Key") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = newExtraValue,
                            onValueChange = { newExtraValue = it },
                            label = { Text("Value") },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (newExtraKey.isNotBlank()) {
                                    extrasList = extrasList + (newExtraKey to newExtraValue)
                                    newExtraKey = ""
                                    newExtraValue = ""
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add Extra")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // List Extras
                    extrasList.forEach { (k, v) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$k : $v", modifier = Modifier.weight(1f))
                            IconButton(onClick = { extrasList = extrasList - (k to v) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
