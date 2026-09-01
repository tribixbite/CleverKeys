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
        if (!sweepEnabled()) { println("[skip] OQ sweep — set -PgeoSweep=true to run"); return }
        val scales = listOf(1.0f, 0.5f, 0.25f, 0.0f)
        val configs = scales.map { s -> "scale=$s" to GeometricEngineConfig(endOvershootCostScale = s) }
        runSyntheticSweep("OQ-9 endOvershootCostScale", configs)
        runLocalCorpusSweep("OQ-9 endOvershootCostScale", configs)
    }

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
