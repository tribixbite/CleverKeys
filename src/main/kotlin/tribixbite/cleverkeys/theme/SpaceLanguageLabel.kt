package tribixbite.cleverkeys.theme

import java.util.Locale

/**
 * The language name a keyboard look prints on the space bar ([KeyboardLook.spaceLanguage]):
 * Samsung Keyboard shows "English (US)" (`build/oct28-samsung-ref/1.jpg`) — the language named in
 * itself, then the region code in brackets. Pure; `SpaceLanguageLabelTest`.
 */
object SpaceLanguageLabel {

    /** Label size as a fraction of the main key-label size (1.jpg: cap height 24 px vs 44 px letters). */
    const val SIZE = 0.55f

    /**
     * The label for [languageTag] (the active primary language, e.g. "en" or "pt-BR"): its own
     * name ("Deutsch"), plus the region — the tag's own, else [system]'s when it is the same
     * language — as "(US)". An unknown language yields the tag itself; an empty tag nothing.
     */
    fun text(languageTag: String, system: Locale): String {
        if (languageTag.isBlank()) return ""
        val tag = Locale.forLanguageTag(languageTag)
        val language = tag.language.ifEmpty { return languageTag }
        val region = tag.country.ifEmpty { if (system.language == language) system.country else "" }
        val shown = Locale(language, region)
        val name = shown.getDisplayLanguage(shown)
        // Java names unknown languages with their code: show the tag as given.
        if (name.isEmpty() || name.equals(language, ignoreCase = true)) return languageTag
        val capitalised = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(shown) else it.toString() }
        return if (region.isEmpty()) capitalised else "$capitalised ($region)"
    }
}
