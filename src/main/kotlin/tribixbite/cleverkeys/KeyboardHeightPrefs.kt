package tribixbite.cleverkeys

import android.content.SharedPreferences

/**
 * The four keyboard-height preferences and THE rule for which one applies
 * (spec `docs/specs/foldable-keyboard-height.md`).
 *
 * Height is stored per orientation (#161) and per screen on foldables: the outer (folded)
 * screen uses [PORTRAIT] / [LANDSCAPE], the inner (unfolded) screen uses [PORTRAIT_UNFOLDED] /
 * [LANDSCAPE_UNFOLDED]. An inner-screen key that was never set **follows the outer-screen value
 * of the same orientation**, not the built-in default. Before 2026-10-20 nothing wrote the inner
 * keys, so every unfolded inner screen fell back to the default height (user report: "keyboard
 * height always changes to default when using the inner screen").
 *
 * Every reader — `Config.refresh`, `KeyboardDimensionsHelper`, the Settings sliders — goes
 * through [percent], so the rule cannot drift between them.
 */
object KeyboardHeightPrefs {
    const val PORTRAIT = "keyboard_height"
    const val LANDSCAPE = "keyboard_height_landscape"
    const val PORTRAIT_UNFOLDED = "keyboard_height_unfolded"
    const val LANDSCAPE_UNFOLDED = "keyboard_height_landscape_unfolded"

    /** The two inner-screen keys: never defaulted, so "unset" keeps meaning "follow outer". */
    val UNFOLDED_KEYS: Set<String> = setOf(PORTRAIT_UNFOLDED, LANDSCAPE_UNFOLDED)

    /** All four keys. */
    val ALL_KEYS: Set<String> = setOf(PORTRAIT, LANDSCAPE) + UNFOLDED_KEYS

    /** The outer-screen key for an orientation. */
    fun outerKey(landscape: Boolean): String = if (landscape) LANDSCAPE else PORTRAIT

    /** The inner-screen key for an orientation. */
    fun innerKey(landscape: Boolean): String = if (landscape) LANDSCAPE_UNFOLDED else PORTRAIT_UNFOLDED

    /** The key the keyboard reads for this orientation and fold state. */
    fun keyFor(landscape: Boolean, unfolded: Boolean): String =
        if (unfolded) innerKey(landscape) else outerKey(landscape)

    /** The built-in default for an orientation. */
    fun default(landscape: Boolean): Int =
        if (landscape) Defaults.KEYBOARD_HEIGHT_LANDSCAPE else Defaults.KEYBOARD_HEIGHT_PORTRAIT

    /**
     * Height percent for [landscape] / [unfolded], read through [contains] and [getInt]
     * (Android-free, so the rule is unit-testable on the pure JVM runner), clamped to
     * [SettingsRanges.KEYBOARD_HEIGHT] — the range the sliders offer.
     */
    fun percent(
        landscape: Boolean,
        unfolded: Boolean,
        contains: (String) -> Boolean,
        getInt: (String, Int) -> Int,
    ): Int = followOuter(outerKey(landscape), unfolded, default(landscape), contains, getInt)
        .coerceIn(SettingsRanges.KEYBOARD_HEIGHT)

    /**
     * THE fold rule for every orientation-scoped preference stored as `<outerKey>` (outer screen)
     * and `<outerKey>_unfolded` (a foldable's inner screen): the outer value, or on the inner screen
     * the inner value when it was ever set and the outer value otherwise. Shared by the keyboard
     * height ([percent]) and the bottom/left/right margins (`Config.orientedPercent`).
     */
    fun followOuter(
        outerKey: String,
        unfolded: Boolean,
        default: Int,
        contains: (String) -> Boolean,
        getInt: (String, Int) -> Int,
    ): Int {
        val outer = getInt(outerKey, default)
        if (!unfolded) return outer
        val inner = outerKey + UNFOLDED_SUFFIX
        return if (contains(inner)) getInt(inner, outer) else outer
    }

    /** Suffix of every inner-screen key. */
    const val UNFOLDED_SUFFIX = "_unfolded"

    /** [percent] over [prefs], recovering from type-corrupted values like the rest of Config. */
    fun percent(prefs: SharedPreferences, landscape: Boolean, unfolded: Boolean): Int =
        percent(landscape, unfolded, prefs::contains) { key, def -> Config.safeGetInt(prefs, key, def) }
}
