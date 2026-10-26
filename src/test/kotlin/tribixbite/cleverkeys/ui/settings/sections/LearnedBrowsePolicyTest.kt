package tribixbite.cleverkeys.ui.settings.sections

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.ui.settings.sections.LearnedBrowsePolicy.CountLine
import tribixbite.cleverkeys.ui.settings.sections.LearnedBrowsePolicy.PAGE_SIZE
import tribixbite.cleverkeys.ui.settings.sections.LearnedBrowsePolicy.PREFETCH_DISTANCE

/**
 * Maintainer request (2026-10-26): "Browsing learned words and phrases needs pagination and
 * search." The browsers showed the top 200 rows with no search; [LearnedBrowsePolicy] is the
 * filter and paging policy behind the new search field and the page-at-a-time list.
 */
class LearnedBrowsePolicyTest {

    private val words = LearnedBrowsePolicy.index(
        listOf("Café", "cafeteria", "naïve", "hello", "میکنم", "کتاب", "Straße"),
    ) { listOf(it) }

    @Test
    fun `an empty or blank query keeps every row in ranked order`() {
        val all = listOf("Café", "cafeteria", "naïve", "hello", "میکنم", "کتاب", "Straße")
        assertThat(LearnedBrowsePolicy.filter(words, "")).containsExactlyElementsIn(all).inOrder()
        assertThat(LearnedBrowsePolicy.filter(words, "   ")).containsExactlyElementsIn(all).inOrder()
        assertThat(LearnedBrowsePolicy.filter(words, "‌")).containsExactlyElementsIn(all).inOrder()
    }

    @Test
    fun `matching ignores case and diacritics in both directions`() {
        assertThat(LearnedBrowsePolicy.filter(words, "cafe")).containsExactly("Café", "cafeteria").inOrder()
        assertThat(LearnedBrowsePolicy.filter(words, "CAFÉ")).containsExactly("Café", "cafeteria").inOrder()
        assertThat(LearnedBrowsePolicy.filter(words, "naive")).containsExactly("naïve")
    }

    @Test
    fun `matching is a substring match, not only a prefix`() {
        assertThat(LearnedBrowsePolicy.filter(words, "ell")).containsExactly("hello")
        assertThat(LearnedBrowsePolicy.filter(words, "teria")).containsExactly("cafeteria")
    }

    @Test
    fun `Persian half-space and Arabic yeh-kaf variants match the stored word`() {
        // Stored: میکنم (no ZWNJ); typed with a half-space and an Arabic yeh.
        assertThat(LearnedBrowsePolicy.filter(words, "مي‌کنم")).containsExactly("میکنم")
        // Stored with keheh; typed with Arabic kaf.
        assertThat(LearnedBrowsePolicy.filter(words, "كتاب")).containsExactly("کتاب")
    }

    @Test
    fun `no match yields an empty list`() {
        assertThat(LearnedBrowsePolicy.filter(words, "zzz")).isEmpty()
    }

    @Test
    fun `a phrase query can span both words of a pair`() {
        data class Pair2(val a: String, val b: String)
        val phrases = LearnedBrowsePolicy.index(
            listOf(Pair2("one", "of"), Pair2("of", "the"), Pair2("the", "end")),
        ) { listOf(it.a, it.b) }
        assertThat(LearnedBrowsePolicy.filter(phrases, "of the")).containsExactly(Pair2("of", "the"))
        assertThat(LearnedBrowsePolicy.filter(phrases, "the")).containsExactly(Pair2("of", "the"), Pair2("the", "end")).inOrder()
    }

    @Test
    fun `the list shows one page at first and never more than the matches`() {
        assertThat(LearnedBrowsePolicy.visibleCount(matchCount = 20_000, pagesLoaded = 1)).isEqualTo(PAGE_SIZE)
        assertThat(LearnedBrowsePolicy.visibleCount(matchCount = 20_000, pagesLoaded = 3)).isEqualTo(3 * PAGE_SIZE)
        assertThat(LearnedBrowsePolicy.visibleCount(matchCount = 42, pagesLoaded = 3)).isEqualTo(42)
        assertThat(LearnedBrowsePolicy.visibleCount(matchCount = 0, pagesLoaded = 1)).isEqualTo(0)
        // A nonsensical page count still shows the first page; a huge one does not overflow.
        assertThat(LearnedBrowsePolicy.visibleCount(matchCount = 500, pagesLoaded = 0)).isEqualTo(PAGE_SIZE)
        assertThat(LearnedBrowsePolicy.visibleCount(matchCount = 500, pagesLoaded = Int.MAX_VALUE)).isEqualTo(500)
    }

    @Test
    fun `scrolling near the end of the shown rows loads the next page`() {
        val nearEnd = PAGE_SIZE - PREFETCH_DISTANCE
        assertThat(LearnedBrowsePolicy.pagesAfterScroll(nearEnd, matchCount = 1_000, pagesLoaded = 1)).isEqualTo(2)
        assertThat(LearnedBrowsePolicy.pagesAfterScroll(nearEnd - 1, matchCount = 1_000, pagesLoaded = 1)).isEqualTo(1)
        assertThat(LearnedBrowsePolicy.pagesAfterScroll(2 * PAGE_SIZE - 1, matchCount = 1_000, pagesLoaded = 2)).isEqualTo(3)
    }

    @Test
    fun `no page is added when everything is shown or nothing is laid out`() {
        assertThat(LearnedBrowsePolicy.pagesAfterScroll(41, matchCount = 42, pagesLoaded = 1)).isEqualTo(1)
        assertThat(LearnedBrowsePolicy.pagesAfterScroll(-1, matchCount = 1_000, pagesLoaded = 1)).isEqualTo(1)
    }

    @Test
    fun `the count line is the total alone without a query and N of M with one`() {
        assertThat(LearnedBrowsePolicy.countLine("", matches = 1234, total = 1234)).isEqualTo(CountLine.Total(1234))
        assertThat(LearnedBrowsePolicy.countLine("  ", matches = 1234, total = 1234)).isEqualTo(CountLine.Total(1234))
        assertThat(LearnedBrowsePolicy.countLine("caf", matches = 2, total = 1234)).isEqualTo(CountLine.Filtered(2, 1234))
        assertThat(LearnedBrowsePolicy.countLine("zzz", matches = 0, total = 1234)).isEqualTo(CountLine.Filtered(0, 1234))
    }
}
