package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.swipe.CtcEngineAdapter
import tribixbite.cleverkeys.swipe.GeometricEngineAdapter
import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * Owns a single-threaded prediction executor and its in-flight tasks, exposing a small
 * cancel/submit/shutdown surface. Pure JVM (no Android types) so it is unit-testable.
 *
 * ## Two priority slots (WP9 audit M-2, 2026-08-11)
 *
 * The runner tracks the in-flight task of each slot independently while still executing
 * everything on ONE thread (so callers keep single-thread confinement of their own state):
 *
 *  - **Foreground** ([cancelAndSubmit]) — user-visible work whose result is awaited (a swipe
 *    decode, a typing prediction). A new foreground submission cancels the previous foreground
 *    task AND any in-flight background task: the newest user gesture always wins the thread.
 *  - **Background** ([submitBackground]) — opportunistic work nobody is waiting on (an engine
 *    prewarm). A new background submission cancels only the previous *background* task; it
 *    NEVER cancels foreground work. This is the M-2 fix: before it existed, a prewarm posted
 *    from `onStartInputView` could cancel an in-flight swipe decode, silently losing the swipe.
 *
 * Both slots run FIFO on the same executor, so a queued background task delays (but never
 * cancels) a later foreground task; that is bounded by the prewarm's own build cost and was
 * the pre-existing behavior.
 *
 * Every submitted task starts with a cleared interrupt status: cancelling a *running* task
 * interrupts the shared worker thread, and a task that returns without consuming that
 * interrupt would otherwise leak the flag into the next task (which typically treats
 * `isInterrupted` as "I was superseded, drop my result" — a silent result loss).
 *
 * Submissions after [shutdown] are silently dropped rather than throwing, which matches the
 * lifecycle where predictions can be requested while the IME is tearing down.
 */
class PredictionTaskRunner(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    @Volatile
    private var currentTask: Future<*>? = null

    @Volatile
    private var backgroundTask: Future<*>? = null

    /** True once the underlying executor has been shut down. */
    val isShutdown: Boolean
        get() = executor.isShutdown

    /** Cancels (interrupting) the currently-running FOREGROUND task, if any. */
    fun cancelCurrent() {
        currentTask?.cancel(true)
    }

    /** Cancels (interrupting) the currently-running BACKGROUND task, if any. */
    fun cancelBackground() {
        backgroundTask?.cancel(true)
    }

    /**
     * Cancels the in-flight foreground AND background tasks, then submits [task] in the
     * foreground slot; a no-op if the executor is already shut down.
     */
    fun cancelAndSubmit(task: Runnable) {
        cancelCurrent()
        cancelBackground()
        if (executor.isShutdown) return
        currentTask = submitGuarded(task)
    }

    /**
     * Submits [task] in the background slot, superseding (cancelling) only a previous
     * background task. Foreground work in flight is left running — see the class KDoc.
     * A no-op if the executor is already shut down.
     */
    fun submitBackground(task: Runnable) {
        cancelBackground()
        if (executor.isShutdown) return
        backgroundTask = submitGuarded(task)
    }

    /** Submits [task] with a stale-interrupt clear, swallowing post-shutdown rejection. */
    private fun submitGuarded(task: Runnable): Future<*>? = try {
        executor.submit {
            // Drop an interrupt left over from a cancelled predecessor before this task's own
            // work starts (see class KDoc). Harmless for this task's own cancellation: a task
            // cancelled while still queued never runs at all, and a cancel landing after this
            // point sets the flag again.
            Thread.interrupted()
            task.run()
        }
    } catch (e: RejectedExecutionException) {
        null
    }

    /** Cancels the in-flight tasks and shuts the executor down, interrupting running work. */
    fun shutdown() {
        cancelCurrent()
        cancelBackground()
        executor.shutdownNow()
    }
}

/**
 * Thin swipe-gesture / ML front-end and cursor-sync bookkeeper (WP9 R-1 step 6).
 *
 * Owns:
 * - Swipe gesture completion → neural prediction request ([handleSwipeTyping] /
 *   [performSwipeTyping], incl. the cold-start engine-ready replay guard)
 * - Swipe ML trace capture ([getCurrentSwipeData] / [resetSwipeData]) — consumed by
 *   SuggestionHandler (auto-insert path) and SuggestionBridge (tap path) via MLDataCollector
 * - Cursor-sync bookkeeping: SAS-1 position invalidation, the 100ms debounce, and
 *   [PredictionContextTracker.synchronizeWithCursor] ([onCursorMoved])
 * - View-side helpers for the unified commit path (haptics, latched-shift clear,
 *   keyboard height)
 *
 * Does NOT own suggestion presentation or the commit engine: both the swipe-results flow
 * ([handlePredictionResults]) and the cursor-sync prediction+post phase delegate to
 * [SuggestionHandler] — THE single pipeline (possessive augmentation, password guard,
 * contraction injection, exact-add, `specialPromptActive` prompt guard, and the one
 * deletion/spacing/tracking commit engine). InputCoordinator's divergent duplicates were
 * deleted in R-1 step 6 (see docs/audit/remediation/3-core-ime.md).
 *
 * This is also the planned insertion site for the R-1 step 7 SwipeEngineRouter (geometric
 * engine for non-QWERTY layouts): any engine's prediction list feeds the same
 * [SuggestionHandler.handleSwipePredictionResults] seam.
 */
