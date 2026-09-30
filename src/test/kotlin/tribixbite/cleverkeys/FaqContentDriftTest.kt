package tribixbite.cleverkeys

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the in-app FAQ (`settings_faq_*_a`, rendered by the Help section) pointing at settings
 * and screens that exist.
 *
 * The 2026-09-29 sweep moved the FAQ into resources verbatim and found it still told users to
 * tune "Length Penalty (Alpha)", "Vocab Frequency Weight" and "Prefix Boost" (sliders of the
 * neural engine removed on 2026-08-18) and to open a "Per-Key Customization" screen whose card
 * is actually titled "Customize Per-Key Actions". Two checks stop that recurring:
 *
 *  - no locale's FAQ may name a removed neural-engine setting (the translations kept the English
 *    names verbatim in most locales, so the check runs on every locale);
 *  - every "Settings → A → B" breadcrumb in the English FAQ must name, segment by segment, a
 *    title that is a real string resource (section/screen titles carry a leading emoji, which is
 *    ignored).
 */
class FaqContentDriftTest {

    /** Names of settings that belonged to the deleted neural swipe engine. */
    private val removedSettingNames = listOf(
        "Length Penalty", "Vocab Frequency", "Prefix Boost", "Längenstrafe", "Präfix-Boost",
        "Pénalité de longueur", "Boost de préfixe", "Penalità di lunghezza", "Boost prefisso",
        "Lengtestraf", "Prefix-boost",
    )

    /** Capitalized breadcrumb: "Settings → Multi-Language → Language Packs". */
    private val breadcrumb = Regex("Settings((?: → [A-Z][\\w&-]*(?: [A-Z&][\\w&-]*)*)+)")

    private fun faqAnswers(texts: Map<String, String>) =
        texts.filterKeys { it.startsWith("settings_faq_") && it.endsWith("_a") }

    /** "📱 Activities" -> "Activities": drops leading emoji/symbols and spaces. */
    private fun bareTitle(text: String): String =
        TranslationResources.unescape(text).replace("&amp;", "&").dropWhile { !it.isLetter() }.trim()

    @Test fun noLocaleFaqNamesARemovedSetting() {
        val offenders = mutableListOf<String>()
        for (dir in TranslationResources.localeDirs + TranslationResources.defaultDir) {
            for ((key, text) in faqAnswers(TranslationResources.strings(dir))) {
                val hit = removedSettingNames.firstOrNull { text.contains(it, ignoreCase = true) }
                if (hit != null) offenders += "${dir.name}/$key names removed setting \"$hit\""
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun englishFaqBreadcrumbsNameRealTitles() {
        val english = TranslationResources.strings(TranslationResources.defaultDir)
        val titles = english.values.map(::bareTitle).toSet()
        val answers = faqAnswers(english)
        assertTrue("no FAQ answers found — key pattern is stale", answers.size >= 8)
        var checked = 0
        val offenders = mutableListOf<String>()
        for ((key, raw) in answers) {
            val text = TranslationResources.unescape(raw).replace("&amp;", "&")
            for (match in breadcrumb.findAll(text)) {
                for (segment in match.groupValues[1].split(" → ").map { it.trim() }.filter { it.isNotEmpty() }) {
                    checked++
                    if (segment !in titles) offenders += "$key: \"$segment\" is not a screen/section title"
                }
            }
        }
        assertTrue("expected breadcrumbs in the FAQ (found $checked segments)", checked >= 5)
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }
}
