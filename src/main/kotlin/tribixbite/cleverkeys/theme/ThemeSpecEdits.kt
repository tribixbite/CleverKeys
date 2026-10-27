package tribixbite.cleverkeys.theme

/**
 * The Theme Creator's edits of a custom theme's version-2 extension (the "Keyboard look"
 * section): which colour map a row edits, the seven geometry sliders with their ranges and
 * steps, and the "Use keyboard setting" (= not defined) state of every field. Pure, so the
 * editor's rules are pinned off-device (`ThemeSpecEditsTest`); the composables only render them.
 */
object ThemeSpecEdits {

    /** Which colours a colour row edits: the common look colours, or one mode's variant. */
    enum class Mode { BOTH, LIGHT, DARK }

    /** The colour [role] has in [mode]'s map of [ext] (null = not set there). */
    fun color(ext: ThemeSpec, mode: Mode, role: ThemeColorRole): Int? = when (mode) {
        Mode.BOTH -> ext.colors[role]
        Mode.LIGHT -> ext.light[role]
        Mode.DARK -> ext.dark[role]
    }

    /** [ext] with [role] set to [argb] in [mode]'s map, or removed from it when [argb] is null. */
    fun withColor(ext: ThemeSpec, mode: Mode, role: ThemeColorRole, argb: Int?): ThemeSpec {
        fun Map<ThemeColorRole, Int>.edit() = if (argb == null) this - role else this + (role to argb)
        return when (mode) {
            // The 17 DIY colours live in the theme's own scheme, never in the common extension map.
            Mode.BOTH -> { require(!role.diy) { "$role is edited in the theme's colours" }; ext.copy(colors = ext.colors.edit()) }
            Mode.LIGHT -> ext.copy(light = ext.light.edit())
            Mode.DARK -> ext.copy(dark = ext.dark.edit())
        }
    }

    /**
     * One geometry slider: the [LookGeometry] field, the slider range (within the `<theme>`
     * attribute's accepted range) and step. Units as [LookGeometry]: gaps as fractions of the key
     * width / row height, dp, a label-size multiplier, a font weight.
     */
    enum class GeometryField(val range: ClosedFloatingPointRange<Float>, val step: Float) {
        GAP_HORIZONTAL(ThemeSpecXml.GAP_RANGE, 0.01f),
        GAP_VERTICAL(ThemeSpecXml.GAP_RANGE, 0.01f),
        CORNER_RADIUS(0f..32f, 1f),
        BORDER_WIDTH(0f..4f, 0.5f),
        SHADOW(0f..4f, 0.1f),
        LABEL_SCALE(ThemeSpecXml.LABEL_SCALE_RANGE, 0.05f),
        LABEL_WEIGHT(100f..900f, 100f);

        fun get(g: LookGeometry): Float? = when (this) {
            GAP_HORIZONTAL -> g.keyGapHorizontal
            GAP_VERTICAL -> g.keyGapVertical
            CORNER_RADIUS -> g.cornerRadiusDp
            BORDER_WIDTH -> g.borderWidthDp
            SHADOW -> g.shadowDyDp
            LABEL_SCALE -> g.labelScale
            LABEL_WEIGHT -> g.labelWeight?.toFloat()
        }

        /** [g] with this field set to [v] snapped to the step and clamped, or unset (null). */
        fun set(g: LookGeometry, v: Float?): LookGeometry {
            val s = v?.let { snap(it) }
            return when (this) {
                GAP_HORIZONTAL -> g.copy(keyGapHorizontal = s)
                GAP_VERTICAL -> g.copy(keyGapVertical = s)
                CORNER_RADIUS -> g.copy(cornerRadiusDp = s)
                BORDER_WIDTH -> g.copy(borderWidthDp = s)
                SHADOW -> g.copy(shadowDyDp = s)
                LABEL_SCALE -> g.copy(labelScale = s)
                LABEL_WEIGHT -> g.copy(labelWeight = s?.toInt())
            }
        }

        /** [v] on the slider's grid inside its range (no float noise: 0.12, not 0.120000005). */
        fun snap(v: Float): Float {
            val steps = Math.round((v.coerceIn(range) - range.start) / step)
            val snapped = range.start + steps * step
            return snapped.toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(range)
        }

        /** The value a slider shows while the field is unset: what the keyboard uses without it. */
        val neutral: Float
            get() = when (this) {
                LABEL_SCALE -> 1f
                LABEL_WEIGHT -> 400f
                else -> range.start
            }
    }

    /** [ext], or null when it defines nothing (the theme stays a version-1 theme). */
    fun stored(ext: ThemeSpec): ThemeSpec? = ext.copy(name = null).takeIf { !it.isEmpty }
}
