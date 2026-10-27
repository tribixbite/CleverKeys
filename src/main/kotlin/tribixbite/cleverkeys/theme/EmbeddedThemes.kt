package tribixbite.cleverkeys.theme

/**
 * Ids of layouts' EMBEDDED themes (`<theme>` in a layout XML, [ThemeSpecXml]) — the fifth theme
 * kind beside built-in XML, `decorative_`, `custom_` and `look_` themes.
 *
 * An embedded theme is partial and inherits from the user's GLOBAL theme, so its effective id
 * names both: `embedded_<n>.<globalThemeId>`, where `<n>` interns the spec (equal specs share a
 * number for the process lifetime). Changing the layout's `<theme>`, or the global theme under
 * it, therefore changes `Config.themeName`, which is what re-inflates the keyboard view
 * (`ConfigurationManager`, theme-system skill rule 6). The id is never persisted: Config
 * re-derives it on every refresh from the parsed layouts.
 *
 * Process-wide and thread-safe; pure JVM (`EmbeddedThemesTest`).
 */
object EmbeddedThemes {

    const val ID_PREFIX = "embedded_"

    private val specs = ArrayList<ThemeSpec>()
    private val numbers = HashMap<ThemeSpec, Int>()

    /** The effective theme id of [spec] over the global theme [baseThemeId]. */
    @Synchronized
    fun idFor(spec: ThemeSpec, baseThemeId: String): String {
        val n = numbers.getOrPut(spec) { specs.add(spec); specs.size - 1 }
        return "$ID_PREFIX$n.$baseThemeId"
    }

    /** An embedded id split into its spec and base theme id; null when not one of ours. */
    data class Parsed(val spec: ThemeSpec, val baseThemeId: String)

    @Synchronized
    fun parse(id: String): Parsed? {
        if (!id.startsWith(ID_PREFIX)) return null
        val rest = id.substring(ID_PREFIX.length)
        val dot = rest.indexOf('.')
        if (dot <= 0) return null
        val n = rest.substring(0, dot).toIntOrNull() ?: return null
        val spec = specs.getOrNull(n) ?: return null
        return Parsed(spec, rest.substring(dot + 1))
    }

    fun isEmbeddedId(id: String): Boolean = id.startsWith(ID_PREFIX)
}
