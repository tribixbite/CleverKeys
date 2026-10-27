package tribixbite.cleverkeys

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import tribixbite.cleverkeys.theme.LookHintStyle
import tribixbite.cleverkeys.theme.LookLegends
import tribixbite.cleverkeys.theme.LookLetterKey
import tribixbite.cleverkeys.theme.LookPillKey
import tribixbite.cleverkeys.theme.LookPreview
import tribixbite.cleverkeys.theme.LookPreviewShape
import tribixbite.cleverkeys.theme.ThemeColorRole
import tribixbite.cleverkeys.theme.ThemeSpec
import tribixbite.cleverkeys.theme.ThemeSpecEdits
import tribixbite.cleverkeys.theme.ThemeSpecEdits.GeometryField
import tribixbite.cleverkeys.theme.ThemeSpecXml

/**
 * The Theme Creator's "Keyboard look" section (2026-10-28): everything a layout `<theme>` can
 * define beyond the 17 colours above it — look colours, light/dark-mode colours, key shape,
 * gesture hints, the key-press preview and key roles. Every field starts unset ("Use keyboard
 * setting" / "Not set") and the theme stays a version-1 theme until one is set
 * (ThemeSpecEdits.stored). Rules: [ThemeSpecEdits]; storage: `CustomTheme.extension`.
 *
 * @param baseColors the theme's own 17 colours (what a variant row shows while unset).
 */
