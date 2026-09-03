package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * ARC-072 slice 3 — the keyboard's hand-written composition root.
 *
 * This class absorbed the six `*Initializer` factory files (Manager / Prediction /
 * Propagator / Receiver / SubtypeLayout / SuggestionBar, ~841 lines): each was a
 * `create()` + `initialize()` that newed up objects and returned a result holder —
 * exactly what a composition root does, spread over six files. The construction logic
 * lives here now, in one place, in dependency order. No DI framework: `by lazy` gives
 * the ordering + single-instance semantics a hand-written graph needs, and it stays
 * debuggable/steppable.
 *
 * Shape of the graph:
 *  - **`by lazy` vals** for stable one-shot singletons ([managers], [suggestionBridge]).
 *    `CleverKeysService.onCreate` reads them in its (unchanged) wiring order, so
 *    construction order and every side effect are identical to the retired Initializers.
 *  - **explicit functions** for the phases with temporal coupling to service-mutable
 *    state ([wireSwipeTypingComponents], [buildConfigPropagator], [refreshSubtypeAndLayout],
 *    [createReceiverIfNeeded]) — they run more than once and/or read `LayoutManager` /
 *    `SubtypeManager` references the service owns and replaces over its lifetime, so a
 *    lazy would freeze the wrong instant.
 *
 * The Bridges (KeyEventReceiverBridge, LayoutBridge, SuggestionBridge) are KEPT — they
 * are genuine delegation seams the service forwards through, not wiring — and co-located
 * in this `wiring/` directory.
 *
 * Dir-only grouping (ARC-048 R4 convention): the file keeps `package tribixbite.cleverkeys`
 * so nothing outside the tree layout changes. Pinned by [WiringCompositionRootDriftTest].
 */
