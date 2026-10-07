package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.swipe.ContractionOverlay
import java.io.File

/**
 * G5 audit H1 — CTC contraction DISPLAY mapping, end-to-end over the SHIPPED
 * lexicon: `en_enhanced.json` contains zero apostrophe words (contractions are
 * a-z aliases), so a raw CTC decode surfaces "dont"/"im". The adapter overlays
 * [ContractionOverlay] with ordinals from [CtcLexiconMerge.ordinals]; this test
 * runs that exact combination (real asset ranks + production-shaped contraction
 * fixtures, mirroring `ContractionOverlayTest`'s en fixtures) and locks in:
 *
 *  - junk aliases are REPLACED: dont → don't, im → I'm, cant → can't;
 *  - paired real-word bases are KEPT, never replaced: "well" stays "well" with
 *    "we'll" spliced right after it (2026-09-26 placement rule — we'll's measured
 *    frequency 202 is below well's lexicon frequency 223);
 *  - the pronoun set over the SHIPPED, MEASURED pairing + lexicon frequencies:
 *    I'd / I'll / we'd / he's / she's become rank 0 over id / ill / wed / hes / shes,
 *    while shed / shell / whore / well / hell / were / its keep rank 0 with the
 *    contraction at rank 1 (learning-system audit RC1 + RC3); the formerly bin-only
 *    pairs follow the same rule: why's is rank 0 over whys, natl keeps rank 0 over nat'l;
 *  - the frequency-descending ordinal ranking of the shipped asset actually
 *    exhibits the separation `ContractionOverlay.REAL_WORD_ORDINAL_MAX` = 1200
 *    assumes (junk aliases deep, real-word bases shallow) — the threshold
 *    oracle for THIS ranking, the CTC analogue of the geometric CKDT numbers
 *    measured in the 2026-07-23 audit.
 */
class CtcContractionDisplayTest {

    companion object {
        private const val DICT_ASSET = "src/main/assets/dictionaries/en_enhanced.json"

        private const val PAIRINGS_ASSET = "src/main/assets/dictionaries/contraction_pairings.json"

        /** Lowercase word → frequency ordinal over the shipped, unmodified lexicon. */
        private lateinit var ordinals: HashMap<String, Int>

        /** The shipped lexicon's frequencies (the 134..255 byte scale λ was fitted on). */
        private lateinit var lexicon: LinkedHashMap<String, Double>

        /** The shipped pairings, parsed by the PRODUCTION parser. */
        private lateinit var pairings: Map<String, List<ContractionManager.PairedVariant>>

        @JvmStatic
        @BeforeClass
        fun loadShippedLexicon() {
            val file = File(DICT_ASSET)
            check(file.isFile) { "expected shipped lexicon at ${file.path} (run from project root)" }
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val base = ArrayList<Pair<String, Double>>(root.size())
            for ((word, freq) in root.entrySet()) {
                base.add(word to freq.asDouble)
            }
            val merged = CtcLexiconMerge.merge(base, emptyList(), emptySet())
            ordinals = CtcLexiconMerge.ordinals(merged)
            lexicon = merged
            pairings = ContractionManager.parsePairings(File(PAIRINGS_ASSET).readText())
        }
    }

    // Production-shaped contraction fixtures (contractions.bin / contractions_en.json),
    // including the binary store's paired-entry pollution of the non-paired map that
    // makes the paired-first rule load-bearing (see ContractionOverlayTest).
    private val enPaired = mapOf(
        "well" to listOf("we'll"),
        "its" to listOf("it's"),
    )
    private val enNonPaired = mapOf(
        "dont" to "don't",
        "im" to "I'm",
        "cant" to "can't",
        "theyd" to "they'd",
        "well" to "we'll",
        "its" to "it's",
    )

    private fun applyCtc(words: List<String>, scores: List<Int>) = ContractionOverlay.apply(
        words, scores,
        pairedVariants = { enPaired[it] },
        nonPairedMapping = { enNonPaired[it] },
        wordOrdinal = { ordinals[it] },
        pairedVariantFrequency = ::shippedPairFrequency,
        baseFrequency = ::shippedBaseFrequency,
    )

    private fun shippedPairFrequency(base: String, variant: String): Int? =
        pairings[base]?.firstOrNull { it.contraction == variant }?.frequency

    private fun shippedBaseFrequency(word: String): Int? = lexicon[word]?.toInt()

