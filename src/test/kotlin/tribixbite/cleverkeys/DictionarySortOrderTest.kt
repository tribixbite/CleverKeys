package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import tribixbite.cleverkeys.DictionaryManagerActivity.SortType

/**
 * v1.2.6 and v1.2.8: "Sort by Frequency/Match/A-Z/Z-A" in the Dictionary Manager.
 *
 * Release-record rows anchored at `activities/DictionaryManagerActivity.kt#DictionaryManagerActivity`,
 * both PRESENT-UNTESTED. The claim has two parts and both can break independently:
 *
 *  1. **The four orderings are what their names say.** The comparators live in
 *     [sortWordsForDisplay], extracted verbatim from `WordListFragment.filter` so they can run
 *     without a Fragment. Every mode is asserted on a fixture whose correct order differs under
 *     all four, so no two modes can be confused with each other.
 *  2. **The spinner picks the mode the user pointed at.** `setupFilter` maps a spinner POSITION
 *     to `SortType.values()[position]`, so the label list and the enum's declaration order are
 *     one contract held in two files. Reordering either alone silently mislabels every option —
 *     tapping "A-Z" would sort by match quality — with no compile error and no behavioural
 *     symptom any of the tests above could see.
 */
class DictionarySortOrderTest {

    /**
     * Deliberately adversarial: frequency order, alphabetical order and match order are all
     * different, and two entries share a frequency so ties are observable.
     */
    private val words = listOf(
        DictionaryWord("zebra", frequency = 900, source = WordSource.MAIN),
        DictionaryWord("Apple", frequency = 100, source = WordSource.MAIN),
        DictionaryWord("apricot", frequency = 500, source = WordSource.MAIN),
        DictionaryWord("app", frequency = 300, source = WordSource.CUSTOM),
        DictionaryWord("banana", frequency = 500, source = WordSource.USER),
        DictionaryWord("appliance", frequency = 50, source = WordSource.MAIN),
    )

    private fun sorted(sortType: SortType, query: String = ""): List<String> =
        sortWordsForDisplay(words, sortType, query).map { it.word }

    // ------------------------------------------------------------- the four orderings

    @Test
    fun freqOrdersByFrequencyHighestFirst() {
        assertThat(sorted(SortType.FREQ))
            .containsExactly("zebra", "apricot", "banana", "app", "Apple", "appliance").inOrder()
        assertWithMessage(
            "apricot and banana are both 500 — a stable sort keeps them in input order, so " +
                "the list does not reshuffle itself between identical renders"
        ).that(sorted(SortType.FREQ).indexOf("apricot"))
            .isLessThan(sorted(SortType.FREQ).indexOf("banana"))
    }

    @Test
    fun aToZIsAlphabeticalIgnoringCase() {
        assertWithMessage(
            "case-folded, so `Apple` sorts among the a-words rather than ahead of every " +
                "lowercase entry as raw String order would put it"
        ).that(sorted(SortType.A_Z))
            .containsExactly("app", "Apple", "appliance", "apricot", "banana", "zebra").inOrder()
    }

    @Test
    fun zToAIsTheReverseAlphabeticalOrder() {
        assertThat(sorted(SortType.Z_A))
            .containsExactly("zebra", "banana", "apricot", "appliance", "Apple", "app").inOrder()
        assertWithMessage("Z-A must be exactly A-Z reversed for a tie-free fixture")
            .that(sorted(SortType.Z_A)).isEqualTo(sorted(SortType.A_Z).reversed())
    }

    @Test
    fun matchPutsTheExactHitFirstThenPrefixHitsThenTheRestEachByFrequency() {
        // Query "app" gives three buckets, each internally ordered by frequency desc:
        //   0 exact  : app
        //   1 prefix : Apple (100), appliance (50)   — case-insensitive, so `Apple` qualifies
        //   2 rest   : zebra (900), apricot (500), banana (500)
        // `apricot` is deliberately in bucket 2: it shares only "ap", not the "app" prefix.
        assertThat(sorted(SortType.MATCH, query = "app"))
            .containsExactly("app", "Apple", "appliance", "zebra", "apricot", "banana").inOrder()
    }

    @Test
    fun matchIsCaseInsensitiveOnBothSides() {
        assertWithMessage("typing `APPLE` must find the stored `Apple` as an EXACT match")
            .that(sorted(SortType.MATCH, query = "APPLE").first()).isEqualTo("Apple")
        assertThat(sorted(SortType.MATCH, query = "apple").first()).isEqualTo("Apple")
    }

    @Test
    fun matchFallsBackToFrequencyWhenTheSearchBoxIsEmpty() {
        assertWithMessage(
            "with no query there is nothing to be relevant to; ranking by frequency is the " +
                "documented fallback and keeps the list stable while the user clears the box"
        ).that(sorted(SortType.MATCH)).isEqualTo(sorted(SortType.FREQ))
    }

    @Test
    fun matchRanksNonMatchesByFrequencyAmongThemselves() {
        val ranked = sorted(SortType.MATCH, query = "app")
        // Bucket 1 (prefix hits) is itself frequency-ordered: Apple 100 before appliance 50.
        assertThat(ranked.subList(1, 3)).containsExactly("Apple", "appliance").inOrder()
        // Bucket 2 (non-matches) likewise: zebra 900, then the two 500s in input order.
        assertThat(ranked.subList(3, 6)).containsExactly("zebra", "apricot", "banana").inOrder()
    }

    @Test
    fun everySortModeIsATotalOrderOverTheSameWords() {
        for (mode in SortType.values()) {
            assertWithMessage("$mode must reorder, never drop or duplicate, the input")
                .that(sortWordsForDisplay(words, mode, "app").map { it.word })
                .containsExactlyElementsIn(words.map { it.word })
        }
    }

    @Test
    fun anEmptyListSortsToAnEmptyListInEveryMode() {
        for (mode in SortType.values()) {
            assertThat(sortWordsForDisplay(emptyList(), mode, "app")).isEmpty()
        }
    }

    // ------------------------------------------- the spinner-position ↔ enum contract

    @Test
    fun theEnumDeclaresExactlyTheFourAnnouncedModesInSpinnerOrder() {
        assertThat(SortType.values().map { it.name })
            .containsExactly("FREQ", "MATCH", "A_Z", "Z_A").inOrder()
        assertWithMessage("saved instance state stores the ORDINAL, so FREQ must stay the default 0")
            .that(SortType.FREQ.ordinal).isEqualTo(0)
    }

    @Test
    fun theSpinnerLabelsLineUpWithTheEnumDeclarationOrder() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt")
        assertWithMessage("expected ${source.path} (run from project root)")
            .that(source.isFile).isTrue()
        val text = source.readText()

        assertWithMessage(
            "the sort spinner's labels are the user-visible half of this contract; the code " +
                "maps position → SortType.values()[position], so the list must be in the enum's " +
                "declaration order"
        ).that(text).contains("""listOf("Freq", "Match", "A-Z", "Z-A")""")
        assertWithMessage("position is resolved positionally against the enum")
            .that(text.replace(Regex("\\s+"), " ")).contains("SortType.values()[position]")

        // And the enum block itself, so a reorder there is caught with the same failure.
        val enumBlock = text.substringAfter("enum class SortType {").substringBefore("}")
        val declared = Regex("""^\s*(\w+),?""", RegexOption.MULTILINE)
            .findAll(enumBlock).map { it.groupValues[1] }.toList()
        assertThat(declared).containsExactly("FREQ", "MATCH", "A_Z", "Z_A").inOrder()
    }
}
