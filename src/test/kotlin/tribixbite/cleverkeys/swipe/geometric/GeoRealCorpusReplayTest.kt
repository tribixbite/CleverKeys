package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assume
import org.junit.Test

/**
 * REAL-CORPUS REPLAY validation — the pending NON-CIRCULAR check named in the spec's
 * As-Built Notes (`docs/specs/geometric-swipe-engine.md`) and `memory/todo.md`.
 *
 * Everything else in this suite tunes and asserts against OUR OWN synthetic noise
 * (`GeoTraceSynthesizer`), so a tuning win there could in principle be circular — the
 * synthesizer and the engine share assumptions. This test replays REAL HUMAN swipes
 * (FUTO `swipe.futo.org`, config `swipe-1`, held-out `test` split, MIT license) through
 * the SHIPPED engine and reports whether this week's SLOPPY-tier tuning
 * (`endpointInsetKw=0.30`, `directionPenaltyWeight=0.30`, `maxCandidatesScored=1200`)
 * helps on real noise — measured A/B against the pre-fix baseline
 * (`endpointInsetKw=0`, `directionPenaltyWeight=0`, `maxCandidatesScored=800`).
 *
 * ## Gating (default suite + CI both skip cleanly)
 *  - `-PgeoFull` (`System.getProperty("geoFull") == "true"`, bridged in build.gradle) —
 *    the replay decodes thousands of real traces against the full 98k dictionary
 *    UNDER BOTH configs, far heavier than the < 90 s default-suite budget.
 *  - `Assume.assumeTrue(sampleFile.exists())` — the corpus DATA is LOCAL-ONLY (never
 *    committed; MIT-licensed but large). Regenerate it with
 *    `node scripts/fetch_futo_replay_sample.mjs`; absent → the test is SKIPPED, so a
 *    clean checkout / CI never fails for a missing local cache.
 *
 * Run: `sh gradlew runPureTests -PtestClass=swipe.geometric.GeoRealCorpusReplayTest -PgeoFull=true`
 *
 * ## Geometry source (DOCUMENTED, per spec deliverable)
 * The replay [LayoutGeometry] is built from the OFFICIAL FUTO QWERTY layout definition
 * `swipe-5/layouts/qwerty.json` (linked from the dataset card), committed verbatim at
 * `src/test/resources/layouts/futo_qwerty.json` — a per-key centroid+radius geometry
 * over the normalized [0,1]² LETTER AREA (3 rows, 26 keys, NO bottom/function row; the
 * corpus x,y are already normalized over exactly this canvas). This is the geometry
 * FUTO's own decoder used, so the replay is faithful to the data's coordinate frame.
 * A uniform 3-row grid is NOT used — the official per-key rects are authoritative.
 *
 * ## Coordinate handling
 * Corpus x,y are normalized [0,1] over the letter-area canvas; `TracePoint` expects
 * key-area-local PIXELS, so each point is multiplied by the row's canvas w,h and the
 * engine is told `keyAreaWidthPx/HeightPx = (w, h)`. Because `d_kw` divides the
 * vertical delta by `aspect = w/h`, the geometry is built PER aspect bucket (rounded to
 * 0.1) and traces are processed grouped by bucket — one warmed engine+index per bucket,
 * bounding index churn without needing a large `indexCacheCapacity`.
 *
 * ## What is asserted
 * FIRST RUN was REPORT-ONLY (no accuracy floors). After observing the measured A-config
 * numbers, CONSERVATIVE regression floors ~3-4 pts below measured were added
 * (PROVISIONAL, mirroring [GeoAccuracyThresholds] discipline) so a future regression is
 * caught without brittleness. If config A were WORSE than B on real data that would be a
 * CRITICAL finding; the test prints a prominent banner and (see [abMustNotRegress])
 * asserts A ≥ B − a small margin so a real regression fails loudly.
 */
class GeoRealCorpusReplayTest {

    // ── corpus + geometry sources ────────────────────────────────────────────

