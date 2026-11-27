package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcDecodedSlate
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSlateAssembly
import tribixbite.cleverkeys.swipe.ctc.CtcUserWords
import java.io.File

/**
 * Disabling a word removes that word's OWN swipe display only (Saga release r21, 2026-11-26,
 * case 10). With `well` disabled the w-e-l-l path offered `ell | wl | el | will | elk | wel | wall
 * | …`: `we'll` went with it, twice in a row. Replayed through the SHIPPED encoder and the shipped
 * en lexicon, with the trie built as `CtcEngineAdapter.lexiconFor` builds it for EN_JSON — merge
 * minus the disabled words, [DisabledPairedSurfaces], then the alias-key injection — and the slate
 * as `decodeAsync` finishes it: the contraction overlay over the shipped pairings, then
 * [CtcSlateAssembly.single].
 */
class CtcDisabledWordReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    private val params = CtcScoringParams.presetFor("en", topK = CtcReplayEngine.TOP_K)
    private val pairings by lazy { ContractionManager.parsePairings(File(CtcReplayEngine.EN_PAIRINGS).readText()) }
    private val aliasKeys by lazy {
        LinkedHashSet<String>().apply {
            for (path in listOf(CtcReplayEngine.EN_CONTRACTIONS, CtcReplayEngine.EN_PAIRINGS)) {
                addAll(JSONObject(File(path).readText()).keys().asSequence())
            }
        }
    }

    /** The finished en slate for a straight trace of [word] with [disabled] words disabled. */
    private fun slate(engine: CtcReplayEngine, word: String, disabled: Set<String>): List<String> {
        val alphabet = engine.layoutGeometry.alphabet
        val alphabetSet = alphabet.toHashSet()
        val base = engine.baseLexiconPairs()
        val merged = CtcLexiconMerge.merge(base, emptyList(), disabled)
        val userWords = CtcUserWords.of(emptyList(), disabled, CtcLexiconMerge.baseFloor(base))
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, merged)
        for ((surface, frequency) in DisabledPairedSurfaces.keep(
            userWords.excluded, base, { k -> pairings[k]?.map { it.contraction } },
            { UserJoinerPreference.stripToAlphabet(it, alphabetSet) },
        )) {
            trie.insert(surface, frequency)
        }
        CtcContractionKeys.inject(trie, aliasKeys, CtcContractionKeys.derivedFloor(merged.values))

        val (x, y, t) = CtcTraceShapes.straight(word, engine.layoutGeometry)
        val candidates = engine.decoderOver(trie, params).decode(x, y, t)
        val pairedBases = PairingBaseFrequencies.select(merged, pairings.keys)
        val (words, scores) = ContractionOverlay.apply(
            words = candidates.map { it.word },
            scores = CtcSlateAssembly.softmaxPermille(candidates.map { it.finalScore }),
            pairedVariants = { b -> pairings[b]?.map { it.contraction } },
            nonPairedMapping = { null },
            wordOrdinal = { null },
            pairedVariantFrequency = { b, v -> pairings[b]?.firstOrNull { it.contraction == v }?.frequency },
            baseFrequency = { pairedBases[it] },
            isExcludedBase = { it in userWords.excluded },
        )
        val shown = CtcSlateAssembly.single(CtcDecodedSlate("en", candidates, words, scores, params, userWords)).first
        println("[case10] $word disabled=$disabled beam=${candidates.map { it.word }} shown=$shown")
        return shown
    }

    @Test
    fun `a disabled base keeps its paired contraction on the slate`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            for ((base, contraction) in listOf("well" to "we'll", "were" to "we're", "ill" to "i'll")) {
                val enabled = slate(engine, base, emptySet())
                assertWithMessage("control: with `$base` enabled the path offers both")
                    .that(enabled).containsAtLeast(base, contraction)

                val shown = slate(engine, base, setOf(base))

                assertWithMessage(
                    "Saga r21 case 10: disabling `$base` must remove `$base` only; `$contraction` is " +
                        "the same path's other reading and a separate entry. Got $shown"
                ).that(shown).contains(contraction)
                assertWithMessage("the disabled word itself is never offered").that(shown).doesNotContain(base)
                assertWithMessage(
                    "`$contraction` takes the slot the disabled word had on this trace"
                ).that(shown.indexOf(contraction)).isAtMost(enabled.indexOf(base) + 1)
            }
        }
    }

    @Test
    fun `disabling a contraction keeps its base word`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            for ((base, contraction) in listOf("well" to "we'll", "were" to "we're", "ill" to "i'll")) {
                val shown = slate(engine, base, setOf(contraction))

                assertWithMessage("disabling `$contraction` must not remove `$base`. Got $shown")
                    .that(shown).contains(base)
                assertWithMessage("and `$contraction` itself is gone").that(shown).doesNotContain(contraction)
            }
        }
    }

    @Test
    fun `a disabled word whose possessive is another path gives its slot to the next word`() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val shown = slate(engine, "world", setOf("world"))

            // `world's` is spelled on `worlds`; `world` is not kept (no projection) and stays at the floor.
            assertWithMessage("`world's` must not inherit a disabled `world`'s rank one. Got $shown")
                .that(shown.firstOrNull()).isNotEqualTo("world's")
            assertWithMessage("it is still offered (tap typing offers it too)").that(shown).contains("world's")
            assertWithMessage("and `world` is not").that(shown).doesNotContain("world")
        }
    }
}
