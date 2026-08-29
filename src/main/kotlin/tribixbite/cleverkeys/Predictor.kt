package tribixbite.cleverkeys

import android.content.Context
import tribixbite.cleverkeys.contextaware.ContextContinuation
import tribixbite.cleverkeys.personalization.BoostExplanation
import tribixbite.cleverkeys.swipe.SwipeContextRescorer

/**
 * The word-prediction contract the IME actually consumes (ARC-048 / 5-architecture.md R6).
 *
 * [WordPredictor] is a 2,600-line concrete class that used to implement nothing, so no
 * consumer could be exercised against a stand-in and every test that wanted a predictor had
 * to load a real dictionary on a real device. This interface is the beachhead: it is
 * deliberately NOT "every public member of WordPredictor" — it is exactly the surface the
 * consumers call, derived from
 *
 * ```
 * rg -o 'predictor\??\.\w+|wordPredictor\??\.\w+' SuggestionHandler.kt PredictionCoordinator.kt \
 *    Keyboard2View.kt
 * ```
 * (DictionaryManager was a fourth caller until ARC-079 deleted its predictor cache.)
 *
 * Anything WordPredictor exposes that no consumer calls (scoring internals, dictionary
 * bookkeeping, `isReady`, the `signalReloadNeeded` companion) stays off the interface on
 * purpose: widening it later is cheap, narrowing it is not.
 *
 * The members are grouped by the phase that calls them, because the phases have different
 * lifetimes and a fake usually only needs one group:
 *
 * | Group | Called by | Notes |
 * |---|---|---|
 * | Lifecycle | `PredictionCoordinator` | dictionary load/unload, persistence, teardown |
 * | Query | `SuggestionHandler` | per-keystroke; must be cheap |
 * | Context/learning | `SuggestionHandler` | word commit, undo, sentence boundary |
 * | Next word | `SuggestionHandler` → `NextWordPredictor` | learned + shipped-seed continuations |
 * | Provenance | `SuggestionHandler` (inspection sheet) | debug/explain only, nullable |
 *
 * Default argument values live HERE rather than on the override: Kotlin forbids an override
 * from restating a default, and callers that omit the argument resolve it through this
 * declaration, so behaviour is unchanged for both interface-typed and `WordPredictor`-typed
 * receivers.
 *
 * Implemented by [WordPredictor] (production) — pinned by `PredictorContractTest`.
 */
interface Predictor {

    // ---- Lifecycle -------------------------------------------------------------------

    /** Push the live [Config]; also re-syncs the personalization/learning master gates. */
    fun setConfig(config: Config)

    /** Load [language]'s primary dictionary off the caller's thread; [callback] on completion. */
    fun loadDictionaryAsync(context: Context, language: String, callback: Runnable?)

    /** @return true when a secondary dictionary for [language] was loaded. */
    fun loadSecondaryDictionary(language: String): Boolean

    /** Drop the secondary dictionary and its index (memory reclaim on language switch). */
    fun unloadSecondaryDictionary()

    /** @return true while a dictionary load is in flight — suggestions are not meaningful yet. */
    fun isLoading(): Boolean

    /** Re-read the user's custom words / disabled words after an external edit. */
    fun reloadCustomAndUserWords()

    /** Flush learned data (context LM bigrams + personalization) to disk. */
    fun persistLearnedData()

    /** Detach the UserDictionary observer — call before dropping the instance. */
    fun stopObservingDictionaryChanges()

    // ---- Query -----------------------------------------------------------------------

    /** Rank dictionary candidates for the typed [keySequence] under [context]. */
    fun predictWordsWithContext(
        keySequence: String,
        context: List<String>
    ): WordPredictor.PredictionResult

    /** Best-effort single-word correction of [typedWord]; returns [typedWord] when none applies. */
    fun autoCorrect(typedWord: String): String

    /** @return true if [word] is in the loaded (primary or secondary) dictionary. */
    fun isInDictionary(word: String): Boolean

    /** @return true if [word] is in the user's learned personal vocabulary. */
    fun isInUserVocabulary(word: String): Boolean

    /** @return true if the user has explicitly disabled [word] from suggestions. */
    fun isWordDisabled(word: String): Boolean

    /** Restore user-dictionary casing ("iPhone", proper nouns) over lowercase storage forms. */
    fun applyUserWordCaseToList(words: List<String>): List<String>

    // ---- Context / learning ----------------------------------------------------------

    /**
     * Record a committed [word] as context, and learn from it when the field permits.
     * [fieldAllowsPersonalizedLearning] is the per-field incognito gate, NOT the master pref.
     */
    fun addWordToContext(word: String?, fieldAllowsPersonalizedLearning: Boolean = true)

    /** Undo a learn performed by [addWordToContext] (autocorrect reverted by the user). */
    fun rollbackCommittedWord(word: String, fieldAllowsPersonalizedLearning: Boolean = true)

    /** Clear the rolling context window (input-session boundary). */
    fun clearContext()

    /** Mark a sentence boundary so the next word is not conditioned on the previous one. */
    fun onSentenceBoundary()

    /** Reset transient prediction state without touching learned data. */
    fun reset()

    // ---- Next word -------------------------------------------------------------------

    /** Learned continuations of [contextWords]; empty when the learning gates are closed. */
    fun getNextWordCandidates(
        contextWords: List<String>,
        maxResults: Int = 10
    ): List<ContextContinuation>

    /** Shipped (not learned) continuations used only to fill slots the learned store could not. */
    fun getStaticNextWordSeed(
        contextWords: List<String>,
        maxResults: Int
    ): List<StaticBigramSeed.Continuation>

    /** Personalization boost (0..6) for [word]; 0 when personalization is off or unknown. */
    fun getPersonalizationBoostFor(word: String): Float

    /** Per-word context evidence for swipe rescoring; null when the gates are closed or cold. */
    fun getSwipeContextEvidence(
        words: List<String>,
        contextWords: List<String>
    ): List<SwipeContextRescorer.Evidence>?

    // ---- Provenance (inspection sheet) -----------------------------------------------

    /** Score breakdown for the suggestion inspection sheet; null when unavailable. */
    fun explainScore(word: String, keySequence: String, context: List<String>): ScoreBreakdown?

    /** Personalization breakdown for the inspection sheet; null when unavailable. */
    fun explainPersonalization(word: String): BoostExplanation?
}
