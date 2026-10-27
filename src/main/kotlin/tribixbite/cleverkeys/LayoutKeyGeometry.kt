package tribixbite.cleverkeys

import java.util.Locale
import tribixbite.cleverkeys.theme.KeyboardLookGeometry

/**
 * One per-layout key-geometry field (maintainer request 2026-10-27: "layouts can override/set
 * layout-specific key gap and border, so user setting acts as global default").
 *
 * Every value is in the GLOBAL preference's own unit, so an override means exactly what the
 * same number means in Settings → Appearance: gaps in the preference's percent (0–5, step 0.05),
 * [BORDER] as 0 (off) / 1 (on), the corner radius in percent of the key width, the line width
 * in dp.
 *
 * @property prefKey the global preference this field overrides (never written by a binding).
 * @property jsonKey the field inside a `layouts` entry's [LayoutKeyGeometry.JSON_FIELD] object.
 * @property xmlAttribute the `<keyboard …>` root attribute carrying the layout's XML default.
 * @property range values accepted from the preference, a layout XML or a backup.
 * @property sliderRange the Layout Manager slider's range (the global slider's range).
 * @property increment the slider step (the global slider's step).
 */
enum class KeyGeometryField(
    val prefKey: String,
    val jsonKey: String,
    val xmlAttribute: String,
    val range: ClosedFloatingPointRange<Float>,
    val sliderRange: ClosedFloatingPointRange<Float>,
    val increment: Float,
) {
    HORIZONTAL_GAP("key_horizontal_margin", "horizontal_gap", "key_horizontal_gap", 0f..5f, 0f..5f, 0.05f),
    VERTICAL_GAP("key_vertical_margin", "vertical_gap", "key_vertical_gap", 0f..5f, 0f..5f, 0.05f),
    BORDER("border_config", "border", "key_border", 0f..1f, 0f..1f, 1f),
    BORDER_RADIUS(
        "custom_border_radius", "border_radius", "key_border_radius",
        SettingsRanges.ImportEnvelope.CUSTOM_BORDER_RADIUS.toSliderRange(),
        SettingsRanges.CUSTOM_BORDER_RADIUS.toSliderRange(), 1f,
    ),
    BORDER_WIDTH(
        "custom_border_line_width", "border_width", "key_border_width",
        SettingsRanges.CUSTOM_BORDER_LINE_WIDTH, SettingsRanges.CUSTOM_BORDER_LINE_WIDTH, 1f,
    );

    /** True for the on/off field. */
    val isToggle: Boolean get() = this == BORDER

    companion object {
        fun ofJsonKey(key: String): KeyGeometryField? = entries.firstOrNull { it.jsonKey == key }
    }
}

/**
 * Pure rules for per-layout KEY GEOMETRY overrides — the third per-layout binding beside
 * [LayoutLanguageBinding] and [LayoutThemeBinding], with the same three states PER FIELD:
 *  - absent        — no user choice; the layout XML's attribute (if valid) applies;
 *  - [Choice.Inherit] — explicitly follows the global setting, overriding an XML default
 *    (stored as [INHERIT], the counterpart of the theme binding's `"none"`);
 *  - [Choice.Value] — this layout's own value.
 *
 * Precedence for each field, most specific first (spec docs/specs/layout-system.md
 * "Per-layout key geometry"): user per-layout choice > layout XML default > the effective
 * keyboard look's geometry > the global user setting. Global preferences are never written.
 * Pure JVM (no Android types): Config, the `layouts` serializer, Layout Manager, the XML export
 * and the backup preview all call into it.
 */
object LayoutKeyGeometry {

    /** The `layouts` entry field holding the per-field choices (a JSON object). */
    const val JSON_FIELD = "key_geometry"

    /** Stored per-field value meaning "follow the global setting", even over an XML default. */
    const val INHERIT = "inherit"

    /** One field's stored choice. */
    sealed class Choice {
        object Inherit : Choice() {
            override fun toString() = "Inherit"
        }
        data class Value(val value: Float) : Choice()
    }

