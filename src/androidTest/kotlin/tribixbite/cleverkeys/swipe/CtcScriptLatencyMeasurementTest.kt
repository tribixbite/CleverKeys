package tribixbite.cleverkeys.swipe

import android.content.Context
import android.graphics.PointF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.TestConfigHelper
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * B2 — the first per-script CTC latency MEASUREMENT (`memory/language-support-todo.md` §B,
 * round-2 closure 2026-09-03). Until this class existed, every published CTC latency number
 * was Latin-only: `CtcLatencyGateTest` is deliberately hard-coded to en/fr/de, so ru/el/uk/
 * bg/mk/he shipped with no latency figure at all.
 *
 * ## Measurement, not gate
 * The per-language numbers this test logs are the FIRST measurements for these scripts, so
 * there is no calibrated budget to assert against — inventing one would gate on a guess.
 * The only assertion on time is a generous sanity ceiling ([SANITY_MEDIAN_MS]) that catches
 * a pathological regression (an accidental cold path, a lexicon rebuilt per decode) without
 * pretending to be a tuned bar. Publish the logged medians; tune a real budget only after a
 * few runs establish variance. The en gate (`CtcLatencyGateTest`, median budget 150 ms)
 * remains the tuned bar for the Latin path.
 *
 * ## What is measured
 * Wall time of the full production decode path — featurize → ONNX encoder → trie beam →
 * display overlay — via `CtcEngineAdapter.decodeAsync` against the REAL shipped langpack
 * (staged into the test APK by `copyScriptLatencyPacks`, byte-identical to
 * `scripts/dictionaries/langpack-<code>.zip`), on the script's own bundled layout, tracing
 * a genuinely frequent word of that language. Includes two main-thread handler hops and the
 * latch — sub-millisecond noise against a tens-of-milliseconds decode, and exactly the hops
 * the production dispatcher pays too.
 *
 * ## Why the trace words are folded surfaces
 * The trie stores each script's POST-FOLD surface (el "άγνωστο" → path "αγνωστο"), and the
 * board's centre keys are the wiring alphabet, so the synthetic trace must visit the folded
 * letters. Each word in [TRACE_WORDS] was picked host-side from the real pack: rank-sorted
 * (most frequent first), 6–8 letters after folding, no repeated adjacent letter, every
 * folded letter in the wiring alphabet. The map is asserted to cover every ROUTED script so
 * wiring a new script extends this measurement deliberately rather than silently skipping.
 */
@RunWith(AndroidJUnit4::class)
class CtcScriptLatencyMeasurementTest {

