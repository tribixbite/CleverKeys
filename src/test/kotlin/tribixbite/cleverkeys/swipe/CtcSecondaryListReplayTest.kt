package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcDecodedSlate
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSlateAssembly
import tribixbite.cleverkeys.swipe.ctc.CtcSurfaceForms
import tribixbite.cleverkeys.swipe.ctc.CtcUserWords
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * The two Note10+ reports of 2026-11-26 (release r20) replayed through the SHIPPED encoder, the
 * shipped en and es lexicons and the shipped presets — the decode `CtcEngineAdapter.decodeAsync`
 * runs, with each lexicon built the way `lexiconFor` builds it (merge, then trie, then alias-key
 * injection for en). The pure rules are pinned in [tribixbite.cleverkeys.swipe.ctc.CtcSlateAssemblyTest].
 *
 *  - **F1**: English primary, Spanish secondary, `parcero` in the Spanish custom list. A swipe of
 *    `parcero` inserted `parcel`. Here the merged slate must give the word the rank the English
 *    list itself would give it, which is rank one for its own trace and not rank one for a
 *    `parcel` trace.
 *  - **F3**: `world` disabled, yet a `world` swipe offered it. The root cause is shown at the trie
 *    (alias-key injection puts the disabled pairing key back), and the assembled slate must not
 *    offer it while `world's` (a separate pairing entry) stays.
 *
 * Synthetic straight traces over the golden layout ([CtcTraceShapes]); the replay leaves out the
 * fuzzy rescue, which only fills spare slots below the beam.
 */
class CtcSecondaryListReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    private val enParams = CtcScoringParams.presetFor("en", topK = TOP_K)
    private val esParams = CtcScoringParams.presetFor("es", topK = TOP_K)

    /** A decoded slate in the adapter's shape: expanded candidates, their words, softmax scores. */
    private fun slate(
        language: String,
        params: CtcScoringParams,
        candidates: List<CtcCandidate>,
        userWords: CtcUserWords,
        words: List<String> = candidates.map { it.word },
        scores: List<Int> = CtcSlateAssembly.softmaxPermille(candidates.map { it.finalScore }),
    ) = CtcDecodedSlate(language, candidates, words, scores, params, userWords)

    /** `lexiconFor`'s CKDT branch for es, with [userWords] merged as custom words. */
    private class Spanish(val trie: CtcLexiconTrie, val display: Map<String, String>,
                          val alternates: Map<String, Array<tribixbite.cleverkeys.swipe.ctc.CtcAlternateForm>>,
                          val userWords: CtcUserWords)

    private fun spanish(engine: CtcReplayEngine, userWords: List<Pair<String, Int>>): Spanish {
        val entries = File(ES_DICTIONARY).inputStream().use { CkdtDictionaryReader.readEntries(it) }
        val base = CtcCkdtLexicon.frequencyPairs(entries)
        val merged = CtcLexiconMerge.merge(base, userWords, emptySet())
        val projected = CtcAzProjection.projectLexicon(merged)
        return Spanish(
            CtcLexiconTrie.loadFromFrequencyMap(engine.layoutGeometry.alphabet, projected.freqs),
            projected.display, projected.alternates,
            CtcUserWords.of(userWords, emptySet(), CtcLexiconMerge.baseFloor(base)),
        )
    }

    private fun spanishSlate(engine: CtcReplayEngine, es: Spanish, trace: Triple<DoubleArray, DoubleArray, DoubleArray>): CtcDecodedSlate {
        val (x, y, t) = trace
        val raw = engine.decoderOver(es.trie, esParams).decode(x, y, t)
        val expanded = CtcSurfaceForms.expand(raw, es.display, es.alternates, esParams.lambda, TOP_K)
        return slate("es", esParams, expanded, es.userWords)
    }

    private fun englishSlate(engine: CtcReplayEngine, trace: Triple<DoubleArray, DoubleArray, DoubleArray>): CtcDecodedSlate {
        val (x, y, t) = trace
        val base = engine.baseLexiconPairs()
        val candidates = engine.decodeDetailed(x, y, t).candidates.take(TOP_K)
        return slate("en", enParams, candidates, CtcUserWords.of(emptyList(), emptySet(), CtcLexiconMerge.baseFloor(base)))
    }

    /** The top word the English list gives [trace] with `parcero` as an English custom word at 255. */
    private fun englishListTop(engine: CtcReplayEngine, trace: Triple<DoubleArray, DoubleArray, DoubleArray>): CtcCandidate {
        val merged = CtcLexiconMerge.merge(engine.baseLexiconPairs(), listOf(WORD to 255), emptySet())
        val (x, y, t) = trace
        return engine.decoderWithLexicon(merged).decode(x, y, t).first()
    }

    // ── F1 ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a swipe of a Spanish-list custom word inserts it under an English primary`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val trace = CtcTraceShapes.straight(WORD, engine.layoutGeometry)
            val es = spanish(engine, listOf(WORD to 255))
            val primary = englishSlate(engine, trace)
            val secondary = spanishSlate(engine, es, trace)
            println("[F1] en=${primary.words} es=${secondary.words}")
            assertWithMessage("precondition: the Spanish decode finds the user word")
                .that(secondary.words).contains(WORD)

            val merged = CtcSlateAssembly.dual(primary, secondary, TOP_K)
            println("[F1] merged=${merged.map { it.word }}")

            assertWithMessage(
                "Note10+ r20: the bar was `parcel | parcero | pardo…` and `parcel` was inserted. " +
                    "In the English list the same word is rank one for its own trace " +
                    "(${englishListTop(engine, trace).word}); the Spanish list must give it the same."
            ).that(merged.first().word).isEqualTo(WORD)
            assertThat(merged.first().language).isEqualTo("es")
        }
    }

    @Test
    fun `the placement equals the score the English list gives the word`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val trace = CtcTraceShapes.straight(WORD, engine.layoutGeometry)
            val es = spanish(engine, listOf(WORD to 255))
            val inSpanish = spanishSlate(engine, es, trace).candidates.first { it.word == WORD }
            val asEnglishCustom = englishListTop(engine, trace)
            assertThat(asEnglishCustom.word).isEqualTo(WORD)

            val placed = CtcSlateAssembly.primaryEquivalentScore(
                inSpanish, 255, enParams, CtcLexiconMerge.baseFloor(engine.baseLexiconPairs()),
            )
            println("[F1] placed=$placed englishList=${asEnglishCustom.finalScore}")

            assertWithMessage(
                "one emission matrix, the same Viterbi path: only the frequency term differs between " +
                    "the two lists, and the placement must replace it with the English list's own"
            ).that(placed).isWithin(1e-6).of(asEnglishCustom.finalScore)
        }
    }

    @Test
    fun `an English swipe the English list keeps does not turn into the Spanish custom word`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val es = spanish(engine, listOf(WORD to 255))
            for (english in listOf("parcel", "parade", "pardon")) {
                val trace = CtcTraceShapes.straight(english, engine.layoutGeometry)
                val merged = CtcSlateAssembly.dual(englishSlate(engine, trace), spanishSlate(engine, es, trace), TOP_K)
                val englishList = englishListTop(engine, trace)
                println("[F1] $english: merged=${merged.map { it.word }} englishListTop=${englishList.word}")

                assertWithMessage(
                    "the English list with `parcero` in it still answers `${englishList.word}` to a " +
                        "`$english` trace; the Spanish list must not do more for the word than that"
                ).that(merged.first().word).isEqualTo(englishList.word)
            }
        }
    }

    // ── F3 ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a disabled pairing key is offered by no swipe and its possessive stays`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val alphabet = engine.layoutGeometry.alphabet
            val base = engine.baseLexiconPairs()
            val disabled = setOf(DISABLED)
            // lexiconFor's EN_JSON branch: merge minus the disabled word, then the alias keys.
            val merged = CtcLexiconMerge.merge(base, emptyList(), disabled)
            val trie = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, merged)
            assertWithMessage("the merge drops the disabled word").that(trie.contains(DISABLED)).isFalse()
            val aliasKeys = LinkedHashSet<String>()
            for (path in listOf(CtcReplayEngine.EN_CONTRACTIONS, CtcReplayEngine.EN_PAIRINGS)) {
                aliasKeys.addAll(JSONObject(File(path).readText()).keys().asSequence())
            }
            CtcContractionKeys.inject(trie, aliasKeys, CtcContractionKeys.derivedFloor(merged.values))
            assertWithMessage(
                "ROOT CAUSE: `world` is a pairing key (`world` → `world's`), and the injection " +
                    "re-inserts every alias key the merged lexicon lacks — so disabling it put it " +
                    "back at the floor"
            ).that(trie.contains(DISABLED)).isTrue()

            val (x, y, t) = CtcTraceShapes.straight(DISABLED, engine.layoutGeometry)
            val candidates = engine.decoderOver(trie, enParams).decode(x, y, t)
            println("[F3] beam=${candidates.map { it.word }}")
            assertWithMessage("the device symptom: the beam offers the disabled word")
                .that(candidates.map { it.word }).contains(DISABLED)

            // The en contraction overlay as the adapter runs it (the shipped pairings), then the assembly.
            val pairings = ContractionManager.parsePairings(File(CtcReplayEngine.EN_PAIRINGS).readText())
            val ordinals = CtcLexiconMerge.ordinals(merged)
            val (words, scores) = ContractionOverlay.apply(
                words = candidates.map { it.word },
                scores = CtcSlateAssembly.softmaxPermille(candidates.map { it.finalScore }),
                pairedVariants = { b -> pairings[b]?.map { it.contraction } },
                nonPairedMapping = { null },
                wordOrdinal = { ordinals[it] },
                pairedVariantFrequency = { b, v -> pairings[b]?.firstOrNull { it.contraction == v }?.frequency },
                baseFrequency = { merged[it]?.toInt() },
            )
            val userWords = CtcUserWords.of(emptyList(), disabled, CtcLexiconMerge.baseFloor(base))
            val (shown, _) = CtcSlateAssembly.single(slate("en", enParams, candidates, userWords, words, scores))
            println("[F3] shown=$shown")

            assertWithMessage("no swipe alternate may offer a disabled word").that(shown).doesNotContain(DISABLED)
            assertWithMessage(
                "`world's` is its own pairing entry (from `world` and `worlds`); tap typing offers it " +
                    "with `world` disabled, so the swipe keeps it"
            ).that(shown).contains("world's")
        }
    }

    private companion object {
        const val TOP_K = CtcReplayEngine.TOP_K
        const val WORD = "parcero"
        const val DISABLED = "world"
        const val ES_DICTIONARY = "src/main/assets/dictionaries/es_enhanced.bin"
    }
}