    /** Validated value of [field], or null when out of range / not a number. Border → 0 or 1. */
    fun validate(field: KeyGeometryField, raw: Float?): Float? {
        if (raw == null || raw.isNaN() || raw.isInfinite()) return null
        if (field.isToggle) return if (raw == 0f) 0f else if (raw == 1f) 1f else null
        return raw.takeIf { it in field.range }
    }

    /** A raw textual value (XML attribute or JSON string) of [field]; booleans for [KeyGeometryField.BORDER]. */
    fun parseValue(field: KeyGeometryField, raw: String?): Float? {
        val s = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (field.isToggle) return when (s) {
            "true", "1" -> 1f
            "false", "0" -> 0f
            else -> null
        }
        return validate(field, s.toFloatOrNull())
    }

    /**
     * Normalises a stored entry (from the preference, a backup or the UI) given as JSON-like
     * values: a number/boolean/numeric string is validated, [INHERIT] (any case) is kept, and
     * anything else — unknown fields, out-of-range or malformed values — is dropped, so a hostile
     * backup never binds an impossible geometry and never hides its layout. Empty result → null.
     */
    fun normalizeEntry(raw: Map<String, Any?>?): Map<KeyGeometryField, Choice>? {
        if (raw == null) return null
        val out = LinkedHashMap<KeyGeometryField, Choice>()
        for ((key, value) in raw) {
            val field = KeyGeometryField.ofJsonKey(key) ?: continue
            val choice = when (value) {
                is String -> if (value.trim().equals(INHERIT, ignoreCase = true)) Choice.Inherit
                    else parseValue(field, value)?.let { Choice.Value(it) }
                is Boolean -> if (field.isToggle) Choice.Value(if (value) 1f else 0f) else null
                is Number -> validate(field, value.toFloat())?.let { Choice.Value(it) }
                else -> null
            } ?: continue
            out[field] = choice
        }
        return normalized(out)
    }

    /** [entry] in canonical field order, or null when empty (an untouched entry stores nothing). */
    fun normalized(entry: Map<KeyGeometryField, Choice>?): Map<KeyGeometryField, Choice>? {
        if (entry.isNullOrEmpty()) return null
        return KeyGeometryField.entries.mapNotNull { f -> entry[f]?.let { f to it } }.toMap()
    }

    /** JSON-ready form of [entry] (border as a boolean), or null when there is nothing to store. */
    fun toRaw(entry: Map<KeyGeometryField, Choice>?): Map<String, Any>? {
        val e = normalized(entry) ?: return null
        return e.entries.associate { (f, c) ->
            f.jsonKey to when (c) {
                Choice.Inherit -> INHERIT
                is Choice.Value -> if (f.isToggle) c.value == 1f else c.value.toDouble().let { d ->
                    // Integers stay integers in the stored JSON (radius 12, not 12.0).
                    if (d == Math.rint(d)) d.toLong() else d
                }
            }
        }
    }

    /**
     * The layout's XML DEFAULTS from its root attributes ([declared] = attribute name → raw
     * value, as `KeyboardData.declared_key_geometry` holds them). Invalid values are ignored
     * here (loading stays lenient); [xmlProblems] reports them to the editor.
     */
    fun declaredValues(declared: Map<String, String>?): Map<KeyGeometryField, Float> {
        if (declared.isNullOrEmpty()) return emptyMap()
        return KeyGeometryField.entries.mapNotNull { f ->
            parseValue(f, declared[f.xmlAttribute])?.let { f to it }
        }.toMap()
    }

    /** XML attributes of [declared] whose value is not valid; the layout editor refuses them. */
    fun xmlProblems(declared: Map<String, String>?): List<String> =
        KeyGeometryField.entries.filter { f ->
            val raw = declared?.get(f.xmlAttribute)
            raw != null && parseValue(f, raw) == null
        }.map { it.xmlAttribute }

