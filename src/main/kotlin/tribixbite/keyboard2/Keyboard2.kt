package tribixbite.keyboard2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.*

/**
 * CleverKeys InputMethodService with neural swipe prediction
 *
 * Modern Kotlin rewrite of Keyboard2.java featuring:
 * - ONNX neural prediction instead of CGR
 * - Coroutine-based asynchronous processing
 * - Reactive configuration management
 * - Memory-efficient prediction pipeline
 * - Enhanced error handling and logging
 */
class Keyboard2 : InputMethodService(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    SuggestionBar.OnSuggestionSelectedListener {

    companion object {
        private const val TAG = "Keyboard2"
    }

    // Core components
    private lateinit var keyboardView: Keyboard2View
    private lateinit var keyEventHandler: KeyEventHandler
    private lateinit var config: Config
    private lateinit var handler: Handler
    private lateinit var neuralEngine: NeuralSwipeEngine

    // Layout management
    private var currentSpecialLayout: KeyboardData? = null
    private lateinit var localeTextLayout: KeyboardData

    // UI components
    private var emojiPane: ViewGroup? = null
    private var clipboardPane: ViewGroup? = null
    private var suggestionBar: SuggestionBar? = null
    private var inputViewContainer: LinearLayout? = null

    // Swipe prediction
    private val currentWord = StringBuilder()
    private val contextWords = mutableListOf<String>()
    private var wasLastInputSwipe = false

    // Service state
    var actionId: Int = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Receiver for handling keyboard events
    private inner class EventReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Save state when screen turns off
                    saveCurrentState()
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    // Handle configuration changes
                    refreshConfig()
                }
            }
        }
    }

    private val eventReceiver = EventReceiver()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CleverKeys service starting")

        try {
            initializeService()
            Log.d(TAG, "CleverKeys service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CleverKeys service", e)
        }
    }

    private fun initializeService() {
        // Initialize handler
        handler = Handler(Looper.getMainLooper())

        // Initialize configuration
        val prefs = getSharedPreferences("cleverkeys_prefs", Context.MODE_PRIVATE)
        keyEventHandler = KeyEventHandler(EventReceiver())

        Config.initGlobalConfig(prefs, resources, keyEventHandler, false)
        config = Config.globalConfig()
        prefs.registerOnSharedPreferenceChangeListener(this)

        // Initialize keyboard view
        keyboardView = Keyboard2View(this).apply {
            setKeyboardService(this@Keyboard2)
        }

        // Load default layout
        localeTextLayout = loadDefaultLayout()
        keyboardView.setKeyboard(currentLayout())

        // Initialize neural engine
        serviceScope.launch {
            try {
                neuralEngine = NeuralSwipeEngine.getInstance()
                Log.d(TAG, "Neural engine initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize neural engine", e)
            }
        }

        // Register for system events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        registerReceiver(eventReceiver, filter)

        // Initialize other services
        ClipboardHistoryService.on_startup(this, keyEventHandler)
    }

    private fun loadDefaultLayout(): KeyboardData {
        return KeyboardData.load(resources, R.layout.keyboard)
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "Creating input view")

        // Create container for keyboard and suggestion bar
        inputViewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Add suggestion bar if enabled
        if (config.word_prediction_enabled || config.swipe_typing_enabled) {
            createSuggestionBar()
        }

        // Add keyboard view
        inputViewContainer?.addView(keyboardView)

        return inputViewContainer ?: keyboardView
    }

    private fun createSuggestionBar() {
        suggestionBar = SuggestionBar(this).apply {
            setOnSuggestionSelectedListener(this@Keyboard2)
        }
        inputViewContainer?.addView(suggestionBar, 0) // Add at top
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "Starting input view")

        refreshConfig()
        refreshActionLabel(info)
        currentSpecialLayout = refreshSpecialLayout(info)
        keyboardView.setKeyboard(currentLayout())
        keyEventHandler.started(info)

        // Reset input state
        currentWord.clear()
        contextWords.clear()
        wasLastInputSwipe = false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "Finishing input view")
        saveCurrentState()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(eventReceiver)
            serviceScope.cancel()
            neuralEngine.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    // Layout management
    fun currentLayoutUnmodified(): KeyboardData {
        return currentSpecialLayout ?: run {
            val layoutIndex = config.get_current_layout()
            val layouts = config.layouts
            when {
                layoutIndex < layouts.size -> layouts[layoutIndex]
                else -> localeTextLayout
            }
        }
    }

    fun currentLayout(): KeyboardData {
        return currentSpecialLayout ?: LayoutModifier.modify_layout(currentLayoutUnmodified())
    }

    fun setTextLayout(layoutIndex: Int) {
        config.set_current_layout(layoutIndex)
        currentSpecialLayout = null
        keyboardView.setKeyboard(currentLayout())
    }

    fun incrementTextLayout(delta: Int) {
        val size = config.layouts.size
        val newIndex = (config.get_current_layout() + delta + size) % size
        setTextLayout(newIndex)
    }

    fun setSpecialLayout(layout: KeyboardData) {
        currentSpecialLayout = layout
        keyboardView.setKeyboard(layout)
    }

    private fun refreshSpecialLayout(info: EditorInfo?): KeyboardData? {
        if (info == null) return null

        val inputType = info.inputType
        return when {
            isPasswordField(inputType) -> loadLayout(R.xml.pin)
            isNumericField(inputType) -> {
                when (config.selected_number_layout) {
                    NumberLayout.PIN -> loadLayout(R.xml.pin)
                    NumberLayout.NUMBER -> loadNumpadLayout(R.xml.numeric)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun isPasswordField(inputType: Int): Boolean {
        return (inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
               (inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0 ||
               (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0
    }

    private fun isNumericField(inputType: Int): Boolean {
        return (inputType and InputType.TYPE_CLASS_NUMBER) != 0
    }

    fun loadLayout(layoutId: Int): KeyboardData {
        return KeyboardData.load(resources, layoutId)
    }

    fun loadNumpadLayout(layoutId: Int): KeyboardData {
        return LayoutModifier.modify_numpad(
            KeyboardData.load(resources, layoutId),
            currentLayoutUnmodified()
        )
    }

    private fun refreshActionLabel(info: EditorInfo?) {
        if (info == null) return

        config.actionLabel = when (info.actionLabel) {
            null -> getActionName(info.imeOptions)
            else -> info.actionLabel.toString()
        }
        config.actionId = info.actionId
        config.swapEnterActionKey = shouldSwapEnterActionKey(info.imeOptions)
    }

    private fun getActionName(imeOptions: Int): String {
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.key_action_next)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.key_action_done)
            EditorInfo.IME_ACTION_GO -> getString(R.string.key_action_go)
            EditorInfo.IME_ACTION_PREVIOUS -> getString(R.string.key_action_prev)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.key_action_search)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.key_action_send)
            else -> ""
        }
    }

    private fun shouldSwapEnterActionKey(imeOptions: Int): Boolean {
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE -> false
            else -> true
        }
    }

    // Neural prediction handling
    fun handleSwipePrediction(prediction: PredictionResult) {
        serviceScope.launch {
            try {
                val candidates = prediction.candidates.take(5) // Limit to top 5
                val suggestions = candidates.map { it.word }

                suggestionBar?.setSuggestions(suggestions)

                Log.d(TAG, "Updated suggestions: $suggestions")
                wasLastInputSwipe = true

                // Auto-commit first suggestion if confidence is high
                val topCandidate = candidates.firstOrNull()
                if (topCandidate != null && topCandidate.confidence > 0.8f) {
                    // Could auto-commit here, but let user choose for now
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling swipe prediction", e)
            }
        }
    }

    // SuggestionBar.OnSuggestionSelectedListener implementation
    override fun onSuggestionSelected(suggestion: String) {
        try {
            val ic = currentInputConnection
            if (ic != null) {
                // Clear any existing composition
                ic.finishComposingText()

                // If last input was swipe, replace any partial text
                if (wasLastInputSwipe && currentWord.isNotEmpty()) {
                    ic.deleteSurroundingText(currentWord.length, 0)
                }

                // Insert the selected word with a space
                ic.commitText("$suggestion ", 1)

                // Update context
                contextWords.add(suggestion)
                if (contextWords.size > 3) {
                    contextWords.removeAt(0)
                }

                currentWord.clear()
                wasLastInputSwipe = false

                // Clear suggestions
                suggestionBar?.clearSuggestions()

                Log.d(TAG, "Committed suggestion: $suggestion")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error committing suggestion", e)
        }
    }

    // Configuration management
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(TAG, "Preference changed: $key")
        refreshConfig()
    }

    private fun refreshConfig() {
        serviceScope.launch {
            try {
                // Refresh global config
                config = Config.globalConfig()

                // Update keyboard view if needed
                keyboardView.reset()

                // Recreate suggestion bar if prediction settings changed
                if (config.word_prediction_enabled || config.swipe_typing_enabled) {
                    if (suggestionBar == null && inputViewContainer != null) {
                        createSuggestionBar()
                    }
                } else {
                    suggestionBar?.let { bar ->
                        inputViewContainer?.removeView(bar)
                        suggestionBar = null
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing config", e)
            }
        }
    }

    private fun saveCurrentState() {
        // Save any pending state
        Log.d(TAG, "Saving current state")
    }

    // Utility methods
    private fun inflateView(layoutId: Int): View {
        return layoutInflater.inflate(layoutId, null)
    }

    // Event receiver implementation
    private inner class EventReceiver {
        fun key_down(keyValue: KeyValue, isSwipe: Boolean) {
            // Handle key down events
            Log.d(TAG, "Key down: $keyValue (swipe: $isSwipe)")
        }

        fun key_up(keyValue: KeyValue, modifiers: Pointers.Modifiers) {
            // Handle key up events
            Log.d(TAG, "Key up: $keyValue")

            // Handle text input
            if (!isSwipe(keyValue)) {
                handleRegularKeyInput(keyValue)
            }
        }

        fun mods_changed(modifiers: Pointers.Modifiers) {
            // Handle modifier changes
            Log.d(TAG, "Modifiers changed: $modifiers")
        }
    }

    private fun isSwipe(keyValue: KeyValue): Boolean {
        // Determine if this was a swipe gesture based on key value
        return false // Simplified for now
    }

    private fun handleRegularKeyInput(keyValue: KeyValue) {
        try {
            val ic = currentInputConnection ?: return
            val text = keyValue.toString()

            if (text.isNotEmpty()) {
                ic.commitText(text, 1)
                currentWord.append(text)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling regular key input", e)
        }
    }
}