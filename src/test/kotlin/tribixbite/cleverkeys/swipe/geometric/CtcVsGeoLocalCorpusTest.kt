package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.CtcReplayEngine
import tribixbite.cleverkeys.swipe.TraceCorpusQuality
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * ARC-019 MEASUREMENT — the two configurations where CTC had never been contested:
 *
 *  1. **LOCAL combined corpus, same-inputs head-to-head** (`headToHead_localCombinedCorpus`).
 *     The ~8.6k-real-trace combined English test set is the one corpus where the geometric
 *     engine BEAT the deleted neural engine (55.2 vs ~53.7 top-1, spec §LOCAL replay). CTC —
 *     the shipping DEFAULT — was never run on it. This decodes the identical usable subset
 *     through BOTH engines. "Usable" = rows with monotone timestamps
 *     ([TraceCorpusQuality.hasUsableTimestamps]): ~47% of the corpus stores a non-timestamp
 *     third column that CTC's 60 Hz resampler turns into confident nonsense, so the
 *     head-to-head subset is smaller than the geo-only replay's 8.6k — both engines see
 *     EXACTLY the same rows, which is what makes the comparison valid.
 *
 *  2. **Synthetic degradation tiers** (`ctcOnSyntheticTiers`). The geometric engine has
 *     CLEAN/TYPICAL/SLOPPY robustness numbers from [GeoTraceSynthesizer]; CTC had none.
 *     Same synthesizer, same stratified word sample, same layout, decoded through the real
 *     shipped ONNX encoder + beam. CAVEAT stated up front: the synthesizer's 8 ms-step
 *     timing is synthetic, and CTC (unlike geometric) consumes timestamps via its 60 Hz
 *     resampler — treat the absolute numbers as indicative, the tier-to-tier DROP as the
 *     finding.
 *
 *  3. **UT-5 contraction-alias ranks** (`contractionAliasRanks_ut5`). The v1.5.0 deferral
 *     ("doesnt"→"doesn't" under-ranked) was never re-measured after the contraction rework.
 *     The corpus carries 12 real `dont` rows + 1 `cant`; synthetic traces cover the rest of
 *     the alias family. The slate surface here is the a–z ALIAS form ("dont") — the
 *     apostrophe display ("don't") is applied by the adapter overlay downstream, outside
 *     this replay's boundary (see [CtcReplayEngine] KDoc), so rank-of-alias IS the
 *     measurement.
 *
 * ## Gating — measurement, not a gate
 * Runs only under `-PgeoFull=true` with the local corpus cache + extracted ORT natives
 * present (all three skip cleanly otherwise). Assertions are deliberately loose sanity
 * floors that catch WIRING garbage (wrong frame/normalization ⇒ near-zero accuracy), not
 * regression floors — the numbers belong in `docs/eval/`, which is where each run's output
 * should be recorded.
 *
 * Run: `sh gradlew runPureTests -PtestClass=swipe.geometric.CtcVsGeoLocalCorpusTest -PgeoFull=true`
 */
class CtcVsGeoLocalCorpusTest {

    // ── corpus ────────────────────────────────────────────────────────────────

    private class Row(
        val word: String,
        val w: Float,
        val h: Float,
        val x: DoubleArray,
        val y: DoubleArray,
        val t: DoubleArray,
    )

    private val cacheFile = File(
        System.getProperty("user.home"), ".cache/cleverkeys-test/combined_english_swipes.jsonl.gz"
    )

