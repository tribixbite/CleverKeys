package tribixbite.cleverkeys

import tribixbite.cleverkeys.theme.KeyboardLooks

/**
 * The `theme` attribute each PREDEFINED layout declares in its XML (`srcs/layouts`), for code
 * that cannot parse layouts: the pure backup preview ([tribixbite.cleverkeys.backup.LayoutThemeBindingDiff])
 * and Layout Manager's binding chip. Config reads the parsed attribute itself
 * (`KeyboardData.declared_theme`); `StyleLayoutsTest` pins this map against the real XML, so
 * the two cannot drift.
 *
 * Only the familiar-style layouts declare one — each binds the matching keyboard look, so
 * switching to it transforms the whole keyboard. Every other predefined layout follows the user.
 */
object PredefinedLayoutThemes {
    val DECLARED: Map<String, String> = mapOf(
        "latn_qwerty_us_ios_style" to KeyboardLooks.IOS,
        "latn_qwerty_us_gboard_style" to KeyboardLooks.GBOARD,
        "latn_qwerty_us_swiftkey_style" to KeyboardLooks.SWIFTKEY,
        "latn_qwerty_us_samsung_style" to KeyboardLooks.SAMSUNG,
    )

    /** The theme the predefined layout [name] declares, or null. */
    fun declaredThemeOf(name: String): String? = DECLARED[name]
}
