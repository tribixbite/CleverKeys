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

    // ── G5 CTC twins of the geometric pins (CTC integration audit, 2026-08-14) ──────────

    /**
     * CTC twin of [geometricWarmUpUsesBackgroundTaskSlot]: the CTC prewarm must stay in
     * the runner's BACKGROUND slot so an `onStartInputView` prewarm can never cancel an
     * in-flight decode; the decode must stay FOREGROUND (last-swipe-wins).
     */
    @Test
    fun ctcWarmUpUsesBackgroundTaskSlot() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")

        val warmUpBody = adapter.substringAfter("fun warmUpAsync(").substringBefore("fun shutdown(")
        assertWithMessage(
            "CtcEngineAdapter.warmUpAsync must submit in the BACKGROUND slot " +
                "(tasks.submitBackground) so a prewarm can never cancel an in-flight decode."
        ).that(warmUpBody).contains("tasks.submitBackground")
        assertWithMessage(
            "CtcEngineAdapter.warmUpAsync must NOT use the foreground cancelAndSubmit slot."
        ).that(warmUpBody).doesNotContain("tasks.cancelAndSubmit")

        val decodeBody = adapter.substringAfter("fun decodeAsync(").substringBefore("fun postIfNewest(")
        assertWithMessage(
            "CtcEngineAdapter.decodeAsync must stay in the FOREGROUND slot: a new swipe " +
                "supersedes the previous decode AND any in-flight prewarm."
        ).that(decodeBody).contains("tasks.cancelAndSubmit")
    }

    /**
     * CTC twin of [geometricDecodeCallbackGuardsAgainstStaleInputField]: the CTC decode
     * callback replays captured input handles and must re-check field currency.
     */
    @Test
    fun ctcDecodeCallbackGuardsAgainstStaleInputField() {
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val ctcPath = coordinator
            .substringAfter("private fun performCtcSwipeTyping(")
            .substringBefore("fun prewarmGeometricEngine(")

        assertWithMessage(
            "performCtcSwipeTyping's decode callback must guard with " +
                "isReplayInputStillCurrent before handing results to the commit pipeline."
        ).that(ctcPath).contains("isReplayInputStillCurrent(ic, editorInfo)")
    }

    /**
     * Audit M1 (layout-aware since the 2026-08-15 gate widening): ctc mode must never
     * degrade below what the layout gets today. performCtcSwipeTyping reads the active
     * language BEFORE dispatch and, when it isn't English, falls through PER LAYOUT:
     * neural ([dispatchNeuralSwipeTyping] — the SAME flow Engine.NEURAL takes) only on a
     * QWERTY-supported layout; geometric ([performGeometricSwipeTyping]) on a widened
     * non-QWERTY Latin layout, where the QWERTY-trained transformer would decode garbage.
     */
    @Test
    fun ctcModeFallsThroughToNeuralForNonEnglishLanguage() {
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val ctcPath = coordinator
            .substringAfter("private fun performCtcSwipeTyping(")
            .substringBefore("fun prewarmGeometricEngine(")

        val gateIdx = ctcPath.indexOf("CtcEngineAdapter.LANGUAGE")
        assertWithMessage(
            "performCtcSwipeTyping must gate on the adapter's language constant " +
                "(CtcEngineAdapter.LANGUAGE) before dispatching to the CTC adapter."
        ).that(gateIdx).isAtLeast(0)
        val layoutGateIdx = ctcPath.indexOf("isSwipeTypingSupportedForLayout")
        assertWithMessage(
            "The non-English fallback must be LAYOUT-AWARE (gate widening 2026-08-15): " +
                "it must consult Config.isSwipeTypingSupportedForLayout so neural is only " +
                "dispatched where the transformer was trained (QWERTY-Latin)."
        ).that(layoutGateIdx).isAtLeast(0)
        val fallthroughIdx = ctcPath.indexOf("dispatchNeuralSwipeTyping(")
        assertWithMessage(
            "performCtcSwipeTyping must fall through to dispatchNeuralSwipeTyping — the " +
                "same flow the NEURAL routing branch takes — for non-English on QWERTY."
        ).that(fallthroughIdx).isAtLeast(0)
        assertWithMessage(
            "The neural fallthrough must sit right after the language gate, BEFORE any " +
                "CTC dispatch (the language read precedes engine dispatch)."
        ).that(fallthroughIdx).isGreaterThan(gateIdx)
        val geoFallbackIdx = ctcPath.indexOf("performGeometricSwipeTyping(")
        assertWithMessage(
            "Non-English on a NON-QWERTY (widened Latin) layout must fall through to " +
                "performGeometricSwipeTyping — NEVER neural (the transformer cannot " +
                "decode non-QWERTY geometry)."
        ).that(geoFallbackIdx).isAtLeast(0)
        assertWithMessage(
            "The language gate must run before the CTC ML-trace capture, so a neural " +
                "fallthrough swipe is captured as ENGINE_NEURAL by performSwipeTyping."
        ).that(ctcPath.indexOf("beginSwipeCapture")).isGreaterThan(fallthroughIdx)
    }

    /**
     * Gate widening 2026-08-15 — missing-letter safety: the router now routes ANY
     * Latin-script layout to CTC under ctc mode, but a Latin layout lacking any a–z
     * letter cannot build a CtcLayout (the adapter would return an empty slate — a
     * coverage regression vs the geometric engine that served it before the widening).
     * performCtcSwipeTyping must therefore check CtcEngineAdapter.supportsLayout BEFORE
     * the CTC ML-trace capture and fall through to performGeometricSwipeTyping when it
     * is false.
     */
    @Test
    fun ctcModeFallsThroughToGeometricWhenLayoutIncomplete() {
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val ctcPath = coordinator
            .substringAfter("private fun performCtcSwipeTyping(")
            .substringBefore("fun prewarmGeometricEngine(")

        val supportsIdx = ctcPath.indexOf("supportsLayout(")
        assertWithMessage(
            "performCtcSwipeTyping must gate on CtcEngineAdapter.supportsLayout so a " +
                "letter-incomplete Latin layout never dead-ends in an empty CTC slate."
        ).that(supportsIdx).isAtLeast(0)
        val captureIdx = ctcPath.indexOf("beginSwipeCapture")
        assertWithMessage(
            "The supportsLayout gate must run BEFORE the CTC ML-trace capture (the " +
                "geometric fallthrough does its own ENGINE_GEOMETRIC capture)."
        ).that(supportsIdx).isLessThan(captureIdx)
        val geoAfterSupports = ctcPath.indexOf("performGeometricSwipeTyping(", supportsIdx)
        assertWithMessage(
            "A supportsLayout=false layout must fall through to performGeometricSwipeTyping."
        ).that(geoAfterSupports).isAtLeast(0)
        assertWithMessage(
            "The geometric fallthrough for an unsupported layout must precede the CTC " +
                "capture/dispatch."
        ).that(geoAfterSupports).isLessThan(captureIdx)
    }

    /**
     * Gate widening 2026-08-15 — the prewarm must warm the engine that will ACTUALLY
     * serve the next swipe, mirroring performCtcSwipeTyping's dispatch: CTC only when
     * the language is English AND supportsLayout holds; geometric when the serving
     * engine is geometric (en + letter-incomplete Latin layout, or non-en + non-QWERTY).
     */
    @Test
    fun ctcPrewarmWarmsTheServingEngine() {
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val prewarmBody = coordinator
            .substringAfter("fun prewarmGeometricEngine(")
            .substringBefore("private fun performSwipeTyping(")
        val ctcBranch = prewarmBody.substringAfter("Engine.CTC")

        assertWithMessage(
            "The prewarm's Engine.CTC branch must gate on supportsLayout — warming the " +
                "CTC adapter for a layout it cannot serve wastes the warm-up AND leaves " +
                "the actually-serving geometric engine cold."
        ).that(ctcBranch).contains("supportsLayout(")
        assertWithMessage(
            "The prewarm's Engine.CTC branch must gate on the adapter's language constant " +
                "(non-en → CTC never serves)."
        ).that(ctcBranch).contains("CtcEngineAdapter.LANGUAGE")
        assertWithMessage(
            "When CTC will not serve (letter-incomplete layout, or non-en on non-QWERTY), " +
                "the prewarm must warm the geometric engine instead."
        ).that(ctcBranch).contains("geometricAdapterOrCreate().warmUpAsync")
    }

    /**
     * Gate widening 2026-08-15 — supportsLayout must be the CHEAP memoized check: it
     * delegates to the same layoutFor memo the decode path uses (a reference-compare
     * after the first call), never a parallel rebuild path.
     */
    @Test
    fun ctcSupportsLayoutDelegatesToTheLayoutMemo() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")

        val supportsIdx = adapter.indexOf("fun supportsLayout(")
        assertWithMessage(
            "CtcEngineAdapter must expose supportsLayout(keyboard, params, frameW, frameH) " +
                "for the dispatch-time gate."
        ).that(supportsIdx).isAtLeast(0)
        val body = adapter.substring(supportsIdx).substringBefore("private fun")
        assertWithMessage(
            "supportsLayout must delegate to the memoized layoutFor (the layout memo " +
                "already computes exactly 'can a full a–z CtcLayout be built') — not a " +
                "fresh buildMappedLayout."
        ).that(body).contains("layoutFor(")
        assertWithMessage(
            "supportsLayout must NOT call buildMappedLayout directly (that would bypass " +
                "the memo and recompute key rects on every swipe)."
        ).that(body).doesNotContain("buildMappedLayout(")
    }

    /**
     * G5 defense-in-depth pins on CtcEngineAdapter.decodeAsync: the adapter keeps its own
     * English gate (upstream M1 fallthrough is the primary), and EVERY decodeAsync entry —
     * including the degenerate/non-en early-return — claims a decode generation so an
     * older in-flight decode can never land on the bar after the newer empty result.
     */
    @Test
    fun ctcDecodeKeepsEnGateAndAlwaysClaimsGeneration() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        val decodeBody = adapter.substringAfter("fun decodeAsync(").substringBefore("fun postIfNewest(")

        assertWithMessage(
            "decodeAsync must keep the language gate (defense-in-depth under the M1 " +
                "InputCoordinator fallthrough)."
        ).that(decodeBody).contains("language.equals(LANGUAGE, ignoreCase = true)")

        val earlyReturn = decodeBody.substringBefore("tasks.cancelAndSubmit")
        assertWithMessage(
            "decodeAsync's early-return branch must claim a decode generation " +
                "(decodeGeneration.incrementAndGet()) before delivering the empty result."
        ).that(earlyReturn).contains("decodeGeneration.incrementAndGet()")
    }

    /**
     * Audit L5: an ONNX-session load failure must retry a bounded number of times before
     * latching off — the old `modelLoadFailed` boolean permanently disabled ctc for the
     * IME's whole lifetime on the first (possibly transient) failure.
     */
    @Test
    fun ctcModelLoadFailureRetriesBoundedThenLatches() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")

        assertWithMessage(
            "CtcEngineAdapter must bound model-load retries via MAX_MODEL_LOAD_ATTEMPTS."
        ).that(adapter).contains("MAX_MODEL_LOAD_ATTEMPTS")
        assertWithMessage(
            "modelOrNull must stop attempting once the retry budget is exhausted."
        ).that(adapter).contains("modelLoadAttempts >= MAX_MODEL_LOAD_ATTEMPTS")
        assertWithMessage(
            "The permanent first-failure latch (modelLoadFailed) must not return."
        ).that(adapter).doesNotContain("modelLoadFailed")
    }
}
