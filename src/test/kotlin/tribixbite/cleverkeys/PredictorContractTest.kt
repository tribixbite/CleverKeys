package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.contextaware.ContextContinuation
import tribixbite.cleverkeys.personalization.BoostExplanation
import tribixbite.cleverkeys.swipe.SwipeContextRescorer
import java.io.File

/**
 * The milestone for ARC-048 / 5-architecture.md R6: **a test that drives the prediction
 * contract through a fake instead of the real 2,600-line [WordPredictor]**.
 *
 * Before [Predictor] existed this file could not be written. Every consumer named the
 * concrete class, so exercising the next-word assembly meant constructing a WordPredictor,
 * which means an Android `Context`, a dictionary load, a `ContextModel` backed by the
 * process-wide bigram/trigram stores, and ~5-10 MB of heap — i.e. an instrumented test on a
 * device. [FakePredictor] below is 60 lines of in-memory state and runs in the pure JVM
 * suite.
 *
 * Two things are asserted:
 *  1. the interface is a SUFFICIENT contract for the next-word pipeline stage — the fake is
 *     wired exactly as `SuggestionHandler.maybeShowNextWordPredictions` wires the real
 *     predictor into `NextWordPredictor.generate`, and the tiering (learned first, shipped
 *     seed only filling leftover slots) comes out right;
 *  2. the seam does not silently close again — `WordPredictor` must keep declaring
 *     `: Predictor`, and `PredictionCoordinator.getWordPredictor()` must keep handing
 *     consumers the interface rather than the concrete class.
 */
class PredictorContractTest {

    // =====================================================================================
    // The fake. Real in-memory behaviour, no mocking framework: the members the tests do not
    // use throw, so a future test that widens its reach fails loudly instead of reading a
    // silently-empty stub.
    // =====================================================================================

    private class FakePredictor(
        private val learned: List<ContextContinuation> = emptyList(),
        private val seed: List<StaticBigramSeed.Continuation> = emptyList(),
        private val dictionary: Set<String> = emptySet(),
        private val userVocabulary: Set<String> = emptySet(),
        private val disabled: Set<String> = emptySet(),
        private val boosts: Map<String, Float> = emptyMap(),
    ) : Predictor {

        /** Context the fake has been told about, oldest first — the learn-path observation. */
        val context = mutableListOf<String>()

        /** Words learned with the per-field gate OPEN (incognito fields must not land here). */
        val learnedWords = mutableListOf<String>()

        var sentenceBoundaries = 0
            private set

        // ---- Next word ------------------------------------------------------------------

        override fun getNextWordCandidates(contextWords: List<String>, maxResults: Int) =
            learned.take(maxResults)

        override fun getStaticNextWordSeed(contextWords: List<String>, maxResults: Int) =
            seed.take(maxResults)

        override fun getPersonalizationBoostFor(word: String) = boosts[word] ?: 0f

        override fun getSwipeContextEvidence(
            words: List<String>,
            contextWords: List<String>
        ): List<SwipeContextRescorer.Evidence>? = null

        // ---- Query ----------------------------------------------------------------------

        override fun isInDictionary(word: String) = word in dictionary
        override fun isInUserVocabulary(word: String) = word in userVocabulary
        override fun isWordDisabled(word: String) = word in disabled
        override fun applyUserWordCaseToList(words: List<String>) = words
        override fun autoCorrect(typedWord: String) = typedWord

        override fun predictWordsWithContext(
            keySequence: String,
            context: List<String>
        ): WordPredictor.PredictionResult {
            val hits = dictionary.filter { it.startsWith(keySequence) }.sorted()
            return WordPredictor.PredictionResult(hits, hits.indices.map { 1000 - it })
        }

        // ---- Context / learning ---------------------------------------------------------

        override fun addWordToContext(word: String?, fieldAllowsPersonalizedLearning: Boolean) {
            val w = word ?: return
            context += w
            if (fieldAllowsPersonalizedLearning) learnedWords += w
        }

        override fun rollbackCommittedWord(
            word: String,
            fieldAllowsPersonalizedLearning: Boolean
        ) {
            context.remove(word)
            if (fieldAllowsPersonalizedLearning) learnedWords.remove(word)
        }

        override fun clearContext() = context.clear()
        override fun onSentenceBoundary() { sentenceBoundaries++ }
        override fun reset() { /* transient state only; the fake has none */ }

        // ---- Lifecycle / provenance: not exercised here ---------------------------------

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
        override fun persistLearnedData() = unsupported("persistLearnedData")
        override fun stopObservingDictionaryChanges() = unsupported("stopObserving")
        override fun explainScore(
            word: String,
            keySequence: String,
            context: List<String>
        ): ScoreBreakdown? = null
        override fun explainPersonalization(word: String): BoostExplanation? = null

        private fun unsupported(member: String): Nothing =
            throw UnsupportedOperationException("FakePredictor.$member not needed by this test")
    }

