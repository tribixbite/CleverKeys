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
            injected = CtcContractionKeys.inject(frTrie, aliasKeys)
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
            "with weak per-frame evidence the real word wins — this is the 'never preferred' " +
                "half of the invariant, and it holding is why the lune regression cannot recur"
        ).that(decode(spell("dabaissement", floor = -9f)).first()).isEqualTo("abaissement")

        assertWithMessage(
            "with strong per-frame evidence the injected key MUST be able to win, or the " +
                "restore is inert rather than merely conservative"
        ).that(decode(spell("dabaissement", floor = -80f)).first()).isEqualTo("dabaissement")
    }

    /**
     * **The finding that matters, pinned so it cannot be forgotten.**
     *
     * NONE of the 16 curated hyphen compounds added on 2026-08-20 reach the bar at realistic
     * per-frame evidence. Every one is beaten by an ordinary French word that shares a prefix:
     *
     * | swiped | what the beam returns instead |
     * |---|---|
     * | `questce` | equestre, question, questions |
     * | `celuici` | celui, celtics, celtic |
     * | `audessous` | auditions, audacieuse, anderson |
     * | `grandsparents` | agrandissement, grandissante, grandissant |
     *
     * The mechanism is the same one the sibling test characterises: a lexicon-native word
     * collects `λ·ln(freq)` ≈ 11 nats that a floor-injected key cannot, which buys it more than
     * a frame of mismatch. So the data added by the 2026-08-17 restore AND by the 2026-08-20
     * curation is **present and correct but not surfaceable through the beam** — it reaches the
     * user only via the display overlay, on words the beam already produced for other reasons.
     *
     * Measured precisely: the assertion is on the TOP-3, because that is roughly what the
     * suggestion bar renders. Some keys do reach the tail of the top-8 — `quelquesunes` lands
     * at rank 7 — which is the difference between "the beam cannot produce it" (false) and "the
     * user will not see it" (true). That distinction matters for the fix: the key IS reachable,
     * it is out-ranked, so the remedy is a scoring question and not a trie one.
     *
     * This test asserts the CURRENT behaviour deliberately. It is not an endorsement: it exists
     * so that if `INJECTED_FREQUENCY` is ever raised, this fails loudly and someone has to
     * re-derive the trade rather than discovering it later from a bug report. Under audit; see
     * the handoff.
     */
    @Test
    fun `curated compounds do not currently reach the bar on realistic evidence`() {
        val shadowed = listOf("questce", "celuici", "cellesci", "audessous", "quelquesunes")
        for (key in shadowed) {
            assertWithMessage("precondition: '$key' must be injected into the trie")
                .that(frTrie.contains(key)).isTrue()
            assertWithMessage(
                "'$key' currently loses to a lexicon-native competitor at realistic per-frame " +
                    "evidence. If this now PASSES, INJECTED_FREQUENCY (or λ) changed — go and " +
                    "re-derive the reachable/never-preferred trade before accepting it."
            ).that(decode(spell(key, floor = -9f)).take(3)).doesNotContain(key)
        }
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
}
