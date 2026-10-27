package tribixbite.cleverkeys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.prefs.LayoutsPreference
import kotlin.math.roundToInt

/**
 * Pure state rules of the per-layout "Key spacing & borders" editor (Layout Manager).
 * Spec: docs/specs/layout-system.md "Per-layout key geometry".
 */
object LayoutKeyGeometryEditor {

    /**
     * The editor's starting choices: per field the value the layout uses now (its own choice or
     * its XML default), or null when it follows the global setting ("Use global setting" ticked).
     */
    fun initialChoices(
        entry: Map<KeyGeometryField, LayoutKeyGeometry.Choice>?,
        declared: Map<KeyGeometryField, Float>,
    ): Map<KeyGeometryField, Float?> {
        val effective = LayoutKeyGeometry.effective(entry, declared)
        return KeyGeometryField.entries.associateWith { effective[it] }
    }

    /**
     * The slider value of a field: its own value, else the inherited global value (shown, dimmed,
     * while inheriting) snapped into the slider's range so the thumb never sits outside it.
     */
    fun sliderValue(field: KeyGeometryField, own: Float?, global: Float): Float =
        (own ?: global).coerceIn(field.sliderRange.start, field.sliderRange.endInclusive)

    /** A dragged slider value snapped to the field's increment (the global slider's step). */
    fun snap(field: KeyGeometryField, raw: Float): Float {
        val steps = ((raw - field.sliderRange.start) / field.increment).roundToInt()
        val v = field.sliderRange.start + steps * field.increment
        // Round to 2 decimals so 0.05 steps store as 1.35, not 1.3499999.
        return ((v * 100f).roundToInt() / 100f).coerceIn(field.sliderRange.start, field.sliderRange.endInclusive)
    }

    /** Readout of a value in the global setting's own display unit. */
    fun display(field: KeyGeometryField, value: Float): String = when (field) {
        KeyGeometryField.HORIZONTAL_GAP, KeyGeometryField.VERTICAL_GAP -> "$value%"
        // The switch itself shows on/off; no untranslated readout.
        KeyGeometryField.BORDER -> ""
        KeyGeometryField.BORDER_RADIUS -> "${value.roundToInt()}%"
        KeyGeometryField.BORDER_WIDTH -> "${value.roundToInt()}dp"
    }

    /** Chip state: true when any field differs from the global setting for this layout. */
    fun hasOverrides(entry: Map<KeyGeometryField, LayoutKeyGeometry.Choice>?, declared: Map<KeyGeometryField, Float>): Boolean =
        LayoutKeyGeometry.effective(entry, declared).isNotEmpty()
}

/**
 * Each field's GLOBAL value in the preference's own unit, read as Settings → Appearance and
 * Config read them (absent = the compiled default).
 */
internal fun globalKeyGeometryValues(prefs: android.content.SharedPreferences): Map<KeyGeometryField, Float> {
    fun dip(key: String, def: Float) = Config.dipPrefValue(prefs, key).let { if (it < 0f) def else it }
    return mapOf(
        KeyGeometryField.HORIZONTAL_GAP to dip("key_horizontal_margin", Defaults.KEY_HORIZONTAL_MARGIN),
        KeyGeometryField.VERTICAL_GAP to dip("key_vertical_margin", Defaults.KEY_VERTICAL_MARGIN),
        KeyGeometryField.BORDER to if (prefs.getBoolean("border_config", Defaults.BORDER_CONFIG)) 1f else 0f,
        KeyGeometryField.BORDER_RADIUS to
            (runCatching { prefs.getInt("custom_border_radius", Defaults.CUSTOM_BORDER_RADIUS) }.getOrNull()
                ?: Defaults.CUSTOM_BORDER_RADIUS).toFloat(),
        KeyGeometryField.BORDER_WIDTH to dip("custom_border_line_width", Defaults.CUSTOM_BORDER_LINE_WIDTH.toFloat()),
    )
}

/** The XML key-geometry defaults of a layout entry (predefined layouts declare none). */
internal fun declaredKeyGeometryOf(layout: LayoutsPreference.Layout): Map<KeyGeometryField, Float> = when (layout) {
    is LayoutsPreference.CustomLayout -> LayoutKeyGeometry.declaredValues(layout.parsed?.declared_key_geometry)
    else -> emptyMap()
}

/**
 * Editor for a layout's key gaps and border. Each field has "Use global setting" (ticked = the
 * field follows Settings → Appearance and, under a keyboard look, the look) and a slider/switch
 * with the global control's range and step, showing the inherited value while ticked.
 *
 * @param globals each field's current GLOBAL value in the preference's unit.
 */
@Composable
internal fun LayoutKeyGeometryDialog(
    layout: LayoutsPreference.Layout,
    globals: Map<KeyGeometryField, Float>,
    onDismiss: () -> Unit,
    onSave: (Map<KeyGeometryField, Float?>) -> Unit,
) {
    val declared = remember(layout) { declaredKeyGeometryOf(layout) }
    val choices = remember(layout) {
        mutableStateMapOf<KeyGeometryField, Float?>().apply {
            putAll(LayoutKeyGeometryEditor.initialChoices(layout.keyGeometry, declared))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_key_geometry_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.layout_key_geometry_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (field in KeyGeometryField.entries) {
                    val own = choices[field]
                    val global = globals[field] ?: 0f
                    val inheriting = own == null
                    val shown = LayoutKeyGeometryEditor.sliderValue(field, own, global)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(titleOf(field)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = LayoutKeyGeometryEditor.display(field, shown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (inheriting) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = inheriting,
                            onCheckedChange = { follow -> choices[field] = if (follow) null else shown },
                        )
                        Text(
                            text = stringResource(R.string.layout_key_geometry_use_global),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (field.isToggle) {
                        Switch(
                            checked = shown == 1f,
                            enabled = !inheriting,
                            onCheckedChange = { on -> choices[field] = if (on) 1f else 0f },
                        )
                    } else {
                        Slider(
                            value = shown,
                            onValueChange = { choices[field] = LayoutKeyGeometryEditor.snap(field, it) },
                            valueRange = field.sliderRange,
                            // CLAUDE.md "Compose slider steps": stops BETWEEN the ends.
                            steps = sliderSteps(field.sliderRange, field.increment),
                            enabled = !inheriting,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(choices.toMap()) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** The global control's title, reused so the editor names each field as Appearance does. */
private fun titleOf(field: KeyGeometryField): Int = when (field) {
    KeyGeometryField.HORIZONTAL_GAP -> R.string.appearance_key_horizontal_margin_title
    KeyGeometryField.VERTICAL_GAP -> R.string.appearance_key_vertical_margin_title
    KeyGeometryField.BORDER -> R.string.appearance_custom_border_title
    KeyGeometryField.BORDER_RADIUS -> R.string.appearance_border_radius_title
    KeyGeometryField.BORDER_WIDTH -> R.string.appearance_border_line_width_title
}
