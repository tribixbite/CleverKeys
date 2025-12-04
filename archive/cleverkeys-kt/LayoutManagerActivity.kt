package tribixbite.cleverkeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.burnoutcrew.reorderable.*
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference

/**
 * Layout Manager Activity - Manage Keyboard Layouts
 *
 * Features:
 * - View all configured layouts
 * - Add new layouts (System, Named, Custom)
 * - Remove layouts (minimum 1 layout required)
 * - Reorder layouts via drag-and-drop
 * - Edit custom layouts
 * - Persist changes to SharedPreferences
 *
 * Integrates with LayoutsPreference for backend logic
 * and uses Material 3 Compose for modern UI.
 */
class LayoutManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = MaterialTheme.colorScheme.primary,
                    surface = MaterialTheme.colorScheme.surface
                )
            ) {
                LayoutManagerScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cleverkeys_prefs", android.content.Context.MODE_PRIVATE)

    // Load layouts from preferences
    var layouts by remember {
        mutableStateOf(
            ListGroupPreference.load_from_preferences(
                LayoutsPreference.KEY,
                prefs,
                LayoutsPreference.DEFAULT,
                LayoutsPreference.SERIALIZER
            ).toMutableList()
        )
    }

    // Get layout display names
    val layoutNames = remember { LayoutsPreference.getLayoutNames(context.resources) }
    val layoutDisplayNames = remember {
        try {
            val displayNamesId = context.resources.getIdentifier("pref_layout_entries", "array", null)
            if (displayNamesId != 0) {
                context.resources.getStringArray(displayNamesId)
            } else {
                layoutNames.toTypedArray()
            }
        } catch (e: Exception) {
            layoutNames.toTypedArray()
        }
    }

    // Dialog states
    var showAddDialog by remember { mutableStateOf(false) }
    var showCustomLayoutDialog by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Int?>(null) }

    // Reorderable state for drag-and-drop
    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val item = layouts.removeAt(from.index)
            layouts.add(to.index, item)
            // Save immediately on reorder
            saveLayouts(prefs, layouts)
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard Layouts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Add Layout") },
                text = { Text("Add Layout") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "Drag to reorder • Tap to edit • ${layouts.size} layout${if (layouts.size != 1) "s" else ""}",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Layout list with drag-and-drop
            LazyColumn(
                state = reorderState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .reorderable(reorderState)
            ) {
                itemsIndexed(layouts, key = { index, _ -> index }) { index, layout ->
                    ReorderableItem(
                        reorderableState = reorderState,
                        key = index
                    ) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")

                        LayoutItem(
                            layout = layout,
                            index = index,
                            layoutNames = layoutNames,
                            layoutDisplayNames = layoutDisplayNames,
                            context = context,
                            elevation = elevation,
                            onEdit = {
                                if (layout is LayoutsPreference.CustomLayout) {
                                    showCustomLayoutDialog = Pair(index, layout.xml)
                                }
                            },
                            onDelete = {
                                if (layouts.size > 1) {
                                    showDeleteConfirmDialog = index
                                }
                            },
                            reorderState = reorderState
                        )
                    }
                }
            }
        }
    }

    // Add Layout Dialog
    if (showAddDialog) {
        AddLayoutDialog(
            layoutNames = layoutNames,
            layoutDisplayNames = layoutDisplayNames,
            onDismiss = { showAddDialog = false },
            onSelectSystem = {
                layouts.add(LayoutsPreference.SystemLayout())
                saveLayouts(prefs, layouts)
                showAddDialog = false
            },
            onSelectNamed = { name ->
                layouts.add(LayoutsPreference.NamedLayout(name))
                saveLayouts(prefs, layouts)
                showAddDialog = false
            },
            onSelectCustom = {
                showAddDialog = false
                showCustomLayoutDialog = Pair(-1, readInitialCustomLayout(context))
            }
        )
    }

    // Custom Layout Editor Dialog
    showCustomLayoutDialog?.let { (index, initialXml) ->
        CustomLayoutEditorDialog(
            initialXml = initialXml,
            allowRemove = index >= 0,
            onDismiss = { showCustomLayoutDialog = null },
            onSave = { xml ->
                val customLayout = LayoutsPreference.CustomLayout.parse(xml)
                if (index >= 0) {
                    // Edit existing
                    layouts[index] = customLayout
                } else {
                    // Add new
                    layouts.add(customLayout)
                }
                saveLayouts(prefs, layouts)
                showCustomLayoutDialog = null
            },
            onRemove = {
                if (index >= 0 && layouts.size > 1) {
                    layouts.removeAt(index)
                    saveLayouts(prefs, layouts)
                }
                showCustomLayoutDialog = null
            }
        )
    }

    // Delete Confirmation Dialog
    showDeleteConfirmDialog?.let { index ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Remove Layout?") },
            text = {
                Text("Are you sure you want to remove this layout?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (layouts.size > 1) {
                            layouts.removeAt(index)
                            saveLayouts(prefs, layouts)
                        }
                        showDeleteConfirmDialog = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun LayoutItem(
    layout: LayoutsPreference.Layout,
    index: Int,
    layoutNames: List<String>,
    layoutDisplayNames: Array<String>,
    context: android.content.Context,
    elevation: androidx.compose.ui.unit.Dp,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    reorderState: ReorderableLazyListState
) {
    val layoutLabel = when (layout) {
        is LayoutsPreference.NamedLayout -> {
            val valueIndex = layoutNames.indexOf(layout.name)
            if (valueIndex >= 0 && valueIndex < layoutDisplayNames.size) {
                layoutDisplayNames[valueIndex]
            } else {
                layout.name
            }
        }
        is LayoutsPreference.CustomLayout -> {
            if (layout.parsed?.name?.isNotEmpty() == true) {
                layout.parsed.name
            } else {
                "Custom Layout"
            }
        }
        is LayoutsPreference.SystemLayout -> "System Settings"
        else -> "Unknown Layout"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .shadow(elevation, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle (using Menu icon as fallback)
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Drag to reorder",
                modifier = Modifier
                    .detectReorderAfterLongPress(reorderState)
                    .padding(end = 12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Layout info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Layout ${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = layoutLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Layout type badge
                Text(
                    text = when (layout) {
                        is LayoutsPreference.SystemLayout -> "System"
                        is LayoutsPreference.NamedLayout -> "Predefined"
                        is LayoutsPreference.CustomLayout -> "Custom XML"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Action buttons
            Row {
                // Edit button (only for custom layouts)
                if (layout is LayoutsPreference.CustomLayout) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AddLayoutDialog(
    layoutNames: List<String>,
    layoutDisplayNames: Array<String>,
    onDismiss: () -> Unit,
    onSelectSystem: () -> Unit,
    onSelectNamed: (String) -> Unit,
    onSelectCustom: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column {
                // Title
                Text(
                    text = "Add Layout",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )

                // Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("System") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Predefined") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Custom") }
                    )
                }

                // Content
                when (selectedTab) {
                    0 -> {
                        // System layout option
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Use device's default keyboard layout",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = onSelectSystem,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add System Layout")
                            }
                        }
                    }
                    1 -> {
                        // Predefined layouts list
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            items(layoutNames.size) { index ->
                                val name = layoutNames[index]
                                val displayName = layoutDisplayNames.getOrElse(index) { name }

                                // Skip system and custom entries
                                if (name != "system" && name != "custom") {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .clickable { onSelectNamed(name) },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        // Custom layout option
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Create a custom keyboard layout using XML format",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = onSelectCustom,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Create Custom Layout")
                            }
                        }
                    }
                }

                // Cancel button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun CustomLayoutEditorDialog(
    initialXml: String,
    allowRemove: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit
) {
    var xmlText by remember { mutableStateOf(initialXml) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Validate XML
    LaunchedEffect(xmlText) {
        validationError = try {
            if (xmlText.isNotBlank()) {
                KeyboardData.loadStringExn(xmlText)
                null
            } else {
                "Layout XML cannot be empty"
            }
        } catch (e: Exception) {
            e.message
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Title
                Text(
                    text = "Custom Layout Editor",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )

                // XML Editor
                OutlinedTextField(
                    value = xmlText,
                    onValueChange = { xmlText = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    label = { Text("Layout XML") },
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Remove button (if allowed)
                    if (allowRemove) {
                        TextButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Remove")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onSave(xmlText) },
                            enabled = validationError == null && xmlText.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

// Helper functions

private fun saveLayouts(
    prefs: android.content.SharedPreferences,
    layouts: List<LayoutsPreference.Layout>
) {
    val editor = prefs.edit()
    ListGroupPreference.save_to_preferences(LayoutsPreference.KEY, editor, layouts, LayoutsPreference.SERIALIZER)
    editor.apply()
}

private fun readInitialCustomLayout(context: android.content.Context): String {
    return try {
        val qwertyId = context.resources.getIdentifier("latn_qwerty_us", "raw", null)
        if (qwertyId != 0) {
            context.resources.openRawResource(qwertyId).use { inputStream ->
                Utils.readAllUtf8(inputStream)
            }
        } else {
            ""
        }
    } catch (e: Exception) {
        ""
    }
}
