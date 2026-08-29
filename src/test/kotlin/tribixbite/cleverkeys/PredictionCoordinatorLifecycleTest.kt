package tribixbite.cleverkeys

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.contextaware.ContextContinuation
import tribixbite.cleverkeys.personalization.BoostExplanation
import tribixbite.cleverkeys.swipe.SwipeContextRescorer

/**
 * ARC-079 — the learned-data lifecycle after the duplicate-residency deletion.
 *
 * `DictionaryManager` used to keep its own per-language `WordPredictor` cache, and
 * [PredictionCoordinator.flushLearnedData] / [PredictionCoordinator.shutdown] fanned out to it.
 * That cache is gone, so the coordinator's single predictor is now the ONLY holder of
 * context-LM bigrams and personalization vocabulary in the process — which makes these two
 * methods the only remaining checkpoint on the way to disk. A dropped call here loses whatever
 * the user typed since the last input-session boundary, silently (there is no finalizer).
 *
 * Driven through the [Predictor] interface with a recording fake (ARC-048 R6), so no dictionary
 * load, no Android `Context` and no 5-10 MB of heap are involved. The coordinator itself is
 * built via Objenesis and reflection-injected — the same idiom [DictionaryManagerTest] uses to
 * step around the android.jar stub chain in its constructor.
 */
class PredictionCoordinatorLifecycleTest {

    // =====================================================================================
    // Recording fake: the four lifecycle members this test exercises record their calls;
    // everything else throws so a widened reach fails loudly instead of no-oping.
    // =====================================================================================

    private class RecordingPredictor : Predictor {
        var persistCalls = 0
            private set
        var clearContextCalls = 0
            private set
        var stopObservingCalls = 0
            private set

        /** Call order, so "persist before release" is checkable, not just "both happened". */
        val calls = mutableListOf<String>()

        override fun persistLearnedData() {
            persistCalls++
            calls += "persist"
        }

        override fun clearContext() {
            clearContextCalls++
            calls += "clearContext"
        }

        override fun stopObservingDictionaryChanges() {
            stopObservingCalls++
            calls += "stopObserving"
        }

        override fun setConfig(config: Config) = unsupported("setConfig")
        override fun loadDictionaryAsync(
            context: android.content.Context,
            language: String,
            callback: Runnable?
        ) = unsupported("loadDictionaryAsync")

        override fun loadSecondaryDictionary(language: String): Boolean =
            unsupported("loadSecondaryDictionary")

        override fun unloadSecondaryDictionary() = unsupported("unloadSecondaryDictionary")
        override fun isLoading(): Boolean = false
        override fun reloadCustomAndUserWords() = unsupported("reloadCustomAndUserWords")

        override fun predictWordsWithContext(
            keySequence: String,
            context: List<String>
        ): WordPredictor.PredictionResult = unsupported("predictWordsWithContext")

        override fun autoCorrect(typedWord: String): String = unsupported("autoCorrect")
        override fun isInDictionary(word: String): Boolean = unsupported("isInDictionary")
        override fun isInUserVocabulary(word: String): Boolean = unsupported("isInUserVocabulary")
        override fun isWordDisabled(word: String): Boolean = unsupported("isWordDisabled")
        override fun applyUserWordCaseToList(words: List<String>): List<String> =
            unsupported("applyUserWordCaseToList")

        override fun addWordToContext(word: String?, fieldAllowsPersonalizedLearning: Boolean) =
            unsupported("addWordToContext")

        override fun rollbackCommittedWord(
            word: String,
            fieldAllowsPersonalizedLearning: Boolean
        ) = unsupported("rollbackCommittedWord")

        override fun onSentenceBoundary() = unsupported("onSentenceBoundary")
        override fun reset() = unsupported("reset")

        override fun getNextWordCandidates(
            contextWords: List<String>,
            maxResults: Int
        ): List<ContextContinuation> = unsupported("getNextWordCandidates")

        override fun getStaticNextWordSeed(
            contextWords: List<String>,
            maxResults: Int
        ): List<StaticBigramSeed.Continuation> = unsupported("getStaticNextWordSeed")

        override fun getPersonalizationBoostFor(word: String): Float =
            unsupported("getPersonalizationBoostFor")

        override fun getSwipeContextEvidence(
            words: List<String>,
            contextWords: List<String>
        ): List<SwipeContextRescorer.Evidence>? = unsupported("getSwipeContextEvidence")

        override fun explainScore(
            word: String,
            keySequence: String,
            context: List<String>
        ): ScoreBreakdown? = unsupported("explainScore")

        override fun explainPersonalization(word: String): BoostExplanation? =
            unsupported("explainPersonalization")

        private fun unsupported(member: String): Nothing =
            throw UnsupportedOperationException("RecordingPredictor.$member not needed here")
    }

    // =====================================================================================

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    /**
     * Build a [PredictionCoordinator] without running its constructor and inject [predictor]
     * as the live one. `dictionaryManager` stays null — after ARC-079 the coordinator's flush
     * and teardown must be complete without it.
     */
    private fun coordinatorWith(predictor: Predictor): PredictionCoordinator {
        val coordinator = org.objenesis.ObjenesisStd()
            .newInstance(PredictionCoordinator::class.java)
        val field = PredictionCoordinator::class.java.getDeclaredField("wordPredictor")
        field.isAccessible = true
        field.set(coordinator, predictor)
        return coordinator
    }

    @Test
    fun `flushLearnedData checkpoints and clears the coordinator-owned predictor`() {
        val fake = RecordingPredictor()
        val coordinator = coordinatorWith(fake)

        coordinator.flushLearnedData()

        // Persist first, then drop the rolling window: clearing before the save would throw
        // away context the store had not yet been told about.
        assertThat(fake.calls).containsExactly("persist", "clearContext").inOrder()
        assertThat(fake.persistCalls).isEqualTo(1)
        assertThat(fake.clearContextCalls).isEqualTo(1)
    }

    @Test
    fun `shutdown checkpoints before releasing the predictor`() {
        val fake = RecordingPredictor()
        val coordinator = coordinatorWith(fake)
        assertThat(coordinator.isWordPredictionAvailable()).isTrue()

        coordinator.shutdown()

        // The full teardown contract: flush (persist + clear), detach the dictionary observer,
        // then drop the reference. Ordering matters — an observer left attached outlives the
        // predictor, and a persist after the null-out never runs at all.
        assertThat(fake.calls)
            .containsExactly("persist", "clearContext", "stopObserving").inOrder()
        assertThat(coordinator.getWordPredictor()).isNull()
        assertThat(coordinator.getDictionaryManager()).isNull()
        assertThat(coordinator.isWordPredictionAvailable()).isFalse()
    }

    @Test
    fun `flushLearnedData survives a throwing predictor`() {
        // The flush runs on an input-session boundary (onFinishInputView). A store-side
        // failure there must not propagate into the IME lifecycle callback.
        val throwing = object : Predictor by RecordingPredictor() {
            override fun persistLearnedData(): Unit = throw IllegalStateException("store down")
        }
        val coordinator = coordinatorWith(throwing)

        coordinator.flushLearnedData() // must not throw
    }
}