@Composable
internal fun ThemeLookEditor(
    extension: ThemeSpec,
    baseColors: Map<ThemeColorRole, Int>,
    onChange: (ThemeSpec) -> Unit,
) {
    var editing by remember { mutableStateOf<Pair<ThemeSpecEdits.Mode, ThemeColorRole>?>(null) }
    var variant by remember { mutableStateOf(ThemeSpecEdits.Mode.LIGHT) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.theme_section_look), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.theme_section_look_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(R.string.theme_section_look_colors)
            for (role in ThemeColorRole.LOOK_ONLY) {
                RoleColorRow(
                    label = stringResource(roleLabel(role)),
                    value = extension.colors[role],
                    shown = extension.colors[role],
                    onEdit = { editing = ThemeSpecEdits.Mode.BOTH to role },
                    onClear = { onChange(ThemeSpecEdits.withColor(extension, ThemeSpecEdits.Mode.BOTH, role, null)) },
                )
            }

            SectionTitle(R.string.theme_section_variants)
            Text(
                stringResource(R.string.theme_variants_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = variant == ThemeSpecEdits.Mode.LIGHT,
                    onClick = { variant = ThemeSpecEdits.Mode.LIGHT },
                    label = { Text(stringResource(R.string.theme_mode_light)) },
                )
                FilterChip(
                    selected = variant == ThemeSpecEdits.Mode.DARK,
                    onClick = { variant = ThemeSpecEdits.Mode.DARK },
                    label = { Text(stringResource(R.string.theme_mode_dark)) },
                )
            }
            for (role in ThemeColorRole.entries) {
                val own = ThemeSpecEdits.color(extension, variant, role)
                RoleColorRow(
                    label = stringResource(roleLabel(role)),
                    value = own,
                    shown = own ?: extension.colors[role] ?: baseColors[role],
                    onEdit = { editing = variant to role },
                    onClear = { onChange(ThemeSpecEdits.withColor(extension, variant, role, null)) },
                )
            }

            SectionTitle(R.string.theme_section_shape)
            for (field in GeometryField.entries) {
                GeometrySlider(
                    label = stringResource(geometryLabel(field)),
                    value = field.get(extension.geometry),
                    field = field,
                    onChange = { onChange(extension.copy(geometry = field.set(extension.geometry, it))) },
                )
            }

            SectionTitle(R.string.theme_section_hints)
            ChoiceChips(
                options = listOf(LookLegends.ALL, LookLegends.DIGITS, LookLegends.NONE),
                selected = extension.legends,
                label = { stringResource(hintsLabel(it)) },
                onSelect = { onChange(extension.copy(legends = it)) },
            )
            OptionalSlider(
                label = stringResource(R.string.theme_hint_size),
                value = extension.hintStyle?.sizeFraction,
                range = 0.2f..0.6f, step = 0.01f, neutral = LookHintStyle.DEFAULT.sizeFraction,
            ) { v ->
                onChange(extension.copy(hintStyle = v?.let { LookHintStyle(it, extension.hintStyle?.weight ?: LookHintStyle.DEFAULT.weight) }))
            }
            OptionalSlider(
                label = stringResource(R.string.theme_hint_weight),
                value = extension.hintStyle?.weight?.toFloat(),
                range = 100f..900f, step = 100f, neutral = LookHintStyle.DEFAULT.weight.toFloat(),
            ) { v ->
                onChange(extension.copy(hintStyle = v?.let {
                    LookHintStyle(extension.hintStyle?.sizeFraction ?: LookHintStyle.DEFAULT.sizeFraction, it.toInt())
                }))
            }

            SectionTitle(R.string.theme_section_key_preview)
            ChoiceChips(
                options = LookPreviewShape.entries,
                selected = extension.preview?.shape,
                label = { stringResource(previewLabel(it)) },
                onSelect = { shape ->
                    onChange(extension.copy(preview = shape?.let { (extension.preview ?: LookPreview(it)).copy(shape = it) }))
                },
            )
            extension.preview?.let { p ->
                val scale = ThemeSpecXml.PREVIEW_SCALE_RANGE
                PlainSlider(stringResource(R.string.theme_preview_width), p.widthScale, scale, 0.05f) {
                    onChange(extension.copy(preview = p.copy(widthScale = it)))
                }
                PlainSlider(stringResource(R.string.theme_preview_height), p.heightScale, scale, 0.05f) {
                    onChange(extension.copy(preview = p.copy(heightScale = it)))
                }
                PlainSlider(stringResource(R.string.theme_shape_label_scale), p.labelScale, scale, 0.05f) {
                    onChange(extension.copy(preview = p.copy(labelScale = it)))
                }
                PlainSlider(stringResource(R.string.theme_preview_corner), p.cornerScale, ThemeSpecXml.PREVIEW_CORNER_RANGE, 0.01f) {
                    onChange(extension.copy(preview = p.copy(cornerScale = it)))
                }
                SwitchRow(stringResource(R.string.theme_preview_shadow), p.shadow) {
                    onChange(extension.copy(preview = p.copy(shadow = it)))
                }
            }

            SectionTitle(R.string.theme_section_key_roles)
            UseSettingRow(extension.pillKeys == null) { follow ->
                onChange(extension.copy(pillKeys = if (follow) null else emptySet()))
            }
            val pills = extension.pillKeys
            for ((key, res) in listOf(
                LookPillKey.ENTER to R.string.theme_pill_enter,
                LookPillKey.ACTION to R.string.theme_pill_action,
                LookPillKey.NUMBERS to R.string.theme_pill_numbers,
            )) {
                SwitchRow(stringResource(res), pills?.contains(key) == true, enabled = pills != null) { on ->
                    val set = pills.orEmpty()
                    onChange(extension.copy(pillKeys = if (on) set + key else set - key))
                }
            }
            SwitchRow(
                stringResource(R.string.theme_letter_emoji),
                extension.letterKeys?.contains(LookLetterKey.EMOJI) == true,
            ) { on -> onChange(extension.copy(letterKeys = if (on) setOf(LookLetterKey.EMOJI) else null)) }
            OutlinedTextField(
                value = extension.functionChars.orEmpty(),
                onValueChange = { text ->
                    val t = text.take(ThemeSpecXml.MAX_FUNCTION_CHARS)
                    onChange(extension.copy(functionChars = t.ifEmpty { null }))
                },
                label = { Text(stringResource(R.string.theme_function_chars)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            SwitchRow(stringResource(R.string.theme_dynamic_color), extension.dynamicColor == true) { on ->
                onChange(extension.copy(dynamicColor = if (on) true else null))
            }
        }
    }

    editing?.let { (mode, role) ->
        val initial = ThemeSpecEdits.color(extension, mode, role) ?: extension.colors[role] ?: baseColors[role] ?: 0xFF808080.toInt()
        ColorPickerDialog(
            initialColor = Color(initial),
            attributeName = stringResource(roleLabel(role)),
            onDismiss = { editing = null },
            onColorSelected = { c ->
                onChange(ThemeSpecEdits.withColor(extension, mode, role, colorArgb(c)))
                editing = null
            },
        )
    }
}

private fun colorArgb(c: Color): Int = (c.value.toLong() ushr 32).toInt()

@Composable
private fun SectionTitle(res: Int) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** A colour row: label, hex (or "Not set"), swatch of what is drawn, and Clear when set. */
@Composable
private fun RoleColorRow(label: String, value: Int?, shown: Int?, onEdit: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (value == null) stringResource(R.string.theme_color_not_set)
            else String.format(Locale.ROOT, "#%08X", value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(shown?.let { Color(it) } ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        if (value != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.theme_color_clear))
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
    }
}

@Composable
private fun UseSettingRow(following: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = following, onCheckedChange = onToggle)
        Text(stringResource(R.string.theme_use_keyboard_setting), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GeometrySlider(label: String, value: Float?, field: GeometryField, onChange: (Float?) -> Unit) {
    OptionalSlider(label, value, field.range, field.step, field.neutral) { onChange(it?.let(field::snap)) }
}

/** A slider with "Use keyboard setting" (= unset; the slider then shows [neutral], disabled). */
@Composable
private fun OptionalSlider(
    label: String,
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    neutral: Float,
    onChange: (Float?) -> Unit,
) {
    val shown = value ?: neutral
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            tribixbite.cleverkeys.theme.ThemeValues.formatFloat(shown),
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
    UseSettingRow(value == null) { follow -> onChange(if (follow) null else shown) }
    Slider(
        value = shown,
        onValueChange = { onChange(snapTo(it, range, step)) },
        valueRange = range,
        // CLAUDE.md "Compose slider steps": stops BETWEEN the ends.
        steps = sliderSteps(range, step),
        enabled = value != null,
    )
}

@Composable
private fun PlainSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(tribixbite.cleverkeys.theme.ThemeValues.formatFloat(value), style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = value.coerceIn(range),
        onValueChange = { onChange(snapTo(it, range, step)) },
        valueRange = range,
        steps = sliderSteps(range, step),
    )
}

private fun snapTo(v: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    val n = Math.round((v.coerceIn(range) - range.start) / step)
    return (range.start + n * step).toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(range)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** One chip per option plus "Use keyboard setting" (null). */
@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T?) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.theme_use_keyboard_setting)) },
        )
        for (o in options) {
            FilterChip(selected = selected == o, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

/** Label of a colour role: the Theme Creator's existing names for the 17, new ones for the rest. */
internal fun roleLabel(role: ThemeColorRole): Int = when (role) {
    ThemeColorRole.KEY -> R.string.theme_color_key_default
    ThemeColorRole.KEY_PRESSED -> R.string.theme_color_key_activated
    ThemeColorRole.KEY_LOCKED -> R.string.theme_color_key_locked
    ThemeColorRole.KEY_MODIFIER -> R.string.theme_color_key_modifier
    ThemeColorRole.KEY_SPECIAL -> R.string.theme_color_key_special
    ThemeColorRole.LABEL -> R.string.theme_color_key_label
    ThemeColorRole.SUB_LABEL -> R.string.theme_color_sub_label
    ThemeColorRole.SECONDARY_LABEL -> R.string.theme_color_secondary_label
    ThemeColorRole.KEY_BORDER -> R.string.theme_color_key_border
    ThemeColorRole.KEY_BORDER_PRESSED -> R.string.theme_color_border_activated
    ThemeColorRole.SWIPE_TRAIL -> R.string.theme_color_swipe_trail
    ThemeColorRole.RIPPLE -> R.string.theme_color_ripple
    ThemeColorRole.BAR_TEXT -> R.string.theme_color_suggestion_text
    ThemeColorRole.BAR_BACKGROUND -> R.string.theme_color_suggestion_background
    ThemeColorRole.BAR_HIGHLIGHT -> R.string.theme_color_high_confidence
    ThemeColorRole.KEYBOARD_BACKGROUND -> R.string.theme_color_keyboard_background
    ThemeColorRole.SURFACE -> R.string.theme_color_keyboard_surface
    ThemeColorRole.LABEL_PRESSED -> R.string.theme_color_label_pressed
    ThemeColorRole.LOCKED_LABEL -> R.string.theme_color_locked_label
    ThemeColorRole.ENTER_KEY -> R.string.theme_color_enter_key
    ThemeColorRole.ENTER_LABEL -> R.string.theme_color_enter_label
    ThemeColorRole.ACTION_KEY -> R.string.theme_color_action_key
    ThemeColorRole.ACTION_LABEL -> R.string.theme_color_action_label
    ThemeColorRole.MODIFIER_LABEL -> R.string.theme_color_modifier_label
    ThemeColorRole.KEY_SHADOW -> R.string.theme_color_key_shadow
    ThemeColorRole.BAR_SEPARATOR -> R.string.theme_color_bar_separator
    ThemeColorRole.POPUP -> R.string.theme_color_popup
    ThemeColorRole.POPUP_LABEL -> R.string.theme_color_popup_label
    ThemeColorRole.POPUP_SELECTED -> R.string.theme_color_popup_selected
    ThemeColorRole.POPUP_SELECTED_LABEL -> R.string.theme_color_popup_selected_label
}

private fun geometryLabel(field: GeometryField): Int = when (field) {
    GeometryField.GAP_HORIZONTAL -> R.string.appearance_key_horizontal_margin_title
    GeometryField.GAP_VERTICAL -> R.string.appearance_key_vertical_margin_title
    GeometryField.CORNER_RADIUS -> R.string.appearance_border_radius_title
    GeometryField.BORDER_WIDTH -> R.string.appearance_border_line_width_title
    GeometryField.SHADOW -> R.string.theme_shape_shadow
    GeometryField.LABEL_SCALE -> R.string.theme_shape_label_scale
    GeometryField.LABEL_WEIGHT -> R.string.theme_shape_label_weight
}

private fun hintsLabel(l: LookLegends): Int = when (l) {
    LookLegends.ALL -> R.string.theme_hints_all
    LookLegends.DIGITS -> R.string.theme_hints_digits
    LookLegends.NONE -> R.string.theme_hints_none
}

private fun previewLabel(s: LookPreviewShape): Int = when (s) {
    LookPreviewShape.NONE -> R.string.theme_preview_none
    LookPreviewShape.BUBBLE -> R.string.theme_preview_bubble
    LookPreviewShape.BALLOON -> R.string.theme_preview_balloon
    LookPreviewShape.COLUMN -> R.string.theme_preview_column
    LookPreviewShape.PLATE -> R.string.theme_preview_plate
}
