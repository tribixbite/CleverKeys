package tribixbite.cleverkeys.theme

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The `<theme>` element of a layout XML (docs/wiki/layouts/layout-xml-reference.md):
 *
 * ```xml
 * <theme name="Harbour" key="#FFFFFF" label="#1A1B20" gap_horizontal="0.12" corner_radius="8"
 *        hints="digits" preview="bubble">
 *   <light keyboard_background="#EEEDF4"/>
 *   <dark keyboard_background="#1E1F25" key="#38393F" label="#E2E2E9"/>
 * </theme>
 * ```
 *
 * Colour attributes ([ThemeColorRole.xmlName]) are allowed on all three elements; every other
 * attribute only on `<theme>`. Parsing is TOLERANT: an unknown attribute or a malformed /
 * out-of-range value is dropped and reported in `problems` (the layout still loads; the layout
 * editor refuses to save while problems remain). Pure: the caller hands in attribute maps.
 */
object ThemeSpecXml {

    const val TAG = "theme"
    const val LIGHT_TAG = "light"
    const val DARK_TAG = "dark"

    // ── attribute names and ranges (one place: parser, writer, docs table, tests) ──
    const val NAME = "name"
    const val GAP_HORIZONTAL = "gap_horizontal"
    const val GAP_VERTICAL = "gap_vertical"
    const val CORNER_RADIUS = "corner_radius"
    const val BORDER_WIDTH = "border_width"
    const val SHADOW_DY = "shadow_dy"
    const val LABEL_SCALE = "label_scale"
    const val LABEL_WEIGHT = "label_weight"
    const val FUNCTION_CHARS = "function_chars"
    const val HINTS = "hints"
    const val PILL_KEYS = "pill_keys"
    const val LETTER_KEYS = "letter_keys"
    const val PREVIEW = "preview"
    const val PREVIEW_WIDTH = "preview_width"
    const val PREVIEW_HEIGHT = "preview_height"
    const val PREVIEW_LABEL_SCALE = "preview_label_scale"
    const val PREVIEW_CORNER = "preview_corner"
    const val PREVIEW_SHADOW = "preview_shadow"
    const val HINT_SIZE = "hint_size"
    const val HINT_WEIGHT = "hint_weight"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val BAR_SLOTS = "bar_slots"
    const val BAR_CENTRE = "bar_centre"
    const val BAR_QUOTE_TYPED = "bar_quote_typed"
    const val BAR_EMPHASIS = "bar_emphasis"

    val GAP_RANGE = 0f..KeyboardLookGeometry.MAX_GAP
    val CORNER_RADIUS_RANGE = 0f..64f
    val BORDER_WIDTH_RANGE = 0f..8f
    val SHADOW_RANGE = 0f..8f
    val LABEL_SCALE_RANGE = KeyboardLookGeometry.MIN_LABEL_SCALE..KeyboardLookGeometry.MAX_LABEL_SCALE
    val WEIGHT_RANGE = 100..900
    val PREVIEW_SCALE_RANGE = 0.5f..3f
    val PREVIEW_CORNER_RANGE = 0f..1f
    val HINT_SIZE_RANGE = 0.1f..1f
    const val MAX_FUNCTION_CHARS = 16

    /** Attribute names `<theme>` accepts besides the colour roles. */
    val THEME_ATTRIBUTES: List<String> = listOf(
        NAME, GAP_HORIZONTAL, GAP_VERTICAL, CORNER_RADIUS, BORDER_WIDTH, SHADOW_DY, LABEL_SCALE,
        LABEL_WEIGHT, FUNCTION_CHARS, HINTS, PILL_KEYS, LETTER_KEYS, PREVIEW, PREVIEW_WIDTH,
        PREVIEW_HEIGHT, PREVIEW_LABEL_SCALE, PREVIEW_CORNER, PREVIEW_SHADOW, HINT_SIZE, HINT_WEIGHT,
        DYNAMIC_COLOR, BAR_SLOTS, BAR_CENTRE, BAR_QUOTE_TYPED, BAR_EMPHASIS,
    )

    private val PILL_NAMES = mapOf("enter" to LookPillKey.ENTER, "action" to LookPillKey.ACTION, "numbers" to LookPillKey.NUMBERS)
    private val LETTER_NAMES = mapOf("emoji" to LookLetterKey.EMOJI)

