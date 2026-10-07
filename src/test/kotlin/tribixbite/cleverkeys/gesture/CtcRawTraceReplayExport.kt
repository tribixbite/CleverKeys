package tribixbite.cleverkeys

import android.util.Log
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Opt-in replay tool (NOT a regression test — the name deliberately does not end in `Test`,
 * so it is in neither runner list): drives the SHIPPED [ImprovedSwipeGestureRecognizer] over
 * corpus traces and writes the touch traces each engine would receive, for
 * `scripts/short_word_ctc_eval.py dump-trace` to decode. This is how the short-word fix's
 * integrated numbers are measured against the eval note's emulations
 * (`docs/eval/2026-10-07-short-word-ctc.md` §7).
 *
 * Input: `short_word_ctc_eval.py export-rows` JSONL (`{"k","w","pts":[[x,y,t_ms],…]}` in the
 * letter-box frame). Every trace is scaled to the same device-pixel box the Python emulation
 * assumes for the 1.26 px noise threshold (1000 × 470 px), replayed through
 * startSwipe/addPoint on an injected clock that returns each sample's own timestamp, lifted
 * at the last sample (position and time), and written back in the letter-box frame:
 *
 *  - `<out>.ctc.jsonl`    — [ImprovedSwipeGestureRecognizer.ctcTrace] (what CTC featurizes now)
 *  - `<out>.smooth.jsonl` — the smoothed path + timestamps (what CTC featurized before)
 *
 * Run: `CK_REPLAY_IN=rows.jsonl CK_REPLAY_OUT=prefix scripts/gradle-guard.sh runMockTests
 * -PtestClass=CtcRawTraceReplayExport`. Without both variables it is skipped.
 */
class CtcRawTraceReplayExport {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkObject(Config.Companion)
        val config = mockk<Config>(relaxed = true)
        // Shipped defaults for the pre-processing being replayed; key-registration gates are
        // irrelevant (no keys are offered — only the path is exported).
        config.swipe_smoothing_window = Defaults.SWIPE_SMOOTHING_WINDOW
        config.swipe_noise_threshold = Defaults.SWIPE_NOISE_THRESHOLD
        config.swipe_min_distance = Defaults.SWIPE_MIN_DISTANCE
        every { Config.globalConfig() } returns config
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun export() {
        val input = System.getenv("CK_REPLAY_IN")
        val out = System.getenv("CK_REPLAY_OUT")
        assumeTrue("set CK_REPLAY_IN and CK_REPLAY_OUT to run the replay export", input != null && out != null)
        var now = 0L
        var rows = 0
        val recognizer = ImprovedSwipeGestureRecognizer(clock = { now })
        File("$out.ctc.jsonl").bufferedWriter().use { ctcOut ->
            File("$out.smooth.jsonl").bufferedWriter().use { smoothOut ->
                File(input!!).forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val row = JSONObject(line)
                    val pts = row.getJSONArray("pts")
                    fun x(i: Int) = pts.getJSONArray(i).getDouble(0).toFloat() * BOX_W_PX
                    fun y(i: Int) = pts.getJSONArray(i).getDouble(1).toFloat() * BOX_H_PX
                    fun t(i: Int) = pts.getJSONArray(i).getLong(2)
                    now = t(0)
                    recognizer.startSwipe(x(0), y(0), null)
                    for (i in 1 until pts.length()) {
                        now = t(i)
                        recognizer.addPoint(x(i), y(i), null)
                    }
                    val last = pts.length() - 1
                    now = t(last)
                    recognizer.recordLift(x(last), y(last))
                    val trace = recognizer.ctcTrace()!!
                    ctcOut.write(rowJson(row, trace.points.map { it.x to it.y }, trace.timestamps))
                    smoothOut.write(rowJson(row, recognizer.getSwipePath().map { it.x to it.y }, recognizer.getTimestamps()))
                    recognizer.reset()
                    // MockK records every globalConfig() call; thousands of traces exhaust the
                    // runner's heap unless the record is dropped (the stubs are kept).
                    if (++rows % 100 == 0) {
                        clearMocks(Config.Companion, answers = false, recordedCalls = true,
                            childMocks = false, verificationMarks = true, exclusionRules = false)
                    }
                }
            }
        }
    }

    private fun rowJson(row: JSONObject, points: List<Pair<Float, Float>>, times: List<Long>): String {
        val pts = JSONArray()
        points.forEachIndexed { i, (px, py) ->
            pts.put(JSONArray().put((px / BOX_W_PX).toDouble()).put((py / BOX_H_PX).toDouble()).put(times[i]))
        }
        return JSONObject().put("k", row.getString("k")).put("w", row.getString("w")).put("pts", pts).toString() + "\n"
    }

    private companion object {
        /** The eval script's device-pixel box for the noise threshold (`BOX_W_PX`, `BOX_H_PX`). */
        const val BOX_W_PX = 1000f
        const val BOX_H_PX = 470f
    }
}
