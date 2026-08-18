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
 *
 * OUT OF SCOPE — deliberate (L7, review 2026-08-06; the settings toggle copy is
 * scoped to match): the master gate covers AUTOMATIC recording of typing
 * behavior. Data the user EXPLICITLY creates is governed by its own controls:
 * - `SwipeCalibrationActivity` traces — recorded only during a calibration
 *   session the user starts, behind its own consent flow.
 * - `SwipePerformanceStats` — prediction latency/accuracy aggregates behind
 *   the separate performance-stats preference (no text content).
 * - Backup restore — importing a backup repopulates learned stores even with
 *   the master off; restoring one's own exported data is an explicit act.
 */
object LearningGate {

    /** Longest word window handed to the context LM (trigram-ready, audit §1.3-D). */
    const val CONTEXT_WINDOW = 4

    /**
     * Mirror of `android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`
     * (`0x1000000`, API 26+). Duplicated here so the incognito-field gate logic is
     * pure JVM and unit-testable without android.jar; [LearningGateTest] pins the
     * value against the platform constant.
     */
    const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000

    /**
     * Incognito-keyboard contract (M5, review 2026-08-06): an editor that sets
     * `IME_FLAG_NO_PERSONALIZED_LEARNING` (e.g. a browser's private tab) asks the
     * IME not to learn from — or personalize based on — what is typed in it.
     *
     * @param imeOptions the raw `EditorInfo.imeOptions` bits for the active field
     * @return false when the field forbids personalized learning
     */
    fun fieldAllowsPersonalizedLearning(imeOptions: Int): Boolean =
        (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) == 0

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

    /**
     * May previously learned SELECTION history be READ (H3, review 2026-08-06)?
     * The adaptation multiplier re-ranks predictions and suppresses
     * add-to-dictionary prompts from persisted selection counts — with the
     * master off that history must be inert, not just frozen (same contract as
     * [canUseLearnedContext] for the context LM).
     */
    fun canUseAdaptation(onDeviceLearningEnabled: Boolean): Boolean = onDeviceLearningEnabled

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
     * gate passes — with the master gate off (or the active field forbidding
     * personalized learning, M5), NEITHER lambda is invoked, so no in-RAM
     * state mutates and nothing can be persisted.
     *
     * The context sink receives the trailing [CONTEXT_WINDOW]-word window; the
     * production sink (`ContextModel.recordCommit`) records ONLY the NEWEST
     * bigram/trigram ending at [committedWord] (M3, review 2026-08-06 — the
     * previous full-window replay re-recorded earlier pairs on every commit,
     * so a single typing inflated frequencies past the "seen ≥2×" floor).
     *
     * @param recentWords rolling committed-word window, most recent last,
     *   INCLUDING [committedWord]
     * @param committedWord the normalized word just committed
     * @param fieldAllowsPersonalizedLearning false when the active editor set
     *   `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito field) — suppresses
     *   BOTH learn paths regardless of user preferences
     * @param recordSequence context-LM sink (`ContextModel.recordCommit`)
     * @param recordWordUsage personalization sink (`PersonalizationEngine.recordWordTyped`)
     */
    fun learnCommittedWord(
        recentWords: List<String>,
        committedWord: String,
        onDeviceLearningEnabled: Boolean,
        contextAwareEnabled: Boolean,
        personalizedLearningEnabled: Boolean,
        recordSequence: (List<String>) -> Unit,
        recordWordUsage: (String) -> Unit,
        fieldAllowsPersonalizedLearning: Boolean = true
    ) {
        // M5: an incognito field's no-learning request outranks every user pref.
        val master = onDeviceLearningEnabled && fieldAllowsPersonalizedLearning
        if (canLearnContext(master, contextAwareEnabled) && recentWords.size >= 2) {
            val sequenceLength = kotlin.math.min(CONTEXT_WINDOW, recentWords.size)
            recordSequence(recentWords.takeLast(sequenceLength))
        }
        if (canLearnPersonalization(master, personalizedLearningEnabled)) {
            recordWordUsage(committedWord)
        }
    }
}
