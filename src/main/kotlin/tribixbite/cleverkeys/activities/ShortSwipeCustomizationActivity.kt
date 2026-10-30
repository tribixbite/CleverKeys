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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.toArgb
import tribixbite.cleverkeys.a11y.KeyLabels
import tribixbite.cleverkeys.a11y.ShortSwipeAccessibilityActions
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
    val rootView = LocalView.current
    val layoutEditor = remember {
        LayoutSubkeyEditor(
            DirectBootAwarePreferences.get_shared_preferences(context),
            context.resources,
            resolveSystemName = { CleverKeysService.layoutSourceSnapshot()?.builtInName },
        )
    }
    // Saving, removing and restoring a slot: shared with the subkey popover's assign screen.
    val slotActions = remember { SubkeySlotActions(context, manager, layoutEditor) }

    // Load mappings on first composition
    LaunchedEffect(Unit) {
        manager.loadMappings()
    }

    // Observe mappings
    val mappings by manager.mappingsFlow.collectAsState()

    // Text field state for capturing key presses
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // The captured key: the TEXT it typed (saveable, to find the key again after rotation), its
    // runtime short-swipe code (ShortSwipeKeyCodes — Enter is its glyph, not "\n"), the live key
    // and where its subkeys come from (SubkeyKeyContext). Saveable with the direction and the
    // palette flags: rotating with the palette open keeps it open (audit 2026-10-08).
    var selectedTyped by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKeyCode by rememberSaveable { mutableStateOf<String?>(null) }
    var keyContext by remember { mutableStateOf<SubkeyKeyContext?>(null) }

    // Direction being edited, and whether the edit goes into the layout (copy) or a custom mapping
    var editingDirection by rememberSaveable { mutableStateOf<SwipeDirection?>(null) }
    var editTarget by rememberSaveable { mutableStateOf(SlotEditTarget.GLOBAL) }

    // Show command palette
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }

    // Track last captured key for customization
    var lastCapturedKey by remember { mutableStateOf<String?>(null) }

    fun openKey(typed: String) {
        val liveKey = CleverKeysService.findKeyByChar(typed)
        val code = ShortSwipeKeyCodes.ofKey(liveKey?.keys?.getOrNull(0)) ?: ShortSwipeKeyCodes.canonical(typed)
        selectedTyped = typed
        selectedKeyCode = code
        keyContext = SubkeyKeyContext.resolve(context, layoutEditor, liveKey)
        lastCapturedKey = ShortSwipeKeyCodes.displayName(code, liveKey?.keys?.getOrNull(0), context::getString)
    }

    // After rotation the saveable key text survives but the live key does not: find it again.
    LaunchedEffect(selectedTyped) {
        val typed = selectedTyped
        if (typed != null && keyContext == null) openKey(typed)
    }

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
            // The last code point typed (an emoji is two chars). Enter types "\n" and Space " ":
            // openKey maps them to their keys, never to a mapping stored under the raw text.
            val lastChar = String(Character.toChars(text.codePointBefore(text.length)))
            openKey(lastChar)
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
                            text = stringResource(R.string.short_swipe_last_key, lastCapturedKey.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Key customization modal
        selectedKeyCode?.let { keyCode ->
            val ctx = keyContext
            val keyMappings = mappings.filter { it.keyCode == keyCode }.associateBy { it.direction }
            val slots = remember(ctx, keyMappings) {
                KeySubkeySources.resolve(ctx?.definedKey, ctx?.layoutOwned == true, ctx?.liveKey, keyMappings)
            }
            val title = ShortSwipeKeyCodes.displayName(keyCode, ctx?.liveKey?.keys?.getOrNull(0), context::getString)

            KeyCustomizationDialog(
                title = title,
                key = ctx?.liveKey,
                rowHeight = ctx?.rowHeight ?: 1.0f,
                slots = slots,
                layoutName = ctx?.layoutName,
                copyName = ctx?.copyName,
                onDismiss = {
                    selectedKeyCode = null
                    selectedTyped = null
                    keyContext = null
                    editingDirection = null
                    // Refocus the text field to keep keyboard visible
                    scope.launch {
                        delay(100)
                        focusRequester.requestFocus()
                    }
                },
                onEditSlot = { direction, target ->
                    editingDirection = direction
                    editTarget = target
                    showCommandPalette = true
                },
                onRemoveSlot = { direction, target ->
                    val slot = slots.firstOrNull { it.direction == direction }
                    if (slot != null) scope.launch {
                        slotActions.remove(ctx, keyCode, slot, target)?.let { keyContext = it }
                    }
                },
                onRestoreSlot = { direction ->
                    val slot = slots.firstOrNull { it.direction == direction }
                    if (slot != null) scope.launch { slotActions.restore(keyCode, slot) }
                }
            )
        }

        // Command palette for selecting action
        // Uses onMappingSelected to get both custom label and action separately
        val paletteKeyCode = selectedKeyCode
        val paletteDirection = editingDirection
        if (showCommandPalette && paletteKeyCode != null && paletteDirection != null) {
            CommandPaletteDialog(
                onDismiss = {
                    showCommandPalette = false
                    editingDirection = null
                },
                onMappingSelected = { selection ->
                    scope.launch {
                        // Layout or custom mapping (SubkeyEditPolicy.save), shared with the popover.
                        slotActions.save(keyContext, paletteKeyCode, paletteDirection, editTarget, selection)
                            ?.let { keyContext = it }
                        showCommandPalette = false
                        editingDirection = null
                    }
                },
                subtitle = stringResource(
                    R.string.subkey_assign_title,
                    ShortSwipeKeyCodes.displayName(paletteKeyCode, keyContext?.liveKey?.keys?.getOrNull(0), context::getString),
                    stringResource(paletteDirection.displayNameRes)
                ),
                // An existing custom mapping opens in its own editor, filled in (shared with the
                // subkey popover's edit screen); backing out of it reaches the full list. A layout
                // subkey starts from the list.
                initialMapping = if (editTarget == SlotEditTarget.LAYOUT) null else mappings.firstOrNull {
                    it.keyCode == paletteKeyCode && it.direction == paletteDirection
                }
            )
        }
    }
}


