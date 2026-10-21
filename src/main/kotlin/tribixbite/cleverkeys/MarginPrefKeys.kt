package tribixbite.cleverkeys

/**
 * Every keyboard-margin preference key, defined once (code-quality audit 2026-10-20, L2 / L13).
 *
 * A margin is stored per side, per orientation and per screen: `<base>_portrait` /
 * `<base>_landscape` on the outer (folded) screen and the same plus
 * [KeyboardHeightPrefs.UNFOLDED_SUFFIX] on a foldable's inner screen, which follows the outer value
 * until set ([KeyboardHeightPrefs.followOuter]). The bottom margin is a percent of the screen
 * height ([SettingsRanges.MARGIN_BOTTOM_PERCENT]); the sides are a percent of its width
 * ([SettingsRanges.MARGIN_SIDE_PERCENT]). These literals used to be restated in Config, its dp→%
 * migration, the backup importer and validator, the reset policy and three Settings files.
 *
 * The outer-screen keys are `const` so they can be `when` labels.
 */
object MarginPrefKeys {
    const val BOTTOM = "margin_bottom"
    const val LEFT = "margin_left"
    const val RIGHT = "margin_right"

    const val BOTTOM_PORTRAIT = "margin_bottom_portrait"
    const val BOTTOM_LANDSCAPE = "margin_bottom_landscape"
    const val LEFT_PORTRAIT = "margin_left_portrait"
    const val LEFT_LANDSCAPE = "margin_left_landscape"
    const val RIGHT_PORTRAIT = "margin_right_portrait"
    const val RIGHT_LANDSCAPE = "margin_right_landscape"

    /** The key for one margin [base] in one orientation, on the outer or ([unfolded]) inner screen. */
    fun key(base: String, landscape: Boolean, unfolded: Boolean = false): String =
        base + (if (landscape) "_landscape" else "_portrait") +
            (if (unfolded) KeyboardHeightPrefs.UNFOLDED_SUFFIX else "")

    /** The four keys of one margin [base]: portrait, landscape, then their inner-screen twins. */
    fun allOf(base: String): List<String> =
        listOf(false, true).flatMap { unfolded -> listOf(false, true).map { key(base, it, unfolded) } }

    /**
     * The built-in default percent of one margin [base] in one orientation
     * (`Defaults.MARGIN_*`; the inner screen has no defaults of its own).
     */
    fun defaultPercent(base: String, landscape: Boolean): Int = when (base) {
        BOTTOM -> if (landscape) Defaults.MARGIN_BOTTOM_LANDSCAPE else Defaults.MARGIN_BOTTOM_PORTRAIT
        LEFT -> if (landscape) Defaults.MARGIN_LEFT_LANDSCAPE else Defaults.MARGIN_LEFT_PORTRAIT
        RIGHT -> if (landscape) Defaults.MARGIN_RIGHT_LANDSCAPE else Defaults.MARGIN_RIGHT_PORTRAIT
        else -> throw IllegalArgumentException("not a margin: $base")
    }

    /** [defaultPercent] for any key built by [key]. */
    fun defaultPercentOf(key: String): Int {
        val base = listOf(BOTTOM, LEFT, RIGHT).first { key.startsWith("${it}_") }
        return defaultPercent(base, key.startsWith(base + "_landscape"))
    }

    /** The four bottom-margin keys (percent of screen height). */
    val BOTTOM_KEYS: List<String> = allOf(BOTTOM)

    /** The eight left/right-margin keys (percent of screen width). */
    val SIDE_KEYS: List<String> = allOf(LEFT) + allOf(RIGHT)

    /**
     * The dp-era `horizontal_margin_*` keys and the left/right pair each was split into
     * (shared by Config's startup migration and the backup importer). The pairs follow
     * [allOf]'s order: portrait, landscape, portrait-unfolded, landscape-unfolded.
     */
    val LEGACY_HORIZONTAL: List<Pair<String, List<String>>> = allOf("horizontal_margin")
        .zip(allOf(LEFT).zip(allOf(RIGHT)) { left, right -> listOf(left, right) })
}
