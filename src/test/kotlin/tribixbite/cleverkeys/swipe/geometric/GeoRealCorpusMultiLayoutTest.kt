package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assume
import org.junit.Test

/**
 * REAL-CORPUS REPLAY on NON-QWERTY layouts — the regime the geometric engine exists
 * for, and the DIRECT real-world test of the synthetic Dvorak KNOWN PARTIAL.
 *
 * The sibling [GeoRealCorpusReplayTest] validated QWERTY-en against real FUTO swipes
 * (config `swipe-1`). But English-QWERTY never routes to this engine in production —
 * the transformer owns it. This test replays REAL human swipes on the layouts the
 * geometric engine is actually FOR: Dvorak (en), AZERTY (fr), QWERTZ + German (de),
 * Spanish (es), from the `swipe-5` `train` split of `futo-org/swipe.futo.org` (MIT),
 * which carries per-row `layout` / `language` / `dual_finger` columns.
 *
 * ## The headline question (spec As-Built "SLOPPY-tier fix", OQ-8)
 * Synthetic SLOPPY measured Dvorak top-3 = **77.0%**, ~1 pt below the shared 0.78
 * floor — the documented KNOWN PARTIAL, tracked as OQ-8 (a CLEAN-safe direction/tangent
 * channel at higher weight). That gap was measured against OUR synthetic noise. Does
 * REAL Dvorak noise confirm the gap, beat the floor, or fall below?
 *  - Real-Dvorak A top-3 **≥ 78%** ⇒ the synthetic SLOPPY tier OVERSTATES Dvorak
 *    difficulty; OQ-8 urgency drops (real users are not in the sub-floor regime).
 *  - Real-Dvorak A top-3 **< 78%** ⇒ OQ-8 is confirmed real-world-relevant.
 * The verdict is printed LOUDLY and recorded in the spec As-Built subsection.
 *
 * ## Gating (default suite + CI both skip cleanly) — mirrors [GeoRealCorpusReplayTest]
 *  - `-PgeoFull` (`System.getProperty("geoFull") == "true"`, bridged in build.gradle) —
 *    five layouts × thousands of real traces × 2 configs is far over the < 90 s budget.
 *  - `Assume.assumeTrue(anyCorpusPresent)` — the corpus DATA is LOCAL-ONLY (never
 *    committed). Regenerate with `node scripts/fetch_futo_multilayout_sample.mjs`;
 *    absent → SKIPPED, so a clean checkout / CI never fails for a missing local cache.
 *    Each layout also self-skips independently if its own cache file is missing.
 *
 * Run: `sh gradlew runPureTests -PtestClass=swipe.geometric.GeoRealCorpusMultiLayoutTest -PgeoFull=true`
 *
 * ## Geometry source (DOCUMENTED, per spec deliverable)
 * The replay [LayoutGeometry] for each layout is built from the OFFICIAL FUTO
 * `swipe-5/layouts/<layout>.json` (linked from the dataset card), committed verbatim at
 * `src/test/resources/layouts/futo_<layout>.json` — a per-key centroid+radius geometry
 * over the normalized [0,1]² letter area (3 rows, 26–29 keys incl. `'` / ä ö ü / ñ, NO
 * bottom row; the corpus x,y are already normalized over exactly this canvas). This is
 * the geometry FUTO's own decoder used, so the replay is faithful to the data frame.
 *
 * ## Coverage vs projection failure (reported SEPARATELY)
 * A sample word is decoded ONLY if it is BOTH (a) present in the layout's dictionary AND
 * (b) projectable onto the layout ([LayoutProjection.project] != null). Category (b)
 * matters for German `ß` (no ß key on german/qwertz, and ß has no NFD decomposition — it
 * is untypeable, so ß-words are counted+reported, NOT crashed) and French ligatures
 * (`œ`/`æ`). Accented letters (é/ä/ñ) DO project via the engine's NFD/alias tiers, so
 * they are kept. Both the OOV rate and the projection-failure rate are printed per layout.
 */
class GeoRealCorpusMultiLayoutTest {

    // ── the five corpora (spec mapping: layout → dictionary language) ────────────

    /** One layout to replay: cache-file base name, official geometry, dictionary. */
    private data class Corpus(
        val layout: String,
        val lang: String,
        val dict: () -> GeometricDictionary,
    )

