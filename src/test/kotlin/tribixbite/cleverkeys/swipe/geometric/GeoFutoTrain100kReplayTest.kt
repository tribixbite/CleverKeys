package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assume
import org.junit.Test

/**
 * FUTO swipe-1/TRAIN 100k head-to-head runner (2026-07-23) — the GEOMETRIC half of the
 * clean-timestamp re-measurement (the local 8.5k corpus has corrupt t → the prior
 * head-to-head ran neural position-only; this corpus has real ~60 Hz timestamps).
 *
 * EVAL-ONLY, report-only (no floors): decodes every in-dict trace of
 * `futo_train100k.jsonl.gz` (seeded random sample of swipe-1/train — see
 * scripts/fetch_futo_train100k.mjs, seed recorded there) under the SHIPPED config and
 * writes PER-TRACE results (in-dict index, word, top-10 predictions) to
 * `geo_futo100k.jsonl` in the cache dir for the metrics join
 * (scripts/futo100k_metrics.py) with the neural harness's --out file.
 *
 * Gating mirrors [GeoRealCorpusReplayTest]: -PgeoFull + cache-file existence.
 * Optional env FUTO100K_LIMIT=N decodes only the first N in-dict traces (sanity gate).
 *
 * Run: `sh gradlew runPureTests -PtestClass=swipe.geometric.GeoFutoTrain100kReplayTest -PgeoFull=true`
 */
class GeoFutoTrain100kReplayTest {

