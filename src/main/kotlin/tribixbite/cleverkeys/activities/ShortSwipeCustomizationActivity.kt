package tribixbite.cleverkeys

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.customization.*
import tribixbite.cleverkeys.theme.CleverKeysTheme
import android.graphics.Typeface

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
            // WP8: unified under CleverKeysTheme (branded scheme + keyboard type/shape scale),
            // matching its sibling settings activities. Follows the system day/night setting.
            CleverKeysTheme {
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
    val rootView = LocalView.current

    // Load mappings on first composition
    LaunchedEffect(Unit) {
        manager.loadMappings()
    }

    // Observe mappings
    val mappings by manager.mappingsFlow.collectAsState()

    // Text field state for capturing key presses
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Selected key for customization modal - now includes both keyCode and optional KeyboardData.Key
    // Saveable (with the direction and the palette flag): rotating with the palette open keeps it
    // open on the same key and direction (audit 2026-10-08). The Key itself is display-only.
    var selectedKeyCode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf<KeyboardData.Key?>(null) }
    var selectedKeyRowHeight by remember { mutableFloatStateOf(1.0f) }

    // Direction being edited
    var editingDirection by rememberSaveable { mutableStateOf<SwipeDirection?>(null) }

    // Show command palette
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }

    // Track last captured key for customization
    var lastCapturedKey by remember { mutableStateOf<String?>(null) }

    // Request focus when the screen opens to show keyboard
    LaunchedEffect(Unit) {
        delay(300) // Small delay to let the UI settle
        focusRequester.requestFocus()
        // #134: showSoftInput, not toggleSoftInput — toggle HIDES an already-visible IME
        // (the reported vanish, at entry) and SHOW_FORCED leaks a forced-shown keyboard
        // past this activity (both deprecated since API 31). Same mechanism as the
        // TopAppBar reopen button below, which is on-device verified.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(rootView, 0)
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

    // Hoisted out of the semantics {} lambda (not a composable scope).
    val showKeyboardDesc = stringResource(R.string.short_swipe_show_keyboard)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.short_swipe_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // #134: Show keyboard button — re-opens the IME if it lost focus
                    IconButton(
                        onClick = {
                            focusRequester.requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(rootView, 0)
                        },
                        modifier = Modifier.semantics { contentDescription = showKeyboardDesc }
                    ) {
                        Text("⌨", fontSize = 22.sp)
                    }
                    // Reset all button
                    IconButton(
                        onClick = {
                            scope.launch {
                                manager.resetAll()
                                Toast.makeText(context, R.string.short_swipe_toast_all_reset, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.short_swipe_reset_all))
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
                        text = stringResource(R.string.short_swipe_tap_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.short_swipe_mappings_summary,
                            mappings.size,
                            mappings.size,
                            CommandRegistry.totalCount
                        ),
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
                        text = stringResource(R.string.short_swipe_instruction_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.short_swipe_instruction_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    if (lastCapturedKey != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.short_swipe_last_key, lastCapturedKey?.uppercase().orEmpty()),
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
                    android.util.Log.d("ShortSwipe", "Direction tapped: $direction, showing command palette")
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
        // Uses onMappingSelected to get both custom label and action separately
        if (showCommandPalette && selectedKeyCode != null && editingDirection != null) {
            CommandPaletteDialog(
                onDismiss = {
                    showCommandPalette = false
                    editingDirection = null
                },
                onMappingSelected = { selection ->
                    // This callback receives separate label and action from the dialog
                    scope.launch {
                        // Shared with the subkey popover's assign screen (ShortSwipeAssignment).
                        ShortSwipeAssignment.apply(context, manager, selectedKeyCode!!, editingDirection!!, selection)
                        showCommandPalette = false
                        editingDirection = null
                    }
                },
                subtitle = stringResource(
                    R.string.subkey_assign_title,
                    selectedKeyCode!!.uppercase(),
                    stringResource(editingDirection!!.displayNameRes)
                ),
                // An existing mapping opens in its own editor, filled in (shared with the
                // subkey popover's edit screen); backing out of it reaches the full list.
                initialMapping = mappings.firstOrNull {
                    it.keyCode == selectedKeyCode && it.direction == editingDirection
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
@OptIn(ExperimentalComposeUiApi::class)
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
                    text = stringResource(R.string.short_swipe_customize_key, keyCode.uppercase()),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (key != null) {
                        stringResource(R.string.short_swipe_dialog_hint_with_layout)
                    } else {
                        stringResource(R.string.short_swipe_dialog_hint_no_layout)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Magnified key view - uses full Key if available
                // Calculate aspect ratio for the container: keyWidth / rowHeight
                // Keys are typically slightly taller than wide (e.g., 1.0 width / 1.1 height = 0.91)
                val keyAspectRatio = if (key != null && rowHeight > 0) {
                    key.width / rowHeight
                } else {
                    0.85f // Default: keys are typically slightly taller than wide
                }

                // Use Compose-based direction selection instead of AndroidView touch handling
                // This ensures reliable touch events in Dialog context
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Use the KeyMagnifierView for visual display only
                    AndroidView(
                        factory = { ctx ->
                            KeyMagnifierView(ctx).apply {
                                // Use full key if available, otherwise fall back to keyCode mode
                                if (key != null) {
                                    setKey(key, existingMappings, rowHeight)
                                } else {
                                    setKeyCode(keyCode, existingMappings)
                                }
                                // Touch handling is done via Compose overlay below
                            }
                        },
                        update = { view ->
                            if (key != null) {
                                view.setKey(key, existingMappings, rowHeight)
                            } else {
                                view.setKeyCode(keyCode, existingMappings)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(keyAspectRatio)
                            .padding(16.dp)
                    )

                    // Compose-based touch overlay for reliable direction detection
                    // This overlays the AndroidView and handles all touch events
                    DirectionTouchOverlay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(keyAspectRatio)
                            .padding(16.dp),
                        onDirectionTapped = { direction ->
                            selectedDirection = direction
                            onDirectionTapped(direction)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current mappings list
                if (existingMappings.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.short_swipe_custom_mappings),
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
                    Text(stringResource(R.string.common_close))
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
    val context = LocalContext.current

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
                    text = stringResource(swipeDirectionShortRes(direction)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Mapping info
            Column(modifier = Modifier.weight(1f)) {
                // Use AndroidView with special font for icon characters when useKeyFont is true
                if (mapping.useKeyFont) {
                    // Show icon using special font via AndroidView, at the keyboard's icon
                    // factor against the row's 13 sp label text (SubLabelSizing)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "\"",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        AndroidView(
                            factory = { ctx ->
                                android.widget.TextView(ctx).apply {
                                    typeface = Theme.getKeyFont(ctx)
                                    setTextSize(
                                        android.util.TypedValue.COMPLEX_UNIT_SP,
                                        SubLabelSizing.previewSp(13f, true, mapping.displayText)
                                    )
                                    setTextColor(android.graphics.Color.WHITE)
                                    text = mapping.displayText
                                }
                            },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Text(
                            text = "\"",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        text = "\"${mapping.displayText}\"",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
                val actionDesc = remember(mapping.actionType, mapping.actionValue, context) {
                    shortSwipeActionDescription(context, mapping.actionType, mapping.actionValue)
                }
                Text(
                    text = actionDesc,
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
                    contentDescription = stringResource(R.string.common_delete),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Compose-based touch overlay for direction detection.
 * This overlays the KeyMagnifierView and handles touch events reliably in Dialog context.
 * Divides the area into 9 zones: 8 directions + center.
 */
@Composable
private fun DirectionTouchOverlay(
    modifier: Modifier = Modifier,
    onDirectionTapped: (SwipeDirection) -> Unit
) {
    Box(modifier = modifier) {
        // Create a 3x3 grid of clickable zones
        Column(modifier = Modifier.fillMaxSize()) {
            // Top row: NW, N, NE
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DirectionZone(SwipeDirection.NW, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
                DirectionZone(SwipeDirection.N, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
                DirectionZone(SwipeDirection.NE, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
            }
            // Middle row: W, Center (no action), E
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DirectionZone(SwipeDirection.W, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
                // Center zone - no action, just transparent
                Box(modifier = Modifier.weight(1f).fillMaxHeight())
                DirectionZone(SwipeDirection.E, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
            }
            // Bottom row: SW, S, SE
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DirectionZone(SwipeDirection.SW, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
                DirectionZone(SwipeDirection.S, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
                DirectionZone(SwipeDirection.SE, Modifier.weight(1f).fillMaxHeight(), onDirectionTapped)
            }
        }
    }
}

/**
 * Individual clickable zone for a direction.
 * Uses distinct colors to make each zone visually identifiable.
 */
@Composable
private fun DirectionZone(
    direction: SwipeDirection,
    modifier: Modifier = Modifier,
    onTapped: (SwipeDirection) -> Unit
) {
    // Assign distinct colors to each direction for visual identification
    val zoneColor = when (direction) {
        SwipeDirection.NW -> Color(0x40FF6B6B)  // Red-ish
        SwipeDirection.N  -> Color(0x404ECDC4)  // Teal
        SwipeDirection.NE -> Color(0x40FFE66D)  // Yellow
        SwipeDirection.W  -> Color(0x4095E1D3)  // Mint
        SwipeDirection.E  -> Color(0x40F38181)  // Coral
        SwipeDirection.SW -> Color(0x40AA96DA)  // Purple
        SwipeDirection.S  -> Color(0x4072D4E8)  // Cyan
        SwipeDirection.SE -> Color(0x40FCBAD3)  // Pink
    }

    Box(
        modifier = modifier
            .background(zoneColor)
            .clickable(
                // Disable ripple to avoid visual noise
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) {
                android.util.Log.d("DirectionZone", "Tapped direction: $direction")
                onTapped(direction)
            },
        contentAlignment = Alignment.Center
    ) {
        // Show direction label in each zone
        Text(
            text = stringResource(swipeDirectionShortRes(direction)),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/** Longest text-action value quoted in a mapping summary before it is truncated with "…". */
private const val ACTION_TEXT_PREVIEW_CHARS = 20

/**
 * Localized one-line summary of a short-swipe action ("Command: copy", "Text: "hi"", …),
 * shared by the mapping card and the "Mapped …" confirmation toast.
 *
 * An INTENT value is the JSON of an [IntentDefinition]; when it does not parse, the summary
 * says so instead of showing raw JSON.
 */
internal fun shortSwipeActionDescription(context: Context, type: ActionType, value: String): String =
    when (type) {
        ActionType.COMMAND -> context.getString(R.string.short_swipe_action_desc_command, value)
        ActionType.TEXT -> context.getString(
            R.string.short_swipe_action_desc_text,
            if (value.length > ACTION_TEXT_PREVIEW_CHARS) value.take(ACTION_TEXT_PREVIEW_CHARS) + "…" else value
        )
        ActionType.KEY_EVENT -> context.getString(R.string.short_swipe_action_desc_key_event, value)
        ActionType.INTENT -> try {
            val def = com.google.gson.Gson().fromJson(value, IntentDefinition::class.java)
            context.getString(R.string.short_swipe_action_desc_intent, def.name)
        } catch (e: Exception) {
            context.getString(R.string.short_swipe_action_desc_intent_invalid)
        }
        ActionType.TEMPLATE -> context.getString(R.string.command_palette_template_title)
        ActionType.TIMESTAMP -> context.getString(R.string.short_swipe_action_desc_timestamp, value)
    }

/** Localized compass abbreviation ("N", "NE", …) for the compact direction badges and zones. */
@androidx.annotation.StringRes
internal fun swipeDirectionShortRes(direction: SwipeDirection): Int = when (direction) {
    SwipeDirection.N -> R.string.short_swipe_direction_short_n
    SwipeDirection.NE -> R.string.short_swipe_direction_short_ne
    SwipeDirection.E -> R.string.short_swipe_direction_short_e
    SwipeDirection.SE -> R.string.short_swipe_direction_short_se
    SwipeDirection.S -> R.string.short_swipe_direction_short_s
    SwipeDirection.SW -> R.string.short_swipe_direction_short_sw
    SwipeDirection.W -> R.string.short_swipe_direction_short_w
    SwipeDirection.NW -> R.string.short_swipe_direction_short_nw
}