/**
 * Per-key customization dialog: the magnified key, then every direction with what a swipe that
 * way does now and where that comes from (Layout / Built-in / Extra key / Custom / Removed —
 * [SubkeySource]); a custom mapping that replaces a layout subkey says which.
 *
 * Changing or removing a LAYOUT subkey writes the layout itself: when [copyName] is set (a
 * built-in layout) the dialog first asks to create that modified copy, or to change the key in
 * all layouts instead (a custom mapping, as before). Every other edit is a custom mapping.
 *
 * @param title the key's display name (ShortSwipeKeyCodes.displayName — never a raw control char)
 * @param key the live key for the magnifier, or null (then [title] is drawn)
 * @param layoutName the name of the layout that owns LAYOUT slots
 * @param copyName the name of the copy a LAYOUT edit creates, or null to edit in place
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyCustomizationDialog(
    title: String,
    key: KeyboardData.Key?,
    rowHeight: Float = 1.0f,
    slots: List<SubkeySlot>,
    layoutName: String?,
    copyName: String?,
    onDismiss: () -> Unit,
    onEditSlot: (SwipeDirection, SlotEditTarget) -> Unit,
    onRemoveSlot: (SwipeDirection, SlotEditTarget) -> Unit,
    onRestoreSlot: (SwipeDirection) -> Unit,
) {
    // A LAYOUT edit of a built-in layout waiting for the copy confirmation: direction + remove?
    var pendingCopy by rememberSaveable { mutableStateOf<Pair<SwipeDirection, Boolean>?>(null) }
    val customMappings = remember(slots) { slots.mapNotNull { s -> s.custom?.let { s.direction to it } }.toMap() }

    fun go(direction: SwipeDirection, remove: Boolean, target: SlotEditTarget) =
        if (remove) onRemoveSlot(direction, target) else onEditSlot(direction, target)

    /** Route an edit (or removal) of [slot] by the shared rule ([SubkeyEditPolicy.route]). */
    fun route(slot: SubkeySlot, remove: Boolean) {
        when (SubkeyEditPolicy.route(slot, copyNeeded = copyName != null)) {
            SubkeyEditPolicy.Route.ASK_COPY -> pendingCopy = slot.direction to remove
            SubkeyEditPolicy.Route.LAYOUT -> go(slot.direction, remove, SlotEditTarget.LAYOUT)
            SubkeyEditPolicy.Route.GLOBAL -> go(slot.direction, remove, SlotEditTarget.GLOBAL)
        }
    }

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
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = stringResource(R.string.short_swipe_customize_key, title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (layoutName != null) {
                        stringResource(R.string.subkey_dialog_layout_hint, layoutName)
                    } else {
                        stringResource(R.string.short_swipe_dialog_hint_no_layout)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

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
                        factory = { ctx -> KeyMagnifierView(ctx) },
                        update = { view ->
                            // The live key (layout + Extra Keys + custom); without one, the key's
                            // NAME — a raw glyph code drawn in a text font is an unrelated character.
                            if (key != null) view.setKey(key, customMappings, rowHeight)
                            else view.setKeyCode(title, customMappings)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(keyAspectRatio)
                            .padding(16.dp)
                    )

                    // Compose-based touch overlay for reliable direction detection
                    // This overlays the AndroidView and handles all touch events
                    val overlayContext = LocalContext.current
                    val overlayLabelColor = remember { Color(KeyMagnifierView.overlayLabelColor(overlayContext)) }
                    DirectionTouchOverlay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(keyAspectRatio)
                            .padding(16.dp),
                        labelColor = overlayLabelColor,
                        onDirectionTapped = { direction ->
                            slots.firstOrNull { it.direction == direction }?.let { route(it, remove = false) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.subkey_dialog_subkeys_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                slots.forEach { slot ->
                    SubkeySlotItem(
                        slot = slot,
                        onEdit = { route(slot, remove = false) },
                        onRemove = { route(slot, remove = true) },
                        onRestore = { onRestoreSlot(slot.direction) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Close button
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }

    // Composed AFTER the parent Dialog so it stacks above it (CLAUDE.md "Compose dialog stacking").
    pendingCopy?.let { (direction, remove) ->
        LayoutCopyQuestionDialog(
            layoutName = layoutName.orEmpty(),
            copyName = copyName.orEmpty(),
            onAnswer = { target ->
                pendingCopy = null
                go(direction, remove, target)
            },
            onCancel = { pendingCopy = null },
        )
    }
}

/**
 * One direction of the per-key dialog: what it does now, a badge saying where that comes from,
 * and (for a custom mapping or removal over a layout subkey) what it replaces. Tap to change;
 * the trailing button removes it, or restores what a custom mapping hides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubkeySlotItem(
    slot: SubkeySlot,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onRestore: () -> Unit,
) {
    val context = LocalContext.current
    val getString: (Int) -> String = context::getString
    val source = slot.source
    val custom = slot.custom
    val shown = slot.shownValue ?: slot.definedValue
    val sourceLabel = stringResource(source.labelRes)
    val directionName = stringResource(slot.direction.displayNameRes)

    // What a swipe this way does now, in words (a key-font glyph is never read out or printed).
    val what: String = when {
        custom != null && custom.isRemoval -> stringResource(R.string.subkey_slot_nothing)
        custom != null -> ShortSwipeAccessibilityActions.describeMapping(custom, getString)
        shown != null -> KeyLabels.describe(shown, getString)
        else -> stringResource(R.string.subkey_slot_nothing)
    }
    // A custom mapping (or removal) hides this layout or built-in subkey.
    val replaced = slot.shadowedValue?.let { KeyLabels.describe(it, getString) }
    val replacedText = replaced?.let { stringResource(R.string.subkey_slot_replaces, it) }
    val a11y = listOfNotNull(directionName, what, sourceLabel, replacedText).joinToString(", ")

    Card(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        colors = CardDefaults.cardColors(
            containerColor = if (source == SubkeySource.NONE) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            }
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
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(swipeDirectionShortRes(slot.direction)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // The slot's label as the key draws it (key font for icons), when it has one
            SlotLabel(slot)

            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                Text(text = what, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 2)
                if (custom != null && !custom.isRemoval) {
                    val actionDesc = remember(custom.actionType, custom.actionValue, context) {
                        shortSwipeActionDescription(context, custom.actionType, custom.actionValue)
                    }
                    Text(text = actionDesc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (replacedText != null) {
                    Text(text = replacedText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }

            SubkeySourceBadge(source)

            // Trailing action: restore what a custom mapping hides, delete a custom mapping, or
            // remove the subkey (a layout subkey via the layout; others with a `removed` blank).
            when (source) {
                SubkeySource.CUSTOM, SubkeySource.REMOVED -> IconButton(
                    onClick = onRestore,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (slot.shadowedValue != null) Icons.Filled.Refresh else Icons.Filled.Delete,
                        contentDescription = stringResource(
                            if (slot.shadowedValue != null) R.string.subkey_edit_restore else R.string.common_delete
                        ),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                SubkeySource.NONE -> Spacer(modifier = Modifier.size(36.dp))
                else -> IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.subkey_edit_remove),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** The slot's on-key label, drawn in the key font when it is an icon; nothing for an empty slot. */
@Composable
private fun SlotLabel(slot: SubkeySlot) {
    val custom = slot.custom
    val (label, keyFont) = when {
        custom != null && custom.isRemoval -> return
        custom != null -> custom.displayText to custom.useKeyFont
        else -> {
            val kv = slot.shownValue ?: slot.definedValue ?: return
            kv.getString().take(4) to kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)
        }
    }
    if (label.isBlank()) return
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    Box(modifier = Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.Center) {
        if (keyFont) {
            AndroidView(
                factory = { ctx -> android.widget.TextView(ctx) },
                update = { tv ->
                    tv.typeface = Theme.getKeyFont(tv.context)
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SubLabelSizing.previewSp(15f, true, label))
                    tv.setTextColor(color)
                    tv.text = label
                    // The row's merged description names it; the glyph itself is not readable.
                    tv.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            )
        } else {
            Text(text = label, fontSize = SubLabelSizing.previewSp(15f, false, label).sp, maxLines = 1)
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
    /** Colour of the direction names: the magnifier theme's sublabel colour, readable on light keys. */
    labelColor: Color,
    onDirectionTapped: (SwipeDirection) -> Unit
) {
    Box(modifier = modifier) {
        // Create a 3x3 grid of clickable zones
        Column(modifier = Modifier.fillMaxSize()) {
            // Top row: NW, N, NE
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DirectionZone(SwipeDirection.NW, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
                DirectionZone(SwipeDirection.N, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
                DirectionZone(SwipeDirection.NE, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
            }
            // Middle row: W, Center (no action), E
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DirectionZone(SwipeDirection.W, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
                // Center zone - no action, just transparent
                Box(modifier = Modifier.weight(1f).fillMaxHeight())
                DirectionZone(SwipeDirection.E, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
            }
            // Bottom row: SW, S, SE
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DirectionZone(SwipeDirection.SW, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
                DirectionZone(SwipeDirection.S, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
                DirectionZone(SwipeDirection.SE, Modifier.weight(1f).fillMaxHeight(), labelColor, onDirectionTapped)
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
    labelColor: Color,
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
            color = labelColor
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
        // The command's localized name ("Voice input (on device)"), never its id ("voice_input");
        // an id the catalogue does not know (a legacy AvailableCommand name) is shown as stored.
        ActionType.COMMAND -> context.getString(
            R.string.short_swipe_action_desc_command,
            CommandRegistry.getByName(value)?.let { context.getString(it.nameRes) } ?: value
        )
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
