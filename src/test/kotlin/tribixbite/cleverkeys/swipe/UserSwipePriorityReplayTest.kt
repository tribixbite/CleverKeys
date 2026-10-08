package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.SwipePriority
import tribixbite.cleverkeys.UserWordFrequency
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcPriorityBonus
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder

/**
 * User swipe priority through the SHIPPED CTC stack (real featurizer, real ONNX encoder, real
 * beam): how far a per-word final-score bonus must go before the hard words of the
 * 2026-10-07 reports (`ad`, `wet`, `adb`, `somethings`) win their own traces, and what the
 * boosted word then takes from its rivals' traces. Measurement + pins for
 * `docs/eval/2026-10-08-user-swipe-priority.md`.
 */
class UserSwipePriorityReplayTest {

    private fun assumeOrt() {
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path are set",
            CtcReplayEngine.ortAvailable(),
        )
    }

    /** The decoder a user with [target] in the personal dictionary at [bonus] decodes with. */
    private fun decoderFor(engine: CtcReplayEngine, target: String, bonus: Double): CtcSwipeDecoder {
        val merged = CtcLexiconMerge.merge(
            engine.baseLexiconPairs(), listOf(target to UserWordFrequency.DEFAULT), emptySet())
        return engine.decoderWithLexicon(
            merged, topK = engine.scoringParams.beamWidth,
            priority = CtcPriorityBonus(mapOf(target to bonus)),
        )
    }

    // ── measurement instrument ─────────────────────────────────────────────────────

    /**
     * For each target and shape: the target's rank as a plain personal-dictionary word (bonus
     * 0) and the bonus it needs to reach rank 1 (`top1.final − target.final`, ≤ 0 when it
     * already wins; NaN when it is not in the final beam at all — no bonus can help then).
     * Then, at each swept level, the rival traces whose winner flips to the boosted target.
     */
    @Test
    fun prioritySweepDiagnostic() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            for (target in TARGETS.keys) {
                val decoder = decoderFor(engine, target, 0.0)
                for ((label, shape) in shapes(target, layout)) {
                    val (x, y, t) = shape
                    val c = decoder.decode(x, y, t)
                    println("[PRIO] target %-10s %-22s rank=%2d needs=%7.3f top3=%s".format(
                        target, label, rankOf(c, target), needed(c, target),
                        c.take(3).map { "%s:%.2f".format(it.word, it.finalScore) }))
                }
            }
            for ((target, rivals) in TARGETS) {
                for (level in LEVELS) {
                    val decoder = decoderFor(engine, target, level)
                    val flipped = ArrayList<String>()
                    var total = 0
                    for (rival in rivals) for ((label, shape) in shapes(rival, layout)) {
                        val (x, y, t) = shape
                        val c = decoder.decode(x, y, t)
                        total++
                        if (c.firstOrNull()?.word == target) flipped += "$rival/$label"
                    }
                    println("[PRIO] boost %-10s b=%.1f rival traces lost %d/%d %s".format(
                        target, level, flipped.size, total, flipped))
                }
            }
        }
    }

    // ── pins ───────────────────────────────────────────────────────────────────────

    /**
     * The chosen levels do what the eval note says, on the shipped stack and every shape:
     * `somethings` wins as a plain personal-dictionary word (Normal), `ad` and `wet` at High,
     * `adb` at Highest. A better encoder may make a LOWER level enough — that would still pass.
     */
    @Test
    fun raisedTargetsWinTheirTracesAtTheirLevel() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            for ((target, level) in LEVEL_PINS) {
                val decoder = decoderFor(engine, target, UserSwipePriorityBonus.ctcBonus(level))
                for ((label, shape) in shapes(target, layout)) {
                    val (x, y, t) = shape
                    val c = decoder.decode(x, y, t)
                    assertWithMessage("$target at $level, $label: slate ${c.take(3).map { it.word }}")
                        .that(c.first().word).isEqualTo(target)
                }
            }
        }
    }

    /**
     * A user who raises nothing decodes exactly as before: the adapter's table is
     * [CtcPriorityBonus.NONE] and a decode through it equals one without the parameter, on
     * every shape of every rival word (the words whose swipes a raise could otherwise take).
     */
    @Test
    fun nothingRaisedDecodesExactlyAsBefore() {
        assumeOrt()
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val base = engine.baseLexiconPairs()
            val merged = CtcLexiconMerge.merge(base, TARGETS.keys.map { it to UserWordFrequency.DEFAULT }, emptySet())
            val table = UserSwipePriorityBonus.ctcBySurface(
                TARGETS.keys.map { it to UserWordFrequency.DEFAULT }, emptyMap()) { it }
            assertThat(table).isSameInstanceAs(CtcPriorityBonus.NONE)
            val plain = engine.decoderWithLexicon(merged, topK = engine.scoringParams.beamWidth)
            val viaTable = engine.decoderWithLexicon(merged, topK = engine.scoringParams.beamWidth, priority = table)
            for (word in TARGETS.values.flatten().distinct()) for ((label, shape) in shapes(word, layout)) {
                val (x, y, t) = shape
                assertWithMessage("$word $label").that(viaTable.decode(x, y, t)).isEqualTo(plain.decode(x, y, t))
            }
        }
    }

    /** Bonus [target] needs to reach rank 1 (≤ 0 = already first); NaN when absent from the beam. */
    private fun needed(c: List<CtcCandidate>, target: String): Double {
        val t = c.firstOrNull { it.word == target } ?: return Double.NaN
        return c.first().finalScore - t.finalScore
    }

    /** 1-based rank of [word] in [c], or -1. */
    private fun rankOf(c: List<CtcCandidate>, word: String): Int =
        c.indexOfFirst { it.word == word }.let { if (it < 0) -1 else it + 1 }

    /** The deterministic shape set: straight/eased with and without the lift sample, plus wobbles. */
    private fun shapes(word: String, layout: CtcLayout) = listOf(
        "straight" to CtcTraceShapes.straight(word, layout),
        "straight+lift" to CtcTraceShapes.withLift(CtcTraceShapes.straight(word, layout)),
        "straight+stop200+lift" to CtcTraceShapes.withLift(CtcTraceShapes.straight(word, layout), 200.0),
        "eased+lift" to CtcTraceShapes.withLift(CtcTraceShapes.eased(word, layout)),
        "wobble .02" to CtcTraceShapes.wobbled(word, layout, 0.02, 5.0),
        "wobble .035" to CtcTraceShapes.wobbled(word, layout, 0.035, 4.0),
    )

    private companion object {
        /** Target → the rival words whose own traces a boost of the target may steal. */
        val TARGETS = linkedMapOf(
            "ad" to listOf("as", "an", "and", "at", "add", "sad"),
            "wet" to listOf("we", "wt", "were", "west", "yet", "set"),
            "adb" to listOf("an", "and", "ab", "am", "sad"),
            "somethings" to listOf("something", "things"),
        )

        /** The level each target needs on the shipped stack (eval note §2/§4). */
        val LEVEL_PINS = linkedMapOf(
            "somethings" to SwipePriority.NORMAL,
            "ad" to SwipePriority.HIGH,
            "wet" to SwipePriority.HIGH,
            "adb" to SwipePriority.HIGHEST,
        )

        /** Final-score bonus levels swept (nats). */
        val LEVELS = listOf(0.0, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0)
    }
}