    private val corpora = listOf(
        Corpus("dvorak", "en") { GeoTestFixtures.englishCkdt() },
        Corpus("azerty", "fr") { GeoTestFixtures.frenchCkdt() },
        Corpus("qwertz", "de") { GeoTestFixtures.germanCkdt() },
        Corpus("german", "de") { GeoTestFixtures.germanCkdt() },
        Corpus("spanish", "es") { GeoTestFixtures.spanishCkdt() },
    )

    private fun cacheDir(): File {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        return if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
    }

    private fun corpusFile(layout: String) = File(cacheDir(), "futo_swipe5_$layout.jsonl.gz")
    private fun layoutJson(layout: String) = File("src/test/resources/layouts/futo_$layout.json")

    // ── A/B configs (identical to GeoRealCorpusReplayTest — the whole point) ──────

    /** Config A — the SHIPPED defaults (this week's SLOPPY-tier tuning). */
    private val configA = GeometricEngineConfig()

    /** Config B — the PRE-FIX baseline: the three tuned levers reverted. */
    private val configB = GeometricEngineConfig(
        endpointInsetKw = 0f,
        directionPenaltyWeight = 0f,
        maxCandidatesScored = 800,
    )

    // ── the replay ───────────────────────────────────────────────────────────────

    @Test
    fun realCorpusReplay_multiLayout_abReport() {
        // Gate 1: full-grid flag (heavy — five layouts × thousands of decodes × 2 configs).
        if (System.getProperty("geoFull") != "true") {
            println("[skip] FUTO multi-layout real-corpus replay — set -PgeoFull=true to run")
            return
        }
        // Gate 2: at least one corpus cache must exist (never committed).
        val present = corpora.filter { corpusFile(it.layout).exists() }
        Assume.assumeTrue(
            "No FUTO swipe-5 caches found under ${cacheDir().absolutePath}; regenerate with " +
                "`node scripts/fetch_futo_multilayout_sample.mjs` (skipping)",
            present.isNotEmpty(),
        )

        val results = LinkedHashMap<String, LayoutResult>()
        for (c in corpora) {
            val file = corpusFile(c.layout)
            if (!file.exists()) {
                println("[replay] ${c.layout}: cache missing (${file.name}) — skipped")
                continue
            }
            assertWithMessage("committed FUTO layout json for ${c.layout}")
                .that(layoutJson(c.layout).exists()).isTrue()
            results[c.layout] = replayLayout(c)
        }

        printOverallSummary(results)
        printDvorakVerdict(results["dvorak"])

        // ── assertions (added after the report-only first run; see companion) ───────
        for ((layout, r) in results) {
            val floor = FLOORS[layout] ?: continue
            assertWithMessage("$layout real-corpus A top-1 (PROVISIONAL floor)")
                .that(r.a.overall.top1()).isAtLeast(floor.top1)
            assertWithMessage("$layout real-corpus A top-3 (PROVISIONAL floor)")
                .that(r.a.overall.top3()).isAtLeast(floor.top3)
            assertWithMessage("$layout real-corpus A top-5 (PROVISIONAL floor)")
                .that(r.a.overall.top5()).isAtLeast(floor.top5)
            // Non-circularity: shipped tuning must NOT regress vs pre-fix on real noise.
            abMustNotRegress(layout, r.a, r.b)
        }
    }