    /** LOCAL-ONLY gzipped JSONL sample (regenerate via scripts/fetch_futo_replay_sample.mjs). */
    private val sampleFile: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        val dir = if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
        File(dir, "futo_swipe1_test_sample.jsonl.gz")
    }

    /** Committed official FUTO QWERTY layout definition. */
    private val futoLayoutJson = File("src/test/resources/layouts/futo_qwerty.json")

    /** Full shipped English dictionary (98,140 CKDT) — the SAME lexicon the synthetic
     *  QWERTY accuracy test decodes against; the sample only selects which words. */
    private val dict = GeoTestFixtures.englishCkdt()

    // ── A/B configs (the entire point) ────────────────────────────────────────

    /** Config A — the SHIPPED defaults (this week's SLOPPY-tier tuning). */
    private val configA = GeometricEngineConfig()

    /**
     * Config B — the PRE-FIX baseline: the three levers reverted to their pre-tuning
     * values (`endpointInsetKw=0` bit-identical no-op, `directionPenaltyWeight=0` off,
     * `maxCandidatesScored=800`). Every other knob stays at the shipped default so the
     * A/B delta isolates exactly the tuning under test.
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

    /** Length stratum by codepoint count (apostrophes stripped for the bucket). */
    private enum class LenStratum(val label: String) { SHORT_2_3("2-3"), MID_4_6("4-6"), LONG_7_PLUS("7+") }

    private fun lenStratumOf(word: String): LenStratum {
        val n = word.replace("'", "").length
        return when {
            n <= 3 -> LenStratum.SHORT_2_3
            n <= 6 -> LenStratum.MID_4_6
            else -> LenStratum.LONG_7_PLUS
        }
    }

    /** Mutable top-K tally; `+=` a decode outcome, `.acc()` snapshots fractions. */
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
    fun realCorpusReplay_abReport() {
        // Gate 1: full-grid flag (heavy — thousands of real decodes × 2 configs).
        if (System.getProperty("geoFull") != "true") {
            println("[skip] FUTO real-corpus replay — set -PgeoFull=true to run")
            return
        }
        // Gate 2: the local corpus cache must exist (never committed).
        Assume.assumeTrue(
            "FUTO sample not found at ${sampleFile.absolutePath}; regenerate with " +
                "`node scripts/fetch_futo_replay_sample.mjs` (skipping)",
            sampleFile.exists(),
        )
        assertWithMessage("committed FUTO layout json must exist")
            .that(futoLayoutJson.exists()).isTrue()

        val rows = loadSample(sampleFile)
        println("[replay] loaded ${rows.size} real traces from ${sampleFile.name}")

        // ── coverage: fraction of sample words present in the full en dictionary ──
        val dictWords = HashSet<String>(dict.size * 2)
        for (i in 0 until dict.size) dictWords.add(dict.word(i).lowercase())
        // The decode target must match a dictionary entry EXACTLY (the engine emits the
        // canonical form) — we report coverage on the lowercased surface form.
        val inDict = rows.filter { dictWords.contains(it.word) }
        val coverage = if (rows.isNotEmpty()) inDict.size.toDouble() / rows.size else 0.0
        println("[replay] dictionary coverage: ${inDict.size}/${rows.size} = ${pct(coverage)} " +
            "(decoding only in-dictionary words; OOV are Wikipedia proper nouns / rare terms)")

        // ── group in-dict rows by aspect bucket (rounded to 0.1) ──────────────────
        // Outlier canvases (aspect far outside the phone-portrait 1.8–3.0 band, e.g.
        // landscape/tablet 4.8/6.0) are decoded in their own buckets — the FUTO geometry
        // is normalized so aspect only rescales d_kw's vertical term; excluding them would
        // hide real data, so they stay (and land in their own bucket engines).
        val byBucket = LinkedHashMap<Float, MutableList<ReplayRow>>()
        for (r in inDict) {
            val bucket = Math.round((r.w / r.h) * 10f) / 10f
            byBucket.getOrPut(bucket) { ArrayList() }.add(r)
        }
        println("[replay] aspect buckets: ${byBucket.entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key}=${it.value.size}" }}")

        // ── A/B decode over every in-dict trace, per bucket ───────────────────────
        val a = ConfigTallies("A(shipped)")
        val b = ConfigTallies("B(pre-fix)")

        for ((bucket, bucketRows) in byBucket) {
            val layout = buildFutoLayout(bucket)
            // One warmed engine+index per config per bucket (index churn bounded to the
            // bucket count; no reliance on indexCacheCapacity across buckets).
            val engineA = GeometricSwipeEngine(configA).also { it.warmUp(layout, dict) }
            val engineB = GeometricSwipeEngine(configB).also { it.warmUp(layout, dict) }
            val prepA = GesturePreprocessor(configA)
            val prunerA = CandidatePruner(configA)
            val indexA = TemplateIndex.build(layout, dict, configA, TemplateGenerator(configA))
            val prepB = GesturePreprocessor(configB)
            val prunerB = CandidatePruner(configB)
            val indexB = TemplateIndex.build(layout, dict, configB, TemplateGenerator(configB))

            for (r in bucketRows) {
                val ordinal = ordinalOf(r.word) // -1 if not present (already filtered in-dict)
                val trace = toTrace(r)
                if (trace.size < 3) continue
                decodeInto(a, r, trace, layout, engineA, prepA, prunerA, indexA, ordinal)
                decodeInto(b, r, trace, layout, engineB, prepB, prunerB, indexB, ordinal)
            }
        }

        // ── report ────────────────────────────────────────────────────────────────
        printAbTable(a, b, coverage, inDict.size)

        // Context anchor: FUTO's paper (arXiv 2606.25247) measured a well-built SHARK2 at
        // ~80.05% top-1 on real QWERTY swipes. We are NOT the same pipeline (different
        // dictionary, no per-user calibration, no bottom-row/language-model rescoring), so
        // this is a REFERENCE POINT, not an assertion target.
        println("[replay] --- SHARK2 paper reference (arXiv 2606.25247): ~80.05% top-1 real QWERTY ---")
        println("[replay] our A top-1 real = ${pct(a.overall.top1())} " +
            "(gap to paper SHARK2 = ${"%+.1f".format((a.overall.top1() - 0.8005) * 100)} pts)")

        // ── regression floors (PROVISIONAL — ~3-4 pts below the first measured run) ──
        // Added AFTER the report-only first run (measured A: see the constants). These
        // guard against a silent real-corpus regression without being brittle.
        assertWithMessage("A(shipped) real-corpus overall top-1 (PROVISIONAL floor)")
            .that(a.overall.top1()).isAtLeast(REAL_A_TOP1_FLOOR)
        assertWithMessage("A(shipped) real-corpus overall top-3 (PROVISIONAL floor)")
            .that(a.overall.top3()).isAtLeast(REAL_A_TOP3_FLOOR)
        assertWithMessage("A(shipped) real-corpus overall top-5 (PROVISIONAL floor)")
            .that(a.overall.top5()).isAtLeast(REAL_A_TOP5_FLOOR)

        // ── the non-circular claim: shipped tuning must NOT regress vs pre-fix on REAL
        //    noise. A worse-than-baseline A is a CRITICAL finding — fail loudly rather
        //    than hide it. A small negative tolerance absorbs decode-count noise; a real
        //    regression (A materially below B) trips this.
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

        // Prune recall (survivor contains the true ordinal): attributes a miss to pruning
        // vs scoring, matching the synthetic harness's `pruneRecall`.
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

    // ── FUTO geometry builder ──────────────────────────────────────────────────

    /**
     * Build the replay [LayoutGeometry] for a given [aspect] from the committed official
     * FUTO QWERTY per-key geometry (`futo_qwerty.json`). The JSON gives each letter key's
     * normalized centroid (cx, cy) + half-extents (rx, ry) over the [0,1]² letter area;
     * we lay them out row-major (by cy then cx) so ids/rows/cols are deterministic, and
     * feed each as a single-key row of unit content — but because the JSON already
     * carries FINAL normalized centroids we bypass the width/shift stagger machinery and
     * construct the geometry directly (the Builder is for UNITS-space layouts; here the
     * coordinates are already normalized, so a direct construction is exact).
     *
     * NO bottom row is appended (the corpus canvas IS the letter area).
     */
    private fun buildFutoLayout(aspect: Float): LayoutGeometry {
        val keysJson = futoKeys // parsed once, cached
        // Row grouping by cy (3 distinct rows in the FUTO geometry); col by cx within row.
        val distinctCy = keysJson.map { it.cy }.distinct().sorted()
        val rowIndexOf = HashMap<Float, Int>()
        distinctCy.forEachIndexed { i, cy -> rowIndexOf[cy] = i }

        val ordered = keysJson.sortedWith(compareBy({ rowIndexOf.getValue(it.cy) }, { it.cx }))
        val keys = ArrayList<SwipeKey>(ordered.size)
        val chars = HashMap<Int, MutableList<Int>>()
        val colCounters = HashMap<Int, Int>()
        var letterWidthSum = 0f
        ordered.forEachIndexed { id, k ->
            val row = rowIndexOf.getValue(k.cy)
            val col = colCounters.getOrDefault(row, 0)
            colCounters[row] = col + 1
            val w = k.rx * 2f
            val h = k.ry * 2f
            keys.add(
                SwipeKey(
                    id = id,
                    label = k.letter,
                    cx = k.cx, cy = k.cy, w = w, h = h,
                    row = row, col = col,
                    isLetterNode = true,
                ),
            )
            chars.getOrPut(k.letter.codePointAt(0)) { ArrayList() }.add(id)
            letterWidthSum += w
        }
        val frozen = HashMap<Int, IntArray>(chars.size)
        for ((cp, ids) in chars) frozen[cp] = ids.toIntArray()
        val meanKw = letterWidthSum / keys.size
        return LayoutGeometry(
            keys = keys,
            chars = frozen,
            aliases = emptyMap(),
            aspect = aspect,
            meanKeyWidth = meanKw,
        )
    }

    /** One parsed FUTO key. */
    private data class FutoKey(val letter: String, val cx: Float, val cy: Float, val rx: Float, val ry: Float)

    /** Parse `futo_qwerty.json` once (a tiny hand-rolled parser — no JSON dep needed;
     *  the file is a fixed, trusted repo resource with a flat numeric shape). */
    private val futoKeys: List<FutoKey> by lazy { parseFutoKeys(futoLayoutJson.readText()) }

    private fun parseFutoKeys(json: String): List<FutoKey> {
        // Extract each { "letter": "x", "cx": .., "cy": .., "rx": .., "ry": .. } object.
        // Regex over the flat, trusted, machine-generated file (values are plain numbers).
        val objRe = Regex(
            "\\{\\s*\"letter\"\\s*:\\s*\"([^\"]+)\"\\s*," +
                "\\s*\"cx\"\\s*:\\s*(-?[0-9.eE]+)\\s*," +
                "\\s*\"cy\"\\s*:\\s*(-?[0-9.eE]+)\\s*," +
                "\\s*\"rx\"\\s*:\\s*(-?[0-9.eE]+)\\s*," +
                "\\s*\"ry\"\\s*:\\s*(-?[0-9.eE]+)\\s*\\}",
        )
        val out = ArrayList<FutoKey>()
        for (m in objRe.findAll(json)) {
            out.add(
                FutoKey(
                    letter = m.groupValues[1],
                    cx = m.groupValues[2].toFloat(),
                    cy = m.groupValues[3].toFloat(),
                    rx = m.groupValues[4].toFloat(),
                    ry = m.groupValues[5].toFloat(),
                ),
            )
        }
        require(out.size == 26) { "expected 26 FUTO letter keys, parsed ${out.size}" }
        return out
    }

    // ── loading / helpers ──────────────────────────────────────────────────────

    /** Read the gzipped JSONL sample into [ReplayRow]s (tiny hand parse per line). */
    private fun loadSample(file: File): List<ReplayRow> {
        val out = ArrayList<ReplayRow>(4096)
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

    private fun printAbTable(a: ConfigTallies, b: ConfigTallies, coverage: Double, decoded: Int) {
        println("")
        println("========================= FUTO REAL-CORPUS REPLAY (swipe-1/test) =========================")
        println("geometry: official FUTO qwerty.json (per-key rects, letter-area, no bottom row)")
        println("decoded (in-dict): $decoded traces   coverage: ${pct(coverage)}")
        println("------------------------------------------------------------------------------------------")
        println("config          top-1     top-3     top-5     prune-recall")
        println("A(shipped)   ${fmtRow(a.overall)}   ${pct(a.pruneRecall())}")
        println("B(pre-fix)   ${fmtRow(b.overall)}   ${pct(b.pruneRecall())}")
        println("Δ (A−B)      ${fmtDelta(a.overall.top1(), b.overall.top1())}   " +
            "${fmtDelta(a.overall.top3(), b.overall.top3())}   " +
            "${fmtDelta(a.overall.top5(), b.overall.top5())}")
        println("------------------------------------------------------------------------------------------")
        println("by word-length stratum (top-1 / top-3 / top-5):")
        for (s in LenStratum.values()) {
            val ta = a.byLen.getValue(s); val tb = b.byLen.getValue(s)
            println("  ${s.label.padEnd(4)} A n=${ta.n}  ${fmtRow(ta)}")
            println("  ${s.label.padEnd(4)} B n=${tb.n}  ${fmtRow(tb)}")
        }
        println("==========================================================================================")

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
            println("!!! This week's SLOPPY tuning does NOT generalize to real swipes on this metric.")
            println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        } else {
            println(">>> A ≥ B on all headline metrics: this week's tuning holds up on REAL noise (non-circular).")
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
        assertWithMessage("CRITICAL: A(shipped) real-corpus top-1 must not regress vs B(pre-fix)")
            .that(a.overall.top1()).isAtLeast(b.overall.top1() - CRITICAL_EPS)
        assertWithMessage("CRITICAL: A(shipped) real-corpus top-3 must not regress vs B(pre-fix)")
            .that(a.overall.top3()).isAtLeast(b.overall.top3() - CRITICAL_EPS)
        assertWithMessage("CRITICAL: A(shipped) real-corpus top-5 must not regress vs B(pre-fix)")
            .that(a.overall.top5()).isAtLeast(b.overall.top5() - CRITICAL_EPS)
    }

    private fun fmtRow(t: Tally): String =
        "${pct(t.top1())}   ${pct(t.top3())}   ${pct(t.top5())}"

    private fun fmtDelta(x: Double, y: Double): String = "%+.1f".format((x - y) * 100).padStart(8)

    private fun pct(x: Double): String = "%.1f%%".format(x * 100).padStart(6)

    companion object {
        /**
         * Tolerance (absolute fraction) for the A-vs-B non-regression guard and the
         * CRITICAL-finding banner. ~1 pt absorbs decode-count sampling noise between the
         * two configs while still catching a material real regression.
         */
        private const val CRITICAL_EPS = 0.01

        // PROVISIONAL real-corpus regression floors — set ~4 pts below the first
        // report-only measured A-config run (2026-07-20: A top-1 75.2% / top-3 85.4% /
        // top-5 87.9% over 3912 in-dict FUTO swipe-1/test traces, 97.8% coverage). The
        // ~4 pt margin absorbs corpus-resample / decode-count churn while still catching a
        // real regression; ratchet only with a fresh measured basis (mirrors the
        // GeoAccuracyThresholds discipline). These are NOT tuning targets — see the
        // report banner for the authoritative A/B numbers.
        private const val REAL_A_TOP1_FLOOR = 0.71
        private const val REAL_A_TOP3_FLOOR = 0.81
        private const val REAL_A_TOP5_FLOOR = 0.84
    }
}