    private companion object {
        const val TAG = "CtcScriptLatency"

        const val FRAME_W = 1080f
        const val FRAME_H = 640f
        const val WARMUPS = 3
        const val ITERATIONS = 15
        const val SANITY_MEDIAN_MS = 1000.0

        /** Straight-line trace shape, matching the golden fixture generator. */
        const val TRACE_STEPS_PER_SEGMENT = 12
        const val TRACE_STEP_MS = 16L

        /**
         * Per-script folded trace surfaces (see class KDoc for the selection recipe;
         * canonical forms in comments where folding changes the spelling).
         */
        val TRACE_WORDS = mapOf(
            "ru" to "авангард",
            "el" to "αγνωστο", // άγνωστο
            "uk" to "абонент",
            "bg" to "август",
            "mk" to "авантура",
            "he" to "אבודים",
        )

        /**
         * A real 50k-class pack projects to well above this; a synthesized or truncated
         * fixture pack (the [CtcImportedPackInstrumentedTest] kind) sits far below. Keeps
         * the measurement honest: the numbers are meaningless against a toy trie.
         */
        const val MIN_TRIE_WORDS = 25_000
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Config must initialize — the adapter reads ctc_beam_width from it",
            TestConfigHelper.ensureConfigInitialized(context)
        )
    }

    @After
    fun tearDown() {
        // Leave the device as found: these packs are test fixtures here, not user installs.
        val manager = LanguagePackManager.getInstance(context)
        for (language in TRACE_WORDS.keys) {
            manager.deletePack(language)
            CtcInstalledPacks.invalidate(context, language)
            File(context.cacheDir, "langpack-$language.zip").delete()
        }
    }

    /** Stage the real pack out of the TEST APK's assets and run the shipping importer. */
    private fun importRealPack(language: String) {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val zip = File(context.cacheDir, "langpack-$language.zip")
        testAssets.open("langpacks/langpack-$language.zip").use { input ->
            FileOutputStream(zip).use { input.copyTo(it) }
        }
        val result = LanguagePackManager.getInstance(context).importLanguagePack(Uri.fromFile(zip))
        assertTrue(
            "$language: importing the real langpack must succeed, got $result",
            result is tribixbite.cleverkeys.langpack.ImportResult.Success
        )
        CtcInstalledPacks.invalidate(context, language)
    }

    private fun loadLayout(name: String): KeyboardData {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        assertTrue("layout resource missing: $name", id != 0)
        val kd = KeyboardData.load(context.resources, id)
        assertNotNull("layout $name must parse", kd)
        return kd!!
    }

    private fun paramsFor(kd: KeyboardData) = KeyboardGeometry.Params(
        keyWidth = FRAME_W / kd.keysWidth,
        rowHeight = FRAME_H / kd.keysHeight,
        marginTop = 0f,
        marginLeft = 0f,
    )

    private fun letterCenter(kd: KeyboardData, params: KeyboardGeometry.Params, c: Char): PointF {
        val rect = KeyboardGeometry.computeKeyRects(kd, params).firstOrNull { r ->
            r.kv.getKind() == KeyValue.Kind.Char && r.kv.getChar().lowercaseChar() == c
        } ?: throw AssertionError("no centre key for '$c'")
        return PointF(
            (rect.bounds.left + rect.bounds.right) / 2f,
            (rect.bounds.top + rect.bounds.bottom) / 2f,
        )
    }

    private fun traceFor(
        kd: KeyboardData,
        params: KeyboardGeometry.Params,
        word: String,
    ): Pair<List<PointF>, List<Long>> {
        val centers = word.map { letterCenter(kd, params, it) }
        val points = ArrayList<PointF>()
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until centers.size - 1) {
            val a = centers[i]
            val b = centers[i + 1]
            for (s in 0 until TRACE_STEPS_PER_SEGMENT) {
                val f = s / TRACE_STEPS_PER_SEGMENT.toFloat()
                points.add(PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
                times.add(t)
                t += TRACE_STEP_MS
            }
        }
        points.add(centers.last())
        times.add(t)
        return points to times
    }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var thrown: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try { block() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
        }
        assertTrue("main-thread block must run", latch.await(10, TimeUnit.SECONDS))
        thrown?.let { throw it }
    }

    private fun decodeBlocking(
        adapter: CtcEngineAdapter,
        kd: KeyboardData,
        params: KeyboardGeometry.Params,
        path: List<PointF>,
        times: List<Long>,
        language: String,
    ): PredictionResult {
        var result: PredictionResult? = null
        var failed = false
        val latch = CountDownLatch(1)
        onMain {
            adapter.decodeAsync(
                keyboard = kd,
                params = params,
                frameWidthPx = FRAME_W,
                frameHeightPx = FRAME_H,
                swipePath = path,
                timestamps = times,
                language = language,
                secondaryLanguage = null,
                onDecodeFailure = { failed = true; latch.countDown() },
            ) {
                result = it
                latch.countDown()
            }
        }
        assertTrue("$language: decode must complete", latch.await(60, TimeUnit.SECONDS))
        assertTrue("$language: decode FAILED (routed to fallback) — see logcat", !failed)
        return result!!
    }

    @Test
    fun everyRoutedScript_decodesItsRealPack_atMeasuredLatency() {
        val routed = CtcScriptSupport.SCRIPTS.filterValues {
            it.status == CtcScriptSupport.Status.ROUTED
        }
        assertTrue("no ROUTED script — the measurement is vacuous", routed.isNotEmpty())
        assertEquals(
            "TRACE_WORDS must cover exactly the ROUTED scripts — wiring a new script " +
                "extends this measurement deliberately (pick a word per the class KDoc), " +
                "and an unwired script must not leave a stale entry behind",
            routed.keys.sorted(), TRACE_WORDS.keys.sorted()
        )

        val adapter = CtcEngineAdapter(context)
        val report = StringBuilder("per-script CTC decode latency (ms):\n")
        try {
            for ((language, wiring) in routed) {
                importRealPack(language)

                val trie = adapter.trieFor(language)
                assertNotNull("$language: no trie from the imported real pack", trie)
                assertTrue(
                    "$language: trie has only ${trie!!.wordCount} words — this is not the " +
                        "real pack (bound $MIN_TRIE_WORDS)",
                    trie.wordCount > MIN_TRIE_WORDS
                )
                val surface = TRACE_WORDS.getValue(language)
                assertTrue(
                    "$language: '$surface' must be a trie path (else the beam walks a miss " +
                        "and the timing measures the wrong shape)",
                    trie.contains(surface)
                )

                val kd = loadLayout(wiring.layoutXml.removeSuffix(".xml"))
                val params = paramsFor(kd)
                assertTrue(
                    "$language: its own board must be CTC-eligible",
                    adapter.supportsLayout(kd, params, FRAME_W, FRAME_H, language)
                )
                val (path, times) = traceFor(kd, params, surface)

                repeat(WARMUPS) { decodeBlocking(adapter, kd, params, path, times, language) }
                val samples = DoubleArray(ITERATIONS) {
                    val start = System.nanoTime()
                    val result = decodeBlocking(adapter, kd, params, path, times, language)
                    val ms = (System.nanoTime() - start) / 1e6
                    assertTrue(
                        "$language: warm decode returned an empty slate — the timing would " +
                            "be measuring a no-op",
                        result.words.isNotEmpty()
                    )
                    ms
                }
                samples.sort()
                val median = samples[ITERATIONS / 2]
                val p90 = samples[13] // nearest-rank p90 of 15
                report.append(
                    "  $language ('$surface', ${trie.wordCount} words): " +
                        "median=%.1f p90=%.1f min=%.1f max=%.1f\n"
                            .format(median, p90, samples.first(), samples.last())
                )
                Log.i(
                    TAG,
                    ("$language: median=%.1fms p90=%.1fms min=%.1f max=%.1f (n=$ITERATIONS, " +
                        "warm, ${trie.wordCount}-word trie)")
                            .format(median, p90, samples.first(), samples.last())
                )
                assertTrue(
                    ("$language: median %.1f ms breaches the %.0f ms sanity ceiling — this is " +
                        "not a tuned budget failing, it is a pathological regression " +
                        "(cold path per decode, per-decode lexicon rebuild, or similar)")
                            .format(median, SANITY_MEDIAN_MS),
                    median < SANITY_MEDIAN_MS
                )

                // Bounded caches: drop this language's pack before the next to keep each
                // measurement independent of LRU eviction order.
                LanguagePackManager.getInstance(context).deletePack(language)
                CtcInstalledPacks.invalidate(context, language)
            }
            Log.i(TAG, report.toString())
        } finally {
            adapter.shutdown()
        }
    }
}
