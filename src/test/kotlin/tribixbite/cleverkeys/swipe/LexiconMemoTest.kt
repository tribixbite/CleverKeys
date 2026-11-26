package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tribixbite.cleverkeys.PredictionTaskRunner
import tribixbite.cleverkeys.swipe.ctc.CtcBeamDecoder
import tribixbite.cleverkeys.swipe.ctc.CtcEmissions
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams

/**
 * [LexiconMemo] — the per-language memo both swipe adapters keep (candidate 2a′ of
 * `docs/audit/2026-11-26-memory-analysis.md`).
 *
 * The heavy property: a rebuild must not run while the memo still reaches the value it replaces
 * (the old English trie, 28.25 MiB retained). The safety properties around it, under the adapters'
 * real threading (one [PredictionTaskRunner] worker, a new swipe cancels a prewarm): a decode that
 * holds the old snapshot finishes on it, a decode that cancels a rebuild gets the complete new
 * value, and an interrupted build leaves nothing behind.
 */
class LexiconMemoTest {

    private val az = "abcdefghijklmnopqrstuvwxyz".toCharArray()
    private val params = CtcScoringParams.tunedV2(beamWidth = 50, topK = 10)

    /** A memo value the way the adapters' are: a trie plus the version it was built for. */
    private class Lexicon(val version: Long, val trie: CtcLexiconTrie) {
        /** Ballast standing in for the ordinals and rescue index, so a leak is visible. */
        @Suppress("unused")
        val ballast = ByteArray(4 * 1024 * 1024)
    }

    private fun build(version: Long): Lexicon = Lexicon(version, CtcLexiconTrie(az).also {
        it.insert("got", 221.0); it.insert("git", 173.0)
        // The custom-word add that makes version 2.
        if (version >= 2) it.insert("gut", 255.0)
    })

    /** Emissions that support `got` and `git` (o/i split), `got` ahead. */
    private fun emissions(): CtcEmissions {
        val blank = az.size
        fun frame(vararg peaks: Pair<Int, Float>): FloatArray =
            FloatArray(az.size + 1) { c -> peaks.firstOrNull { it.first == c }?.second ?: -9f }
        val g = 'g' - 'a'; val i = 'i' - 'a'; val o = 'o' - 'a'; val t = 't' - 'a'
        val rows = listOf(
            frame(g to -0.05f), frame(blank to -0.1f),
            frame(o to -0.6f, i to -0.8f), frame(o to -0.6f, i to -0.8f),
            frame(blank to -0.1f), frame(t to -0.05f), frame(blank to -0.1f),
        )
        val values = FloatArray(rows.size * (az.size + 1))
        rows.forEachIndexed { k, r -> System.arraycopy(r, 0, values, k * r.size, r.size) }
        return CtcEmissions(values, rows.size, az.size + 1)
    }

    private fun decodeWords(lexicon: Lexicon): List<String> =
        CtcBeamDecoder.decode(emissions(), lexicon.trie, params).map { it.word }

    /** Stores a fresh [version] for [language] and returns only a weak handle to it. */
    private fun seed(memo: LexiconMemo<Lexicon>, language: String, version: Long): WeakReference<Lexicon> {
        val value = build(version)
        memo[language] = value
        return WeakReference(value)
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(40) {
            if (ref.get() == null) return true
            System.gc()
            @Suppress("UNUSED_VARIABLE")
            val pressure = ByteArray(1024 * 1024)
            Thread.sleep(10)
        }
        return ref.get() == null
    }

    @Test
    fun aMissReleasesTheStaleEntryBeforeTheRebuild() {
        val memo = LexiconMemo<Lexicon>(capacity = 2)
        val old = seed(memo, "en", version = 1)

        assertThat(memo.current("en") { it.version == 2L }).isNull()

        // This is where the adapter builds the replacement: nothing in the memo may reach the old
        // value any more (no decode is in flight in this test).
        assertWithMessage("the stale entry is collectable while its replacement is being built")
            .that(collected(old)).isTrue()
        assertThat(memo.languages()).doesNotContain("en")
    }

    @Test
    fun aHitServesTheEntryAndMarksItMostRecentlyUsed() {
        val memo = LexiconMemo<Lexicon>(capacity = 2)
        memo["en"] = build(1)
        memo["de"] = build(1)
        assertThat(memo.current("en") { it.version == 1L }?.version).isEqualTo(1L)
        memo["fr"] = build(1)
        assertWithMessage("the least recently used language is evicted at capacity")
            .that(memo.languages()).containsExactly("en", "fr").inOrder()
    }

    @Test
    fun aMissOnOneLanguageKeepsTheOther() {
        val memo = LexiconMemo<Lexicon>(capacity = 2)
        memo["en"] = build(1)
        memo["de"] = build(1)
        assertThat(memo.current("en") { it.version == 2L }).isNull()
        memo.remove("fr")
        assertThat(memo.languages()).containsExactly("de")
    }