    /**
     * Parse a `<theme>` element: [theme] its attributes, [light] / [dark] the attributes of its
     * `<light>` / `<dark>` children (null = absent). Problems are appended to [problems] as
     * "`<element> attribute="value"`" strings.
     */
    fun parse(
        theme: Map<String, String>,
        light: Map<String, String>?,
        dark: Map<String, String>?,
        problems: MutableList<String>,
    ): ThemeSpec {
        fun bad(element: String, attr: String, value: String) { problems.add("<$element> $attr=\"$value\"") }
        fun colors(element: String, attrs: Map<String, String>?, allowOthers: Boolean): Map<ThemeColorRole, Int> {
            if (attrs == null) return emptyMap()
            val out = LinkedHashMap<ThemeColorRole, Int>()
            for ((k, v) in attrs) {
                val role = ThemeColorRole.ofXml(k)
                when {
                    role != null -> ThemeValues.parseColor(v)?.let { out[role] = it } ?: bad(element, k, v)
                    allowOthers && k in THEME_ATTRIBUTES -> Unit
                    else -> bad(element, k, v)
                }
            }
            return out
        }
        val common = colors(TAG, theme, allowOthers = true)
        fun floatAttr(name: String, range: ClosedFloatingPointRange<Float>): Float? {
            val raw = theme[name] ?: return null
            return ThemeValues.parseFloat(raw, range) ?: run { bad(TAG, name, raw); null }
        }
        fun intAttr(name: String, range: IntRange): Int? {
            val raw = theme[name] ?: return null
            return raw.trim().toIntOrNull()?.takeIf { it in range } ?: run { bad(TAG, name, raw); null }
        }
        fun boolAttr(name: String): Boolean? {
            val raw = theme[name] ?: return null
            return ThemeValues.parseBoolean(raw) ?: run { bad(TAG, name, raw); null }
        }
        fun <T> setAttr(name: String, names: Map<String, T>): Set<T>? {
            val raw = theme[name] ?: return null
            val tokens = raw.split(',', ' ').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            val values = tokens.map { names[it] }
            if (values.any { it == null }) { bad(TAG, name, raw); return null }
            return values.filterNotNull().toCollection(LinkedHashSet())
        }
        val geometry = LookGeometry(
            keyGapHorizontal = floatAttr(GAP_HORIZONTAL, GAP_RANGE),
            keyGapVertical = floatAttr(GAP_VERTICAL, GAP_RANGE),
            cornerRadiusDp = floatAttr(CORNER_RADIUS, CORNER_RADIUS_RANGE),
            borderWidthDp = floatAttr(BORDER_WIDTH, BORDER_WIDTH_RANGE),
            shadowDyDp = floatAttr(SHADOW_DY, SHADOW_RANGE),
            labelScale = floatAttr(LABEL_SCALE, LABEL_SCALE_RANGE),
            labelWeight = intAttr(LABEL_WEIGHT, WEIGHT_RANGE),
        )
        val functionChars = theme[FUNCTION_CHARS]?.let { raw ->
            if (raw.length <= MAX_FUNCTION_CHARS) raw else { bad(TAG, FUNCTION_CHARS, raw); null }
        }
        val legends = theme[HINTS]?.let { raw ->
            when (raw.trim().lowercase(Locale.ROOT)) {
                "none" -> LookLegends.NONE
                "digits" -> LookLegends.DIGITS
                "all" -> LookLegends.ALL
                else -> { bad(TAG, HINTS, raw); null }
            }
        }
        val preview = theme[PREVIEW]?.let { raw ->
            val shape = LookPreviewShape.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            if (shape == null) { bad(TAG, PREVIEW, raw); null } else LookPreview(
                shape = shape,
                widthScale = floatAttr(PREVIEW_WIDTH, PREVIEW_SCALE_RANGE) ?: 1f,
                heightScale = floatAttr(PREVIEW_HEIGHT, PREVIEW_SCALE_RANGE) ?: 1f,
                labelScale = floatAttr(PREVIEW_LABEL_SCALE, PREVIEW_SCALE_RANGE) ?: 1f,
                cornerScale = floatAttr(PREVIEW_CORNER, PREVIEW_CORNER_RANGE) ?: LookPreview.NONE.cornerScale,
                shadow = boolAttr(PREVIEW_SHADOW) ?: false,
            )
        }
        if (theme[PREVIEW] == null) {
            // Size attributes without a shape have nothing to size.
            for (a in listOf(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_LABEL_SCALE, PREVIEW_CORNER, PREVIEW_SHADOW)) {
                theme[a]?.let { bad(TAG, a, it) }
            }
        }
        val hintSize = floatAttr(HINT_SIZE, HINT_SIZE_RANGE)
        val hintWeight = intAttr(HINT_WEIGHT, WEIGHT_RANGE)
        val hintStyle = if (hintSize == null && hintWeight == null) null else LookHintStyle(
            sizeFraction = hintSize ?: LookHintStyle.DEFAULT.sizeFraction,
            weight = hintWeight ?: LookHintStyle.DEFAULT.weight,
        )
        // The suggestion-bar arrangement (LookBar): any of its three attributes defines it, the
        // others take their defaults (3 slots, best in the centre, typed word unquoted).
        val barSlots = intAttr(BAR_SLOTS, LookBar.MIN_SLOTS..LookBar.MAX_SLOTS)
        val barCentre = boolAttr(BAR_CENTRE)
        val barQuote = boolAttr(BAR_QUOTE_TYPED)
        val barEmphasis = theme[BAR_EMPHASIS]?.let { raw ->
            BarEmphasis.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: run { bad(TAG, BAR_EMPHASIS, raw); null }
        }
        val bar = if (barSlots == null && barCentre == null && barQuote == null && barEmphasis == null) null else LookBar(
            slots = barSlots ?: LookBar.DEFAULT_SLOTS,
            centreBest = barCentre ?: true,
            quoteTyped = barQuote ?: false,
            emphasis = barEmphasis ?: BarEmphasis.COMMIT,
        )
        return ThemeSpec(
            name = theme[NAME]?.trim()?.takeIf { it.isNotEmpty() },
            colors = common,
            light = colors(LIGHT_TAG, light, allowOthers = false),
            dark = colors(DARK_TAG, dark, allowOthers = false),
            geometry = geometry,
            functionChars = functionChars,
            legends = legends,
            pillKeys = setAttr(PILL_KEYS, PILL_NAMES),
            letterKeys = setAttr(LETTER_KEYS, LETTER_NAMES),
            preview = preview,
            hintStyle = hintStyle,
            dynamicColor = boolAttr(DYNAMIC_COLOR),
            bar = bar,
        )
    }

