package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift guards for the 2026-08-06 context-LM/learning/privacy review fixes
 * whose WIRING lives in Android-heavy classes that pure-JVM tests cannot
 * instantiate (`WordPredictor`, `SuggestionHandler`, `CleverKeysService`, …).
 * The BEHAVIOR of each fix is covered by real-store tests
 * ([ContextLearningBoundaryTest], [SelectionHistoryTest],
 * [tribixbite.cleverkeys.contextaware.LearnedStoreForgetRaceTest]); these scans
 * pin the production call sites so a refactor can't silently disconnect them.
 *
 * Same source-scan convention as [TestRunnerListDriftTest] / BackspaceUndoTest
 * (project root as CWD).
 */
class LearningWiringDriftTest {

    // ---------------------------------------------------------------- H1

    @Test
    fun `H1 - session boundaries clear the learn window`() {
        // onFinishInputView → PredictionCoordinator.flushLearnedData → clearContext
        val coordinator = readSource("PredictionCoordinator.kt")
        assertThat(coordinator).contains("wordPredictor?.clearContext()")

        val service = readSource("CleverKeysService.kt")
        assertThat(service).contains("_predictionCoordinator?.flushLearnedData()")

        // (Until ARC-079 this also checked DictionaryManager's per-language predictors. There
        // is only one predictor in the process now, and the line above is its window clear.)

        // Entering a password field drops the window immediately.
        val handler = readSource("SuggestionHandler.kt")
        assertThat(handler).contains("getWordPredictor()?.clearContext()")
    }

    // ---------------------------------------------------------------- H2

    @Test
    fun `H2 - next-word call-site 2 only runs on manual selection`() {
        // The swipe AUTO-INSERT routes through onSuggestionSelected with
        // isManualSelection=false; an ungated maybeShowNextWordPredictions there
        // replaced the swipe-alternates bar and broke swipe correction. The
        // auto-insert path composes candidates via appendNextWordToSwipeAlternates.
        val handler = readSource("SuggestionHandler.kt")
        assertThat(handler).contains(
            "if (isManualSelection) {\n                maybeShowNextWordPredictions(editorInfo)"
        )
    }

    // ---------------------------------------------------------------- H3 + M2

    @Test
    fun `H3 - adaptation multiplier reads are gated`() {
        val predictor = readSource("WordPredictor.kt")
        // The gate helper exists and is fail-closed on null config.
        assertThat(predictor).contains(
            "LearningGate.canUseAdaptation(config?.on_device_learning_enabled ?: false)"
        )
        // Every getAdaptationMultiplier READ site is inside a canUseAdaptation()
        // guard (the helper's own definition is the single ungated reference).
        val readSites = Regex("""adaptationManager\?\.getAdaptationMultiplier""").findAll(predictor).count()
        val guards = Regex("""canUseAdaptation\(\)""").findAll(predictor).count()
        assertThat(readSites).isEqualTo(3) // isInDictionary, resolveScoreBreakdown, autoCorrect
        assertThat(guards).isAtLeast(3)
        // Belt-and-braces: the store's own enabled flag is synced from config.
        assertThat(predictor).contains("syncAdaptationEnabled()")
    }

    @Test
    fun `M2 - learning gate reads fail CLOSED on null config`() {
        val predictor = readSource("WordPredictor.kt")
        // No learning-gate pref may default to true when config is absent.
        for (pref in listOf(
            "on_device_learning_enabled",
            "context_aware_predictions_enabled",
            "personalized_learning_enabled"
        )) {
            assertThat(predictor).doesNotContain("$pref ?: true")
        }
    }

    @Test
    fun `M2 - the live predictor is constructed with the real Config`() {
        // M2's original subject was DictionaryManager's two predictor construction sites,
        // which threaded `Config.globalConfigOrNull()` in because a predictor without a config
        // fails its learning/read gates CLOSED. ARC-079 deleted both sites along with the
        // cache; the invariant now applies to the single predictor that remains, which gets
        // the coordinator's own live Config directly (no global fallback needed).
        val coordinator = readSource("PredictionCoordinator.kt")
        val init = coordinator.substringAfter("private fun initializeWordPredictor()")
            .substringBefore("fun ensureInitialized()")
        assertThat(init).contains("wordPredictor = WordPredictor().apply {")
        assertThat(init).contains("setConfig(config)")
        // A config swap must reach it too, or the gates go stale after a settings change.
        assertThat(coordinator).contains("wordPredictor?.setConfig(config)")
    }

    // ------------------------------------------- ARC-021 (audit 2026-08-28)

