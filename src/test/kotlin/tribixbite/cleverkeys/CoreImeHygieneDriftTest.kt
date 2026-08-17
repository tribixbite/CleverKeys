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
     * Audit M1 (layout-aware since the 2026-08-15 gate widening; language-table since the
     * fr/de/es enablement): ctc mode must never degrade below what the layout gets today.
     * performCtcSwipeTyping reads the active language BEFORE dispatch and, when CTC does
     * not support it, falls through PER LAYOUT: neural ([dispatchNeuralSwipeTyping] — the
     * SAME flow Engine.NEURAL takes) only on a QWERTY-supported layout; geometric
     * ([performGeometricSwipeTyping]) on a widened non-QWERTY Latin layout, where the
     * QWERTY-trained transformer would decode garbage.
     */
    @Test
    fun ctcModeFallsThroughToNeuralForUnsupportedLanguage() {
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val ctcPath = coordinator
            .substringAfter("private fun performCtcSwipeTyping(")
            .substringBefore("fun prewarmGeometricEngine(")

        val gateIdx = ctcPath.indexOf("CtcEngineAdapter.supportsLanguage(")
        assertWithMessage(
            "performCtcSwipeTyping must gate on the adapter's language table " +
                "(CtcEngineAdapter.supportsLanguage) before dispatching to the CTC adapter " +
                "— a hard-coded language literal here would silently diverge from " +
                "CtcLanguageSupport.SUPPORTED."
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
                "same flow the NEURAL routing branch takes — for an unsupported language " +
                "on QWERTY."
        ).that(fallthroughIdx).isAtLeast(0)
        assertWithMessage(
            "The neural fallthrough must sit right after the language gate, BEFORE any " +
                "CTC dispatch (the language read precedes engine dispatch)."
        ).that(fallthroughIdx).isGreaterThan(gateIdx)
        val geoFallbackIdx = ctcPath.indexOf("performGeometricSwipeTyping(")
        assertWithMessage(
            "An unsupported language on a NON-QWERTY (widened Latin) layout must fall " +
                "through to performGeometricSwipeTyping — NEVER neural (the transformer " +
                "cannot decode non-QWERTY geometry)."
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
            "The prewarm's Engine.CTC branch must gate on the adapter's language table " +
                "(unsupported language → CTC never serves), by the SAME predicate the " +
                "dispatch uses — otherwise prewarm and dispatch can disagree."
        ).that(ctcBranch).contains("CtcEngineAdapter.supportsLanguage(")
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
     * language gate (upstream M1 fallthrough is the primary), and EVERY decodeAsync entry —
     * including the degenerate/unsupported-language early-return — claims a decode
     * generation so an older in-flight decode can never land on the bar after the newer
     * empty result.
     */
    @Test
    fun ctcDecodeKeepsLanguageGateAndAlwaysClaimsGeneration() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        val decodeBody = adapter.substringAfter("fun decodeAsync(").substringBefore("fun postIfNewest(")

        assertWithMessage(
            "decodeAsync must keep the language gate (defense-in-depth under the M1 " +
                "InputCoordinator fallthrough)."
        ).that(decodeBody).contains("supportsLanguage(language)")

        val earlyReturn = decodeBody.substringBefore("tasks.cancelAndSubmit")
        assertWithMessage(
            "decodeAsync's early-return branch must claim a decode generation " +
                "(decodeGeneration.incrementAndGet()) before delivering the empty result."
        ).that(earlyReturn).contains("decodeGeneration.incrementAndGet()")
    }

    /**
     * Per-language enablement (2026-08-16, fr/de/es): the trie and the decoder are
     * LANGUAGE-KEYED. A language switch must rebuild both — reusing the previous
     * language's trie would decode against the wrong vocabulary, and reusing the previous
     * decoder would decode at the wrong λ (4.0 on the en JSON scale vs 2.0 on the CKDT
     * scale, `docs/eval/2026-08-15-ctc-per-language-lambda.md`). This is the audit's
     * stale-memo hazard; the memo keys are the only thing standing between a language
     * switch and a silently mis-scored slate.
     */
    @Test
    fun ctcMemosAreKeyedByLanguage() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")

        val trieMemoCheck = adapter
            .substringAfter("private fun lexiconFor(")
            .substringBefore("private fun contentVersion(")
        assertWithMessage(
            "lexiconFor's memo hit must require the LANGUAGE to match, not just the " +
                "content-hash version — a language switch may never reuse the previous " +
                "language's trie."
        ).that(trieMemoCheck).contains("it.language == lang && it.version == version")
        assertWithMessage(
            "lexiconFor must resolve its asset/source through the CtcLanguageSupport " +
                "table (adding a language is a table entry, not an adapter edit)."
        ).that(trieMemoCheck).contains("CtcLanguageSupport.assetFor(")

        val decoderKeyBlock = adapter
            .substringAfter("private data class DecoderKey(")
            .substringBefore(")")
        assertWithMessage(
            "The decoder memo key must include the language so the per-language preset " +
                "(λ) cannot be carried across a language switch."
        ).that(decoderKeyBlock).contains("val language: String")

        val decoderBody = adapter
            .substringAfter("private fun decoderFor(")
            .substringBefore("// ── Display overlays")
        assertWithMessage(
            "decoderFor must actually put the language into the memo key."
        ).that(decoderBody).contains("DecoderKey(mapped, trie, beamWidth, language)")
        assertWithMessage(
            "The decoder must be built with the per-language preset (CtcScoringParams." +
                "presetFor), never the fixed en tunedV2 constants."
        ).that(decoderBody).contains("CtcScoringParams.presetFor(language")
        assertWithMessage(
            "The decoder must NOT hard-code tunedV2 (that is the en-scale λ)."
        ).that(decoderBody).doesNotContain("CtcScoringParams.tunedV2(")
    }

    /**
     * Per-language enablement (2026-08-16): the decoded slate is a–z, so BOTH display
     * overlays must run inside the adapter before the shared pipeline — canonical accents
     * (CKDT languages: "cafe" → "café") and then contraction aliases ("dont" → "don't").
     * Dropping the accent overlay would commit unaccented words in fr/de/es; dropping the
     * contraction overlay would regress the en H1 fix.
     */
    @Test
    fun ctcAppliesAccentThenContractionDisplay() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")

        assertWithMessage(
            "The adapter must expose an accent/canonical display overlay."
        ).that(adapter).contains("private fun applyCanonicalDisplay(")
        val composed = adapter.substringAfter("private fun applyDisplay(")
            .substringBefore("// ── Public surface")
        assertWithMessage(
            "applyDisplay must compose BOTH overlays with accents applied FIRST " +
                "(contraction keys are a–z aliases, so they must see the a–z surface " +
                "of any word the accent map did not rewrite)."
        ).that(composed).contains("applyCanonicalDisplay(")
        assertWithMessage("applyDisplay must still apply the contraction overlay.")
            .that(composed).contains("applyContractionDisplay(")

        val decodeBody = adapter.substringAfter("fun decodeAsync(").substringBefore("fun postIfNewest(")
        assertWithMessage(
            "decodeAsync must route its slate through applyDisplay before delivering it " +
                "to the shared pipeline (the pipeline does not map surfaces)."
        ).that(decodeBody).contains("applyDisplay(")
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

    /**
     * Contraction display must be scoped to the ACTIVE DECODE LANGUAGE (2026-08-16).
     *
     * Both swipe adapters used to load the bundled ENGLISH base (`contractions.bin` +
     * `contraction_pairings.json`) for EVERY language before the active language's file,
     * which injected English morphology into non-English slates — a `fr` decode of the real
     * French word `franco` also offered `franco's` (`CtcMultiLanguageInstrumentedTest`).
     * Code-switching is a bug, not a feature: the neural engine drew this line in v1.1.88
     * (`OptimizedVocabulary` clears the English contractions before loading the target
     * language's) and the shared pipeline already draws it for possessives
     * (`SuggestionHandler.shouldAugmentPossessives`).
     *
     * The policy therefore has exactly ONE implementation
     * ([tribixbite.cleverkeys.ContractionManager.loadSwipeDisplayMappings], gated by
     * `SwipeContractionPolicy`), and neither adapter may hand-roll its own load order again
     * — that is how the two drifted into mirroring each other's bug in the first place.
     * Behavior is covered by `SwipeContractionLanguageIsolationTest` (pure, real assets),
     * `ContractionManagerTest` (instrumented loader) and `CtcMultiLanguageInstrumentedTest`
     * (end-to-end slate).
     */
    @Test
    fun swipeAdaptersScopeContractionsToTheActiveLanguage() {
        val adapters = listOf(
            "tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt",
            "tribixbite/cleverkeys/swipe/GeometricEngineAdapter.kt",
        )
        for (relative in adapters) {
            // The function BODY only (the KDoc above it legitimately names the old calls
            // while explaining what changed).
            val body = source(relative)
                .substringAfter("private fun contractionsFor(")
                .substringBefore("private fun apply")
            assertWithMessage(
                "$relative: contractionsFor must load through the single policy entry point " +
                    "ContractionManager.loadSwipeDisplayMappings(language)."
            ).that(body).contains("loadSwipeDisplayMappings(language)")
            assertWithMessage(
                "$relative: contractionsFor must NOT load the bundled English base itself — " +
                    "loadMappings() for a non-English language is exactly the leak that put " +
                    "\"franco's\" in a French slate."
            ).that(body).doesNotContain("loadMappings()")
            assertWithMessage(
                "$relative: contractionsFor must NOT hand-roll a per-language load order; " +
                    "the policy lives in ContractionManager/SwipeContractionPolicy."
            ).that(body).doesNotContain("loadLanguageContractions(")
        }

        // The loader itself: the non-English branch must DROP whatever was loaded before
        // (loadLanguageContractions is earlier-wins and never clears, and the adapters reuse
        // ONE manager instance across language switches).
        val loader = source("tribixbite/cleverkeys/ContractionManager.kt")
            .substringAfter("fun loadSwipeDisplayMappings(")
            .substringBefore("fun loadLanguageContractions(")
        assertWithMessage(
            "loadSwipeDisplayMappings must decide via SwipeContractionPolicy (one rule, " +
                "shared with the pure tests), not an inline language literal."
        ).that(loader).contains("SwipeContractionPolicy.usesEnglishBase(")
        for (map in listOf("nonPairedContractions", "pairedContractions", "knownContractions")) {
            assertWithMessage(
                "loadSwipeDisplayMappings' non-English branch must clear $map before loading " +
                    "the active language's file, or the previous language's (English's) " +
                    "mappings survive the switch."
            ).that(loader).contains("$map.clear()")
        }
    }

    /**
     * Contraction alias keys are REACHABLE, never PREFERRED — at **both** sites, not just one.
     *
     * ### The bug this pins
     *
     * `loadContractionsFromInputStream` injects every non-paired alias key into `vocabulary`
     * at the frequency floor, precisely so the 17,931 fr / 21,214 it mappings restored on
     * 2026-08-17 cannot outrank real vocabulary. But `filterPredictions` reads `vocabulary`
     * only behind `_englishFallbackEnabled`, which is `(primary == "en") || (secondary == "en")`
     * — the code-switching rule. So in a French slate with no English secondary that lookup is
     * SKIPPED, control falls through to the `nonPairedContractions` fallback, and whatever that
     * fallback assigns is the score the word actually gets.
     *
     * That fallback used to fabricate `WordInfo(0.88f, tier 2)` — a top-100 common-word boost —
     * which silently defeated the load-time floor in exactly the configuration the floor was
     * written for. Fixing one site and not the other is the failure mode; this test pins both.
     *
     * The English aliases ("doesnt", "cant") are unaffected: they are reached at the
     * `vocabulary` lookup whenever English is primary or secondary, which is the only
     * configuration in which English words may surface at all.
     */
    @Test
    fun contractionAliasKeysEnterAtTheFloorAtEveryScoringSite() {
        val relative = "tribixbite/cleverkeys/OptimizedVocabulary.kt"
        val vocab = source(relative)

        // The constants themselves must be a floor, not a boost. Read from source rather than
        // referenced directly: the companion is private, and widening production visibility to
        // suit a test is the wrong trade.
        val declaredFrequency = Regex("const val CONTRACTION_ALIAS_FREQUENCY = ([0-9.]+)f")
            .find(vocab)?.groupValues?.get(1)?.toFloat()
        assertWithMessage(
            "$relative must declare CONTRACTION_ALIAS_FREQUENCY as a plain float literal so " +
                "this test can check its magnitude."
        ).that(declaredFrequency).isNotNull()
        assertWithMessage(
            "CONTRACTION_ALIAS_FREQUENCY must be a floor. Anything a real word can reach makes " +
                "tens of thousands of injected pseudo-words competitive with real vocabulary."
        ).that(declaredFrequency!!).isLessThan(0.01f)
        assertWithMessage(
            "CONTRACTION_ALIAS_TIER must be the ordinary tier — tier 2 is the common-word boost."
        ).that(vocab).contains("const val CONTRACTION_ALIAS_TIER: Byte = 0")

        // Both regions are stripped of comments before the negative assertions: the comments
        // deliberately NAME the rejected value ("this used to be 0.88f") so the next reader knows
        // why the constant is there, and a raw text search cannot tell that from a live boost.
        fun code(region: String): String = region
            .lineSequence()
            .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

        // Site 1 — the runtime fallback in filterPredictions. This is the ONLY site that scores
        // the key in a non-English slate, because the `vocabulary` lookup above it is gated.
        val fallback = code(
            vocab
                .substringAfter("if (info == null && nonPairedContractions.containsKey(word)) {")
                .substringBefore("if (info == null) {")
        )
        assertWithMessage(
            "$relative: the nonPairedContractions fallback in filterPredictions must assign the " +
                "shared alias constants. It is reached only when English fallback is OFF, so a " +
                "hard-coded frequency here overrides the load-time floor for every non-English " +
                "alias key without touching the injection code that appears to set the policy."
        ).that(fallback).contains("WordInfo(CONTRACTION_ALIAS_FREQUENCY, CONTRACTION_ALIAS_TIER)")
        assertWithMessage(
            "$relative: the nonPairedContractions fallback must not re-fabricate a frequency. " +
                "0.88f/tier 2 is the top-100 common-word boost this test exists to keep out."
        ).that(fallback).doesNotContain("0.88f")

        // Site 2 — the load-time injection. The `!containsKey` branch is the productive-elision
        // case (fr `dabaissement`); the else branch is the attested-alias upgrade and is
        // deliberately left alone, so scope the assertion to the injection branch only.
        val injection = code(
            vocab
                .substringAfter("if (!vocabulary.containsKey(withoutApostrophe)) {")
                .substringBefore("} else {")
        )
        assertWithMessage(
            "$relative: load-time injection of an alias key the vocabulary does not attest must " +
                "use the same floor constants as the runtime fallback and as the other two " +
                "engines (CtcContractionKeys.INJECTED_FREQUENCY, WordPredictor" +
                ".CONTRACTION_ALIAS_RANK)."
        ).that(injection).contains("WordInfo(CONTRACTION_ALIAS_FREQUENCY, CONTRACTION_ALIAS_TIER)")
        assertWithMessage(
            "$relative: the injection branch must not boost. A key the vocabulary lacks is by " +
                "construction not a word of the language."
        ).that(injection).doesNotContain("0.88f")
    }
}