    /**
     * The `<theme>` element for [spec] (one attribute per line, children only when the variant
     * has colours), indented by [indent]. [parse] of the result returns [spec] again (pinned by
     * `ThemeSpecTest`), which is what makes XML → custom theme → XML lossless.
     */
    fun write(spec: ThemeSpec, indent: String = "  "): String {
        val attrs = ArrayList<Pair<String, String>>()
        spec.name?.let { attrs.add(NAME to it) }
        for (role in ThemeColorRole.entries) spec.colors[role]?.let { attrs.add(role.xmlName to ThemeValues.formatColor(it)) }
        val g = spec.geometry
        g.keyGapHorizontal?.let { attrs.add(GAP_HORIZONTAL to ThemeValues.formatFloat(it)) }
        g.keyGapVertical?.let { attrs.add(GAP_VERTICAL to ThemeValues.formatFloat(it)) }
        g.cornerRadiusDp?.let { attrs.add(CORNER_RADIUS to ThemeValues.formatFloat(it)) }
        g.borderWidthDp?.let { attrs.add(BORDER_WIDTH to ThemeValues.formatFloat(it)) }
        g.shadowDyDp?.let { attrs.add(SHADOW_DY to ThemeValues.formatFloat(it)) }
        g.labelScale?.let { attrs.add(LABEL_SCALE to ThemeValues.formatFloat(it)) }
        g.labelWeight?.let { attrs.add(LABEL_WEIGHT to it.toString()) }
        spec.functionChars?.let { attrs.add(FUNCTION_CHARS to it) }
        spec.legends?.let { attrs.add(HINTS to it.name.lowercase(Locale.ROOT)) }
        spec.pillKeys?.let { set -> attrs.add(PILL_KEYS to PILL_NAMES.filterValues { it in set }.keys.joinToString(" ")) }
        spec.letterKeys?.let { set -> attrs.add(LETTER_KEYS to LETTER_NAMES.filterValues { it in set }.keys.joinToString(" ")) }
        spec.preview?.let { p ->
            attrs.add(PREVIEW to p.shape.name.lowercase(Locale.ROOT))
            attrs.add(PREVIEW_WIDTH to ThemeValues.formatFloat(p.widthScale))
            attrs.add(PREVIEW_HEIGHT to ThemeValues.formatFloat(p.heightScale))
            attrs.add(PREVIEW_LABEL_SCALE to ThemeValues.formatFloat(p.labelScale))
            attrs.add(PREVIEW_CORNER to ThemeValues.formatFloat(p.cornerScale))
            attrs.add(PREVIEW_SHADOW to p.shadow.toString())
        }
        spec.hintStyle?.let {
            attrs.add(HINT_SIZE to ThemeValues.formatFloat(it.sizeFraction))
            attrs.add(HINT_WEIGHT to it.weight.toString())
        }
        spec.dynamicColor?.let { attrs.add(DYNAMIC_COLOR to it.toString()) }
        spec.bar?.let {
            attrs.add(BAR_SLOTS to it.slots.toString())
            attrs.add(BAR_CENTRE to it.centreBest.toString())
            attrs.add(BAR_QUOTE_TYPED to it.quoteTyped.toString())
            attrs.add(BAR_EMPHASIS to it.emphasis.name.lowercase())
        }

        val inner = "$indent  "
        val sb = StringBuilder()
        sb.append(indent).append('<').append(TAG)
        for ((k, v) in attrs) sb.append('\n').append(inner).append("  ").append(k).append("=\"").append(escape(v)).append('"')
        val children = listOf(LIGHT_TAG to spec.light, DARK_TAG to spec.dark).filter { it.second.isNotEmpty() }
        if (children.isEmpty()) {
            sb.append("/>")
            return sb.toString()
        }
        sb.append(">")
        for ((tag, colors) in children) {
            sb.append('\n').append(inner).append('<').append(tag)
            for (role in ThemeColorRole.entries) colors[role]?.let {
                sb.append(' ').append(role.xmlName).append("=\"").append(ThemeValues.formatColor(it)).append('"')
            }
            sb.append("/>")
        }
        sb.append('\n').append(indent).append("</").append(TAG).append('>')
        return sb.toString()
    }