    // ARC-021's two tests pinned the predictor-EVICTION flush inside
    // `DictionaryManager.setLanguage` and the three release paths that cache had
    // (eviction / flushLearnedData / cleanup). ARC-079 deleted the cache, so all three
    // release paths and the invariant they protected are gone with it — there is nothing
    // left in DictionaryManager that can strand unsaved learning, because it holds no
    // predictor. The surviving release path is the coordinator's, and its "persist before
    // release" ordering is pinned by
    // `the coordinator flushes its own predictor and delegates learning to no one` below
    // plus the behavioural `PredictionCoordinatorLifecycleTest`.

    // ------------------------------------------- ARC-079 (audit 2026-08-29)

    @Test
    fun `DictionaryManager owns no predictor - the coordinator's is the only one`() {
        // ARC-079: `DictionaryManager` used to keep a per-language `WordPredictor` cache that
        // RE-loaded the very dictionary `PredictionCoordinator` had already loaded — ~5-10 MB
        // of duplicate residency per configured language, for a cache with zero prediction
        // consumers (its only readers were isLoading/flushLearnedData/cleanup and a
        // zero-caller preloadLanguages). It is deleted; this pins the residency guarantee,
        // because nothing about the class NAME stops someone re-adding a predictor to it.
        val dictManager = readSource("DictionaryManager.kt")

        // Word-boundary anchored: a bare `contains("WordPredictor(")` also matches the
        // *method* name `getWordPredictor(` that this file's own KDoc cites, which would make
        // the pin fire on prose. `\b` requires a non-word char before the type name, so it
        // matches construction (`= WordPredictor()`) and not `getWordPredictor(`.
        assertWithMessage(
            "DictionaryManager must not CONSTRUCT a WordPredictor — the process holds exactly " +
                "one, built by PredictionCoordinator.initializeWordPredictor."
        ).that(dictManager).doesNotContainMatch("""\bWordPredictor\(""")

        for (token in listOf("WordPredictor>", "WordPredictor?")) {
            assertWithMessage(
                "DictionaryManager must not hold a WordPredictor-typed field. Found: $token"
            ).that(dictManager).doesNotContain(token)
        }
        // The cache's own API surface must be gone with it, not left as an empty shell.
        // `fun `-anchored for the same prose-safety reason as the regex above: the class
        // KDoc names these members to explain why they were deleted, so only a DECLARATION
        // may fail the pin. Predictor-driving calls (loadDictionaryAsync,
        // startObservingDictionaryChanges, persistLearnedData) need no separate check —
        // with no construction site and no predictor-typed field there is no receiver to
        // call them on.
        assertThat(dictManager).doesNotContain("fun preloadLanguages(")
        assertThat(dictManager).doesNotContain("fun isLoading(")
        assertThat(dictManager).doesNotContain("fun flushLearnedData(")
        assertThat(dictManager).doesNotContain("fun cleanup(")
    }

    @Test
    fun `the coordinator flushes its own predictor and delegates learning to no one`() {
        // The flip side of the pin above: with the manager's cache gone, every learned-data
        // path has to terminate on the coordinator's predictor. A leftover
        // `dictionaryManager?.flushLearnedData()` / `?.cleanup()` would not compile, but a
        // future re-introduction would — and would silently re-open ARC-079.
        val coordinator = readSource("PredictionCoordinator.kt")

        assertThat(coordinator).doesNotContain("dictionaryManager?.flushLearnedData()")
        assertThat(coordinator).doesNotContain("dictionaryManager?.cleanup()")

        // Teardown ordering: persist BEFORE the reference is dropped, or the flush runs
        // against an instance nothing can reach (same invariant the deleted eviction loop had).
        val shutdown = coordinator.substringAfter("fun shutdown()").substringBefore("fun getDebugState()")
        val flushIdx = shutdown.indexOf("flushLearnedData()")
        val releaseIdx = shutdown.indexOf("wordPredictor = null")
        assertThat(flushIdx).isGreaterThan(-1)
        assertThat(releaseIdx).isGreaterThan(-1)
        assertThat(flushIdx).isLessThan(releaseIdx)
        assertThat(shutdown).contains("wordPredictor?.stopObservingDictionaryChanges()")
    }

    // ---------------------------------------------------------------- M5

