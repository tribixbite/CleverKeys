package tribixbite.cleverkeys

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LanguageDisplayNames] replaced two English-only tables (device finding 2026-09-29: the
 * language dropdowns and the keyboard's "Primary:/Secondary:" toggle message showed English
 * names under fa/hu). Names now come from CLDR in the UI locale, plus the endonym.
 *
 * Expected strings are CLDR data as shipped with the test JDK; Android's ICU uses the same
 * CLDR source, so the shapes asserted here (UI-language name, endonym in parentheses,
 * capitalised first letter) hold on device even if a spelling differs between CLDR versions.
 */
class LanguageDisplayNamesTest {

    private val hu = Locale.forLanguageTag("hu")
    private val fa = Locale.forLanguageTag("fa")

    @Test fun englishUiShowsEnglishNameAndEndonym() {
        assertEquals("Spanish (Español)", LanguageDisplayNames.displayName("es", Locale.ENGLISH))
        assertEquals("German (Deutsch)", LanguageDisplayNames.displayName("de", Locale.ENGLISH))
    }

    @Test fun uiLanguageItselfIsNamedOnce() {
        assertEquals("English", LanguageDisplayNames.displayName("en", Locale.ENGLISH))
        assertEquals("Magyar", LanguageDisplayNames.displayName("hu", hu))
    }

    @Test fun nonEnglishUiGetsItsOwnSpellingCapitalised() {
        val french = LanguageDisplayNames.displayName("fr", hu)
        assertEquals("Francia (Français)", french)
        // Persian script has no case; the name is Persian, the endonym French.
        val inFa = LanguageDisplayNames.displayName("fr", fa)
        assertTrue(inFa, inFa.endsWith("(Français)"))
        assertFalse(inFa, inFa.startsWith("French"))
    }

    @Test fun regionalCodesAndSeparatorsAreAccepted() {
        assertEquals(
            LanguageDisplayNames.displayName("en-GB", Locale.ENGLISH),
            LanguageDisplayNames.displayName("en_GB", Locale.ENGLISH)
        )
        assertTrue(LanguageDisplayNames.displayName("en_GB", Locale.ENGLISH).startsWith("English"))
    }

    @Test fun unknownCodesFallBackToUpperCaseCode() {
        assertEquals("XX", LanguageDisplayNames.displayName("xx", Locale.ENGLISH))
        assertEquals("!!", LanguageDisplayNames.displayName("!!", Locale.ENGLISH))
    }

    @Test fun noneSentinelIsLeftToTheCaller() {
        // "none" is a stored pref value; its label is the translated common_none resource,
        // resolved by callers — the helper must not invent an English "None".
        assertEquals("none", LanguageDisplayNames.NONE)
    }
}