    /** XML attribute escaping (names and function characters are free text). */
    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** A `<theme>` start tag or empty element anywhere in a layout document. */
    private val THEME_ELEMENT = Regex("<theme\\b([^>]*?)(/>|>[\\s\\S]*?</theme>)")
    private val KEYBOARD_ROOT = Regex("<keyboard(\\s[^>]*)?>")
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")

    /**
     * The layout XML with its `<theme>` element replaced by [spec] (or removed when [spec] is
     * null; inserted as the first child of `<keyboard>` when there was none). Used by Layout
     * Manager's Export XML to embed the layout's effective custom theme.
     */
    fun replaceInLayout(xml: String, spec: ThemeSpec?): String {
        // A layout's comments may mention "<theme>" or "<keyboard>" (the worked example does):
        // only matches outside comments count.
        val comments = COMMENT.findAll(xml).map { it.range }.toList()
        fun outside(m: MatchResult) = comments.none { m.range.first in it }
        val existing = THEME_ELEMENT.findAll(xml).firstOrNull(::outside)
        val element = spec?.let { write(it) }
        if (existing != null) {
            if (element == null) {
                // Drop the element and the whitespace-only remainder of its line.
                val before = xml.substring(0, existing.range.first).trimEnd(' ', '\t')
                val after = xml.substring(existing.range.last + 1).let { if (it.startsWith("\n")) it.substring(1) else it }
                return before + after
            }
            val lineStart = xml.lastIndexOf('\n', existing.range.first).let { it + 1 }
            return xml.substring(0, lineStart) + element + xml.substring(existing.range.last + 1)
        }
        if (element == null) return xml
        val root = KEYBOARD_ROOT.findAll(xml).firstOrNull(::outside) ?: return xml
        return xml.substring(0, root.range.last + 1) + "\n" + element + xml.substring(root.range.last + 1)
    }
}

/**
 * JSON form of a [ThemeSpec] — the `look` extension of a custom theme (`CustomTheme.toJson`,
 * format version 2) and therefore of the backup's `custom_themes` section. Only defined fields
 * are written; unknown keys are ignored on read so a newer build's extension still loads.
 */
object ThemeSpecJson {

