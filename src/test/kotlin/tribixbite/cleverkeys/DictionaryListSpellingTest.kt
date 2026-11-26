package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Dictionary Manager's German list with Swiss spelling on (todo 2026-11-25 D2 follow-up): the
 * keyboard respells every German lexicon at load (`SwissSpelling`), but the manager still listed
 * the shipped `straße`, so a Swiss user saw words the keyboard never offers. The list is respelled
 * the same way now, twins merged, and a word disabled in either spelling shows as disabled.
 */
class DictionaryListSpellingTest {

    private fun main(word: String, frequency: Int, enabled: Boolean = true) =
        DictionaryWord(word, frequency, WordSource.MAIN, enabled)

    @Test
    fun sharpSWordsAreListedWithSs() {
        val out = DictionaryListSpelling.respell(listOf(main("straße", 9000), main("haus", 8000)), emptySet())
        assertThat(out.map { it.word }).containsExactly("strasse", "haus").inOrder()
    }

    @Test
    fun twinsBecomeOneRowWithTheHigherFrequency() {
        val out = DictionaryListSpelling.respell(
            listOf(main("Maße", 5000), main("Masse", 4000), main("gross", 100), main("groß", 7000)),
            emptySet(),
        )
        assertThat(out.map { it.word to it.frequency })
            .containsExactly("Masse" to 5000, "gross" to 7000).inOrder()
    }

    @Test
    fun aWordDisabledInGermanySpellingShowsDisabled() {
        // The row's own flag was computed against the shipped spelling, which is what the user
        // disabled before turning the switch on; the keyboard then disables `strasse` too.
        val out = DictionaryListSpelling.respell(listOf(main("straße", 9000, enabled = false)), setOf("straße"))
        assertThat(out.single().enabled).isFalse()
    }

    @Test
    fun aDisabledTwinDisablesTheMergedRow() {
        // The keyboard's foldDisabled turns a disabled `Maße` into a disabled `Masse` as well.
        val out = DictionaryListSpelling.respell(
            listOf(main("Maße", 5000, enabled = false), main("Masse", 4000)),
            setOf("Maße"),
        )
        assertThat(out.single().enabled).isFalse()
    }

    @Test
    fun enablingAnSsRowAlsoEnablesItsSharpSSpellings() {
        val toEnable = DictionaryListSpelling.spellingsToEnable("Masse", setOf("Maße", "Masse", "haus"))
        assertThat(toEnable).containsExactly("Maße", "Masse")
    }
}
