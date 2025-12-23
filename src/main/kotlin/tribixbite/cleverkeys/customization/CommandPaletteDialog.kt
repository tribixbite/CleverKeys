package tribixbite.cleverkeys.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Data class to hold the complete mapping selection with separate label and action.
 */
data class MappingSelection(
    val displayLabel: String,  // What shows on the key (user-customizable)
    val actionType: ActionType,
    val actionValue: String,   // The actual command name or text to insert
    val useKeyFont: Boolean = false  // Whether to use the special keyboard icon font
)

/**
 * Searchable command palette dialog for selecting keyboard commands.
 *
 * Features:
 * - Full text search across command names, descriptions, and keywords
 * - Grouped by category for easy browsing
 * - Shows all 100+ commands from CommandRegistry
 * - Supports both command selection and custom text input
 * - Allows customizing the display label separately from the action
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteDialog(
    onDismiss: () -> Unit,
    onCommandSelected: (CommandRegistry.Command) -> Unit,
    onTextSelected: (String) -> Unit,
    onMappingSelected: ((MappingSelection) -> Unit)? = null, // New callback with full control
    initialSearchQuery: String = ""
) {
    var searchQuery by remember { mutableStateOf(initialSearchQuery) }
    var showTextInput by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    // State for label confirmation dialog
    var pendingCommand by remember { mutableStateOf<CommandRegistry.Command?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var customLabel by remember { mutableStateOf("") }

    // Get filtered commands
    val filteredCommands = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            CommandRegistry.getByCategory()
        } else {
            CommandRegistry.searchRanked(searchQuery).groupBy { it.category }
        }
    }

    // Show label confirmation dialog when pending selection exists
    if (pendingCommand != null || pendingText != null) {
        // Get display info for commands (includes icon and font flag)
        val commandDisplayInfo = pendingCommand?.let {
            CommandRegistry.getDisplayInfo(it.name)
        }

        // Determine if this is an icon-based command
        val isIconMode = commandDisplayInfo?.useKeyFont == true

        LabelConfirmationDialog(
            defaultLabel = when {
                pendingCommand != null -> commandDisplayInfo?.displayText ?: pendingCommand!!.displayName.take(4)
                pendingText != null -> pendingText!!.take(4)
                else -> ""
            },
            actionDescription = when {
                pendingCommand != null -> "Command: ${pendingCommand!!.displayName}"
                pendingText != null -> "Text: \"${pendingText!!.take(30)}${if (pendingText!!.length > 30) "..." else ""}\""
                else -> ""
            },
            currentLabel = customLabel,
            isIconMode = isIconMode,
            iconPreviewText = if (isIconMode) pendingCommand?.displayName else null,
            onLabelChange = { customLabel = it },
            onConfirm = {
                val label = customLabel.ifBlank {
                    when {
                        pendingCommand != null -> commandDisplayInfo?.displayText ?: pendingCommand!!.displayName.take(4)
                        pendingText != null -> pendingText!!.take(4)
                        else -> "?"
                    }
                }

                // Determine if we should use the icon font:
                // - If user customized the label, don't use icon font (they typed text)
                // - If using default label from command, use the command's font setting
                val useIconFont = customLabel.isBlank() && commandDisplayInfo?.useKeyFont == true

                if (onMappingSelected != null) {
                    // Use the new callback with full mapping data
                    val selection = when {
                        pendingCommand != null -> MappingSelection(
                            displayLabel = label,
                            actionType = ActionType.COMMAND,
                            actionValue = pendingCommand!!.name,
                            useKeyFont = useIconFont
                        )
                        pendingText != null -> MappingSelection(
                            displayLabel = label,
                            actionType = ActionType.TEXT,
                            actionValue = pendingText!!,
                            useKeyFont = false  // Text input never uses icon font
                        )
                        else -> null
                    }
                    selection?.let { onMappingSelected(it) }
                } else {
                    // Fallback to legacy callbacks (for backwards compatibility)
                    pendingCommand?.let { onCommandSelected(it) }
                    pendingText?.let { onTextSelected(it) }
                }

                pendingCommand = null
                pendingText = null
                customLabel = ""
            },
            onCancel = {
                pendingCommand = null
                pendingText = null
                customLabel = ""
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                TopAppBar(
                    title = {
                        Text(
                            if (showTextInput) "Enter Custom Text" else "Select Command",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showTextInput) {
                                showTextInput = false
                            } else {
                                onDismiss()
                            }
                        }) {
                            Icon(
                                imageVector = if (showTextInput) Icons.Filled.ArrowBack else Icons.Filled.Close,
                                contentDescription = if (showTextInput) "Back" else "Close"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                if (showTextInput) {
                    // Custom text input mode
                    CustomTextInputSection(
                        text = customText,
                        onTextChange = { customText = it },
                        onConfirm = {
                            if (customText.isNotBlank()) {
                                // Show label confirmation instead of directly calling callback
                                pendingText = customText
                                customLabel = customText.take(4)
                            }
                        }
                    )
                } else {
                    // Command search and list mode
                    CommandSearchSection(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        filteredCommands = filteredCommands,
                        onCommandSelected = { command ->
                            // Show label confirmation instead of directly calling callback
                            pendingCommand = command
                            customLabel = command.displayName.take(4)
                        },
                        onShowTextInput = { showTextInput = true }
                    )
                }
            }
        }
    }
}

/**
 * Dialog for confirming and customizing the display label for a mapping.
 */
