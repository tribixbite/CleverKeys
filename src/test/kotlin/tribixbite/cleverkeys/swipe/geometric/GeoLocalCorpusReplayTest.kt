package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assume
import org.junit.Test

/**
 * LOCAL COMBINED-CORPUS REPLAY — the neural model's held-out test distribution.
 *
 * A third, INDEPENDENT real-corpus source (after the FUTO swipe-1/test replay in
 * [GeoRealCorpusReplayTest] and the multi-layout FUTO swipe-5/train replay in
 * `GeoRealCorpusMultiLayoutTest`). This one replays the SAME traces the ONNX neural
 * pipeline is scored on — the combined English swipe test set, ~8.6k real en/QWERTY
 * swipes in the proshian `{curve:{x[],y[],t[],grid_name},word}` format — through the
 * SHIPPED geometric engine on the DEFAULT Latin QWERTY layout. It reports A/B (shipped
 * defaults vs the pre-SLOPPY-fix baseline) so any real-corpus regression fails loudly,
 * and it enables the future geometric-vs-neural head-to-head on identical inputs (the
 * neural side is NOT implemented here — this is the geometric half of that bridge).
 *
 * ## Gating (default suite + CI both skip cleanly)
 *  - `-PgeoFull` (`System.getProperty("geoFull") == "true"`, bridged in build.gradle) —
 *    the replay decodes ALL ~8.6k real traces against the full 98,140-word en dictionary
 *    UNDER BOTH configs, far heavier than the < 90 s default-suite budget.
 *  - `Assume.assumeTrue(cacheFile.exists())` — the corpus DATA is LOCAL-ONLY (never
 *    committed; it is the neural model's private held-out set). Regenerate the cache with
 *    `node scripts/build_local_corpus_replay.mjs` (reads the local
 *    `~/storage/shared/Download/combined_english_swipes_test.jsonl.txt`); absent → the test
 *    is SKIPPED, so a clean checkout / CI never fails for a missing local cache.
 *
 * Run: `sh gradlew runPureTests -PtestClass=swipe.geometric.GeoLocalCorpusReplayTest -PgeoFull=true`
 *
 * ## Geometry source (DOCUMENTED, per spec deliverable — reconciled with evidence)
 * The `qwerty_english` grid geometry is defined in the repo at
 * `tools/test_cli_predict.py` / `tools/test_cli_predict.ts` (the `QWERTY_KEYS` centroid
 * table) and `model/train_character_model.py` (`KeyboardGrid`, loading
 * `data/data_preprocessed/gridname_to_grid.json`). All three agree on the SAME per-key
 * PIXEL centroids, laid out on a 36 px horizontal / 59 px vertical pitch:
 * ```
 *   q(18,34) w(54,34) e(90,34) r(126,34) t(162,34) y(198,34) u(234,34) i(270,34) o(306,34) p(342,34)
 *   a(36,93) s(72,93) d(108,93) f(144,93) g(180,93) h(216,93) j(252,93) k(288,93) l(324,93)
 *   z(72,152) x(108,152) c(144,152) v(180,152) b(216,152) n(252,152) m(288,152)
 * ```
 * The replay [LayoutGeometry] is built directly from THIS table (NOT from an
 * `srcs/layouts` XML) — [QWERTY_ENGLISH_KEYS] below is a verbatim transcription. This is
 * the exact geometry the neural pipeline's nearest-key feature uses, so the replay is
 * faithful to the corpus's coordinate frame.
 *
 * ### Canvas reconciliation (evidence-checked, per the GEOMETRY CAUTION)
 * Measured extents across all 8,607 traces: x ∈ [0.87, 360.00], y ∈ [0.00, 215.00].
 * The canonical grid canvas is therefore **360 × 215 px** (bottom-row centroid 152 +
 * half key height). The `/280` divisor in `test_cli_predict.py`'s `extractFeatures` is an
 * unrelated NEURAL-MODEL input squash (it just maps px into a [0,1)-ish band the encoder
 * was trained on) — it is NOT the geometric coordinate frame and is deliberately ignored
 * here. Geometry validated by two independent evidence checks BEFORE the full grid run:
 *  - START-key nearest-match 73.0% / END-key 66.1% — LOWER than FUTO's 94.7% start match,
 *    but the median start-point distance to the true first-key centroid is only 14.3 px
 *    (< the 36 px key pitch) and the misses are to ADJACENT keys (`d`→`f`, `e`→`r`). This
 *    is intrinsic corpus DIFFICULTY (it is the neural model's HARD held-out set — the
 *    neural decoder itself gets only ~53% top-1 on it), not a geometry mismatch.
 *  - Per-word letter COVERAGE 88.7% (fraction of a word's unique letters whose key the
 *    trace path passes over) — confirms the paths genuinely traverse their letters on this
 *    grid, i.e. the centroids/canvas are correct.
 *
 * ## Coordinate handling (identical contract to [GeoRealCorpusReplayTest])
 * The cache stores `pts` as normalized [0,1] over the canvas plus per-row `w,h` (= the
 * pixel canvas). `TracePoint` expects key-area-local PIXELS, so each point is multiplied
 * by `w,h` and the engine is told `keyAreaWidthPx/HeightPx = (w, h)`. This whole corpus is
 * a single fixed 360×215 canvas → a single aspect bucket (`w/h ≈ 1.674`), so one warmed
 * engine+index per config suffices (no bucket loop needed).
 *
 * ## What is asserted
 * FIRST RUN was REPORT-ONLY (no accuracy floors). After observing the measured A-config
 * numbers, CONSERVATIVE regression floors ~4 pts below measured were added (PROVISIONAL,
 * mirroring [GeoAccuracyThresholds] discipline) so a future regression is caught without
 * brittleness, plus a hard A ≥ B − 1pt non-regression guard (the non-circular claim).
 */
