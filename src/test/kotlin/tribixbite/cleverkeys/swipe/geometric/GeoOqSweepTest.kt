package tribixbite.cleverkeys.swipe.geometric

import org.junit.Assume
import org.junit.Test

/**
 * WAVE-G MEASUREMENT INSTRUMENT for the archived geo research doc's open questions
 * (docs/history/audits/2026-07-20-geo-sloppy-research.md §4): ARC-027 / OQ-9
 * (direction-aware overshoot clamp), ARC-028 / OQ-10 (length-scaled ordering slack),
 * ARC-029 / OQ-11 (reversal-count confidence signal).
 *
 * Each sweep decodes the SAME deterministic stratified sample (250 words × 3 seeds,
 * NFR-4 seeded) across layouts × tiers under the candidate knob values, side by side
 * with the shipped-default baseline IN THE SAME JVM RUN — so every delta is exact
 * (no cross-run noise), plus the ~8.5k-trace LOCAL real-corpus replay (shared loader
 * [GeoLocalCorpus], identical rows/geometry to the official A/B gate) per knob value.
 * This is the campaign's evidence gate: NO default changes without these tables
 * showing a non-regressing improvement.
 *
 * ## Gating
 * Heavy (tens of thousands of full-dictionary decodes) and measurement-only, so it is
 * DOUBLE-gated: `-PgeoSweep=true` (bridged to the `geoSweep` system property in
 * build.gradle, analogous to `geoFull`) AND — for the real-corpus half — the local
 * corpus cache being present (never committed; `Assume`-skipped when absent).
 * The default suite and CI always skip in milliseconds.
 *
 * Run: `scripts/gradle-guard.sh runPureTests -PtestClass=swipe.geometric.GeoOqSweepTest -PgeoSweep=true`
 *
 * The tests deliberately assert nothing about which knob value WINS — they are
 * instruments, not gates; the ship/decline decision and its numbers are recorded in
 * the knob KDocs in [GeometricEngineConfig] and the wave-G commit messages. The only
 * assertions are structural sanity (the baseline config decodes at all).
 */
class GeoOqSweepTest {

    private fun sweepEnabled(): Boolean = System.getProperty("geoSweep") == "true"

    /**
     * Per-method selector: `-PoqOnly=oq9|oq10|oq11` (bridged like `geoSweep`) runs just
     * one instrument method — each is many minutes of full-dictionary decoding, and a
     * re-measurement of one OQ should not pay for the other two. Empty/unset runs all.
     */
    private fun oqSelected(name: String): Boolean {
        if (!sweepEnabled()) { println("[skip] OQ sweep — set -PgeoSweep=true to run"); return false }
        val only = System.getProperty("oqOnly") ?: ""
        if (only.isNotEmpty() && only != name) { println("[skip] $name — oqOnly=$only"); return false }
        return true
    }

    // ── shared fixtures ─────────────────────────────────────────────────────────

    private val en = GeoTestFixtures.englishCkdt()

    /** The three sweep layouts: control + the two research-doc problem children. */
    private data class SweepLayout(val label: String, val layout: LayoutGeometry)

    private val layouts: List<SweepLayout> by lazy {
        listOf(
            SweepLayout("en/qwerty", GeoLayoutFixtures.loadShipped("latn_qwerty_us")),
            SweepLayout("en/dvorak", GeoLayoutFixtures.loadShipped("latn_dvorak")),
            SweepLayout("en/weird", GeoLayoutFixtures.loadFixture("weird_custom")),
        )
    }

    private val tiers = listOf(
        GeoTraceSynthesizer.Tier.CLEAN,
        GeoTraceSynthesizer.Tier.TYPICAL,
        GeoTraceSynthesizer.Tier.SLOPPY,
    )

    /** Sweep grid size: medium (exact same-sample deltas; winners get the official full grid). */
    private val sampleSize = 250
    private val seeds = 3

    // ── OQ-9 / ARC-027: direction-aware overshoot clamp ─────────────────────────

