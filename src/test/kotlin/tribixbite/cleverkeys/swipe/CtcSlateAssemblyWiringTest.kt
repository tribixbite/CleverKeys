package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * `CtcEngineAdapter` needs an Android `Context`, assets and an ONNX session, so its wiring of
 * [tribixbite.cleverkeys.swipe.ctc.CtcSlateAssembly] is pinned by source scan; the behaviour is
 * pinned in `CtcSlateAssemblyTest` and `CtcSecondaryListReplayTest` (Note10+ r20 reports F1/F3,
 * 2026-11-26).
 */
class CtcSlateAssemblyWiringTest {

    private val adapter: String by lazy {
        val f = File("src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        assertWithMessage("run from the project root: ${f.path}").that(f.isFile).isTrue()
        f.readText()
    }

    private val lexiconFor: String by lazy {
        adapter.substringAfter("private fun lexiconFor(language: String): TrieMemo? {")
            .substringBefore("private fun langpackFileFor(")
    }

    private val decodeAsync: String by lazy {
        adapter.substringAfter("fun decodeAsync(").substringBefore("private fun postIfNewest(")
    }

    @Test
    fun theUserWordsComeFromTheSameInputsAsTheMerge() {
        assertWithMessage(
            "the slate's disabled filter and user-word placement must be built from the exact " +
                "user-word list and disabled set the merge consumed, in the same trie build"
        ).that(lexiconFor).contains("CtcUserWords.of(\n            userWordPairs, disabled, CtcLexiconMerge.baseFloor(basePairs),")
        assertWithMessage(
            "a joiner user word reaches the slate only through the overlay's preference table, so " +
                "the same table must tell the assembly which candidate it was shown for (audit C6)"
        ).that(lexiconFor).contains("preferredForms = userPreferences.mapValues { it.value.form },")
        assertWithMessage(
            "the disabled set is read per decode from the per-language preference the tap path " +
                "reads (`disabled_words_<lang>`, via UserLexiconInputs), never from a copy of its own: " +
                "the trie memo is keyed on a hash of that set, so a disable rebuilds on the next swipe"
        ).that(lexiconFor).contains("val inputs = UserLexiconInputs.read(prefs, lang)")
        assertWithMessage("and the Swiss-spelling fold applies to the filter as it does to the merge")
            .that(lexiconFor).contains("val disabled = SwissSpelling.foldDisabled(swissSpelling, inputs.disabled)")
    }

    @Test
    fun everySlateGoesThroughTheAssembly() {
        assertWithMessage("the single-language slate is filtered against the disabled words")
            .that(decodeAsync).contains("CtcSlateAssembly.single(")
        assertWithMessage("the dual-language slate is assembled (filter + user-word placement)")
            .that(decodeAsync).contains("CtcSlateAssembly.dual(")
        assertWithMessage(
            "no direct rank merge in the adapter: it would skip the disabled filter and place a " +
                "secondary-list user word below the primary's top word whatever the path says"
        ).that(decodeAsync).doesNotContain("CtcRankMerger.merge(")
    }

    @Test
    fun theBarScoresAreTheAssemblysScale() {
        assertWithMessage(
            "the display scores and the placement must share one softmax: the placement compares a " +
                "score on that scale with the primary slate's own scores"
        ).that(adapter.substringAfter("private fun toPredictionResult(").substringBefore("\n    }\n"))
            .contains("CtcSlateAssembly.softmaxPermille(")
    }
}
