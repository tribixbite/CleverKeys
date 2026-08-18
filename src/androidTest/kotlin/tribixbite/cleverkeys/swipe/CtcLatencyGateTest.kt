package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.onnx.ModelLoader
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder

/**
 * The G3 latency GATE (integration plan §3, new test 2) — unlike the loose-bound
 * [tribixbite.cleverkeys.swipe.ctc.CtcOnnxLatencyBenchmarkTest] measurement harness,
 * this test FAILS when the production CTC decode path regresses past its budget.
 *
 * Production configuration end to end:
 *  - encoder loaded through [ModelLoader] with hardware acceleration ON (XNNPACK
 *    chain at `Defaults.ONNX_XNNPACK_THREADS`) — exactly what [CtcEngineAdapter]
 *    does at runtime;
 *  - lexicon trie built from the real bundled `dictionaries/en_enhanced.json`
 *    through [CtcEngineAdapter.trieFor] — the SHIPPING merge path (custom/disabled
 *    words + content-hash memo), not a test replica;
 *  - decode of the golden fixture's `model_keyboard` trace (the longest, most
 *    beam-expensive case) at `presetFor("en", beamWidth = 100, topK = 8)` — the
 *    adapter's production preset and slate size for the largest bundled lexicon
 *    (en 98k words; fr/de are 40k and es 50k, so en is the worst case).
 *
 * Budget: G3's bar was "≤ the then-current transformer's ~100–300 ms". Expected actuals are
 * ~1 ms encoder + a trie beam in the tens of ms on an emulator core, so
 * median < 150 ms / p90 < 250 ms has wide margin yet still catches a pathological
 * regression (accidental beam-300 default, trie rebuild per swipe, softmax in the
 * hot loop). Note this runs on an x86_64 cloud emulator — a proxy, not a phone
 * little core (plan §3's caveat).
 */
@RunWith(AndroidJUnit4::class)
class CtcLatencyGateTest {

    private companion object {
        const val TAG = "CtcLatencyGate"
        const val FIXTURE = "ctc/ctc_golden.json"

        const val WARMUPS = 5
        const val ITERATIONS = 30

        /** The gate (plan §3): production decode must sit well inside that budget. */
        const val MEDIAN_BUDGET_MS = 150.0
        const val P90_BUDGET_MS = 250.0

        /** Production preset at the validated width and the adapter's slate size. */
        const val BEAM_WIDTH = 100
        const val TOP_K = 8

        /**
         * The gate measures the WORST-CASE lexicon: en's 98k-word trie (the largest
         * bundled CTC lexicon — fr/de are 40k, es 50k), decoded at the en preset.
         */
        const val GATE_LANGUAGE = "en"
    }

    private class FixtureCase(
        val layout: CtcLayout,
        val px: DoubleArray,
        val py: DoubleArray,
        val pt: DoubleArray,
    )

