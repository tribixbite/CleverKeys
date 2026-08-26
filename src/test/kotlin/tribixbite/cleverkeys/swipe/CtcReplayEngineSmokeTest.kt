package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * Smoke test for [CtcReplayEngine]: the real shipped model must decode a real trace correctly,
 * AND the shape of the slate it produces must not drift without someone noticing.
 *
 * Without the first, a wiring mistake (wrong layout frame, wrong feature order, wrong score scale)
 * would surface as a quietly wrong REPLAY RESULT rather than a failure. Without the second, a
 * change to the shipped decode path silently invalidates the published evaluation — which has now
 * happened twice; see [slateShapeHasNotDrifted].
 */
class CtcReplayEngineSmokeTest {

    /** One real trace: the target word, its timestamps, and its normalized x/y. */
    private class Row(val word: String, val t: DoubleArray, val x: DoubleArray, val y: DoubleArray)

    private fun traceFile(): File = File(
        System.getProperty("user.home"), ".cache/cleverkeys-test/combined_english_swipes.jsonl.gz"
    )

    /**
     * The first [limit] usable rows of the local pool.
     *
     * Deliberately the FIRST N in file order rather than a sample: this fixture's job is to be
     * byte-identical between runs so a drift assertion means "the decoder changed", never "the
     * sample changed".
     */
    private fun loadRows(limit: Int): List<Row> {
        val rows = ArrayList<Row>(limit)
        java.util.zip.GZIPInputStream(traceFile().inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (rows.size >= limit) break
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
                rows.add(Row(word, t, x, y))
            }
        }
        return rows
    }

    private fun assumeRunnable() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + " +
                "onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
        Assume.assumeTrue(
            "no local trace pool at ${traceFile().path} (local-only, never committed) — skipping",
            traceFile().isFile,
        )
    }

    @Test
    fun theShippedModelDecodesGoldenCasesCorrectly() {
        assumeRunnable()
        // The golden fixture's `cases` are FEATURE-PARITY vectors (kind/name/points/features),
        // not word decodes, so they cannot smoke-test end-to-end accuracy. Real traces can.
        val rows = loadRows(SAMPLE)
        Assume.assumeTrue("no usable traces", rows.isNotEmpty())

        CtcReplayEngine.build("en").use { engine ->
            var top1 = 0
            for (row in rows) {
                val slate = engine.decode(row.x, row.y, row.t)
                if (slate.words.firstOrNull() == row.word) top1++
                if (top1 <= 3) {
                    println("[ctc-smoke] '${row.word}' -> ${slate.words.take(3)} " +
                        "scores=${slate.scores.take(3)}")
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

    /**
     * DECODER-DRIFT CANARY — the shape of the slate, not its correctness.
     *
     * ## Why this exists
     *
     * `docs/eval/2026-08-22-context-rescoring-first-replay.md` measures whether context rescoring
     * should be enabled by default. Its entire result depends on **how far behind the runner-up
     * sits**, because `SwipeContextRescorer`'s rank-1 guard is the ratio test
     * `scores[i] >= R_MIN * scores[0]`. A candidate below that line is arithmetically
     * un-promotable; one above it is a promotion the guard permits.
     *
     * That distribution has now been silently rewritten **twice** by changes to the shipped decode
     * path, and both times the evaluation kept publishing numbers that no longer described the
     * shipping code:
     *
     *  - `20d620f4` added `CtcFuzzyRescue` at rank two with a score of `topScore / 2`, moving the
     *    measured median runner-up/top-1 ratio **0.254 -> 0.500** and the fraction of slates
     *    inside the guard's factor of two from **24% to 54%**.
     *  - `c83d6ff2` moved the rescue BELOW the beam, reverting both to ~0.26 / ~25%.
     *
     * Neither change failed a test. Nothing failed at all — the numbers just quietly stopped being
     * true. This test converts that into a red build.
     *
     * ## What a failure means
     *
     * **Not** that the decoder is wrong. It means the slate-shape distribution moved, so any
     * published rescoring result is now stale: **re-run the replay and re-baseline the eval doc
     * before quoting it**, then update [FRACTION_WITHIN_GUARD] with a note saying which commit
     * moved it and why.
     *
     * ## Limitation, stated rather than hidden
     *
     * This needs the local trace pool, which is never committed, so it SKIPS on a fresh checkout.
     * That is acceptable because the evaluation is written on a machine that has the corpus — the
     * canary guards the place where the stale numbers would actually be quoted. It is not a
     * substitute for a committed fixture, and if one ever exists this should move onto it.
     */
    @Test
    fun slateShapeHasNotDrifted() {
        assumeRunnable()
        val rows = loadRows(SAMPLE)
        Assume.assumeTrue("no usable traces", rows.isNotEmpty())

        CtcReplayEngine.build("en").use { engine ->
            val ratios = ArrayList<Double>(rows.size)
            for (row in rows) {
                val slate = engine.decode(row.x, row.y, row.t)
                val top = slate.scores.firstOrNull() ?: continue
                val runnerUp = slate.scores.getOrNull(1) ?: continue
                if (top > 0) ratios.add(runnerUp.toDouble() / top)
            }
            Assume.assumeTrue("no multi-candidate slates", ratios.isNotEmpty())

            val sorted = ratios.sorted()
            val median = sorted[sorted.size / 2]
            val within = ratios.count { it >= SwipeContextRescorer.R_MIN }
            val fraction = within.toDouble() / ratios.size
            println("[ctc-shape] n=${ratios.size} median=%.3f withinGuard=%d (%.1f%%)"
                .format(median, within, fraction * 100))

            // NOTE the parentheses: `.format()` binds to the LAST literal of a `+` chain, so
            // without them the placeholders below would print raw and the numbers would be lost —
            // in the one message that only ever renders when the test fails.
            assertWithMessage(
                ("SLATE SHAPE DRIFTED: %.1f%% of slates now have a runner-up within the rank-1 " +
                    "guard's factor of two (expected %.1f%% +- %.1f%%, median ratio %.3f). This is " +
                    "NOT necessarily a decoder bug — but it DOES mean the published context-" +
                    "rescoring evaluation is stale, because its result is a function of exactly " +
                    "this distribution. Re-run ContextRescoringReplayTest, re-baseline " +
                    "docs/eval/2026-08-22-context-rescoring-first-replay.md, then update " +
                    "FRACTION_WITHIN_GUARD here with the commit that moved it.")
                    .format(fraction * 100, FRACTION_WITHIN_GUARD * 100, TOLERANCE * 100, median)
            ).that(fraction).isWithin(TOLERANCE).of(FRACTION_WITHIN_GUARD)
        }
    }

    private companion object {
        /** Fixed sample size; the first N usable rows, so the fixture is stable across runs. */
        const val SAMPLE = 40

        /**
         * Fraction of slates whose runner-up sits at or above `R_MIN * top-1`, i.e. the share the
         * rank-1 guard would permit a promotion into.
         *
         * **Measured 6/40 = 0.150 at `37fa3832`** (median ratio 0.164), after `c83d6ff2` moved
         * fuzzy rescue below the beam. For scale, the same quantity was ~0.54 while `20d620f4`
         * inserted rescues at rank two — more than three times this, and far outside the band.
         *
         * When this legitimately changes, record the commit that moved it and why, the same way
         * this line does. A bare number edited without a reason is how a canary becomes noise.
         */
        const val FRACTION_WITHIN_GUARD = 0.15

        /**
         * Band. Wide enough to absorb a candidate or two moving on a 40-trace sample, far tighter
         * than the 30-point shift `20d620f4` caused — which is the size of drift that matters.
         */
        const val TOLERANCE = 0.10
    }
}