    @Test
    fun oq9_endOvershootCostScale_sweep() {
        if (!oqSelected("oq9")) return
        val scales = listOf(1.0f, 0.5f, 0.25f, 0.0f)
        val configs = scales.map { s -> "scale=$s" to GeometricEngineConfig(endOvershootCostScale = s) }
        runSyntheticSweep("OQ-9 endOvershootCostScale", configs)
        runLocalCorpusSweep("OQ-9 endOvershootCostScale", configs)
    }

    // ── OQ-10 / ARC-028: length-scaled ordering slack ───────────────────────────

    @Test
    fun oq10_orderingSlack_sweep() {
        if (!oqSelected("oq10")) return
        val configs = listOf(
            "off(base)" to GeometricEngineConfig(),
            "W1,len2" to GeometricEngineConfig(orderingSlackTunnelW = 1, orderingSlackMinTemplateLenKw = 2f),
            "W1,len3" to GeometricEngineConfig(orderingSlackTunnelW = 1, orderingSlackMinTemplateLenKw = 3f),
            "W1,len4" to GeometricEngineConfig(orderingSlackTunnelW = 1, orderingSlackMinTemplateLenKw = 4f),
            "W2,len3" to GeometricEngineConfig(orderingSlackTunnelW = 2, orderingSlackMinTemplateLenKw = 3f),
        )
        runSyntheticSweep("OQ-10 orderingSlack", configs)
        runLocalCorpusSweep("OQ-10 orderingSlack", configs)
    }

    // ── OQ-11 / ARC-029: reversal-count confidence signal ───────────────────────

    /**
     * Measures whether the gesture's direction-reversal count is a REAL quality
     * signal on real traces, and whether reversal-scaled confidence temperature
     * improves calibration. Confidence-only: ranking is provably unaffected (a
     * temperature change is monotone on the scores), so the evidence here is about
     * the 0–1000 posterior, not top-K accuracy.
     *
     *  - Table 1: reversal-count distribution + top-1 accuracy + mean emitted top-1
     *    confidence per bucket {0, 1, 2, 3+} — the correlation claim.
     *  - Table 2: 10-bin expected calibration error (ECE) of the top-1 confidence
     *    under candidate `reversalConfidenceTempSlope` values. Re-tempering uses the
     *    posterior identity `softmax(S/m) ∝ p^(1/m)` (p from the emitted integers,
     *    a ≤0.05% rounding approximation), so ONE decode pass measures every slope.
     */
    @Test
    fun oq11_reversalConfidence_measurement() {
        if (!oqSelected("oq11")) return
        Assume.assumeTrue(
            "local corpus cache not found at ${GeoLocalCorpus.cacheFile.absolutePath} (skipping)",
            GeoLocalCorpus.cacheFile.exists(),
        )
        val rows = GeoLocalCorpus.load()
        val dictWords = HashSet<String>(en.size * 2)
        for (i in 0 until en.size) dictWords.add(en.word(i).lowercase())
        val inDict = rows.filter { dictWords.contains(it.word) }
        val aspect = if (inDict.isNotEmpty()) inDict[0].w / inDict[0].h else GeoLocalCorpus.CANVAS_ASPECT
        val layout = GeoLocalCorpus.buildQwertyEnglishLayout(aspect)

        val cfg = GeometricEngineConfig() // slope 0 — raw posterior; reversals still counted
        val engine = GeometricSwipeEngine(cfg).also { it.warmUp(layout, en) }
        val prep = GesturePreprocessor(cfg)

        class Obs(val reversals: Int, val correct: Boolean, val probs: DoubleArray)
        val obs = ArrayList<Obs>(inDict.size)
        for (r in inDict) {
            val trace = GeoLocalCorpus.toTrace(r)
            if (trace.size < 3) continue
            val gesture = prep.process(trace, r.w, r.h, layout)
            val result = engine.decode(GeometricSwipeRequest(trace, r.w, r.h, layout, en))
            if (result.words.isEmpty()) continue
            val probs = DoubleArray(result.scores.size) { result.scores[it] / 1000.0 }
            obs.add(Obs(gesture.reversalCount, result.words[0] == r.word, probs))
        }

        println("")
        println("========== OQ-11 reversal-count confidence — LOCAL REAL-CORPUS (n=${obs.size}) ==========")
        // ── Table 1: correlation ──
        println("reversals   n       top-1 acc   mean top-1 conf (slope=0)")
        val buckets = listOf(0..0, 1..1, 2..2, 3..Int.MAX_VALUE)
        val labels = listOf("0", "1", "2", "3+")
        for ((b, range) in buckets.withIndex()) {
            val sel = obs.filter { it.reversals in range }
            if (sel.isEmpty()) { println("${labels[b].padEnd(9)}   0       —           —"); continue }
            val acc = sel.count { it.correct }.toDouble() / sel.size
            val conf = sel.sumOf { it.probs[0] } / sel.size
            println("${labels[b].padEnd(9)}   ${sel.size.toString().padEnd(6)}  ${pct(acc)}      ${pct(conf)}")
        }

        // ── Table 2: calibration under candidate slopes ──
        fun top1ConfUnderSlope(o: Obs, slope: Float): Double {
            if (slope <= 0f || o.reversals <= 0) return o.probs[0]
            val m = (1.0 + slope * o.reversals).coerceAtMost(cfg.reversalConfidenceTempMax.toDouble())
            var sum = 0.0
            var top = 0.0
            for (k in o.probs.indices) {
                val p = if (o.probs[k] > 0) Math.pow(o.probs[k], 1.0 / m) else 0.0
                if (k == 0) top = p
                sum += p
            }
            return if (sum > 0) top / sum else o.probs[0]
        }
        fun ece(slope: Float): Double {
            val bins = Array(10) { intArrayOf(0, 0) } // [count, correct]
            val confSum = DoubleArray(10)
            for (o in obs) {
                val c = top1ConfUnderSlope(o, slope)
                val b = (c * 10).toInt().coerceIn(0, 9)
                bins[b][0]++
                if (o.correct) bins[b][1]++
                confSum[b] += c
            }
            var e = 0.0
            for (b in 0 until 10) {
                if (bins[b][0] == 0) continue
                val acc = bins[b][1].toDouble() / bins[b][0]
                val conf = confSum[b] / bins[b][0]
                e += bins[b][0].toDouble() / obs.size * Math.abs(acc - conf)
            }
            return e
        }
        println("--- 10-bin ECE of top-1 confidence (lower = better calibrated) ---")
        for (slope in listOf(0f, 0.15f, 0.3f, 0.5f, 1.0f)) {
            println("slope=${slope.toString().padEnd(5)}  ECE=${"%.4f".format(ece(slope))}")
        }
        // Overall over/under-confidence context for reading the ECE table.
        val acc = obs.count { it.correct }.toDouble() / obs.size
        val meanConf = obs.sumOf { it.probs[0] } / obs.size
        println("overall: top-1 acc ${pct(acc)}   mean top-1 conf ${pct(meanConf)} (slope=0)")
        println("=".repeat(78))
    }