    /** Full A/B replay of one layout's corpus. */
    private fun replayLayout(c: Corpus): LayoutResult {
        val dict = c.dict()
        val rows = loadSample(corpusFile(c.layout))
        val geoKeys = parseFutoKeys(layoutJson(c.layout).readText())

        // Dictionary index (lowercased surface form → ordinal) for coverage + prune recall.
        val ordinalIndex = HashMap<String, Int>(dict.size * 2)
        for (i in 0 until dict.size) ordinalIndex.putIfAbsent(dict.word(i).lowercase(), i)

        // Build a base layout once (aspect only rescales d_kw's vertical term; the char
        // maps/keys are aspect-independent) to probe projectability of each sample word.
        val probeLayout = buildFutoLayout(geoKeys, 2.0f)

        // Partition sample words into decode / OOV / projection-failure buckets.
        var oov = 0
        var projFail = 0
        val projFailChars = HashMap<Int, Int>() // untypeable codepoint → count
        val decodeRows = ArrayList<ReplayRow>(rows.size)
        for (r in rows) {
            val ord = ordinalIndex[r.word]
            if (ord == null) { oov++; continue }
            if (LayoutProjection.project(r.word, probeLayout) == null) {
                projFail++
                for (cp in offendingCodepoints(r.word, probeLayout)) {
                    projFailChars[cp] = (projFailChars[cp] ?: 0) + 1
                }
                continue
            }
            decodeRows.add(r)
        }
        val coverage = if (rows.isNotEmpty()) decodeRows.size.toDouble() / rows.size else 0.0

        // Group decode rows by aspect bucket (rounded to 0.1) — one warmed engine/index
        // per bucket per config, bounding index churn (matches GeoRealCorpusReplayTest).
        val byBucket = LinkedHashMap<Float, MutableList<ReplayRow>>()
        for (r in decodeRows) {
            val bucket = Math.round((r.w / r.h) * 10f) / 10f
            byBucket.getOrPut(bucket) { ArrayList() }.add(r)
        }

        val a = ConfigTallies("A(shipped)")
        val b = ConfigTallies("B(pre-fix)")
        for ((bucket, bucketRows) in byBucket) {
            val layout = buildFutoLayout(geoKeys, bucket)
            val engineA = GeometricSwipeEngine(configA).also { it.warmUp(layout, dict) }
            val engineB = GeometricSwipeEngine(configB).also { it.warmUp(layout, dict) }
            val prepA = GesturePreprocessor(configA)
            val prunerA = CandidatePruner(configA)
            val indexA = TemplateIndex.build(layout, dict, configA, TemplateGenerator(configA))
            val prepB = GesturePreprocessor(configB)
            val prunerB = CandidatePruner(configB)
            val indexB = TemplateIndex.build(layout, dict, configB, TemplateGenerator(configB))
            for (r in bucketRows) {
                val ordinal = ordinalIndex[r.word] ?: -1
                val trace = toTrace(r)
                if (trace.size < 3) continue
                decodeInto(a, r, trace, layout, dict, engineA, prepA, prunerA, indexA, ordinal)
                decodeInto(b, r, trace, layout, dict, engineB, prepB, prunerB, indexB, ordinal)
            }
        }

        val result = LayoutResult(
            layout = c.layout, lang = c.lang,
            totalRows = rows.size, decoded = decodeRows.size,
            oov = oov, projFail = projFail, projFailChars = projFailChars,
            coverage = coverage, a = a, b = b,
        )
        printLayoutTable(result)
        return result
    }

    /** Decode one trace under a config's engine and record top-K + prune survival. */
    private fun decodeInto(
        tallies: ConfigTallies,
        row: ReplayRow,
        trace: List<TracePoint>,
        layout: LayoutGeometry,
        dict: GeometricDictionary,
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

        // Prune recall (survivor shortlist contains the true ordinal): attributes a miss
        // to pruning vs scoring, matching the synthetic harness's pruneRecall.
        if (ordinal >= 0) {
            val gesture = prep.process(trace, w, h, layout)
            if (gesture.pathLengthKw > 0f) {
                val survivors = pruner.prune(gesture, index, layout)
                tallies.pruneTested++
                for (o in survivors) if (o == ordinal) { tallies.pruneSurvived++; break }
            }
        }
    }

    // ── FUTO geometry builder (generalized from GeoRealCorpusReplayTest) ─────────

