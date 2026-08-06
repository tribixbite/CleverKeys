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