    private fun pct(x: Double): String = "%5.1f%%".format(x * 100)

    // ── shared sweep drivers ────────────────────────────────────────────────────

    /**
     * Decode the deterministic per-layout stratified sample at every tier under every
     * config; print an aligned table with deltas vs the FIRST config (the baseline).
     */
    private fun runSyntheticSweep(title: String, configs: List<Pair<String, GeometricEngineConfig>>) {
        println("")
        println("========== $title — SYNTHETIC sweep (sample=$sampleSize seeds=$seeds) ==========")
        for (sl in layouts) {
            // Sample once per layout (identical across configs; stratification depends
            // only on layout + dict + fixed seed).
            val sample = GeoAccuracyHarness(sl.layout, en, sl.label).stratifiedSample(sampleSize)
            val results = HashMap<Pair<String, GeoTraceSynthesizer.Tier>, GeoAccuracyHarness.Accuracy>()
            for ((name, cfg) in configs) {
                val harness = GeoAccuracyHarness(sl.layout, en, "$title ${sl.label} $name", cfg)
                for (tier in tiers) {
                    results[name to tier] = harness.runGrid(sample, tier, seeds)
                }
            }
            val baseName = configs.first().first
            println("--- ${sl.label} (deltas vs $baseName, pts) ---")
            println("config            tier      top-1        top-3        top-5")
            for ((name, _) in configs) {
                for (tier in tiers) {
                    val r = results.getValue(name to tier)
                    val b = results.getValue(baseName to tier)
                    println(
                        "${name.padEnd(14)}  ${tier.name.padEnd(8)}" +
                            "  ${cell(r.top1, b.top1)}  ${cell(r.top3, b.top3)}  ${cell(r.top5, b.top5)}"
                    )
                }
            }
        }
        println("=".repeat(78))
    }