    fun toJson(spec: ThemeSpec): JSONObject = JSONObject().apply {
        spec.name?.let { put("name", it) }
        if (spec.colors.isNotEmpty()) put("colors", colorsJson(spec.colors))
        if (spec.light.isNotEmpty()) put("light", colorsJson(spec.light))
        if (spec.dark.isNotEmpty()) put("dark", colorsJson(spec.dark))
        val g = spec.geometry
        val geometry = JSONObject().apply {
            g.keyGapHorizontal?.let { put("gapHorizontal", num(it)) }
            g.keyGapVertical?.let { put("gapVertical", num(it)) }
            g.cornerRadiusDp?.let { put("cornerRadiusDp", num(it)) }
            g.borderWidthDp?.let { put("borderWidthDp", num(it)) }
            g.shadowDyDp?.let { put("shadowDyDp", num(it)) }
            g.labelScale?.let { put("labelScale", num(it)) }
            g.labelWeight?.let { put("labelWeight", it) }
        }
        if (geometry.length() > 0) put("geometry", geometry)
        spec.functionChars?.let { put("functionChars", it) }
        spec.legends?.let { put("hints", it.name) }
        spec.pillKeys?.let { put("pillKeys", JSONArray(it.map { k -> k.name })) }
        spec.letterKeys?.let { put("letterKeys", JSONArray(it.map { k -> k.name })) }
        spec.preview?.let { p ->
            put("preview", JSONObject().apply {
                put("shape", p.shape.name)
                put("width", num(p.widthScale))
                put("height", num(p.heightScale))
                put("labelScale", num(p.labelScale))
                put("corner", num(p.cornerScale))
                put("shadow", p.shadow)
            })
        }
        spec.hintStyle?.let { h ->
            put("hintStyle", JSONObject().apply {
                put("size", num(h.sizeFraction))
                put("weight", h.weight)
            })
        }
        spec.dynamicColor?.let { put("dynamicColor", it) }
        spec.bar?.let { b ->
            put("bar", JSONObject().apply {
                put("slots", b.slots)
                put("centre", b.centreBest)
                put("quoteTyped", b.quoteTyped)
                put("emphasis", b.emphasis.name)
            })
        }
    }

    /**
     * Parse [json]. Strict about TYPES (a value of the wrong type throws, so a corrupt backup is
     * refused as a whole by the backup validator) but tolerant of unknown keys and enum names
     * from a newer build (dropped).
     */
    fun fromJson(json: JSONObject): ThemeSpec {
        val g = json.optJSONObject("geometry")
        fun f(o: JSONObject?, key: String): Float? = if (o != null && o.has(key)) o.getDouble(key).toFloat() else null
        fun i(o: JSONObject?, key: String): Int? = if (o != null && o.has(key)) o.getInt(key) else null
        fun <T : Enum<T>> enumSet(key: String, values: Array<T>): Set<T>? {
            val arr = json.optJSONArray(key) ?: return null
            return (0 until arr.length()).mapNotNull { idx -> values.firstOrNull { it.name == arr.getString(idx) } }
                .toCollection(LinkedHashSet())
        }
        val previewJson = json.optJSONObject("preview")
        val preview = previewJson?.let { p ->
            LookPreviewShape.entries.firstOrNull { it.name == p.getString("shape") }?.let { shape ->
                LookPreview(
                    shape = shape,
                    widthScale = f(p, "width") ?: 1f,
                    heightScale = f(p, "height") ?: 1f,
                    labelScale = f(p, "labelScale") ?: 1f,
                    cornerScale = f(p, "corner") ?: LookPreview.NONE.cornerScale,
                    shadow = p.optBoolean("shadow", false),
                )
            }
        }
        val hint = json.optJSONObject("hintStyle")
        val barJson = json.optJSONObject("bar")
        // An out-of-range slot count (hand-edited or from a newer build) drops the arrangement.
        val bar = barJson?.let { b ->
            val slots = i(b, "slots") ?: LookBar.DEFAULT_SLOTS
            if (slots !in LookBar.MIN_SLOTS..LookBar.MAX_SLOTS) null
            // An unknown emphasis name (a newer build) falls back to the default.
            else LookBar(
                slots, b.optBoolean("centre", true), b.optBoolean("quoteTyped", false),
                BarEmphasis.entries.firstOrNull { it.name == b.optString("emphasis") } ?: BarEmphasis.COMMIT,
            )
        }
        return sanitize(ThemeSpec(
            name = if (json.has("name")) json.getString("name") else null,
            colors = colors(json.optJSONObject("colors")),
            light = colors(json.optJSONObject("light")),
            dark = colors(json.optJSONObject("dark")),
            geometry = LookGeometry(
                keyGapHorizontal = f(g, "gapHorizontal"),
                keyGapVertical = f(g, "gapVertical"),
                cornerRadiusDp = f(g, "cornerRadiusDp"),
                borderWidthDp = f(g, "borderWidthDp"),
                shadowDyDp = f(g, "shadowDyDp"),
                labelScale = f(g, "labelScale"),
                labelWeight = i(g, "labelWeight"),
            ),
            functionChars = if (json.has("functionChars")) json.getString("functionChars") else null,
            legends = if (json.has("hints")) LookLegends.entries.firstOrNull { it.name == json.getString("hints") } else null,
            pillKeys = enumSet("pillKeys", LookPillKey.entries.toTypedArray()),
            letterKeys = enumSet("letterKeys", LookLetterKey.entries.toTypedArray()),
            preview = preview,
            hintStyle = hint?.let {
                LookHintStyle(
                    sizeFraction = f(it, "size") ?: LookHintStyle.DEFAULT.sizeFraction,
                    weight = i(it, "weight") ?: LookHintStyle.DEFAULT.weight,
                )
            },
            dynamicColor = if (json.has("dynamicColor")) json.getBoolean("dynamicColor") else null,
            bar = bar,
        ))
    }

