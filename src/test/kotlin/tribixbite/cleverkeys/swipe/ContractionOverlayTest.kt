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
    fun `en paired variant with no known frequency still goes to the tail`() {
        // No pairing frequency → no evidence the variant deserves a top slot, so the
        // b2d7b908 tail placement is kept (this is also every fr/it pairs-file entry).
        // The tail score is clamped to the last emitted score so the slate stays monotone.
        val (words, scores) = applyEn(listOf("well", "wall"), listOf(900, 800))
        assertThat(words).containsExactly("well", "wall", "we'll").inOrder()
        assertThat(scores).containsExactly(900, 800, 800).inOrder()
    }

    // ── Paired placement (2026-09-26, learning-system audit RC1 + RC3) ──────────────
    //
    // Frequencies below are the SHIPPED values: pairing frequency from
    // contraction_pairings.json (base-scoped), base frequency from en_enhanced.json.

    private val pairFreq = mapOf(
        "well" to mapOf("we'll" to 200),
        "shed" to mapOf("she'd" to 200),
        "id" to mapOf("i'd" to 200),
        "hell" to mapOf("he'll" to 200),
        "shes" to mapOf("she's" to 241),
        "teams" to mapOf("team's" to 206),
        "would" to mapOf("wouldn't" to 179, "would've" to 170),
        "world" to mapOf("world's" to 224),
        "girls" to mapOf("girl's" to 150, "girls'" to 140),
    )
    private val pairedAll = pairFreq.mapValues { it.value.keys.toList() }
    private val lexFreq = mapOf(
        "well" to 223, "wall" to 213, "shed" to 189, "id" to 196, "is" to 250,
        "hell" to 206, "shes" to 177, "teams" to 203, "would" to 228, "world" to 221,
        "wood" to 210, "girls" to 200,
    )

    private fun applyRanked(
        words: List<String>,
        scores: List<Int>,
        baseFrequency: (String) -> Int? = { lexFreq[it] },
    ) = ContractionOverlay.apply(
        words, scores,
        pairedVariants = { pairedAll[it] },
        nonPairedMapping = { null },
        wordOrdinal = { null },
        pairedVariantFrequency = { base, variant -> pairFreq[base]?.get(variant) },
        baseFrequency = baseFrequency,
    )

    @Test
    fun `variant less frequent than its base is spliced directly after the base`() {
        // well 223 > we'll 200: the word keeps rank 0 (auto-insert target), the variant is
        // the very next slot instead of being pushed off-screen behind "wall".
        val (words, scores) = applyRanked(listOf("well", "wall"), listOf(900, 800))
        assertThat(words).containsExactly("well", "we'll", "wall").inOrder()
        assertThat(scores).containsExactly(900, 900, 800).inOrder()
    }

    @Test
    fun `variant more frequent than its base goes AHEAD of it and becomes rank 0`() {
        // she'd 200 > shed 189 — the reported bug: swiping she'd auto-inserted "shed".
        val (words, scores) = applyRanked(listOf("shed", "shes"), listOf(900, 700))
        assertThat(words.take(2)).containsExactly("she'd", "shed").inOrder()
        assertThat(scores.take(2)).containsExactly(900, 900).inOrder()
    }

    @Test
    fun `id yields i'd first`() {
        val (words, _) = applyRanked(listOf("id", "is"), listOf(900, 800))
        assertThat(words).containsExactly("i'd", "id", "is").inOrder()
    }

    @Test
    fun `hell stays first — base more frequent than he'll`() {
        val (words, _) = applyRanked(listOf("hell", "well"), listOf(900, 800))
        assertThat(words).containsExactly("hell", "he'll", "well", "we'll").inOrder()
    }

    @Test
    fun `a pronoun 's clitic is promoted like any other contraction`() {
        // she's 241 vs shes 177 — "'s" after a pronoun is "is/has", not a possessive.
        val (words, _) = applyRanked(listOf("shes"), listOf(900))
        assertThat(words).containsExactly("she's", "shes").inOrder()
    }

    @Test
    fun `a possessive is never promoted over its base, only spliced after it`() {
        // team's 206 > teams 203 in the shipped files, but wordfreq ranks "teams" far
        // above "team's" (4.97 vs 4.09 zipf) — 24 of the 69 possessive/clitic promotions the
        // raw frequencies would make are wrong this way, so possessives never go ahead.
        val (words, _) = applyRanked(listOf("teams", "trams"), listOf(900, 800))
        assertThat(words).containsExactly("teams", "team's", "trams").inOrder()
    }

    @Test
    fun `would-world non-regression — non-projection variants stay at the tail`() {
        // b2d7b908: splicing ALL of would's variants pushed "world" from #2 to #4.
        // wouldn't/would've are not what the trace spelled (projection != "would"), so
        // they remain tail completions and "world" keeps rank 1.
        val (words, _) = applyRanked(
            listOf("would", "world", "wood"), listOf(900, 850, 800)
        )
        assertThat(words.take(3)).containsExactly("would", "world", "wood").inOrder()
        assertThat(words.drop(3)).containsExactly("wouldn't", "would've", "world's").inOrder()
    }

    @Test
    fun `at most one variant is spliced per base — the rest go to the tail`() {
        val (words, _) = applyRanked(listOf("girls", "gills"), listOf(900, 800))
        assertThat(words).containsExactly("girls", "girl's", "gills", "girls'").inOrder()
    }

    @Test
    fun `unknown base frequency never promotes — splice after only`() {
        // The geometric engine's CKDT ranks are not on the pairing file's byte scale, so it
        // passes no base frequency; the variant is still spliced but never goes ahead.
        val (words, _) = applyRanked(listOf("shed", "shes"), listOf(900, 700)) { null }
        assertThat(words.take(2)).containsExactly("shed", "she'd").inOrder()
    }

    @Test
    fun `scores stay non-increasing across splice, promotion and tail`() {
        val (_, scores) = applyRanked(
            listOf("shed", "would", "well", "girls", "wood"),
            listOf(900, 880, 870, 860, 500),
        )
        assertThat(scores).isInOrder(Comparator.reverseOrder<Int>())
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
        assertThat(scores).containsExactly(950, 800, 800).inOrder()
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
