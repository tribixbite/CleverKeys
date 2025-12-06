package tribixbite.cleverkeys

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.customization.*

/**
 * Short Swipe Customization Activity v3
 *
 * Complete rewrite that uses the ACTUAL keyboard view for preview.
 *
 * Features:
 * - Real Keyboard2View at the bottom showing the user's actual themed keyboard
 * - Tap any key to open a magnified customization modal
 * - KeyMagnifierView shows the selected key at ~200% scale with all current mappings
 * - 8-direction tappable zones for adding/editing short swipe gestures
 * - CommandPaletteDialog with searchable list of ALL 100+ keyboard commands
 * - Support for custom text input (up to 100 characters)
 */
class ShortSwipeCustomizationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                ShortSwipeCustomizationScreenV3(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortSwipeCustomizationScreenV3(onBack: () -> Unit) {
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
    var selectedKey by remember { mutableStateOf<KeyboardData.Key?>(null) }
    var selectedKeyCode by remember { mutableStateOf<String?>(null) }
    var selectedKeyRowHeight by remember { mutableStateOf(1.0f) }

    // Direction being edited
    var editingDirection by remember { mutableStateOf<SwipeDirection?>(null) }

    // Show command palette
    var showCommandPalette by remember { mutableStateOf(false) }

    // Keyboard preview host reference
    var keyboardPreviewHost by remember { mutableStateOf<KeyboardPreviewHost?>(null) }

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
        ) {
            // Info card at top
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Tap any key below to customize its short swipe gestures",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${mappings.size} custom mappings • ${CommandRegistry.totalCount} commands available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // Keyboard preview takes remaining space
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AndroidView(
                    factory = { ctx ->
                        KeyboardPreviewHost(ctx).apply {
                            keyboardPreviewHost = this
                            previewMode = true
                            // Use extended callback with row height for proper aspect ratio
                            onKeyTappedWithRowHeight = { key, rowHeight ->
                                selectedKey = key
                                selectedKeyCode = getKeyCodeFromKey(key)
                                selectedKeyRowHeight = rowHeight
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Key customization modal
        selectedKey?.let { key ->
            val keyCode = selectedKeyCode ?: return@let
            val keyMappings = mappings.filter { it.keyCode == keyCode }
                .associateBy { it.direction }

            KeyCustomizationDialogV3(
                key = key,
                keyCode = keyCode,
                rowHeight = selectedKeyRowHeight,
                existingMappings = keyMappings,
                onDismiss = {
                    selectedKey = null
                    selectedKeyCode = null
                    editingDirection = null
                },
                onDirectionTapped = { direction ->
                    editingDirection = direction
                    showCommandPalette = true
                },
                onDeleteMapping = { direction ->
                    scope.launch {
                        manager.removeMapping(keyCode, direction)
                    }
                }
            )
        }

        // Command palette for selecting action
        if (showCommandPalette && selectedKeyCode != null && editingDirection != null) {
            CommandPaletteDialog(
                onDismiss = {
                    showCommandPalette = false
                    editingDirection = null
                },
                onCommandSelected = { command ->
                    scope.launch {
                        val mapping = ShortSwipeMapping(
                            keyCode = selectedKeyCode!!,
                            direction = editingDirection!!,
                            displayText = command.displayName.take(4),
                            actionType = ActionType.COMMAND,
                            actionValue = command.name
                        )
                        manager.setMapping(mapping)
                        showCommandPalette = false
                        editingDirection = null
                        Toast.makeText(context, "Mapped ${editingDirection!!.displayName} to ${command.displayName}", Toast.LENGTH_SHORT).show()
                    }
                },
                onTextSelected = { text ->
                    scope.launch {
                        val mapping = ShortSwipeMapping(
                            keyCode = selectedKeyCode!!,
                            direction = editingDirection!!,
                            displayText = text.take(4),
                            actionType = ActionType.TEXT,
                            actionValue = text
                        )
                        manager.setMapping(mapping)
                        showCommandPalette = false
                        editingDirection = null
                        Toast.makeText(context, "Mapped ${editingDirection!!.displayName} to text: \"$text\"", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

/**
 * Get a simple key code string from a KeyboardData.Key for storage.
 */
private fun getKeyCodeFromKey(key: KeyboardData.Key): String? {
    val mainKey = key.keys[0] ?: return null

    return when (mainKey.getKind()) {
        KeyValue.Kind.Char -> mainKey.getChar().lowercaseChar().toString()
        KeyValue.Kind.String -> mainKey.getString().lowercase()
        KeyValue.Kind.Event -> mainKey.getEvent().name.lowercase()
        KeyValue.Kind.Keyevent -> "keyevent_${mainKey.getKeyevent()}"
        KeyValue.Kind.Modifier -> mainKey.getModifier().name.lowercase()
        KeyValue.Kind.Editing -> mainKey.getEditing().name.lowercase()
        else -> mainKey.getString().lowercase().ifEmpty { null }
    }
}

/**
 * Key customization dialog showing magnified key with 8-direction zones.
 *
 * @param key The key to customize
 * @param keyCode The key code identifier
 * @param rowHeight The row height in keyboard units (for proper aspect ratio)
 * @param existingMappings Existing custom mappings for this key
 * @param onDismiss Called when dialog is dismissed
 * @param onDirectionTapped Called when a direction zone is tapped
 * @param onDeleteMapping Called when a mapping should be deleted
 */
@Composable
fun KeyCustomizationDialogV3(
    key: KeyboardData.Key,
    keyCode: String,
    rowHeight: Float = 1.0f,
    existingMappings: Map<SwipeDirection, ShortSwipeMapping>,
    onDismiss: () -> Unit,
    onDirectionTapped: (SwipeDirection) -> Unit,
    onDeleteMapping: (SwipeDirection) -> Unit
) {
    var selectedDirection by remember { mutableStateOf<SwipeDirection?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = "Customize \"${keyCode.uppercase()}\" Key",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Tap a direction to add or edit a short swipe gesture",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Magnified key view with 8-direction zones
                // Use fillMaxWidth with fixed max height to maintain proper aspect ratio
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .padding(horizontal = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            KeyMagnifierView(ctx).apply {
                                // Pass row height for proper aspect ratio calculation
                                setKey(key, existingMappings, rowHeight)
                                this.onDirectionTapped = { direction ->
                                    selectedDirection = direction
                                    onDirectionTapped(direction)
                                }
                            }
                        },
                        update = { view ->
                            // Pass row height when updating too
                            view.setKey(key, existingMappings, rowHeight)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current mappings list
                if (existingMappings.isNotEmpty()) {
                    Text(
                        text = "Custom Mappings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    existingMappings.forEach { (direction, mapping) ->
                        MappingListItem(
                            direction = direction,
                            mapping = mapping,
                            onEdit = { onDirectionTapped(direction) },
                            onDelete = { onDeleteMapping(direction) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Close button
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingListItem(
    direction: SwipeDirection,
    mapping: ShortSwipeMapping,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction badge
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = direction.shortLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Mapping info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\"${mapping.displayText}\"",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Text(
                    text = "${mapping.actionType.displayName}: ${mapping.actionValue.take(30)}${if (mapping.actionValue.length > 30) "..." else ""}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