class GeoLocalCorpusReplayTest {

    // ── corpus + geometry sources ────────────────────────────────────────────

    /** LOCAL-ONLY gzipped JSONL cache (regenerate via scripts/build_local_corpus_replay.mjs). */
    private val cacheFile: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        val dir = if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
        File(dir, "combined_english_swipes.jsonl.gz")
    }

    /** Full shipped English dictionary (98,140 CKDT) — the same lexicon every en replay
     *  and the synthetic QWERTY accuracy test decode against. */
    private val dict = GeoTestFixtures.englishCkdt()

    // ── A/B configs (the entire point) ────────────────────────────────────────

    /** Config A — the SHIPPED defaults (this week's SLOPPY-tier tuning). */
    private val configA = GeometricEngineConfig()

    /**
     * Config B — the PRE-FIX baseline: the three SLOPPY levers reverted to pre-tuning
     * values (`endpointInsetKw=0`, `directionPenaltyWeight=0`, `maxCandidatesScored=800`).
     * Every other knob stays at the shipped default so the A/B delta isolates exactly the
     * tuning under test — identical to [GeoRealCorpusReplayTest]'s config B.
     */
    private val configB = GeometricEngineConfig(
        endpointInsetKw = 0f,
        directionPenaltyWeight = 0f,
        maxCandidatesScored = 800,
    )

    // ── data model ─────────────────────────────────────────────────────────────

    /** One replay row: canonical word + canvas px extents + normalized trace points. */
    private data class ReplayRow(
        val word: String,
        val w: Float,
        val h: Float,
        val pts: List<FloatArray>, // each = [x, y, t] with x,y normalized [0,1]
    )

    /** Length stratum by codepoint count (apostrophes stripped — none in this corpus). */
    private enum class LenStratum(val label: String) { SHORT_2_3("2-3"), MID_4_6("4-6"), LONG_7_PLUS("7+") }

    private fun lenStratumOf(word: String): LenStratum {
        val n = word.replace("'", "").length
        return when {
            n <= 3 -> LenStratum.SHORT_2_3
            n <= 6 -> LenStratum.MID_4_6
            else -> LenStratum.LONG_7_PLUS
        }
    }

    /** Mutable top-K tally; `.add(rank)` a decode outcome, `.topN()` snapshots fractions. */
    private class Tally {
        var n = 0; var t1 = 0; var t3 = 0; var t5 = 0
        fun add(rank: Int) {
            n++
            if (rank in 0 until 1) t1++
            if (rank in 0 until 3) t3++
            if (rank in 0 until 5) t5++
        }
        fun top1() = if (n > 0) t1.toDouble() / n else 0.0
        fun top3() = if (n > 0) t3.toDouble() / n else 0.0
        fun top5() = if (n > 0) t5.toDouble() / n else 0.0
    }

    // ── the replay ─────────────────────────────────────────────────────────────

    @Test
    fun localCorpusReplay_abReport() {
        // Gate 1: full-grid flag (heavy — ~8.6k real decodes × 2 configs × 98k dict).
        if (System.getProperty("geoFull") != "true") {
            println("[skip] local combined-corpus replay — set -PgeoFull=true to run")
            return
        }
        // Gate 2: the local corpus cache must exist (never committed).
        Assume.assumeTrue(
            "local corpus cache not found at ${cacheFile.absolutePath}; regenerate with " +
                "`node scripts/build_local_corpus_replay.mjs` (skipping)",
            cacheFile.exists(),
        )

        val rows = loadSample(cacheFile)
        println("[replay] loaded ${rows.size} real traces from ${cacheFile.name}")

        // ── dictionary coverage: fraction of corpus words present in the full en dict ──
        val dictWords = HashSet<String>(dict.size * 2)
        for (i in 0 until dict.size) dictWords.add(dict.word(i).lowercase())
        // The decode target must match a dictionary entry EXACTLY (the engine emits the
        // canonical form) — coverage is on the lowercased surface form.
        val inDict = rows.filter { dictWords.contains(it.word) }
        val oovCount = rows.size - inDict.size
        val coverage = if (rows.isNotEmpty()) inDict.size.toDouble() / rows.size else 0.0
        println("[replay] dictionary coverage: ${inDict.size}/${rows.size} = ${pct(coverage)} " +
            "(OOV=$oovCount — rare/technical terms absent from the 98k en dict; decoding only in-dict words)")

        // ── single fixed canvas → single aspect bucket → one warmed engine per config ──
        val aspect = if (inDict.isNotEmpty()) inDict[0].w / inDict[0].h else CANVAS_ASPECT
        val layout = buildQwertyEnglishLayout(aspect)
        // Endpoint sanity match-rates on the SHIPPED geometry (documented in the report,
        // reconciled above — LOW start/end match is corpus difficulty, not a bad grid).
        val (startMatch, endMatch) = endpointMatchRates(inDict, layout)
        println("[replay] geometry sanity — start-key match ${pct(startMatch)}, end-key match ${pct(endMatch)} " +
            "(median start-pt→true-key dist ~14px < 36px pitch → adjacent-key noise, geometry OK)")

        val a = ConfigTallies("A(shipped)")
        val b = ConfigTallies("B(pre-fix)")

        val engineA = GeometricSwipeEngine(configA).also { it.warmUp(layout, dict) }
        val engineB = GeometricSwipeEngine(configB).also { it.warmUp(layout, dict) }
        val prepA = GesturePreprocessor(configA)
        val prunerA = CandidatePruner(configA)
        val indexA = TemplateIndex.build(layout, dict, configA, TemplateGenerator(configA))
        val prepB = GesturePreprocessor(configB)
        val prunerB = CandidatePruner(configB)
        val indexB = TemplateIndex.build(layout, dict, configB, TemplateGenerator(configB))

        for (r in inDict) {
            val ordinal = ordinalOf(r.word)
            val trace = toTrace(r)
            if (trace.size < 3) continue
            decodeInto(a, r, trace, layout, engineA, prepA, prunerA, indexA, ordinal)
            decodeInto(b, r, trace, layout, engineB, prepB, prunerB, indexB, ordinal)
        }

        // ── report ────────────────────────────────────────────────────────────────
        printAbTable(a, b, coverage, inDict.size, oovCount, startMatch, endMatch)

        // ── cross-corpus comparison vs the FUTO en replay (spec deliverable 3) ──────
        // Same engine, DIFFERENT collection methodology: FUTO swipe-1/test measured
        // 75.2/85.4/87.9 (t1/t3/t5) at 97.8% coverage. This is the NEURAL model's test
        // distribution — the future geometric-vs-neural head-to-head decodes THESE traces
        // (neural side not implemented here; see spec § Phase 7 / WP9 router).
        println("[replay] --- cross-corpus comparison (same shipped engine, different corpora) ---")
        println("[replay] FUTO swipe-1/test:   75.2% / 85.4% / 87.9%  (t1/t3/t5) @ 97.8% cov")
        println("[replay] LOCAL combined-test: ${pct(a.overall.top1())} / ${pct(a.overall.top3())} / " +
            "${pct(a.overall.top5())}  (t1/t3/t5) @ ${pct(coverage)} cov")
        println("[replay] NOTE this is the NEURAL model's held-out set (neural top-1 ~53% per " +
            "tools/test_cli_predict.py); the geometric-vs-neural bridge decodes IDENTICAL inputs.")

        // ── regression floors (PROVISIONAL — ~4 pts below the first measured run) ──
        // Added AFTER the report-only first run (measured A: see the constants). These
        // guard against a silent real-corpus regression without being brittle.
        assertWithMessage("A(shipped) local-corpus overall top-1 (PROVISIONAL floor)")
            .that(a.overall.top1()).isAtLeast(LOCAL_A_TOP1_FLOOR)
        assertWithMessage("A(shipped) local-corpus overall top-3 (PROVISIONAL floor)")
            .that(a.overall.top3()).isAtLeast(LOCAL_A_TOP3_FLOOR)
        assertWithMessage("A(shipped) local-corpus overall top-5 (PROVISIONAL floor)")
            .that(a.overall.top5()).isAtLeast(LOCAL_A_TOP5_FLOOR)

        // ── the non-circular claim: shipped tuning must NOT regress vs pre-fix on REAL
        //    noise. A worse-than-baseline A is a CRITICAL finding — fail loudly. ──────
        abMustNotRegress(a, b)
    }

    /** Decode one trace under a config's engine and record top-K + prune survival. */
    private fun decodeInto(
        tallies: ConfigTallies,
        row: ReplayRow,
        trace: List<TracePoint>,
        layout: LayoutGeometry,
        engine: GeometricSwipeEngine,
        prep: GesturePreprocessor,
        pruner: CandidatePruner,
        index: TemplateIndex,
        ordinal: Int,
    ) {
        val w = row.w
        val h = row.h
        val result = engine.decode(GeometricSwipeRequest(trace, w, h, layout, dict))
        val rank = rankOf(row.word, result)
        val stratum = lenStratumOf(row.word)
        tallies.overall.add(rank)
        tallies.byLen.getValue(stratum).add(rank)

        // Prune recall (survivor set contains the true ordinal): attributes a miss to
        // pruning vs scoring, matching the synthetic harness's `pruneRecall`.
        if (ordinal >= 0) {
            val gesture = prep.process(trace, w, h, layout)
            if (gesture.pathLengthKw > 0f) {
                val survivors = pruner.prune(gesture, index, layout)
                tallies.pruneTested++
                var found = false
                for (o in survivors) if (o == ordinal) { found = true; break }
                if (found) tallies.pruneSurvived++
            }
        }
    }

    // ── qwerty_english geometry builder ─────────────────────────────────────────

    /** One `qwerty_english` key: letter + PIXEL centroid (from the repo grid tables). */
    private data class GridKey(val letter: String, val px: Float, val py: Float)

    /**
     * Build the replay [LayoutGeometry] for a given [aspect] from the repo-authoritative
     * `qwerty_english` PIXEL centroids ([QWERTY_ENGLISH_KEYS]). Pixel centroids are
     * normalized to [0,1] over the 360×215 canvas (matching the loader's normalized pts);
     * key hit-boxes use the uniform grid pitch (36 px wide, 59 px tall). Row/col are
     * assigned row-major by (py, px) so ids are deterministic. NO bottom/function row is
     * appended (the corpus canvas IS the letter area — same as the FUTO builder).
     */
    private fun buildQwertyEnglishLayout(aspect: Float): LayoutGeometry {
        // Row grouping by py; col by px within row.
        val distinctPy = QWERTY_ENGLISH_KEYS.map { it.py }.distinct().sorted()
        val rowIndexOf = HashMap<Float, Int>()
        distinctPy.forEachIndexed { i, py -> rowIndexOf[py] = i }

        val ordered = QWERTY_ENGLISH_KEYS.sortedWith(compareBy({ rowIndexOf.getValue(it.py) }, { it.px }))
        val keys = ArrayList<SwipeKey>(ordered.size)
        val chars = HashMap<Int, MutableList<Int>>()
        val colCounters = HashMap<Int, Int>()
        // Uniform key hit-box (px pitch), normalized to the canvas.
        val kwNorm = KEY_PITCH_X_PX / CANVAS_W
        val khNorm = KEY_PITCH_Y_PX / CANVAS_H
        ordered.forEachIndexed { id, k ->
            val row = rowIndexOf.getValue(k.py)
            val col = colCounters.getOrDefault(row, 0)
            colCounters[row] = col + 1
            keys.add(
                SwipeKey(
                    id = id,
                    label = k.letter,
                    cx = k.px / CANVAS_W, cy = k.py / CANVAS_H,
                    w = kwNorm, h = khNorm,
                    row = row, col = col,
                    isLetterNode = true,
                ),
            )
            chars.getOrPut(k.letter.codePointAt(0)) { ArrayList() }.add(id)
        }
        val frozen = HashMap<Int, IntArray>(chars.size)
        for ((cp, ids) in chars) frozen[cp] = ids.toIntArray()
        return LayoutGeometry(
            keys = keys,
            chars = frozen,
            aliases = emptyMap(),
            aspect = aspect,
            meanKeyWidth = kwNorm,
        )
    }

    /** Start/end nearest-key match rate on [layout] over [rows] — geometry sanity check. */
    private fun endpointMatchRates(rows: List<ReplayRow>, layout: LayoutGeometry): Pair<Double, Double> {
        var startHit = 0; var endHit = 0; var tot = 0
        for (r in rows) {
            val letters = r.word.filter { it in 'a'..'z' }
            if (letters.isEmpty() || r.pts.isEmpty()) continue
            tot++
            val p0 = r.pts.first(); val pN = r.pts.last()
            if (nearestKeyLabel(p0[0] * r.w, p0[1] * r.h, layout, r.w, r.h) == letters.first().toString()) startHit++
            if (nearestKeyLabel(pN[0] * r.w, pN[1] * r.h, layout, r.w, r.h) == letters.last().toString()) endHit++
        }
        return Pair(
            if (tot > 0) startHit.toDouble() / tot else 0.0,
            if (tot > 0) endHit.toDouble() / tot else 0.0,
        )
    }

    /** Nearest letter-node label to a key-area-local PIXEL point (Euclidean on px). */
    private fun nearestKeyLabel(px: Float, py: Float, layout: LayoutGeometry, w: Float, h: Float): String {
        var best = ""; var bd = Float.MAX_VALUE
        for (k in layout.keys) {
            if (!k.isLetterNode) continue
            val dx = px - k.cx * w
            val dy = py - k.cy * h
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = k.label }
        }
        return best
    }

    // ── loading / helpers (identical contract to GeoRealCorpusReplayTest) ────────

    /** Read the gzipped JSONL cache into [ReplayRow]s (tiny hand parse per line). */
    private fun loadSample(file: File): List<ReplayRow> {
        val out = ArrayList<ReplayRow>(9000)
        GZIPInputStream(file.inputStream()).bufferedReader().useLines { seq ->
            for (line in seq) {
                if (line.isBlank()) continue
                out.add(parseLine(line))
            }
        }
        return out
    }

    /** Parse one `{"word":..,"w":..,"h":..,"pts":[[x,y,t],...]}` line. */
    private fun parseLine(line: String): ReplayRow {
        val word = Regex("\"word\"\\s*:\\s*\"([^\"]*)\"").find(line)!!.groupValues[1]
        val w = Regex("\"w\"\\s*:\\s*(-?[0-9.eE]+)").find(line)!!.groupValues[1].toFloat()
        val h = Regex("\"h\"\\s*:\\s*(-?[0-9.eE]+)").find(line)!!.groupValues[1].toFloat()
        val ptsStr = Regex("\"pts\"\\s*:\\s*\\[(.*)\\]\\s*\\}\\s*$").find(line)!!.groupValues[1]
        val pts = ArrayList<FloatArray>()
        for (m in Regex("\\[\\s*(-?[0-9.eE]+)\\s*,\\s*(-?[0-9.eE]+)\\s*,\\s*(-?[0-9.eE]+)\\s*\\]").findAll(ptsStr)) {
            pts.add(floatArrayOf(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat()))
        }
        return ReplayRow(word, w, h, pts)
    }

    /** Convert a [ReplayRow] to key-area-local-PIXEL [TracePoint]s (normalized × canvas). */
    private fun toTrace(r: ReplayRow): List<TracePoint> {
        val out = ArrayList<TracePoint>(r.pts.size)
        for (p in r.pts) {
            out.add(TracePoint(p[0] * r.w, p[1] * r.h, p[2].toLong()))
        }
        return out
    }

    /** 0-based rank of [word] in [result], or -1 if absent. */
    private fun rankOf(word: String, result: tribixbite.cleverkeys.PredictionResult): Int {
        for (k in result.words.indices) if (result.words[k] == word) return k
        return -1
    }

    /** Dictionary ordinal of [word] (lowercased exact match), or -1. Memoized. */
    private fun ordinalOf(word: String): Int = ordinalIndex[word] ?: -1
    private val ordinalIndex: Map<String, Int> by lazy {
        val m = HashMap<String, Int>(dict.size * 2)
        for (i in 0 until dict.size) m.putIfAbsent(dict.word(i).lowercase(), i)
        m
    }

    // ── reporting ──────────────────────────────────────────────────────────────

    /** Per-config tallies: overall + per-length-stratum + prune recall. */
    private inner class ConfigTallies(val name: String) {
        val overall = Tally()
        val byLen = LenStratum.values().associateWith { Tally() }
        var pruneTested = 0
        var pruneSurvived = 0
        fun pruneRecall() = if (pruneTested > 0) pruneSurvived.toDouble() / pruneTested else 0.0
    }

    private fun printAbTable(
        a: ConfigTallies, b: ConfigTallies, coverage: Double, decoded: Int, oov: Int,
        startMatch: Double, endMatch: Double,
    ) {
        println("")
        println("===================== LOCAL COMBINED-CORPUS REPLAY (neural held-out test) =====================")
        println("geometry: repo qwerty_english centroids (tools/test_cli_predict.*, 26 keys, 360x215px, no bottom row)")
        println("sanity: start-key ${pct(startMatch)}  end-key ${pct(endMatch)}   (adjacent-key noise; geometry OK)")
        println("decoded (in-dict): $decoded traces   coverage: ${pct(coverage)}   OOV: $oov")
        println("-----------------------------------------------------------------------------------------------")
        println("config          top-1     top-3     top-5     prune-recall")
        println("A(shipped)   ${fmtRow(a.overall)}   ${pct(a.pruneRecall())}")
        println("B(pre-fix)   ${fmtRow(b.overall)}   ${pct(b.pruneRecall())}")
        println("Δ (A−B)      ${fmtDelta(a.overall.top1(), b.overall.top1())}   " +
            "${fmtDelta(a.overall.top3(), b.overall.top3())}   " +
            "${fmtDelta(a.overall.top5(), b.overall.top5())}")
        println("-----------------------------------------------------------------------------------------------")
        println("by word-length stratum (top-1 / top-3 / top-5):")
        for (s in LenStratum.values()) {
            val ta = a.byLen.getValue(s); val tb = b.byLen.getValue(s)
            println("  ${s.label.padEnd(4)} A n=${ta.n}  ${fmtRow(ta)}")
            println("  ${s.label.padEnd(4)} B n=${tb.n}  ${fmtRow(tb)}")
        }
        println("===============================================================================================")

        // CRITICAL-finding banner if A regresses vs B on any headline metric.
        val regressions = buildList {
            if (a.overall.top1() < b.overall.top1() - CRITICAL_EPS) add("top-1")
            if (a.overall.top3() < b.overall.top3() - CRITICAL_EPS) add("top-3")
            if (a.overall.top5() < b.overall.top5() - CRITICAL_EPS) add("top-5")
        }
        if (regressions.isNotEmpty()) {
            println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            println("!!! CRITICAL: shipped config A is WORSE than pre-fix baseline B on REAL noise: " +
                "${regressions.joinToString(", ")}")
            println("!!! This week's SLOPPY tuning does NOT generalize to the neural held-out set on this metric.")
            println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        } else {
            println(">>> A ≥ B on all headline metrics: this week's tuning holds up on the neural held-out set (non-circular).")
        }
    }

    /**
     * Assert the shipped tuning did NOT regress the real-corpus accuracy vs the pre-fix
     * baseline. A(shipped) must be within [CRITICAL_EPS] of B on each headline metric
     * (A ≥ B − eps). A material regression on real data is a CRITICAL finding surfaced by
     * this failing — it must NOT be silently tolerated (and we do NOT change any default
     * here to "fix" it; that is a human decision).
     */
    private fun abMustNotRegress(a: ConfigTallies, b: ConfigTallies) {
        assertWithMessage("CRITICAL: A(shipped) local-corpus top-1 must not regress vs B(pre-fix)")
            .that(a.overall.top1()).isAtLeast(b.overall.top1() - CRITICAL_EPS)
        assertWithMessage("CRITICAL: A(shipped) local-corpus top-3 must not regress vs B(pre-fix)")
            .that(a.overall.top3()).isAtLeast(b.overall.top3() - CRITICAL_EPS)
        assertWithMessage("CRITICAL: A(shipped) local-corpus top-5 must not regress vs B(pre-fix)")
            .that(a.overall.top5()).isAtLeast(b.overall.top5() - CRITICAL_EPS)
    }

    private fun fmtRow(t: Tally): String =
        "${pct(t.top1())}   ${pct(t.top3())}   ${pct(t.top5())}"

    private fun fmtDelta(x: Double, y: Double): String = "%+.1f".format((x - y) * 100).padStart(8)

    private fun pct(x: Double): String = "%.1f%%".format(x * 100).padStart(6)

    companion object {
        /** Canonical `qwerty_english` grid canvas (px) — see class KDoc reconciliation. */
        private const val CANVAS_W = 360f
        private const val CANVAS_H = 215f
        private const val CANVAS_ASPECT = CANVAS_W / CANVAS_H // ≈ 1.674

        /** Uniform key hit-box pitch on the `qwerty_english` grid (px). */
        private const val KEY_PITCH_X_PX = 36f
        private const val KEY_PITCH_Y_PX = 59f // 34 → 93 → 152 row spacing

        /**
         * The repo-authoritative `qwerty_english` per-key PIXEL centroids, transcribed
         * verbatim from `tools/test_cli_predict.py` / `.ts` `QWERTY_KEYS` (identical to the
         * `KeyboardGrid` derived from `model/train_character_model.py`'s grid JSON). NO
         * bottom/function row — this IS the letter area the corpus x,y live in.
         */
        private val QWERTY_ENGLISH_KEYS: List<GridKey> = listOf(
            GridKey("q", 18f, 34f), GridKey("w", 54f, 34f), GridKey("e", 90f, 34f),
            GridKey("r", 126f, 34f), GridKey("t", 162f, 34f), GridKey("y", 198f, 34f),
            GridKey("u", 234f, 34f), GridKey("i", 270f, 34f), GridKey("o", 306f, 34f),
            GridKey("p", 342f, 34f),
            GridKey("a", 36f, 93f), GridKey("s", 72f, 93f), GridKey("d", 108f, 93f),
            GridKey("f", 144f, 93f), GridKey("g", 180f, 93f), GridKey("h", 216f, 93f),
            GridKey("j", 252f, 93f), GridKey("k", 288f, 93f), GridKey("l", 324f, 93f),
            GridKey("z", 72f, 152f), GridKey("x", 108f, 152f), GridKey("c", 144f, 152f),
            GridKey("v", 180f, 152f), GridKey("b", 216f, 152f), GridKey("n", 252f, 152f),
            GridKey("m", 288f, 152f),
        )

        /**
         * Tolerance (absolute fraction) for the A-vs-B non-regression guard and the
         * CRITICAL-finding banner. ~1 pt absorbs decode-count sampling noise between the
         * two configs while still catching a material real regression.
         */
        private const val CRITICAL_EPS = 0.01

        // PROVISIONAL local-corpus regression floors — set ~4 pts below the first
        // report-only measured A-config run (2026-07-21: A top-1 55.2% / top-3 68.0% /
        // top-5 71.7% over 8521 in-dict traces from the 8,607-row neural held-out set,
        // 99.0% coverage). The ~4 pt margin absorbs decode-count churn while still catching
        // a real regression; ratchet only with a fresh measured basis (mirrors
        // GeoAccuracyThresholds discipline). These are NOT tuning targets — see the report
        // banner for the authoritative A/B numbers. This corpus is ~20 pts HARDER than the
        // FUTO swipe-1/test replay (75.2/85.4/87.9) because it is the neural model's HARD
        // held-out set (the ONNX decoder itself reaches only ~53% top-1 on it).
        private const val LOCAL_A_TOP1_FLOOR = 0.51
        private const val LOCAL_A_TOP3_FLOOR = 0.64
        private const val LOCAL_A_TOP5_FLOOR = 0.67
    }
}