    /**
     * The exact wiring of `SuggestionHandler.maybeShowNextWordPredictions` (SuggestionHandler.kt
     * around the `predictionTasks.cancelAndSubmit` block), lifted verbatim so this test breaks if
     * the production call shape drifts away from what the interface can supply.
     */
    private fun nextWordThrough(
        predictor: Predictor,
        contextWords: List<String>
    ): List<NextWordPredictor.Candidate> {
        val learned = predictor.getNextWordCandidates(contextWords, maxResults = 10)
        return NextWordPredictor.generate(
            learned = learned,
            lastCommittedWord = contextWords.lastOrNull(),
            personalizationBoost = { predictor.getPersonalizationBoostFor(it) },
            isWordAllowed = { w ->
                !predictor.isWordDisabled(w) &&
                    (predictor.isInDictionary(w) || predictor.isInUserVocabulary(w))
            },
            staticSeed = predictor.getStaticNextWordSeed(
                contextWords,
                NextWordPredictor.MAX_SUGGESTIONS
            )
        )
    }

    // =====================================================================================
    // 1. The interface is sufficient for the next-word stage
    // =====================================================================================

    @Test
    fun fakePredictorDrivesTheNextWordPipeline() {
        val fake = FakePredictor(
            learned = listOf(
                ContextContinuation("morning", frequency = 9, probability = 0.40f),
                ContextContinuation("night", frequency = 4, probability = 0.20f),
            ),
            dictionary = setOf("morning", "night", "afternoon"),
            boosts = mapOf("night" to 6f), // 1 + 6/4 = 2.5× — enough to overtake "morning"
        )

        val candidates = nextWordThrough(fake, listOf("good"))

        // Personalization reorders WITHIN the learned tier: 0.20 × 2.5 = 500 beats
        // 0.40 × 1.0 = 400. That is `NextWordPredictor`'s documented ranking, reached here
        // entirely through the interface.
        assertThat(candidates.map { it.word }).containsExactly("night", "morning").inOrder()
        assertThat(candidates.map { it.score }).containsExactly(500, 400).inOrder()
        assertThat(candidates.none { it.fromStaticSeed }).isTrue()
    }

    @Test
    fun fakePredictorShowsTheStaticSeedFillingOnlyLeftoverSlots() {
        val fake = FakePredictor(
            // One learned candidate clears the floors; two bar slots are left over.
            learned = listOf(ContextContinuation("morning", frequency = 9, probability = 0.40f)),
            seed = listOf(
                StaticBigramSeed.Continuation("luck", rank = 0.90f),
                StaticBigramSeed.Continuation("morning", rank = 0.80f), // dedup vs learned
                StaticBigramSeed.Continuation("news", rank = 0.70f),
            ),
            dictionary = setOf("morning", "luck", "news"),
        )

        val candidates = nextWordThrough(fake, listOf("good"))

        assertThat(candidates.map { it.word }).containsExactly("morning", "luck", "news").inOrder()
        // Learned first, seed strictly below the ceiling — the tier relationship the
        // provenance sheet promises the user.
        assertThat(candidates[0].fromStaticSeed).isFalse()
        assertThat(candidates.drop(1).all { it.fromStaticSeed }).isTrue()
        assertThat(candidates.drop(1).all { it.score <= NextWordPredictor.STATIC_SEED_SCORE_CEILING })
            .isTrue()
    }