class KeyboardComponentGraph(
    private val service: CleverKeysService,
    private val config: Config,
    private val keyboardView: Keyboard2View,
    private val keyEventHandler: KeyEventHandler,
    private val handler: Handler,
    private val receiverBridge: KeyEventReceiverBridge,
) {

    /**
     * The manager cluster (formerly `ManagerInitializer.InitializationResult`).
     *
     * Cross-dependencies:
     *  - InputCoordinator requires contextTracker, predictionCoordinator, keyboardView
     *  - SuggestionHandler requires contextTracker, predictionCoordinator,
     *    contractionManager, keyEventHandler
     *  - KeyboardDimensionsHelper requires keyboardView
     *
     * SuggestionBar references are set later (onStartInputView) via SuggestionBarPropagator.
     */
    data class Managers(
        val contractionManager: ContractionManager,
        val clipboardManager: ClipboardManager,
        val contextTracker: PredictionContextTracker,
        val predictionCoordinator: PredictionCoordinator,
        val inputCoordinator: InputCoordinator,
        val suggestionHandler: SuggestionHandler,
        val keyboardDimensionsHelper: KeyboardDimensionsHelper,
        val mlDataCollector: MLDataCollector,
    )

    /**
     * Builds the manager cluster in dependency order (formerly `ManagerInitializer.initialize()`,
     * v1.32.388). One-shot: the first read constructs everything below, later reads reuse it.
     *
     * Order (unchanged): ContractionManager → ClipboardManager → PredictionContextTracker →
     * PredictionCoordinator (+`initialize()`, which loads the dictionary) → InputCoordinator →
     * SuggestionHandler (+ delegate wiring) → KeyboardDimensionsHelper → MLDataCollector.
     */
    val managers: Managers by lazy {
        MemoryProbe.reset()
        MemoryProbe.mark("init.enter", settle = true)

        // Contraction mappings for apostrophe insertion, scoped to the languages the user
        // actually selected (v1.32.341; language-scoped 2026-08-19).
        //
        // This used to load the English base, then the primary language, then English AGAIN
        // unconditionally — so a German user got "I'm" when typing `im` (rank 16) and a French
        // user got "don't" for `dont` (rank 104), because the base loaded first and both
        // loaders are earlier-wins. loadTypingMappings encodes the precedence: primary,
        // then secondary, then the English base ONLY if English is one of the two.
        val contractionManager = ContractionManager(service)
        val prefsForLang = DirectBootAwarePreferences.get_shared_preferences(service)
        val secondaryLang = prefsForLang
            .getString("pref_secondary_language", "none")
            ?.takeIf { prefsForLang.getBoolean("pref_enable_multilang", false) }
        contractionManager.loadTypingMappings(config.primary_language, secondaryLang)
        MemoryProbe.mark("init.contractionManager", settle = true) {
            "known=${contractionManager.getTotalKnownCount()}"
        }

        // Clipboard manager (v1.32.349)
        val clipboardManager = ClipboardManager(service, config)

        // Prediction context tracker (v1.32.342)
        val contextTracker = PredictionContextTracker()
        MemoryProbe.mark("init.clipboard+contextTracker", settle = true)

        // Prediction coordinator (v1.32.346)
        val predictionCoordinator = PredictionCoordinator(service, config)
        // v1.1.90: CRITICAL - Must call initialize() to load the dictionary
        predictionCoordinator.initialize()
        MemoryProbe.mark("init.predictionCoordinator", settle = true)

        // Input coordinator (v1.32.350). SuggestionBar is set later in onStartInputView.
        val inputCoordinator = InputCoordinator(
            service,
            config,
            contextTracker,
            predictionCoordinator,
            null, // suggestionBar created later
            keyboardView
        )

        // Suggestion handler (v1.32.361)
        val suggestionHandler = SuggestionHandler(
            service,
            config,
            contextTracker,
            predictionCoordinator,
            contractionManager,
            keyEventHandler
        )

        // WP9 R-1 steps 4-6: wire the unified delegates now that both exist. MANDATORY since
        // step 6 — InputCoordinator has no fallback pipelines anymore. SuggestionHandler owns
        // the whole swipe-results flow (possessives, password guard, THE single commit engine)
        // and the cursor-sync prediction+post phase (guarded pipeline, R-7). Bookkeeping
        // (debounce, cursor sync, swipe gesture/ML capture) stays in InputCoordinator.
        inputCoordinator.setSwipeResultDelegate(suggestionHandler)
        inputCoordinator.setCursorSyncDelegate(suggestionHandler)

        // Keyboard-dimensions helper (v1.32.362)
        val keyboardDimensionsHelper = KeyboardDimensionsHelper(service, config)
        keyboardDimensionsHelper.setKeyboardView(keyboardView)

        // ML data collector (v1.32.370)
        val mlDataCollector = MLDataCollector(service)
        MemoryProbe.mark("init.done", settle = true)

        Managers(
            contractionManager,
            clipboardManager,
            contextTracker,
            predictionCoordinator,
            inputCoordinator,
            suggestionHandler,
            keyboardDimensionsHelper,
            mlDataCollector
        )
    }

    // Convenience reads — each forces the [managers] cluster on first access.
    val contractionManager: ContractionManager get() = managers.contractionManager
    val clipboardManager: ClipboardManager get() = managers.clipboardManager
    val contextTracker: PredictionContextTracker get() = managers.contextTracker
    val predictionCoordinator: PredictionCoordinator get() = managers.predictionCoordinator
    val inputCoordinator: InputCoordinator get() = managers.inputCoordinator
    val suggestionHandler: SuggestionHandler get() = managers.suggestionHandler
    val keyboardDimensionsHelper: KeyboardDimensionsHelper get() = managers.keyboardDimensionsHelper
    val mlDataCollector: MLDataCollector get() = managers.mlDataCollector

    /**
     * Suggestion/prediction delegation bridge (v1.32.406). Kept as a Bridge — the service
     * forwards suggestion callbacks through it; only its construction moved here.
     */
    val suggestionBridge: SuggestionBridge by lazy {
        SuggestionBridge.create(
            service,
            suggestionHandler,
            mlDataCollector,
            inputCoordinator,
            contextTracker,
            predictionCoordinator,
            keyboardView
        )
    }

    /**
     * Wires the view's service handle and (when predictions/swipe are enabled) loads the
     * prediction models synchronously. Formerly `PredictionInitializer.initializeIfEnabled()`
     * (v1.32.405/v1.32.529).
     *
     * The FIRST `setSwipeTypingComponents` call runs UNCONDITIONALLY — outside every config
     * check, before any model loading, and with no engine-readiness gate.
     *
     * Despite the name, `setSwipeTypingComponents` is what gives `Keyboard2View` its
     * `_keyboard2` reference, and that handle is load-bearing far beyond word swipes:
     * custom short swipes (`onCustomShortSwipe` returns early with "no service reference"
     * without it), suggestion-bar messages, the selection menu, and the primary/secondary
     * language toggles all go through it. **No subkey behaviour may depend on prediction
     * or swipe-typing settings** — a user with both switched off still gets their custom
     * subkey gestures, which is the whole point of the Short Swipe Customization feature.
     *
     * Three wrong gates lived here, each narrower than the last was assumed to be:
     *  1. `isSwipeTypingAvailable()`, then literally "the transformer engine is built" —
     *     the wrong dependency twice over, since this call passes the word predictor and
     *     the service handle and touched that engine not at all. It only ever worked
     *     because something always built the engine eagerly.
     *  2. `swipe_typing_enabled`, which still stranded users who keep swipe typing off.
     *  3. the enclosing `word_prediction_enabled || swipe_typing_enabled`, which stranded
     *     users who have BOTH off.
     * When that build became conditional on routing, gate 1 went permanently false in CTC
     * mode, the call stopped happening, and `_keyboard2` stayed null — killing word swipes
     * and user-created subkey short swipes together, while layout-defined subkeys kept
     * working because they never take this path. The gate is gone; keep it gone.
     *
     * `setSwipeTypingComponents` is null-tolerant by signature, so passing a not-yet-built
     * predictor is safe: the predictor is re-read per gesture, the service handle is not.
     */
    fun wireSwipeTypingComponents() {
        keyboardView.setSwipeTypingComponents(
            predictionCoordinator.getWordPredictor(),
            service
        )

        if (config.word_prediction_enabled || config.swipe_typing_enabled) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("KeyboardComponentGraph", "Starting model initialization (synchronous)...")
            }
            val startTime = System.currentTimeMillis()

            // OPTIMIZATION v1.32.529: load models synchronously to guarantee the first swipe
            // works (236ms load, instant after — the singleton persists for the app lifecycle).
            predictionCoordinator.initialize()

            val loadTime = System.currentTimeMillis() - startTime
            android.util.Log.i("KeyboardComponentGraph", "✅ Models loaded in ${loadTime}ms (ready for swipes)")

            // Re-push the predictor now that it exists; the handle above was wired before the
            // synchronous load, so on a cold start the first call passed null for it.
            keyboardView.setSwipeTypingComponents(
                predictionCoordinator.getWordPredictor(),
                service
            )
        }
    }

    /**
     * Creates + registers the DebugModePropagator and builds the ConfigPropagator.
     * Formerly `PropagatorInitializer.initialize()` (v1.32.396).
     *
     * [layoutManager] and [subtypeManager] are parameters (not graph reads) because the
     * service owns and replaces those references over its lifetime; at onCreate time both
     * are still null — same values the retired Initializer captured.
     */
    fun buildConfigPropagator(
        debugLoggerImpl: SuggestionHandler.DebugLogger,
        debugLoggingManager: DebugLoggingManager,
        layoutManager: LayoutManager?,
        subtypeManager: SubtypeManager?,
    ): ConfigPropagator {
        // Create debug mode propagator and register it with the debug logging manager
        val debugModePropagator = DebugModePropagator.create(
            suggestionHandler,
            keyboardDimensionsHelper,
            predictionCoordinator,
            debugLoggerImpl,
            debugLoggingManager
        )
        debugLoggingManager.registerDebugModeListener(debugModePropagator)

        // Build config propagator with all managers
        return ConfigPropagator.builder()
            .setClipboardManager(clipboardManager)
            .setPredictionCoordinator(predictionCoordinator)
            .setInputCoordinator(inputCoordinator)
            .setSuggestionHandler(suggestionHandler)
            .setKeyboardDimensionsHelper(keyboardDimensionsHelper)
            .setLayoutManager(layoutManager)
            .setKeyboardView(keyboardView)
            .setSubtypeManager(subtypeManager)
            .build()
    }

    /**
     * Result of subtype + layout refresh (formerly `SubtypeLayoutInitializer.InitializationResult`).
     *
     * @property layoutBridge non-null ONLY when the LayoutManager was just created (first call);
     *   callers must not overwrite an existing bridge with null.
     */
    data class SubtypeLayoutResult(
        val subtypeManager: SubtypeManager,
        val layoutManager: LayoutManager?,
        val layoutBridge: LayoutBridge?,
    )

    /**
     * Refreshes the IME subtype and creates/updates the LayoutManager (+LayoutBridge on first
     * call). Formerly `SubtypeLayoutInitializer.refreshSubtypeAndLayout()` (v1.32.409).
     *
     * Not a lazy: it runs on every subtype change and takes the service's CURRENT manager
     * references, updating in place when they already exist.
     */
    fun refreshSubtypeAndLayout(
        existingSubtypeManager: SubtypeManager?,
        existingLayoutManager: LayoutManager?,
        resources: Resources,
        changedTo: android.view.inputmethod.InputMethodSubtype? = null,
    ): SubtypeLayoutResult {
        // Initialize SubtypeManager if needed (lazy initialization)
        val subtypeManager = existingSubtypeManager ?: SubtypeManager(service)

        // Refresh subtype and get default layout. gh #160: when this refresh is driven by
        // onCurrentInputMethodSubtypeChanged the delivered subtype is passed through and wins
        // over the IMM re-derivation (stale/aliased answers) — see SubtypeManager.refreshSubtype.
        val defaultLayout = subtypeManager.refreshSubtype(config, resources, changedTo)
            ?: KeyboardData.load(resources, R.raw.latn_qwerty_us)

        // Update or create LayoutManager
        val layoutManager: LayoutManager?
        val layoutBridge: LayoutBridge?

        if (existingLayoutManager != null && defaultLayout != null) {
            // Update existing LayoutManager with locale layout
            existingLayoutManager.setLocaleTextLayout(defaultLayout)
            layoutManager = existingLayoutManager
            layoutBridge = null // Don't recreate bridge
        } else if (defaultLayout != null) {
            // First call - initialize LayoutManager with default layout, plus its bridge
            layoutManager = LayoutManager(service, config, defaultLayout)
            layoutBridge = LayoutBridge.create(layoutManager, keyboardView)
        } else {
            // defaultLayout is null - return null result
            layoutManager = null
            layoutBridge = null
        }

        return SubtypeLayoutResult(subtypeManager, layoutManager, layoutBridge)
    }

    /**
     * Lazily creates the KeyboardReceiver and registers it on the KeyEventReceiverBridge.
     * Formerly `ReceiverInitializer.initializeIfNeeded()` (v1.32.397).
     *
     * @param existingReceiver the current receiver (returned unchanged when non-null)
     * @return the existing receiver, a newly created one, or null when layoutManager /
     *   subtypeManager are not ready yet (creation deferred to a later onStartInputView)
     */
    fun createReceiverIfNeeded(
        existingReceiver: KeyboardReceiver?,
        layoutManager: LayoutManager?,
        subtypeManager: SubtypeManager?,
    ): KeyboardReceiver? {
        // Return existing receiver if already created
        if (existingReceiver != null) {
            return existingReceiver
        }

        // Cannot create receiver without layoutManager or subtypeManager - defer until initialized
        if (layoutManager == null || subtypeManager == null) {
            return null
        }

        // Create new KeyboardReceiver with all dependencies
        val newReceiver = KeyboardReceiver(
            service,
            service,
            keyboardView,
            layoutManager,
            clipboardManager,
            contextTracker,
            inputCoordinator,
            subtypeManager,
            handler
        )

        // Set receiver on bridge for KeyEventHandler delegation
        receiverBridge.setReceiver(newReceiver)

        return newReceiver
    }
}