    @Test
    fun `M5 - onStartInputView derives the incognito flag from imeOptions`() {
        val service = readSource("CleverKeysService.kt")
        assertThat(service).contains(
            "LearningGate.fieldAllowsPersonalizedLearning(info.imeOptions)"
        )

        val handler = readSource("SuggestionHandler.kt")
        // The flag rides into the learn funnel …
        assertThat(handler).contains("addWordToContext(word, fieldAllowsPersonalizedLearning)")
        // … gates adaptation recording …
        assertThat(handler).contains(
            "LearningGate.canLearnAdaptation(config.on_device_learning_enabled) &&\n" +
                "            fieldAllowsPersonalizedLearning"
        )
        // … and both next-word shouldShow call sites.
        val shouldShowWired = Regex(
            """fieldAllowsPersonalizedLearning = fieldAllowsPersonalizedLearning"""
        ).findAll(handler).count()
        assertThat(shouldShowWired).isEqualTo(2)
    }

    @Test
    fun `context-LM pref reaches both shouldShow call sites and the cursor-park editor read`() {
        // Audit 2026-08-26: `context_aware_predictions_enabled` is a required
        // shouldShow parameter because the Settings UI hides the next-word
        // toggle when the context LM is off — a stale-on feature pref must not
        // pass the gate, and the cursor-park path must not READ the editor text
        // in a state where no candidate can ever surface. Downstream
        // `getNextWordCandidates` fails closed too (LearningGate), but the gate
        // itself must be the honest answer, not rescued by a lower layer.
        val handler = readSource("SuggestionHandler.kt")
        val gateWired = Regex(
            """contextAwareEnabled = config\.context_aware_predictions_enabled"""
        ).findAll(handler).count()
        assertThat(gateWired).isEqualTo(2)

        // The cheap-gate set guarding the getTextBeforeCursor read includes it.
        val parkRead = handler.substringAfter("private fun readEditorParkContext")
            .substringBefore("getTextBeforeCursor")
        assertThat(parkRead).contains("config.context_aware_predictions_enabled")
    }

    // ---------------------------------------------------------------- M6

    @Test
    fun `M6 - queued next-word posts carry a bar-generation guard`() {
        val handler = readSource("SuggestionHandler.kt")
        assertThat(handler).contains("val generationAtSubmit")
        val guarded = Regex("""contentGeneration\(\) != generationAtSubmit\) return@post""")
            .findAll(handler).count()
        assertThat(guarded).isEqualTo(2) // maybeShowNextWordPredictions + swipe append

        val bar = readSource("SuggestionBar.kt")
        assertThat(bar).contains("contentGeneration++")
    }

    // ---------------------------------------------------------------- M7

    @Test
    fun `M7 - UserAdaptationManager delegates to the pure SelectionHistory core`() {
        val manager = readSource("UserAdaptationManager.kt")
        assertThat(manager).contains("SelectionHistory(")
        // Pruned words' preference keys are deleted on save.
        assertThat(manager).contains("snapshot.removals")
        assertThat(manager).contains("remove(KEY_WORD_SELECTIONS + word)")
    }

    // ------------------------------------------ TODO-553 (resolved 2026-08-06)

    @Test
    fun `swipe auto-insert tracks the COMMITTED word, not the raw prediction`() {
        val handler = readSource("SuggestionHandler.kt")
        // Replacement tracking uses onSuggestionSelected's RETURN (post final
        // autocorrect + I-word handling) so REPLACE deletion counts match the
        // editor even when the correction changed the word's length.
        assertThat(handler).containsMatch(
            """setLastAutoInsertedWord\(\s*committedWord \?: topPrediction\.removePrefix\("raw:"\)"""
        )
        // And the learn funnel records the FINAL word: the final-autocorrect
        // rewrite (processedWord = correctedWord) happens BEFORE the single
        // updateContext(processedWord) learn call in onSuggestionSelected.
        val rewriteIdx = handler.indexOf("processedWord = correctedWord")
        val learnIdx = handler.indexOf("updateContext(processedWord)")
        assertThat(rewriteIdx).isGreaterThan(-1)
        assertThat(learnIdx).isGreaterThan(rewriteIdx)
        assertThat(handler.indexOf("updateContext(processedWord)", learnIdx + 1)).isEqualTo(-1)
    }

    // -------------------------------------------- L5 (resolved 2026-08-06)

    @Test
    fun `L5 - cursor park predicts from the editor text before the cursor`() {
        // InputCoordinator threads the live InputConnection into the delegate.
        val ic = readSource("InputCoordinator.kt")
        assertThat(ic).contains("handleCursorParkPrediction(editorInfo, ic)")

        val handler = readSource("SuggestionHandler.kt")
        // The park path tokenizes real editor text via the pure helper …
        assertThat(handler).contains("NextWordPredictor.contextFromEditorText")
        // … and the editor read stays behind the cheap next-word prerequisites
        // (feature pref, master gate, per-field incognito flag) so fields that
        // can never surface candidates are never even read.
        assertThat(handler).containsMatch(
            """(?s)fun readEditorParkContext.{0,600}next_word_prediction_enabled.{0,200}on_device_learning_enabled.{0,200}fieldAllowsPersonalizedLearning"""
        )
    }