    /** The overlay exactly as the CTC adapter runs it for en, over the shipped pairings. */
    private fun applyShipped(words: List<String>, scores: List<Int>) = ContractionOverlay.apply(
        words, scores,
        pairedVariants = { base -> pairings[base]?.map { it.contraction } },
        nonPairedMapping = { enNonPaired[it] ?: mapOf(
            "theyll" to "they'll", "theyd" to "they'd", "hed" to "he'd",
            "youd" to "you'd", "itll" to "it'll", "youll" to "you'll",
        )[it] },
        wordOrdinal = { ordinals[it] },
        pairedVariantFrequency = ::shippedPairFrequency,
        baseFrequency = ::shippedBaseFrequency,
    )

    // ── H1 decode-result mapping ────────────────────────────────────────────────────

    @Test
    fun `dont presents as don't`() {
        val (words, scores) = applyCtc(listOf("dont", "done"), listOf(900, 800))
        assertThat(words).containsExactly("don't", "done").inOrder()
        assertThat(scores).containsExactly(900, 800).inOrder()
    }

    @Test
    fun `im presents as I'm`() {
        val (words, _) = applyCtc(listOf("im"), listOf(900))
        assertThat(words).containsExactly("I'm")
    }

    @Test
    fun `cant presents as can't`() {
        val (words, _) = applyCtc(listOf("cant"), listOf(900))
        assertThat(words).containsExactly("can't")
    }

    @Test
    fun `well stays well — paired base keeps its slot, variant spliced right after`() {
        val (words, scores) = applyCtc(listOf("well", "wall"), listOf(900, 800))
        assertThat(words).containsExactly("well", "we'll", "wall").inOrder()
        assertThat(scores).containsExactly(900, 900, 800).inOrder()
    }

    // ── Reported pronoun set over the SHIPPED data (learning-system audit RC1 + RC3) ──

    @Test
    fun `paired pronoun contractions rank by measured pairing vs lexicon frequency`() {
        // Each key is decoded as the TOP beam candidate with two distinct words behind it.
        // Expected: the contraction is rank 0 exactly when its MEASURED pairing frequency beats
        // the key's own lexicon frequency by at least ContractionOverlay.PROMOTION_MARGIN, and
        // is ALWAYS within the first two slots — never behind the distinct candidates (the
        // pre-fix tail placement put it at slot 3+). Values: variant byte vs base byte, both
        // on en_enhanced.json's scale (see BundledContractionDataTest for provenance).
        val expectedTop = mapOf(
            "id" to "i'd", // 211 vs 196
            "ill" to "i'll", // 212 vs 198
            "wed" to "we'd", // 193 vs 178
            "hes" to "he's", // 215 vs 184
            "shes" to "she's", // 209 vs 177
            "shed" to "shed", // 188 vs 189 — near tie (zipf 4.18 vs 4.23), base keeps rank 0
            "shell" to "shell", // 188 vs 192 — was a flat-200 artefact that promoted she'll
            "whore" to "whore", // 163 vs 182 — was a flat-200 artefact that promoted who're
            "well" to "well", // 202 vs 223
            "hell" to "hell", // 195 vs 206
            "were" to "were", // 211 vs 229
            "its" to "its", // 229 vs 225 — it's IS more frequent, but inside the margin
            // Formerly bin-only pairs (no frequency -> tail, off-screen), measured 2026-09-26:
            "whys" to "why's", // 166 vs 157 (zipf 3.01 vs 2.52) — "why's that?" beats "the whys"
            "natl" to "natl", // 159 vs 158 — near tie, the traced abbreviation keeps rank 0
        )
        val variantOf = mapOf(
            "id" to "i'd", "ill" to "i'll", "wed" to "we'd", "hes" to "he's", "shes" to "she's",
            "shed" to "she'd", "shell" to "she'll", "whore" to "who're", "well" to "we'll",
            "hell" to "he'll", "were" to "we're", "its" to "it's",
            "whys" to "why's", "natl" to "nat'l",
        )
        for ((key, top) in expectedTop) {
            val (words, _) = applyShipped(listOf(key, "zzfill", "zzfiller"), listOf(900, 800, 700))
            assertWithMessage("swiped '$key' slate $words").that(words[0]).isEqualTo(top)
            assertWithMessage("swiped '$key' slate $words")
                .that(words.take(2)).containsExactly(key, variantOf.getValue(key))
        }
    }