    @Test
    fun anInFlightDecodeFinishesOnItsSnapshotAndOnlyThenReleasesIt() {
        val reference = decodeWords(build(1))
        val memo = LexiconMemo<Lexicon>(capacity = 2)
        val old = seed(memo, "en", version = 1)
        val runner = PredictionTaskRunner()
        try {
            val holding = CountDownLatch(1)
            val finishBeam = AtomicBoolean(false)
            val decoded = AtomicReference<List<String>>()
            val decodedVersion = AtomicReference<Long>()
            runner.cancelAndSubmit {
                val snapshot = memo.current("en") { it.version == 1L }!!
                holding.countDown()
                // The beam does not look at the interrupt flag: a decode in flight runs to its end.
                while (!finishBeam.get()) Thread.onSpinWait()
                decoded.set(decodeWords(snapshot))
                decodedVersion.set(snapshot.version)
            }
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue()

            // The next swipe, after a custom-word add: it cancels the decode in flight and queues
            // behind it on the same worker.
            val oldCollectedDuringRebuild = AtomicBoolean(false)
            val rebuilt = CountDownLatch(1)
            runner.cancelAndSubmit {
                if (memo.current("en") { it.version == 2L } == null) {
                    oldCollectedDuringRebuild.set(collected(old))
                    memo["en"] = build(2)
                }
                rebuilt.countDown()
            }
            finishBeam.set(true)
            assertThat(rebuilt.await(30, TimeUnit.SECONDS)).isTrue()

            assertWithMessage("the decode in flight decoded on the snapshot it held")
                .that(decodedVersion.get()).isEqualTo(1L)
            assertThat(decoded.get()).isEqualTo(reference)
            assertWithMessage(
                "once that decode returned, nothing kept the old value alive during the rebuild"
            ).that(oldCollectedDuringRebuild.get()).isTrue()
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun aDecodeThatCancelsARebuildBuildsTheCompleteNewValue() {
        val memo = LexiconMemo<Lexicon>(capacity = 2)
        seed(memo, "en", version = 1)
        val runner = PredictionTaskRunner()
        val completeBuilds = AtomicInteger(0)
        try {
            val prewarmBuilding = CountDownLatch(1)
            val prewarmInterrupted = AtomicBoolean(false)
            val languagesDuringPrewarmBuild = AtomicReference<List<String>>()
            // A prewarm notices the new version and starts the rebuild; the user swipes mid-build.
            runner.submitBackground {
                if (memo.current("en") { it.version == 2L } == null) {
                    languagesDuringPrewarmBuild.set(memo.languages())
                    try {
                        prewarmBuilding.countDown()
                        Thread.sleep(30_000) // a long trie build that checks for interruption
                        memo["en"] = build(2)
                        completeBuilds.incrementAndGet()
                    } catch (e: InterruptedException) {
                        prewarmInterrupted.set(true)
                    }
                }
            }
            assertThat(prewarmBuilding.await(5, TimeUnit.SECONDS)).isTrue()

            val words = AtomicReference<List<String>>()
            val version = AtomicReference<Long>()
            val containsNewWord = AtomicBoolean(false)
            val done = CountDownLatch(1)
            runner.cancelAndSubmit {
                val lexicon = memo.current("en") { it.version == 2L }
                    ?: build(2).also { memo["en"] = it; completeBuilds.incrementAndGet() }
                version.set(lexicon.version)
                containsNewWord.set(lexicon.trie.contains("gut"))
                words.set(decodeWords(lexicon))
                done.countDown()
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()

            assertWithMessage("the slot was already empty while the prewarm was building")
                .that(languagesDuringPrewarmBuild.get()).doesNotContain("en")
            assertThat(prewarmInterrupted.get()).isTrue()
            assertWithMessage("the decode got the new version, built completely, once")
                .that(version.get()).isEqualTo(2L)
            assertThat(containsNewWord.get()).isTrue()
            assertThat(completeBuilds.get()).isEqualTo(1)
            assertThat(words.get()).isEqualTo(decodeWords(build(2)))
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun aBuildThatThrowsLeavesTheSlotEmptyNotStale() {
        val memo = LexiconMemo<Lexicon>(capacity = 2)
        memo["en"] = build(1)
        memo["de"] = build(1)
        val thrown = runCatching {
            if (memo.current("en") { it.version == 2L } == null) {
                throw InterruptedException("Lexicon load cancelled")
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(InterruptedException::class.java)
        assertWithMessage("an interrupted rebuild leaves no partial and no stale entry; de is kept")
            .that(memo.languages()).containsExactly("de")
    }

    /** The contract above is the shipped one only while both adapters key their memo through it. */
    @Test
    fun bothSwipeAdaptersKeepTheirLexiconInThisMemo() {
        val dir = File("src/main/kotlin/tribixbite/cleverkeys/swipe")
        val ctc = File(dir, "CtcEngineAdapter.kt").readText()
        val geometric = File(dir, "GeometricEngineAdapter.kt").readText()
        assertThat(ctc).contains("private val trieMemos = LexiconMemo<TrieMemo>(capacity = 2)")
        assertThat(geometric).contains("private val dictionaryMemos = LexiconMemo<DictMemo>(capacity = 2)")
        assertThat(ctc).contains("trieMemos.current(lang) {")
        assertThat(geometric).contains("dictionaryMemos.current(lang) {")
        val enBranch = ctc.substringAfter("CtcLanguageSupport.LexiconSource.EN_JSON -> {")
            .substringBefore("CtcLanguageSupport.LexiconSource.CKDT_BIN ->")
        assertWithMessage("the en lexicon is streamed, not read whole (candidate 2a)")
            .that(enBranch).contains("CtcEnLexiconJson.readPairs(")
        assertThat(enBranch).doesNotContain("readBytes")
    }
}
