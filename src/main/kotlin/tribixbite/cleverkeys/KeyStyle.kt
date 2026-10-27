package tribixbite.cleverkeys

import tribixbite.cleverkeys.theme.ThemeValues

/**
 * Per-key (and per-row) styling from a layout XML: `<key key_background="#…" …>` or the same
 * attributes on `<row>` as defaults for its keys (docs/wiki/layouts/layout-xml-reference.md).
 *
 * Precedence, most specific first: `<key>` attribute > `<row>` attribute > the user's per-layout
 * key geometry (Layout Manager) > the layout's `<keyboard key_*>` defaults > the effective
 * theme / look > the global Appearance settings. A field left null follows the next level.
 *
 * Geometry fields use the `<keyboard key_*>` attributes' own names and units
 * ([KeyGeometryField]: gaps in the global preference's percent, border on/off, radius in percent
 * of the key width, line width in dp). A per-key gap shrinks or widens only the DRAWN frame
 * inside the key's cell: the cell — hit-testing, the swipe engines' key centres, TalkBack
 * bounds — never moves ([KeyStyles.frame]).
 *
 * Pure JVM (`KeyStyleTest`).
 */
data class KeyStyle(
    val geometry: Map<KeyGeometryField, Float> = emptyMap(),
    val background: Int? = null,
    val backgroundPressed: Int? = null,
    val borderColor: Int? = null,
    val labelColor: Int? = null,
    val labelColorPressed: Int? = null,
) {
    val isEmpty: Boolean
        get() = geometry.isEmpty() && background == null && backgroundPressed == null &&
            borderColor == null && labelColor == null && labelColorPressed == null

    /** This style over [base] (a row's): every field this one leaves unset takes [base]'s. */
    fun over(base: KeyStyle?): KeyStyle {
        if (base == null) return this
        return KeyStyle(
            geometry = base.geometry + geometry,
            background = background ?: base.background,
            backgroundPressed = backgroundPressed ?: base.backgroundPressed,
            borderColor = borderColor ?: base.borderColor,
            labelColor = labelColor ?: base.labelColor,
            labelColorPressed = labelColorPressed ?: base.labelColorPressed,
        )
    }
}

/** Parsing and frame placement for [KeyStyle]. */
object KeyStyles {

    const val BACKGROUND = "key_background"
    const val BACKGROUND_PRESSED = "key_background_pressed"
    const val BORDER_COLOR = "key_border_color"
    const val LABEL_COLOR = "key_label_color"
    const val LABEL_COLOR_PRESSED = "key_label_color_pressed"

    /** Every styling attribute `<key>` and `<row>` accept (geometry first, as on `<keyboard>`). */
    val ATTRIBUTES: List<String> =
        KeyGeometryField.entries.map { it.xmlAttribute } + listOf(BACKGROUND, BACKGROUND_PRESSED, BORDER_COLOR, LABEL_COLOR, LABEL_COLOR_PRESSED)

    /**
     * Read the styling attributes of one `<key>` / `<row>` element through [attr] (attribute name
     * → raw value or null). Invalid values are dropped and reported in [problems] as
     * "`<element> name="value"`"; null when the element carries no valid styling.
     */
    fun parse(element: String, attr: (String) -> String?, problems: MutableList<String>): KeyStyle? {
        val geometry = LinkedHashMap<KeyGeometryField, Float>()
        for (f in KeyGeometryField.entries) {
            val raw = attr(f.xmlAttribute) ?: continue
            LayoutKeyGeometry.parseValue(f, raw)?.let { geometry[f] = it }
                ?: problems.add("<$element> ${f.xmlAttribute}=\"$raw\"")
        }
        fun color(name: String): Int? {
            val raw = attr(name) ?: return null
            return ThemeValues.parseColor(raw) ?: run { problems.add("<$element> $name=\"$raw\""); null }
        }
        val style = KeyStyle(
            geometry = geometry,
            background = color(BACKGROUND),
            backgroundPressed = color(BACKGROUND_PRESSED),
            borderColor = color(BORDER_COLOR),
            labelColor = color(LABEL_COLOR),
            labelColorPressed = color(LABEL_COLOR_PRESSED),
        )
        return style.takeIf { !it.isEmpty }
    }

    /** A drawn key frame inside its cell, in px. */
    data class Frame(val left: Float, val top: Float, val width: Float, val height: Float) {
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    /**
     * Where a key with its own gaps is drawn. `Keyboard2View.onDraw` walks the row with the
     * layout's gaps: a frame starts at [x] = cell left + [layoutHGap] / 2, is
     * cell width − [layoutHGap] wide (same vertically). A key with its own gaps [keyHGap] /
     * [keyVGap] is placed so its frame stays CENTRED on the same cell — the cell centre is what
     * hit-testing and both swipe engines use, so styling can never move a key's centre.
     */
    fun frame(
        x: Float, y: Float, cellWidth: Float, cellHeight: Float,
        layoutHGap: Float, layoutVGap: Float, keyHGap: Float, keyVGap: Float,
    ): Frame = Frame(
        left = x + (keyHGap - layoutHGap) / 2f,
        top = y + (keyVGap - layoutVGap) / 2f,
        width = cellWidth - keyHGap,
        height = cellHeight - keyVGap,
    )
}
