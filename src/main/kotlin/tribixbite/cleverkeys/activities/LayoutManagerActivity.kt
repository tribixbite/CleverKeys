package tribixbite.cleverkeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.burnoutcrew.reorderable.*
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.XmlLayoutExporter

import tribixbite.cleverkeys.theme.KeyboardTheme
import tribixbite.cleverkeys.theme.ThemeProvider
import java.util.UUID

/** Wrapper to give each layout a stable unique ID for drag-reorder */
data class LayoutWithId(
    val id: String = UUID.randomUUID().toString(),
    val layout: LayoutsPreference.Layout
)

class LayoutManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // #35: Follow system dark/light mode
            KeyboardTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LayoutManagerScreen(
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

private fun getLayoutXml(context: android.content.Context, layoutName: String): String {
    return try {
        val res = context.resources
        val id = LayoutsPreference.layoutIdOfName(res, layoutName)
        if (id > 0) {
            res.openRawResource(id).use { Utils.read_all_utf8(it) }
        } else {
            // Fallback for System or unknown
            res.openRawResource(R.raw.latn_qwerty_us).use { Utils.read_all_utf8(it) }
        }
    } catch (e: Exception) {
        ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // CRITICAL: Use DirectBootAwarePreferences to get the SAME SharedPreferences as the keyboard service
    val prefs = remember { DirectBootAwarePreferences.get_shared_preferences(context) }

    // Load layouts from preferences with stable IDs for drag-reorder
    var layoutsWithIds by remember {
        mutableStateOf(
            (ListGroupPreference.loadFromPreferences(
                LayoutsPreference.KEY,
                prefs,
                LayoutsPreference.DEFAULT,
                LayoutsPreference.SERIALIZER
            ) ?: LayoutsPreference.DEFAULT).map { LayoutWithId(layout = it) }
        )
    }

    // Helper function to extract raw layouts for saving
    fun getLayoutsForSaving(): List<LayoutsPreference.Layout> = layoutsWithIds.map { it.layout }

    // Get layout display names
    val layoutNames = remember { LayoutsPreference.getLayoutNames(context.resources) }
    val layoutDisplayNames = remember {
        try {
            context.resources.getStringArray(R.array.pref_layout_entries)
        } catch (e: Exception) {
            layoutNames.toTypedArray()
        }
    }

    // GH #186/#61: languages with a dictionary on this device (bundled + installed packs), for
    // the per-layout language picker and its "not installed" warning.
    val availableLanguages = remember { LanguageAvailability.availableLanguages(context) }
    val uiLocale = context.resources.configuration.locales[0]
    fun languageName(code: String): String = LanguageDisplayNames.displayName(code, uiLocale)
    // XML `language` defaults, built-in layouts included (arab_pc_ir declares fa), parsed once each.
    val declaredLanguages = remember {
        DeclaredLayoutLanguages { name -> LayoutsPreference.layoutOfString(context.resources, name) }
    }

    // Per-layout theme binding: the themes Theme Manager offers (custom themes live-updated from
    // the same store ThemeProvider renders from), and which ids exist on this device.
    val themeProvider = remember { ThemeProvider.getInstance(context) }
    val customThemes by themeProvider.customThemeManager.customThemes.collectAsState()
    val themeChoices = remember(customThemes) { layoutThemeChoices(context.resources, customThemes) }
    fun themeName(id: String): String = layoutThemeDisplayName(id, context.resources, customThemes)
    val globalThemeName = themeName(prefs.getString("theme", null) ?: ThemeProvider.FALLBACK_THEME_ID)

    // Dialog states
    var showAddDialog by remember { mutableStateOf(false) }
    var showLanguagePickerFor by remember { mutableStateOf<Int?>(null) }
    var showThemePickerFor by remember { mutableStateOf<Int?>(null) }
    var showKeyGeometryFor by remember { mutableStateOf<Int?>(null) }
    var showCustomLayoutDialog by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Int?>(null) }

    // Reorderable state for drag-and-drop
    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Create new list with reordered items (don't mutate)
            layoutsWithIds = layoutsWithIds.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        },
        onDragEnd = { _, _ ->
            // Save after drag completes
            saveLayouts(prefs, getLayoutsForSaving())
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.layout_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
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
                icon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.layout_manager_add)
                    )
                },
                text = { Text(stringResource(R.string.layout_manager_add)) }
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
                    text = pluralStringResource(
                        R.plurals.layout_manager_hint,
                        layoutsWithIds.size,
                        layoutsWithIds.size
                    ),
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
                itemsIndexed(layoutsWithIds, key = { _, item -> item.id }) { index, layoutWithId ->
                    ReorderableItem(
                        reorderableState = reorderState,
                        key = layoutWithId.id
                    ) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")

                        LayoutItem(
                            layout = layoutWithId.layout,
                            index = index,
                            layoutNames = layoutNames,
                            layoutDisplayNames = layoutDisplayNames,
                            context = context,
                            elevation = elevation,
                            languageLabel = layoutLanguageLabel(layoutWithId.layout, declaredLanguages, ::languageName),
                            missingLanguage = declaredLanguages.bindingOf(layoutWithId.layout)
                                ?.takeIf { it !in availableLanguages }
                                ?.let(::languageName),
                            onLanguage = { showLanguagePickerFor = index },
                            themeLabel = layoutThemeLabel(layoutWithId.layout, ::themeName),
                            missingTheme = layoutThemeBindingOf(layoutWithId.layout)
                                ?.takeIf { !themeProvider.isAvailable(it) },
                            onTheme = { showThemePickerFor = index },
                            keyGeometryCustom = LayoutKeyGeometryEditor.hasOverrides(
                                layoutWithId.layout.keyGeometry, declaredKeyGeometryOf(layoutWithId.layout),
                            ),
                            onKeyGeometry = { showKeyGeometryFor = index },
                            onEdit = {
                                val initialXml = when (val layout = layoutWithId.layout) {
                                    is LayoutsPreference.CustomLayout -> layout.xml
                                    is LayoutsPreference.NamedLayout -> getLayoutXml(context, layout.name)
                                    is LayoutsPreference.SystemLayout -> getLayoutXml(context, "latn_qwerty_us")
                                    else -> ""
                                }
                                showCustomLayoutDialog = Pair(index, initialXml)
                            },
                            onDelete = {
                                if (layoutsWithIds.size > 1) {
                                    showDeleteConfirmDialog = index
                                }
                            },
                            reorderState = reorderState,
                            onSaveTheme = embeddedThemeOf(layoutWithId.layout)?.let { spec ->
                                { saveLayoutThemeAsCustom(context, themeProvider, prefs, layoutWithId.layout, spec) }
                            }
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
                layoutsWithIds = layoutsWithIds + LayoutWithId(layout = LayoutsPreference.SystemLayout())
                saveLayouts(prefs, getLayoutsForSaving())
                showAddDialog = false
            },
            onSelectNamed = { name ->
                layoutsWithIds = layoutsWithIds + LayoutWithId(layout = LayoutsPreference.NamedLayout(name))
                saveLayouts(prefs, getLayoutsForSaving())
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
            exportThemeEntry = layoutsWithIds.getOrNull(index)?.layout?.theme,
            exportKeyGeometryEntry = layoutsWithIds.getOrNull(index)?.layout?.keyGeometry,
            allowRemove = index >= 0,
            onDismiss = { showCustomLayoutDialog = null },
            onSave = { xml ->
                // Editing the XML (including renaming it) keeps the entry's language binding
                // (GH #186/#61) and theme binding; a new layout starts with none, so an imported
                // XML's `theme`/`language` attributes are its default bindings.
                val existing = layoutsWithIds.getOrNull(index)?.layout
                val customLayout = LayoutsPreference.CustomLayout.parse(
                    xml,
                    language = existing?.language,
                    theme = existing?.theme,
                    keyGeometry = existing?.keyGeometry
                )
                if (index >= 0) {
                    // Edit existing - create new list with updated item
                    layoutsWithIds = layoutsWithIds.toMutableList().apply {
                        this[index] = LayoutWithId(id = this[index].id, layout = customLayout)
                    }
                } else {
                    // Add new
                    layoutsWithIds = layoutsWithIds + LayoutWithId(layout = customLayout)
                }
                saveLayouts(prefs, getLayoutsForSaving())
                showCustomLayoutDialog = null
            },
            onRemove = {
                if (index >= 0 && layoutsWithIds.size > 1) {
                    layoutsWithIds = layoutsWithIds.toMutableList().apply { removeAt(index) }
                    saveLayouts(prefs, getLayoutsForSaving())
                }
                showCustomLayoutDialog = null
            }
        )
    }

    // Per-layout language picker (GH #186/#61)
    showLanguagePickerFor?.let { index ->
        val entry = layoutsWithIds.getOrNull(index)
        if (entry == null) {
            showLanguagePickerFor = null
        } else {
            LayoutLanguageDialog(
                layout = entry.layout,
                declaredLanguages = declaredLanguages,
                availableLanguages = availableLanguages,
                languageName = ::languageName,
                onDismiss = { showLanguagePickerFor = null },
                onSelect = { choice ->
                    val declared = declaredLanguages.of(entry.layout)
                    val updated = entry.layout.withLanguage(LayoutLanguageBinding.entryValueFor(choice, declared))
                    layoutsWithIds = layoutsWithIds.toMutableList().apply {
                        this[index] = LayoutWithId(id = entry.id, layout = updated)
                    }
                    saveLayouts(prefs, getLayoutsForSaving())
                    showLanguagePickerFor = null
                }
            )
        }
    }

    // Per-layout theme picker
    showThemePickerFor?.let { index ->
        val entry = layoutsWithIds.getOrNull(index)
        if (entry == null) {
            showThemePickerFor = null
        } else {
            LayoutThemeDialog(
                layout = entry.layout,
                choices = themeChoices,
                globalThemeName = globalThemeName,
                isAvailable = themeProvider::isAvailable,
                onDismiss = { showThemePickerFor = null },
                onSelectOwn = {
                    // No user choice: the layout's own <theme> renders (no XML id beats it here).
                    layoutsWithIds = layoutsWithIds.toMutableList().apply {
                        this[index] = LayoutWithId(id = entry.id, layout = entry.layout.withTheme(null))
                    }
                    saveLayouts(prefs, getLayoutsForSaving())
                    showThemePickerFor = null
                },
                onSelect = { choice ->
                    val updated = entry.layout.withTheme(
                        LayoutThemeBinding.entryValueFor(
                            choice, declaredThemeOf(entry.layout), hasEmbedded = embeddedThemeOf(entry.layout) != null,
                        )
                    )
                    layoutsWithIds = layoutsWithIds.toMutableList().apply {
                        this[index] = LayoutWithId(id = entry.id, layout = updated)
                    }
                    // Saving refreshes the keyboard's Config: if this is the current layout,
                    // the keyboard re-renders in the new effective theme.
                    saveLayouts(prefs, getLayoutsForSaving())
                    showThemePickerFor = null
                }
            )
        }
    }

    // Per-layout key spacing & borders
    showKeyGeometryFor?.let { index ->
        val entry = layoutsWithIds.getOrNull(index)
        if (entry == null) {
            showKeyGeometryFor = null
        } else {
            LayoutKeyGeometryDialog(
                layout = entry.layout,
                globals = remember(index) { globalKeyGeometryValues(prefs) },
                onDismiss = { showKeyGeometryFor = null },
                onSave = { choices ->
                    val updated = entry.layout.withKeyGeometry(
                        LayoutKeyGeometry.entryFor(choices, declaredKeyGeometryOf(entry.layout))
                    )
                    layoutsWithIds = layoutsWithIds.toMutableList().apply {
                        this[index] = LayoutWithId(id = entry.id, layout = updated)
                    }
                    // Saving refreshes Config; the current layout re-lays out with the new geometry.
                    saveLayouts(prefs, getLayoutsForSaving())
                    showKeyGeometryFor = null
                }
            )
        }
    }

    // Delete Confirmation Dialog
    showDeleteConfirmDialog?.let { index ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text(stringResource(R.string.layout_manager_remove_title)) },
            text = {
                Text(stringResource(R.string.layout_manager_remove_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (layoutsWithIds.size > 1) {
                            layoutsWithIds = layoutsWithIds.toMutableList().apply { removeAt(index) }
                            saveLayouts(prefs, getLayoutsForSaving())
                        }
                        showDeleteConfirmDialog = null
                    }
                ) {
                    Text(stringResource(R.string.common_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text(stringResource(R.string.common_cancel))
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
    languageLabel: String,
    missingLanguage: String?,
    onLanguage: () -> Unit,
    themeLabel: String,
    missingTheme: String?,
    onTheme: () -> Unit,
    keyGeometryCustom: Boolean,
    onKeyGeometry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    reorderState: ReorderableLazyListState,
    /** The layout XML carries its own `<theme>`: offer "Save theme as custom theme". */
    onSaveTheme: (() -> Unit)? = null,
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
                stringResource(R.string.layout_manager_custom_layout_name)
            }
        }
        is LayoutsPreference.SystemLayout -> stringResource(R.string.layout_manager_system_layout_name)
        else -> stringResource(R.string.layout_manager_unknown_layout_name)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .shadow(elevation, RoundedCornerShape(8.dp))
            .clickable(onClick = onEdit),
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
                contentDescription = stringResource(R.string.layout_manager_drag_desc),
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
                    text = stringResource(R.string.layout_manager_item_index, index + 1),
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
                        is LayoutsPreference.SystemLayout -> stringResource(R.string.layout_manager_tab_system)
                        is LayoutsPreference.NamedLayout -> stringResource(R.string.layout_manager_tab_predefined)
                        is LayoutsPreference.CustomLayout -> stringResource(R.string.layout_manager_badge_custom_xml)
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                // GH #186/#61: the layout's language binding; tap to change it.
                AssistChip(
                    onClick = onLanguage,
                    label = {
                        Text(
                            text = stringResource(R.string.keyboard_lang_layout_active, languageLabel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (missingLanguage != null) {
                    Text(
                        text = stringResource(R.string.layout_language_not_installed, missingLanguage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // The layout's theme binding; tap to change it.
                AssistChip(
                    onClick = onTheme,
                    label = {
                        Text(
                            text = stringResource(R.string.layout_theme_chip, themeLabel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                if (missingTheme != null) {
                    Text(
                        text = stringResource(R.string.layout_theme_missing, missingTheme),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Per-layout key gaps and border (LayoutKeyGeometry); tap to change them.
                AssistChip(
                    onClick = onKeyGeometry,
                    label = {
                        Text(
                            text = stringResource(
                                R.string.layout_key_geometry_chip,
                                stringResource(
                                    if (keyGeometryCustom) R.string.layout_key_geometry_custom
                                    else R.string.layout_key_geometry_global
                                ),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )

                // A layout with its own <theme>: store it as a custom theme (Theme Manager / Creator).
                if (onSaveTheme != null) {
                    AssistChip(
                        onClick = onSaveTheme,
                        label = {
                            Text(
                                text = stringResource(R.string.layout_theme_save_custom),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            // Action buttons
            Row {
                // Edit button (now always visible for all layouts since we support cloning)
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.common_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * The XML `language` default of each layout entry, for Layout Manager's chip, warning and picker.
 *
 * Built-in (named) layouts are read too: `arab_pc_ir.xml` declares `language="fa"` (2026-10-25),
 * and Config resolves a built-in's declaration through [LayoutsPreference.loadLayoutsWithBindings].
 * Reading only custom layouts here made the chip say "Follow Multi-Language settings" while the
 * keyboard was bound to Persian, and choosing "Follow" then stored `null` (= keep the XML default)
 * instead of [LayoutLanguageBinding.UNBOUND], so the binding could not be turned off.
 *
 * @param loadBuiltIn parses a built-in layout by name (production: [LayoutsPreference.layoutOfString]);
 *   each name is parsed once per instance, and a failed or missing layout declares nothing.
 */
internal class DeclaredLayoutLanguages(private val loadBuiltIn: (String) -> KeyboardData?) {
    private val builtIn = HashMap<String, String?>()

    /** The raw XML `language` attribute of [layout], or null (System layout, or none declared). */
    fun of(layout: LayoutsPreference.Layout): String? = when (layout) {
        is LayoutsPreference.CustomLayout -> layout.parsed?.declared_language
        is LayoutsPreference.NamedLayout -> {
            if (!builtIn.containsKey(layout.name)) {
                builtIn[layout.name] = runCatching { loadBuiltIn(layout.name) }.getOrNull()?.declared_language
            }
            builtIn[layout.name]
        }
        else -> null
    }

    /** The effective language binding of [layout], or null when it follows Multi-Language. */
    fun bindingOf(layout: LayoutsPreference.Layout): String? =
        LayoutLanguageBinding.effective(layout.language, of(layout))
}

/** Chip text for a layout's binding: the language, marked when it comes from the XML. */
@Composable
private fun layoutLanguageLabel(
    layout: LayoutsPreference.Layout,
    declaredLanguages: DeclaredLayoutLanguages,
    languageName: (String) -> String
): String {
    val bound = declaredLanguages.bindingOf(layout)
        ?: return stringResource(R.string.layout_language_follow_multilang)
    return if (layout.language == null) {
        stringResource(R.string.layout_language_from_xml, languageName(bound))
    } else {
        languageName(bound)
    }
}

/** Chip text for a layout's theme binding: the theme, marked when it comes from the XML. */
@Composable
private fun layoutThemeLabel(
    layout: LayoutsPreference.Layout,
    themeName: (String) -> String
): String {
    if (usesEmbeddedTheme(layout)) return stringResource(R.string.layout_theme_own)
    val bound = layoutThemeBindingOf(layout)
        ?: return stringResource(R.string.layout_theme_follow_global)
    return if (layout.theme == null) {
        stringResource(R.string.layout_language_from_xml, themeName(bound))
    } else {
        themeName(bound)
    }
}

/**
 * Picker for a layout's language binding (GH #186/#61): "Follow Multi-Language settings" or
 * one language. Lists every language with a dictionary on this device, plus the current
 * binding even when its pack is not installed (marked, so the warning has a visible cause).
 */
@Composable
private fun LayoutLanguageDialog(
    layout: LayoutsPreference.Layout,
    declaredLanguages: DeclaredLayoutLanguages,
    availableLanguages: List<String>,
    languageName: (String) -> String,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    val declared = LayoutLanguageBinding.normalizeCode(declaredLanguages.of(layout))
    val current = declaredLanguages.bindingOf(layout)
    val options: List<String?> = listOf<String?>(null) +
        (availableLanguages + listOfNotNull(current, declared)).distinct().sortedBy { languageName(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_language_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.layout_language_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(options.size, key = { options[it] ?: "" }) { i ->
                        val code = options[i]
                        val name = code?.let(languageName)
                        val label = when {
                            code == null -> stringResource(R.string.layout_language_follow_multilang)
                            code == declared -> stringResource(R.string.layout_language_from_xml, name!!)
                            else -> name!!
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onSelect(code) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = code == current, onClick = { onSelect(code) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                                if (code != null && code !in availableLanguages) {
                                    Text(
                                        text = stringResource(R.string.layout_language_not_installed, name!!),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
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
    var selectedTab by remember { mutableIntStateOf(0) }

    // Saga 2026-10-27: in landscape the dialog was 80 % of a short screen and a title, the tabs
    // and a separate Cancel row left the Predefined list two entries high. Now the dialog takes
    // the screen's own size (not the platform dialog's), Cancel shares the title row, and the
    // list gets every remaining pixel (LayoutManagerLandscapeAddLayoutTest).
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = screen.screenWidthDp > screen.screenHeightDp
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(if (landscape) 0.75f else 0.92f)
                .widthIn(max = 640.dp)
                .height((screen.screenHeightDp * if (landscape) 0.92f else 0.8f).dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column {
                // Title, with Cancel beside it (no footer row taking list height)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.layout_manager_add),
                        style = if (landscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }

                // Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.layout_manager_tab_system)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.layout_manager_tab_predefined)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.layout_manager_tab_custom)) }
                    )
                }

                // Content
                when (selectedTab) {
                    0 -> {
                        // System layout option (scrolls on a short landscape screen)
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.layout_manager_system_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = onSelectSystem,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.layout_manager_add_system))
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
                                            .padding(horizontal = 16.dp, vertical = 3.dp)
                                            .clickable { onSelectNamed(name) },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        // Custom layout option
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.layout_manager_custom_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = onSelectCustom,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.layout_manager_create_custom))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun CustomLayoutEditorDialog(
    initialXml: String,
    /** The entry's stored theme binding choice, written onto the root of an exported XML. */
    exportThemeEntry: String?,
    exportKeyGeometryEntry: Map<KeyGeometryField, LayoutKeyGeometry.Choice>? = null,
    allowRemove: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit
) {
    var xmlText by remember { mutableStateOf(initialXml) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Import Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                xmlText = LayoutXmlTransfer.read { context.contentResolver.openInputStream(it) }
            } catch (e: Exception) {
                // Audit M6: a failed import used to leave the editor unchanged with no message.
                android.util.Log.w(LayoutXmlTransfer.TAG, "Layout XML import failed", e)
                android.widget.Toast.makeText(
                    context,
                    LayoutXmlTransfer.failureMessage(
                        R.string.layout_manager_import_failed, e, ResourcesResultText(context.resources)
                    ),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Export Launcher
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        uri?.let {
            try {
                pendingExportContent?.let { content ->
                    LayoutXmlTransfer.write({ context.contentResolver.openOutputStream(it) }, content)
                }
            } catch (e: Exception) {
                // Audit M6: a failed export (SAF denial, disk full) used to look like success.
                android.util.Log.w(LayoutXmlTransfer.TAG, "Layout XML export failed", e)
                android.widget.Toast.makeText(
                    context,
                    LayoutXmlTransfer.failureMessage(
                        R.string.layout_manager_export_failed, e, ResourcesResultText(context.resources)
                    ),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Export can embed the layout's effective CUSTOM theme as its <theme> element, so the file
    // carries the appearance to a device that does not have that theme (LayoutThemeTransfer).
    val themeProvider = remember { ThemeProvider.getInstance(context) }
    val boundCustomTheme = LayoutThemeBinding.effective(exportThemeEntry, LayoutThemeBinding.declaredThemeOfXml(xmlText))
        ?.takeIf { it.startsWith("custom_") && themeProvider.isAvailable(it) }
    var includeTheme by remember { mutableStateOf(true) }

    // Validate XML (the empty-input message is resolved here: LaunchedEffect is not composable)
    val emptyXmlError = stringResource(R.string.layout_manager_xml_empty_error)
    val invalidLanguageError = stringResource(R.string.layout_language_xml_invalid)
    val invalidThemeError = stringResource(R.string.layout_theme_xml_invalid)
    val invalidKeyGeometryError = stringResource(R.string.layout_key_geometry_xml_invalid)
    val invalidStyleError = stringResource(R.string.layout_style_xml_invalid)
    LaunchedEffect(xmlText) {
        validationError = try {
            if (xmlText.isNotBlank()) {
                val parsed = KeyboardData.load_string_exn(xmlText)
                // GH #186/#61: loading is lenient about `language`, so the editor is where an
                // invalid code is caught — before it can be saved and silently ignored.
                when {
                    LayoutLanguageBinding.xmlLanguageProblem(parsed.declared_language) -> invalidLanguageError
                    // Same rule for the optional `theme` attribute (a well-formed id of a theme
                    // not on this device is accepted: it falls back to the global theme).
                    LayoutThemeBinding.xmlThemeProblem(parsed.declared_theme) -> invalidThemeError
                    // Out-of-range or malformed key_* geometry attributes.
                    LayoutKeyGeometry.xmlProblems(parsed.declared_key_geometry).isNotEmpty() ->
                        invalidKeyGeometryError.format(
                            LayoutKeyGeometry.xmlProblems(parsed.declared_key_geometry).joinToString()
                        )
                    // <theme>, <row> and <key> styling values loading would silently drop.
                    parsed.style_problems.isNotEmpty() -> invalidStyleError.format(parsed.style_problems.joinToString())
                    else -> null
                }
            } else {
                emptyXmlError
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
                    text = stringResource(R.string.layout_manager_editor_title),
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
                    label = { Text(stringResource(R.string.layout_manager_xml_label)) },
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )

                // File Operations
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("text/xml", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.layout_manager_import_xml))
                    }
                    OutlinedButton(
                        onClick = {
                            val manager = ShortSwipeCustomizationManager.getInstance(context)
                            val mappings = manager.getAllMappings()
                            // The exported file carries the entry's theme binding as the root
                            // `theme` attribute, so re-importing it restores the binding.
                            // …and its key-geometry choices as the key_* root attributes.
                            val themed = LayoutKeyGeometry.applyToXml(
                                LayoutThemeBinding.applyToXml(xmlText, exportThemeEntry), exportKeyGeometryEntry,
                            )
                            val withTheme = if (includeTheme && boundCustomTheme != null) {
                                tribixbite.cleverkeys.theme.LayoutThemeTransfer.layoutXmlWithCustomTheme(themeProvider, themed, boundCustomTheme)
                            } else themed
                            pendingExportContent = XmlLayoutExporter.injectMappings(withTheme, mappings)
                            exportLauncher.launch("custom_layout.xml")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.layout_manager_export_xml))
                    }
                }

                if (boundCustomTheme != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includeTheme, onCheckedChange = { includeTheme = it })
                        Text(
                            text = stringResource(R.string.layout_manager_export_include_theme),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

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
                            Text(stringResource(R.string.common_remove))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onSave(xmlText) },
                            enabled = validationError == null && xmlText.isNotBlank()
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }
    }
}

// Helper functions

/**
 * Layout Manager → "Save theme as custom theme": [spec] (the layout's `<theme>`) becomes a custom
 * theme named after the layout in THE provider's store (theme-system skill rule 4), so it is
 * listed in Theme Manager and opens in the Theme Creator. Unset colours are filled from the global
 * theme the layout inherited them from.
 */
private fun saveLayoutThemeAsCustom(
    context: android.content.Context,
    provider: ThemeProvider,
    prefs: android.content.SharedPreferences,
    layout: LayoutsPreference.Layout,
    spec: tribixbite.cleverkeys.theme.ThemeSpec,
) {
    val name = (layout as? LayoutsPreference.CustomLayout)?.parsed?.name?.takeIf { it.isNotBlank() }
        ?: spec.name
        ?: context.getString(R.string.layout_manager_custom_layout_name)
    val global = prefs.getString("theme", null) ?: ThemeProvider.FALLBACK_THEME_ID
    val saved = tribixbite.cleverkeys.theme.LayoutThemeTransfer.saveAsCustomTheme(provider, spec, name, global)
    val message = if (saved != null) context.getString(R.string.layout_theme_saved_custom, saved.name)
    else context.getString(R.string.layout_theme_save_full, tribixbite.cleverkeys.theme.CustomThemeManager.MAX_CUSTOM_THEMES)
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

private fun saveLayouts(
    prefs: android.content.SharedPreferences,
    layouts: List<LayoutsPreference.Layout>
) {
    val editor = prefs.edit()
    ListGroupPreference.saveToPreferences(LayoutsPreference.KEY, editor, layouts, LayoutsPreference.SERIALIZER)
    editor.apply()
}

private fun readInitialCustomLayout(context: android.content.Context): String {
    return try {
        context.resources.openRawResource(R.raw.latn_qwerty_us).use { inputStream ->
            Utils.read_all_utf8(inputStream)
        }
    } catch (e: Exception) {
        ""
    }
}

/**
 * Reading and writing a layout XML document picked through the Storage Access Framework, and
 * the user-visible message for a failure (audit M6, 2026-10-20: both used to be swallowed).
 *
 * Android-free so `runPureTests` covers it: the stream openers are passed in. A provider that
 * returns no stream is a failure, never an empty layout or a silent no-op export.
 */
internal object LayoutXmlTransfer {
    const val TAG = "LayoutXmlTransfer"

    /** Read the whole document as UTF-8. @throws ClassifiedIoException when no stream opens */
    fun read(open: () -> java.io.InputStream?): String {
        val stream = open() ?: throw ClassifiedIoException(
            IoFailureReason.FILE_NOT_FOUND, "Content provider returned no input stream"
        )
        return stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    /** Write [content] as UTF-8. @throws ClassifiedIoException when no stream opens */
    fun write(open: () -> java.io.OutputStream?, content: String) {
        val stream = open() ?: throw ClassifiedIoException(
            IoFailureReason.READ_WRITE, "Content provider returned no output stream"
        )
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    /** "[bodyRes] text: <localized reason>" for [error], resolved through [text]. */
    fun failureMessage(@androidx.annotation.StringRes bodyRes: Int, error: Throwable, text: ResultText): String =
        text.string(bodyRes, text.string(IoFailureClassifier.classify(error).messageRes))
}