    /**
     * The overrides a layout entry actually has, per field: the user's choice when there is one
     * (Inherit removes the field), otherwise the XML default. Absent fields follow the look and
     * then the global setting.
     */
    fun effective(entry: Map<KeyGeometryField, Choice>?, declared: Map<KeyGeometryField, Float>): Map<KeyGeometryField, Float> {
        val out = LinkedHashMap<KeyGeometryField, Float>()
        for (f in KeyGeometryField.entries) {
            when (val c = entry?.get(f)) {
                Choice.Inherit -> Unit
                is Choice.Value -> out[f] = c.value
                null -> declared[f]?.let { out[f] = it }
            }
        }
        return out
    }

    /**
     * The entry to store when the user picks [choices] in Layout Manager (per field: null =
     * "Use global setting", else a value). Picking the XML default stores nothing for that field;
     * following the global setting over an XML default is stored as [Choice.Inherit]; with no
     * override at all the entry is null, so the stored preference stays byte-identical.
     */
    fun entryFor(choices: Map<KeyGeometryField, Float?>, declared: Map<KeyGeometryField, Float>): Map<KeyGeometryField, Choice>? {
        val out = LinkedHashMap<KeyGeometryField, Choice>()
        for (f in KeyGeometryField.entries) {
            val picked = validate(f, choices[f])
            val xml = declared[f]
            when {
                picked == null -> if (xml != null) out[f] = Choice.Inherit
                picked == xml -> Unit
                else -> out[f] = Choice.Value(picked)
            }
        }
        return normalized(out)
    }

    /** The geometry the keyboard draws with, in px (what [KeyboardLookGeometry.Effective] carries). */
    data class Pixels(
        val verticalMargin: Float,
        val horizontalMargin: Float,
        val borderRadius: Float,
        val borderWidth: Float,
        val borderWidthActivated: Float,
    )

    /**
     * The user's global settings in Config's units (gap fractions as `Config.key_*_margin`,
     * radius as a fraction of the key width, line width in px).
     */
    data class Globals(
        val borderOn: Boolean,
        val borderRadiusFraction: Float,
        val borderWidthPx: Float,
    )

    /**
     * Applies a layout's [overrides] on top of [base] — the geometry after the global setting and
     * the effective look ([KeyboardLookGeometry.resolve]) — so the layout wins over both.
     *
     * - Gaps: an override replaces the gap; [gapFraction] converts the stored percent exactly as
     *   Config converts the global preference (dp-scaled percent → fraction).
     * - Border: with none of the three border fields overridden, [base] is kept. Otherwise the
     *   layout's border is ON when [KeyGeometryField.BORDER] says so (or, if not overridden, when
     *   the global switch is on): the radius and width are the layout's values where set, else the
     *   global custom border's — and they beat a look's. A border turned OFF for the layout keeps
     *   [base], i.e. the theme's (or look's) own frame, as the global switch off does.
     */
    fun applyOverrides(
        base: Pixels,
        overrides: Map<KeyGeometryField, Float>,
        globals: Globals,
        keyWidth: Float,
        rowHeight: Float,
        density: Float,
        gapFraction: (Float) -> Float,
    ): Pixels {
        if (overrides.isEmpty()) return base
        val vertical = overrides[KeyGeometryField.VERTICAL_GAP]?.let { gapFraction(it) * rowHeight } ?: base.verticalMargin
        val horizontal = overrides[KeyGeometryField.HORIZONTAL_GAP]?.let { gapFraction(it) * keyWidth } ?: base.horizontalMargin
        val touchesBorder = BORDER_FIELDS.any { it in overrides }
        val borderOn = effectiveBorderOn(overrides, globals.borderOn)
        if (!touchesBorder || !borderOn) {
            return base.copy(verticalMargin = vertical, horizontalMargin = horizontal)
        }
        val radius = overrides[KeyGeometryField.BORDER_RADIUS]?.let { it / 100f * keyWidth }
            ?: (globals.borderRadiusFraction * keyWidth)
        val width = overrides[KeyGeometryField.BORDER_WIDTH]?.let { it * density } ?: globals.borderWidthPx
        return Pixels(vertical, horizontal, radius, width, width)
    }