    /**
     * [spec] with every value outside the ranges a layout `<theme>` accepts dropped (geometry,
     * hint style) or clamped (preview sizes), so a hand-edited or hostile backup can never bind
     * an impossible geometry. Values the XML parser accepts pass unchanged.
     */
    fun sanitize(spec: ThemeSpec): ThemeSpec {
        fun Float?.within(r: ClosedFloatingPointRange<Float>): Float? = this?.takeIf { !it.isNaN() && it in r }
        val g = spec.geometry
        return spec.copy(
            geometry = LookGeometry(
                keyGapHorizontal = g.keyGapHorizontal.within(ThemeSpecXml.GAP_RANGE),
                keyGapVertical = g.keyGapVertical.within(ThemeSpecXml.GAP_RANGE),
                cornerRadiusDp = g.cornerRadiusDp.within(ThemeSpecXml.CORNER_RADIUS_RANGE),
                borderWidthDp = g.borderWidthDp.within(ThemeSpecXml.BORDER_WIDTH_RANGE),
                shadowDyDp = g.shadowDyDp.within(ThemeSpecXml.SHADOW_RANGE),
                labelScale = g.labelScale.within(ThemeSpecXml.LABEL_SCALE_RANGE),
                labelWeight = g.labelWeight?.takeIf { it in ThemeSpecXml.WEIGHT_RANGE },
            ),
            functionChars = spec.functionChars?.takeIf { it.length <= ThemeSpecXml.MAX_FUNCTION_CHARS },
            preview = spec.preview?.let { p ->
                fun Float.clamp(r: ClosedFloatingPointRange<Float>) = if (isNaN()) r.start else coerceIn(r)
                p.copy(
                    widthScale = p.widthScale.clamp(ThemeSpecXml.PREVIEW_SCALE_RANGE),
                    heightScale = p.heightScale.clamp(ThemeSpecXml.PREVIEW_SCALE_RANGE),
                    labelScale = p.labelScale.clamp(ThemeSpecXml.PREVIEW_SCALE_RANGE),
                    cornerScale = p.cornerScale.clamp(ThemeSpecXml.PREVIEW_CORNER_RANGE),
                )
            },
            hintStyle = spec.hintStyle?.takeIf {
                !it.sizeFraction.isNaN() && it.sizeFraction in ThemeSpecXml.HINT_SIZE_RANGE && it.weight in ThemeSpecXml.WEIGHT_RANGE
            },
        )
    }

    private fun colorsJson(colors: Map<ThemeColorRole, Int>): JSONObject = JSONObject().apply {
        for (role in ThemeColorRole.entries) colors[role]?.let { put(role.jsonKey, it) }
    }

    private fun colors(o: JSONObject?): Map<ThemeColorRole, Int> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<ThemeColorRole, Int>()
        for (role in ThemeColorRole.entries) if (o.has(role.jsonKey)) out[role] = o.getInt(role.jsonKey)
        return out
    }

    /** A float as the decimal its shortest text denotes (0.15, not 0.15000000596…). */
    private fun num(v: Float): Double = v.toString().toDouble()
}
