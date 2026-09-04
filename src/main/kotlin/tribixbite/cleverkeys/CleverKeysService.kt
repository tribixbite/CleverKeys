package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import tribixbite.cleverkeys.ml.SwipeMLData

/**
 * Main InputMethodService implementation for Unexpected Keyboard.
 *
 * This class serves as the central coordinator for the keyboard, managing:
 * - **View Lifecycle**: Creates and manages keyboard views, content panes (emoji/clipboard), and input views
 * - **Layout Management**: Delegates to [LayoutManager] for keyboard layout loading and switching
 * - **Input Processing**: Coordinates with [KeyEventHandler] for key events and text input
 * - **Prediction System**: Manages swipe typing and tap prediction via [PredictionCoordinator]
 * - **Configuration**: Maintains keyboard settings through [ConfigurationManager]
 * - **Clipboard**: Handles clipboard history via [ClipboardManager]
 * - **Suggestions**: Displays word predictions through [SuggestionBar] and [SuggestionHandler]
 *
 * ## Architecture
 * The class has undergone extensive refactoring (v1.32.341-v1.32.412) to extract concerns into
 * specialized helper classes. This improves maintainability while keeping the InputMethodService
 * lifecycle methods (onCreate, onCreateInputView, onStartInputView, etc.) in this class.
 *
 * ## Prediction Strategy
 * All predictions wait for gesture completion to avoid premature suggestions — a partial trace
 * is not evidence about the intended word, and decoding one produces suggestions the user never
 * asked for.
 *
 * ## Key Lifecycle Methods
 * - [onCreate]: Initialize managers and load configuration
 * - [onCreateInputView]: Create keyboard view and UI components
 * - [onStartInputView]: Configure keyboard for current input field (restarting={true/false})
 * - [onFinishInputView]: Clean up when keyboard is hidden
 * - [onDestroy]: Release resources and unregister listeners
 *
 * @since v1.0 (migrated to Kotlin in v1.32.884)
 */