    @Test
    fun `its and it's — spliced beside, never auto-inserted over its`() {
        // its/it's is the canonical grammatical confusable: the trace is identical, only
        // syntax decides, and the overlay has no syntax. The measured prior (it's 229 vs its
        // 225, zipf 6.33 vs 6.14 = 1.55x) is a near tie, so it's is spliced at slot 1 — one
        // tap away instead of the pre-2026-09-26 tail (it lived only in contractions.bin, with
        // no frequency) — but the literal "its" keeps rank 0 and the auto-insert.
        val its = pairings.getValue("its").single { it.contraction == "it's" }.frequency!!
        val base = lexicon.getValue("its").toInt()
        assertThat(its).isGreaterThan(base) // the data does NOT fake the tie
        assertThat(its - base).isLessThan(ContractionOverlay.PROMOTION_MARGIN)

        val (words, scores) = applyShipped(listOf("its", "ots"), listOf(900, 800))
        assertThat(words).containsExactly("its", "it's", "ots").inOrder()
        assertThat(scores).containsExactly(900, 900, 800).inOrder()
    }

    @Test
    fun `REPLACE-bucket pronoun contractions keep their own slot`() {
        for ((key, display) in listOf(
            "theyll" to "they'll", "theyd" to "they'd", "hed" to "he'd",
            "youd" to "you'd", "itll" to "it'll", "youll" to "you'll",
        )) {
            assertWithMessage("'$key' must be past the real-word guard")
                .that(ordinals[key]!!).isAtLeast(ContractionOverlay.REAL_WORD_ORDINAL_MAX)
            val (words, _) = applyShipped(listOf(key, "zzfill"), listOf(900, 800))
            assertThat(words).containsExactly(display, "zzfill").inOrder()
        }
    }

    @Test
    fun `shipped would-world slate keeps world at rank 1`() {
        // b2d7b908 on-device regression, re-pinned over the shipped data.
        val (words, _) = applyShipped(listOf("would", "world", "wood"), listOf(900, 850, 800))
        assertThat(words.take(3)).containsExactly("would", "world", "wood").inOrder()
    }

    @Test
    fun `shipped would-world slate keeps world at rank 1 on a confident slate too`() {
        // The 2026-09-26 possessive splice keys on decoder confidence; "would" has no
        // possessive projection (world's projects to "worlds"), so nothing moves.
        val (words, _) = applyShipped(listOf("would", "world", "wood"), listOf(900, 300, 200))
        assertThat(words.take(3)).containsExactly("would", "world", "wood").inOrder()
    }

    @Test
    fun `shipped possessives — beside a confident rank-0 base, tail otherwise, never ahead`() {
        // Maintainer request 2026-09-26: "bump team's when the decoder is confident in teams".
        val (confident, _) = applyShipped(listOf("teams", "zzfill", "zzfiller"), listOf(900, 100, 50))
        assertThat(confident).containsExactly("teams", "team's", "zzfill", "zzfiller").inOrder()

        // Contested (runner-up within 2x): the competitor keeps slot 1, team's trails.
        val (contested, _) = applyShipped(listOf("teams", "zzfill"), listOf(900, 600))
        assertThat(contested).containsExactly("teams", "zzfill", "team's").inOrder()

        // Lower-ranked base: its possessive stays at the tail even on a confident slate.
        val (lower, _) = applyShipped(listOf("zzfill", "teams", "zzfiller"), listOf(900, 100, 50))
        assertThat(lower).containsExactly("zzfill", "teams", "zzfiller", "team's").inOrder()

        // ones/one's: the upstream possessive value leads the lexicon by 12 (>= the promotion
        // margin) while wordfreq says the opposite — still only BESIDE, never ahead.
        val (ones, _) = applyShipped(listOf("ones", "zzfill"), listOf(900, 100))
        assertThat(ones).containsExactly("ones", "one's", "zzfill").inOrder()

        // Sweep: EVERY shipped base with a possessive projection, decoded as a confident top-1,
        // keeps rank 0 and shows exactly its possessive at slot 1.
        var swept = 0
        val shortBases = ArrayList<String>()
        for ((base, variants) in pairings) {
            val possessives = variants.map { it.contraction }.filter {
                ContractionOverlay.isProjectionOf(base, it) && ContractionOverlay.isPossessive(it)
            }
            if (possessives.isEmpty()) continue
            val (words, _) = applyShipped(listOf(base, "zzfill"), listOf(900, 100))
            if (base.length < tribixbite.cleverkeys.ContractionInjectionPolicy.MIN_BASE_LENGTH) {
                // 2026-10-07: `is`→`i's`, `as`→`a's`, `ms`→`m's` … are letter plurals — never shown.
                assertWithMessage("swiped '$base' slate $words").that(words).containsNoneIn(possessives)
                assertWithMessage("swiped '$base' slate $words").that(words[0]).isEqualTo(base)
                shortBases += base
                continue
            }
            assertWithMessage("swiped '$base' slate $words").that(words[0]).isEqualTo(base)
            assertWithMessage("swiped '$base' slate $words").that(words[1]).isIn(possessives)
            swept++
        }
        // 593 of the file's bases list a possessive whose apostrophe-free form is the base
        // itself (`teams` → `team's`); the other possessive entries are non-projections
        // (`world` → `world's`, a different trace) and are unaffected. Exact, so a data
        // regeneration that silently drops the family fails here.
        assertThat(swept).isEqualTo(576)
        // The 17 two-letter bases are all single-letter hosts (letter plurals): as is vs bs cs …
        assertThat(shortBases).containsExactly(
            "as", "bs", "cs", "ds", "es", "gs", "is", "js", "ks", "ms", "os", "ps", "qs", "rs", "ts", "vs", "xs",
        )
    }

