package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The case_cycle rewrite ([CaseCycle]): lower → Title → UPPER → lower. */
class CaseCycleTest {

    private val en = Locale.ENGLISH

    private fun cycle(text: String, locale: Locale = en): List<String> {
        val seen = mutableListOf(text)
        repeat(3) { seen += CaseCycle.next(seen.last(), locale) }
        return seen
    }

    @Test
    fun cyclesThroughTheThreeFormsAndBack() {
        assertEquals(listOf("hello world", "Hello World", "HELLO WORLD", "hello world"), cycle("hello world"))
    }

    @Test
    fun mixedCaseRestartsAtLowerCase() {
        assertEquals("iphone", CaseCycle.next("iPhone", en))
        assertEquals("hello", CaseCycle.next("hELLO", en))
    }

    @Test
    fun aSingleCapitalIsUpperCaseSoTheCycleKeepsMoving() {
        assertEquals(listOf("I", "i", "I", "i"), cycle("I"))
    }

    @Test
    fun innerApostrophesDoNotStartAWord() {
        assertEquals("Don't Stop", CaseCycle.next("don't stop", en))
        assertEquals("Don’t", CaseCycle.next("don’t", en))
    }

    @Test
    fun localeAndLengthChangingMappings() {
        val tr = Locale.forLanguageTag("tr")
        assertEquals("İstanbul", CaseCycle.next("istanbul", tr))
        assertEquals("ISTANBUL", CaseCycle.next("Istanbul", en))
        // ß has no single-character upper case: the result is longer.
        assertEquals("STRASSE", CaseCycle.next("Straße", Locale.GERMAN))
    }

    @Test
    fun textWithoutLetterCaseIsReturnedUnchanged() {
        assertEquals("1234", CaseCycle.next("1234", en))
        assertEquals("漢字", CaseCycle.next("漢字", en))
        assertEquals(CaseCycle.Form.UNCASED, CaseCycle.formOf("— 42 —", en))
    }

    @Test
    fun separatorsAreKept() {
        assertEquals("Line One\nLine-Two", CaseCycle.next("line one\nline-two", en))
    }
}
