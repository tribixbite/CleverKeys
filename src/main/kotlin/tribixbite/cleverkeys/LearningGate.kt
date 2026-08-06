package tribixbite.cleverkeys

/**
 * MASTER PRIVACY GATE for all on-device learning (audit follow-up 2026-08-06,
 * Task A). Single source of truth for the question "may this typing-derived
 * signal be recorded/persisted right now?".
 *
 * The `on_device_learning_enabled` preference (default ON; this is the opt-OUT)
 * short-circuits EVERY learn path at the write layer:
 *
 * | Path | Store | Gated via |
 * |---|---|---|
 * | Context LM (bigrams + trigrams) | `BigramStore`/`TrigramStore` | [learnCommittedWord] → [canLearnContext] |
 * | Personalization vocabulary | `UserVocabulary` | [learnCommittedWord] → [canLearnPersonalization] (plus `PersonalizationEngine.setEnabled` sync in `WordPredictor.setConfig`) |
 * | Selection adaptation | `UserAdaptationManager` prefs | [canLearnAdaptation] (call site: `SuggestionHandler.onSuggestionSelected`) |
 * | Swipe-ML traces | `SwipeMLDataStore` | [canCollectSwipeMl] (call site: `PrivacyManager.canCollectSwipeData`, checked by `MLDataCollector` before storing) |
 *
 * READ paths that surface previously learned data are gated too, so turning the
 * master off makes the learned stores fully inert (neither written nor read):
 * [canUseLearnedContext] (dynamic context boost + next-word candidate source)
 * and the personalization boost (returns 0 once the engine is disabled).
 *
 * Everything here is pure JVM so the privacy contract is unit-testable
 * ([tribixbite.cleverkeys.OnDeviceLearningPrivacyTest] wires this funnel to the
 * real stores over in-memory storage and asserts nothing is recorded or
 * persisted with the master gate off).
 */
object LearningGate {

    /** Longest word window handed to the context LM (trigram-ready, audit §1.3-D). */
    const val CONTEXT_WINDOW = 4

    /** May the context LM (bigram/trigram stores) record new sequences? */
    fun canLearnContext(onDeviceLearningEnabled: Boolean, contextAwareEnabled: Boolean): Boolean =
        onDeviceLearningEnabled && contextAwareEnabled

    /** May the personalization vocabulary record word usage? */
    fun canLearnPersonalization(onDeviceLearningEnabled: Boolean, personalizedLearningEnabled: Boolean): Boolean =
        onDeviceLearningEnabled && personalizedLearningEnabled

    /**
     * May `UserAdaptationManager` record a suggestion selection?
     * (Pre-existing privacy gap: this store had NO preference gate at all — it
     * recorded and persisted selection counts unconditionally. The master gate
     * now covers it.)
     */
    fun canLearnAdaptation(onDeviceLearningEnabled: Boolean): Boolean = onDeviceLearningEnabled

    /** May swipe-ML trajectory data be collected/stored? */
    fun canCollectSwipeMl(onDeviceLearningEnabled: Boolean, collectSwipeEnabled: Boolean): Boolean =
        onDeviceLearningEnabled && collectSwipeEnabled

    /**
     * May previously learned context data be READ (dynamic context boost,
     * next-word candidate generation)? Master off ⇒ the learned store is fully
     * inert, not just frozen.
     */
    fun canUseLearnedContext(onDeviceLearningEnabled: Boolean, contextAwareEnabled: Boolean): Boolean =
        onDeviceLearningEnabled && contextAwareEnabled

    /**
     * THE learn funnel for a committed word (production caller:
     * `WordPredictor.addWordToContext`). Runs each learn path only when its
     * gate passes — with the master gate off, NEITHER lambda is invoked, so no
     * in-RAM state mutates and nothing can be persisted.
     *
     * @param recentWords rolling committed-word window, most recent last,
     *   INCLUDING [committedWord]
     * @param committedWord the normalized word just committed
     * @param recordSequence context-LM sink (`ContextModel.recordSequence`)
     * @param recordWordUsage personalization sink (`PersonalizationEngine.recordWordTyped`)
     */
    fun learnCommittedWord(
        recentWords: List<String>,
        committedWord: String,
        onDeviceLearningEnabled: Boolean,
        contextAwareEnabled: Boolean,
        personalizedLearningEnabled: Boolean,
        recordSequence: (List<String>) -> Unit,
        recordWordUsage: (String) -> Unit
    ) {
        if (canLearnContext(onDeviceLearningEnabled, contextAwareEnabled) && recentWords.size >= 2) {
            val sequenceLength = kotlin.math.min(CONTEXT_WINDOW, recentWords.size)
            recordSequence(recentWords.takeLast(sequenceLength))
        }
        if (canLearnPersonalization(onDeviceLearningEnabled, personalizedLearningEnabled)) {
            recordWordUsage(committedWord)
        }
    }
}