    @Test
    fun `shipped lets — let's keeps rank 0 and the bare word is one tap away`() {
        // 2026-10-07 round 2: `lets` moved from REPLACE to PAIRED. let's leads the lexicon's
        // `lets` by more than PROMOTION_MARGIN, so it stays the auto-insert.
        val (words, _) = applyShipped(listOf("lets", "zzfill"), listOf(900, 100))
        assertThat(words.take(3)).containsExactly("let's", "lets", "zzfill").inOrder()
    }

    @Test
    fun `its stays its with the variant appended`() {
        val (words, _) = applyCtc(listOf("its"), listOf(900))
        assertThat(words).containsExactly("its", "it's").inOrder()
    }

    @Test
    fun `unmapped words pass through untouched`() {
        val input = listOf("hello", "world")
        val (words, scores) = applyCtc(input, listOf(900, 800))
        assertThat(words).isEqualTo(input)
        assertThat(scores).containsExactly(900, 800).inOrder()
    }

    // ── Threshold oracle over the shipped ranking ───────────────────────────────────

    @Test
    fun `shipped lexicon ordinals separate junk aliases from real-word bases at 1200`() {
        val max = ContractionOverlay.REAL_WORD_ORDINAL_MAX
        // Non-paired junk aliases must rank DEEP (replacement is safe).
        for (alias in listOf("dont", "im", "cant", "theyd", "wont")) {
            assertThat(ordinals[alias]).isNotNull()
            assertThat(ordinals[alias]!!).isAtLeast(max)
        }
        // Real-word contraction bases must rank SHALLOW (never replaced, only
        // augmented) — the guard's protected side.
        for (word in listOf("were", "its", "well", "hell")) {
            assertThat(ordinals[word]!!).isLessThan(max)
        }
    }

    // ── User preference for a joiner word, over the SHIPPED data (2026-09-29) ──────────────

    /**
     * The whole paired pronoun set a user can prefer after the swipe offer: with the variant
     * in the personal dictionary, the variant is rank 0 and the base is kept at #1 — over the
     * shipped lexicon, pairings and merge, built exactly as `CtcEngineAdapter`'s EN branch.
     */
    @Test
    fun `a preferred contraction is rank 0 over its base for every shipped near-tie pronoun`() {
        val alphabetSet = ('a'..'z').toHashSet()
        for ((base, variant) in listOf("shed" to "she'd", "shell" to "she'll", "well" to "we'll", "hell" to "he'll")) {
            val userWords = listOf(variant to tribixbite.cleverkeys.UserWordFrequency.DEFAULT)
            val prefs = tribixbite.cleverkeys.swipe.UserJoinerPreference.build(
                userWords,
                { tribixbite.cleverkeys.swipe.UserJoinerPreference.stripToAlphabet(it, alphabetSet) },
                { ordinals.containsKey(it) },
            )
            val (words, _) = ContractionOverlay.apply(
                listOf(base, "the"), listOf(900, 100),
                pairedVariants = { b -> pairings[b]?.map { it.contraction } },
                nonPairedMapping = { null },
                wordOrdinal = { ordinals[it] },
                pairedVariantFrequency = ::shippedPairFrequency,
                baseFrequency = ::shippedBaseFrequency,
                userPreferredForm = { prefs[it] },
            )
            assertWithMessage("prefer $variant").that(words.take(2)).containsExactly(variant, base).inOrder()
            val (plain, _) = applyShipped(listOf(base, "the"), listOf(900, 100))
            assertWithMessage("without the user word $base keeps rank 0").that(plain.first()).isEqualTo(base)
        }
    }
}
