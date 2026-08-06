package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator
import tribixbite.cleverkeys.autocorrect.AutocorrectContextGuard

/**
 * Handles suggestion selection, prediction display, and text completion logic.
 *
 * This class centralizes all logic related to:
 * - Suggestion bar updates and auto-insertion
 * - Prediction results from neural/typing engines
 * - Autocorrect for typing and swipe predictions
 * - Context tracking updates
 * - Text replacement and deletion (Termux-aware)
 * - Regular typing prediction updates
 *
 * Responsibilities:
 * - Display predictions in suggestion bar
 * - Auto-insert top predictions after swipe
 * - Handle manual suggestion selection
 * - Apply autocorrect to typed/predicted words
 * - Manage word deletion and replacement
 * - Update context tracker with completed words
 * - Handle Termux mode special cases
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - View creation and inflation
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.361).
 */
class SuggestionHandler(
    private val context: Context,
    private var config: Config,
    private val contextTracker: PredictionContextTracker,
    private val predictionCoordinator: PredictionCoordinator,
    private val contractionManager: ContractionManager,
    private val keyeventhandler: KeyEventHandler
) {
    companion object {
        private const val TAG = "SuggestionHandler"

        /**
         * Issue #72: Words that should always be capitalized.
         * Includes "I" and all its contractions.
         */
        private val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")

        /**
         * Apply shift/caps-lock-at-swipe-start transformation to a prediction.
         *
         * v1.33.9: Used for both the auto-inserted top prediction and the alternates shown in
         * the suggestion bar so every entry shares the same casing.
         *
         * WP9 step 3 (2026-07-20): relocated verbatim from InputCoordinator.applyShiftTransformation.
         * SuggestionHandler now OWNS this transform; the swipe request carries the shift state
         * (threaded through handleSwipeTyping → AsyncPredictionHandler → handlePredictionResults)
         * instead of the transform reading InputCoordinator's private fields. Pure (no instance
         * state) so it is a companion function callable by both pipelines during the migration.
         *
         * Semantics (unchanged): caps-lock wins over shift; caps-lock uppercases the whole word;
         * shift capitalizes only the first letter (no-op if already uppercase); neither → verbatim.
         *
         * @param word Prediction word to transform.
         * @param shiftActive True if shift was latched (single tap) when the swipe started.
         * @param shiftLocked True if shift was LOCKED (caps lock) when the swipe started.
         */
        fun applyShiftTransformation(word: String, shiftActive: Boolean, shiftLocked: Boolean): String {
            return when {
                shiftLocked -> {
                    // Caps Lock: uppercase entire word
                    word.uppercase(java.util.Locale.getDefault())
                }
                shiftActive -> {
                    // Shift: capitalize first letter only
                    word.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                    }
                }
                else -> word
            }
        }
    }

    /**
     * Issue #72: Capitalize "I" words if the setting is enabled.
     * Transforms "i" → "I", "i'm" → "I'm", "i'll" → "I'll", etc.
     *
     * @param word Word to potentially capitalize
     * @return Capitalized word if it's an I-word, otherwise unchanged
     */
    private fun capitalizeIWord(word: String): String {
        // v1.2.8: Use globalConfig to ensure setting is always current
        if (!Config.globalConfig().autocapitalize_i_words) return word

        val lower = word.lowercase()
        return if (lower in I_WORDS) {
            // Capitalize the first letter (I)
            word.replaceFirstChar { it.uppercaseChar() }
        } else {
            word
        }
    }

    /**
     * Preserve the capitalization pattern from the original word in the corrected word.
     * Handles three cases:
     * 1. ALL CAPS: "TEH" → "THE"
     * 2. Title Case: "Teh" → "The"
     * 3. lowercase: "teh" → "the"
     *
     * @param original The word as typed by the user
     * @param corrected The autocorrected word (typically lowercase)
     * @return Corrected word with original's capitalization pattern applied
     */
    private fun preserveCapitalization(original: String, corrected: String): String {
        if (original.isEmpty() || corrected.isEmpty()) return corrected

        return when {
            // All uppercase: "TEH" → "THE"
            original.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase()
            // First letter uppercase (title case): "Teh" → "The"
            original[0].isUpperCase() -> corrected.replaceFirstChar { it.uppercaseChar() }
            // All lowercase: keep as-is
            else -> corrected
        }
    }

    /**
     * Check if a capitalized word was intentionally capitalized (proper noun) vs auto-capitalized.
     * Returns true if:
     * 1. Word starts with uppercase
     * 2. Word appears mid-sentence (not after sentence-ending punctuation or at text start)
     *
     * This detects intentional proper nouns like "Boston" typed mid-sentence.
     *
     * @param ic InputConnection to check surrounding text
     * @param wordLength Length of the word just completed
     * @return true if the capitalization appears intentional (proper noun)
     */
    private fun isIntentionallyCapitalized(ic: android.view.inputmethod.InputConnection?, wordLength: Int): Boolean {
        if (ic == null || wordLength == 0) return false

        // Get text before the word (before the word + space that was just typed)
        // We need to look at what's before the word started
        val textBefore = ic.getTextBeforeCursor(wordLength + 5, 0) ?: return false
        if (textBefore.length <= wordLength) {
            // Word is at the very start of text - auto-cap position
            return false
        }

        // Get the character right before the word started
        val beforeWordIndex = textBefore.length - wordLength - 1
        if (beforeWordIndex < 0) return false

        val charBefore = textBefore[beforeWordIndex]

        // If preceded by sentence-ending punctuation, it's auto-cap position
        if (charBefore in ".!?\n") return false

        // If preceded by space, check what's before that space
        if (charBefore == ' ' && beforeWordIndex > 0) {
            val charBeforeSpace = textBefore[beforeWordIndex - 1]
            // If space follows sentence-ending punctuation, it's auto-cap
            if (charBeforeSpace in ".!?\n") return false
        }

        // Word is mid-sentence - capitalization was intentional
        return true
    }

    /**
     * Interface for sending debug logs to SwipeDebugActivity.
     * Implemented by CleverKeysService to bridge to its sendDebugLog method.
     */
    interface DebugLogger {
        fun sendDebugLog(message: String)
    }

    // Non-final - updated after creation
    private var suggestionBar: SuggestionBar? = null

    // Debug mode for logging
    private var debugMode = false
    private var debugLogger: DebugLogger? = null

    // Async prediction execution
    private val predictionTasks = PredictionTaskRunner()
    // Post to main thread explicitly — View.post() silently drops runnables for detached views
    private val mainHandler = Handler(Looper.getMainLooper())

    // WP9 R-1 step 6 (D5): single ML-capture implementation for the swipe auto-insert path —
    // the same collector SuggestionBridge uses for the tap path (privacy-gated internally).
    private val mlDataCollector = MLDataCollector(context)

    // v1.2.6: Flag to prevent async prediction task from overwriting special prompts
    // (autocorrect undo, add-to-dictionary)
    @Volatile
    private var specialPromptActive = false

    // Password mode tracking
    private var isPasswordMode = false

    // Next-word prediction (audit 2026-08-06 §4): true while the bar is showing
    // context-only next-word candidates (no partial typed). Lets backspace dismiss
    // them and lets onSuggestionSelected tag the commit PredictionSource.NEXT_WORD.
    @Volatile
    private var nextWordSuggestionsActive = false

    // M5 (review 2026-08-06): incognito-field contract. False while the active
    // editor sets IME_FLAG_NO_PERSONALIZED_LEARNING — suppresses the learn
    // funnel, selection-adaptation recording, and next-word surfacing for that
    // field. Set from CleverKeysService.onStartInputView; reset to true is the
    // safe default only because every consumer ANDs it with its own gates.
    @Volatile
    private var fieldAllowsPersonalizedLearning = true

    /**
     * Per-field incognito flag (M5): called from `onStartInputView` with
     * [LearningGate.fieldAllowsPersonalizedLearning] of the field's
     * `EditorInfo.imeOptions`. While false, nothing typed in this field is
     * learned and no personalized next-word candidates are surfaced.
     */
    fun setFieldPersonalizedLearningAllowed(allowed: Boolean) {
        fieldAllowsPersonalizedLearning = allowed
        if (!allowed) vlog { "Field requests no personalized learning (incognito)" }
    }

    /**
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
    }

    /** Verbose-only debug log; message lambda is not evaluated unless verbose logging is enabled. */
    private inline fun vlog(message: () -> String) { if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, message()) }

    /**
     * Shuts down the prediction executor (interrupting in-flight work) and clears main-thread
     * callbacks. Called during IME teardown so no prediction thread outlives the service.
     */
    fun shutdown() {
        predictionTasks.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** True once the prediction executor has been shut down (test/diagnostic hook). */
    fun isPredictionExecutorShutdown(): Boolean = predictionTasks.isShutdown

    /**
     * Sets the suggestion bar reference and registers this handler as the
     * long-press provenance inspector (Task B — SuggestionHandler owns the
     * pipeline knowledge; the bar stays a dumb display surface).
     *
     * @param suggestionBar Suggestion bar for displaying predictions
     */
    fun setSuggestionBar(suggestionBar: SuggestionBar?) {
        this.suggestionBar = suggestionBar
        suggestionBar?.setOnSuggestionInspectedListener { index, word, meta ->
            inspectSuggestion(index, word, meta)
        }
    }

    /**
     * Task B Tier 1: compose and display the provenance sheet for a long-pressed
     * suggestion — which engine/source produced it plus its score components
     * (wires the previously dead `PersonalizationEngine.explainBoost()`).
     *
     * Available with all debug prefs off: inspection is on-demand and costs
     * nothing until invoked. When the at-generation breakdown is missing (e.g.
     * a restored bar without metas), it is lazily recomputed from the current
     * partial + context — the inputs are all still known (audit §2.3).
     */
    private fun inspectSuggestion(index: Int, word: String, meta: SuggestionMeta?) {
        val bar = suggestionBar ?: return

        // Special-suggestion wires (dict_add:/exact_add:) resolve to their word
        // for display; ordinary words pass through unchanged.
        val displayWord = when (val route = routeSuggestionSelection(word)) {
            is SelectionRoute.AddToDictionary -> route.word
            is SelectionRoute.ExactAdd -> route.word
            is SelectionRoute.CommitWord -> route.wire.removePrefix("raw:")
        }

        val predictor = predictionCoordinator.getWordPredictor()

        // Lazily recompute the unified-score breakdown when absent and the word
        // came from the unified scorer's world (typed-path origins).
        val breakdown = meta?.breakdown ?: run {
            val partial = contextTracker.getCurrentWord()
            if (partial.isNotEmpty() &&
                (meta == null || meta.origin == SuggestionOrigin.DICTIONARY_PREFIX)
            ) {
                predictor?.explainScore(displayWord, partial, contextTracker.getContextWords().toList())
            } else {
                null
            }
        }
        val effectiveMeta = when {
            meta == null && breakdown != null ->
                SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX, breakdown)
            meta != null && meta.breakdown == null && breakdown != null ->
                meta.copy(breakdown = breakdown)
            else -> meta
        }

        val text = ProvenanceFormatter.format(
            word = displayWord,
            meta = effectiveMeta,
            barScore = null, // breakdown carries the meaningful score; raw bar ints are debug-only
            personalizationExplanation = predictor?.explainPersonalization(displayWord)
                ?.takeIf { it.inVocabulary }?.explanation
        )
        bar.showProvenancePopup(text)
    }

    /**
     * Sets debug mode and logger.
     *
     * @param enabled Whether debug mode is enabled
     * @param logger Debug logger implementation
     */
    fun setDebugMode(enabled: Boolean, logger: DebugLogger?) {
        debugMode = enabled
        debugLogger = logger
    }

    /**
     * Sets password mode.
     * When enabled, predictions are disabled and password text is tracked.
     *
     * @param enabled Whether password mode is enabled
     */
    fun setPasswordMode(enabled: Boolean) {
        isPasswordMode = enabled
        if (enabled) {
            // Clear predictions when entering password mode
            suggestionBar?.clearSuggestions()
            // H1 (review 2026-08-06): drop the learn-funnel's rolling word window
            // too — a bigram must never join pre-password context to whatever is
            // committed after the password field.
            predictionCoordinator.getWordPredictor()?.clearContext()
        }
        vlog { "Password mode ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Check if currently in password mode.
     */
    fun isInPasswordMode(): Boolean = isPasswordMode

    /**
     * Handle a character typed in password field.
     * Syncs with actual field content to handle all edge cases.
     *
     * @param char The character that was typed
     */
    fun handlePasswordChar(char: Char) {
        if (!isPasswordMode) return
        // Sync with field to handle any edge cases (autocomplete, etc.)
        suggestionBar?.syncPasswordWithField()
    }

    /**
     * Handle a string typed in password field.
     * Syncs with actual field content to handle all edge cases.
     *
     * @param text The text that was typed
     */
    fun handlePasswordText(text: String) {
        if (!isPasswordMode) return
        // Sync with field to handle paste, autocomplete, etc.
        suggestionBar?.syncPasswordWithField()
    }

    /**
     * Handle backspace in password field.
     * Syncs with actual field content to handle select-all+delete, etc.
     */
    fun handlePasswordBackspace() {
        if (!isPasswordMode) return
        // Sync with field - handles select-all+delete, cursor position changes, etc.
        suggestionBar?.syncPasswordWithField()
    }

    /**
     * Sends a debug log message if debug mode is enabled.
     */
    private fun sendDebugLog(message: String) {
        if (debugMode && debugLogger != null) {
            debugLogger?.sendDebugLog(message)
        }
    }

    /**
     * WP9 R-1 steps 4+6 — THE swipe result path (single pipeline). Called unconditionally by
     * [InputCoordinator.handlePredictionResults] (step 6 removed the `unified_swipe_pipeline`
     * flag and the legacy IC-only path). Owns the BAR presentation (case preservation, shift/caps
     * transform, possessive augmentation D1, password guard D2) AND — since step 6 — the COMMIT,
     * which now runs through THIS class's [onSuggestionSelected] (isManualSelection=false), the
     * same engine the manual-tap path uses. InputCoordinator's divergent commit engine was deleted.
     *
     * This is also the seam the geometric engine feeds in R-1 steps 7-9 (see
     * `docs/audit/remediation/3-core-ime.md` Addendum 2026-07-21): any engine producing a
     * prediction list routes through here, inheriting the guard/augment/commit stack.
     *
     * Swipe-commit deltas landed by step 6 (deliberate, previously divergent from tap):
     *   - Termux REPLACE deletion now uses key events (this engine's Termux branches) — but the
     *     replace branch is unreachable on auto-insert (tracking is cleared just below), and
     *     production taps already used this engine, so no live behavior changed.
     *   - Mid-sentence swipe (space already after cursor) no longer double-spaces
     *     ([SmartAutoSpace.decideTrailingSpace] NO_SPACE_MID_SENTENCE now applies to swipe).
     *   - #151 sync-suppressed fields (URL/email) never get a leading space on swipe.
     *   - Final autocorrect on auto-insert now preserves capitalization
     *     ([preserveCapitalization]) and skips contraction KEYS (isContractionKey — parity
     *     with the deleted IC engine, oracle scenario 11).
     *   - auto_space_after_suggestion=false suppresses the swipe trailing space exactly as the
     *     production IC engine did (SmartAutoSpace branch 1 updated in the same commit).
     *   - D5 LANDED: swipe ML capture routes through [MLDataCollector] (single implementation,
     *     same `swipe_debug_detailed_logging` + privacy-consent gating as IC's inline block).
     *
     * @param inputCoordinator the delegating swipe front-end — supplies haptics, the latched-shift
     *   clear, keyboard height, and the captured swipe ML trace (IC remains the gesture/ML owner).
     */
    fun handleSwipePredictionResults(
        predictions: List<String>?,
        scores: List<Int>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        shiftActive: Boolean,
        shiftLocked: Boolean,
        inputCoordinator: InputCoordinator
    ) {
        // Swipe results replace whatever the bar shows — any next-word display state ends here.
        nextWordSuggestionsActive = false

        // D2: password-field guard. Detect from the tracked mode OR the live editor (the latter holds
        // in tests / before onStartInputView sets the mode). Suppress the swipe unless the user opted in.
        val passwordField = isPasswordMode || SuggestionBar.isPasswordField(editorInfo)
        if (passwordField && !config.swipe_on_password_fields) {
            vlog { "SWIPE password field + swipe_on_password_fields=false — suppressing" }
            suggestionBar?.clearSuggestions()
            return
        }

        if (predictions.isNullOrEmpty()) {
            suggestionBar?.clearSuggestions()
            return
        }

        // Apply user word case preservation BEFORE shift transformation (proper nouns like "Boston"),
        // then the shift/caps-lock-at-swipe-start transform — IDENTICAL to the legacy IC path so
        // shift/caps casing (oracle 2/3) is unchanged.
        val casedPredictions = predictionCoordinator.getWordPredictor()
            ?.applyUserWordCaseToList(predictions) ?: predictions
        val transformedPredictions = casedPredictions.map {
            applyShiftTransformation(it, shiftActive, shiftLocked)
        }

        // D1: augment the bar list with possessive forms (transient-style augment reused from the tap
        // path). Kept aligned with scores; possessives appended at the end so the top prediction is
        // unchanged and the auto-insert target below is still the highest-scoring word.
        // ENGLISH ONLY (2026-07-23): generatePossessive appends English "'s" morphology —
        // on the geometric path's non-English languages (Cyrillic ЙЦУКЕН, French AZERTY…)
        // it would fabricate junk like "maison's"/"дом's". Gate on the ACTIVE dictionary
        // language (null → "en" preserves the pre-gate behavior in en-only contexts).
        val barWords = transformedPredictions.toMutableList()
        val barScores = (scores ?: emptyList()).toMutableList()
        val activeLanguage = predictionCoordinator.getDictionaryManager()?.getCurrentLanguage() ?: "en"
        val engineWordCount = barWords.size
        if (activeLanguage == "en") {
            augmentPredictionsWithPossessives(barWords, barScores)
        }

        // Task B: provenance metas — engine outputs first, then any appended
        // possessive forms (augment appends at the end, so index >= engineWordCount
        // means POSSESSIVE).
        val swipeOrigin = SuggestionOrigin.forSwipeEngineMode(config.swipe_engine_mode)
        val barMetas = MutableList(barWords.size) { i ->
            SuggestionMeta(if (i < engineWordCount) swipeOrigin else SuggestionOrigin.POSSESSIVE)
        }

        suggestionBar?.let { bar ->
            bar.setShowDebugScores(config.swipe_show_debug_scores)
            bar.setShowOriginMarkers(config.suggestion_provenance_markers)
            bar.setSuggestionsWithScores(barWords, barScores, barMetas)

            // Auto-insert the top (highest-scoring) prediction through THE single commit engine
            // (step 6): haptic + manual-typing termination + tracking clear were absorbed verbatim
            // from the deleted InputCoordinator.autoInsertTopSuggestion.
            bar.getTopSuggestion()?.takeIf { it.isNotEmpty() }?.let { topPrediction ->
                inputCoordinator.triggerSwipeCompleteHaptic()

                // If manual typing was in progress, terminate it with a space. The typed chars are
                // already committed via KeyEventHandler.send_text() — currentWord is only a tracking
                // buffer, so committing just the space preserves them ("i" + swipe "think" → "i think ").
                if (contextTracker.getCurrentWordLength() > 0 && ic != null) {
                    ic.commitText(" ", 1)
                    contextTracker.clearCurrentWord()
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                }

                // Clear tracking BEFORE the commit so consecutive swipes APPEND (the replace branch
                // in onSuggestionSelected must not fire on an auto-insert).
                contextTracker.clearLastAutoInsertedWord()
                contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)

                // D5: snapshot swipe state before the commit resets wasLastInputSwipe.
                val wasSwipeAutoInsert = contextTracker.wasLastInputSwipe()
                val swipeData = inputCoordinator.getCurrentSwipeData()

                val committedWord = onSuggestionSelected(
                    topPrediction, ic, editorInfo, resources, isManualSelection = false
                )

                // D5 LANDED (step 6): swipe ML capture through MLDataCollector — the single
                // implementation the tap path (SuggestionBridge) already uses. Gating preserved
                // from IC's inline block: detailed logging on AND swipe data present (the collector
                // itself re-checks privacy consent before storing).
                if (wasSwipeAutoInsert && swipeData != null && config.swipe_debug_detailed_logging) {
                    mlDataCollector.collectAndStoreSwipeData(
                        committedWord ?: topPrediction,
                        swipeData,
                        inputCoordinator.keyboardHeightPx(),
                        predictionCoordinator.getMlDataStore()
                    )
                }
                inputCoordinator.resetSwipeData()

                // Clear the latched shift indicator after a shift+swipe commit; caps lock stays
                // until the user unlocks it (was IC.onSuggestionSelected's post-commit clearing).
                if (shiftActive && !shiftLocked) {
                    inputCoordinator.clearLatchedShiftAfterSwipe()
                }

                // Track the auto-inserted word so tapping an alternate replaces ONLY this word.
                // TODO: when final autocorrect rewrites the word, this still records the raw
                // prediction (pre-existing behavior preserved from IC) — replacement deletion
                // counts can drift when the correction changes the word length.
                val cleanPrediction = topPrediction.replace(Regex("^raw:"), "")
                contextTracker.setLastAutoInsertedWord(cleanPrediction)
                contextTracker.setLastCommitSource(PredictionSource.NEURAL_SWIPE)

                // Re-display the augmented+transformed correction list (D1: possessives persist in
                // the final swipe bar).
                bar.setSuggestionsWithScores(barWords, barScores, barMetas)

                // Next-word call-site 3 (audit §4.4): keep the swipe ALTERNATES
                // (the user may still correct the swipe) and APPEND up to
                // MAX_SWIPE_APPEND next-word candidates. Per-suggestion NEXT_WORD
                // metas make the tap path append-after (not replace) for these
                // entries. Store lookups are in-RAM map reads — cheap enough to
                // run inline on the result path.
                appendNextWordToSwipeAlternates(bar, barWords, barScores, barMetas, editorInfo)
            }
        }
    }

    /**
     * Next-word call-site 3 (audit §4.4 recommended composition): after a swipe
     * auto-insert, append up to [NextWordPredictor.MAX_SWIPE_APPEND] learned
     * next-word candidates AFTER the swipe alternates. The alternates are kept
     * so swipe correction still works; the appended entries carry NEXT_WORD
     * metas, which [onSuggestionSelected] uses to APPEND the tapped word
     * instead of replacing the auto-inserted swipe word.
     *
     * L3 (review 2026-08-06): candidate generation runs on the shared
     * [predictionTasks] executor, not the UI thread — the first lookup of a
     * language lazily LOADS its persisted n-gram blobs, which caused
     * first-swipe jank inline. The append post is guarded by the bar
     * generation (M6): if the bar changed while queued (user typed, new swipe),
     * the stale append is dropped.
     */
    private fun appendNextWordToSwipeAlternates(
        bar: SuggestionBar,
        barWords: MutableList<String>,
        barScores: MutableList<Int>,
        barMetas: MutableList<SuggestionMeta>,
        editorInfo: EditorInfo?
    ) {
        val generationAtSubmit = bar.contentGeneration()
        val contextWords = contextTracker.getContextWords().toList()
        predictionTasks.cancelAndSubmit {
            if (Thread.currentThread().isInterrupted) return@cancelAndSubmit
            val candidates = generateNextWordCandidates(editorInfo) ?: return@cancelAndSubmit

            val existingLower = barWords.map { it.lowercase() }.toHashSet()
            val appendWords = mutableListOf<String>()
            val appendScores = mutableListOf<Int>()
            val appendMetas = mutableListOf<SuggestionMeta>()
            for (candidate in candidates) {
                if (appendWords.size >= NextWordPredictor.MAX_SWIPE_APPEND) break
                if (candidate.word.lowercase() in existingLower) continue
                appendWords.add(capitalizeIWord(candidate.word))
                appendScores.add(candidate.score)
                appendMetas.add(
                    SuggestionMeta(
                        SuggestionOrigin.NEXT_WORD,
                        note = NextWordPredictor.provenanceNote(candidate, contextWords)
                    )
                )
            }
            if (appendWords.isEmpty() || Thread.currentThread().isInterrupted) return@cancelAndSubmit

            mainHandler.post {
                // M6: the swipe-alternates bar this append targets must still be
                // the live content — abort if anything replaced it while queued.
                if (bar.contentGeneration() != generationAtSubmit) return@post
                barWords.addAll(appendWords)
                barScores.addAll(appendScores)
                barMetas.addAll(appendMetas)
                bar.setSuggestionsWithScores(barWords, barScores, barMetas)
            }
        }
    }

    /**
     * Shared gated next-word generation (call-sites 1–4). Returns null when any
     * guard fails (feature off, master learning gate off, password/prompt/
     * Termux, empty context) or nothing clears the confidence floor.
     */
    private fun generateNextWordCandidates(editorInfo: EditorInfo?): List<NextWordPredictor.Candidate>? {
        val inTermuxApp = try {
            editorInfo?.packageName == "com.termux"
        } catch (e: Exception) {
            false
        }
        val contextWords = contextTracker.getContextWords().toList()
        if (!NextWordPredictor.shouldShow(
                featureEnabled = config.next_word_prediction_enabled,
                onDeviceLearningEnabled = config.on_device_learning_enabled,
                wordPredictionEnabled = config.word_prediction_enabled,
                isPasswordMode = isPasswordMode,
                specialPromptActive = specialPromptActive,
                inTermuxApp = inTermuxApp,
                hasContext = contextWords.isNotEmpty(),
                fieldAllowsPersonalizedLearning = fieldAllowsPersonalizedLearning // M5
            )
        ) {
            return null
        }
        val predictor = predictionCoordinator.getWordPredictor() ?: return null

        val learned = predictor.getNextWordCandidates(contextWords, maxResults = 10)
        val candidates = NextWordPredictor.generate(
            learned = learned,
            lastCommittedWord = contextWords.lastOrNull(),
            personalizationBoost = { predictor.getPersonalizationBoostFor(it) },
            isWordAllowed = { w ->
                !predictor.isWordDisabled(w) &&
                    (predictor.isInDictionary(w) || predictor.isInUserVocabulary(w))
            }
        )
        return candidates.ifEmpty { null }
    }

    /**
     * Called when user selects a suggestion from the suggestion bar.
     * Handles autocorrect, text replacement, and context updates.
     *
     * @param word Selected word
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     * @param resources Resources for metrics
     * @param isManualSelection True if user explicitly tapped a suggestion (skip final autocorrect),
     *                          false for auto-insert after swipe (final autocorrect may apply)
     * @return the processed word that was committed (post autocorrect / I-word handling), or null
     *         when nothing was committed (blank input, special-suggestion routes, autocorrect undo,
     *         or no InputConnection). Step 6: the swipe auto-insert path uses this for ML capture.
     */
    fun onSuggestionSelected(
        word: String?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        isManualSelection: Boolean = false
    ): String? {
        // Null/empty check
        if (word.isNullOrBlank()) return null

        // Next-word tap (audit §4.4): a whole-bar next-word display OR (call-site
        // 3) a per-suggestion NEXT_WORD meta on a mixed swipe-alternates bar —
        // either way the commit is tagged NEXT_WORD below. Consumed either way —
        // any selection ends the next-word display state.
        val tappedOrigin = suggestionBar?.getMetaForSuggestion(word)?.origin
        val wasNextWordSelection =
            nextWordSuggestionsActive || tappedOrigin == SuggestionOrigin.NEXT_WORD
        nextWordSuggestionsActive = false

        // R3: Route the special-suggestion protocol through the shared typed
        // routing decision (single source of truth) instead of ad-hoc prefix
        // parsing. routeSuggestionSelection is pure and unit-tested.
        when (val route = routeSuggestionSelection(word)) {
            // "Add to dictionary?" tap → add the word to the user dictionary.
            is SelectionRoute.AddToDictionary -> {
                handleAddToDictionary(route.word)
                return null
            }
            // #42: "+word" tap → commit the exact typed word and add to dictionary.
            is SelectionRoute.ExactAdd -> {
                handleExactWordAdd(route.word, ic, editorInfo)
                return null
            }
            // Ordinary word: fall through to autocorrect/commit handling below.
            is SelectionRoute.CommitWord -> Unit
        }

        // Check if this is an autocorrect undo (user tapped the original word after autocorrect)
        val lastAutocorrectOriginal = contextTracker.getLastAutocorrectOriginalWord()
        if (contextTracker.getLastCommitSource() == PredictionSource.AUTOCORRECT &&
            lastAutocorrectOriginal != null &&
            word.equals(lastAutocorrectOriginal, ignoreCase = true)
        ) {
            handleAutocorrectUndo(word, lastAutocorrectOriginal, ic, editorInfo)
            return null
        }

        var processedWord = word

        // Check if this is a raw prediction (user explicitly selected neural network output)
        // Raw predictions should skip autocorrect
        val isRawPrediction = processedWord.startsWith("raw:")

        // Strip "raw:" prefix before processing (v1.33.7: fixed regex to match actual prefix format)
        // Prefix format: "raw:word" not " [raw:0.08]"
        processedWord = processedWord.replace(Regex("^raw:"), "")

        // Issue #72: Capitalize "I" words (i → I, i'm → I'm, i'll → I'll)
        processedWord = capitalizeIWord(processedWord)

        // Check if this is a known contraction (already has apostrophes from displayText)
        // If it is, skip autocorrect to prevent fuzzy matching to wrong words
        val isKnownContraction = contractionManager.isKnownContraction(processedWord)

        // v1.1.87 / step 6: also protect contraction KEYS (apostrophe-free forms like "dont",
        // "cest") — they must not be fuzzy-matched to similar words. Parity with the deleted
        // InputCoordinator engine; oracle scenario 11 pins "dont" committing verbatim.
        val isContractionKey = contractionManager.isContractionKey(processedWord)

        // Skip autocorrect for:
        // 1. Known contractions (prevent fuzzy matching)
        // 2. Contraction keys (apostrophe-free forms — same protection)
        // 3. Raw predictions (user explicitly selected this neural output)
        // 4. Manual selections (user explicitly tapped a neural prediction - issue #63 fix)
        if (isKnownContraction || isContractionKey || isRawPrediction || isManualSelection) {
            if (isKnownContraction) {
                vlog { "KNOWN CONTRACTION: \"$processedWord\" - skipping autocorrect" }
            }
            if (isContractionKey) {
                vlog { "CONTRACTION KEY: \"$processedWord\" - skipping autocorrect" }
            }
            if (isRawPrediction) {
                vlog { "RAW PREDICTION: \"$processedWord\" - skipping autocorrect" }
            }
            if (isManualSelection) {
                vlog { "MANUAL SELECTION: \"$processedWord\" - skipping autocorrect (user chose this word)" }
            }
        } else {
            // v1.33.7: Final autocorrect - second chance autocorrect after beam search
            // Applies when auto-inserting a prediction (even if beam autocorrect was OFF)
            // Useful for correcting vocabulary misses
            // SKIP for known contractions, raw predictions, and manual selections
            if (config.swipe_final_autocorrect_enabled && predictionCoordinator.getWordPredictor() != null) {
                var correctedWord = predictionCoordinator.getWordPredictor()?.autoCorrect(processedWord)

                // If autocorrect found a better match, use it
                if (correctedWord != null && correctedWord != processedWord) {
                    // Preserve capitalization from original prediction
                    correctedWord = preserveCapitalization(processedWord, correctedWord)
                    correctedWord = capitalizeIWord(correctedWord)
                    vlog { "FINAL AUTOCORRECT: \"$processedWord\" → \"$correctedWord\"" }
                    processedWord = correctedWord
                }
            }
        }

        // Record user selection for adaptation learning — behind the MASTER
        // on-device-learning gate (Task A): UserAdaptationManager persists
        // selection counts to prefs and previously had NO preference gate.
        // M5: an incognito field (IME_FLAG_NO_PERSONALIZED_LEARNING) suppresses
        // this learning path too.
        if (LearningGate.canLearnAdaptation(config.on_device_learning_enabled) &&
            fieldAllowsPersonalizedLearning
        ) {
            predictionCoordinator.getAdaptationManager()?.recordSelection(processedWord.trim())
        }

        // CRITICAL: Save swipe flag before resetting for use in spacing logic below
        val isSwipeAutoInsert = contextTracker.wasLastInputSwipe()

        // Store ML data if this was a swipe prediction selection
        // Note: ML data collection is handled by InputCoordinator, not here
        // This handler only deals with suggestion selection logic

        // Reset swipe tracking
        contextTracker.setWasLastInputSwipe(false)

        ic?.let { inputConnection ->
            try {
                // Detect if we're in Termux for special handling
                val inTermuxApp = try {
                    editorInfo?.packageName == "com.termux"
                } catch (e: Exception) {
                    false
                }

                // #151: URI/email/password/number fields never get cursor-sync
                // (shouldSyncForInputType skips them), so deletion counts from the
                // tracker are always (0,0) there. Tapping a suggestion in a browser
                // URL bar must still replace the typed partial token — we detect
                // these fields up front and (a) force the editor-scan fallback,
                // (b) never inject a leading space (it would corrupt the value).
                val syncSuppressedField = !contextTracker.shouldSyncForInputType(editorInfo)

                // Next-word call-site 3 (audit §4.4): a NEXT_WORD candidate that
                // was APPENDED after swipe alternates must append after the
                // auto-inserted word — clearing the tracking here prevents the
                // REPLACE branch below from deleting the swiped word.
                if (wasNextWordSelection &&
                    contextTracker.getLastCommitSource() == PredictionSource.NEURAL_SWIPE
                ) {
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                }

                // IMPORTANT: _currentWord tracks typed characters, but they're already committed to input!
                // When typing normally (not swipe), each character is committed immediately via KeyEventHandler
                // So _currentWord is just for tracking - the text is already in the editor
                // We should NOT delete _currentWord characters here because:
                // 1. They're already committed and visible
                // 2. Swipe gesture detection happens AFTER typing completes
                // 3. User expects swipe to ADD a word, not delete what they typed
                //
                // Example bug scenario:
                // - User types "i" (committed to editor, _currentWord="i")
                // - User swipes "think" (without space after "i")
                // - Old code: deletes "i", adds " think " → result: " think " (lost the "i"!)
                // - New code: keeps "i", adds " think " → result: "i think " (correct!)
                //
                // The ONLY time we should delete is when replacing an auto-inserted prediction
                // (handled below via _lastAutoInsertedWord tracking)

                // CRITICAL: If we just auto-inserted a word from neural swipe, delete it for replacement
                // This allows user to tap a different prediction instead of appending
                // Only delete if the last commit was from neural swipe (not from other sources)
                if (!contextTracker.getLastAutoInsertedWord().isNullOrEmpty() &&
                    contextTracker.getLastCommitSource() == PredictionSource.NEURAL_SWIPE
                ) {
                    vlog { "REPLACE: Deleting auto-inserted word: '${contextTracker.getLastAutoInsertedWord()}'" }

                    var deleteCount = (contextTracker.getLastAutoInsertedWord()?.length ?: 0) + 1 // Word + trailing space
                    var deletedLeadingSpace = false

                    if (inTermuxApp) {
                        // TERMUX: Use backspace key events instead of InputConnection methods
                        // Termux doesn't support deleteSurroundingText properly
                        vlog { "TERMUX: Using backspace key events to delete $deleteCount chars" }

                        // Check if there's a leading space to delete
                        val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                        if (textBefore != null && textBefore.isNotEmpty() && textBefore[0] == ' ') {
                            deleteCount++ // Include leading space
                            deletedLeadingSpace = true
                        }

                        // Send backspace key events
                        repeat(deleteCount) {
                            keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DEL, 0)
                        }
                    } else {
                        // NORMAL APPS: Use InputConnection methods
                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                            val debugBefore = inputConnection.getTextBeforeCursor(50, 0)
                            Log.d(TAG, "REPLACE: Text before cursor (50 chars): '$debugBefore'")
                        }
                        vlog { "REPLACE: Delete count = $deleteCount" }

                        // Delete the auto-inserted word and its space
                        inputConnection.deleteSurroundingText(deleteCount, 0)

                        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                            val debugAfter = inputConnection.getTextBeforeCursor(50, 0)
                            Log.d(TAG, "REPLACE: After deleting word, text before cursor: '$debugAfter'")
                        }

                        // Also need to check if there was a space added before it
                        val textBefore = inputConnection.getTextBeforeCursor(1, 0)
                        vlog { "REPLACE: Checking for leading space, got: '$textBefore'" }
                        if (textBefore != null && textBefore.isNotEmpty() && textBefore[0] == ' ') {
                            vlog { "REPLACE: Deleting leading space" }
                            // Delete the leading space too
                            inputConnection.deleteSurroundingText(1, 0)

                            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                                val debugFinal = inputConnection.getTextBeforeCursor(50, 0)
                                Log.d(TAG, "REPLACE: After deleting leading space: '$debugFinal'")
                            }
                        }
                    }

                    // Clear the tracking variables
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                }
                // ALSO: If user is selecting a prediction during regular typing, delete the partial word
                // This handles typing "hel" then selecting "hello" - we need to delete "hel" first
                // v1.2.6: Also handles cursor mid-word - need to delete BOTH prefix AND suffix
                // #78: Fall back to scanning the editor when ContextTracker has no composing-region
                // info (Termux/Fennec address bar/Google Keep commit chars without composing-text).
                else if (!isSwipeAutoInsert) {
                    // v1.2.6 FIX: Do immediate cursor sync to get accurate prefix/suffix
                    // The debounced sync may not have completed yet
                    // v1.2.7: CRITICAL - Clear expectingSelectionUpdate flag first!
                    // If a previous deletion set this flag and onUpdateSelection hasn't fired yet,
                    // the sync would be skipped, causing suffix deletion to fail (e.g., "ca|n't" → "canteen n't")
                    contextTracker.expectingSelectionUpdate = false
                    contextTracker.synchronizeWithCursor(
                        inputConnection,
                        config.primary_language,
                        editorInfo
                    )

                    var (prefixDelete, suffixDelete) = contextTracker.getCharsToDeleteForPrediction()

                    // #78 fallback: when ContextTracker reports 0 length, scan the editor
                    // for a partial word ending immediately before the cursor. Treats
                    // letters, digits, apostrophes, and hyphens as word characters so
                    // hyphenated typing ("co-o" → "co-op") works correctly.
                    // #151: also fall back when cursor-sync is suppressed for this input
                    // type (URL bars, email fields) — there currentWord IS tracked from
                    // typing (non-zero), but sync never populated the deletion counts,
                    // so without the scan the typed partial ("exa") was left behind and
                    // the tap produced "exa example ".
                    if (prefixDelete == 0 && suffixDelete == 0 &&
                        (contextTracker.getCurrentWordLength() == 0 || syncSuppressedField)) {
                        try {
                            val before = inputConnection.getTextBeforeCursor(64, 0)?.toString() ?: ""
                            if (before.isNotEmpty()) {
                                val wordStart = before.indexOfLast {
                                    !it.isLetterOrDigit() && it != '\'' && it != '-'
                                } + 1
                                val partialLen = before.length - wordStart
                                if (partialLen in 1..64) {
                                    prefixDelete = partialLen
                                    vlog { "TYPING PREDICTION (#78 fallback): scanned editor, prefixDelete=$partialLen" }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "TYPING PREDICTION: editor-scan fallback failed", e)
                        }
                    }

                    if (prefixDelete == 0 && suffixDelete == 0) {
                        // Nothing to delete — neither composing-text nor a partial word at cursor.
                    } else {
                        vlog { "TYPING PREDICTION: Deleting partial word - prefix=$prefixDelete, suffix=$suffixDelete" }
                        if (inTermuxApp) {
                            // TERMUX: Use backspace key events
                            // First delete suffix (move right then backspace), then delete prefix
                            if (suffixDelete > 0) {
                                // Move cursor to end of word
                                repeat(suffixDelete) {
                                    keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                                }
                            }
                            // Delete entire word (prefix + suffix)
                            repeat(prefixDelete + suffixDelete) {
                                keyeventhandler.send_key_down_up(KeyEvent.KEYCODE_DEL, 0)
                            }
                        } else {
                            // NORMAL APPS: Use InputConnection with both prefix AND suffix deletion
                            inputConnection.deleteSurroundingText(prefixDelete, suffixDelete)

                            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                                val debugAfter = inputConnection.getTextBeforeCursor(50, 0)
                                Log.d(TAG, "TYPING PREDICTION: After deleting partial, text before cursor: '$debugAfter'")
                            }
                        }
                    }
                }

                // Add space before word if previous character isn't whitespace.
                // For tapped suggestions (not swipe), respect auto_space_before_suggestion setting.
                // Swipe auto-inserts always get the leading space since the swipe replaces no typed text.
                val needsSpaceBefore = if (!isSwipeAutoInsert && !config.auto_space_before_suggestion) {
                    false  // User disabled leading space before tapped suggestions
                } else if (syncSuppressedField) {
                    // #151: never inject a leading space into URL/email/etc. fields —
                    // after replacing "exa" in "https://exa" the previous char is '/',
                    // and " example" would corrupt the URL.
                    false
                } else {
                    try {
                        // SAS-1: read TWO chars — straight quotes " and ' are opener vs
                        // possessive/closing depending on the char before them
                        val textBefore = inputConnection.getTextBeforeCursor(2, 0)
                        if (textBefore != null && textBefore.isNotEmpty()) {
                            val prevChar = textBefore.last()
                            val charBeforePrev =
                                if (textBefore.length >= 2) textBefore[textBefore.length - 2] else null
                            // SAS-1: no leading auto-space after opening punctuation
                            // ( [ { " ' “ ‘ ¿ ¡ — `("` + swipe "word" → `(word`, not `( word`
                            SmartAutoSpace.needsLeadingSpace(prevChar, charBeforePrev)
                        } else {
                            false
                        }
                    } catch (e: Exception) {
                        // If getTextBeforeCursor fails, assume we don't need space before
                        false
                    }
                }

                // v1.2.6 FIX: Check if there's already a space after cursor (mid-sentence replacement)
                // Don't add trailing space if one already exists to avoid double spaces
                val hasSpaceAfter = try {
                    val textAfter = inputConnection.getTextAfterCursor(1, 0)
                    textAfter != null && textAfter.isNotEmpty() && textAfter[0].isWhitespace()
                } catch (e: Exception) {
                    false
                }

                // Apply capitalization if user was typing with shift (first letter uppercase)
                val currentWord = contextTracker.getCurrentWord()
                val shouldCapitalize = currentWord.isNotEmpty() && currentWord[0].isUpperCase()
                val capitalizedWord = if (shouldCapitalize && processedWord.isNotEmpty()) {
                    processedWord.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                    }
                } else {
                    processedWord
                }

                // Commit the selected word
                // #78: Only skip trailing space when:
                // 1. auto_space_after_suggestion is disabled (user preference #82)
                // 2. OR there's already a space after cursor (mid-sentence replacement)
                // The previous Termux-app override has been removed — Termux users who want
                // no trailing space should disable auto_space_after_suggestion.
                // The decision itself lives in SmartAutoSpace (pure, unit-tested) so it
                // can't drift from AutoSpaceLogicTest.
                val trailingSpaceMode = SmartAutoSpace.decideTrailingSpace(
                    autoSpaceAfterEnabled = config.auto_space_after_suggestion,
                    isSwipeAutoInsert = isSwipeAutoInsert,
                    hasSpaceAfter = hasSpaceAfter
                )
                val insertMode: String
                val textToInsert = when (trailingSpaceMode) {
                    SmartAutoSpace.TrailingSpaceMode.NO_SPACE_USER_DISABLED -> {
                        // #82: User disabled auto-space after suggestion (tap selection only)
                        insertMode = "AUTO-SPACE DISABLED"
                        if (needsSpaceBefore) " $capitalizedWord" else capitalizedWord
                    }
                    SmartAutoSpace.TrailingSpaceMode.NO_SPACE_MID_SENTENCE -> {
                        // v1.2.6: Mid-sentence replacement - don't add trailing space (already exists)
                        insertMode = "MID-SENTENCE (hasSpaceAfter=true)"
                        if (needsSpaceBefore) " $capitalizedWord" else capitalizedWord
                    }
                    SmartAutoSpace.TrailingSpaceMode.TRAILING_SPACE -> {
                        // Normal apps (incl. Termux when user opts in) or swipe: Insert word with trailing space
                        insertMode = "NORMAL/SWIPE MODE (needsSpaceBefore=$needsSpaceBefore, isSwipe=$isSwipeAutoInsert, capitalize=$shouldCapitalize)"
                        if (needsSpaceBefore) " $capitalizedWord " else "$capitalizedWord "
                    }
                }
                vlog { "$insertMode: textToInsert len=${textToInsert.length}" }

                // v1.2.7: Mark space as auto-inserted for smart punctuation
                // #78: Trailing space is added when neither user-disabled nor mid-sentence applies
                val addedTrailingSpace =
                    trailingSpaceMode == SmartAutoSpace.TrailingSpaceMode.TRAILING_SPACE

                // SAS-1: capture the pre-commit cursor position so the pending
                // auto-space carries a position stamp (validated at punctuation time;
                // -1 when the editor doesn't support ExtractedText → legacy check)
                val preCommitCursorPos = if (addedTrailingSpace) {
                    PredictionContextTracker.currentCursorPosition(inputConnection)
                } else {
                    -1
                }

                vlog { "Committing text: len=${textToInsert.length}" }
                inputConnection.commitText(textToInsert, 1)

                if (addedTrailingSpace) {
                    contextTracker.markAutoSpacePending(
                        if (preCommitCursorPos >= 0) preCommitCursorPos + textToInsert.length else -1
                    )
                } else {
                    // SAS-1: a re-commit without a fresh trailing space makes any
                    // previously pending auto-space stale — invalidate it
                    contextTracker.invalidateAutoSpacePending()
                }

                // Track that this commit was from candidate selection (manual tap)
                // Note: Auto-insertions set this separately to NEURAL_SWIPE
                if (contextTracker.getLastCommitSource() != PredictionSource.NEURAL_SWIPE) {
                    contextTracker.setLastCommitSource(
                        if (wasNextWordSelection) PredictionSource.NEXT_WORD
                        else PredictionSource.CANDIDATE_SELECTION
                    )
                }
            } catch (e: Exception) {
                // Log the failure (type/message only, never committed text) and reset the
                // selection-tracking state so a botched commit can't leave stale context
                // (hardening ported from the deleted InputCoordinator engine, step 6).
                Log.e(TAG, "Error in onSuggestionSelected", e)
                contextTracker.clearLastAutoInsertedWord()
                contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                contextTracker.expectingSelectionUpdate = false
                contextTracker.clearCurrentWordSuffix()
            }

            // Update context with the selected word
            updateContext(processedWord)

            // Clear current word
            // NOTE: Don't clear suggestions here - they're re-displayed after auto-insertion
            contextTracker.clearCurrentWord()

            // Next-word prediction call-site 2 (audit §4.4): after a MANUAL tap
            // commit the context just grew — chain another round of context-only
            // candidates (this is what makes "want" → tap "to" → suggest
            // "go/see/be" flow). H2 (review 2026-08-06): gated on
            // isManualSelection — the swipe AUTO-INSERT also routes through this
            // method, and an ungated call here replaced the swipe-alternates bar
            // (breaking swipe correction); the auto-insert path composes
            // next-word candidates via call-site 3 (appendNextWordToSwipeAlternates)
            // instead, which APPENDS after the alternates.
            if (isManualSelection) {
                maybeShowNextWordPredictions(editorInfo)
            }
        }

        return if (ic != null) processedWord else null
    }

    /**
     * Next-word prediction (audit 2026-08-06 §4, opt-in `next_word_prediction_enabled`,
     * default OFF): generate context-only candidates from the learned bigram LM and
     * show them in the (otherwise empty) suggestion bar.
     *
     * Pure gating + generation live in [NextWordPredictor] (unit-tested); this method
     * owns only the impure wiring — config/tracker reads, the shared
     * [predictionTasks] executor (same cancellation semantics as
     * [updatePredictionsForCurrentWord]), and the main-thread bar post. Runs the
     * store lookups off the UI thread.
     *
     * An empty candidate set leaves the bar untouched (empty) — showing nothing
     * is the designed common case (§4.2 confidence floor).
     */
    private fun maybeShowNextWordPredictions(editorInfo: EditorInfo?) {
        val inTermuxApp = try {
            editorInfo?.packageName == "com.termux"
        } catch (e: Exception) {
            false
        }
        val contextWords = contextTracker.getContextWords().toList()
        if (!NextWordPredictor.shouldShow(
                featureEnabled = config.next_word_prediction_enabled,
                onDeviceLearningEnabled = config.on_device_learning_enabled,
                wordPredictionEnabled = config.word_prediction_enabled,
                isPasswordMode = isPasswordMode,
                specialPromptActive = specialPromptActive,
                inTermuxApp = inTermuxApp,
                hasContext = contextWords.isNotEmpty(),
                fieldAllowsPersonalizedLearning = fieldAllowsPersonalizedLearning // M5
            )
        ) {
            return
        }
        val predictor = predictionCoordinator.getWordPredictor() ?: return

        // M6 (review 2026-08-06): snapshot the bar generation at submit time —
        // any bar-content change between now and the queued post (swipe results,
        // autocorrect prompt, another prediction pass) bumps it, and the post
        // aborts instead of overwriting the newer state.
        val generationAtSubmit = suggestionBar?.contentGeneration() ?: return

        predictionTasks.cancelAndSubmit {
            if (Thread.currentThread().isInterrupted) return@cancelAndSubmit

            val learned = predictor.getNextWordCandidates(contextWords, maxResults = 10)
            val candidates = NextWordPredictor.generate(
                learned = learned,
                lastCommittedWord = contextWords.lastOrNull(),
                personalizationBoost = { predictor.getPersonalizationBoostFor(it) },
                isWordAllowed = { w ->
                    !predictor.isWordDisabled(w) &&
                        (predictor.isInDictionary(w) || predictor.isInUserVocabulary(w))
                }
            )
            if (candidates.isEmpty()) return@cancelAndSubmit

            // Presentation (§4.4): stored lowercase → restore "I" forms and
            // user-dictionary proper-noun case for display.
            val displayWords = predictor.applyUserWordCaseToList(
                candidates.map { capitalizeIWord(it.word) }
            )
            val scores = candidates.map { it.score }
            // Task B: NEXT_WORD metas with the learned statistics behind each
            // candidate (frequency + conditional probability) as the sheet note.
            val metas = candidates.map { candidate ->
                SuggestionMeta(
                    SuggestionOrigin.NEXT_WORD,
                    note = NextWordPredictor.provenanceNote(candidate, contextWords)
                )
            }

            if (Thread.currentThread().isInterrupted || specialPromptActive) return@cancelAndSubmit
            mainHandler.post {
                // Skip if state moved on while queued: special prompt appeared or the
                // user already started typing the next word.
                if (specialPromptActive || isPasswordMode) return@post
                if (contextTracker.getCurrentWordLength() > 0) return@post
                suggestionBar?.let { bar ->
                    // M6: abort when the bar changed since submit — this post is stale.
                    if (bar.contentGeneration() != generationAtSubmit) return@post
                    nextWordSuggestionsActive = true
                    bar.setShowDebugScores(config.swipe_show_debug_scores)
                    bar.setShowOriginMarkers(config.suggestion_provenance_markers)
                    bar.setSuggestionsWithScores(displayWords, scores, metas)
                }
            }
        }
    }

    /**
     * Next-word call-site 4 (audit §4.4): the cursor parked after existing text
     * with NO partial word under it — e.g. tapping at the end of a sentence.
     * InputCoordinator's empty-prefix cursor-sync branch routes here instead of
     * clearing the bar directly, so context-only candidates can surface exactly
     * like Gboard's tap-into-text behavior. Every next-word guard applies; when
     * the feature is off this degrades to the original clear.
     *
     * SCOPE (L5, review 2026-08-06 — accepted limitation): candidates derive
     * from the SESSION's committed-word context (`contextTracker`), not from
     * the editor text preceding the parked cursor. Parking mid-way into text
     * typed in an earlier session therefore predicts from the wrong (or empty)
     * context and usually shows nothing — safe but not Gboard-complete. Reading
     * the words before the cursor via InputConnection would need a guarded
     * editor scan on every park; deferred until the feature (default OFF)
     * earns it.
     */
    fun handleCursorParkPrediction(editorInfo: EditorInfo?) {
        if (isPasswordMode) return
        suggestionBar?.clearSuggestions()
        maybeShowNextWordPredictions(editorInfo)
    }

    /**
     * Handle "Add to dictionary?" tap: add the word to user dictionary.
     * Does not modify the input text since the word is already committed.
     *
     * @param wordToAdd The word to add to dictionary
     */
    private fun handleAddToDictionary(wordToAdd: String) {
        if (wordToAdd.isEmpty()) {
            Log.w(TAG, "ADD TO DICTIONARY: Empty word, ignoring")
            return
        }

        vlog { "ADD TO DICTIONARY: Adding '$wordToAdd'" }

        // Add to user dictionary
        predictionCoordinator.getDictionaryManager()?.addUserWord(wordToAdd)

        // Refresh dictionary so word appears in predictions immediately
        predictionCoordinator.refreshCustomWords()

        // Clear tracking
        contextTracker.clearAutocorrectTracking()
        // v1.2.6: Clear special prompt flag
        specialPromptActive = false

        // Show confirmation message (clearAfter=true so bar clears instead of restoring prompt)
        suggestionBar?.showTemporaryMessage("Added '$wordToAdd' to dictionary", 2000L, clearAfter = true)
    }

    /**
     * #42: Handle exact typed word tap: commit the word, add to dictionary, and insert trailing space.
     * Unlike handleAddToDictionary, this is used during typing (not after word completion).
     *
     * @param exactWord The exact word user typed that they want to add
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    private fun handleExactWordAdd(exactWord: String, ic: InputConnection?, editorInfo: EditorInfo?) {
        if (exactWord.isEmpty()) {
            Log.w(TAG, "EXACT ADD: Empty word, ignoring")
            return
        }

        vlog { "EXACT ADD: Committing and adding '$exactWord' to dictionary" }

        // First, delete the partial word that was typed (since we're replacing it)
        val currentWord = contextTracker.getCurrentWord()
        if (currentWord.isNotEmpty() && ic != null) {
            // Detect Termux
            val inTermuxApp = try {
                editorInfo?.packageName == "com.termux"
            } catch (e: Exception) {
                false
            }

            if (inTermuxApp) {
                // Termux: Use backspace key events
                repeat(currentWord.length) {
                    keyeventhandler.send_key_down_up(android.view.KeyEvent.KEYCODE_DEL, 0)
                }
            } else {
                ic.deleteSurroundingText(currentWord.length, 0)
            }
        }

        // Commit the exact word with trailing space
        ic?.commitText("$exactWord ", 1)

        // Add to user dictionary
        predictionCoordinator.getDictionaryManager()?.addUserWord(exactWord)
        predictionCoordinator.refreshCustomWords()

        // Update context with the committed word
        updateContext(exactWord)

        // Reset state
        contextTracker.clearCurrentWord()
        predictionCoordinator.getWordPredictor()?.reset()
        suggestionBar?.clearSuggestions()

        // Show confirmation
        suggestionBar?.showTemporaryMessage("Added '$exactWord' to dictionary", 1500L, clearAfter = true)
    }

    /**
     * Handle autocorrect undo: replace the autocorrected word with the original.
     * Also adds the original word to dictionary so it won't be autocorrected again.
     *
     * @param tappedWord The word the user tapped (original word before autocorrect)
     * @param originalWord The original word that was autocorrected (for logging)
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    private fun handleAutocorrectUndo(
        tappedWord: String,
        originalWord: String,
        ic: InputConnection?,
        editorInfo: EditorInfo?
    ) {
        val correctedWord = contextTracker.getLastAutoInsertedWord()
        if (correctedWord.isNullOrEmpty()) {
            Log.w(TAG, "AUTOCORRECT UNDO: No corrected word tracked, falling back to normal selection")
            return
        }

        vlog { "AUTOCORRECT UNDO: Replacing '$correctedWord' with '$tappedWord'" }

        ic?.let { inputConnection ->
            // Detect Termux
            val inTermuxApp = try {
                editorInfo?.packageName == "com.termux"
            } catch (e: Exception) {
                false
            }

            // Delete the autocorrected word + trailing space
            val deleteCount = correctedWord.length + 1 // Word + space

            if (inTermuxApp) {
                // Termux: Use backspace key events
                repeat(deleteCount) {
                    keyeventhandler.send_key_down_up(android.view.KeyEvent.KEYCODE_DEL, 0)
                }
            } else {
                inputConnection.deleteSurroundingText(deleteCount, 0)
            }

            // Insert the original word with trailing space
            inputConnection.commitText("$tappedWord ", 1)

            // Update context with the original word
            updateContext(tappedWord)

            // Add to user dictionary so it won't be autocorrected again
            predictionCoordinator.getDictionaryManager()?.addUserWord(tappedWord)
            vlog { "AUTOCORRECT UNDO: Added '$tappedWord' to user dictionary" }

            // Refresh dictionary so word appears in predictions immediately
            predictionCoordinator.refreshCustomWords()

            // Clear autocorrect tracking
            contextTracker.clearAutocorrectTracking()
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.CANDIDATE_SELECTION)
            // v1.2.6: Clear special prompt flag
            specialPromptActive = false

            // Show confirmation message (clearAfter=true so bar clears instead of restoring prompt)
            suggestionBar?.showTemporaryMessage("Added '$tappedWord' to dictionary", 2000L, clearAfter = true)

            // Clear suggestions after brief delay (message will auto-clear)
        }
    }

    /**
     * Update context with a completed word.
     *
     * NOTE: This is a legacy helper method. New code should use
     * _contextTracker.commitWord() directly with appropriate PredictionSource.
     *
     * @param word Completed word to add to context
     */
    fun updateContext(word: String?) {
        if (word.isNullOrEmpty()) return

        // Use the current source from tracker, or UNKNOWN if not set
        val source = contextTracker.getLastCommitSource() ?: PredictionSource.UNKNOWN

        // Commit word to context tracker (not auto-inserted since this is manual update)
        contextTracker.commitWord(word, source, false)

        // Add word to WordPredictor for language detection + the gated learn
        // funnel. M5: the per-field incognito flag rides along so nothing typed
        // in an IME_FLAG_NO_PERSONALIZED_LEARNING field is learned.
        predictionCoordinator.getWordPredictor()
            ?.addWordToContext(word, fieldAllowsPersonalizedLearning)

        // Track word for multi-language detection
        try {
            SwipePredictorOrchestrator.getInstance(context).trackCommittedWord(word)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to track word for language detection", e)
        }
    }

    /**
     * Handle regular typing predictions (non-swipe).
     * Updates predictions as user types each character.
     *
     * @param text Text being typed
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    fun handleRegularTyping(text: String, ic: InputConnection?, editorInfo: EditorInfo?) {
        // Handle password mode: update password display, skip predictions
        if (isPasswordMode) {
            handlePasswordText(text)
            return
        }

        if (!config.word_prediction_enabled || predictionCoordinator.getWordPredictor() == null || suggestionBar == null) {
            return
        }

        // Track current word being typed
        when {
            text.length == 1 && text[0].isLetter() -> {
                // A typed letter starts/extends a partial — next-word candidates (if
                // showing) are superseded by ordinary prefix predictions below.
                nextWordSuggestionsActive = false
                contextTracker.appendToCurrentWord(text)
                // If just started a new word (first letter), clear auto-insert and autocorrect tracking
                // This prevents incorrectly deleting a previously swiped word when
                // user types a new word then taps a prediction
                if (contextTracker.getCurrentWordLength() == 1) {
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.clearAutocorrectTracking()
                    contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                    // v1.2.6: Clear special prompt flag - user is typing a new word
                    specialPromptActive = false
                }
                updatePredictionsForCurrentWord()
            }
            text.length == 1 && !text[0].isLetter() -> {
                // Any non-letter character - update context and reset current word

                // If we had a word being typed, add it to context before clearing
                if (contextTracker.getCurrentWordLength() > 0) {
                    val completedWord = contextTracker.getCurrentWord()

                    // Auto-correct the typed word if feature is enabled
                    // DISABLED in Termux app due to erratic behavior with terminal input
                    val inTermuxApp = try {
                        editorInfo?.packageName == "com.termux"
                    } catch (e: Exception) {
                        false
                    }

                    // Issue #72: Auto-capitalize "I" words when completed
                    // Check BEFORE autocorrect so this works even if autocorrect is disabled
                    val capitalizedWord = capitalizeIWord(completedWord)
                    val needsICapitalization = text == " " && !inTermuxApp &&
                        capitalizedWord != completedWord

                    if (needsICapitalization) {
                        ic?.let { inputConnection ->
                            // Delete the typed word + space (already committed)
                            inputConnection.deleteSurroundingText(completedWord.length + 1, 0)
                            // Insert the capitalized word with trailing space
                            inputConnection.commitText("$capitalizedWord ", 1)
                            updateContext(capitalizedWord)
                            contextTracker.clearCurrentWord()
                            contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)
                            vlog { "I-WORD CAPITALIZE: '$completedWord' → '$capitalizedWord'" }
                            predictionCoordinator.getWordPredictor()?.reset()
                            suggestionBar?.clearSuggestions()
                            return
                        }
                    }

                    // Non-prose guard (2026-07-13): the word tracker only sees
                    // LETTERS, so "teh" inside "foo.teh" / "user@teh" /
                    // "https://teh…" looks identical to prose "teh". The editor
                    // text reveals the real token — skip autocorrect when the
                    // cursor just left a URL/email/path-like token, otherwise
                    // domains and pasted-then-edited URLs get corrupted.
                    val inNonProseToken = AutocorrectContextGuard.isNonProseContext(
                        ic?.getTextBeforeCursor(72, 0)
                    )

                    if (config.autocorrect_enabled && predictionCoordinator.getWordPredictor() != null &&
                        text == " " && !inTermuxApp && !inNonProseToken) {
                        var correctedWord = predictionCoordinator.getWordPredictor()?.autoCorrect(completedWord)

                        // If correction was made, replace the typed word
                        if (correctedWord != null && correctedWord != completedWord) {
                            // Preserve original capitalization pattern
                            correctedWord = preserveCapitalization(completedWord, correctedWord)
                            // Also apply I-word capitalization
                            correctedWord = capitalizeIWord(correctedWord)

                            ic?.let { inputConnection ->
                                // At this point:
                                // - The typed word "thid" has been committed via KeyEventHandler.send_text()
                                // - The space " " has ALSO been committed via handle_text_typed(" ")
                                // - Editor contains "thid "
                                // - We need to delete both the word AND the space, then insert corrected word + space

                                // Delete the typed word + space (already committed)
                                inputConnection.deleteSurroundingText(completedWord.length + 1, 0)

                                // Insert the corrected word WITH trailing space (normal apps only)
                                inputConnection.commitText("$correctedWord ", 1)

                                // Update context with corrected word
                                updateContext(correctedWord)

                                // Clear current word
                                contextTracker.clearCurrentWord()

                                // Track autocorrect state for undo functionality
                                // When user taps original word in suggestions, we can detect and replace
                                contextTracker.setLastAutoInsertedWord(correctedWord)
                                contextTracker.setLastCommitSource(PredictionSource.AUTOCORRECT)
                                contextTracker.setLastAutocorrectOriginalWord(completedWord)

                                vlog { "AUTOCORRECT: '$completedWord' → '$correctedWord' (tracking for undo)" }

                                // v1.2.6 FIX: Cancel pending prediction task and set flag to prevent overwriting
                                predictionTasks.cancelCurrent()
                                specialPromptActive = true

                                // Show original word as first suggestion for easy undo
                                suggestionBar?.setSuggestionsWithScores(
                                    listOf(completedWord, correctedWord), // Original word first for undo
                                    listOf(0, 0),
                                    listOf(
                                        SuggestionMeta(SuggestionOrigin.AUTOCORRECT, note = "Your typed word (tap to undo)"),
                                        SuggestionMeta(SuggestionOrigin.AUTOCORRECT, note = "Autocorrected from “$completedWord”")
                                    )
                                )

                                // Reset prediction state
                                predictionCoordinator.getWordPredictor()?.reset()

                                return // Skip normal text processing - we've handled everything
                            }
                        }
                    }

                    updateContext(completedWord)

                    // Check if this word is NOT in dictionary - offer to add it
                    // Only prompt if:
                    // 1. Word was just completed with space (text == " ")
                    // 2. Word is at least 3 characters (avoid prompts for short words)
                    // 3. Word is not in dictionary
                    // 4. Not a valid possessive of a known word (UT-2) and not a
                    //    URL/email/path fragment (UT-3) — see
                    //    AutocorrectContextGuard.shouldOfferAddToDictionary.
                    if (text == " ") {
                        val wordPredictor = predictionCoordinator.getWordPredictor()
                        val dictionaryManager = predictionCoordinator.getDictionaryManager()
                        val shouldPrompt = AutocorrectContextGuard.shouldOfferAddToDictionary(
                            token = completedWord,
                            inNonProseToken = inNonProseToken,
                            isKnownWord = { w ->
                                // Predictor not ready → treat as known (never prompt),
                                // preserving the original `?: true` behavior.
                                (wordPredictor?.isInDictionary(w) ?: true) ||
                                    (dictionaryManager?.isUserWord(w) ?: false)
                            },
                            isDisabledWord = { w -> wordPredictor?.isWordDisabled(w) ?: false }
                        )

                        if (shouldPrompt) {
                            // v1.2.6 FIX: Cancel pending prediction task and set flag to prevent overwriting
                            predictionTasks.cancelCurrent()
                            specialPromptActive = true

                            // Store word for add-to-dictionary handling
                            contextTracker.setLastAutocorrectOriginalWord(completedWord)
                            contextTracker.setLastCommitSource(PredictionSource.USER_TYPED_TAP)

                            // Show "Add to dictionary?" prompt. The wire string is
                            // produced by the shared typed model (single source of
                            // truth for the dict_add: protocol).
                            suggestionBar?.setSuggestionsWithScores(
                                listOf(Suggestion.AddToDictionary(completedWord).wire),
                                listOf(0)
                            )

                            vlog { "UNKNOWN WORD: '$completedWord' - showing add to dictionary prompt" }

                            // Skip clearing suggestions below
                            contextTracker.clearCurrentWord()
                            predictionCoordinator.getWordPredictor()?.reset()
                            return
                        }
                    }
                }

                // Sentence boundary (audit 2026-08-06 §4.6): after `.` `?` `!` the
                // learned-context window resets so recordSequence never learns
                // bigrams spanning a sentence boundary (noise for both context
                // boosting and next-word generation).
                if (text[0] == '.' || text[0] == '?' || text[0] == '!') {
                    predictionCoordinator.getWordPredictor()?.onSentenceBoundary()
                }

                // Reset current word
                contextTracker.clearCurrentWord()
                predictionCoordinator.getWordPredictor()?.reset()
                nextWordSuggestionsActive = false
                suggestionBar?.clearSuggestions()

                // Next-word prediction call-site 1 (audit §4.4): after a typed word
                // completes with a space, offer context-only candidates instead of
                // leaving the bar empty. Space only — after sentence-final punct the
                // context was just cleared, and other punctuation keeps the bar empty.
                if (text == " ") {
                    maybeShowNextWordPredictions(editorInfo)
                }
            }
            text.length > 1 -> {
                // Multi-character input (paste, etc) - reset
                contextTracker.clearCurrentWord()
                predictionCoordinator.getWordPredictor()?.reset()
                nextWordSuggestionsActive = false
                suggestionBar?.clearSuggestions()
            }
        }
    }

    /**
     * Handle backspace for prediction tracking.
     * Updates predictions as user deletes characters.
     */
    fun handleBackspace() {
        // Handle password mode: update password display
        if (isPasswordMode) {
            handlePasswordBackspace()
            return
        }

        // Backspace dismisses next-word candidates (audit §4.4 replacement
        // semantics — the only new state next-word introduces).
        if (nextWordSuggestionsActive && contextTracker.getCurrentWordLength() == 0) {
            nextWordSuggestionsActive = false
            suggestionBar?.clearSuggestions()
            return
        }

        if (contextTracker.getCurrentWordLength() > 0) {
            contextTracker.deleteLastChar()
            if (contextTracker.getCurrentWordLength() > 0) {
                updatePredictionsForCurrentWord()
            } else {
                suggestionBar?.clearSuggestions()
            }
        }
    }

    /**
     * WP9 R-1 steps 5+6 — THE cursor-sync prediction entry. Called by
     * [InputCoordinator.onCursorMoved]'s debounced runnable (unconditionally since step 6), AFTER
     * InputCoordinator has done its cursor bookkeeping ([PredictionContextTracker.onCursorPositionChanged],
     * the 100ms debounce, and [PredictionContextTracker.synchronizeWithCursor] which populates
     * [PredictionContextTracker.currentWord] with the synced rawPrefix). This is the cursor-sync
     * sibling of the tap-path [handleRegularTyping] → it delegates straight into the SAME
     * [updatePredictionsForCurrentWord] pipeline the typing path uses, so cursor-sync now shares
     * ONE contraction-injection / exact-add / I-word-capitalization / capitalization-from-prefix
     * implementation — and, crucially, the [specialPromptActive] guard that lives inside it. That
     * guard is what structurally resolves R-7: a cursor-sync pass can no longer clobber an
     * autocorrect-undo / add-to-dictionary prompt owned by SH, because the SINGLE pipeline both
     * paths share checks the flag before submitting and inside the posted runnable.
     *
     * Structural note on the delegation split (R-1 step 5): InputCoordinator KEEPS
     *   - [PredictionContextTracker.onCursorPositionChanged] (SAS-1 auto-space invalidation),
     *   - the 100ms debounce (oracle scenario 27 pins that two rapid moves collapse to one pass),
     *   - [PredictionContextTracker.synchronizeWithCursor] with the caller's `language` param
     *     (CJK skip + input-type gating — the ONLY consumer of that param; both pipelines then use
     *     the SAME `predictionCoordinator.getWordPredictor()`, so predictor language is preserved),
     *   - the empty-prefix else-branch (preserve-vs-clear the bar on autocorrect-undo / swipe).
     * Only the prediction+post phase moves here — reached exactly when the synced prefix is non-empty,
     * so the empty-prefix branch never reaches SH and there is no double-clear race.
     *
     * Behavior deltas vs. the legacy [InputCoordinator.triggerPredictionsForPrefix] (intentional):
     *   - exact_add now surfaces for an unknown word even when the predictor returns ZERO
     *     predictions. The legacy IC path early-returned on `allResults.isEmpty()` BEFORE its
     *     exact-add branch and additionally post-guarded on `finalWords.isNotEmpty()`; SH's
     *     [updatePredictionsForCurrentWord] runs the exact-add branch on the empty list, so
     *     `finalWords = [exact_add wire]` is posted (oracle scenario 26 flip, step 5).
     *   - the [specialPromptActive] guard (see above) — R-7 resolved structurally.
     * Dictionary possessives are unaffected: they arrive as ordinary predictions on BOTH paths,
     * so there is no gateable possessive delta here (oracle scenario 25 stays as-is).
     */
    fun handleCursorSyncPrediction() {
        // Password mode: never surface predictions from a cursor move (matches the tap-path guard
        // in handleRegularTyping and handlePredictionResults). synchronizeWithCursor already skips
        // password input types, so currentWord is normally empty here — this is defence in depth.
        if (isPasswordMode) return
        updatePredictionsForCurrentWord()
    }

    /**
     * Update predictions based on current partial word.
     *
     * Shared by the typing path ([handleRegularTyping] / [handleBackspace]) and, since WP9 R-1
     * step 5, the cursor-sync path ([handleCursorSyncPrediction]). Reads the partial from
     * [PredictionContextTracker.currentWord], which typing populates letter-by-letter and cursor-sync
     * populates via [PredictionContextTracker.synchronizeWithCursor] (the synced rawPrefix, which may
     * contain an apostrophe — e.g. cursor after "don'"). The apostrophe-stripped secondary search
     * term below is a no-op for the typing path (typed partials are letters-only) and restores the
     * legacy IC cursor-sync's dual-search so contraction bases hidden behind an apostrophe still hit.
     */
    private fun updatePredictionsForCurrentWord() {
        if (contextTracker.getCurrentWordLength() > 0) {
            val partial = contextTracker.getCurrentWord()

            // Check if first letter is uppercase (user typed with Shift, or cursor-synced from a
            // capitalized token). Mirrors the legacy IC cursor-sync rawPrefix capitalization check.
            val shouldCapitalize = partial.isNotEmpty() && partial[0].isUpperCase()

            // Copy context to be thread-safe
            val contextWords = contextTracker.getContextWords().toList()

            // Cancel previous task if running, then submit new prediction task
            predictionTasks.cancelAndSubmit {
                if (Thread.currentThread().isInterrupted) return@cancelAndSubmit

                // Use contextual prediction (Heavy operation). WP9 step 5: search the partial AND,
                // if it carries an apostrophe (cursor-synced mid-contraction, e.g. "don'"), the
                // apostrophe-free form too — restoring the legacy IC cursor-sync dual-search so a
                // contraction base hidden behind an apostrophe still hits. For the typing path the
                // partial is letters-only, so `noApostrophe == partial` and the second search is
                // skipped entirely (pure no-op — the tap path is byte-identical to before).
                var result = predictionCoordinator.getWordPredictor()?.predictWordsWithContext(partial, contextWords)
                val noApostrophe = partial.replace("'", "").replace("’", "")
                if (noApostrophe != partial && noApostrophe.isNotEmpty() &&
                    (result?.words?.isEmpty() != false)
                ) {
                    // Primary (apostrophe-carrying) search found nothing — retry apostrophe-free.
                    // Only overrides when the primary was empty, so the apostrophe form keeps its
                    // ranking whenever it does produce results.
                    predictionCoordinator.getWordPredictor()
                        ?.predictWordsWithContext(noApostrophe, contextWords)
                        ?.takeIf { it.words.isNotEmpty() }
                        ?.let { result = it }
                }

                // Bind a stable non-null local: `result` is a reassignable `var` captured by the
                // lambda (WP9 step 5 dual-search), so Kotlin can't smart-cast it after the guard.
                val prediction = result ?: return@cancelAndSubmit
                if (Thread.currentThread().isInterrupted) return@cancelAndSubmit

                // v1.2.0: Apply contraction transformation (e.g., "dont" -> "don't")
                // Check if the typed partial matches a contraction key
                val contractionWords = mutableListOf<String>()
                val contractionScores = mutableListOf<Int>()

                // Check if the exact partial is a non-paired contraction key (e.g., dont → don't)
                val contractionMapping = contractionManager.getNonPairedMapping(partial)
                if (contractionMapping != null) {
                    // Add contraction as first suggestion with high score
                    // Issue #72: Also capitalize I-contractions (im → I'm, ill → I'll)
                    contractionWords.add(capitalizeIWord(contractionMapping))
                    contractionScores.add(prediction.scores.firstOrNull()?.plus(1000) ?: 10000)
                }

                // Check if the exact partial is a paired contraction base (e.g., its → it's)
                // Paired contractions are words where BOTH the base and contraction are valid
                // Only inject paired contractions for prefixes >= 3 chars to avoid
                // corrupting frequency ranking with possessive forms (t→t's, a→a's)
                val pairedVariants = if (partial.length >= 3) contractionManager.getPairedContractions(partial) else null
                if (pairedVariants != null && contractionMapping == null) {
                    // Add paired variants as high-priority suggestions alongside the base word
                    for (variant in pairedVariants) {
                        contractionWords.add(capitalizeIWord(variant))
                        contractionScores.add(prediction.scores.firstOrNull()?.plus(500) ?: 5000)
                    }
                }

                // v1.2.6 FIX: Transform ALL predictions through contraction manager
                // e.g., if predictor suggests "cant", transform to "can't"
                // Issue #72: Also capitalize I-words (i → I, i'm → I'm)
                val transformedPredictions = prediction.words.map { word ->
                    val contracted = contractionManager.getNonPairedMapping(word) ?: word
                    capitalizeIWord(contracted)
                }

                // Merge contraction with predictions (contraction first, then transformed predictions)
                // Filter out duplicates (contraction/paired variants might already be in list).
                // Task B: aligned (word, score, meta) merge — filtering a dup out of the
                // middle keeps each score/meta attached to ITS word (the old
                // `scores.take(filteredCount)` could shift scores past a mid-list dup).
                val injectedLowerSet = contractionWords.map { it.lowercase() }.toSet()
                val mergedWords = contractionWords.toMutableList()
                val mergedScores = contractionScores.toMutableList()
                val mergedMetas = MutableList(contractionWords.size) {
                    SuggestionMeta(SuggestionOrigin.CONTRACTION)
                }
                transformedPredictions.forEachIndexed { i, transformed ->
                    if (transformed.lowercase() in injectedLowerSet) return@forEachIndexed
                    mergedWords.add(transformed)
                    mergedScores.add(prediction.scores.getOrElse(i) { 0 })
                    mergedMetas.add(
                        prediction.metas?.getOrNull(i)
                            ?: SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX)
                    )
                }

                // Apply capitalization transformation if user started with uppercase
                val transformedWords = if (shouldCapitalize) {
                    mergedWords.map { word ->
                        word.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                        }
                    }
                } else {
                    mergedWords
                }

                // #42: Add exact typed word option if enabled and word is not in predictions
                // This allows users to tap the exact typed word to add it to dictionary
                val finalWords: List<String>
                val finalScores: List<Int>
                val finalMetas: List<SuggestionMeta>
                if (config.show_exact_typed_word && partial.length >= 2) {
                    // Check if the exact partial (with capitalization) is already in predictions
                    val exactTyped = if (shouldCapitalize) {
                        partial.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                        }
                    } else {
                        partial
                    }
                    val exactLower = exactTyped.lowercase()
                    val alreadyInPredictions = transformedWords.any { it.lowercase() == exactLower }
                    val isUserWord = predictionCoordinator.getDictionaryManager()?.isUserWord(exactTyped) ?: false
                    val isInDictionary = predictionCoordinator.getWordPredictor()?.isInDictionary(exactTyped) ?: true

                    if (!alreadyInPredictions && !isUserWord && !isInDictionary) {
                        // Add exact typed word at the end as an ExactAdd suggestion.
                        // Wire string from the shared typed model (single source of
                        // truth for the exact_add: protocol). End position so it
                        // doesn't displace the best prediction.
                        finalWords = transformedWords + Suggestion.ExactAdd(exactTyped).wire
                        finalScores = mergedScores + 0  // Low score since it's at the end
                        finalMetas = mergedMetas + SuggestionMeta(SuggestionOrigin.EXACT_ADD)
                        vlog { "EXACT ADD: Added '$exactTyped' as tap-to-add option" }
                    } else {
                        finalWords = transformedWords
                        finalScores = mergedScores
                        finalMetas = mergedMetas
                    }
                } else {
                    finalWords = transformedWords
                    finalScores = mergedScores
                    finalMetas = mergedMetas
                }

                // Post result to UI thread
                // v1.2.6 FIX: Check if task was cancelled or special prompt is active
                if (finalWords.isNotEmpty() && suggestionBar != null &&
                    !Thread.currentThread().isInterrupted && !specialPromptActive) {
                    // Use Handler.post() instead of View.post() — View.post() silently
                    // drops runnables when the View is not attached to a window
                    mainHandler.post {
                        // v1.2.6: Skip if special prompt became active while queued
                        if (specialPromptActive) return@post

                        suggestionBar?.let { bar ->
                            // Prefix predictions supersede any next-word display state
                            // (covers the cursor-sync path; the typing path already
                            // cleared the flag in the letter branch).
                            nextWordSuggestionsActive = false
                            bar.setShowDebugScores(config.swipe_show_debug_scores)
                            bar.setShowOriginMarkers(config.suggestion_provenance_markers)
                            // v1.2.0: Use merged scores that include contraction scores
                            bar.setSuggestionsWithScores(finalWords, finalScores, finalMetas)
                        }
                    }
                }
            }
        }
    }

    /**
     * Smart delete last word - deletes the last auto-inserted word or last typed word.
     * Handles edge cases to avoid deleting too much text.
     *
     * @param ic InputConnection for text manipulation
     * @param editorInfo Editor info for app detection
     */
    fun handleDeleteLastWord(ic: InputConnection?, editorInfo: EditorInfo?) {
        if (ic == null) return

        // Check if we're in Termux - if so, use Ctrl+Backspace fallback
        val inTermux = try {
            editorInfo?.packageName == "com.termux"
        } catch (e: Exception) {
            Log.e(TAG, "DELETE_LAST_WORD: Error detecting Termux", e)
            false
        }

        // For Termux, use Ctrl+W key event which Termux handles correctly
        // Termux doesn't support InputConnection methods, but processes terminal control sequences
        if (inTermux) {
            vlog { "DELETE_LAST_WORD: Using Ctrl+W (^W) for Termux" }
            // Send Ctrl+W which is the standard terminal "delete word backward" sequence
            keyeventhandler.send_key_down_up(
                KeyEvent.KEYCODE_W,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            // Clear tracking
            contextTracker.clearLastAutoInsertedWord()
            contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
            return
        }

        // First, try to delete the last auto-inserted word if it exists
        val lastAutoInserted = contextTracker.getLastAutoInsertedWord()
        if (!lastAutoInserted.isNullOrEmpty()) {
            vlog { "DELETE_LAST_WORD: Deleting auto-inserted word: '$lastAutoInserted'" }

            // Get text before cursor to verify
            val textBefore = ic.getTextBeforeCursor(100, 0)
            if (textBefore != null) {
                val beforeStr = textBefore.toString()

                // Check if the last auto-inserted word is actually at the end
                // Account for trailing space that swipe words have
                val hasTrailingSpace = beforeStr.endsWith(" ")
                val lastWord = if (hasTrailingSpace) {
                    beforeStr.substring(0, beforeStr.length - 1).trim()
                } else {
                    beforeStr.trim()
                }

                // Find last word in the text
                val lastSpaceIdx = lastWord.lastIndexOf(' ')
                val actualLastWord = if (lastSpaceIdx >= 0) {
                    lastWord.substring(lastSpaceIdx + 1)
                } else {
                    lastWord
                }

                // Verify this matches our tracked word (case-insensitive to be safe)
                if (actualLastWord.equals(lastAutoInserted, ignoreCase = true)) {
                    // Delete the word + trailing space if present
                    var deleteCount = lastAutoInserted.length
                    if (hasTrailingSpace) deleteCount += 1

                    ic.deleteSurroundingText(deleteCount, 0)
                    vlog { "DELETE_LAST_WORD: Deleted $deleteCount characters" }

                    // Clear tracking
                    contextTracker.clearLastAutoInsertedWord()
                    contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
                    return
                }
            }

            // If verification failed, fall through to delete last word generically
            vlog { "DELETE_LAST_WORD: Auto-inserted word verification failed, using generic delete" }
        }

        // Fallback: Delete the last word before cursor (generic approach)
        val textBefore = ic.getTextBeforeCursor(100, 0)
        if (textBefore.isNullOrEmpty()) {
            vlog { "DELETE_LAST_WORD: No text before cursor, falling back to Ctrl+Backspace" }
            keyeventhandler.send_key_down_up(
                KeyEvent.KEYCODE_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            return
        }

        val beforeStr = textBefore.toString()
        var cursorPos = beforeStr.length

        // Skip trailing whitespace
        while (cursorPos > 0 && beforeStr[cursorPos - 1].isWhitespace()) {
            cursorPos--
        }

        if (cursorPos == 0) {
            vlog { "DELETE_LAST_WORD: Only whitespace before cursor" }
            return
        }

        // Find the start of the last word
        var wordStart = cursorPos
        while (wordStart > 0 && !beforeStr[wordStart - 1].isWhitespace()) {
            wordStart--
        }

        // Calculate delete count (word + any trailing spaces we skipped)
        var deleteCount = beforeStr.length - wordStart

        // Safety check: don't delete more than 50 characters at once
        if (deleteCount > 50) {
            vlog { "DELETE_LAST_WORD: Refusing to delete $deleteCount characters (safety limit)" }
            deleteCount = 50
        }

        vlog { "DELETE_LAST_WORD: Deleting last word (generic), count=$deleteCount" }
        if (!ic.deleteSurroundingText(deleteCount, 0)) {
            vlog { "DELETE_LAST_WORD: deleteSurroundingText failed, falling back to Ctrl+Backspace" }
            keyeventhandler.send_key_down_up(
                KeyEvent.KEYCODE_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        }

        // Clear tracking
        contextTracker.clearLastAutoInsertedWord()
        contextTracker.setLastCommitSource(PredictionSource.UNKNOWN)
    }

    /**
     * Augment predictions with possessive forms.
     *
     * OPTIMIZATION v5 (perftodos5.md): Generate possessives dynamically instead of storing 1700+ entries.
     * For each top prediction (limit to first 3-5), generate possessive form if applicable.
     *
     * @param predictions List of predictions to augment (modified in-place)
     * @param scores List of scores corresponding to predictions (modified in-place)
     */
    private fun augmentPredictionsWithPossessives(predictions: MutableList<String>, scores: MutableList<Int>) {
        if (predictions.isEmpty()) return

        // Generate possessives for top 3 predictions only (avoid clutter)
        val limit = minOf(3, predictions.size)
        val possessivesToAdd = mutableListOf<String>()
        val possessiveScores = mutableListOf<Int>()

        for (i in 0 until limit) {
            val word = predictions[i]
            val possessive = contractionManager.generatePossessive(word)

            if (possessive != null) {
                // Don't add if possessive already exists in predictions
                val alreadyExists = predictions.any { it.equals(possessive, ignoreCase = true) }

                if (!alreadyExists) {
                    possessivesToAdd.add(possessive)
                    // Slightly lower score than base word (base word is more common)
                    val baseScore = scores.getOrElse(i) { 128 }
                    possessiveScores.add(baseScore - 10) // 10 points lower than base
                }
            }
        }

        // Add possessives to the end of predictions list
        if (possessivesToAdd.isNotEmpty()) {
            predictions.addAll(possessivesToAdd)
            scores.addAll(possessiveScores)

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Added ${possessivesToAdd.size} possessive forms to predictions")
            }
        }
    }
}