/**
 * Suggestion-bar pane construction + pane/strip mode switching (formerly
 * `SuggestionBarInitializer`, v1.32.381). Static view plumbing rather than service wiring,
 * so it stays an object; it lives in this file because its `initialize` is construction —
 * the same composition concern as the graph above. Callers: PredictionViewSetup (build +
 * height), KeyboardReceiver (pane/strip switching).
 *
 * Simplified approach: no ViewFlipper. Just a topPane FrameLayout that contains either the
 * scrollView (suggestion bar) or contentPaneContainer (emoji/clipboard). Views are swapped
 * by removing/adding children.
 *
 * Hierarchy:
 * - inputViewContainer (LinearLayout, VERTICAL)
 *   - topPane (FrameLayout) - contains either scrollView OR contentPaneContainer
 *   - keyboardView (added by caller)
 */
object SuggestionBarPane {

    /**
     * Result of suggestion bar initialization.
     */
    data class InitializationResult(
        val inputViewContainer: LinearLayout,
        val suggestionBar: SuggestionBar,
        val contentPaneContainer: FrameLayout,
        val scrollView: HorizontalScrollView,
        val topPane: FrameLayout,
    )

    /**
     * Initialize suggestion bar and input view container.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        theme: Theme?,
        opacity: Int,
        clipboardPaneHeightPercent: Int
    ): InitializationResult {
        // Root container - NO gravity setting, just stack vertically
        val inputViewContainer = LinearLayout(context)
        inputViewContainer.orientation = LinearLayout.VERTICAL

        // Create suggestion bar with theme
        val suggestionBar = if (theme != null) {
            SuggestionBar(context, theme)
        } else {
            SuggestionBar(context)
        }
        suggestionBar.setOpacity(opacity)

        // Wrap suggestion bar in horizontal scroll view
        val scrollView = HorizontalScrollView(context)
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.isFillViewport = true

        val suggestionParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        suggestionBar.layoutParams = suggestionParams
        scrollView.addView(suggestionBar)

        // Create content pane container (for clipboard/emoji)
        val contentPaneContainer = FrameLayout(context)

        // Calculate suggestion bar height
        val suggestionBarHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            40f,
            context.resources.displayMetrics
        ).toInt()

        // Create topPane - a simple FrameLayout that holds the current view
        val topPane = FrameLayout(context)
        topPane.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            suggestionBarHeight
        )

        // Set scrollView to fill topPane
        scrollView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Start with scrollView (suggestion bar)
        topPane.addView(scrollView)

        // Add topPane to container (keyboard view added by caller)
        inputViewContainer.addView(topPane)

        return InitializationResult(
            inputViewContainer,
            suggestionBar,
            contentPaneContainer,
            scrollView,
            topPane
        )
    }

    /**
     * Calculate content pane height in pixels.
     */
    @JvmStatic
    fun calculateContentPaneHeight(context: Context, heightPercent: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return (screenHeight * heightPercent) / 100
    }