    private val cacheDir: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
    }
    // Input corpus: defaults to the 100k train sample; override via FUTO100K_IN for the
    // 2,400-row test-split head-to-head (test2400_ordered.jsonl.gz, same {word,w,h,pts} shape).
    private val sampleFile = File(cacheDir, System.getenv("FUTO100K_IN") ?: "futo_train100k.jsonl.gz")
    private val outFile: File = run {
        val name = System.getenv("FUTO100K_OUT") ?: "geo_futo100k.jsonl"
        File(cacheDir, name)
    }

    private val futoLayoutJson = File("src/test/resources/layouts/futo_qwerty.json")
    private val dict = GeoTestFixtures.englishCkdt()
    private val config = GeometricEngineConfig() // SHIPPED defaults only

    private data class ReplayRow(
        val idx: Int, // index within the IN-DICT sequence (the neural join key)
        val word: String,
        val w: Float,
        val h: Float,
        val pts: List<FloatArray>,
    )

    @Test
    fun futoTrain100k_geoDecode() {
        if (System.getProperty("geoFull") != "true") {
            println("[skip] FUTO train-100k replay — set -PgeoFull=true to run")
            return
        }
        Assume.assumeTrue(
            "sample not found at ${sampleFile.absolutePath}; run scripts/fetch_futo_train100k.mjs",
            sampleFile.exists(),
        )
        assertWithMessage("committed FUTO layout json must exist")
            .that(futoLayoutJson.exists()).isTrue()

        // Load + in-dict filter (IDENTICAL predicate to the neural harness: lowercase word
        // ∈ the 98,140-word en dictionary; bin and json word sets verified identical).
        val dictWords = HashSet<String>(dict.size * 2)
        for (i in 0 until dict.size) dictWords.add(dict.word(i).lowercase())

        // GEO_ALL_ROWS=true: skip the in-dict filter and key every row by its ORIGINAL
        // file index, so the geo cache aligns 1:1 with the FUTO reference + neural caches
        // (which score every trace; OOV = miss). Used for the 2,400-row test-split
        // head-to-head. Default (false) preserves the 100k in-dict-sequence contract.
        val allRows = System.getenv("GEO_ALL_ROWS") == "true"
        var totalRows = 0
        val inDict = ArrayList<ReplayRow>(110_000)
        GZIPInputStream(sampleFile.inputStream()).bufferedReader().useLines { seq ->
            for (line in seq) {
                if (line.isBlank()) continue
                val origIdx = totalRows // 0-based index over non-blank input lines
                totalRows++
                val r = parseLine(line) ?: continue
                if (allRows) {
                    inDict.add(ReplayRow(origIdx, r.word, r.w, r.h, r.pts))
                } else if (dictWords.contains(r.word)) {
                    inDict.add(ReplayRow(inDict.size, r.word, r.w, r.h, r.pts))
                }
            }
        }
        val limit = System.getenv("FUTO100K_LIMIT")?.toIntOrNull() ?: 0
        val work = if (limit in 1 until inDict.size) inDict.subList(0, limit) else inDict
        println("[futo100k] rows=$totalRows kept=${inDict.size} " +
            "(coverage ${"%.1f".format(inDict.size * 100.0 / totalRows)}%) allRows=$allRows decoding=${work.size}")

        // Group by aspect bucket (0.1 rounding) but PRESERVE the row index in output.
        val byBucket = LinkedHashMap<Float, MutableList<ReplayRow>>()
        for (r in work) {
            val bucket = Math.round((r.w / r.h) * 10f) / 10f
            byBucket.getOrPut(bucket) { ArrayList() }.add(r)
        }
        println("[futo100k] aspect buckets: ${byBucket.entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key}=${it.value.size}" }}")

        // In allRows mode r.idx is the ORIGINAL file index (may exceed work.size if any
        // line failed to parse); size the output array to cover the max index.
        val results = arrayOfNulls<String>(if (allRows) totalRows else work.size)
        var decoded = 0
        var t1 = 0; var t3 = 0; var t5 = 0
        val t0 = System.currentTimeMillis()
        for ((bucket, bucketRows) in byBucket) {
            val layout = buildFutoLayout(bucket)
            val engine = GeometricSwipeEngine(config).also { it.warmUp(layout, dict) }
            for (r in bucketRows) {
                val trace = ArrayList<TracePoint>(r.pts.size)
                for (p in r.pts) trace.add(TracePoint(p[0] * r.w, p[1] * r.h, p[2].toLong()))
                if (trace.size < 3) { results[r.idx] = null; continue }
                val res = engine.decode(GeometricSwipeRequest(trace, r.w, r.h, layout, dict))
                decoded++
                val rank = res.words.indexOfFirst { it == r.word }
                if (rank == 0) t1++
                if (rank in 0..2) t3++
                if (rank in 0..4) t5++
                val preds = res.words.joinToString(",") { "\"" + it.replace("\"", "") + "\"" }
                results[r.idx] = "{\"idx\":${r.idx},\"word\":\"${r.word}\",\"preds\":[$preds]}"
            }
        }
        val ms = System.currentTimeMillis() - t0
        println("[futo100k] decoded=$decoded in ${ms}ms (${"%.2f".format(ms.toDouble() / decoded)} ms/trace)")
        println("[futo100k] GEO overall: top1=${"%.2f".format(t1 * 100.0 / decoded)}% " +
            "top3=${"%.2f".format(t3 * 100.0 / decoded)}% top5=${"%.2f".format(t5 * 100.0 / decoded)}%")

        outFile.printWriter().use { pw ->
            for (line in results) if (line != null) pw.println(line)
        }
        println("[futo100k] per-trace results -> ${outFile.absolutePath}")
    }

    // ── geometry / parsing (verbatim adaptations from GeoRealCorpusReplayTest) ──────

    private data class FutoKey(val letter: String, val cx: Float, val cy: Float, val rx: Float, val ry: Float)

    private val futoKeys: List<FutoKey> by lazy {
        val objRe = Regex(
            "\\{\\s*\"letter\"\\s*:\\s*\"([^\"]+)\"\\s*," +
                "\\s*\"cx\"\\s*:\\s*(-?[0-9.eE]+)\\s*," +
                "\\s*\"cy\"\\s*:\\s*(-?[0-9.eE]+)\\s*," +
                "\\s*\"rx\"\\s*:\\s*(-?[0-9.eE]+)\\s*," +
                "\\s*\"ry\"\\s*:\\s*(-?[0-9.eE]+)\\s*\\}",
        )
        val out = ArrayList<FutoKey>()
        for (m in objRe.findAll(futoLayoutJson.readText())) {
            out.add(FutoKey(m.groupValues[1], m.groupValues[2].toFloat(),
                m.groupValues[3].toFloat(), m.groupValues[4].toFloat(), m.groupValues[5].toFloat()))
        }
        require(out.size == 26) { "expected 26 FUTO letter keys, parsed ${out.size}" }
        out
    }

    private fun buildFutoLayout(aspect: Float): LayoutGeometry {
        val distinctCy = futoKeys.map { it.cy }.distinct().sorted()
        val rowIndexOf = HashMap<Float, Int>()
        distinctCy.forEachIndexed { i, cy -> rowIndexOf[cy] = i }
        val ordered = futoKeys.sortedWith(compareBy({ rowIndexOf.getValue(it.cy) }, { it.cx }))
        val keys = ArrayList<SwipeKey>(ordered.size)
        val chars = HashMap<Int, MutableList<Int>>()
        val colCounters = HashMap<Int, Int>()
        var letterWidthSum = 0f
        ordered.forEachIndexed { id, k ->
            val row = rowIndexOf.getValue(k.cy)
            val col = colCounters.getOrDefault(row, 0)
            colCounters[row] = col + 1
            keys.add(SwipeKey(id, k.letter, k.cx, k.cy, k.rx * 2f, k.ry * 2f, row, col, true))
            chars.getOrPut(k.letter.codePointAt(0)) { ArrayList() }.add(id)
            letterWidthSum += k.rx * 2f
        }
        val frozen = HashMap<Int, IntArray>(chars.size)
        for ((cp, ids) in chars) frozen[cp] = ids.toIntArray()
        return LayoutGeometry(keys, frozen, emptyMap(), aspect, letterWidthSum / keys.size)
    }

    private fun parseLine(line: String): ReplayRow? {
        val word = Regex("\"word\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: return null
        val w = Regex("\"w\"\\s*:\\s*(-?[0-9.eE]+)").find(line)?.groupValues?.get(1)?.toFloat() ?: return null
        val h = Regex("\"h\"\\s*:\\s*(-?[0-9.eE]+)").find(line)?.groupValues?.get(1)?.toFloat() ?: return null
        val ptsStr = Regex("\"pts\"\\s*:\\s*\\[(.*)\\]\\s*\\}\\s*$").find(line)?.groupValues?.get(1) ?: return null
        val pts = ArrayList<FloatArray>()
        for (m in Regex("\\[\\s*(-?[0-9.eE]+)\\s*,\\s*(-?[0-9.eE]+)\\s*,\\s*(-?[0-9.eE]+)\\s*\\]").findAll(ptsStr)) {
            pts.add(floatArrayOf(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat()))
        }
        return if (pts.size >= 3) ReplayRow(0, word, w, h, pts) else null
    }
}