class InputCoordinator(
    private val context: Context,
    private var config: Config,
    private val contextTracker: PredictionContextTracker,
    private val predictionCoordinator: PredictionCoordinator,
    private var suggestionBar: SuggestionBar?,
    private val keyboardView: Keyboard2View
) {
    companion object {
        private const val TAG = "InputCoordinator"

        // v1.2.6: Debounce delay for cursor sync (ms)
        // Prevents excessive IPC calls during drag selection
        private const val CURSOR_SYNC_DEBOUNCE_MS = 100L

        /**
         * Pure identity check for the cold-start swipe replay guard (F4), extracted so it is
         * unit-testable without constructing an [InputCoordinator].
         *
         * The replay may run seconds after the swipe; if the input field changed in the meantime
         * the framework hands out a NEW [InputConnection] and [EditorInfo], so reference identity
         * against the live pair reliably detects staleness. When the live pair is unavailable
         * ([hasLiveInput] false — e.g. no provider wired) we fall back to only requiring the
         * captured connection to be non-null (best-effort defensive guard).
         */
        internal fun isReplayInputStillCurrent(
            capturedIc: InputConnection?,
            capturedEditor: EditorInfo?,
            liveIc: InputConnection?,
            liveEditor: EditorInfo?,
            hasLiveInput: Boolean
        ): Boolean {
            if (!hasLiveInput) return capturedIc != null
            return capturedIc != null && capturedIc === liveIc && capturedEditor === liveEditor
        }
    }

    // v1.2.6: Handler for debouncing cursor sync
    private val syncHandler = Handler(Looper.getMainLooper())
    private var pendingSyncRunnable: Runnable? = null

    // Debug logger for SwipeDebugActivity integration
    // Only active when debug mode is enabled in settings
    private var debugLogger: ((String) -> Unit)? = null

    /**
     * Sets the debug logger for pipeline visibility.
     * When set, debug messages appear in SwipeDebugActivity instead of logcat.
     *
     * @param logger Lambda that broadcasts debug messages to SwipeDebugActivity
     */
    fun setDebugLogger(logger: ((String) -> Unit)?) {
        debugLogger = logger
    }

    /**
     * Supplies the CURRENTLY-active input connection + editor info (i.e.
     * `InputMethodService.currentInputConnection` / `currentInputEditorInfo` at call time).
     *
     * Used by the cold-start swipe replay to detect that the input field changed between the
     * swipe and the (possibly seconds-later) engine-ready callback, so the replay does not
     * commit text into a stale/other field. Optional — if unset the replay falls back to a
     * best-effort guard on the captured references only.
     */
    fun interface CurrentInputProvider {
        fun current(): Pair<InputConnection?, EditorInfo?>
    }

    private var currentInputProvider: CurrentInputProvider? = null

    /** Wires the live-input provider (see [CurrentInputProvider]); set by the service. */
    fun setCurrentInputProvider(provider: CurrentInputProvider?) {
        currentInputProvider = provider
    }

    /**
     * WP9 R-1 steps 4+6: SuggestionHandler that owns the ENTIRE swipe result flow — bar
     * presentation (possessives, password guard, shift/caps transform) AND the commit engine.
     * Wired post-construction (SH is created alongside this IC) to avoid a circular constructor
     * dependency. Step 6 removed the legacy IC-only path and the `unified_swipe_pipeline` flag:
     * an unwired delegate (misconfigured unit context) now clears the bar and logs instead of
     * running a divergent fallback.
     */
    private var swipeResultDelegate: SuggestionHandler? = null

    /** Wires the unified-swipe delegate (see [swipeResultDelegate]); set by ManagerInitializer. */
    fun setSwipeResultDelegate(handler: SuggestionHandler?) {
        swipeResultDelegate = handler
    }

    /**
     * WP9 R-1 steps 5+6: SuggestionHandler that owns the cursor-sync prediction flow.
     * [onCursorMoved]'s debounced runnable — after the (retained) cursor bookkeeping +
     * [PredictionContextTracker.synchronizeWithCursor] — routes the prediction+post phase to
     * [SuggestionHandler.handleCursorSyncPrediction]: SH's single guarded pipeline (contraction
     * injection, exact-add, I-word cap, capitalization-from-prefix, AND the `specialPromptActive`
     * prompt guard) — structurally resolving R-7. Step 6 deleted the legacy
     * `triggerPredictionsForPrefix` fallback along with the `unified_swipe_pipeline` flag.
     */
    private var cursorSyncDelegate: SuggestionHandler? = null

    /** Wires the unified cursor-sync delegate (see [cursorSyncDelegate]); set by ManagerInitializer. */
    fun setCursorSyncDelegate(handler: SuggestionHandler?) {
        cursorSyncDelegate = handler
    }

    // Swipe ML data collection
    private var currentSwipeData: SwipeMLData? = null

    // v1.32.926: Track if shift was active when current swipe started (for capitalize first letter)
    private var wasShiftActiveAtSwipeStart: Boolean = false
    // v1.33.8: Track if shift was LOCKED (caps lock) when swipe started (for ALL CAPS output)
    private var wasShiftLockedAtSwipeStart: Boolean = false

    /**
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
    }

    /**
     * Updates suggestion bar reference.
     * Called when suggestion bar is created in onStartInputView.
     *
     * @param suggestionBar Suggestion bar instance
     */
    fun setSuggestionBar(suggestionBar: SuggestionBar?) {
        this.suggestionBar = suggestionBar
    }

    // ==================== v1.2.6: Cursor-Aware Prediction ====================

    /**
     * Called when cursor position changes (tap, arrow keys, cut/paste).
     * Debounces rapid cursor movements (e.g., during drag selection) and
     * triggers synchronization of prediction context with actual text.
     *
     * @param newPosition New cursor position (for logging)
     * @param ic InputConnection to read text from
     * @param language Primary language code (for CJK detection)
     * @param editorInfo Editor info for input type checks
     */
    fun onCursorMoved(
        newPosition: Int,
        ic: InputConnection?,
        language: String = "en",
        editorInfo: EditorInfo? = null
    ) {
        // SAS-1: cursor movement invalidates the pending auto-space swallow unless
        // it reports exactly the stamped position (the auto-space commit's own
        // onUpdateSelection callback). Synchronous — must run before any debounce.
        contextTracker.onCursorPositionChanged(newPosition)

        // Cancel any pending sync
        pendingSyncRunnable?.let { syncHandler.removeCallbacks(it) }

        // Schedule new sync with debounce delay
        pendingSyncRunnable = Runnable {
            contextTracker.synchronizeWithCursor(ic, language, editorInfo)

            // Trigger predictions for the synced word
            // v1.2.7 FIX: Use PREFIX ONLY for prediction lookup, not fullWord
            // When cursor is at "per|fect", we want "per" matches (person, perhaps), not "perfect" matches
            // The suffix is only used for deletion when a prediction is selected
            val prefix = contextTracker.getCurrentWord()
            val suffix = contextTracker.getCurrentWordSuffix()

            if (prefix.isNotEmpty()) {
                // WP9 R-1 steps 5+6: route the prediction+post phase to SuggestionHandler's single
                // guarded pipeline (contraction injection, exact-add, I-word cap,
                // capitalization-from-prefix, `specialPromptActive` prompt guard) via the
                // already-synced contextTracker.currentWord — structurally resolving R-7. The
                // bookkeeping above (onCursorPositionChanged, debounce, synchronizeWithCursor with
                // the caller's language) stays here. Step 6 deleted the legacy IC-only pipeline;
                // an unwired delegate means a misconfigured harness, not a fallback.
                cursorSyncDelegate?.handleCursorSyncPrediction()
                    ?: android.util.Log.e(TAG, "cursorSyncDelegate not wired — cursor-sync predictions dropped")
            } else {
                // v1.2.6 FIX: Don't clear suggestions if showing special prompts or swipe corrections
                // After autocorrect/swipe, cursor moves to after space (prefix empty), but we want
                // to keep showing suggestions for undo/correction/add-to-dictionary
                val hasAutocorrectUndo = contextTracker.getLastAutocorrectOriginalWord() != null
                val hasSwipeCorrections = contextTracker.getLastCommitSource() == PredictionSource.NEURAL_SWIPE

                if (!hasAutocorrectUndo && !hasSwipeCorrections) {
                    // Next-word call-site 4 (audit §4.4): cursor parked with no
                    // partial word — route through SuggestionHandler so the
                    // opt-in next-word feature can surface context-only
                    // candidates. With the feature (or master learning gate)
                    // off, the delegate clears the bar exactly as before.
                    // L5 (resolved): the InputConnection rides along so the
                    // delegate can predict from the text before the parked
                    // cursor instead of session-typed context.
                    cursorSyncDelegate?.handleCursorParkPrediction(editorInfo, ic)
                        ?: suggestionBar?.clearSuggestions()
                } else {
                    debugLogger?.invoke("🔄 Preserving suggestions (autocorrect=$hasAutocorrectUndo, swipe=$hasSwipeCorrections)")
                }
            }

            debugLogger?.invoke("🎯 Cursor sync: pos=$newPosition, prefix='$prefix', suffix='$suffix'")
        }
        syncHandler.postDelayed(pendingSyncRunnable!!, CURSOR_SYNC_DEBOUNCE_MS)
    }

    /**
     * Cancels any pending cursor sync.
     * Call when input view is finishing or resetting.
     */
    fun cancelPendingCursorSync() {
        pendingSyncRunnable?.let { syncHandler.removeCallbacks(it) }
        pendingSyncRunnable = null
    }

    /**
     * Releases coordinator resources: cancels any pending cursor sync and stops the
     * geometric/CTC adapters' background threads (if they were ever created). (Step 6: IC no
     * longer owns a prediction executor — the single suggestion executor lives on
     * SuggestionHandler and is shut down there; step 8 adds the geometric decode thread and
     * G5 the CTC decode thread, owned here.)
     */
    fun shutdown() {
        cancelPendingCursorSync()
        geometricAdapter?.shutdown()
        ctcAdapter?.shutdown()
    }

    /**
     * Resets swipe data tracking.
     * Called when starting new input or switching apps.
     */
    fun resetSwipeData() {
        currentSwipeData = null
    }

    /**
     * Gets current swipe ML data for storage.
     * @return Current swipe data or null if no swipe in progress
     */
    fun getCurrentSwipeData(): SwipeMLData? = currentSwipeData

    /**
     * Handle prediction results from async swipe typing prediction — thin delegation to
     * [SuggestionHandler.handleSwipePredictionResults], THE single swipe-results pipeline
     * (bar presentation + possessives + password guard + the unified commit engine).
     *
     * WP9 step 3 (2026-07-20): the shift/caps-lock-at-swipe-start state is CARRIED by the swipe
     * request (threaded through [handleSwipeTyping] → AsyncPredictionHandler → here). The carried
     * [shiftActive] / [shiftLocked] default to this instance's fields so callers that don't thread
     * the state (none in production — the async callback always passes it) stay behavior-identical.
     * WP9 step 6: the legacy IC-only presentation/commit path and the `unified_swipe_pipeline`
     * flag were deleted; the delegate is mandatory (ManagerInitializer wires it) and an unwired
     * delegate clears the bar and logs rather than running a divergent fallback.
     *
     * @param shiftActive True if shift was latched (single tap) when the swipe started.
     * @param shiftLocked True if shift was LOCKED (caps lock) when the swipe started.
     * @param origin the [SuggestionOrigin] of the engine that ACTUALLY decoded this swipe
     *   (audit M2 — each dispatch path passes its own engine, so a geometric decode under
     *   ctc/hybrid mode is tagged GEOMETRIC, and M1's non-en neural fallback NEURAL_BEAM).
     *   Null falls back to the old mode-derived approximation
     *   ([SuggestionOrigin.forSwipeEngineMode]) for callers predating the threading.
     */
    fun handlePredictionResults(
        predictions: List<String>?,
        scores: List<Int>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        shiftActive: Boolean = wasShiftActiveAtSwipeStart,
        shiftLocked: Boolean = wasShiftLockedAtSwipeStart,
        origin: SuggestionOrigin? = null
    ) {
        // Keep the fields in sync with the request-carried state (single source of truth for the
        // default-param seam used by tests and the oracle).
        wasShiftActiveAtSwipeStart = shiftActive
        wasShiftLockedAtSwipeStart = shiftLocked

        val delegate = swipeResultDelegate
        if (delegate == null) {
            android.util.Log.e(TAG, "swipeResultDelegate not wired — swipe predictions dropped")
            suggestionBar?.clearSuggestions()
            return
        }
        delegate.handleSwipePredictionResults(
            predictions, scores, ic, editorInfo, resources, shiftActive, shiftLocked, this, origin
        )
    }

    // ── Thin view-side helpers for the unified commit path (SuggestionHandler-owned since
    // step 6; IC keeps the Keyboard2View reference, so these stay here) ─────────────────────

    /** Haptic feedback for a completed swipe auto-insert (was IC.autoInsertTopSuggestion's). */
    internal fun triggerSwipeCompleteHaptic() {
        keyboardView.triggerHaptic(HapticEvent.SWIPE_COMPLETE)
    }

    /**
     * Clears the latched (single-tap) shift indicator after a shift+swipe commit — the word is
     * already capitalized, so the NEXT word should be lowercase. Caps lock is never cleared here.
     * Posted to the view's thread (was IC.onSuggestionSelected's post-commit clearing).
     */
    internal fun clearLatchedShiftAfterSwipe() {
        keyboardView.post {
            keyboardView.clearLatchedModifiers()
        }
    }

    /** Current keyboard view height in px — for swipe ML data capture (MLDataCollector). */
    internal fun keyboardHeightPx(): Int = keyboardView.height

    /**
     * Whether the input captured at swipe time is still the field that would receive text now,
     * used to decide if a deferred cold-start swipe replay may safely commit.
     *
     * When a [CurrentInputProvider] is wired (production), compares the captured connection AND
     * editor info against the live ones by reference identity — an input-field switch replaces
     * both, so a mismatch means the target moved. Without a provider (e.g. some unit contexts),
     * falls back to a best-effort check that the captured connection is at least non-null.
     */
    private fun isReplayInputStillCurrent(
        capturedIc: InputConnection?,
        capturedEditor: EditorInfo?
    ): Boolean {
        val provider = currentInputProvider
        val live = provider?.current()
        return isReplayInputStillCurrent(
            capturedIc = capturedIc,
            capturedEditor = capturedEditor,
            liveIc = live?.first,
            liveEditor = live?.second,
            hasLiveInput = provider != null
        )
    }

    /**
     * Handle swipe typing gesture completion.
     * @param wasShiftActive v1.32.926: True if shift was latched (single tap) - capitalize first letter
     * @param wasShiftLocked v1.33.8: True if shift was locked (caps lock) - uppercase entire word
     */
    fun handleSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        wasShiftActive: Boolean = false,  // v1.32.926: Track if shift was latched when swipe started
        wasShiftLocked: Boolean = false   // v1.33.8: Track if shift was LOCKED (caps lock) when swipe started
    ) {
        // v1.32.926: Store shift state for capitalize first letter in onSuggestionSelected
        wasShiftActiveAtSwipeStart = wasShiftActive
        // v1.33.8: Store caps lock state for ALL CAPS transformation in onSuggestionSelected
        wasShiftLockedAtSwipeStart = wasShiftLocked

        // Clear auto-inserted word tracking when new swipe starts
        contextTracker.clearLastAutoInsertedWord()

        if (!config.swipe_typing_enabled) return
        // WP9 R-1 step 7: mode+layout-routed engine selection (swipe_engine_mode pref —
        // neural = QWERTY-only swipe as before; hybrid = neural on QWERTY + geometric
        // elsewhere; geometric = SHARK2 everywhere; ctc (G5) = CTC trie-beam on QWERTY +
        // geometric elsewhere). One engine owns each swipe end-to-end; all feed the same
        // SuggestionHandler seam downstream.
        when (SwipeEngineRouter.route(
            keyboardView.getKeyboard(), SwipeEngineRouter.Mode.fromPref(config.swipe_engine_mode)
        )) {
            SwipeEngineRouter.Engine.NONE -> return
            SwipeEngineRouter.Engine.GEOMETRIC -> {
                performGeometricSwipeTyping(
                    swipedKeys, swipePath, timestamps, ic, editorInfo, resources,
                    wasShiftActive, wasShiftLocked
                )
                return
            }
            SwipeEngineRouter.Engine.CTC -> {
                performCtcSwipeTyping(
                    swipedKeys, swipePath, timestamps, ic, editorInfo, resources,
                    wasShiftActive, wasShiftLocked
                )
                return
            }
            SwipeEngineRouter.Engine.NEURAL -> Unit // falls through to the neural flow below
        }

        dispatchNeuralSwipeTyping(
            swipedKeys, swipePath, timestamps, ic, editorInfo, resources,
            wasShiftActive, wasShiftLocked
        )
    }

    /**
     * THE neural swipe flow ([SwipeEngineRouter.Engine.NEURAL]'s dispatch), shared by the
     * NEURAL routing branch and [performCtcSwipeTyping]'s non-English fallthrough (audit M1).
     *
     * Ensures the neural engine is loaded before running the swipe. If it is not yet ready,
     * initializes it OFF the main thread and resumes via [performSwipeTyping] once the attempt
     * completes — never busy-waits on the UI thread. The replay path calls performSwipeTyping
     * directly, so there is no re-enqueue loop.
     */
    private fun dispatchNeuralSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        wasShiftActive: Boolean,
        wasShiftLocked: Boolean
    ) {
        if (config.swipe_typing_enabled && predictionCoordinator.getNeuralEngine() == null) {
            predictionCoordinator.runWhenNeuralEngineReady { _ ->
                // The replay fires after init settles (possibly seconds later). Guard against the
                // input field having changed in the meantime — committing this swipe's word into a
                // now-different field (or a closed connection) would corrupt unrelated text.
                if (isReplayInputStillCurrent(ic, editorInfo)) {
                    performSwipeTyping(
                        swipedKeys, swipePath, timestamps, ic, editorInfo, resources,
                        wasShiftActive, wasShiftLocked
                    )
                } else {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d(TAG, "Dropping cold-start swipe replay: input field changed since swipe")
                    }
                }
            }
            return
        }

        performSwipeTyping(
            swipedKeys, swipePath, timestamps, ic, editorInfo, resources,
            wasShiftActive, wasShiftLocked
        )
    }

    /**
     * Marks swipe state + prepares the ML trace capture shared by BOTH engines (WP9 step 8):
     * sets `wasLastInputSwipe`, snapshots the raw path/timestamps into [currentSwipeData], and
     * records the gesture tracker's key sequence (ML data only — each engine recalculates keys
     * from the raw path independently).
     *
     * [engine] tags the capture with the decoder that produced its suggestions
     * ([SwipeMLData.ENGINE_NEURAL] / [SwipeMLData.ENGINE_GEOMETRIC]) and the layout name is
     * read from the live keyboard. Audit n-2 (2026-08-11): without those two fields a
     * ЙЦУКЕН/Dvorak geometric trace is indistinguishable from a QWERTY neural one in an ML
     * export, so a future neural-training corpus built from exports would silently mix
     * incompatible key geometries.
     */
    private fun beginSwipeCapture(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        resources: Resources,
        engine: String
    ) {
        // Mark that last input was a swipe for ML data collection
        contextTracker.setWasLastInputSwipe(true)

        // Prepare ML data (will be saved if user selects a prediction)
        val metrics = resources.displayMetrics
        currentSwipeData = SwipeMLData(
            "", "user_selection",
            metrics.widthPixels, metrics.heightPixels,
            keyboardView.height,
            layoutName = keyboardView.getKeyboard()?.name ?: SwipeMLData.UNKNOWN,
            engine = engine
        )

        // Add swipe path points with timestamps
        if (swipePath != null && timestamps != null && swipePath.size == timestamps.size) {
            swipePath.indices.forEach { i ->
                val point = swipePath[i]
                val timestamp = timestamps[i]
                currentSwipeData?.addRawPoint(point.x, point.y, timestamp)
            }
        }

        // Build key sequence from swiped keys for ML data ONLY
        swipedKeys.forEach { key ->
            key.keys[0]?.let { kv ->
                if (kv.getKind() == KeyValue.Kind.Char) {
                    currentSwipeData?.addRegisteredKey(kv.getChar().toString())
                }
            }
        }
    }

    // ── WP9 R-1 steps 7-8: geometric engine path (non-QWERTY layouts) ──────────────────

    private var geometricAdapter: GeometricEngineAdapter? = null

    private fun geometricAdapterOrCreate(): GeometricEngineAdapter =
        geometricAdapter ?: GeometricEngineAdapter(context).also { geometricAdapter = it }

    /**
     * Decodes a non-QWERTY swipe with the geometric engine (off the main thread) and feeds
     * the result into the SAME pipeline as neural results — [handlePredictionResults] →
     * [SuggestionHandler.handleSwipePredictionResults] — so the geometric path inherits the
     * password guard, possessive augmentation, shift/caps transform, and THE commit engine.
     * An empty decode (no dictionary for the language, dead layout, degenerate trace) flows
     * through as an empty prediction list → the pipeline clears the bar.
     */
    private fun performGeometricSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        wasShiftActive: Boolean,
        wasShiftLocked: Boolean
    ) {
        if (swipePath.isNullOrEmpty() || timestamps == null) return
        val keyboard = keyboardView.getKeyboard() ?: return
        val params = keyboardView.geometryParams() ?: return
        val frameW = keyboardView.width.toFloat()
        val frameH = keyboardView.height.toFloat()
        if (frameW <= 0f || frameH <= 0f) return

        // Same swipe-state + ML-trace capture as the neural path (D5 collection works
        // identically for geometric selections), tagged with the geometric engine + layout
        // so ML exports stay separable from QWERTY/neural traces (audit n-2).
        beginSwipeCapture(swipedKeys, swipePath, timestamps, resources, SwipeMLData.ENGINE_GEOMETRIC)

        val language = predictionCoordinator.getDictionaryManager()?.getCurrentLanguage()
            ?: config.primary_language
        geometricAdapterOrCreate().decodeAsync(
            keyboard, params, frameW, frameH, swipePath, timestamps, language
        ) { result ->
            // The decode callback replays the InputConnection/EditorInfo captured at swipe
            // time. A decode can land after the field changed (cold Tier-A build takes
            // 150-400 ms, and a same-field restart or an app switch replaces both handles),
            // so apply the SAME staleness guard the neural cold-start replay uses — otherwise
            // this word would be committed into an unrelated field (audit M-2).
            if (isReplayInputStillCurrent(ic, editorInfo)) {
                handlePredictionResults(
                    result.words, result.scores, ic, editorInfo, resources,
                    wasShiftActive, wasShiftLocked,
                    // M2: tag with the engine that ACTUALLY decoded (a hybrid/ctc-mode
                    // non-QWERTY swipe is geometric, not the mode's namesake engine).
                    SuggestionOrigin.GEOMETRIC
                )
            } else if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d(TAG, "Dropping geometric decode: input field changed since swipe")
            }
        }
    }

    // ── G5: CTC engine path (QWERTY-Latin layouts under ctc mode) ───────────────────────

    private var ctcAdapter: CtcEngineAdapter? = null

    private fun ctcAdapterOrCreate(): CtcEngineAdapter =
        ctcAdapter ?: CtcEngineAdapter(context).also { ctcAdapter = it }

    /**
     * Decodes a swipe with the CTC engine (off the main thread) and feeds the result
     * into the SAME pipeline as neural/geometric results — [handlePredictionResults]
     * → [SuggestionHandler.handleSwipePredictionResults] — inheriting the password
     * guard, possessive augmentation, shift/caps transform, and THE commit engine.
     * An empty decode (no model, degenerate trace) flows through as an empty
     * prediction list → the pipeline clears the bar.
     *
     * Audit M1 — the v1 CTC model/lexicon is English-only, so the active language is
     * read BEFORE dispatch: a non-English swipe falls through to
     * [dispatchNeuralSwipeTyping], the SAME flow [SwipeEngineRouter.Engine.NEURAL]
     * takes. Net ctc-mode semantics: CTC(en QWERTY) / neural(non-en QWERTY) /
     * geometric(non-QWERTY) — never less coverage than hybrid (which would have
     * served the non-en QWERTY swipe neurally). The adapter keeps its own en-gate
     * as defense-in-depth.
     */
    private fun performCtcSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        wasShiftActive: Boolean,
        wasShiftLocked: Boolean
    ) {
        val language = predictionCoordinator.getDictionaryManager()?.getCurrentLanguage()
            ?: config.primary_language
        if (!language.equals(CtcEngineAdapter.LANGUAGE, ignoreCase = true)) {
            // M1: non-English on a QWERTY layout under ctc mode → the neural flow
            // (performSwipeTyping tags its own ENGINE_NEURAL ML capture).
            dispatchNeuralSwipeTyping(
                swipedKeys, swipePath, timestamps, ic, editorInfo, resources,
                wasShiftActive, wasShiftLocked
            )
            return
        }
        if (swipePath.isNullOrEmpty() || timestamps == null) return
        val keyboard = keyboardView.getKeyboard() ?: return
        val params = keyboardView.geometryParams() ?: return
        val frameW = keyboardView.width.toFloat()
        val frameH = keyboardView.height.toFloat()
        if (frameW <= 0f || frameH <= 0f) return

        // Same swipe-state + ML-trace capture as the neural/geometric paths, tagged with
        // the CTC engine + layout so ML exports stay separable per decoder (audit n-2).
        beginSwipeCapture(swipedKeys, swipePath, timestamps, resources, SwipeMLData.ENGINE_CTC)

        ctcAdapterOrCreate().decodeAsync(
            keyboard, params, frameW, frameH, swipePath, timestamps, language
        ) { result ->
            // The decode callback replays the InputConnection/EditorInfo captured at swipe
            // time. A decode can land after the field changed (cold path builds the ONNX
            // session + 98k-word trie, and a same-field restart or an app switch replaces
            // both handles), so apply the SAME staleness guard as the geometric path
            // (audit M-2) — otherwise this word could commit into an unrelated field.
            if (isReplayInputStillCurrent(ic, editorInfo)) {
                handlePredictionResults(
                    result.words, result.scores, ic, editorInfo, resources,
                    wasShiftActive, wasShiftLocked,
                    // M2: this dispatch IS the CTC adapter, so the tag is exact.
                    SuggestionOrigin.CTC
                )
            } else if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d(TAG, "Dropping CTC decode: input field changed since swipe")
            }
        }
    }

    /**
     * Proactive background warm-up of the geometric/CTC engine (WP9 step 8 duty 4): called on
     * layout switches (CleverKeysService.onStartInputView) so the first swipe avoids the
     * synchronous cold build (geometric: 150-400 ms Tier-A index; ctc: ONNX session +
     * 98k-word trie). Posted to the view so the frame dimensions are the post-layout ones;
     * a no-op unless the router would pick GEOMETRIC or CTC.
     */
    fun prewarmGeometricEngine() {
        if (!config.swipe_typing_enabled) return
        val mode = SwipeEngineRouter.Mode.fromPref(config.swipe_engine_mode)
        if (mode == SwipeEngineRouter.Mode.NEURAL) return
        keyboardView.post {
            val keyboard = keyboardView.getKeyboard() ?: return@post
            val params = keyboardView.geometryParams() ?: return@post
            val frameW = keyboardView.width.toFloat()
            val frameH = keyboardView.height.toFloat()
            if (frameW <= 0f || frameH <= 0f) return@post
            val language = predictionCoordinator.getDictionaryManager()?.getCurrentLanguage()
                ?: config.primary_language
            when (SwipeEngineRouter.route(keyboard, mode)) {
                SwipeEngineRouter.Engine.GEOMETRIC ->
                    geometricAdapterOrCreate().warmUpAsync(keyboard, params, frameW, frameH, language)
                // G5: front-load the ONNX session + 98k-word trie build (~100-300 ms
                // background) so the first ctc swipe decodes in warm-path time.
                SwipeEngineRouter.Engine.CTC ->
                    ctcAdapterOrCreate().warmUpAsync(keyboard, params, frameW, frameH, language)
                else -> return@post
            }
        }
    }

    /**
     * Runs the actual swipe prediction once the neural engine is (or is confirmed not) ready.
     *
     * Split out of [handleSwipeTyping] so the engine-readiness wait can be non-blocking: the
     * dispatch in [handleSwipeTyping] resumes here on the main thread. Shift state is passed
     * as params rather than re-read from fields (already captured in [handleSwipeTyping]).
     */
    private fun performSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>?,
        timestamps: List<Long>?,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        resources: Resources,
        wasShiftActive: Boolean,
        wasShiftLocked: Boolean
    ) {
        if (predictionCoordinator.getNeuralEngine() == null) {
            // Fallback to word predictor if engine not initialized
            if (predictionCoordinator.getWordPredictor() == null) return

            // Ensure prediction engines are initialized (lazy initialization)
            predictionCoordinator.ensureInitialized()
        }

        beginSwipeCapture(swipedKeys, swipePath, timestamps, resources, SwipeMLData.ENGINE_NEURAL)

        if (!swipePath.isNullOrEmpty()) {
            // Create SwipeInput exactly like SwipeCalibrationActivity (empty swipedKeys)
            // This ensures neural system handles key detection internally for consistency
            // The neural network will recalculate keys from the full path without filtering
            val swipeInput = SwipeInput(
                swipePath,
                timestamps ?: emptyList(),
                emptyList() // Empty - neural recalculates keys
            )

            // UNIFIED PREDICTION STRATEGY: All predictions wait for gesture completion
            // This matches SwipeCalibrationActivity behavior and eliminates premature predictions

            // Cancel any pending predictions first
            predictionCoordinator.getAsyncPredictionHandler()?.cancelPendingPredictions()

            // Request predictions asynchronously - always done on gesture completion
            // which matches the calibration activity's ACTION_UP behavior
            predictionCoordinator.getAsyncPredictionHandler()?.let { handler ->
                handler.requestPredictions(swipeInput, object : AsyncPredictionHandler.PredictionCallback {
                    override fun onPredictionsReady(predictions: List<String>, scores: List<Int>) {
                        // Process predictions on UI thread. WP9 step 3: thread this swipe request's
                        // shift-at-start state through to handlePredictionResults (the callback
                        // closure carries the captured wasShiftActive/wasShiftLocked) so the casing
                        // transform reads the request-carried state, not shared fields.
                        handlePredictionResults(
                            predictions, scores, ic, editorInfo, resources,
                            wasShiftActive, wasShiftLocked,
                            // M2: the neural flow always decodes with the transformer —
                            // including M1's ctc-mode non-English fallthrough.
                            SuggestionOrigin.NEURAL_BEAM
                        )
                    }

                    override fun onPredictionError(error: String) {
                        // Clear suggestions on error
                        suggestionBar?.clearSuggestions()
                    }
                })
            } ?: run {
                // Fallback to synchronous prediction if async handler not available
                // Ensure engine is available before calling predict
                predictionCoordinator.getNeuralEngine()?.let { engine ->
                    val startTime = System.currentTimeMillis()
                    val result = engine.predict(swipeInput)
                    val predictionTime = System.currentTimeMillis() - startTime
                    val predictions = result.words

                    // Show suggestions in the bar
                    if (predictions.isNotEmpty()) {
                        suggestionBar?.let { bar ->
                            bar.setShowDebugScores(config.swipe_show_debug_scores)
                            bar.setSuggestionsWithScores(predictions, result.scores)
                        }
                    }
                }
            }
        }
    }
}
