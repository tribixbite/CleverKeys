package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * WP9 geo path — [ContractionOverlay] decision matrix (2026-07-23 multilingual audit).
 *
 * Fixtures mirror the measured production data: dictionary ordinals from the shipped
 * CKDT bins (en: theyd=62818, dont=1817, well=95; fr: dont=104, cest=5192; de: im=16)
 * and mappings from contractions_non_paired.json / contraction_pairings.json. The guard
 * threshold sits at [ContractionOverlay.REAL_WORD_ORDINAL_MAX] = 1200, inside the
 * measured gap (real-word collisions ≤ 285, junk aliases ≥ 1506).
 */
class ContractionOverlayTest {

    // ── English fixtures ────────────────────────────────────────────────────────────

    private val enPaired = mapOf(
        "well" to listOf("we'll"),
        "its" to listOf("it's"),
        "girls" to listOf("girl's"),
    )

    // The binary contraction store misclassifies paired entries into the non-paired map
    // (loadPairedContractions adds but never removes) — the fixture reproduces that so the
    // paired-first rule is actually load-bearing in the test.
    private val enNonPaired = mapOf(
        "theyd" to "they'd",
        "dont" to "don't",
        "im" to "I'm",
        "well" to "we'll",
        "its" to "it's",
    )

    private val enOrdinals = mapOf(
        "the" to 0, "were" to 51, "its" to 72, "well" to 95,
        "dont" to 1817, "im" to 2063, "theyd" to 62818,
    )

    private fun applyEn(words: List<String>, scores: List<Int>) = ContractionOverlay.apply(
        words, scores,
        pairedVariants = { enPaired[it] },
        nonPairedMapping = { enNonPaired[it] },
        wordOrdinal = { enOrdinals[it] },
    )

    @Test
    fun `en junk alias is replaced with display form`() {
        val (words, scores) = applyEn(listOf("theyd", "them"), listOf(900, 800))
        assertThat(words).containsExactly("they'd", "them").inOrder()
        assertThat(scores).containsExactly(900, 800).inOrder()
    }

    @Test
    fun `en dont is deep-ranked junk and is replaced`() {
        val (words, _) = applyEn(listOf("dont"), listOf(900))
        assertThat(words).containsExactly("don't")
    }

    @Test
    fun `en paired base keeps the word and appends the variant at the end`() {
        // Variants are APPENDED (never spliced next to the base) so injections cannot
        // displace distinct engine candidates ("wall") from the top ranks.
        val (words, scores) = applyEn(listOf("well", "wall"), listOf(900, 800))
        assertThat(words).containsExactly("well", "wall", "we'll").inOrder()
        assertThat(scores).containsExactly(900, 800, 899).inOrder()
    }

    @Test
    fun `en paired-first wins over the polluted non-paired map`() {
        // "its" is in BOTH maps (binary-store pollution); paired must win → keep + inject,
        // never replace.
        val (words, _) = applyEn(listOf("its"), listOf(900))
        assertThat(words).containsExactly("its", "it's").inOrder()
    }

    @Test
    fun `alias absent from the dictionary is treated as junk and replaced`() {
        val (words, _) = ContractionOverlay.apply(
            listOf("im"), listOf(500),
            pairedVariants = { null },
            nonPairedMapping = { enNonPaired[it] },
            wordOrdinal = { null }, // not in the active dictionary at all
        )
        assertThat(words).containsExactly("I'm")
    }

    @Test
    fun `dedupe keeps the first occurrence after mapping collisions`() {
        // "theyd" replaces to "they'd"; a later literal "they'd" (e.g. injected by a custom
        // word) must not duplicate.
        val (words, scores) = applyEn(listOf("theyd", "they'd"), listOf(900, 700))
        assertThat(words).containsExactly("they'd")
        assertThat(scores).containsExactly(900)
    }

    // ── Cross-language guard (the audit's severe cases) ─────────────────────────────

    @Test
    fun `german im is a top-16 real word — kept, variant appended, never replaced`() {
        // Unguarded replacement would rewrite the 16th most common German word to "I'm".
        val (words, scores) = ContractionOverlay.apply(
            listOf("im", "um"), listOf(950, 800),
            pairedVariants = { null },
            nonPairedMapping = { mapOf("im" to "I'm")[it] },
            wordOrdinal = { mapOf("im" to 16, "um" to 40)[it] },
        )
        assertThat(words).containsExactly("im", "um", "I'm").inOrder()
        assertThat(scores).containsExactly(950, 800, 949).inOrder()
    }

    @Test
    fun `french dont is a top-104 real word — kept with variant`() {
        val (words, _) = ContractionOverlay.apply(
            listOf("dont"), listOf(900),
            pairedVariants = { null },
            nonPairedMapping = { mapOf("dont" to "don't")[it] },
            wordOrdinal = { mapOf("dont" to 104)[it] },
        )
        assertThat(words).containsExactly("dont", "don't").inOrder()
    }

    @Test
    fun `french cest is deep-ranked junk — replaced with the apostrophe form`() {
        val (words, _) = ContractionOverlay.apply(
            listOf("cest"), listOf(900),
            pairedVariants = { null },
            nonPairedMapping = { mapOf("cest" to "c'est")[it] },
            wordOrdinal = { mapOf("cest" to 5192)[it] },
        )
        assertThat(words).containsExactly("c'est")
    }

    // ── Boundary + passthrough ──────────────────────────────────────────────────────

    @Test
    fun `threshold boundary — ordinal exactly at max is junk, one below is real`() {
        fun run(ordinal: Int): List<String> = ContractionOverlay.apply(
            listOf("x"), listOf(100),
            pairedVariants = { null },
            nonPairedMapping = { "x'" },
            wordOrdinal = { ordinal },
        ).first
        assertThat(run(ContractionOverlay.REAL_WORD_ORDINAL_MAX)).containsExactly("x'")
        assertThat(run(ContractionOverlay.REAL_WORD_ORDINAL_MAX - 1))
            .containsExactly("x", "x'").inOrder()
    }

    @Test
    fun `words with no mappings pass through untouched`() {
        val input = listOf("hello", "world")
        val (words, scores) = ContractionOverlay.apply(
            input, listOf(900, 800),
            pairedVariants = { null },
            nonPairedMapping = { null },
            wordOrdinal = { null },
        )
        assertThat(words).isEqualTo(input)
        assertThat(scores).containsExactly(900, 800).inOrder()
    }

    @Test
    fun `case-insensitive matching preserves the decoded casing of kept words`() {
        val (words, _) = applyEn(listOf("Well"), listOf(900))
        assertThat(words).containsExactly("Well", "we'll").inOrder()
    }
}
