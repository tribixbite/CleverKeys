package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The look bar's quoted typed word (iOS look) takes its quotation marks from the translated
 * `bar_quote_open` / `bar_quote_close` resources (2026-10-30). Before, SuggestionBar asked
 * ICU's LocaleData, which exists only from API 28, so API 24–27 always showed English marks.
 *
 * Pins: every locale carries both strings with that language's primary marks (CLDR, as ICU4J
 * 73.2 LocaleData reports them; French adds the no-break space inside its guillemets), and the
 * bar reads them — no ICU and no API branch left.
 */
class BarQuoteMarksResourceTest {

    /** Locale → (open, close), as stored in strings.xml (` ` escapes intact). */
    private val expected = mapOf(
        "en" to ("“" to "”"), "cs" to ("„" to "“"), "de" to ("„" to "“"), "es" to ("«" to "»"),
        "fa" to ("«" to "»"), "fil" to ("“" to "”"), "fr" to ("«\\u00A0" to "\\u00A0»"),
        "hu" to ("„" to "”"), "in" to ("“" to "”"), "it" to ("«" to "»"), "ja" to ("「" to "」"),
        "ko" to ("“" to "”"), "lv" to ("“" to "”"), "nl" to ("‘" to "’"), "pl" to ("„" to "”"),
        "pt" to ("“" to "”"), "ro" to ("„" to "”"), "ru" to ("«" to "»"), "tr" to ("“" to "”"),
        "uk" to ("«" to "»"), "vi" to ("“" to "”"), "zh-rCN" to ("“" to "”"),
    )

    private fun marksOf(dir: File): Pair<String?, String?> {
        val s = TranslationResources.strings(dir)
        return s["bar_quote_open"] to s["bar_quote_close"]
    }

    @Test
    fun everyLocaleHasItsLanguagesQuotationMarks() {
        val actual = mapOf("en" to marksOf(TranslationResources.defaultDir)) +
            TranslationResources.localeDirs.associate { TranslationResources.localeOf(it) to marksOf(it) }
        assertEquals("one entry per shipped language", expected.keys, actual.keys)
        for ((locale, marks) in expected) {
            assertEquals(locale, marks.first to marks.second, actual[locale])
        }
    }

    @Test
    fun theBarReadsTheResources_onEveryApiLevel() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        assertTrue(src.contains("R.string.bar_quote_open"))
        assertTrue(src.contains("R.string.bar_quote_close"))
        assertFalse("no ICU lookup", src.contains("android.icu.util.LocaleData"))
        assertFalse("no API-28 branch for the marks", src.contains("VERSION_CODES.P)"))
    }
}
