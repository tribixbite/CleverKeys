package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * REPORT ONLY, not a gate: the live heap a full English CTC rebuild (one custom-word add) leaves
 * reachable at its end, on the pure JVM, for the two 2.0-safe parts of candidate 2 in
 * `docs/audit/2026-11-26-memory-analysis.md`.
 *
 * Builds what `CtcEngineAdapter.lexiconFor`'s EN_JSON branch builds (merge, ordinals, strip-loader
 * trie, fuzzy rescue; no contraction injection, no user words) and measures used heap after GC:
 *  - the old read's intermediates (file bytes + decoded text + `JSONObject`) at their peak;
 *  - one lexicon's retained size;
 *  - a rebuild with the old lexicon still reachable (the old memo) vs released before the build.
 *
 * JVM object sizes are not ART's (and the JVM's `org.json` map is a HashMap, Android's a
 * LinkedHashMap), so the numbers rank the parts; the device figures are the audit's.
 */
class CtcLexiconRebuildPeakReportTest {

    private val asset = File("src/main/assets/dictionaries/en_enhanced.json")
    private val az = "abcdefghijklmnopqrstuvwxyz".toCharArray()

    private class Lexicon(
        val trie: CtcLexiconTrie,
        val ordinals: HashMap<String, Int>,
        val rescue: CtcFuzzyRescue,
    )

    private fun build(): Lexicon {
        val pairs = asset.inputStream().use { CtcEnLexiconJson.readPairs(it) }
        val merged = CtcLexiconMerge.merge(pairs, emptyList(), emptySet())
        val ordinals = CtcLexiconMerge.ordinals(merged)
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(az, merged)
        return Lexicon(trie, ordinals, CtcFuzzyRescue.fromFrequencies(merged, az.toHashSet()))
    }

    private fun usedAfterGc(): Long {
        val rt = Runtime.getRuntime()
        var best = Long.MAX_VALUE
        repeat(4) {
            System.gc()
            Thread.sleep(20)
            best = minOf(best, rt.totalMemory() - rt.freeMemory())
        }
        return best
    }

    private fun mib(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

    @Test
    fun reportTheRebuildPeak() {
        val base = usedAfterGc()

        val bytes = asset.readBytes()
        val text = bytes.decodeToString()
        val obj = JSONObject(text)
        val oldReadIntermediates = usedAfterGc() - base
        assertThat(obj.length()).isGreaterThan(0)
        @Suppress("UNUSED_VALUE") var hold: Any? = listOf(bytes, text, obj)
        hold = null

        var old: Lexicon? = build()
        val oneLexicon = usedAfterGc() - base
        // The old memo: still reachable while the replacement is built.
        val heldNew = build()
        val bothHeld = usedAfterGc() - base
        assertThat(old!!.trie.wordCount).isEqualTo(heldNew.trie.wordCount)
        @Suppress("UNUSED_VALUE")
        old = null
        // What the end of a rebuild holds once the memo released the old lexicon first.
        val afterRelease = usedAfterGc() - base

        println(
            "CTC_REBUILD_PEAK_REPORT (pure JVM, MiB after GC) " +
                "oldReadIntermediates=${mib(oldReadIntermediates)} " +
                "oneEnLexicon=${mib(oneLexicon)} " +
                "rebuildWithOldHeld=${mib(bothHeld)} " +
                "rebuildAfterRelease=${mib(afterRelease)} " +
                "words=${heldNew.trie.wordCount} nodes=${heldNew.trie.nodeCount}"
        )
    }
}
