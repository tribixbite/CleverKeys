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
    // contraction_pairings.json (base-scoped; non-possessive values MEASURED 2026-09-26 —
    // wordfreq zipf through the isotonic fit of en_enhanced.json), base frequency from
    // en_enhanced.json.

    private val pairFreq = mapOf(
        "well" to mapOf("we'll" to 202),
        "shed" to mapOf("she'd" to 188),
        "id" to mapOf("i'd" to 211),
        "ill" to mapOf("i'll" to 212),
        "its" to mapOf("it's" to 229),
        "hell" to mapOf("he'll" to 195),
        "shes" to mapOf("she's" to 209),
        "teams" to mapOf("team's" to 206),
        "would" to mapOf("wouldn't" to 207, "would've" to 192),
        "world" to mapOf("world's" to 224),
        "girls" to mapOf("girl's" to 150, "girls'" to 140),
    )
    private val pairedAll = pairFreq.mapValues { it.value.keys.toList() }
    private val lexFreq = mapOf(
        "well" to 223, "wall" to 213, "shed" to 189, "id" to 196, "is" to 250,
        "hell" to 206, "shes" to 177, "teams" to 203, "would" to 228, "world" to 221,
        "wood" to 210, "girls" to 200, "ill" to 198, "its" to 225, "ots" to 150,
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
        // well 223 > we'll 202: the word keeps rank 0 (auto-insert target), the variant is
        // the very next slot instead of being pushed off-screen behind "wall".
        val (words, scores) = applyRanked(listOf("well", "wall"), listOf(900, 800))
        assertThat(words).containsExactly("well", "we'll", "wall").inOrder()
        assertThat(scores).containsExactly(900, 900, 800).inOrder()
    }

    @Test
    fun `variant more frequent than its base goes AHEAD of it and becomes rank 0`() {
        // i'll 212 vs ill 198 (zipf 5.43 vs 4.72, 5x) — swiping I'll used to auto-insert "ill".
        val (words, scores) = applyRanked(listOf("ill", "all"), listOf(900, 700))
        assertThat(words.take(2)).containsExactly("i'll", "ill").inOrder()
        assertThat(scores.take(2)).containsExactly(900, 900).inOrder()
    }

    @Test
    fun `near tie keeps the traced base at rank 0 — shed over she'd`() {
        // she'd 188 vs shed 189 (zipf 4.18 vs 4.23): the flat curated 200 used to promote
        // she'd; the measured value does not, and she'd is still the very next slot.
        val (words, _) = applyRanked(listOf("shed", "shes"), listOf(900, 700))
        assertThat(words.take(2)).containsExactly("shed", "she'd").inOrder()
    }

    @Test
    fun `a lead under PROMOTION_MARGIN does not promote — its keeps rank 0 over it's`() {
        // it's 229 vs its 225: it's IS more frequent (1.55x) but the lead is inside the margin,
        // so the traced literal keeps the auto-insert and it's is spliced right after it.
        val (words, scores) = applyRanked(listOf("its", "ots"), listOf(900, 800))
        assertThat(words).containsExactly("its", "it's", "ots").inOrder()
        assertThat(scores).containsExactly(900, 900, 800).inOrder()
    }

    @Test
    fun `PROMOTION_MARGIN boundary — a lead of exactly the margin promotes, one less does not`() {
        val margin = ContractionOverlay.PROMOTION_MARGIN
        fun top(lead: Int): String = ContractionOverlay.apply(
            listOf("shed"), listOf(900),
            pairedVariants = { if (it == "shed") listOf("she'd") else null },
            nonPairedMapping = { null },
            wordOrdinal = { null },
            pairedVariantFrequency = { _, _ -> 189 + lead },
            baseFrequency = { if (it == "shed") 189 else null },
        ).first.first()
        assertThat(top(margin)).isEqualTo("she'd")
        assertThat(top(margin - 1)).isEqualTo("shed")
        assertThat(top(0)).isEqualTo("shed")
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
        // she's 209 vs shes 177 — "'s" after a pronoun is "is/has", not a possessive.
        val (words, _) = applyRanked(listOf("shes"), listOf(900))
        assertThat(words).containsExactly("she's", "shes").inOrder()
    }

    @Test
    fun `a possessive is never promoted over its base, only spliced after it`() {
        // team's 206 > teams 203 in the shipped files, but wordfreq ranks "teams" far
        // above "team's" (4.97 vs 4.09 zipf) — 24 of the 69 possessive/clitic promotions the
        // raw frequencies would make are wrong this way, so possessives never go ahead.
        // Confident slate (runner-up 60 < 900 / 2), so team's IS spliced — right after.
        val (words, _) = applyRanked(listOf("teams", "trams"), listOf(900, 60))
        assertThat(words).containsExactly("teams", "team's", "trams").inOrder()

        // team's leads by only 3, inside PROMOTION_MARGIN, so the case above would hold even
        // without the possessive rule. ones/one's leads by 12 (217 vs 205, shipped upstream
        // values) while wordfreq says the opposite (5.07 vs 4.39) — only the possessive rule
        // keeps "ones" first here, even on a confident slate.
        val ones = ContractionOverlay.apply(
            listOf("ones", "once"), listOf(900, 60),
            pairedVariants = { if (it == "ones") listOf("one's") else null },
            nonPairedMapping = { null },
            wordOrdinal = { null },
            pairedVariantFrequency = { _, _ -> 217 },
            baseFrequency = { if (it == "ones") 205 else null },
        ).first
        assertThat(217 - 205).isAtLeast(ContractionOverlay.PROMOTION_MARGIN)
        assertThat(ones).containsExactly("ones", "one's", "once").inOrder()
    }

    // ── Possessive beside a CONFIDENT base (2026-09-26, maintainer request) ─────────
    //
    // The swipe shape of team's IS teams, so decoder confidence says nothing about WHICH
    // reading is meant — grammar decides — and a possessive never goes ahead. But when the
    // base is the decoder's confident pick, its possessive is spliced right after it instead
    // of trailing the slate off-screen. Confident = input rank 0 AND the runner-up scores
    // strictly below half the top (the rescorer's R_MIN notion of "contestable").

    @Test
    fun `confident rank-0 base splices its possessive immediately after it`() {
        val (words, scores) = applyRanked(listOf("teams", "trams", "terms"), listOf(900, 120, 80))
        assertThat(words).containsExactly("teams", "team's", "trams", "terms").inOrder()
        assertThat(scores).containsExactly(900, 900, 120, 80).inOrder()
    }

    @Test
    fun `contested rank-0 base keeps its possessive at the tail`() {
        // trams at 600 is within a factor of two of teams: the decoder has not settled the
        // trace, so the live competitor keeps slot 1 and team's stays a tail completion.
        val (words, scores) = applyRanked(listOf("teams", "trams"), listOf(900, 600))
        assertThat(words).containsExactly("teams", "trams", "team's").inOrder()
        assertThat(scores).containsExactly(900, 600, 600).inOrder()
    }

    @Test
    fun `confidence boundary — runner-up at exactly half is contested, one below is confident`() {
        fun slate(runnerUp: Int) = applyRanked(listOf("teams", "trams"), listOf(900, runnerUp)).first
        assertThat(slate(450)).containsExactly("teams", "trams", "team's").inOrder()
        assertThat(slate(449)).containsExactly("teams", "team's", "trams").inOrder()
        assertThat(ContractionOverlay.isConfidentTop(listOf(900, 450))).isFalse()
        assertThat(ContractionOverlay.isConfidentTop(listOf(900, 449))).isTrue()
    }

    @Test
    fun `a single-candidate slate is confident`() {
        assertThat(ContractionOverlay.isConfidentTop(listOf(700))).isTrue()
        val (words, _) = applyRanked(listOf("teams"), listOf(700))
        assertThat(words).containsExactly("teams", "team's").inOrder()
    }

    @Test
    fun `a lower-ranked base's possessive stays at the tail even on a confident slate`() {
        // teams at rank 1 is not the decoder's pick; splicing team's there would push the
        // distinct candidate "terms" down — the would/world pattern b2d7b908 reverted.
        val (words, _) = applyRanked(listOf("trams", "teams", "terms"), listOf(900, 100, 80))
        assertThat(words).containsExactly("trams", "teams", "terms", "team's").inOrder()
    }

    @Test
    fun `a possessive with no known frequency is spliced beside a confident base`() {
        // 510 bin-derived possessives carry no pairing frequency (alzheimers -> alzheimer's).
        // The frequency never decides a possessive's order against its base (never ahead),
        // so its absence does not bar the splice; among several, a known frequency wins, then
        // list order.
        fun apply(variants: List<String>, freq: Map<String, Int>) = ContractionOverlay.apply(
            listOf("alzheimers", "alzheimer"), listOf(900, 100),
            pairedVariants = { if (it == "alzheimers") variants else null },
            nonPairedMapping = { null },
            wordOrdinal = { null },
            pairedVariantFrequency = { _, v -> freq[v] },
            baseFrequency = { if (it == "alzheimers") 170 else null },
        ).first
        assertThat(apply(listOf("alzheimer's"), emptyMap()))
            .containsExactly("alzheimers", "alzheimer's", "alzheimer").inOrder()
        assertThat(apply(listOf("alzheimer's", "alzheimers'"), emptyMap()))
            .containsExactly("alzheimers", "alzheimer's", "alzheimer", "alzheimers'").inOrder()
        assertThat(apply(listOf("alzheimer's", "alzheimers'"), mapOf("alzheimers'" to 140)))
            .containsExactly("alzheimers", "alzheimers'", "alzheimer", "alzheimer's").inOrder()
    }

    @Test
    fun `D1 possessive augment neither duplicates nor re-tails the spliced possessive`() {
        // The shared pipeline's D1 augment (SuggestionHandler) runs on the overlaid slate and
        // generates a possessive for each of the top 3 words. "team" at slot 2 generates
        // "team's" — already spliced at slot 1 — so it must add NOTHING for it: no second copy
        // at the tail, and (append-only by construction) no move of the spliced one.
        val (words, scores) = applyRanked(listOf("teams", "team", "trams"), listOf(900, 120, 80))
        assertThat(words).containsExactly("teams", "team's", "team", "trams").inOrder()

        val additions = tribixbite.cleverkeys.SuggestionHandler.possessiveAdditions(
            words, scores, languages = null,
        ) { tribixbite.cleverkeys.ContractionManager.possessiveForm(it) }
        assertThat(additions.map { it.first }).containsExactly("teams'")
        val bar = words + additions.map { it.first }
        assertThat(bar).containsExactly("teams", "team's", "team", "trams", "teams'").inOrder()
        assertThat(bar.count { it.equals("team's", ignoreCase = true) }).isEqualTo(1)

        // Same after the pipeline's caps-lock shift transform (dedupe is case-insensitive).
        val caps = tribixbite.cleverkeys.SuggestionHandler.possessiveAdditions(
            words.map { it.uppercase() }, scores, languages = null,
        ) { tribixbite.cleverkeys.ContractionManager.possessiveForm(it) }
        assertThat(caps.map { it.first }).containsExactly("TEAMS'")
    }

    @Test
    fun `would-world guard holds on a confident slate`() {
        // would's variants are non-projections and world's is a projection of "worlds", not of
        // "world" — confidence changes nothing for this slate.
        val (words, _) = applyRanked(listOf("would", "world", "wood"), listOf(900, 300, 200))
        assertThat(words.take(3)).containsExactly("would", "world", "wood").inOrder()
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
        // Confident slate so the possessive splice applies (see the confidence tests above).
        val (words, _) = applyRanked(listOf("girls", "gills"), listOf(900, 80))
        assertThat(words).containsExactly("girls", "girl's", "gills", "girls'").inOrder()
    }

    @Test
    fun `unknown base frequency never promotes — splice after only`() {
        // The geometric engine's CKDT ranks are not on the pairing file's byte scale, so it
        // passes no base frequency; the variant is still spliced but never goes ahead.
        val (words, _) = applyRanked(listOf("id", "is"), listOf(900, 700)) { null }
        assertThat(words.take(2)).containsExactly("id", "i'd").inOrder()
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

    // ── Rule 0: user preference for a joiner word (2026-09-29) ─────────────────────────────

    private fun applyEnPreferring(
        prefs: Map<String, UserJoinerPreference.Preference>,
        words: List<String>,
        scores: List<Int>,
    ) = ContractionOverlay.apply(
        words, scores,
        pairedVariants = { enPaired[it] },
        nonPairedMapping = { enNonPaired[it] },
        wordOrdinal = { enOrdinals[it] },
        userPreferredForm = { prefs[it] },
    )

    @Test
    fun `a preferred form takes its surface's rank and score, the real surface stays behind`() {
        val prefs = mapOf("well" to UserJoinerPreference.Preference("we'll", replacesSurface = false))
        val (words, scores) = applyEnPreferring(prefs, listOf("the", "well", "wall"), listOf(900, 800, 700))
        assertThat(words).containsExactly("the", "we'll", "well", "wall").inOrder()
        assertThat(scores).containsExactly(900, 800, 800, 700).inOrder()
    }

    @Test
    fun `a preferred form on a junk alias leaves one entry, not the alias`() {
        val prefs = mapOf("theyd" to UserJoinerPreference.Preference("they'd", replacesSurface = false))
        val (words, _) = applyEnPreferring(prefs, listOf("theyd", "them"), listOf(900, 800))
        assertThat(words).containsExactly("they'd", "them").inOrder()
    }

    @Test
    fun `a preferred form whose surface is no word replaces it, mapped forms go to the tail`() {
        val prefs = mapOf("dont" to UserJoinerPreference.Preference("do-n't", replacesSurface = true))
        val (words, scores) = applyEnPreferring(prefs, listOf("dont", "done"), listOf(900, 800))
        assertThat(words).containsExactly("do-n't", "done", "don't").inOrder()
        assertThat(scores).isInOrder(Comparator.reverseOrder<Int>())
    }

    @Test
    fun `no preference leaves every existing rule untouched`() {
        val none = applyEnPreferring(emptyMap(), listOf("well", "theyd", "dont"), listOf(900, 800, 700))
        val plain = applyEn(listOf("well", "theyd", "dont"), listOf(900, 800, 700))
        assertThat(none).isEqualTo(plain)
    }
}
