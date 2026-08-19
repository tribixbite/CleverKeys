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
     * committing — the same guard the geometric decode callback uses.
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
     * Audit M1 (language-table since the fr/de/es enablement; unconditional since the neural
     * engine was removed on 2026-08-18): ctc mode must never leave a swipe undispatched.
     * performCtcSwipeTyping reads the active language BEFORE dispatch and, when CTC does not
     * support it, falls through to the geometric engine — which decodes ANY layout in ANY
     * language. This is the cell the maintainer's own device (Italian on QWERTY) lands in:
     * an early `return` here, or a fallthrough guarded by a layout predicate that no longer
     * exists, silently kills swipe typing for every non-en/fr/de/es user.
     */
    @Test
    fun ctcModeFallsThroughToGeometricForUnsupportedLanguage() {
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
        val fallthroughIdx = ctcPath.indexOf("performGeometricSwipeTyping(")
        assertWithMessage(
            "performCtcSwipeTyping must fall through to performGeometricSwipeTyping for an " +
                "unsupported language. Geometric serves every layout and every language; " +
                "returning instead would remove swipe typing outright for those users."
        ).that(fallthroughIdx).isAtLeast(0)
        assertWithMessage(
            "The geometric fallthrough must sit right after the language gate, BEFORE any " +
                "CTC dispatch (the language read precedes engine dispatch)."
        ).that(fallthroughIdx).isGreaterThan(gateIdx)
        assertWithMessage(
            "The language gate must run before the CTC ML-trace capture, so a fallthrough " +
                "swipe is captured by the geometric path with its own engine tag."
        ).that(ctcPath.indexOf("beginSwipeCapture")).isGreaterThan(fallthroughIdx)
        assertWithMessage(
            "The neural engine was removed on 2026-08-18. No dispatch in performCtcSwipeTyping " +
                "may reference it — a resurrected neural fallthrough would call a dead engine."
        ).that(ctcPath).doesNotContain("dispatchNeuralSwipeTyping")
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
     * Code-switching is a bug, not a feature: the deleted vocabulary drew this line in v1.1.88
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
     * The 2026-08-17 fr/it contraction restore injected 17,931 fr / 21,214 it alias keys
     * (`dabaissement` for `d'abaissement`) into the prediction paths. Those keys are NOT words
     * of the language: they must be REACHABLE — so a swipe or a prefix search can surface the
     * apostrophe form — but must never OUTRANK a real word.
     *
     * There were three scoring sites. The two in `OptimizedVocabulary` died with the neural
     * engine on 2026-08-18; this test now pins the two that remain, because they are the ones
     * a future edit can quietly turn back into a boost:
     *
     *  - CTC swipe: `CtcContractionKeys.INJECTED_FREQUENCY` must be `CtcLexiconMerge.MIN_FREQ`.
     *    The beam adds `lambda * ln(freq + 1e-10)`; at MIN_FREQ that term is ~0, so an injected
     *    key gets no frequency bonus while every real word does. The neural vocabulary used
     *    `0.88f` / tier 2 here — a top-100 common-word boost — which is exactly the value this
     *    test exists to keep out.
     *  - Tap typing: `WordPredictor.CONTRACTION_ALIAS_RANK` must be 254, one above "absent" and
     *    the bottom of the range real dictionary words occupy. At the old hard-coded rank 50 all
     *    21k Italian aliases scored ~821k, above nearly every real word.
     *
     * Both must also SKIP a key the index/trie already holds, so a real word that happens to be
     * an alias key (fr `la` -> `l'a`) keeps its real frequency.
     */
    @Test
    fun contractionAliasKeysEnterAtTheFloorAtEveryScoringSite() {
        // ── Site 1: the CTC swipe lexicon ────────────────────────────────────────────
        val ctcRel = "tribixbite/cleverkeys/swipe/ctc/CtcContractionKeys.kt"
        val ctc = source(ctcRel)
        assertWithMessage(
            "$ctcRel: injected contraction keys must enter at CtcLexiconMerge.MIN_FREQ. Any " +
                "literal frequency here decouples the floor from the scale the tuned lambda " +
                "was fitted against."
        ).that(ctc).contains("const val INJECTED_FREQUENCY: Double = CtcLexiconMerge.MIN_FREQ")
        // Strip comments before the negative assertion: the KDoc deliberately NAMES the
        // rejected value so the next reader knows why the constant exists, and a raw text
        // search cannot tell that from a live boost.
        val ctcCode = ctc.lineSequence()
            .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        assertWithMessage(
            "$ctcRel must not reintroduce the neural vocabulary's 0.88f common-word boost."
        ).that(ctcCode).doesNotContain("0.88")
        assertWithMessage(
            "$ctcRel must insert injected keys at INJECTED_FREQUENCY, not at a computed or " +
                "per-word frequency."
        ).that(ctc).contains("trie.insert(lowered, INJECTED_FREQUENCY)")

        // ── Site 2: the tap-typing secondary prefix index ────────────────────────────
        val wpRel = "tribixbite/cleverkeys/WordPredictor.kt"
        val wp = source(wpRel)
        assertWithMessage(
            "$wpRel must declare CONTRACTION_ALIAS_RANK as the rank FLOOR (254 = one above " +
                "absent). A lower rank number is a HIGHER score: rank 50 scored ~821k and " +
                "buried real words under injected pseudo-words."
        ).that(wp).contains("private const val CONTRACTION_ALIAS_RANK = 254")
        assertWithMessage(
            "$wpRel must add a new alias key at CONTRACTION_ALIAS_RANK, never at a literal rank."
        ).that(wp).contains("index.addWord(withoutApostrophe, CONTRACTION_ALIAS_RANK)")
        assertWithMessage(
            "$wpRel must SKIP an alias key the index already holds, so a real word that is " +
                "also an alias key (fr `la` -> `l'a`) keeps its real frequency."
        ).that(wp).contains("!index.contains(normalized)")
    }

    /**
     * Both typing-side contraction load sites must go through the ONE policy method.
     *
     * ### The bug this pins
     *
     * `ManagerInitializer` and `PreferenceUIUpdateHandler` each hand-rolled the same sequence —
     * English base, then the primary language, then `loadLanguageContractions("en")` again,
     * unconditionally. Both loaders are earlier-wins, so English owned every colliding key:
     * a German user typing `im` was offered "I'm", a French user typing `dont` got "don't".
     *
     * Duplicating a policy across two call sites is what made it a two-place fix, and what
     * would make a partial revert silently reintroduce the leak in one language-change path
     * while the other stayed correct. `SwipeContractionLanguageIsolationTest` proves the POLICY
     * is right over the real assets; this proves the CODE uses it.
     */
    @Test
    fun typingContractionLoadSitesUseTheSharedLanguageScopedPolicy() {
        for (relative in listOf(
            "tribixbite/cleverkeys/ManagerInitializer.kt",
            "tribixbite/cleverkeys/PreferenceUIUpdateHandler.kt",
        )) {
            val src = source(relative)
            assertWithMessage(
                "$relative must load typing contractions via ContractionManager" +
                    ".loadTypingMappings(primary, secondary) — it owns the precedence rule " +
                    "(primary, then secondary, then the English base ONLY if English is one " +
                    "of the two)."
            ).that(src).contains("loadTypingMappings(")
            assertWithMessage(
                "$relative must NOT call loadMappings() directly: it loads the English base " +
                    "FIRST, and earlier-wins means English then owns every colliding key for " +
                    "every language. That is the exact defect."
            ).that(src).doesNotContain("loadMappings()")
            assertWithMessage(
                "$relative must NOT hard-code an unconditional English load — English is " +
                    "admitted only when the user selected it as primary or secondary."
            ).that(src).doesNotContain("loadLanguageContractions(\"en\")")
        }
    }

    /**
     * A dead ONNX session must route the swipe to geometric, not produce an empty bar.
     *
     * ### The bug this pins
     *
     * `CtcEngineAdapter.modelOrNull()` retries the load `MAX_MODEL_LOAD_ATTEMPTS` times then
     * latches. Before 2026-08-19 nothing consulted that latch: the decode returned
     * `PredictionResult(emptyList(), emptyList())`, which the shared pipeline renders exactly
     * like "no candidates". Swipe silently stopped working — no exception, no message, and no
     * way for the user to tell it apart from a bad gesture.
     *
     * It was survivable while `neural` was the default and a second ML engine existed. It stopped
     * being survivable on 2026-08-18: `ctc` became the default, `Mode.fromPref` funnels every
     * unrecognised value to it, and neural was deleted. The language, layout and router gates all
     * hand off to geometric, so this was the **only remaining path to no engine at all**.
     *
     * Pinned by source-scan because the failure needs a corrupt ONNX asset to reproduce, which no
     * unit test can stage — and an instrumented test that shipped a deliberately broken model
     * would be a packaging hazard of its own.
     */
    @Test
    fun aDeadCtcSessionFallsThroughToGeometricRatherThanClearingTheBar() {
        val adapter = source("tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt")
        assertWithMessage(
            "CtcEngineAdapter must expose the latch. Without an accessor the dispatcher cannot " +
                "tell a dead session from a working one, and an empty decode is indistinguishable " +
                "from 'no candidates' downstream."
        ).that(adapter).contains("fun isModelPermanentlyUnavailable()")
        assertWithMessage(
            "the latch must be @Volatile — it is written on the decode thread and read on the " +
                "main thread by the dispatcher"
        ).that(adapter).contains("@Volatile")
        assertWithMessage(
            "exhausting the retry budget must SET the latch, not merely log it"
        ).that(adapter).contains("if (latched) modelPermanentlyUnavailable = true")
        assertWithMessage(
            "the log line must not claim 'ctc mode disabled this session' — nothing disables the " +
                "mode, and that wording sent readers looking for a mode change that never happens"
        ).that(adapter).doesNotContain("ctc mode disabled this session")

        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")
        val dispatch = coordinator
            .substringAfter("private fun performCtcSwipeTyping(")
            .substringBefore("fun prewarmGeometricEngine(")
        assertWithMessage(
            "the CTC dispatch guard must consult the latch BEFORE decoding, alongside the " +
                "existing supportsLayout check — both have the same remedy (hand to geometric)"
        ).that(dispatch).contains("isModelPermanentlyUnavailable()")

        val prewarm = coordinator
            .substringAfter("fun prewarmGeometricEngine(")
            .substringBefore("private fun performSwipeTyping(")
        assertWithMessage(
            "prewarm must apply the same condition, or it warms CTC while the next swipe goes " +
                "to a cold geometric engine"
        ).that(prewarm).contains("isModelPermanentlyUnavailable()")
    }

    /**
     * Accuracy figures quoted in source must belong to the model we actually ship.
     *
     * ### The bug this pins
     *
     * `sw2345` is a superseded Phase-J model that was **never decoded on test**
     * (CleverKeys-ML `MODELS_TABLE.md:139`). Its alt-layout numbers — dvorak 89.87 /
     * dvorak-app 88.98, azerty 83.81, qwertz 83.01, german 80.64, spanish 88.45 — were quoted
     * throughout this codebase as though they described the shipped
     * `phaseM_kd_fresh_w1_s1234_fp16w`, whose real figures are HIGHER: dvorak 91.82 / 91.10,
     * azerty 84.53, qwertz 83.97, german 81.30, spanish 89.53 (`MODELS_TABLE.md:113`).
     *
     * They were corrected on 2026-08-18 — and then a KDoc rewrite during the neural-engine
     * removal silently reintroduced two of them, leaving `SwipeEngineRouter` quoting 91.82 in
     * one paragraph and the superseded 89.87 in two others. Nothing caught it, because a stale
     * compiles and passes every other gate. Hence this test.
     *
     * A negative assertion is the right shape here: the numbers are load-bearing only as
     * evidence, so what matters is that the WRONG set cannot reappear anywhere, not that any
     * particular file quotes the right one.
     */
    @Test
    fun sourceQuotesTheShippedModelsAccuracyNotItsSupersededPredecessors() {
        // sw2345's figures. Each is distinctive enough that a match is a real citation, not a
        // coincidental number — they are all 4-significant-figure percentages.
        val supersededFigures = listOf("89.87", "88.98", "83.81", "83.01", "80.64", "88.45")

        // A KDoc that RECORDS the correction has to restate the wrong numbers to be useful, so
        // allow a figure when `sw2345`/`superseded` appears within two lines of it — prose wraps,
        // and requiring the marker on the same line would force awkward line breaks. Two lines is
        // tight enough that it cannot accidentally licence an unrelated citation elsewhere.
        val markerWindow = 2
        fun isDeliberatelyHistorical(lines: List<String>, idx: Int): Boolean =
            (maxOf(0, idx - markerWindow)..minOf(lines.lastIndex, idx + markerWindow)).any {
                lines[it].contains("sw2345") || lines[it].contains("superseded")
            }

        // Scan the TEST sources too, not just `src/main`. The first version of this guard
        // looked only at `mainKotlin` and therefore could not see the copy sitting in
        // `SwipeEngineRouterTest` — a comment that had carried the wrong numbers the whole
        // time. A guard that cannot see half the places the mistake occurs is a false
        // reassurance, which is worse than no guard.
        val roots = listOf(mainKotlin, File("src/test/kotlin"), File("src/androidTest/kotlin"))
        val offenders = mutableListOf<String>()
        roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { idx, line ->
                    if (isDeliberatelyHistorical(lines, idx)) return@forEachIndexed
                    supersededFigures.firstOrNull { line.contains(it) }?.let { figure ->
                        offenders += "${file.path}:${idx + 1} quotes $figure"
                    }
                }
            }

        assertWithMessage(
            "These are `sw2345`'s numbers, not the shipped model's. Use dvorak 91.82 / " +
                "dvorak-app 91.10, azerty 84.53, qwertz 83.97, german 81.30, spanish 89.53 " +
                "(CleverKeys-ML MODELS_TABLE.md:113). If you are deliberately citing the " +
                "superseded model, name `sw2345` on the same line."
        ).that(offenders).isEmpty()
    }
}