@Composable
private fun LabelConfirmationDialog(
    defaultLabel: String,
    actionDescription: String,
    currentLabel: String,
    isIconMode: Boolean,  // Whether the default uses icon font (PUA chars)
    iconPreviewText: String?,  // Readable description to show instead of PUA char
    onLabelChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Customize Label",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    actionDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Determine what to show in the supportingText
                val defaultDescription = if (isIconMode && iconPreviewText != null) {
                    "[${iconPreviewText}]"  // Show readable description for icon
                } else {
                    "\"$defaultLabel\""
                }

                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = { if (it.length <= 8) onLabelChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display Label") },
                    placeholder = {
                        if (isIconMode && iconPreviewText != null) {
                            Text("[Icon: $iconPreviewText]")
                        } else {
                            Text(defaultLabel)
                        }
                    },
                    supportingText = {
                        if (isIconMode) {
                            Text("Leave blank to use default icon. Type text for a custom label.")
                        } else {
                            Text("What shows on the key (max 8 chars). Leave blank for default: $defaultDescription")
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Preview: ", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        // Show readable description for icons, actual text for custom labels
                        val previewText = when {
                            currentLabel.isNotBlank() -> currentLabel
                            isIconMode && iconPreviewText != null -> "[${iconPreviewText}]"
                            else -> defaultLabel
                        }
                        Text(
                            previewText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSearchSection(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filteredCommands: Map<CommandRegistry.Category, List<CommandRegistry.Command>>,
    onCommandSelected: (CommandRegistry.Command) -> Unit,
    onShowTextInput: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search commands (e.g., copy, paste, home, esc)") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        // Quick action: Custom text
        Card(
            onClick = onShowTextInput,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
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
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Custom Text",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        "Enter any text to insert (up to 100 characters)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Stats
        Text(
            text = if (searchQuery.isBlank()) {
                "${CommandRegistry.totalCount} commands available"
            } else {
                "${filteredCommands.values.sumOf { it.size }} results for \"$searchQuery\""
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // Command list grouped by category
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            filteredCommands.toSortedMap(compareBy { it.sortOrder }).forEach { (category, commands) ->
                // Category header
                item(key = "header_${category.name}") {
                    CategoryHeader(category = category, commandCount = commands.size)
                }

                // Commands in category
                items(
                    items = commands,
                    key = { it.name }
                ) { command ->
                    CommandItem(
                        command = command,
                        onClick = { onCommandSelected(command) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: CommandRegistry.Category, commandCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Use available icons from Icons.Filled set
        val icon = when (category) {
            CommandRegistry.Category.CLIPBOARD -> Icons.Filled.List        // Clipboard actions
            CommandRegistry.Category.EDITING -> Icons.Filled.Edit          // Edit actions
            CommandRegistry.Category.CURSOR -> Icons.Filled.KeyboardArrowRight // Cursor movement
            CommandRegistry.Category.NAVIGATION -> Icons.Filled.Menu       // Navigation
            CommandRegistry.Category.SELECTION -> Icons.Filled.Done        // Selection
            CommandRegistry.Category.DELETE -> Icons.Filled.Delete         // Delete
            CommandRegistry.Category.EVENTS -> Icons.Filled.Settings       // Events
            CommandRegistry.Category.MODIFIERS -> Icons.Filled.MoreVert    // Modifiers
            CommandRegistry.Category.FUNCTION_KEYS -> Icons.Filled.Info    // Function keys
            CommandRegistry.Category.SPECIAL_KEYS -> Icons.Filled.Star     // Special keys
            CommandRegistry.Category.SPACES -> Icons.Filled.KeyboardArrowDown // Spaces
            CommandRegistry.Category.DIACRITICS -> Icons.Filled.Favorite   // Diacritics
            CommandRegistry.Category.DIACRITICS_SLAVONIC -> Icons.Filled.Favorite // Slavonic diacritics
            CommandRegistry.Category.DIACRITICS_ARABIC -> Icons.Filled.Favorite   // Arabic diacritics
            CommandRegistry.Category.HEBREW -> Icons.Filled.Favorite       // Hebrew marks
            CommandRegistry.Category.MEDIA -> Icons.Filled.PlayArrow       // Media controls
            CommandRegistry.Category.SYSTEM -> Icons.Filled.Build          // System/app keys
            CommandRegistry.Category.TEXT -> Icons.Filled.Create           // Text
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = category.displayName,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$commandCount",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandItem(
    command: CommandRegistry.Command,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Command name badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = command.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Display name and description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = command.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = command.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                Icons.Filled.Add,
                contentDescription = "Select",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CustomTextInputSection(
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Enter the text that will be inserted when you swipe in this direction.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 100) onTextChange(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to insert") },
            placeholder = { Text("e.g., Hello, World!") },
            supportingText = { Text("${text.length}/100 characters") },
            maxLines = 5,
            minLines = 3
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Quick insert suggestions
        Text(
            "Quick suggestions:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(".", ",", "!", "?", "...", ":-)", "<3").forEach { suggestion ->
                SuggestionChip(
                    onClick = { onTextChange(suggestion) },
                    label = { Text(suggestion) },
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = text.isNotBlank()
        ) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use This Text")
        }
    }
}

/**
 * Simplified command picker for quick access.
 * Shows only commonly used commands without full search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommandPicker(
    onCommandSelected: (CommandRegistry.Command) -> Unit,
    onShowFullPalette: () -> Unit,
    modifier: Modifier = Modifier
) {
    val quickCommands = remember {
        listOf(
            CommandRegistry.getByName("copy"),
            CommandRegistry.getByName("paste"),
            CommandRegistry.getByName("cut"),
            CommandRegistry.getByName("undo"),
            CommandRegistry.getByName("redo"),
            CommandRegistry.getByName("selectAll"),
            CommandRegistry.getByName("home"),
            CommandRegistry.getByName("end"),
            CommandRegistry.getByName("page_up"),
            CommandRegistry.getByName("page_down"),
            CommandRegistry.getByName("tab"),
            CommandRegistry.getByName("esc")
        ).filterNotNull()
    }

    Column(modifier = modifier) {
        Text(
            "Quick Commands",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        quickCommands.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { command ->
                    FilterChip(
                        onClick = { onCommandSelected(command) },
                        label = {
                            Text(
                                command.displayName,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        },
                        selected = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill remaining space if row is incomplete
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onShowFullPalette,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Browse All ${CommandRegistry.totalCount} Commands")
        }
    }
}