    private fun loadUsableRows(): List<Row> {
        val rows = ArrayList<Row>(5000)
        GZIPInputStream(cacheFile.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val word = o.optString("word").lowercase()
                val pts = o.optJSONArray("pts") ?: continue
                if (word.isEmpty() || pts.length() < 3) continue
                val n = pts.length()
                val x = DoubleArray(n); val y = DoubleArray(n); val t = DoubleArray(n)
                for (i in 0 until n) {
                    val p = pts.getJSONArray(i)
                    x[i] = p.getDouble(0); y[i] = p.getDouble(1); t[i] = p.getDouble(2)
                }
                if (!TraceCorpusQuality.hasUsableTimestamps(t)) continue
                rows.add(
                    Row(
                        word,
                        o.optDouble("w", CANVAS_W.toDouble()).toFloat(),
                        o.optDouble("h", CANVAS_H.toDouble()).toFloat(),
                        x, y, t,
                    )
                )
            }
        }
        return rows
    }

    private fun assumeRunnable() {
        Assume.assumeTrue(
            "[skip] ARC-019 measurement — set -PgeoFull=true to run",
            System.getProperty("geoFull") == "true",
        )
        Assume.assumeTrue(
            "no local trace pool at ${cacheFile.path} (local-only, never committed) — skipping",
            cacheFile.isFile,
        )
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative is set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    // ── shared geometric wiring (mirrors GeoLocalCorpusReplayTest's shipped config) ──

    private val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val dict = GeoTestFixtures.englishCkdt()
    private val geoConfig = GeometricEngineConfig()

    private fun geoRank(word: String, engine: GeometricSwipeEngine, row: Row): Int {
        val trace = ArrayList<TracePoint>(row.x.size)
        for (i in row.x.indices) {
            trace.add(TracePoint((row.x[i] * row.w).toFloat(), (row.y[i] * row.h).toFloat(), row.t[i].toLong()))
        }
        if (trace.size < 3) return -1
        val result = engine.decode(GeometricSwipeRequest(trace, row.w, row.h, layout, dict))
        for (k in result.words.indices) if (result.words[k] == word) return k
        return -1
    }

    private fun ctcRank(word: String, engine: CtcReplayEngine, row: Row): Int {
        val slate = engine.decode(row.x, row.y, row.t)
        for (k in slate.words.indices) if (slate.words[k].lowercase() == word) return k
        return -1
    }

    private class Tally(val name: String) {
        var n = 0; var t1 = 0; var t3 = 0; var t5 = 0
        fun add(rank: Int) {
            n++
            if (rank == 0) t1++
            if (rank in 0..2) t3++
            if (rank in 0..4) t5++
        }
        fun line(): String = "%-14s n=%-5d top-1 %5.1f%%  top-3 %5.1f%%  top-5 %5.1f%%"
            .format(name, n, 100.0 * t1 / n, 100.0 * t3 / n, 100.0 * t5 / n)
    }

    // ── 1. same-inputs head-to-head ───────────────────────────────────────────

    @Test
    fun headToHead_localCombinedCorpus() {
        assumeRunnable()
        val rows = loadUsableRows()
        Assume.assumeTrue("no usable-timestamp rows", rows.isNotEmpty())

        // In-dict per the GEO dictionary (98k CKDT) — the same filter the geo-only replay
        // applies, so its historical numbers stay comparable.
        val dictWords = HashSet<String>(dict.size * 2)
        for (i in 0 until dict.size) dictWords.add(dict.word(i).lowercase())
        val inDict = rows.filter { dictWords.contains(it.word) }
        println(
            "[arc019] usable-timestamp rows ${rows.size} (of the full pool; ~47% lack real " +
                "timestamps), in-dict ${inDict.size}"
        )

        val geo = Tally("geometric")
        val ctc = Tally("ctc")
        var bothTop1 = 0; var onlyGeo = 0; var onlyCtc = 0; var neither = 0

        val geoEngine = GeometricSwipeEngine(geoConfig).also { it.warmUp(layout, dict) }
        CtcReplayEngine.build("en").use { ctcEngine ->
            for (r in inDict) {
                val g = geoRank(r.word, geoEngine, r)
                val c = ctcRank(r.word, ctcEngine, r)
                geo.add(g); ctc.add(c)
                when {
                    g == 0 && c == 0 -> bothTop1++
                    g == 0 -> onlyGeo++
                    c == 0 -> onlyCtc++
                    else -> neither++
                }
            }
            println("[arc019] EP=${CtcReplayEngine.executionProvider}")
        }

        println("[arc019] ── LOCAL combined, SAME inputs (usable-timestamp ∩ in-dict) ──")
        println("[arc019] " + geo.line())
        println("[arc019] " + ctc.line())
        println(
            "[arc019] top-1 agreement: both=%d geo-only=%d ctc-only=%d neither=%d"
                .format(bothTop1, onlyGeo, onlyCtc, neither)
        )
        println(
            "[arc019] context: geo-only full-corpus replay measured 55.2%% top-1 (all 8.6k rows); " +
                "neural (deleted) ~53.7%%. Record this run in docs/eval/."
        )

        // Wiring-sanity floors only (see class KDoc). A frame/normalization mistake lands
        // near zero, far below these.
        assertWithMessage("CTC top-1 on real local traces — wiring sanity")
            .that(ctc.t1.toDouble() / ctc.n).isAtLeast(0.30)
        assertWithMessage("geometric top-1 on real local traces — wiring sanity")
            .that(geo.t1.toDouble() / geo.n).isAtLeast(0.30)
    }

    // ── 2. CTC on the synthetic degradation tiers ─────────────────────────────

    @Test
    fun ctcOnSyntheticTiers() {
        assumeRunnable()

        val harness = GeoAccuracyHarness(layout, dict, "en/QWERTY(ctc-arm)")
        val sample = harness.stratifiedSample(SYNTH_SAMPLE)
        val synth = GeoTraceSynthesizer(geoConfig)
        val canvasW = 1000f
        val canvasH = canvasW / layout.aspect

        CtcReplayEngine.build("en").use { engine ->
            for (tier in listOf(
                GeoTraceSynthesizer.Tier.CLEAN,
                GeoTraceSynthesizer.Tier.TYPICAL,
                GeoTraceSynthesizer.Tier.SLOPPY,
            )) {
                val tally = Tally("ctc/$tier")
                for ((idx, sw) in sample.withIndex()) {
                    for (seed in 0 until SYNTH_SEEDS) {
                        val trace = synth.synthesize(
                            sw.word, layout, canvasW, canvasH, tier,
                            seed = (idx.toLong() shl 8) or seed.toLong(),
                        ) ?: continue
                        val n = trace.size
                        val x = DoubleArray(n); val y = DoubleArray(n); val t = DoubleArray(n)
                        for (i in 0 until n) {
                            x[i] = (trace[i].x / canvasW).toDouble()
                            y[i] = (trace[i].y / canvasH).toDouble()
                            t[i] = trace[i].tMillis.toDouble()
                        }
                        val slate = engine.decode(x, y, t)
                        val rank = slate.words.indexOfFirst { it.lowercase() == sw.word.lowercase() }
                        tally.add(rank)
                    }
                }
                println("[arc019-synth] " + tally.line())
            }
            println(
                "[arc019-synth] geo reference (same synthesizer, own harness): TYPICAL 83.4 / " +
                    "SLOPPY 63.8 top-1. CAVEAT: synthetic 8 ms-step timing feeds CTC's 60 Hz " +
                    "resampler — read the tier-to-tier drop, not the absolute level."
            )
        }
    }

    // ── 3. UT-5: contraction-alias ranks ──────────────────────────────────────

    @Test
    fun contractionAliasRanks_ut5() {
        assumeRunnable()
        val aliases = listOf("dont", "doesnt", "cant", "wont", "isnt", "didnt", "im", "ive", "id")
        val rows = loadUsableRows().filter { it.word in aliases }
        val synth = GeoTraceSynthesizer(geoConfig)
        val canvasW = 1000f
        val canvasH = canvasW / layout.aspect

        CtcReplayEngine.build("en").use { engine ->
            println("[ut5] ── real corpus rows ──")
            for (r in rows) {
                val slate = engine.decode(r.x, r.y, r.t)
                val rank = slate.words.indexOfFirst { it.lowercase() == r.word }
                println("[ut5] real   '${r.word}' rank=$rank slate=${slate.words.take(5)}")
            }
            println("[ut5] ── synthetic (TYPICAL, 3 seeds each) ──")
            for (word in aliases) {
                for (seed in 0 until 3) {
                    val trace = synth.synthesize(
                        word, layout, canvasW, canvasH,
                        GeoTraceSynthesizer.Tier.TYPICAL, seed = word.hashCode().toLong() + seed,
                    ) ?: continue
                    val n = trace.size
                    val x = DoubleArray(n); val y = DoubleArray(n); val t = DoubleArray(n)
                    for (i in 0 until n) {
                        x[i] = (trace[i].x / canvasW).toDouble()
                        y[i] = (trace[i].y / canvasH).toDouble()
                        t[i] = trace[i].tMillis.toDouble()
                    }
                    val slate = engine.decode(x, y, t)
                    val rank = slate.words.indexOfFirst { it.lowercase() == word }
                    println("[ut5] synth  '$word' seed=$seed rank=$rank slate=${slate.words.take(5)}")
                }
            }
            println(
                "[ut5] slate surface is the a-z ALIAS form; the apostrophe display " +
                    "(don't/doesn't) is applied by the adapter overlay downstream."
            )
        }
    }

    private companion object {
        /** Canonical grid canvas of the local corpus (see GeoLocalCorpusReplayTest KDoc). */
        const val CANVAS_W = 360f
        const val CANVAS_H = 215f

        /** Synthetic-arm grid: enough for a stable tier ordering without an hour of decode. */
        const val SYNTH_SAMPLE = 150
        const val SYNTH_SEEDS = 2
    }
}
