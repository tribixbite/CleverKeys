package tribixbite.cleverkeys

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.customization.*

/**
 * Short Swipe Customization Activity v4
 *
 * Uses the ACTUAL system keyboard by triggering it with a hidden TextField.
 * When the user types a key, we capture it and show the customization modal.
 *
 * Features:
 * - Triggers real CleverKeys IME at the bottom of the screen
 * - Captures key presses and opens customization modal
 * - KeyMagnifierView shows the selected key at ~200% scale with all current mappings
 * - 8-direction tappable zones for adding/editing short swipe gestures
 * - CommandPaletteDialog with searchable list of ALL 100+ keyboard commands
 * - Support for custom text input (up to 100 characters)
 */
class ShortSwipeCustomizationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable the customization mode flag so CleverKeys knows we're in customization
        CleverKeysService.setCustomizationMode(true)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                ShortSwipeCustomizationScreenV4(
                    onBack = {
                        CleverKeysService.setCustomizationMode(false)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CleverKeysService.setCustomizationMode(false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortSwipeCustomizationScreenV4(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ShortSwipeCustomizationManager.getInstance(context) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Load mappings on first composition
    LaunchedEffect(Unit) {
        manager.loadMappings()
    }

    // Observe mappings
    val mappings by manager.mappingsFlow.collectAsState()

    // Text field state for capturing key presses
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Selected key for customization modal - now includes both keyCode and optional KeyboardData.Key
    var selectedKeyCode by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf<KeyboardData.Key?>(null) }
    var selectedKeyRowHeight by remember { mutableStateOf(1.0f) }

    // Direction being edited
    var editingDirection by remember { mutableStateOf<SwipeDirection?>(null) }

    // Show command palette
    var showCommandPalette by remember { mutableStateOf(false) }

    // Track last captured key for customization
    var lastCapturedKey by remember { mutableStateOf<String?>(null) }

    // Request focus when the screen opens to show keyboard
    LaunchedEffect(Unit) {
        delay(300) // Small delay to let the UI settle
        focusRequester.requestFocus()
        // Show the soft keyboard
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    // Monitor text changes to detect key presses
    LaunchedEffect(textFieldValue.text) {
        val text = textFieldValue.text
        if (text.isNotEmpty()) {
            // Get the last character typed
            val lastChar = text.last().toString()
            lastCapturedKey = lastChar.lowercase()
            selectedKeyCode = lastChar.lowercase()

            // Try to find the full KeyboardData.Key from the loaded layout
            val foundKey = CleverKeysService.findKeyByChar(lastChar)
            selectedKey = foundKey
            if (foundKey != null) {
                selectedKeyRowHeight = CleverKeysService.getRowHeightForKey(foundKey)
            }

            // Clear the text field
            textFieldValue = TextFieldValue("")
        }
    }

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

            // Hidden text field to capture keyboard input
            // This is invisible but captures key presses
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Transparent),
                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp)
            )

            // Instruction area (takes remaining space above keyboard)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Type any key to customize it",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The keyboard below is your actual CleverKeys keyboard.\nTap a key to add short swipe actions to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    if (lastCapturedKey != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Last key: ${lastCapturedKey?.uppercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Key customization modal
        selectedKeyCode?.let { keyCode ->
            val keyMappings = mappings.filter { it.keyCode == keyCode }
                .associateBy { it.direction }

            KeyCustomizationDialog(
                keyCode = keyCode,
                key = selectedKey,  // Pass the full key if available
                rowHeight = selectedKeyRowHeight,
                existingMappings = keyMappings,
                onDismiss = {
                    selectedKeyCode = null
                    selectedKey = null
                    editingDirection = null
                    // Refocus the text field to keep keyboard visible
                    scope.launch {
                        delay(100)
                        focusRequester.requestFocus()
                    }
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
 * Key customization dialog that shows the magnified key with all 8 direction zones.
 * If a KeyboardData.Key is available, it shows the full layout-defined sub-labels.
 * Otherwise, falls back to showing just the key code with custom mappings.
 *
 * @param keyCode The key code string (e.g., "h")
 * @param key Optional full KeyboardData.Key from the loaded layout
 * @param rowHeight The row height for proper aspect ratio
 * @param existingMappings Custom short swipe mappings for this key
 * @param onDismiss Called when dialog is dismissed
 * @param onDirectionTapped Called when a direction zone is tapped
 * @param onDeleteMapping Called when a mapping should be deleted
 */
@Composable
fun KeyCustomizationDialog(
    keyCode: String,
    key: KeyboardData.Key?,
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
                    text = if (key != null) {
                        "Shows existing layout mappings + custom mappings"
                    } else {
                        "Tap a direction to add or edit a short swipe gesture"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Magnified key view - uses full Key if available
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
                    // Use the KeyMagnifierView for directional zones
                    AndroidView(
                        factory = { ctx ->
                            KeyMagnifierView(ctx).apply {
                                // Use full key if available, otherwise fall back to keyCode mode
                                if (key != null) {
                                    setKey(key, existingMappings, rowHeight)
                                } else {
                                    setKeyCode(keyCode, existingMappings)
                                }
                                this.onDirectionTapped = { direction ->
                                    selectedDirection = direction
                                    onDirectionTapped(direction)
                                }
                            }
                        },
                        update = { view ->
                            if (key != null) {
                                view.setKey(key, existingMappings, rowHeight)
                            } else {
                                view.setKeyCode(keyCode, existingMappings)
                            }
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
