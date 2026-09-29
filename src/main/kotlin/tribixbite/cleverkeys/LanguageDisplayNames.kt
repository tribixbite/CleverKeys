package tribixbite.cleverkeys

import java.util.Locale

/**
 * Human-readable names for dictionary/language codes, in the user's UI language.
 *
 * Replaces two hand-written English tables (Settings' language dropdowns and the keyboard's
 * "Primary: …" toggle message) that showed English names — and an English "None" — under
 * every app locale (device finding 2026-09-29, fa/hu). Names come from the platform's CLDR
 * data via [Locale.getDisplayName], so every UI locale gets its own spelling without a
 * per-language resource table, and new language-pack codes are named automatically.
 *
 * Format: `"<name in UI locale> (<endonym>)"` — e.g. under English `"Spanish (Español)"`,
 * under Hungarian `"Spanyol (Español)"` — so a user can recognise their own language even
 * in a UI language they do not read. When the two are equal (the UI language itself) only
 * one is shown. Unknown codes fall back to the upper-cased code, as the old tables did.
 *
 * Pure JVM (no Android types); the "none" sentinel is the caller's job because its label is
 * a string resource.
 */
object LanguageDisplayNames {

    /** Sentinel stored in `pref_secondary_language` for "no secondary language". */
    const val NONE = "none"

    /**
     * @param code a dictionary code such as `"es"`, `"pt"`, `"en_GB"` / `"en-GB"`.
     * @param uiLocale the locale the UI is rendered in (the app's configuration locale).
     */
    fun displayName(code: String, uiLocale: Locale): String {
        val locale = Locale.forLanguageTag(code.replace('_', '-'))
        val language = locale.language
        // forLanguageTag yields "" for malformed tags; getDisplayName echoes unknown codes back
        // unchanged (e.g. "xx"), so either means CLDR has no name for it.
        if (language.isEmpty()) return code.uppercase(Locale.ROOT)
        val inUi = locale.getDisplayName(uiLocale)
        if (inUi.isEmpty() || inUi.equals(code, ignoreCase = true) ||
            inUi.equals(language, ignoreCase = true)
        ) {
            return code.uppercase(Locale.ROOT)
        }
        val localized = inUi.capitalizeFirst(uiLocale)
        val endonym = locale.getDisplayName(locale).capitalizeFirst(locale)
        return if (endonym.isEmpty() || endonym.equals(localized, ignoreCase = true)) {
            localized
        } else {
            "$localized ($endonym)"
        }
    }

    /** CLDR language names are lower-case in many languages; a menu label starts upper-case. */
    private fun String.capitalizeFirst(locale: Locale): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
