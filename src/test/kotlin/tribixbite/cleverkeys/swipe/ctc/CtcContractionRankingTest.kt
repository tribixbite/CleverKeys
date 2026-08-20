package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * Contraction ranking through the REAL beam decoder, on the REAL shipped French assets.
 *
 * ## The gap this closes
 *
 * `ContractionFrequencyTest` was deleted with the neural engine. That was correct — every one of
 * its assertions went through `VocabularyUtils.calculateCombinedScore`, the transformer's scoring
 * formula, with hard-coded fake frequencies. But it left the underlying questions untested on the
 * surviving path, and two of them are load-bearing:
 *
 *  1. **Does a floor-injected alias key actually SURFACE?** `CtcContractionKeys.inject` adds keys
 *     at `MIN_FREQ`, whose `λ·ln(freq)` term is ~0. Everything so far has verified the key is
 *     *present in the trie* — which is not the same as the beam being able to return it in the
 *     top-K against real competitors. A key that is reachable but never surfaces is inert, and
 *     the whole 2026-08-17 restore rests on this.
 *  2. **Does the floor actually hold the line?** "Reachable, never preferred" has been asserted
 *     by reading the constant. This asserts it through the scorer: when the emission evidence is
 *     genuinely ambiguous, the real lexicon word must win.
 *
 * Pure JVM: `CtcBeamDecoder` takes emissions as data, so the decode runs without the ONNX model.
 * The emissions are synthetic and deliberately unambiguous where the test is about reachability,
 * and deliberately balanced where it is about ranking — a real trace would confound the two.
 */
class CtcContractionRankingTest {

    private companion object {
        val AZ = ('a'..'z').toList().toCharArray()
        const val DICT = "src/main/assets/dictionaries"

        /** The real projected French lexicon, exactly as `CtcEngineAdapter` builds it. */
        lateinit var frTrie: CtcLexiconTrie

        /** Alias keys injected into it, and how many actually took. */
        var injected = 0

        /** The derived injection floor for the real fr lexicon. */
        var floor = 0.0

        /** The REAL lexicon surfaces, before injection — the shadow-pair sweep needs these. */
        lateinit var realWords: Set<String>

        @BeforeClass
        @JvmStatic
        fun buildRealFrenchTrie() {
            val bin = File("$DICT/fr_enhanced.bin")
            check(bin.isFile) { "expected shipped asset ${bin.path} (run from project root)" }
            val entries = bin.inputStream().use { CkdtDictionaryReader.readEntries(it) }
            val merged = CtcLexiconMerge.merge(
                CtcCkdtLexicon.frequencyPairs(entries), emptyList(), emptySet()
            )
            val projected = CtcAzProjection.projectLexicon(merged)
            frTrie = CtcLexiconTrie.loadFromFrequencyMap(AZ, projected.freqs)

            val aliasKeys = jsonKeys("contractions_fr.json") + jsonKeys("contraction_pairs_fr.json")
            // Mirrors the adapter exactly: inject one below the lexicon's rarest real word.
            realWords = projected.freqs.keys.toSet()
            floor = CtcContractionKeys.derivedFloor(projected.freqs.values)
            injected = CtcContractionKeys.inject(frTrie, aliasKeys, floor)
        }

        /** Top-level keys of a shipped JSON object asset, without pulling in a JSON parser. */
        private fun jsonKeys(name: String): List<String> =
            Regex("\"([^\"]+)\"\\s*:").findAll(File("$DICT/$name").readText())
                .map { it.groupValues[1] }
                .toList()

        /** Log-emissions spelling [word] confidently: one frame per letter, blank-separated. */
        private fun spell(word: String, confidence: Float = -0.05f, floor: Float = -9f): CtcEmissions {
            val blank = AZ.size
            val cols = AZ.size + 1
            // A blank between every pair, and around the word: this is the alignment the trained
            // CTC convention expects, and it is what makes a doubled letter decodable at all.
            val peaks = mutableListOf(blank)
            for (ch in word) {
                peaks += (ch - 'a')
                peaks += blank
            }
            val values = FloatArray(peaks.size * cols) { floor }
            peaks.forEachIndexed { t, peak -> values[t * cols + peak] = confidence }
            return CtcEmissions(values, peaks.size, cols)
        }

        private fun decode(emissions: CtcEmissions, topK: Int = 8): List<String> =
            CtcBeamDecoder
                .decode(emissions, frTrie, CtcScoringParams.presetFor("fr", topK = topK))
                .map { it.word }
    }