    /**
     * Build the replay [LayoutGeometry] for a given [aspect] from a parsed official FUTO
     * per-key geometry. Each key's normalized centroid (cx, cy) + half-extents (rx, ry)
     * over the [0,1]² letter area is laid out row-major (by cy then cx). Because the JSON
     * already carries FINAL normalized centroids, the geometry is constructed directly
     * (the [LayoutGeometry.Builder] is for UNITS-space layouts — here the coordinates are
     * already normalized, so a direct construction is exact). NO bottom row is appended.
     *
     * Generalizations vs the QWERTY-only builder:
     *  - Handles 26–29 keys (dvorak/azerty add a `'` key; german adds ä/ö/ü; spanish ñ).
     *  - `isLetterNode = Character.isLetter(codepoint)` per key (so the `'` key is a
     *    non-letter node yet still registered into `chars`, letting contractions project).
     *    The 26 base letters keep `letterNodeCount >= 2` (the dead-layout guard).
     *  - EVERY key's folded codepoint is registered into `chars`, so ä/ö/ü/ñ resolve as
     *    tier-1 centers and `'` resolves for contractions.
     */
    private fun buildFutoLayout(keysJson: List<FutoKey>, aspect: Float): LayoutGeometry {
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
            val kw = k.rx * 2f
            val kh = k.ry * 2f
            val cp = k.letter.codePointAt(0)
            val folded = LayoutProjection.foldCodepoint(cp)
            keys.add(
                SwipeKey(
                    id = id,
                    label = k.letter,
                    cx = k.cx, cy = k.cy, w = kw, h = kh,
                    row = row, col = col,
                    isLetterNode = Character.isLetter(cp),
                ),
            )
            chars.getOrPut(folded) { ArrayList() }.add(id)
            letterWidthSum += kw
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

    /**
     * Parse a `futo_<layout>.json` file (a fixed, trusted, machine-generated repo
     * resource with a flat numeric shape — a hand-rolled regex parser, no JSON dep). The
     * `letter` value may be a non-letter (`'`) or a non-ASCII letter (ä/ö/ü/ñ); the regex
     * captures any non-quote run.
     */
    private fun parseFutoKeys(json: String): List<FutoKey> {
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
        require(out.size in 20..40) { "expected 20–40 FUTO keys, parsed ${out.size}" }
        return out
    }

    /** The codepoints of [word] that fail to project onto [layout] (for the report). */
    private fun offendingCodepoints(word: String, layout: LayoutGeometry): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (LayoutProjection.projectCodepoint(LayoutProjection.foldCodepoint(cp), layout) == null) {
                out.add(cp)
            }
        }
        return out
    }

    // ── loading / helpers ────────────────────────────────────────────────────────

    /** One replay row: canonical word + canvas px extents + normalized trace points. */
    private data class ReplayRow(val word: String, val w: Float, val h: Float, val pts: List<FloatArray>)

    /** Length stratum by codepoint count (apostrophes stripped for the bucket). */
    private enum class LenStratum(val label: String) { SHORT_2_3("2-3"), MID_4_6("4-6"), LONG_7_PLUS("7+") }

    private fun lenStratumOf(word: String): LenStratum {
        val n = word.replace("'", "").codePointCount(0, word.replace("'", "").length)
        return when {
            n <= 3 -> LenStratum.SHORT_2_3
            n <= 6 -> LenStratum.MID_4_6
            else -> LenStratum.LONG_7_PLUS
        }
    }

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
        for (p in r.pts) out.add(TracePoint(p[0] * r.w, p[1] * r.h, p[2].toLong()))
        return out
    }

    /** 0-based rank of [word] in [result], or -1 if absent. */
    private fun rankOf(word: String, result: tribixbite.cleverkeys.PredictionResult): Int {
        for (k in result.words.indices) if (result.words[k] == word) return k
        return -1
    }

    // ── tallies / reporting ────────────────────────────────────────────────────

    /** Mutable top-K tally; `add(rank)` records a decode outcome. */
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

    /** Per-config tallies: overall + per-length-stratum + prune recall. */
    private class ConfigTallies(val name: String) {
        val overall = Tally()
        val byLen = LenStratum.values().associateWith { Tally() }
        var pruneTested = 0
        var pruneSurvived = 0
        fun pruneRecall() = if (pruneTested > 0) pruneSurvived.toDouble() / pruneTested else 0.0
    }

    /** Everything the report + assertions need for one layout. */
    private class LayoutResult(
        val layout: String,
        val lang: String,
        val totalRows: Int,
        val decoded: Int,
        val oov: Int,
        val projFail: Int,
        val projFailChars: Map<Int, Int>,
        val coverage: Double,
        val a: ConfigTallies,
        val b: ConfigTallies,
    )

    private fun printLayoutTable(r: LayoutResult) {
        println("")
        println("================= FUTO REAL-CORPUS REPLAY: ${r.layout}→${r.lang} (swipe-5/train) =================")
        println("sample: ${r.totalRows} rows   decoded: ${r.decoded}   OOV: ${r.oov}   " +
            "proj-fail: ${r.projFail}   coverage: ${pct(r.coverage)}")
        if (r.projFailChars.isNotEmpty()) {
            val top = r.projFailChars.entries.sortedByDescending { it.value }.take(6)
                .joinToString(" ") { "U+%04X('%s')=%d".format(it.key, String(Character.toChars(it.key)), it.value) }
            println("proj-fail codepoints (untypeable on layout): $top")
        }
        println("------------------------------------------------------------------------------------------")
        println("config          top-1     top-3     top-5     prune-recall")
        println("A(shipped)   ${fmtRow(r.a.overall)}   ${pct(r.a.pruneRecall())}")
        println("B(pre-fix)   ${fmtRow(r.b.overall)}   ${pct(r.b.pruneRecall())}")
        println("Δ (A−B)      ${fmtDelta(r.a.overall.top1(), r.b.overall.top1())}   " +
            "${fmtDelta(r.a.overall.top3(), r.b.overall.top3())}   " +
            "${fmtDelta(r.a.overall.top5(), r.b.overall.top5())}")
        println("------------------------------------------------------------------------------------------")
        println("by word-length stratum (top-1 / top-3 / top-5):")
        for (s in LenStratum.values()) {
            val ta = r.a.byLen.getValue(s); val tb = r.b.byLen.getValue(s)
            println("  ${s.label.padEnd(4)} A n=${ta.n}  ${fmtRow(ta)}")
            println("  ${s.label.padEnd(4)} B n=${tb.n}  ${fmtRow(tb)}")
        }
        println("==========================================================================================")
        printRegressionBanner(r.layout, r.a, r.b)
    }

    private fun printOverallSummary(results: Map<String, LayoutResult>) {
        if (results.isEmpty()) return
        println("")
        println("################## FUTO MULTI-LAYOUT REPLAY — OVERALL A/B SUMMARY ##################")
        println("layout   lang  decoded  cov     A t1/t3/t5           B t1/t3/t5           Δt3    recall A/B")
        for ((_, r) in results) {
            val a = r.a.overall; val b = r.b.overall
            println(
                "${r.layout.padEnd(8)} ${r.lang.padEnd(4)} ${r.decoded.toString().padStart(6)}  " +
                    "${pct(r.coverage)}  " +
                    "${pct(a.top1())}/${pct(a.top3())}/${pct(a.top5())}   " +
                    "${pct(b.top1())}/${pct(b.top3())}/${pct(b.top5())}   " +
                    "${fmtDelta(a.top3(), b.top3())}  ${pct(r.a.pruneRecall())}/${pct(r.b.pruneRecall())}",
            )
        }
        println("###################################################################################")
    }

    /**
     * THE HEADLINE ANALYSIS — real-Dvorak A top-3 vs the synthetic SLOPPY KNOWN PARTIAL
     * (77.0% measured, 0.78 floor). States the OQ-8 implication explicitly.
     */
    private fun printDvorakVerdict(dvorak: LayoutResult?) {
        if (dvorak == null) {
            println("[verdict] Dvorak corpus absent — no real-world OQ-8 verdict this run.")
            return
        }
        val realTop3 = dvorak.a.overall.top3()
        println("")
        println(">>>>>>>>>>>>>>>>>>>>>>>>>> DVORAK REAL-CORPUS VERDICT (OQ-8) <<<<<<<<<<<<<<<<<<<<<<<<<<")
        println(">>> synthetic SLOPPY Dvorak top-3 = 77.0% (the KNOWN PARTIAL, ~1 pt below the 0.78 floor)")
        println(">>> REAL Dvorak A(shipped) top-3   = ${pct(realTop3)}  (n=${dvorak.a.overall.n} in-dict real swipes)")
        if (realTop3 >= 0.78) {
            println(">>> VERDICT: real Dvorak top-3 >= 78% — the synthetic SLOPPY tier OVERSTATES Dvorak")
            println(">>>          difficulty. Real users are NOT in the sub-floor regime; OQ-8 URGENCY DROPS")
            println(">>>          (the direction-channel scorer gap is a synthetic-noise artifact, not a")
            println(">>>          real-world defect). OQ-8 stays a tracked follow-up, de-prioritized.")
        } else {
            println(">>> VERDICT: real Dvorak top-3 < 78% — OQ-8 is CONFIRMED REAL-WORLD-RELEVANT. The")
            println(">>>          synthetic sub-floor gap reproduces on real swipes; the CLEAN-safe")
            println(">>>          direction/tangent channel (OQ-8) remains a genuine quality closer.")
        }
        println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    }

    /** CRITICAL-finding banner if A regresses vs B on any headline metric. */
    private fun printRegressionBanner(layout: String, a: ConfigTallies, b: ConfigTallies) {
        val regressions = buildList {
            if (a.overall.top1() < b.overall.top1() - CRITICAL_EPS) add("top-1")
            if (a.overall.top3() < b.overall.top3() - CRITICAL_EPS) add("top-3")
            if (a.overall.top5() < b.overall.top5() - CRITICAL_EPS) add("top-5")
        }
        if (regressions.isNotEmpty()) {
            println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            println("!!! CRITICAL ($layout): shipped config A is WORSE than pre-fix baseline B on REAL " +
                "noise: ${regressions.joinToString(", ")}")
            println("!!! This week's SLOPPY tuning does NOT generalize to real swipes on this layout/metric.")
            println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        } else {
            println(">>> $layout: A >= B on all headline metrics — tuning holds up on REAL noise (non-circular).")
        }
    }

    /** Assert the shipped tuning did NOT regress the real-corpus accuracy vs pre-fix. */
    private fun abMustNotRegress(layout: String, a: ConfigTallies, b: ConfigTallies) {
        assertWithMessage("CRITICAL: $layout A(shipped) real-corpus top-1 must not regress vs B(pre-fix)")
            .that(a.overall.top1()).isAtLeast(b.overall.top1() - CRITICAL_EPS)
        assertWithMessage("CRITICAL: $layout A(shipped) real-corpus top-3 must not regress vs B(pre-fix)")
            .that(a.overall.top3()).isAtLeast(b.overall.top3() - CRITICAL_EPS)
        assertWithMessage("CRITICAL: $layout A(shipped) real-corpus top-5 must not regress vs B(pre-fix)")
            .that(a.overall.top5()).isAtLeast(b.overall.top5() - CRITICAL_EPS)
    }

    private fun fmtRow(t: Tally): String = "${pct(t.top1())}   ${pct(t.top3())}   ${pct(t.top5())}"
    private fun fmtDelta(x: Double, y: Double): String = "%+.1f".format((x - y) * 100).padStart(8)
    private fun pct(x: Double): String = "%.1f%%".format(x * 100).padStart(6)

    /** PROVISIONAL per-layout regression floors (top-1/top-3/top-5). */
    private class Floor(val top1: Double, val top3: Double, val top5: Double)

    companion object {
        /**
         * Tolerance (absolute fraction) for the A-vs-B non-regression guard and the
         * CRITICAL-finding banner. ~1 pt absorbs decode-count sampling noise while still
         * catching a material real regression (mirrors GeoRealCorpusReplayTest).
         */
        private const val CRITICAL_EPS = 0.01

        /**
         * PROVISIONAL per-layout real-corpus regression floors — set ~4 pts below the
         * report-only measured A-config run (2026-07-20, `-PgeoFull`; the authoritative
         * A/B table + coverage lives in the spec As-Built "Real-corpus replay —
         * multi-layout" subsection). The ~4 pt margin absorbs corpus-resample /
         * decode-count churn while still catching a real regression; ratchet only with a
         * fresh measured basis (mirrors the GeoAccuracyThresholds / GeoRealCorpusReplayTest
         * discipline). These are NOT tuning targets — the report banner + the hard
         * A ≥ B − 1pt guard ([abMustNotRegress]) carry the authoritative non-circularity
         * check.
         *
         * Measured A (top-1/top-3/top-5), floors are ~A−4 pt rounded down:
         *   dvorak  76.8/79.9/80.4 → 0.72/0.75/0.76   (4-row FUTO geometry, dense; recall 83%)
         *   azerty  78.2/91.1/94.2 → 0.74/0.87/0.90
         *   qwertz  77.3/88.7/91.3 → 0.73/0.84/0.87
         *   german  71.8/82.5/85.2 → 0.67/0.78/0.81
         *   spanish 73.4/86.1/88.5 → 0.69/0.82/0.84
         */
        private val FLOORS: Map<String, Floor> = mapOf(
            "dvorak" to Floor(0.72, 0.75, 0.76),
            "azerty" to Floor(0.74, 0.87, 0.90),
            "qwertz" to Floor(0.73, 0.84, 0.87),
            "german" to Floor(0.67, 0.78, 0.81),
            "spanish" to Floor(0.69, 0.82, 0.84),
        )
    }
}