    /**
     * [applyOverrides] on a look-resolved [KeyboardLookGeometry.Effective] (what
     * `Theme.Computed` holds); shadow, label scale and weight are the look's, untouched. Gap
     * percentages convert exactly as Config converts the global preference
     * (`TypedValue.applyDimension(DIP, v) / 100` = v × density / 100).
     */
    fun applyTo(
        resolved: KeyboardLookGeometry.Effective,
        overrides: Map<KeyGeometryField, Float>,
        globals: Globals,
        keyWidth: Float,
        rowHeight: Float,
        density: Float,
    ): KeyboardLookGeometry.Effective {
        if (overrides.isEmpty()) return resolved
        val p = applyOverrides(
            Pixels(resolved.verticalMargin, resolved.horizontalMargin, resolved.borderRadius,
                resolved.borderWidth, resolved.borderWidthActivated),
            overrides, globals, keyWidth, rowHeight, density,
        ) { percent -> percent * density / 100f }
        return resolved.copy(
            verticalMargin = p.verticalMargin,
            horizontalMargin = p.horizontalMargin,
            borderRadius = p.borderRadius,
            borderWidth = p.borderWidth,
            borderWidthActivated = p.borderWidthActivated,
        )
    }

    /**
     * Whether the user geometry layer draws the custom border for a layout: the layout's
     * [KeyGeometryField.BORDER] override when it has one, else the global `border_config`.
     * A layout that turns the border OFF therefore draws the theme's (or look's) own frame even
     * while the global custom border is on.
     */
    fun effectiveBorderOn(overrides: Map<KeyGeometryField, Float>, globalBorderOn: Boolean): Boolean =
        overrides[KeyGeometryField.BORDER]?.let { it == 1f } ?: globalBorderOn

    private val BORDER_FIELDS = listOf(KeyGeometryField.BORDER, KeyGeometryField.BORDER_RADIUS, KeyGeometryField.BORDER_WIDTH)

    /** The `<keyboard …>` start tag of a layout XML document (attributes in group 1). */
    private val ROOT_TAG = Regex("<keyboard(\\s[^>]*)?>")

    private fun attributeRegex(name: String) = Regex("\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    /** Raw key-geometry root attributes of a layout XML document (textual; for the pure backup preview). */
    fun declaredOfXml(xml: String): Map<String, String> {
        val attributes = ROOT_TAG.find(xml)?.groupValues?.get(1) ?: return emptyMap()
        return KeyGeometryField.entries.mapNotNull { f ->
            attributeRegex(f.xmlAttribute).find(attributes)?.let { m ->
                f.xmlAttribute to (m.groups[2]?.value ?: m.groups[3]?.value ?: "")
            }
        }.toMap()
    }

    /** XML text of a stored value of [field]. */
    fun formatValue(field: KeyGeometryField, value: Float): String = when {
        field.isToggle -> (value == 1f).toString()
        value == Math.rint(value.toDouble()).toFloat() -> value.toLong().toString()
        else -> value.toString()
    }

    /**
     * The layout XML to EXPORT for an entry whose stored choices are [entry]: a value sets the
     * field's root attribute, Inherit removes it, an absent field leaves the XML as written — so
     * re-importing an exported file restores the overrides. Only the root start tag is touched.
     */
    fun applyToXml(xml: String, entry: Map<KeyGeometryField, Choice>?): String {
        val e = normalized(entry) ?: return xml
        val root = ROOT_TAG.find(xml) ?: return xml
        var attributes = root.groupValues[1]
        for ((f, c) in e) {
            attributes = attributes.replace(attributeRegex(f.xmlAttribute), "")
            if (c is Choice.Value) attributes = " ${f.xmlAttribute}=\"${formatValue(f, c.value)}\"$attributes"
        }
        return xml.replaceRange(root.range, "<keyboard$attributes>")
    }
}