    /**
     * Replay the full LOCAL combined-corpus (real en/QWERTY swipes) under every config;
     * print overall + per-length-stratum top-K with deltas vs the first (baseline) config.
     */
    private fun runLocalCorpusSweep(title: String, configs: List<Pair<String, GeometricEngineConfig>>) {
        Assume.assumeTrue(
            "local corpus cache not found at ${GeoLocalCorpus.cacheFile.absolutePath} (skipping corpus half)",
            GeoLocalCorpus.cacheFile.exists(),
        )
        val rows = GeoLocalCorpus.load()
        val dictWords = HashSet<String>(en.size * 2)
        for (i in 0 until en.size) dictWords.add(en.word(i).lowercase())
        val inDict = rows.filter { dictWords.contains(it.word) }
        val aspect = if (inDict.isNotEmpty()) inDict[0].w / inDict[0].h else GeoLocalCorpus.CANVAS_ASPECT
        val layout = GeoLocalCorpus.buildQwertyEnglishLayout(aspect)
        println("")
        println("========== $title — LOCAL REAL-CORPUS sweep (n=${inDict.size} in-dict of ${rows.size}) ==========")

        class Tally {
            var n = 0; var t1 = 0; var t3 = 0; var t5 = 0
            fun add(rank: Int) {
                n++
                if (rank == 0) t1++
                if (rank in 0..2) t3++
                if (rank in 0..4) t5++
            }
            fun top1() = if (n > 0) t1.toDouble() / n else 0.0
            fun top3() = if (n > 0) t3.toDouble() / n else 0.0
            fun top5() = if (n > 0) t5.toDouble() / n else 0.0
        }

        fun lenBucket(word: String): Int {
            val n = word.replace("'", "").length
            return if (n <= 3) 0 else if (n <= 6) 1 else 2
        }
        val bucketLabels = listOf("2-3", "4-6", "7+")

        val overall = LinkedHashMap<String, Tally>()
        val byLen = LinkedHashMap<String, Array<Tally>>()
        for ((name, cfg) in configs) {
            val engine = GeometricSwipeEngine(cfg).also { it.warmUp(layout, en) }
            val o = Tally()
            val lens = Array(3) { Tally() }
            for (r in inDict) {
                val trace = GeoLocalCorpus.toTrace(r)
                if (trace.size < 3) continue
                val result = engine.decode(GeometricSwipeRequest(trace, r.w, r.h, layout, en))
                var rank = -1
                for (k in result.words.indices) if (result.words[k] == r.word) { rank = k; break }
                o.add(rank)
                lens[lenBucket(r.word)].add(rank)
            }
            overall[name] = o
            byLen[name] = lens
        }

        val baseName = configs.first().first
        val base = overall.getValue(baseName)
        println("config          top-1        top-3        top-5      (deltas vs $baseName)")
        for ((name, t) in overall) {
            println("${name.padEnd(14)}  ${cell(t.top1(), base.top1())}  ${cell(t.top3(), base.top3())}  ${cell(t.top5(), base.top5())}")
        }
        println("--- per length stratum (top-1 / top-3) ---")
        for ((name, lens) in byLen) {
            val bl = byLen.getValue(baseName)
            for (b in 0..2) {
                println(
                    "${name.padEnd(14)}  len=${bucketLabels[b].padEnd(3)} n=${lens[b].n}" +
                        "  ${cell(lens[b].top1(), bl[b].top1())}  ${cell(lens[b].top3(), bl[b].top3())}"
                )
            }
        }
        println("=".repeat(78))
    }

    /** `xx.x% (+d.d)` cell — measured value plus delta vs baseline in points. */
    private fun cell(x: Double, base: Double): String =
        "%5.1f%%(%+.1f)".format(x * 100, (x - base) * 100)
}