    // ------------------------------------------- ARC-020 (2026-08-28)

    @Test
    fun `the static next-word seed is read only inside the already-gated path`() {
        // ARC-020's cold-start tier reads SHIPPED data, so `getStaticNextWordSeed`
        // deliberately carries no LearningGate check of its own. What keeps that
        // honest is placement: it may only be called from the two helpers that
        // already ran `NextWordPredictor.shouldShow`. A third call site — or a
        // call moved above the gate — would surface next-word content in a state
        // the user's prefs say must show nothing.
        val handler = readSource("SuggestionHandler.kt")
        val seedReads = Regex("""predictor\.getStaticNextWordSeed\(""").findAll(handler).count()
        assertThat(seedReads).isEqualTo(2)

        for (fn in listOf("generateNextWordCandidates", "maybeShowNextWordPredictions")) {
            val body = handler.substringAfter("private fun $fn")
                .substringBefore("getStaticNextWordSeed")
            assertWithMessage("$fn must run shouldShow before reading the static seed")
                .that(body).contains("NextWordPredictor.shouldShow(")
        }

        // WordPredictor's accessor exists and does NOT re-derive a gate.
        val predictor = readSource("WordPredictor.kt")
        assertThat(predictor).contains("fun getStaticNextWordSeed(")
        val accessor = predictor.substringAfter("fun getStaticNextWordSeed(")
            .substringBefore("\n    }")
        assertThat(accessor).doesNotContain("LearningGate")
        assertThat(accessor).contains("bigramModel?.getPredictions(")
    }

    @Test
    fun `the shipped bigram assets are actually loaded (ARC-010)`() {
        // These six assets shipped in 2025-11 and were read by nothing until
        // ARC-010, because the only loader referencing them had zero callers.
        // Pin both trigger points so they cannot go dead again.
        val predictor = readSource("WordPredictor.kt")
        val loads = Regex("""loadStaticContinuationsAsync\(""").findAll(predictor).count()
        assertThat(loads).isEqualTo(2) // setContext + setLanguage

        val model = readSource("BigramModel.kt")
        assertThat(model).contains("""fun assetNameFor(language: String): String = "bigrams/${'$'}{language}_bigrams.json"""")
        // The asset must NOT reach the scoring tables: its values are curated
        // rank scores, and interpolating them in getContextualProbability would
        // pin getContextMultiplier at its 10x clamp for every listed pair.
        val loader = model.substringAfter("fun loadStaticContinuations(")
            .substringBefore("fun loadStaticContinuationsAsync")
        assertThat(loader).doesNotContain("languageUnigramProbs")
        assertThat(loader).contains("seedIndexes[language] = index")
    }

    // ------------------------------- autocorrect-undo learning rollback (2026-08-06)

    @Test
    fun `autocorrect undo rolls the rejected word back out of the learn funnel`() {
        // Behavior of the store-side inverse is covered by
        // [tribixbite.cleverkeys.contextaware.NgramRollbackTest]; this pins the
        // production wiring: undo → WordPredictor.rollbackCommittedWord →
        // ContextModel.rollbackCommit, with the field's incognito flag riding
        // along so a gate-suppressed learn is never decremented.
        val handler = readSource("SuggestionHandler.kt")
        assertThat(handler).contains(
            "rollbackCommittedWord(correctedWord, fieldAllowsPersonalizedLearning)"
        )

        val predictor = readSource("WordPredictor.kt")
        assertThat(predictor).contains("fun rollbackCommittedWord")
        assertThat(predictor).contains("contextModel?.rollbackCommit")
    }

    // ---------------------------------------------------------------- L1

    @Test
    fun `L1 - provenance popup dismissed on clear and temporary message`() {
        val bar = readSource("SuggestionBar.kt")
        // clearSuggestions dismisses BEFORE its early-return guards.
        assertThat(bar).containsMatch(
            """(?s)fun clearSuggestions\(\) \{.{0,400}dismissProvenancePopup\(\).{0,600}isShowingTemporaryMessage"""
        )
        assertThat(bar).containsMatch(
            """(?s)fun showTemporaryMessage\(.{0,500}dismissProvenancePopup\(\)"""
        )
    }

    /** Read a source file from the main source tree */
    private fun readSource(filename: String): String {
        val projectDir = System.getProperty("user.dir") ?: "."
        val file = File(projectDir, "src/main/kotlin/tribixbite/cleverkeys/$filename")
        assertThat(file.exists()).isTrue()
        return file.readText()
    }
}
