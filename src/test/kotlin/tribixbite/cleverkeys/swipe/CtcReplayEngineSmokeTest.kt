package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * Smoke test for [CtcReplayEngine]: the real shipped model must decode a real trace correctly.
 *
 * Without this, a wiring mistake (wrong layout frame, wrong feature order, wrong score scale)
 * would surface as a quietly wrong REPLAY RESULT rather than a failure — and the replay's whole
 * job is to produce numbers someone will trust.
 *
 * Uses the committed golden fixture's own cases, so it needs no local corpus.
 */
class CtcReplayEngineSmokeTest {

    @Test
    fun theShippedModelDecodesGoldenCasesCorrectly() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + " +
                "onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
        val traceFile = File(
            System.getProperty("user.home"), ".cache/cleverkeys-test/combined_english_swipes.jsonl.gz"
        )
        Assume.assumeTrue(
            "no local trace pool at ${traceFile.path} (local-only, never committed) — skipping",
            traceFile.isFile,
        )

        // The golden fixture's `cases` are FEATURE-PARITY vectors (kind/name/points/features),
        // not word decodes, so they cannot smoke-test end-to-end accuracy. Real traces can.
        val rows = ArrayList<Triple<String, DoubleArray, Pair<DoubleArray, DoubleArray>>>()
        java.util.zip.GZIPInputStream(traceFile.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (rows.size >= 40) break
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val word = o.optString("word").lowercase()
                val pts = o.optJSONArray("pts") ?: continue
                if (word.isEmpty() || pts.length() < 3) continue
                val x = DoubleArray(pts.length()); val y = DoubleArray(pts.length())
                val t = DoubleArray(pts.length())
                for (i in 0 until pts.length()) {
                    val p = pts.getJSONArray(i)
                    x[i] = p.getDouble(0); y[i] = p.getDouble(1); t[i] = p.getDouble(2)
                }
                // ~47% of this corpus is a different format whose third column is not a
                // timestamp — see TraceCorpusQuality. Those decode to confident nonsense.
                if (!TraceCorpusQuality.hasUsableTimestamps(t)) continue
                rows.add(Triple(word, t, x to y))
            }
        }
        Assume.assumeTrue("no usable traces", rows.isNotEmpty())

        CtcReplayEngine.build("en").use { engine ->
            var top1 = 0
            for ((word, t, xy) in rows) {
                val slate = engine.decode(xy.first, xy.second, t)
                if (slate.words.firstOrNull() == word) top1++
                if (top1 <= 3) {
                    println("[ctc-smoke] '$word' -> ${slate.words.take(3)} scores=${slate.scores.take(3)}")
                }
            }
            println("[ctc-smoke] top-1 $top1/${rows.size}")
            assertWithMessage(
                "the shipped model must get most real traces right ($top1/${rows.size}). A low " +
                    "rate means the WIRING is wrong — layout frame, coordinate normalization, " +
                    "feature order or score scale — not that the model is bad."
            ).that(top1).isAtLeast((rows.size * 0.5).toInt())
        }
    }
}
