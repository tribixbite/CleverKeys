package tribixbite.cleverkeys.swipe.geometric

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * TEST-ONLY shared loader for the LOCAL combined-English-swipes corpus (the retired
 * neural model's held-out test set, ~8.6k real en/QWERTY swipes) and its pinned
 * `qwerty_english` pixel geometry.
 *
 * Extracted from [GeoLocalCorpusReplayTest] (which now delegates here) so the wave-G
 * OQ sweep instrument ([GeoOqSweepTest]) replays EXACTLY the same rows on EXACTLY the
 * same layout as the official A/B replay gate — a sweep number and a gate number are
 * directly comparable because they share this single loading path.
 *
 * Carries no `Test` suffix so `TestRunnerListDriftTest` skips it (test hygiene).
 * Purity: `kotlin.*` + `java.io`/`java.util.zip` only (local file read).
 *
 * The corpus cache is LOCAL-ONLY (never committed): regenerate with
 * `node scripts/build_local_corpus_replay.mjs`. Callers must `Assume`/skip when
 * [cacheFile] is absent so clean checkouts and CI never fail.
 */
object GeoLocalCorpus {

    /** Canonical `qwerty_english` grid canvas (px) — see [GeoLocalCorpusReplayTest] KDoc. */
    const val CANVAS_W = 360f
    const val CANVAS_H = 215f
    const val CANVAS_ASPECT = CANVAS_W / CANVAS_H // ≈ 1.674

    /** Uniform key hit-box pitch on the `qwerty_english` grid (px). */
    const val KEY_PITCH_X_PX = 36f
    const val KEY_PITCH_Y_PX = 59f // 34 → 93 → 152 row spacing

    /** One `qwerty_english` key: letter + PIXEL centroid (from the repo grid tables). */
    data class GridKey(val letter: String, val px: Float, val py: Float)

    /** One replay row: canonical word + canvas px extents + normalized trace points. */
    data class Row(
        val word: String,
        val w: Float,
        val h: Float,
        val pts: List<FloatArray>, // each = [x, y, t] with x,y normalized [0,1]
    )

    /** LOCAL-ONLY gzipped JSONL cache (regenerate via scripts/build_local_corpus_replay.mjs). */
    val cacheFile: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        val dir = if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
        File(dir, "combined_english_swipes.jsonl.gz")
    }

    /**
     * Repo-authoritative `qwerty_english` per-key pixel centroids loaded from the
     * pinned test fixture extracted from the retired neural CLI.
     */
    val QWERTY_ENGLISH_KEYS: List<GridKey> by lazy {
        val fixture = File("src/test/resources/layouts/qwerty_english_pixels.csv")
        check(fixture.isFile) { "missing qwerty_english pixel fixture: $fixture" }
        fixture.readLines().filter { it.isNotBlank() }.mapIndexed { index, line ->
            val fields = line.split(',')
            check(fields.size == 3) { "bad qwerty fixture row ${index + 1}: $line" }
            GridKey(fields[0], fields[1].toFloat(), fields[2].toFloat())
        }.also { keys ->
            check(keys.size == 26) { "qwerty fixture must contain 26 keys, got ${keys.size}" }
            check(keys.map { it.letter }.joinToString("") == "qwertyuiopasdfghjklzxcvbnm") {
                "qwerty fixture letter order drifted"
            }
        }
    }

    /** Read the gzipped JSONL cache into [Row]s (tiny hand parse per line). */
    fun load(file: File = cacheFile): List<Row> {
        val out = ArrayList<Row>(9000)
        GZIPInputStream(file.inputStream()).bufferedReader().useLines { seq ->
            for (line in seq) {
                if (line.isBlank()) continue
                out.add(parseLine(line))
            }
        }
        return out
    }

    /** Parse one `{"word":..,"w":..,"h":..,"pts":[[x,y,t],...]}` line. */
    private fun parseLine(line: String): Row {
        val word = Regex("\"word\"\\s*:\\s*\"([^\"]*)\"").find(line)!!.groupValues[1]
        val w = Regex("\"w\"\\s*:\\s*(-?[0-9.eE]+)").find(line)!!.groupValues[1].toFloat()
        val h = Regex("\"h\"\\s*:\\s*(-?[0-9.eE]+)").find(line)!!.groupValues[1].toFloat()
        val ptsStr = Regex("\"pts\"\\s*:\\s*\\[(.*)\\]\\s*\\}\\s*$").find(line)!!.groupValues[1]
        val pts = ArrayList<FloatArray>()
        for (m in Regex("\\[\\s*(-?[0-9.eE]+)\\s*,\\s*(-?[0-9.eE]+)\\s*,\\s*(-?[0-9.eE]+)\\s*\\]").findAll(ptsStr)) {
            pts.add(floatArrayOf(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat()))
        }
        return Row(word, w, h, pts)
    }

    /** Convert a [Row] to key-area-local-PIXEL [TracePoint]s (normalized × canvas). */
    fun toTrace(r: Row): List<TracePoint> {
        val out = ArrayList<TracePoint>(r.pts.size)
        for (p in r.pts) {
            out.add(TracePoint(p[0] * r.w, p[1] * r.h, p[2].toLong()))
        }
        return out
    }

    /**
     * Build the replay [LayoutGeometry] for a given [aspect] from the repo-authoritative
     * `qwerty_english` PIXEL centroids ([QWERTY_ENGLISH_KEYS]). Pixel centroids are
     * normalized to [0,1] over the 360×215 canvas (matching the loader's normalized pts);
     * key hit-boxes use the uniform grid pitch (36 px wide, 59 px tall). Row/col are
     * assigned row-major by (py, px) so ids are deterministic. NO bottom/function row is
     * appended (the corpus canvas IS the letter area — same as the FUTO builder).
     */
    fun buildQwertyEnglishLayout(aspect: Float): LayoutGeometry {
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
}