    @Test
    fun fakePredictorHonoursTheAllowFilterTheInterfaceExposes() {
        val fake = FakePredictor(
            learned = listOf(
                ContextContinuation("teh", frequency = 9, probability = 0.40f),      // typo, absent
                ContextContinuation("banned", frequency = 8, probability = 0.30f),   // disabled
                ContextContinuation("keyboard", frequency = 7, probability = 0.20f), // user vocab
            ),
            dictionary = setOf("banned"),
            userVocabulary = setOf("keyboard"),
            disabled = setOf("banned"),
        )

        // `isWordAllowed` is assembled from THREE interface members
        // (isWordDisabled / isInDictionary / isInUserVocabulary); all three have to be on
        // the contract for the production filter to be expressible against a fake.
        assertThat(nextWordThrough(fake, listOf("my")).map { it.word })
            .containsExactly("keyboard")
    }

    // =====================================================================================
    // 2. Behaviour tests that were previously device-only
    // =====================================================================================

    @Test
    fun perFieldLearningGateIsObservableThroughTheInterface() {
        val fake = FakePredictor()
        val predictor: Predictor = fake

        predictor.addWordToContext("hello", fieldAllowsPersonalizedLearning = true)
        predictor.addWordToContext("hunter2", fieldAllowsPersonalizedLearning = false)

        // Both words are context; only the non-incognito one is learned. This is the
        // distinction `SuggestionHandler.commitWord` depends on, now assertable in the pure
        // suite.
        assertThat(fake.context).containsExactly("hello", "hunter2").inOrder()
        assertThat(fake.learnedWords).containsExactly("hello")

        predictor.rollbackCommittedWord("hello", fieldAllowsPersonalizedLearning = true)
        assertThat(fake.learnedWords).isEmpty()

        predictor.onSentenceBoundary()
        predictor.clearContext()
        assertThat(fake.sentenceBoundaries).isEqualTo(1)
        assertThat(fake.context).isEmpty()
    }

    @Test
    fun predictionResultTravelsThroughTheInterface() {
        val predictor: Predictor = FakePredictor(dictionary = setOf("key", "keyboard", "kite"))

        val result = predictor.predictWordsWithContext("key", context = emptyList())

        assertThat(result.words).containsExactly("key", "keyboard").inOrder()
        assertThat(result.scores).hasSize(2)
    }

    // =====================================================================================
    // 3. Drift pins — the seam must not quietly close again
    // =====================================================================================

    private fun mainSource(name: String): String {
        val f = File("src/main/kotlin/tribixbite/cleverkeys/$name")
        check(f.isFile) { "expected $name at ${f.absolutePath} — run from the project root" }
        return f.readText()
    }

    @Test
    fun wordPredictorImplementsPredictor() {
        assertThat(mainSource("WordPredictor.kt")).contains("class WordPredictor : Predictor")
    }

    @Test
    fun predictionCoordinatorHandsOutTheInterfaceNotTheConcreteClass() {
        val src = mainSource("PredictionCoordinator.kt")
        // The single seam every SuggestionHandler call goes through. If this reverts to
        // `: WordPredictor?`, consumers silently re-couple to the 2,600-line class and this
        // whole test file stops meaning anything.
        assertThat(src).contains("fun getWordPredictor(): Predictor?")
        assertThat(src).contains("private var wordPredictor: Predictor? = null")
    }
}