class CleverKeysService : InputMethodService(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    SuggestionBar.OnSuggestionSelectedListener,
    ConfigChangeListener {

    // Unified prediction strategy: all predictions wait for gesture completion, so a partial
    // trace never produces a suggestion.
    private lateinit var _keyboardView: Keyboard2View
    private lateinit var _keyeventhandler: KeyEventHandler

    // Layout management (v1.32.363: extracted to LayoutManager)
    private var _layoutManager: LayoutManager? = null

    private var _emojiPane: ViewGroup? = null
    private var _contentPaneContainer: FrameLayout? = null // Container for emoji/clipboard panes
    private var _topPane: FrameLayout? = null // TopPane that holds scrollView or contentPaneContainer
    private var _scrollView: android.widget.HorizontalScrollView? = null // ScrollView with suggestion bar
    var actionId: Int = 0 // Action performed by the Action key.
    private lateinit var _handler: Handler

    // Clipboard management (v1.32.349: extracted to ClipboardManager)
    private lateinit var _clipboardManager: ClipboardManager

    // Configuration management (v1.32.345: extracted to ConfigurationManager)
    private lateinit var _configManager: ConfigurationManager
    private var _config: Config? = null // Cached reference from _configManager, updated by ConfigChangeListener

    // Track the theme ID used to create the current keyboard view (for stale view detection)
    private var _currentViewThemeId: Int = 0

    // Prediction coordination (v1.32.346: extracted to PredictionCoordinator)
    private var _predictionCoordinator: PredictionCoordinator? = null

    // UI components (remain in CleverKeysService for view integration)
    private var _suggestionBar: SuggestionBar? = null
    private var _inputViewContainer: LinearLayout? = null

    // Prediction context tracking (v1.32.342: extracted to PredictionContextTracker)
    private lateinit var _contextTracker: PredictionContextTracker

    // Contraction mappings for apostrophe insertion (v1.32.341: extracted to ContractionManager)
    private lateinit var _contractionManager: ContractionManager

    // Input coordination (v1.32.350: extracted to InputCoordinator)
    private lateinit var _inputCoordinator: InputCoordinator

    // Suggestion handling (v1.32.361: extracted to SuggestionHandler)
    private lateinit var _suggestionHandler: SuggestionHandler

    // Keyboard dimensions helper (v1.32.362: extracted from this class)
    private lateinit var _keyboardDimensionsHelper: KeyboardDimensionsHelper

    // Subtype management (v1.32.365: extracted to SubtypeManager)
    private var _subtypeManager: SubtypeManager? = null

    // Event handling (v1.32.368: extracted to KeyboardReceiver)
    private var _receiver: KeyboardReceiver? = null

    // Emoji search management (#41 v5: persistent to survive onStartInputView recreations)
    private var _emojiSearchManager: EmojiSearchManager? = null

    // KeyEventHandler bridge (v1.32.390: extracted to KeyEventReceiverBridge)
    private lateinit var _receiverBridge: KeyEventReceiverBridge

    // Composition root (ARC-072 slice 3: the six *Initializer files collapsed into
    // wiring/KeyboardComponentGraph). Built in onCreate once config exists; the
    // manager fields below are cached reads from it.
    private lateinit var _graph: KeyboardComponentGraph

    // ML data collection (v1.32.370: extracted to MLDataCollector)
    private lateinit var _mlDataCollector: MLDataCollector

    // Debug logging management (v1.32.384: extracted to DebugLoggingManager)
    private lateinit var _debugLoggingManager: DebugLoggingManager

    // Config propagation (v1.32.386: extracted to ConfigPropagator)
    private var _configPropagator: ConfigPropagator? = null

    // Suggestion/prediction bridge (v1.32.406: extracted to SuggestionBridge)
    private lateinit var _suggestionBridge: SuggestionBridge


    // Layout bridge (v1.32.408: extracted to LayoutBridge)
    private lateinit var _layoutBridge: LayoutBridge

    // Preference UI update handler (v1.32.412: extracted to PreferenceUIUpdateHandler)
    private var _preferenceUIUpdateHandler: PreferenceUIUpdateHandler? = null

    // Theme change broadcast receiver
    private var _themeChangeReceiver: BroadcastReceiver? = null

    companion object {
        /** Broadcast action sent when theme changes in ThemeSettingsActivity */
        const val ACTION_THEME_CHANGED = "tribixbite.cleverkeys.ACTION_THEME_CHANGED"

        /** Flag indicating we're in short swipe customization mode (for UI to react) */
        @Volatile
        private var _customizationMode: Boolean = false

        /** Reference to the current service instance for UI components */
        // Self-reference to the live IME service (a Context). Not a leak: it is assigned in
        // onCreate() and cleared in onDestroy() (guarded by `_instance == this`), so it never
        // outlives the service. Callers need the actual service instance for IME operations,
        // so applicationContext cannot substitute here.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var _instance: CleverKeysService? = null

        /** Set whether we're in short swipe customization mode */
        @JvmStatic
        fun setCustomizationMode(enabled: Boolean) {
            _customizationMode = enabled
        }

        /** Check if we're in short swipe customization mode */
        @JvmStatic
        fun isCustomizationMode(): Boolean = _customizationMode

        /** Get the current service instance (may be null if service not running) */
        @JvmStatic
        fun getInstance(): CleverKeysService? = _instance

        /**
         * Re-warms the geometric swipe engine after a Full Geometric Settings knob change
         * (WP9 audit m-3). The adapter bakes the three user knobs into an immutable
         * `GeometricEngineConfig`, so a change invalidates the built engine AND its template
         * cache; without this hook the rebuild happened lazily inside the first post-change
         * swipe (150-400 ms on the decode thread — no jank, but the first swipe pays it),
         * which made the "background re-warm" claim in the spec and the activity header
         * aspirational rather than true.
         *
         * Safe to call from any UI in this process (settings run in the IME's process): a
         * no-op when the service is not running, when swipe typing is off, when the mode does
         * not route to the geometric engine, or when the keyboard view is not laid out yet —
         * in which case `onStartInputView`'s prewarm covers the next appearance anyway. The
         * warm-up itself runs in the adapter's BACKGROUND task slot, so it can never cancel an
         * in-flight decode (audit M-2).
         *
         * Must be called on the main thread (it touches the live keyboard view).
         */
        @JvmStatic
        fun requestGeometricRewarm() {
            val instance = _instance ?: return
            if (!instance::_inputCoordinator.isInitialized) return
            instance._inputCoordinator.prewarmGeometricEngine()
        }

        /**
         * Find a key by its main character in the current keyboard layout.
         * This looks through all rows and keys to find one where the main key (index 0)
         * matches the given character.
         *
         * @param char The lowercase character to search for
         * @return The KeyboardData.Key if found, null otherwise
         */
        @JvmStatic
        fun findKeyByChar(char: String): KeyboardData.Key? {
            val instance = _instance ?: return null
            val layout = try {
                instance.current_layout()
            } catch (e: Exception) {
                return null
            }

            // Search through all rows and keys
            for (row in layout.rows) {
                for (key in row.keys) {
                    // Check if the main key (index 0) matches the character
                    val mainKv = key.keys.getOrNull(0) ?: continue
                    val mainChar = when (mainKv.getKind()) {
                        KeyValue.Kind.Char -> mainKv.getChar().lowercaseChar().toString()
                        KeyValue.Kind.String -> mainKv.getString().lowercase()
                        else -> continue
                    }
                    if (mainChar == char.lowercase()) {
                        return key
                    }
                }
            }
            return null
        }

        /**
         * Get the row height for a key in the current layout.
         * Searches for the key and returns the height of its containing row.
         *
         * @param key The key to find the row height for
         * @return The row height, or 1.0f if not found
         */
        @JvmStatic
        fun getRowHeightForKey(key: KeyboardData.Key): Float {
            val instance = _instance ?: return 1.0f
            val layout = try {
                instance.current_layout()
            } catch (e: Exception) {
                return 1.0f
            }

            for (row in layout.rows) {
                if (row.keys.contains(key)) {
                    return row.height
                }
            }
            return 1.0f
        }
    }

    /**
     * Layout currently visible before it has been modified.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun current_layout_unmodified(): KeyboardData {
        return _layoutBridge.getCurrentLayoutUnmodified()
    }

    /**
     * Layout currently visible.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun current_layout(): KeyboardData {
        return _layoutBridge.getCurrentLayout()
    }

    /**
     * Set text layout by index.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun setTextLayout(l: Int) {
        _layoutBridge.setTextLayout(l)
    }

    /**
     * Cycle to next/previous text layout.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun incrTextLayout(delta: Int) {
        _layoutBridge.incrTextLayout(delta)
    }

    /**
     * Set special layout (numeric, emoji, etc.).
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun setSpecialLayout(l: KeyboardData) {
        _layoutBridge.setSpecialLayout(l)
    }

    /**
     * Load a layout from resources.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun loadLayout(layout_id: Int): KeyboardData? {
        return _layoutBridge.loadLayout(layout_id)
    }

    /**
     * Load a layout that contains a numpad.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun loadNumpad(layout_id: Int): KeyboardData? {
        return _layoutBridge.loadNumpad(layout_id)
    }

    /**
     * Load a pinentry layout.
     * (v1.32.363: Delegated to LayoutManager)
     * (v1.32.408: Delegated to LayoutBridge)
     */
    fun loadPinentry(layout_id: Int): KeyboardData? {
        return _layoutBridge.loadPinentry(layout_id)
    }

    override fun onCreate() {
        super.onCreate()

        // Store instance for static access (needed by ShortSwipeCustomizationActivity)
        _instance = this

        // Initialize ComposeKeyData early (required for shift key modifier operations)
        ComposeKeyData.initialize(this)

        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        _handler = Handler(mainLooper)

        // Create bridge for KeyEventHandler to KeyboardReceiver delegation (v1.32.390)
        // Receiver will be initialized later and set on the bridge
        _receiverBridge = KeyEventReceiverBridge.create(this, _handler)
        _keyeventhandler = KeyEventHandler(_receiverBridge)

        // Create FoldStateTracker for device fold state monitoring
        val foldStateTracker = FoldStateTracker(this)

        // Initialize global config for KeyEventHandler
        Config.initGlobalConfig(prefs, resources, _keyeventhandler, foldStateTracker.isUnfolded())

        // Prewarm emoji keyword index for fast search (loads in background on IO thread)
        EmojiKeywordIndex.prewarm(this)

        // Let the pure CTC language table see this device's imported language packs. Must run
        // before the first swipe: `performCtcSwipeTyping` asks CtcEngineAdapter.supportsLanguage
        // BEFORE it creates the adapter, so without this the first swipe in an
        // imported-pack language would fall to geometric even with a measured-eligible pack.
        // Cheap — it registers a resolver and touches no file. Pinned by CoreImeHygieneDriftTest.
        tribixbite.cleverkeys.swipe.CtcInstalledPacks.bind(this)

        // Initialize configuration manager (v1.32.345: extracted configuration management)
        _configManager = ConfigurationManager(this, Config.globalConfig(), foldStateTracker)
        _config = _configManager.getConfig() // Cache reference for convenience
        _configManager.registerConfigChangeListener(this) // Register for config change notifications

        // Register ConfigurationManager as SharedPreferences listener
        prefs.registerOnSharedPreferenceChangeListener(_configManager)
        // Also register this service to handle theme changes directly
        prefs.registerOnSharedPreferenceChangeListener(this)

        // Register theme change broadcast receiver (for immediate theme updates from ThemeSettingsActivity)
        _themeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_THEME_CHANGED) {
                    // 1. Refresh config to pick up new values (colors, etc.)
                    _configManager.refresh(resources)

                    // 2. FORCE view recreation.
                    // Even if the theme ID hasn't changed (e.g. editing custom theme colors),
                    // we need to recreate the view to pick up the new colors.
                    // Passing 0, 0 forces the logic in onThemeChanged to run.
                    if (isInputViewShown) {
                        onThemeChanged(0, 0)
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_THEME_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(_themeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(_themeChangeReceiver, filter)
        }

        // Check if we're the default IME and remind user if not
        checkAndPromptDefaultIME()
        _keyboardView = inflate_view(R.layout.keyboard) as Keyboard2View
        _keyboardView.reset()
        Logs.set_debug_logs(resources.getBoolean(R.bool.debug_logs))
        ClipboardHistoryService.on_startup(this, _keyeventhandler)

        // Fold state change callback is handled by ConfigurationManager

        // Build the composition root (ARC-072 slice 3: was ManagerInitializer +
        // PredictionInitializer + PropagatorInitializer wiring spread over 6 files).
        // The read order below is the construction order — the graph's `by lazy` members
        // fire on first read, preserving the retired Initializers' exact sequence.
        val config = _config ?: return  // Early return if config not initialized
        _graph = KeyboardComponentGraph(this, config, _keyboardView, _keyeventhandler, _handler, _receiverBridge)

        // Managers (first read constructs the whole cluster in dependency order)
        _contractionManager = _graph.contractionManager
        _clipboardManager = _graph.clipboardManager
        _contextTracker = _graph.contextTracker
        _receiverBridge.setContextTracker(_contextTracker)  // v1.2.7: for smart punctuation
        _predictionCoordinator = _graph.predictionCoordinator
        _inputCoordinator = _graph.inputCoordinator
        _suggestionHandler = _graph.suggestionHandler
        _keyboardDimensionsHelper = _graph.keyboardDimensionsHelper
        _mlDataCollector = _graph.mlDataCollector

        // Suggestion bridge (v1.32.406: extracted to SuggestionBridge; built by the graph)
        _suggestionBridge = _graph.suggestionBridge

        // Wire the view's service handle (unconditional) and load prediction models if enabled
        _graph.wireSwipeTypingComponents()

        // Initialize debug logging manager (v1.32.384)
        _debugLoggingManager = DebugLoggingManager(this, packageName)
        _debugLoggingManager.initializeLogWriter()

        // Connect debug logger to input coordinator for prediction handling logging
        // This enables prediction selection/insertion logs to appear in SwipeDebugActivity
        _inputCoordinator.setDebugLogger { message -> _debugLoggingManager.sendDebugLog(message) }

        // Provide the LIVE input connection/editor so the cold-start swipe replay can drop a
        // deferred commit when the focused field has changed since the swipe (avoids writing
        // into a stale/other field). Reads the service's current values at call time.
        _inputCoordinator.setCurrentInputProvider { currentInputConnection to currentInputEditorInfo }

        // Propagators (v1.32.396; ARC-072: built by the graph). Creates and registers the
        // DebugModePropagator, then builds the ConfigPropagator with all managers.
        // _layoutManager/_subtypeManager are still null here — same values the retired
        // PropagatorInitializer captured at this point in onCreate.
        _configPropagator = _graph.buildConfigPropagator(
            _debugLoggerImpl,
            _debugLoggingManager,
            _layoutManager,
            _subtypeManager
        )

        // Register broadcast receiver for debug mode control (v1.32.384: delegated to DebugLoggingManager)
        _debugLoggingManager.registerDebugModeReceiver(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clear static instance reference
        if (_instance == this) {
            _instance = null
        }

        // Unregister theme change broadcast receiver
        _themeChangeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Receiver may not have been registered
            }
            _themeChangeReceiver = null
        }

        // Cleanup all managers (v1.32.404: extracted to CleanupHandler)
        CleanupHandler.create(
            this,
            _configManager,
            _clipboardManager,
            _predictionCoordinator,
            _debugLoggingManager,
            if (::_inputCoordinator.isInitialized) _inputCoordinator else null,
            if (::_suggestionHandler.isInitialized) _suggestionHandler else null
        ).cleanup()

        // Cleanup DirectBootManager (v1.1.75: Direct Boot compatibility)
        DirectBootManager.getInstance(this).cleanup()
    }

    /**
     * Send debug log message to SwipeDebugActivity if debug mode is enabled.
     * (v1.32.384: Delegated to DebugLoggingManager)
     */
    private fun sendDebugLog(message: String) {
        _debugLoggingManager.sendDebugLog(message)
    }

    /**
     * DebugLogger implementation for SuggestionHandler.
     */
    private val _debugLoggerImpl = object : SuggestionHandler.DebugLogger {
        override fun sendDebugLog(message: String) {
            this@CleverKeysService.sendDebugLog(message)
        }
    }

    /**
     * Gets InputMethodManager.
     * (v1.32.365: Delegated to SubtypeManager)
     */
    fun get_imm(): InputMethodManager {
        return _subtypeManager!!.getInputMethodManager()
    }

    /**
     * Refreshes IME subtype settings and initializes managers.
     * (v1.32.365: Simplified by delegating to SubtypeManager)
     * (v1.32.409: extracted to SubtypeLayoutInitializer; ARC-072: absorbed into the graph)
     */
    private fun refreshSubtypeImm(changedTo: InputMethodSubtype? = null) {
        if (!::_graph.isInitialized) {
            // Degenerate pre-config path: onCreate early-returned before building the graph
            // (config was null). Preserves the retired SubtypeLayoutInitializer's null-config
            // behavior — create the SubtypeManager, leave LayoutManager/LayoutBridge unset.
            _subtypeManager = _subtypeManager ?: SubtypeManager(this)
            return
        }
        val result =
            _graph.refreshSubtypeAndLayout(_subtypeManager, _layoutManager, resources, changedTo)

        _subtypeManager = result.subtypeManager
        _layoutManager = result.layoutManager

        // Initialize LayoutBridge on first call (result.layoutBridge is non-null only on first call)
        result.layoutBridge?.let { _layoutBridge = it }
    }

    /**
     * Refresh action label configuration from EditorInfo.
     *
     * v1.32.379: EditorInfo parsing extracted to EditorInfoHelper (Kotlin).
     * Extracts action label, action ID, and Enter/Action key swap behavior.
     */
    private fun refresh_action_label(info: EditorInfo) {
        val actionInfo = EditorInfoHelper.extractActionInfo(info, resources)

        _config?.actionLabel = actionInfo.actionLabel
        actionId = actionInfo.actionId
        _config?.swapEnterActionKey = actionInfo.swapEnterActionKey
    }

    /** Might re-create the keyboard view. [_keyboardView.setKeyboard()] and
     [setInputView()] must be called soon after. */
    private fun refresh_config() {
        // Delegate to ConfigurationManager, which will trigger listener callbacks
        _configManager.refresh(resources)
    }

    /**
    * Recalculate the keyboard height based on current orientation/foldable state
    * and apply it to the keyboard view. Call this whenever the height might have changed.
    */
    private fun refreshKeyboardHeight() {
        if (!::_neuralLayoutBridge.isInitialized) return
        val height = _neuralLayoutBridge.calculateDynamicKeyboardHeight()
        _keyboardView.layoutParams?.height = height.toInt()
        _keyboardView.requestLayout()
    }

    // ConfigChangeListener implementation (v1.32.345)

    /**
     * Called when configuration has been refreshed.
     * Updates local config reference and propagates to components.
     */
    override fun onConfigChanged(newConfig: Config) {
        // Update cached reference
        _config = newConfig

        // Propagate config to all managers (v1.32.386: delegated to ConfigPropagator)
        _configPropagator?.propagateConfig(newConfig, resources)
    }

    /**
     * Called when theme has changed.
     * Re-creates keyboard views with new theme.
     */
    override fun onThemeChanged(oldTheme: Int, newTheme: Int) {
        // Recreate views with new theme
        _keyboardView = inflate_view(R.layout.keyboard) as Keyboard2View
        _emojiPane = null

        // Clean up clipboard manager views for theme change
        _clipboardManager.cleanup()

        // CRITICAL: Set the keyboard layout on the new view to enable swipe/touch handling
        // Without this, _keyboard is null and key positions aren't calculated
        _keyboardView.setKeyboard(current_layout())

        // Re-initialize swipe typing components on the new view
        // Pass null for word predictor (re-wired by PredictionViewSetup on next input)
        // The service reference enables swipe handling callbacks
        _keyboardView.setSwipeTypingComponents(null, this)

        setInputView(_keyboardView)
    }

    /**
     * Determine special layout based on input type.
     * (v1.32.363: Delegated to LayoutManager)
     */
    private fun refresh_special_layout(info: EditorInfo): KeyboardData? {
        return _layoutManager?.refresh_special_layout(info)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // NOTE: Config refresh is handled by SharedPreferences listener (onSharedPreferenceChanged)
        // We only do initial config load here if config is completely null (shouldn't happen normally)
        if (_config == null) {
            refresh_config()
        }

        // ARC-006: the per-field `clearLanguageHistory()` call that stood here was deleted
        // 2026-08-28 along with UnigramLanguageDetector — it cleared a word window nothing
        // read. The remaining per-field resets live in KeyEventHandler.started(info) below.

        // Check if the current view was created with a stale theme
        val latestThemeId = _config?.theme ?: 0
        if (_currentViewThemeId != latestThemeId && latestThemeId != 0) {
            _keyboardView = inflate_view(R.layout.keyboard) as Keyboard2View
            _emojiPane = null
            _keyboardView.setKeyboard(current_layout())
            _keyboardView.setSwipeTypingComponents(null, this)
            setInputView(_keyboardView)
        } else if (_keyboardView.parent == null) {
            // Ensure view is attached if it was detached
            setInputView(_keyboardView)
        }

        // Initialize subtype and layout if not already done (v1.32.413: ensure layoutManager is ready)
        // This is needed for receiver initialization which depends on layoutManager
        if (_layoutManager == null) {
            refreshSubtypeImm()
        }

        // Initialize KeyboardReceiver if needed (v1.32.397: extracted to ReceiverInitializer;
        // ARC-072: absorbed into the graph)
        // Lazy initialization: creates receiver on first call, returns existing on subsequent calls
        // Note: createReceiverIfNeeded() may return null if layoutManager not ready (rare edge case)
        _receiver = _graph.createReceiverIfNeeded(_receiver, _layoutManager, _subtypeManager)

        // NOTE: Content pane state is now managed by KeyboardReceiver.resetContentPaneState()
        // which is called in onFinishInputView. No visibility manipulation needed here.

        refresh_action_label(info)

        // Set special layout if needed (v1.32.363: use LayoutManager)
        val specialLayout = refresh_special_layout(info)
        if (specialLayout != null) {
            _layoutManager?.setSpecialLayout(specialLayout)
        } else {
            _layoutManager?.clearSpecialLayout()
        }

        _keyboardView.setKeyboard(current_layout())
        // WP9 R-1 step 8: background-warm the geometric engine when this layout routes to it,
        // so the first non-QWERTY swipe avoids the synchronous template-index build. No-op
        // unless swipe typing is on AND swipe_engine_mode is hybrid/geometric.
        if (::_inputCoordinator.isInitialized) _inputCoordinator.prewarmGeometricEngine()
        _keyeventhandler.started(info)

        // Setup prediction views (v1.32.400: extracted prediction/swipe setup logic)
        // Handles initialization, suggestion bar creation, keyboard dimensions, and cleanup
        val config = _config  // Capture for null safety
        val predCoordinator = _predictionCoordinator  // Capture for null safety
        config?.let { cfg ->
            val predictionSetup = PredictionViewSetup.create(
                this,
                cfg,
                _keyboardView,
                predCoordinator,
                _inputCoordinator,
                _suggestionHandler,
                _keyboardDimensionsHelper,
                _receiver,
                _emojiPane
            ).setupPredictionViews(_suggestionBar, _inputViewContainer, _contentPaneContainer, _topPane, _scrollView)

            // Update components from setup result
            _suggestionBar = predictionSetup.suggestionBar
            _inputViewContainer = predictionSetup.inputViewContainer
            _contentPaneContainer = predictionSetup.contentPaneContainer
            _topPane = predictionSetup.topPane
            _scrollView = predictionSetup.scrollView
            setInputView(predictionSetup.inputView)

            // #41 v5: Emoji search manager persists across onStartInputView calls
            // Only create once; it gets initialized when emoji pane is first opened
            if (_emojiSearchManager == null) {
                _emojiSearchManager = EmojiSearchManager()
            }
            _receiver?.setEmojiSearchManager(_emojiSearchManager!!)

            // Password field detection: disable predictions and show eye toggle
            val isPasswordField = SuggestionBar.isPasswordField(info)
            _suggestionBar?.setPasswordMode(isPasswordField)
            _suggestionHandler?.setPasswordMode(isPasswordField)

            // M5 (review 2026-08-06): honor IME_FLAG_NO_PERSONALIZED_LEARNING —
            // incognito fields (private browser tabs etc.) suppress the learn
            // funnel, selection-adaptation recording, and next-word surfacing.
            _suggestionHandler?.setFieldPersonalizedLearningAllowed(
                LearningGate.fieldAllowsPersonalizedLearning(info.imeOptions)
            )
            // #39: Allow swipe predictions in password fields if enabled
            _suggestionBar?.setAllowSwipeInPasswordMode(_config?.swipe_on_password_fields ?: false)
            // Wire up InputConnectionProvider for accurate password text reading
            // This enables the eye toggle to show actual field content even after cursor moves
            _suggestionBar?.setInputConnectionProvider { currentInputConnection }
        }

        // Key positions are read per swipe from Keyboard2View.geometryParams()
        // The manual post() call here was causing redundant "key positions set" logs and layout updates

        _config?.let { Logs.debug_startup_input_view(info, it) }
    }

    override fun setInputView(v: View) {
        val parent = v.parent
        if (parent != null && parent is ViewGroup) {
            parent.removeView(v)
        }
        super.setInputView(v)
        updateSoftInputWindowLayoutParams()
        v.requestApplyInsets()
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        updateSoftInputWindowLayoutParams()
    }

    /**
     * Updates soft input window layout parameters for IME.
     *
     * v1.32.375: Window layout management extracted to WindowLayoutUtils (Kotlin).
     * Configures edge-to-edge display, window height, input area height, and gravity.
     */
    private fun updateSoftInputWindowLayoutParams() {
        val window = window?.window ?: return
        val inputArea = window.findViewById<View>(android.R.id.inputArea)
        WindowLayoutUtils.updateSoftInputWindowLayoutParams(window, inputArea, isFullscreenMode)
    }

    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype) {
        // gh #160: pass the DELIVERED subtype through — re-deriving via the IMM inside this
        // callback can return the old subtype, and tag-only matching aliases duplicate
        // languageTags (ar/ar_TN); either way the switch was invisible.
        refreshSubtypeImm(changedTo = subtype)
        _keyboardView.setKeyboard(current_layout())
        // REMOVED: Redundant layout update - now handled exclusively by PredictionViewSetup's GlobalLayoutListener
        // This eliminates double initialization and input lag on app switches
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // CRITICAL: Refresh config when orientation changes to update landscape/portrait margins
        // Without this, landscape margins are never applied because Config.orientation_landscape
        // isn't updated when the device rotates
        refresh_config()
        refreshKeyboardHeight()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        _keyeventhandler.selection_updated(oldSelStart, newSelStart)
        if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd)) {
            _keyboardView.set_selection_state(newSelStart != newSelEnd)
        }

        // v1.2.6: Trigger cursor-aware prediction sync when cursor moves
        // Only sync when cursor position changes (not selection range change)
        // and when there's no active selection (newSelStart == newSelEnd)
        if (newSelStart == newSelEnd && oldSelStart != newSelStart) {
            _inputCoordinator.onCursorMoved(
                newPosition = newSelStart,
                ic = currentInputConnection,
                language = _config?.primary_language ?: "en",
                editorInfo = currentInputEditorInfo
            )
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        _keyboardView.reset()

        // Clear suggestions to prevent stale state/crashes on app switch
        _suggestionBar?.clearSuggestions()

        // v1.2.6: Cancel any pending cursor sync
        _inputCoordinator.cancelPendingCursorSync()

        // Clear prediction context to prevent cross-app text leaking
        // (e.g., typing "t" in app A then "h" in app B showing "th" predictions)
        _contextTracker.clearAll()

        // Checkpoint learned data (context LM bigrams + user vocabulary) — the
        // natural "user left the field" moment. Async debounced-store flush;
        // no-op when nothing is dirty (2026-08-06 persistence fix).
        _predictionCoordinator?.flushLearnedData()

        // Reset content pane state (hide emoji/clipboard if open)
        _receiver?.resetContentPaneState()
    }

    // ==================== Inline Autofill Support (API 30+) ====================
    // These callbacks enable seamless password manager integration without button presses.
    // The system automatically calls these when focusing on autofill-enabled fields.

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        // Always allow inline autofill suggestions when the feature is available

        return try {
            tribixbite.cleverkeys.autofill.InlineAutofillUtils.createInlineSuggestionsRequest(this)
        } catch (e: Exception) {
            android.util.Log.e("CleverKeysService", "Failed to create inline suggestions request", e)
            null
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        // Always handle inline autofill suggestions when available

        val inlineSuggestions = response.inlineSuggestions
        if (inlineSuggestions.isEmpty()) {
            return false
        }

        return try {
            val inlineSuggestionView = tribixbite.cleverkeys.autofill.InlineAutofillUtils.createView(
                inlineSuggestions,
                this
            )

            // Display inline autofill suggestions in the suggestion bar
            _suggestionBar?.setInlineAutofillView(inlineSuggestionView)

            true
        } catch (e: Exception) {
            android.util.Log.e("CleverKeysService", "Failed to display inline suggestions", e)
            false
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        // NOTE: ConfigurationManager is the primary SharedPreferences listener and handles
        // config refresh. This method handles additional UI updates.
        // (v1.32.412: Delegated to PreferenceUIUpdateHandler)

        // Skip if keyboard components aren't initialized yet (happens when SettingsActivity
        // triggers preference changes before keyboard has been used)
        if (!::_layoutBridge.isInitialized) {
            return
        }

        if (key in setOf("keyboard_height", "keyboard_height_landscape",
                     "keyboard_height_unfolded", "keyboard_height_landscape_unfolded")) {
            refreshKeyboardHeight()
            // Do NOT return here – let the existing handler also run if needed
        }

        // Initialize handler lazily (depends on components that may not exist yet)
        if (_preferenceUIUpdateHandler == null) {
            _preferenceUIUpdateHandler = PreferenceUIUpdateHandler.create(
                this,  // Context for language dictionary reload (v1.1.86)
                _config,
                _layoutBridge,
                _predictionCoordinator,
                _keyboardView,
                _suggestionBar,
                _contractionManager  // v1.2.0: Enable contraction reload on language toggle
            )
        }

        _preferenceUIUpdateHandler?.handlePreferenceChange(key)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        /* Entirely disable fullscreen mode. */
        return false
    }

    /** Not static */
    // v1.32.368: Receiver inner class removed - functionality moved to KeyboardReceiver class

    /**
     * Gets connection token for IME operations.
     * (v1.32.368: Made public for KeyboardReceiver)
     */
    fun getConnectionToken(): IBinder? {
        return window?.window?.attributes?.token
    }

    /**
     * Gets current configuration.
     * (v1.32.368: Added for KeyboardReceiver)
     */
    fun getConfig(): Config? {
        return _config
    }

    // v1.32.349: showDateFilterDialog() moved to ClipboardManager (now showFilterDialog())

    // SuggestionBar.OnSuggestionSelectedListener implementation
    /**
     * Update context with a completed word
     * (v1.32.361: Delegated to SuggestionHandler)
     *
     * NOTE: This is a legacy helper method. New code should use
     * _contextTracker.commitWord() directly with appropriate PredictionSource.
     */
    private fun updateContext(word: String) {
        _suggestionHandler.updateContext(word)
    }

    // Suggestion/Prediction Methods (v1.32.406: Delegated to SuggestionBridge)
    // WP9 R-1 step 6: the dead handlePredictionResults chain (service → bridge → SH's legacy
    // auto-inserting entry; zero callers) was deleted — swipe results flow only through
    // InputCoordinator → SuggestionHandler.handleSwipePredictionResults.

    override fun onSuggestionSelected(word: String) {
        _suggestionBridge.onSuggestionSelected(word)
    }

    fun handleRegularTyping(text: String) {
        _suggestionBridge.handleRegularTyping(text)
    }

    fun handleBackspace() {
        _suggestionBridge.handleBackspace()
    }

    fun handleDeleteLastWord() {
        _suggestionBridge.handleDeleteLastWord()
    }

    /**
     * Trigger a keyboard event (like SWITCH_FORWARD) from external callers.
     * Used by custom short swipe mappings for layout switching.
     * (v1.33.x: Added for short swipe customization support)
     */
    fun triggerKeyboardEvent(event: KeyValue.Event) {
        _receiver?.handle_event_key(event)
    }

    // Keyboard dimension helpers (the NeuralLayoutBridge indirection was deleted with the
    // neural engine on 2026-08-18 — KeyboardDimensionsHelper is called directly).
    private fun calculateDynamicKeyboardHeight(): Float =
        _keyboardDimensionsHelper.calculateDynamicKeyboardHeight()

    private fun getUserKeyboardHeightPercent(): Int =
        _keyboardDimensionsHelper.getUserKeyboardHeightPercent()

    // Called by Keyboard2View when swipe typing completes.
    // wasShiftActive (v1.32.926): shift state for capitalize-first-letter;
    // wasShiftLocked (v1.33.8): caps-lock state for ALL CAPS.
    fun handleSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>,
        timestamps: List<Long>,
        wasShiftActive: Boolean = false,
        wasShiftLocked: Boolean = false
    ) {
        // v1.32.350: Delegated to InputCoordinator
        val ic = currentInputConnection
        val editorInfo = currentInputEditorInfo
        _inputCoordinator.handleSwipeTyping(swipedKeys, swipePath, timestamps, ic, editorInfo, resources, wasShiftActive, wasShiftLocked)
    }

    /**
     * Inflates a view with the current theme.
     * (v1.32.368: Made public for KeyboardReceiver)
     * (v1.32.500: Stamps _currentViewThemeId for stale view detection)
     */
    fun inflate_view(layout: Int): View {
        val themeId = _config?.theme ?: 0
        // Stamp the theme ID when inflating keyboard layout (for stale view detection in onStartInputView)
        if (layout == R.layout.keyboard) {
            _currentViewThemeId = themeId
        }
        return View.inflate(ContextThemeWrapper(this, themeId), layout, null)
    }

    // ARC-084 (2026-08-29): updateCGRPredictions/checkCGRPredictions were re-exported here
    // for "Keyboard2View's CGR store/clear path" — a path whose store had no caller. The
    // whole chain is deleted; pinned by DeadPlumbingDriftTest.

    // ARC-099 (2026-08-30): three pass-throughs to the KeyboardDimensionsHelper "legacy"
    // swipe-prediction methods were re-exported here with zero callers of their own — the
    // only thing that made those helpers reachable by the linker. Whole chain deleted;
    // pinned by DeadPlumbingDriftTest.

    /**
     * Show a temporary message in the suggestion bar.
     * Used for feedback when Toast is suppressed (Android 13+ IME restrictions).
     *
     * @param message The message to display
     * @param durationMs How long to show the message (default 1500ms)
     * @since v1.2.0
     */
    fun showSuggestionBarMessage(message: String, durationMs: Long = 1500L) {
        _suggestionBar?.showTemporaryMessage(message, durationMs)
    }

    // Check if default IME, show notification if not (v1.32.377: Delegated to IMEStatusHelper)
    private fun checkAndPromptDefaultIME() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        IMEStatusHelper.checkAndPromptDefaultIME(this, _handler, prefs, packageName, javaClass.name)
    }

    // v1.32.341: loadContractionMappings() method removed - functionality moved to ContractionManager class
}