    /** The golden fixture's canonical layout + the `model_keyboard` beam trace. */
    private fun loadFixtureCase(): FixtureCase {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val golden = JSONObject(
            testCtx.assets.open(FIXTURE).readBytes().decodeToString()
        )
        val lay = golden.getJSONObject("layout")
        val letters = lay.getString("letters").toList()
        val cx = lay.getJSONArray("cx")
        val cy = lay.getJSONArray("cy")
        val layout = CtcLayout.of(
            letters,
            List(cx.length()) { cx.getDouble(it).toFloat() },
            List(cy.length()) { cy.getDouble(it).toFloat() },
        )
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("kind") != "beam" || c.getString("name") != "model_keyboard") continue
            val pts = c.getJSONObject("points")
            val x = pts.getJSONArray("x"); val y = pts.getJSONArray("y")
            val t = pts.getJSONArray("t")
            return FixtureCase(
                layout,
                DoubleArray(x.length()) { x.getDouble(it) },
                DoubleArray(y.length()) { y.getDouble(it) },
                DoubleArray(t.length()) { t.getDouble(it) },
            )
        }
        throw AssertionError("fixture has no model_keyboard beam case")
    }

    @Test
    fun productionDecodePath_meetsLatencyBudget_andReusesMemos() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val case = loadFixtureCase()
        val env = OrtEnvironment.getEnvironment()

        // ── Cold pass: everything the FIRST real swipe pays without a prewarm —
        // trie build (adapter merge path), session load (production hw-accel ON),
        // and one full decode. Timed as the memo-reuse baseline.
        val adapter = CtcEngineAdapter(target)
        val coldStart = System.nanoTime()
        val trieOrNull = adapter.trieFor(GATE_LANGUAGE)
        assertNotNull("adapter merge path produced no trie", trieOrNull)
        val trie = trieOrNull!!
        assertTrue("bundled trie looks empty (${trie.wordCount} words)", trie.wordCount > 50_000)

        val loaded = ModelLoader(target, env).loadModel(
            CtcEngineAdapter.MODEL_ASSET, "CtcEncoderGate",
            enableHardwareAcceleration = true,
            xnnpackThreads = Defaults.ONNX_XNNPACK_THREADS
        )
        val model = OnnxCtcEmissionModel(env, loaded.session)
        val decoder = CtcSwipeDecoder(
            model, case.layout, trie,
            CtcScoringParams.presetFor(GATE_LANGUAGE, beamWidth = BEAM_WIDTH, topK = TOP_K)
        )
        val coldCandidates = decoder.decode(case.px, case.py, case.pt)
        val coldMs = (System.nanoTime() - coldStart) / 1e6
        assertTrue("cold decode returned no candidates", coldCandidates.isNotEmpty())

        // ── Second decode: same trace, memoized trie re-fetched through the adapter.
        // Must be cheaper than the cold pass (trie build + session load skipped) —
        // this pins the warm path (a per-swipe trie rebuild would break it).
        val secondStart = System.nanoTime()
        val trie2 = adapter.trieFor(GATE_LANGUAGE)
        assertTrue("trieFor did not memoize (rebuilt a different trie)", trie2 === trie)
        val secondCandidates = decoder.decode(case.px, case.py, case.pt)
        val secondMs = (System.nanoTime() - secondStart) / 1e6
        assertTrue("second decode returned no candidates", secondCandidates.isNotEmpty())
        assertTrue(
            "second decode (${secondMs} ms) not cheaper than cold pass (${coldMs} ms) — " +
                "memo reuse broken",
            secondMs < coldMs
        )

        // ── The gate: 5 warmups then 30 timed production decodes.
        repeat(WARMUPS) { decoder.decode(case.px, case.py, case.pt) }
        val samples = LongArray(ITERATIONS)
        var last = emptyList<tribixbite.cleverkeys.swipe.ctc.CtcCandidate>()
        for (i in 0 until ITERATIONS) {
            val t0 = System.nanoTime()
            last = decoder.decode(case.px, case.py, case.pt)
            samples[i] = System.nanoTime() - t0
        }
        assertTrue("gate decode returned no candidates", last.isNotEmpty())
        assertTrue("gate decode returned an empty word", last.all { it.word.isNotEmpty() })

        samples.sort()
        val medianMs = samples[ITERATIONS / 2] / 1e6
        val p90Ms = samples[(ITERATIONS * 9) / 10 - 1] / 1e6
        Log.i(TAG, "GATE [${loaded.executionProvider}] beam=$BEAM_WIDTH topK=$TOP_K " +
            "words=${trie.wordCount} cold=${"%.1f".format(coldMs)}ms " +
            "second=${"%.1f".format(secondMs)}ms median=${"%.1f".format(medianMs)}ms " +
            "p90=${"%.1f".format(p90Ms)}ms " +
            "top=" + last.take(3).joinToString { it.word })

        assertTrue(
            "CTC decode median ${medianMs} ms exceeds the $MEDIAN_BUDGET_MS ms gate",
            medianMs < MEDIAN_BUDGET_MS
        )
        assertTrue(
            "CTC decode p90 ${p90Ms} ms exceeds the $P90_BUDGET_MS ms gate",
            p90Ms < P90_BUDGET_MS
        )

        adapter.shutdown()
        model.close()
    }
}
