package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.onnx.ModelLoader
import tribixbite.cleverkeys.swipe.ctc.CtcBeamDecoder
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcRankMerger
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

        /**
         * Ceiling on the COLD path — trie build + ONNX session load + first decode, i.e.
         * everything the first un-prewarmed swipe of a session pays (audit ARC-023: the
         * cold build was measured but left unbudgeted, so only the memo-reuse comparison
         * `secondMs < coldMs` guarded it — which a 10× slower cold path still satisfies).
         *
         * Provenance: `CtcLexiconTrie` build from `en_enhanced.json` was measured at
         * **2,001 ms ON-DEVICE** (vs 90 ms desktop — the 22× gap is `org.json` parsing the
         * 1.8 MB file plus `loadStrippingNonAlphabet`; see the CTC integration execution
         * brief, "Trie build cost"). That build dominates this number; the session load and
         * the single decode are tens of ms.
         *
         * The budget is that on-device measurement with ~2× headroom. It is deliberately
         * loose: this test runs on an x86_64 cloud emulator, which sits somewhere between
         * the 90 ms desktop and the 2,001 ms phone and has been characterised at neither, so
         * a tight bound would be a flake generator. What it catches is an ORDER-OF-MAGNITUDE
         * regression — a quadratic insert, a per-swipe re-parse, a lost memo — which is
         * exactly the class of bug the old `secondMs < coldMs` check could not see.
         *
         * The test logs the ACTUAL cold time on every run (see `TAG`); once a few emulator
         * numbers exist, tighten this toward that observed value.
         *
         * If this ever trips, the real fix is a PRECOMPILED trie blob shipped as an asset
         * (the web demo's front-coded format from `tools/build_ctc_vocab.py` is the
         * precedent), not a larger constant: 2 s of first-swipe latency is already the worst
         * number on the CTC path and is only tolerable because the prewarm usually hides it.
         */
        const val COLD_BUDGET_MS = 4500.0

        /**
         * CK-150-026 §4.9 asked for the dual-language decode to hold "the same budget" as the
         * single-language case, named at the p95. Deliberately the SAME number as
         * [P90_BUDGET_MS] rather than a relaxed one: a swipe that mixes two lexicons is still
         * one swipe to the user, and the whole point of the finding is that nobody had measured
         * whether two beam searches + two bounded rescue scans stay inside the single-language
         * bar. If this fails, the remedy in §4.9 is a measured capacity/beam decision — not a
         * bigger number here.
         */
        const val DUAL_P95_BUDGET_MS = 250.0

        /** Production preset at the validated width and the adapter's slate size. */
        const val BEAM_WIDTH = 100
        const val TOP_K = 8

        /**
         * The gate measures the WORST-CASE lexicon: en's 98k-word trie (the largest
         * bundled CTC lexicon — fr/de are 40k, es 50k), decoded at the en preset.
         */
        const val GATE_LANGUAGE = "en"

        /**
         * The secondary language of the dual-language gate. fr is the realistic pairing (the
         * only other language with real swipe corpus rows) and, at 40k words, keeps the second
         * trie's ~19 MB inside the 2-slot `trieMemos` LRU alongside en.
         */
        const val SECONDARY_LANGUAGE = "fr"

        /** A third CTC language, used only to prove the en→fr→en cycle does NOT evict. */
        const val THIRD_LANGUAGE = "de"
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
        Log.i(TAG, "cold path (trie build + session load + first decode) = ${"%.0f".format(coldMs)} ms")
        assertTrue(
            "cold path (${"%.0f".format(coldMs)} ms) exceeded the first-swipe budget " +
                "($COLD_BUDGET_MS ms). This is the un-prewarmed first swipe: the en trie " +
                "build dominates it. If this trips, the fix is a PRECOMPILED trie blob " +
                "(ship the built trie as an asset instead of parsing + inserting 98k words " +
                "at runtime) — not a bigger number here.",
            coldMs < COLD_BUDGET_MS
        )

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

    /**
     * CK-150-026 (a) — the DUAL-LANGUAGE decode must hold the single-language budget.
     *
     * With `enable_multilang` on, `CtcEngineAdapter.decodeAsync` runs the encoder ONCE and then
     * decodes each active language against its own trie and preset, bounded-rescues each greedy
     * surface, and merges the two slates by rank. That is 2 beam searches + 2 rescue scans per
     * swipe against 1 + 1 — and until this test nobody had measured it. This mirrors that exact
     * shape (one `emit`, two `CtcBeamDecoder.decode`, two `CtcFuzzyRescue.find`, one
     * `CtcRankMerger.merge`) over the SHIPPING tries from [CtcEngineAdapter.trieFor], so the
     * number it produces is the number a real en+fr swipe pays.
     *
     * The display overlays (`applyCanonicalDisplay` / `applyContractionDisplay`) are the one
     * production step not replayed here: they are `Map` lookups over an ≤8-word slate and their
     * seam is private. Everything the finding named as expensive is in.
     */
    @Test
    fun dualLanguageDecodePath_meetsTheSameLatencyBudget() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val case = loadFixtureCase()
        val env = OrtEnvironment.getEnvironment()

        val adapter = CtcEngineAdapter(target)
        val primaryTrie = adapter.trieFor(GATE_LANGUAGE)
        val secondaryTrie = adapter.trieFor(SECONDARY_LANGUAGE)
        assertNotNull("adapter merge path produced no '$GATE_LANGUAGE' trie", primaryTrie)
        assertNotNull("adapter merge path produced no '$SECONDARY_LANGUAGE' trie", secondaryTrie)
        val primaryRescue = adapter.fuzzyRescueFor(GATE_LANGUAGE)
        val secondaryRescue = adapter.fuzzyRescueFor(SECONDARY_LANGUAGE)
        assertNotNull("no '$GATE_LANGUAGE' rescue index", primaryRescue)
        assertNotNull("no '$SECONDARY_LANGUAGE' rescue index", secondaryRescue)

        val loaded = ModelLoader(target, env).loadModel(
            CtcEngineAdapter.MODEL_ASSET, "CtcEncoderDualGate",
            enableHardwareAcceleration = true,
            xnnpackThreads = Defaults.ONNX_XNNPACK_THREADS
        )
        val model = OnnxCtcEmissionModel(env, loaded.session)
        val padded = CtcFeaturizer.buildPaddedLayout(case.layout)
        val primaryParams =
            CtcScoringParams.presetFor(GATE_LANGUAGE, beamWidth = BEAM_WIDTH, topK = TOP_K)
        val secondaryParams =
            CtcScoringParams.presetFor(SECONDARY_LANGUAGE, beamWidth = BEAM_WIDTH, topK = TOP_K)

        /** One dual-language swipe, in `decodeAsync`'s order. Returns the merged slate. */
        fun decodeDual(): List<CtcRankMerger.Item> {
            val features = CtcFeaturizer.featurize(case.px, case.py, case.pt)
            val emissions = model.emit(features, padded)
            val greedy = CtcBeamDecoder.greedy(emissions, case.layout.alphabet)
            val primaryWords = CtcBeamDecoder.decode(emissions, primaryTrie!!, primaryParams)
                .map { it.word }
            val secondaryWords = CtcBeamDecoder.decode(emissions, secondaryTrie!!, secondaryParams)
                .map { it.word }
            primaryRescue!!.find(greedy, primaryWords.toHashSet())
            secondaryRescue!!.find(greedy, secondaryWords.toHashSet())
            return CtcRankMerger.merge(
                GATE_LANGUAGE, primaryWords, SECONDARY_LANGUAGE, secondaryWords, TOP_K
            )
        }

        val warmSlate = decodeDual()
        assertTrue("dual decode returned no candidates", warmSlate.isNotEmpty())
        assertTrue("dual decode returned an empty word", warmSlate.all { it.word.isNotEmpty() })
        // CK-150-024's precondition: the merged slate is genuinely per-word labelled, which is
        // what `PredictionResult.languages` carries to the possessive gate.
        assertTrue(
            "merged slate lost its language labels",
            warmSlate.all { it.language == GATE_LANGUAGE || it.language == SECONDARY_LANGUAGE }
        )

        repeat(WARMUPS) { decodeDual() }
        val samples = LongArray(ITERATIONS)
        for (i in 0 until ITERATIONS) {
            val t0 = System.nanoTime()
            decodeDual()
            samples[i] = System.nanoTime() - t0
        }

        samples.sort()
        val medianMs = samples[ITERATIONS / 2] / 1e6
        val p90Ms = samples[(ITERATIONS * 9) / 10 - 1] / 1e6
        val p95Ms = samples[(ITERATIONS * 95) / 100 - 1] / 1e6
        Log.i(TAG, "DUAL GATE [${loaded.executionProvider}] $GATE_LANGUAGE+$SECONDARY_LANGUAGE " +
            "beam=$BEAM_WIDTH topK=$TOP_K words=${primaryTrie!!.wordCount}+" +
            "${secondaryTrie!!.wordCount} median=${"%.1f".format(medianMs)}ms " +
            "p90=${"%.1f".format(p90Ms)}ms p95=${"%.1f".format(p95Ms)}ms " +
            "top=" + warmSlate.take(3).joinToString { "${it.word}/${it.language}" })

        assertTrue(
            "dual-language decode median ${medianMs} ms exceeds the $MEDIAN_BUDGET_MS ms gate",
            medianMs < MEDIAN_BUDGET_MS
        )
        assertTrue(
            "dual-language decode p90 ${p90Ms} ms exceeds the $P90_BUDGET_MS ms gate",
            p90Ms < P90_BUDGET_MS
        )
        assertTrue(
            "dual-language decode p95 ${p95Ms} ms exceeds the $DUAL_P95_BUDGET_MS ms gate " +
                "(CK-150-026: two beams + two rescue scans must still fit one swipe)",
            p95Ms < DUAL_P95_BUDGET_MS
        )

        adapter.shutdown()
        model.close()
    }

    /**
     * CK-150-026 (b) — an en→fr→en language-switch cycle must NOT rebuild a memo.
     *
     * `trieMemos` evicts at `size > 2`, so exactly two active languages fit and the round trip
     * back to en has to be a HIT. The reuse signal is the same one
     * [productionDecodePath_meetsLatencyBudget_andReusesMemos] uses — instance identity of the
     * trie returned by the shipping merge path — because a rebuild produces a different object
     * (and costs a fresh ~19 MB plus the build time this test also records).
     *
     * The third-language leg proves the boundary is REAL rather than accidentally generous: with
     * access-ordering, the de build evicts fr (the least-recently-used of the two), en survives.
     * That is the thrash the finding warns about, pinned as behaviour so raising the capacity is
     * a conscious change with this test to update.
     */
    @Test
    fun languageSwitchCycle_reusesMemosWithinTheTwoSlotLru() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = CtcEngineAdapter(target)

        val enBuildStart = System.nanoTime()
        val en = adapter.trieFor(GATE_LANGUAGE)
        val enBuildMs = (System.nanoTime() - enBuildStart) / 1e6
        assertNotNull("adapter merge path produced no '$GATE_LANGUAGE' trie", en)

        val fr = adapter.trieFor(SECONDARY_LANGUAGE)
        assertNotNull("adapter merge path produced no '$SECONDARY_LANGUAGE' trie", fr)
        assertNotSame("two languages must not share one trie", en, fr)

        // ── The switch back. Two languages fit, so this is a memo HIT: same instance, and
        // orders of magnitude cheaper than the build it would otherwise repeat.
        val enRefetchStart = System.nanoTime()
        val enAgain = adapter.trieFor(GATE_LANGUAGE)
        val enRefetchMs = (System.nanoTime() - enRefetchStart) / 1e6
        assertSame(
            "en→fr→en rebuilt the '$GATE_LANGUAGE' trie — the 2-slot LRU must hold both " +
                "active languages (CK-150-026)",
            en, enAgain
        )
        assertTrue(
            "the '$GATE_LANGUAGE' re-fetch (${enRefetchMs} ms) was not cheaper than its build " +
                "(${enBuildMs} ms) — memo reuse broken",
            enRefetchMs < enBuildMs
        )
        // fr is still resident too: the cycle touched nothing else.
        assertSame(
            "'$SECONDARY_LANGUAGE' was evicted by a 2-language cycle",
            fr, adapter.trieFor(SECONDARY_LANGUAGE)
        )

        // ── The documented capacity limit, so raising it is a conscious change with a test to
        // update. `trieMemos` is access-ordered, and the fetches above leave [en, fr] with fr
        // most-recent, so the de build evicts en — the ~19 MB the finding says gets rebuilt on
        // any cycle involving a third language.
        val de = adapter.trieFor(THIRD_LANGUAGE)
        assertNotNull("adapter merge path produced no '$THIRD_LANGUAGE' trie", de)
        val enAfterThird = adapter.trieFor(GATE_LANGUAGE)
        assertNotSame(
            "a third CTC language must evict the least-recently-used trie (capacity 2). If " +
                "this now passes trivially the LRU threshold changed — re-measure the memory " +
                "note on CtcEngineAdapter.trieMemos before accepting it",
            en, enAfterThird
        )
        Log.i(TAG, "LRU CYCLE en build=${"%.1f".format(enBuildMs)}ms " +
            "en refetch=${"%.3f".format(enRefetchMs)}ms " +
            "en rebuilt after $THIRD_LANGUAGE: ${enAfterThird !== en}")

        adapter.shutdown()
    }
}
