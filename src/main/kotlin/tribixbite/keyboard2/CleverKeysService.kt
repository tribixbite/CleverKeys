package tribixbite.keyboard2

import android.content.SharedPreferences
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import tribixbite.keyboard2.ml.SwipeMLData

/**
 * Main InputMethodService implementation for Unexpected Keyboard.
 *
 * This class serves as the central coordinator for the keyboard, managing:
 * - **View Lifecycle**: Creates and manages keyboard views, content panes (emoji/clipboard), and input views
 * - **Layout Management**: Delegates to [LayoutManager] for keyboard layout loading and switching
 * - **Input Processing**: Coordinates with [KeyEventHandler] for key events and text input
 * - **Prediction System**: Manages neural network-based swipe typing via [PredictionCoordinator]
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
 * All predictions wait for gesture completion to avoid premature suggestions. This matches
 * SwipeCalibrationActivity behavior and ensures consistent user experience.
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

    // Unified prediction strategy: All predictions wait for gesture completion
    // to match SwipeCalibrationActivity behavior and eliminate premature predictions
    private lateinit var _keyboardView: Keyboard2View
    private lateinit var _keyeventhandler: KeyEventHandler

    // Layout management (v1.32.363: extracted to LayoutManager)
    private var _layoutManager: LayoutManager? = null

    private var _emojiPane: ViewGroup? = null
    private var _contentPaneContainer: FrameLayout? = null // Container for emoji/clipboard panes
    var actionId: Int = 0 // Action performed by the Action key.
    private lateinit var _handler: Handler

    // Clipboard management (v1.32.349: extracted to ClipboardManager)
    private lateinit var _clipboardManager: ClipboardManager

    // Configuration management (v1.32.345: extracted to ConfigurationManager)
    private lateinit var _configManager: ConfigurationManager
    private var _config: Config? = null // Cached reference from _configManager, updated by ConfigChangeListener

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

    // Neural layout helper (v1.32.362: extracted to NeuralLayoutHelper)
    private lateinit var _neuralLayoutHelper: NeuralLayoutHelper

    // Subtype management (v1.32.365: extracted to SubtypeManager)
    private var _subtypeManager: SubtypeManager? = null

    // Event handling (v1.32.368: extracted to KeyboardReceiver)
    private var _receiver: KeyboardReceiver? = null

    // KeyEventHandler bridge (v1.32.390: extracted to KeyEventReceiverBridge)
    private lateinit var _receiverBridge: KeyEventReceiverBridge

    // ML data collection (v1.32.370: extracted to MLDataCollector)
    private lateinit var _mlDataCollector: MLDataCollector

    // Debug logging management (v1.32.384: extracted to DebugLoggingManager)
    private lateinit var _debugLoggingManager: DebugLoggingManager

    // Config propagation (v1.32.386: extracted to ConfigPropagator)
    private var _configPropagator: ConfigPropagator? = null

    // Suggestion/prediction bridge (v1.32.406: extracted to SuggestionBridge)
    private lateinit var _suggestionBridge: SuggestionBridge

    // Neural layout bridge (v1.32.407: extracted to NeuralLayoutBridge)
    private lateinit var _neuralLayoutBridge: NeuralLayoutBridge

    // Layout bridge (v1.32.408: extracted to LayoutBridge)
    private lateinit var _layoutBridge: LayoutBridge

    // Preference UI update handler (v1.32.412: extracted to PreferenceUIUpdateHandler)
    private var _preferenceUIUpdateHandler: PreferenceUIUpdateHandler? = null

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

        // Initialize configuration manager (v1.32.345: extracted configuration management)
        _configManager = ConfigurationManager(this, Config.globalConfig(), foldStateTracker)
        _config = _configManager.getConfig() // Cache reference for convenience
        _configManager.registerConfigChangeListener(this) // Register for config change notifications

        // Register ConfigurationManager as SharedPreferences listener
        prefs.registerOnSharedPreferenceChangeListener(_configManager)

        // Check if we're the default IME and remind user if not
        checkAndPromptDefaultIME()
        _keyboardView = inflate_view(R.layout.keyboard) as Keyboard2View
        _keyboardView.reset()
        Logs.set_debug_logs(resources.getBoolean(R.bool.debug_logs))
        ClipboardHistoryService.on_startup(this, _keyeventhandler)

        // Fold state change callback is handled by ConfigurationManager

        // Initialize all managers (v1.32.388: extracted to ManagerInitializer)
        val config = _config ?: return  // Early return if config not initialized
        val managers = ManagerInitializer.create(this, config, _keyboardView, _keyeventhandler).initialize()

        _contractionManager = managers.contractionManager
        _clipboardManager = managers.clipboardManager
        _contextTracker = managers.contextTracker
        _predictionCoordinator = managers.predictionCoordinator
        _inputCoordinator = managers.inputCoordinator
        _suggestionHandler = managers.suggestionHandler
        _neuralLayoutHelper = managers.neuralLayoutHelper
        _mlDataCollector = managers.mlDataCollector

        // Initialize suggestion bridge (v1.32.406: extracted to SuggestionBridge)
        val predictionCoord = _predictionCoordinator  // Capture for smart cast
        _suggestionBridge = SuggestionBridge.create(
            this,
            _suggestionHandler,
            _mlDataCollector,
            _inputCoordinator,
            _contextTracker,
            predictionCoord,
            _keyboardView
        )

        // Initialize neural layout bridge (v1.32.407: extracted to NeuralLayoutBridge)
        _neuralLayoutBridge = NeuralLayoutBridge.create(_neuralLayoutHelper, _keyboardView)

        // Initialize prediction components if enabled (v1.32.405: extracted to PredictionInitializer)
        val predCoord = _predictionCoordinator  // Capture for smart cast
        PredictionInitializer.create(config, predCoord, _keyboardView, this)
            .initializeIfEnabled()

        // Initialize debug logging manager (v1.32.384)
        _debugLoggingManager = DebugLoggingManager(this, packageName)
        _debugLoggingManager.initializeLogWriter()

        // Connect debug logger to prediction coordinator for neural engine logging (v1.32.461)
        // This enables key detection logs to appear in SwipeDebugActivity
        _predictionCoordinator?.setDebugLogger { message -> _debugLoggingManager.sendDebugLog(message) }

        // Initialize propagators (v1.32.396: extracted propagator initialization)
        // Creates and registers DebugModePropagator, builds ConfigPropagator with all managers
        val propagators = PropagatorInitializer.create(
            _suggestionHandler,
            _neuralLayoutHelper,
            _debugLoggerImpl,
            _debugLoggingManager,
            _clipboardManager,
            _predictionCoordinator,
            _inputCoordinator,
            _layoutManager,
            _keyboardView,
            _subtypeManager
        ).initialize()

        _configPropagator = propagators.configPropagator

        // Register broadcast receiver for debug mode control (v1.32.384: delegated to DebugLoggingManager)
        _debugLoggingManager.registerDebugModeReceiver(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cleanup all managers (v1.32.404: extracted to CleanupHandler)
        CleanupHandler.create(
            this,
            _configManager,
            _clipboardManager,
            _predictionCoordinator,
            _debugLoggingManager
        ).cleanup()
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
     * (v1.32.409: Delegated to SubtypeLayoutInitializer)
     */
    private fun refreshSubtypeImm() {
        val config = _config  // Capture for null safety
        val result = SubtypeLayoutInitializer.create(this, config, _keyboardView)
            .refreshSubtypeAndLayout(_subtypeManager, _layoutManager, resources)

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
        // OPTIMIZATION: Only refresh config if null (first load) or explicitly restarting
        // SharedPreferences listener will handle config changes, no need to reload every time
        if (_config == null || !restarting) {
            refresh_config()
        }

        // Initialize subtype and layout if not already done (v1.32.413: ensure layoutManager is ready)
        // This is needed for receiver initialization which depends on layoutManager
        if (_layoutManager == null) {
            refreshSubtypeImm()
        }

        // Initialize KeyboardReceiver if needed (v1.32.397: extracted to ReceiverInitializer)
        // Lazy initialization: creates receiver on first call, returns existing on subsequent calls
        // Note: initializeIfNeeded() may return null if layoutManager not ready (rare edge case)
        val subtypeMan = _subtypeManager  // Capture for null safety
        _receiver = ReceiverInitializer.create(
            this,
            this,
            _keyboardView,
            _layoutManager,
            _clipboardManager,
            _contextTracker,
            _inputCoordinator,
            subtypeMan,
            _handler,
            _receiverBridge
        ).initializeIfNeeded(_receiver)

        // Auto-close clipboard pane when switching to new app/field
        // Prevents confusing UX where clipboard briefly shows then keyboard closes
        if (_contentPaneContainer != null && _contentPaneContainer!!.visibility == View.VISIBLE) {
            _contentPaneContainer!!.visibility = View.GONE
            // Also reset search mode state
            _clipboardManager.resetSearchOnHide()
        }

        refresh_action_label(info)

        // Set special layout if needed (v1.32.363: use LayoutManager)
        val specialLayout = refresh_special_layout(info)
        if (specialLayout != null) {
            _layoutManager?.setSpecialLayout(specialLayout)
        } else {
            _layoutManager?.clearSpecialLayout()
        }

        _keyboardView.setKeyboard(current_layout())
        _keyeventhandler.started(info)

        // Setup prediction views (v1.32.400: extracted prediction/swipe setup logic)
        // Handles initialization, suggestion bar creation, neural engine dimensions, and cleanup
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
                _neuralLayoutHelper,
                _receiver,
                _emojiPane
            ).setupPredictionViews(_suggestionBar, _inputViewContainer, _contentPaneContainer)

            // Update components from setup result
            _suggestionBar = predictionSetup.suggestionBar
            _inputViewContainer = predictionSetup.inputViewContainer
            _contentPaneContainer = predictionSetup.contentPaneContainer
            setInputView(predictionSetup.inputView)
        }

        // Neural key positions are now set by PredictionViewSetup's GlobalLayoutListener
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
        refreshSubtypeImm()
        _keyboardView.setKeyboard(current_layout())
        // REMOVED: Redundant layout update - now handled exclusively by PredictionViewSetup's GlobalLayoutListener
        // This eliminates double initialization and input lag on app switches
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
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        _keyboardView.reset()

        // Clear suggestions to prevent stale state/crashes on app switch
        _suggestionBar?.clearSuggestions()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        // NOTE: ConfigurationManager is the primary SharedPreferences listener and handles
        // config refresh. This method handles additional UI updates.
        // (v1.32.412: Delegated to PreferenceUIUpdateHandler)

        // Initialize handler lazily (depends on components that may not exist yet)
        if (_preferenceUIUpdateHandler == null) {
            _preferenceUIUpdateHandler = PreferenceUIUpdateHandler.create(
                _config,
                _layoutBridge,
                _predictionCoordinator,
                _keyboardView,
                _suggestionBar
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

    // v1.32.349: showDateFilterDialog() method removed - functionality moved to ClipboardManager class

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
    private fun handlePredictionResults(predictions: List<String>, scores: List<Int>) {
        _suggestionBridge.handlePredictionResults(predictions, scores)
    }

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

    // Neural Layout Methods (v1.32.407: Delegated to NeuralLayoutBridge)
    private fun calculateDynamicKeyboardHeight(): Float {
        return _neuralLayoutBridge.calculateDynamicKeyboardHeight()
    }

    private fun getUserKeyboardHeightPercent(): Int {
        return _neuralLayoutBridge.getUserKeyboardHeightPercent()
    }

    // Called by Keyboard2View when swipe typing completes
    fun handleSwipeTyping(
        swipedKeys: List<KeyboardData.Key>,
        swipePath: List<android.graphics.PointF>,
        timestamps: List<Long>,
        wasShiftActive: Boolean = false,  // v1.32.926: Pass shift state for capitalize first letter
        wasShiftLocked: Boolean = false   // v1.33.8: Pass caps lock state for ALL CAPS
    ) {
        // v1.32.350: Delegated to InputCoordinator
        val ic = currentInputConnection
        val editorInfo = currentInputEditorInfo
        _inputCoordinator.handleSwipeTyping(swipedKeys, swipePath, timestamps, ic, editorInfo, resources, wasShiftActive, wasShiftLocked)
    }

    /**
     * Inflates a view with the current theme.
     * (v1.32.368: Made public for KeyboardReceiver)
     */
    fun inflate_view(layout: Int): View {
        return View.inflate(ContextThemeWrapper(this, _config?.theme ?: 0), layout, null)
    }

    // CGR Prediction Methods (v1.32.407: Delegated to NeuralLayoutBridge)
    fun updateCGRPredictions() {
        _neuralLayoutBridge.updateCGRPredictions()
    }

    fun checkCGRPredictions() {
        _neuralLayoutBridge.checkCGRPredictions()
    }

    fun updateSwipePredictions(predictions: List<String>) {
        _neuralLayoutBridge.updateSwipePredictions(predictions)
    }

    fun completeSwipePredictions(finalPredictions: List<String>) {
        _neuralLayoutBridge.completeSwipePredictions(finalPredictions)
    }

    fun clearSwipePredictions() {
        _neuralLayoutBridge.clearSwipePredictions()
    }

    // CRITICAL: Extract key positions for neural swipe (v1.32.407: Delegated to NeuralLayoutBridge)
    private fun setNeuralKeyboardLayout() {
        _neuralLayoutBridge.setNeuralKeyboardLayout()
    }

    // Check if default IME, show notification if not (v1.32.377: Delegated to IMEStatusHelper)
    private fun checkAndPromptDefaultIME() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        IMEStatusHelper.checkAndPromptDefaultIME(this, _handler, prefs, packageName, javaClass.name)
    }

    // v1.32.341: loadContractionMappings() method removed - functionality moved to ContractionManager class
}
