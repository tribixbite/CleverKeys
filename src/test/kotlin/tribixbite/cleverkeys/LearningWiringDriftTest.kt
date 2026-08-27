package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
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

        // Every per-language predictor's window is cleared too.
        val dictManager = readSource("DictionaryManager.kt")
        assertThat(dictManager).contains("predictor.clearContext()")

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
    fun `M2 - DictionaryManager threads the global config into its predictors`() {
        val dictManager = readSource("DictionaryManager.kt")
        // Both construction sites (setLanguage + preloadLanguages).
        val threaded = Regex("""Config\.globalConfigOrNull\(\)\?\.let \{ setConfig\(it\) \}""")
            .findAll(dictManager).count()
        assertThat(threaded).isEqualTo(2)
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
