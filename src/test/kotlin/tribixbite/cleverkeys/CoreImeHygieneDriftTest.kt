package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift detection for core-IME hygiene invariants introduced by the P1/P2 remediation:
 *
 *  1. The swipe-commit path (InputCoordinator) must not swallow exceptions silently — the
 *     former empty `catch { // Silently catch }` is replaced by an explicit error log +
 *     state reset. This test locks that in so a future edit can't reintroduce a silent catch.
 *  2. Hot-path debug logs that can carry user-typed text (SuggestionHandler, Pointers,
 *     Autocapitalisation) must be gated behind `BuildConfig.ENABLE_VERBOSE_LOGGING` so PII
 *     never reaches release logcat. Every `Log.d(` occurrence in those files must be gated.
 *  3. PredictionCoordinator must not busy-wait (`Thread.sleep`) on the main thread waiting
 *     for neural-engine init — the CountDownLatch gate replaced that loop.
 *
 * Runs from the project root and scans source text, like GesturePrefAccessDriftTest.
 */
class CoreImeHygieneDriftTest {

    private val mainKotlin = File("src/main/kotlin")

    private fun source(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    @Test
    fun noSilentCatchOnSwipeCommitPath() {
        // WP9 R-1 step 6: the commit engine (incl. the swipe auto-insert path) now lives
        // solely in SuggestionHandler.onSuggestionSelected; InputCoordinator's divergent
        // engine was deleted. The invariant follows the engine.
        val handler = source("tribixbite/cleverkeys/SuggestionHandler.kt")
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")

        assertWithMessage(
            "The commit path must not silently swallow exceptions. The empty " +
                "'// Silently catch' handler was replaced with an explicit error " +
                "log + state reset; do not reintroduce it in either class."
        ).that(handler + coordinator).doesNotContain("Silently catch")

        assertWithMessage(
            "The commit-path catch must log the failure explicitly via " +
                "\"Error in onSuggestionSelected\" and reset selection-tracking state."
        ).that(handler).contains("Error in onSuggestionSelected")
        assertWithMessage(
            "The commit-path catch must reset expectingSelectionUpdate so a botched " +
                "commit cannot leave stale context (hardening ported from InputCoordinator)."
        ).that(handler).contains("contextTracker.expectingSelectionUpdate = false")
    }

    @Test
    fun hotPathDebugLogsAreGated() {
        // Files whose Log.d calls may carry user-typed text and therefore must all be gated.
        val piiSensitiveFiles = listOf(
            "tribixbite/cleverkeys/SuggestionHandler.kt",
            "tribixbite/cleverkeys/Pointers.kt",
            "tribixbite/cleverkeys/Autocapitalisation.kt",
        )
        val gateToken = "ENABLE_VERBOSE_LOGGING"
        val logDPattern = Regex("""\bLog\.d\s*\(""")
        // How far back to look for an enclosing `if (BuildConfig.ENABLE_VERBOSE_LOGGING) {` gate.
        val lookBackLines = 2

        val violations = mutableListOf<String>()
        piiSensitiveFiles.forEach { relative ->
            val lines = source(relative).lines()
            lines.forEachIndexed { idx, line ->
                if (!logDPattern.containsMatchIn(line)) return@forEachIndexed
                // Compliant if the token is on the same line (inline gate or the vlog helper),
                // or on one of the immediately preceding lines (an enclosing if-gate block).
                val sameLine = line.contains(gateToken)
                val gatedByBlock = (1..lookBackLines).any { back ->
                    val prev = idx - back
                    prev >= 0 && lines[prev].contains(gateToken)
                }
                if (!sameLine && !gatedByBlock) {
                    violations.add("$relative:${idx + 1}: ${line.trim()}")
                }
            }
        }

        assertWithMessage(
            "Every Log.d in hot-path/PII-sensitive files must be gated behind " +
                "BuildConfig.ENABLE_VERBOSE_LOGGING (use the vlog helper or an enclosing " +
                "if-gate). Ungated Log.d sites:\n" + violations.joinToString("\n")
        ).that(violations).isEmpty()
    }

    @Test
    fun noThreadSleepInPredictionCoordinator() {
        val text = source("tribixbite/cleverkeys/PredictionCoordinator.kt")

        assertWithMessage(
            "PredictionCoordinator must not busy-wait with Thread.sleep on the main thread; " +
                "the EngineInitGate CountDownLatch replaced the spin loop."
        ).that(text).doesNotContain("Thread.sleep")
    }

    /**
     * WP9 audit M-2 (2026-08-11): the geometric prewarm must stay in the runner's BACKGROUND
     * slot. If it regains `cancelAndSubmit`, an `onStartInputView` prewarm can once again
     * cancel an in-flight swipe decode — a silently lost swipe with no error path.
     */
    @Test
    fun geometricWarmUpUsesBackgroundTaskSlot() {
        val adapter = source("tribixbite/cleverkeys/swipe/GeometricEngineAdapter.kt")

        val warmUpBody = adapter.substringAfter("fun warmUpAsync(").substringBefore("fun shutdown(")
        assertWithMessage(
            "GeometricEngineAdapter.warmUpAsync must submit in the BACKGROUND slot " +
                "(tasks.submitBackground) so a prewarm can never cancel an in-flight decode."
        ).that(warmUpBody).contains("tasks.submitBackground")
        assertWithMessage(
            "warmUpAsync must NOT use the foreground cancelAndSubmit slot (audit M-2)."
        ).that(warmUpBody).doesNotContain("tasks.cancelAndSubmit")

        val decodeBody = adapter.substringAfter("fun decodeAsync(").substringBefore("fun postIfNewest(")
        assertWithMessage(
            "decodeAsync must stay in the FOREGROUND slot: a new swipe supersedes the " +
                "previous decode AND any in-flight prewarm."
        ).that(decodeBody).contains("tasks.cancelAndSubmit")
    }

    /**
     * WP9 audit M-2 (b): the geometric decode callback replays the InputConnection/EditorInfo
     * captured at swipe time, so it must re-check that the field is still current before
     * committing — the same guard the neural cold-start replay uses.
     */
    @Test
    fun geometricDecodeCallbackGuardsAgainstStaleInputField() {
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val geoPath = coordinator
            .substringAfter("private fun performGeometricSwipeTyping(")
            .substringBefore("fun prewarmGeometricEngine(")

        assertWithMessage(
            "performGeometricSwipeTyping's decode callback must guard with " +
                "isReplayInputStillCurrent before handing results to the commit pipeline; " +
                "without it a late decode commits into whatever field is focused now."
        ).that(geoPath).contains("isReplayInputStillCurrent(ic, editorInfo)")
    }
}