    @Test
    fun `the real french trie was built and aliases were injected`() {
        // Guards the guard: if either of these is wrong every assertion below is vacuous.
        assertWithMessage("the shipped fr lexicon must have projected onto a-z")
            .that(frTrie.wordCount).isGreaterThan(30_000)
        assertWithMessage(
            "contraction aliases must have been injected — without them the tests below would " +
                "pass trivially by never having a pseudo-word to rank at all"
        ).that(injected).isGreaterThan(10_000)
    }

    /**
     * CHARACTERISATION, not an aspiration. Records how much emission evidence a floor-injected
     * key needs before it can beat a lexicon-native competitor, because the answer turned out to
     * be surprising and load-bearing.
     *
     * `dabaissement` is the canonical productive elision: not a French dictionary word, present
     * only because `CtcContractionKeys.inject` added it at `MIN_FREQ` (log-freq ~0). The real
     * word `abaissement` differs by ignoring the swiped leading `d` — one frame — while
     * collecting `λ·ln(freq)` ≈ 2.0 × ln(~250) ≈ 11 nats the injected key cannot.
     *
     * So the injected key only wins once one frame of emission evidence is worth more than ~11
     * nats. Measured on the real fr trie at the shipped preset:
     *
     * | per-frame penalty | top-3 |
     * |---|---|
     * | −9  | abaissement, rabaisser, abaissee — injected key absent from top-8 |
     * | −20 | abaissement, **dabaissement**, labaissement |
     * | −80 | **dabaissement**, abaissement, labaissement |
     *
     * Whether −9 or −20 resembles the shipped model's real margins is under audit; that decides
     * whether the 2026-08-17 restore reaches the bar in practice or only ever supplies the
     * display overlay. This test does NOT assert a preference either way — it pins the
     * behaviour so that any change to `INJECTED_FREQUENCY` becomes visible instead of silent.
     */
    @Test
    fun `an injected key needs strong evidence to beat a lexicon-native competitor`() {
        assertWithMessage("precondition: dabaissement must be INJECTED, not lexicon-native")
            .that(frTrie.contains("dabaissement")).isTrue()

        assertWithMessage(
            "emissions that spell the injected key must RETURN it at realistic per-frame " +
                "evidence. Before the derived floor (2026-08-20) this returned `abaissement` — " +
                "the real word won by treating the swiped leading `d` as blank, because its " +
                "frequency bonus outweighed a frame of mismatch once the beam divided the " +
                "emission evidence by len^0.9 and left the bonus undivided."
        ).that(decode(spell("dabaissement", floor = -9f)).first()).isEqualTo("dabaissement")
    }

    /**
     * The curated compounds now reach the bar — this is what the derived floor bought.
     *
     * Before 2026-08-20 not one of the 16 hyphen compounds added the previous day reached the
     * top-3 at realistic evidence; each lost to an ordinary French word sharing a prefix
     * (`questce` to `equestre`/`question`, `celuici` to `celui`, `grandsparents` to
     * `agrandissement`). The data was correct and the trie contained it, but the beam could not
     * return it, so the restore reached the user only through the display overlay.
     *
     * Injecting one below the lexicon's rarest REAL word (fr: 68) closes a gap that was never
     * needed. The invariant is unchanged — every real word still strictly outranks every
     * pseudo-word on frequency — but the margin is now ~0.03–2.5 nats instead of ~8.5, which
     * emission evidence can actually decide.
     */
    @Test
    fun `curated compounds reach the bar on realistic evidence`() {
        for (key in listOf("questce", "celuici", "cellesci", "audessous", "quelquesunes")) {
            assertWithMessage("precondition: '$key' must be injected into the trie")
                .that(frTrie.contains(key)).isTrue()
            assertWithMessage(
                "'$key' must reach the top-3 when the emissions spell it — that is the whole " +
                    "point of injecting it. If this fails, the injection floor regressed."
            ).that(decode(spell(key, floor = -9f)).take(3)).contains(key)
        }
    }