    /**
     * Switch topPane to show content pane with specified height.
     */
    @JvmStatic
    fun switchToContentPaneMode(topPane: FrameLayout, contentPane: FrameLayout, scrollView: HorizontalScrollView, height: Int) {
        android.util.Log.i("SuggestionBarInit", "switchToContentPaneMode: height=$height, contentPane.childCount=${contentPane.childCount}")

        // Remove scrollView if present
        if (scrollView.parent == topPane) {
            topPane.removeView(scrollView)
        }

        // Resize topPane
        val params = topPane.layoutParams
        params.height = height
        topPane.layoutParams = params

        // Set contentPane with explicit height (not MATCH_PARENT)
        contentPane.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            height
        )

        // Add contentPane if not already added
        if (contentPane.parent != topPane) {
            if (contentPane.parent != null) {
                (contentPane.parent as? android.view.ViewGroup)?.removeView(contentPane)
            }
            topPane.addView(contentPane)
        }

        topPane.requestLayout()
        android.util.Log.i("SuggestionBarInit", "switchToContentPaneMode complete: topPane.childCount=${topPane.childCount}")
    }

    /**
     * Switch topPane to show suggestion bar with specified height.
     */
    @JvmStatic
    fun switchToSuggestionBarMode(topPane: FrameLayout, contentPane: FrameLayout, scrollView: HorizontalScrollView, height: Int) {
        android.util.Log.i("SuggestionBarInit", "switchToSuggestionBarMode: height=$height")

        // Remove contentPane if present
        if (contentPane.parent == topPane) {
            topPane.removeView(contentPane)
        }

        // Resize topPane
        val params = topPane.layoutParams
        params.height = height
        topPane.layoutParams = params

        // Set scrollView with explicit height
        scrollView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            height
        )

        // Add scrollView if not already added
        if (scrollView.parent != topPane) {
            if (scrollView.parent != null) {
                (scrollView.parent as? android.view.ViewGroup)?.removeView(scrollView)
            }
            topPane.addView(scrollView)
        }

        topPane.requestLayout()
        android.util.Log.i("SuggestionBarInit", "switchToSuggestionBarMode complete: topPane.childCount=${topPane.childCount}")
    }
}
