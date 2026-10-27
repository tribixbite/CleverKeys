package tribixbite.cleverkeys

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.theme.CustomTheme
import tribixbite.cleverkeys.theme.KeyboardLooks
import tribixbite.cleverkeys.theme.ThemeCategory
import tribixbite.cleverkeys.theme.getAllPredefinedThemes
import tribixbite.cleverkeys.theme.getThemeById

/**
 * One theme the per-layout theme picker offers, with the stored id the binding records and the
 * preview colours the Theme Manager shows for it.
 *
 * @property section localized section heading (Built-in / Custom / a decorative category).
 */
data class LayoutThemeChoice(
    val id: String,
    val name: String,
    val section: String,
    val background: Color,
    val key: Color,
    val label: Color,
)

/**
 * Every theme Theme Manager offers, in its order — Built-in (Monet only on Android 12+, as there),
 * Custom, then the decorative categories — with the ids Theme Manager stores in `theme`.
 */
fun layoutThemeChoices(res: Resources, customThemes: List<CustomTheme>): List<LayoutThemeChoice> {
    val builtInSection = res.getString(R.string.theme_section_builtin)
    val builtIn = ThemeSettingsActivity.BUILTIN_THEMES
        .filter { !it.id.startsWith("monet") || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
        .map {
            LayoutThemeChoice(
                id = it.id,
                name = res.getString(it.nameRes),
                section = builtInSection,
                background = Color(it.backgroundColor),
                key = Color(it.keyColor),
                label = Color(it.labelColor),
            )
        }
    val looksSection = res.getString(R.string.theme_section_looks)
    val systemLight = Config.isSystemLight(res)
    val looks = ThemeSettingsActivity.LOOK_THEMES.mapNotNull { entry ->
        val palette = KeyboardLooks.byId(entry.id)?.palette(systemLight) ?: return@mapNotNull null
        LayoutThemeChoice(
            id = entry.id,
            name = res.getString(entry.nameRes),
            section = looksSection,
            background = Color(palette.keyboardBackground),
            key = Color(palette.key),
            label = Color(palette.label),
        )
    }
    val customSection = res.getString(R.string.theme_section_custom)
    val custom = customThemes.map {
        LayoutThemeChoice(
            id = "custom_${it.id}",
            name = it.name,
            section = customSection,
            background = it.colors.keyboardBackground,
            key = it.colors.keyDefault,
            label = it.colors.keyLabel,
        )
    }
    val predefined = getAllPredefinedThemes()
    val decorative = ThemeCategory.entries.filter { it != ThemeCategory.CUSTOM }.flatMap { category ->
        val section = res.getString(category.labelRes)
        predefined[category].orEmpty().map { info ->
            LayoutThemeChoice(
                id = "decorative_${info.id}",
                name = info.displayName(res),
                section = section,
                background = info.colorScheme.keyboardBackground,
                key = info.colorScheme.keyDefault,
                label = info.colorScheme.keyLabel,
            )
        }
    }
    return builtIn + looks + custom + decorative
}

/**
 * Display name of a theme id for Layout Manager: the Theme Manager name when the theme exists,
 * otherwise the raw id (a missing DIY theme has no name left to show).
 */
fun layoutThemeDisplayName(id: String, res: Resources, customThemes: List<CustomTheme>): String {
    ThemeSettingsActivity.BUILTIN_THEMES.firstOrNull { it.id == id }?.let { return res.getString(it.nameRes) }
    ThemeSettingsActivity.LOOK_THEMES.firstOrNull { it.id == id }?.let { return res.getString(it.nameRes) }
    if (id.startsWith("decorative_")) getThemeById(id.removePrefix("decorative_"))?.let { return it.displayName(res) }
    if (id.startsWith("custom_")) customThemes.firstOrNull { "custom_${it.id}" == id }?.let { return it.name }
    return id
}

/**
 * The XML `theme` default of a layout entry: a custom layout's parsed attribute, or a predefined
 * layout's from [PredefinedLayoutThemes] (the familiar-style layouts bind their look; every other
 * predefined layout declares none and follows the user unless bound here).
 */
internal fun declaredThemeOf(layout: LayoutsPreference.Layout): String? = when (layout) {
    is LayoutsPreference.CustomLayout -> layout.parsed?.declared_theme
    is LayoutsPreference.NamedLayout -> PredefinedLayoutThemes.declaredThemeOf(layout.name)
    else -> null
}

/** The effective theme binding of a layout entry, or null when it follows the global theme. */
internal fun layoutThemeBindingOf(layout: LayoutsPreference.Layout): String? =
    LayoutThemeBinding.effective(layout.theme, declaredThemeOf(layout))

/**
 * Picker for a layout's theme binding: "Follow global theme" or one theme, with a search field
 * over theme names and ids, grouped like Theme Manager. A current binding to a theme that is not
 * on this device is listed (marked) so the Layout Manager warning has a visible cause.
 *
 * @param globalThemeName display name of the user's global theme (shown under "Follow").
 * @param isAvailable whether a theme id exists on this device.
 */
@Composable
internal fun LayoutThemeDialog(
    layout: LayoutsPreference.Layout,
    choices: List<LayoutThemeChoice>,
    globalThemeName: String,
    isAvailable: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val declared = LayoutThemeBinding.normalizeId(declaredThemeOf(layout))
    val current = layoutThemeBindingOf(layout)
    var query by rememberSaveable { mutableStateOf("") }
    // A bound or declared theme missing from this device is still offered (it is the state the
    // user needs to see and leave), labelled by its raw id.
    val missing = listOfNotNull(current, declared).distinct()
        .filter { id -> !isAvailable(id) && choices.none { it.id == id } }
    val filtered = remember(query, choices) {
        val q = query.trim()
        if (q.isEmpty()) choices
        else choices.filter { it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_theme_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.layout_theme_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.layout_theme_search_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    item(key = "follow") {
                        ThemeOptionRow(
                            selected = current == null,
                            title = stringResource(R.string.layout_theme_follow_global),
                            subtitle = stringResource(R.string.layout_theme_follow_global_desc, globalThemeName),
                            swatch = null,
                            onClick = { onSelect(null) }
                        )
                    }
                    items(missing, key = { "missing_$it" }) { id ->
                        ThemeOptionRow(
                            selected = id == current,
                            title = id,
                            subtitle = stringResource(R.string.layout_theme_missing, id),
                            subtitleIsError = true,
                            swatch = null,
                            onClick = { onSelect(id) }
                        )
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.layout_theme_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                    var lastSection: String? = null
                    for (choice in filtered) {
                        if (choice.section != lastSection) {
                            lastSection = choice.section
                            item(key = "section_${choice.section}") {
                                Text(
                                    text = choice.section,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                        item(key = choice.id) {
                            ThemeOptionRow(
                                selected = choice.id == current,
                                // "(layout default)": the theme the layout XML declares.
                                title = if (choice.id == declared) {
                                    stringResource(R.string.layout_language_from_xml, choice.name)
                                } else {
                                    choice.name
                                },
                                subtitle = null,
                                swatch = choice,
                                onClick = { onSelect(choice.id) }
                            )
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
private fun ThemeOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    swatch: LayoutThemeChoice?,
    onClick: () -> Unit,
    subtitleIsError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        if (swatch != null) ThemeSwatch(swatch)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A small keyboard-colour preview: background with one key and its label colour. */
@Composable
private fun ThemeSwatch(choice: LayoutThemeChoice) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 24.dp)
            .background(choice.background, RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(width = 12.dp, height = 12.dp).background(choice.key, RoundedCornerShape(2.dp))) {
                Box(Modifier.size(4.dp).align(Alignment.Center).background(choice.label))
            }
            Box(Modifier.size(width = 12.dp, height = 12.dp).background(choice.key, RoundedCornerShape(2.dp)))
        }
    }
}