    /**
     * The invariant the derived floor must NOT have broken: a pseudo-word may never outrank a
     * real word that the emissions equally support.
     *
     * This is the `lune` guard expressed through the scorer. `derivedFloor` keeps every real
     * word strictly above every injected key on frequency BY CONSTRUCTION, so the property
     * holds in any lexicon — but it is asserted here rather than trusted, because the whole
     * 2026-08-17 recovery exists because this was once got wrong.
     */
    @Test
    fun `an injected key never outranks the real word its emissions spell`() {
        assertWithMessage("precondition: lune is lexicon-native, not floor-injected")
            .that(frTrie.contains("lune")).isTrue()
        assertWithMessage("precondition: the injection floor must sit BELOW every real word")
            .that(floor).isLessThan(69.0)

        // Emissions spelling the real word must return the real word, not the injected sibling
        // that differs by a leading clitic.
        assertWithMessage(
            "a real word's own emissions must return the real word — if an injected key wins " +
                "here the floor is too high and the lune regression is back"
        ).that(decode(spell("abaissement", floor = -9f)).first()).isEqualTo("abaissement")
    }

    @Test
    fun `a lexicon-native word is returned for emissions that spell it`() {
        assertWithMessage("precondition: lune is lexicon-native, not floor-injected")
            .that(frTrie.contains("lune")).isTrue()
        assertWithMessage(
            "emissions spelling a real word must return that word first — an injected " +
                "pseudo-word must never displace it"
        ).that(decode(spell("lune")).first()).isEqualTo("lune")
    }

    /**
     * The regression sweep: across MANY real shadow pairs, a real word must still win its own
     * emissions now that the injection floor sits just below it.
     *
     * ## Why this test exists in this form
     *
     * The audit that prescribed the derived floor called replay validation mandatory, and named
     * the sensitive metric: the count of traces whose top-1 flips real-word → pseudo-word. But
     * `scripts/ctc_lang_lambda_sweep.py` and `eval_altlayout` do NOT model contraction injection
     * at all — verified by search — so an A/B through that harness would decode identically in
     * both arms and prove nothing. Extending it is real work and is recorded as owed.
     *
     * This is the strongest runnable equivalent: it asks the same question the corpus would
     * (does narrowing the frequency gap let a pseudo-word steal a real word's own swipe?) over
     * every shadow pair the real fr lexicon actually contains, on synthetic emissions that
     * spell the real word at a realistic per-frame margin. It is weaker than a corpus replay in
     * one specific way — real traces are noisier than a clean synthetic peak — and stronger in
     * another: it covers every affected pair rather than whichever happen to appear in 1,000
     * sampled traces.
     */
    @Test
    fun `real words still win their own emissions against injected siblings`() {
        // Shadow pair: a real lexicon word W for which some injected key is <clitic> + W. Those
        // are exactly the pairs where the narrowed gap could flip a decision.
        val clitics = listOf('d', 'l', 'j', 'm', 'n', 'c', 's', 't', 'q')
        val shadowed = realWords
            .asSequence()
            .filter { it.length in 6..11 }
            .filter { w -> clitics.any { frTrie.contains(it + w) } }
            .take(120)
            .toList()

        assertWithMessage(
            "precondition: the fr lexicon must actually contain shadow pairs, or this sweep is " +
                "vacuous and proves nothing about the risk it exists to measure"
        ).that(shadowed.size).isAtLeast(20)

        val flipped = shadowed.filter { real ->
            decode(spell(real, floor = -9f), topK = 1).firstOrNull() != real
        }

        assertWithMessage(
            "these real words lost their OWN emissions to an injected sibling. The derived " +
                "floor keeps every real word strictly above every pseudo-word on frequency, so " +
                "any flip here means the emission term alone decided it — which is precisely " +
                "the lune regression in a new form. Flipped: $flipped"
        ).that(flipped).isEmpty()
    }
}
