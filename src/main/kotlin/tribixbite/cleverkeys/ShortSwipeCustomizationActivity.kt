package tribixbite.cleverkeys

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.customization.*

/**
 * Short Swipe Customization Activity
 *
 * Allows users to customize short swipe gestures for every key:
 * - Interactive keyboard preview showing all keys
 * - Tap a key to open customization modal
 * - 8-direction radial selector for choosing swipe direction
 * - Editor for setting display text, action type, and action value
 */
class ShortSwipeCustomizationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                ShortSwipeCustomizationScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

// Standard QWERTY layout for preview
private val KEYBOARD_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortSwipeCustomizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ShortSwipeCustomizationManager.getInstance(context) }

    // Load mappings on first composition
    LaunchedEffect(Unit) {
        manager.loadMappings()
    }

    // Observe mappings
    val mappings by manager.mappingsFlow.collectAsState()

    // Selected key for customization modal
    var selectedKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Short Swipe Customization") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Reset all button
                    IconButton(
                        onClick = {
                            scope.launch {
                                manager.resetAll()
                                Toast.makeText(context, "All customizations reset", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset All")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Tap a key to customize its short swipe gestures",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${mappings.size} custom mappings configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Interactive keyboard preview
            InteractiveKeyboardPreview(
                mappings = mappings,
                onKeyClick = { keyCode -> selectedKey = keyCode }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // List of current customizations
            if (mappings.isNotEmpty()) {
                Text(
                    text = "Current Customizations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(mappings.sortedBy { "${it.keyCode}:${it.direction.ordinal}" }) { mapping ->
                        CustomizationItem(
                            mapping = mapping,
                            onEdit = { selectedKey = mapping.keyCode },
                            onDelete = {
                                scope.launch {
                                    manager.removeMapping(mapping.keyCode, mapping.direction)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Key customization modal
        selectedKey?.let { key ->
            KeyCustomizationDialog(
                keyCode = key,
                existingMappings = mappings.filter { it.keyCode == key },
                onDismiss = { selectedKey = null },
                onSave = { mapping ->
                    scope.launch {
                        manager.setMapping(mapping)
                    }
                },
                onDelete = { direction ->
                    scope.launch {
                        manager.removeMapping(key, direction)
                    }
                }
            )
        }
    }
}

@Composable
fun InteractiveKeyboardPreview(
    mappings: List<ShortSwipeMapping>,
    onKeyClick: (String) -> Unit
) {
    // Group mappings by key for quick lookup
    val mappingsByKey = remember(mappings) {
        mappings.groupBy { it.keyCode }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KEYBOARD_ROWS.forEach { row ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { key ->
                    val hasCustomizations = mappingsByKey.containsKey(key)
                    val customCount = mappingsByKey[key]?.size ?: 0

                    KeyPreviewButton(
                        keyCode = key,
                        hasCustomizations = hasCustomizations,
                        customCount = customCount,
                        onClick = { onKeyClick(key) }
                    )
                }
            }
        }
    }
}

@Composable
fun KeyPreviewButton(
    keyCode: String,
    hasCustomizations: Boolean,
    customCount: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (hasCustomizations)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
            .border(
                width = 1.dp,
                color = if (hasCustomizations)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = keyCode.uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasCustomizations)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
            if (hasCustomizations) {
                Text(
                    text = "$customCount",
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun CustomizationItem(
    mapping: ShortSwipeMapping,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Key + Direction indicator
            Column(
                modifier = Modifier.width(60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = mapping.keyCode.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = mapping.direction.shortLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Mapping details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\"${mapping.displayText}\" -> ${mapping.actionType.displayName}",
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mapping.actionValue,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Actions
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun KeyCustomizationDialog(
    keyCode: String,
    existingMappings: List<ShortSwipeMapping>,
    onDismiss: () -> Unit,
    onSave: (ShortSwipeMapping) -> Unit,
    onDelete: (SwipeDirection) -> Unit
) {
    // Selected direction for editing
    var selectedDirection by remember { mutableStateOf<SwipeDirection?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Customize \"${keyCode.uppercase()}\" Key",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 8-direction radial selector
                if (selectedDirection == null) {
                    DirectionSelector(
                        keyCode = keyCode,
                        existingMappings = existingMappings,
                        onDirectionSelected = { selectedDirection = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                } else {
                    // Direction editor
                    DirectionEditor(
                        keyCode = keyCode,
                        direction = selectedDirection!!,
                        existingMapping = existingMappings.find { it.direction == selectedDirection },
                        onSave = { mapping ->
                            onSave(mapping)
                            selectedDirection = null
                        },
                        onDelete = {
                            onDelete(selectedDirection!!)
                            selectedDirection = null
                        },
                        onBack = { selectedDirection = null }
                    )
                }
            }
        }
    }
}

@Composable
fun DirectionSelector(
    keyCode: String,
    existingMappings: List<ShortSwipeMapping>,
    onDirectionSelected: (SwipeDirection) -> Unit
) {
    val existingDirections = remember(existingMappings) {
        existingMappings.map { it.direction }.toSet()
    }

    Text(
        text = "Select direction to customize",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Radial layout: 3x3 grid with key in center
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top row: NW, N, NE
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DirectionButton(SwipeDirection.NW, existingDirections, onDirectionSelected)
            DirectionButton(SwipeDirection.N, existingDirections, onDirectionSelected)
            DirectionButton(SwipeDirection.NE, existingDirections, onDirectionSelected)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Middle row: W, [KEY], E
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DirectionButton(SwipeDirection.W, existingDirections, onDirectionSelected)

            // Center key display
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = keyCode.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            DirectionButton(SwipeDirection.E, existingDirections, onDirectionSelected)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom row: SW, S, SE
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DirectionButton(SwipeDirection.SW, existingDirections, onDirectionSelected)
            DirectionButton(SwipeDirection.S, existingDirections, onDirectionSelected)
            DirectionButton(SwipeDirection.SE, existingDirections, onDirectionSelected)
        }
    }
}

@Composable
fun DirectionButton(
    direction: SwipeDirection,
    existingDirections: Set<SwipeDirection>,
    onDirectionSelected: (SwipeDirection) -> Unit
) {
    val hasMapping = direction in existingDirections

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (hasMapping)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 2.dp,
                color = if (hasMapping)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable { onDirectionSelected(direction) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = direction.shortLabel,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = if (hasMapping)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectionEditor(
    keyCode: String,
    direction: SwipeDirection,
    existingMapping: ShortSwipeMapping?,
    onSave: (ShortSwipeMapping) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    var displayText by remember { mutableStateOf(existingMapping?.displayText ?: "") }
    var actionType by remember { mutableStateOf(existingMapping?.actionType ?: ActionType.TEXT) }
    var actionValue by remember { mutableStateOf(existingMapping?.actionValue ?: "") }

    var actionTypeExpanded by remember { mutableStateOf(false) }
    var commandExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Direction indicator
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "${direction.displayName} (${direction.shortLabel})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Display text input
        OutlinedTextField(
            value = displayText,
            onValueChange = { if (it.length <= 4) displayText = it },
            label = { Text("Display Text (max 4 chars)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text("${displayText.length}/4 - shown on key corner")
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Action type dropdown
        ExposedDropdownMenuBox(
            expanded = actionTypeExpanded,
            onExpandedChange = { actionTypeExpanded = it }
        ) {
            OutlinedTextField(
                value = actionType.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Action Type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionTypeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = actionTypeExpanded,
                onDismissRequest = { actionTypeExpanded = false }
            ) {
                ActionType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(type.displayName)
                                Text(
                                    text = type.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            actionType = type
                            actionValue = ""
                            actionTypeExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action value input (depends on type)
        when (actionType) {
            ActionType.TEXT -> {
                OutlinedTextField(
                    value = actionValue,
                    onValueChange = { if (it.length <= 100) actionValue = it },
                    label = { Text("Text to Insert") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${actionValue.length}/100 characters")
                    },
                    maxLines = 3
                )
            }
            ActionType.COMMAND -> {
                ExposedDropdownMenuBox(
                    expanded = commandExpanded,
                    onExpandedChange = { commandExpanded = it }
                ) {
                    OutlinedTextField(
                        value = AvailableCommand.entries.find { it.name == actionValue }?.displayName ?: "Select command...",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Command") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = commandExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = commandExpanded,
                        onDismissRequest = { commandExpanded = false }
                    ) {
                        AvailableCommand.entries.forEach { command ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(command.displayName)
                                        Text(
                                            text = command.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    actionValue = command.name
                                    commandExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            ActionType.KEY_EVENT -> {
                OutlinedTextField(
                    value = actionValue,
                    onValueChange = { actionValue = it.filter { c -> c.isDigit() } },
                    label = { Text("Key Event Code") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Android KeyEvent code (e.g., 67 for DEL)")
                    },
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (existingMapping != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }

            Button(
                onClick = {
                    // Validate and create mapping
                    val finalDisplayText = displayText.ifEmpty {
                        when (actionType) {
                            ActionType.TEXT -> actionValue.take(4)
                            ActionType.COMMAND -> AvailableCommand.entries.find { it.name == actionValue }?.displayName?.take(4) ?: "CMD"
                            ActionType.KEY_EVENT -> "KEY"
                        }
                    }

                    val mapping = ShortSwipeMapping(
                        keyCode = keyCode,
                        direction = direction,
                        displayText = finalDisplayText,
                        actionType = actionType,
                        actionValue = actionValue
                    )
                    onSave(mapping)
                },
                modifier = Modifier.weight(1f),
                enabled = actionValue.isNotEmpty()
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save")
            }
        }
    }
}
