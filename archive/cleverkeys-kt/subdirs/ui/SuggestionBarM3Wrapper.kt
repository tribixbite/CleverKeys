package tribixbite.cleverkeys.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.theme.KeyboardTheme

/**
 * View-based wrapper for Material 3 SuggestionBar (Composable).
 *
 * Provides the same API as old SuggestionBar.kt for seamless integration
 * into existing View-based architecture.
 *
 * **FIX #P0-COMPOSE-LIFECYCLE**: Disabled lifecycle-aware recomposer
 * to fix "ViewTreeLifecycleOwner not found" crash when using Compose in IME.
 * IME windows don't have lifecycle owners, so we use the basic recomposer.
 *
 * Usage:
 * ```kotlin
 * val suggestionBar = SuggestionBarM3Wrapper(context)
 * suggestionBar.setOnSuggestionSelectedListener { word ->
 *     // Handle suggestion tap
 * }
 * suggestionBar.setSuggestions(listOf("hello", "world", "test"))
 * ```
 */
class SuggestionBarM3Wrapper(context: Context) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner {

    // Mutable state for suggestions (use .value to access/modify)
    private val currentSuggestions = mutableStateOf<List<Suggestion>>(emptyList())
    private var onSuggestionSelected: ((String) -> Unit)? = null
    private var onSuggestionDismissed: ((String) -> Unit)? = null

    // Word info dialog state (v2.1 Priority 2 Feature #4)
    private val showWordInfoDialog = mutableStateOf(false)
    private val wordInfoDialogWord = mutableStateOf("")
    private val wordInfoDialogConfidence = mutableStateOf<Float?>(null)

    // Custom recomposer for IME context (no lifecycle owner required)
    // AndroidUiDispatcher.Main provides MonotonicFrameClock required by Compose
    private val recomposer = Recomposer(AndroidUiDispatcher.Main)
    private val recomposerScope = CoroutineScope(AndroidUiDispatcher.Main)

    // Lifecycle support for Compose in IME context
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    init {
        // Initialize lifecycle to CREATED then STARTED then RESUMED
        // This is required for Compose to work properly
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        android.util.Log.d("SuggestionBar", "Lifecycle initialized to RESUMED state")

        // Start the recomposer
        recomposerScope.launch {
            recomposer.runRecomposeAndApplyChanges()
        }

        // Create ComposeView with custom Recomposer
        // This fixes "ViewTreeLifecycleOwner not found" crash in IME context
        // by providing our own composition context that doesn't require lifecycle
        val composeView = object : AbstractComposeView(context) {
            @Composable
            override fun Content() {
                KeyboardTheme {
                    SuggestionBarM3(
                        suggestions = currentSuggestions.value,
                        onSuggestionClick = { word ->
                            onSuggestionSelected?.invoke(word)
                        },
                        onSuggestionLongPress = { word ->
                            // v2.1 Priority 2 Feature #4: Show word info dialog on long press
                            showWordInfoDialog(word)
                        },
                        onSuggestionDismiss = { word ->
                            onSuggestionDismissed?.invoke(word)
                            android.util.Log.d("SuggestionBar", "Dismissed: $word")
                        }
                    )

                    // Word info dialog (v2.1 Priority 2 Feature #4)
                    if (showWordInfoDialog.value) {
                        WordInfoDialog(
                            word = wordInfoDialogWord.value,
                            confidence = wordInfoDialogConfidence.value,
                            source = "Neural Prediction",
                            onDismiss = {
                                showWordInfoDialog.value = false
                            },
                            onInsertWord = { word ->
                                // Insert word and close dialog
                                onSuggestionSelected?.invoke(word)
                                showWordInfoDialog.value = false
                            }
                        )
                    }
                }
            }
        }.apply {
            // Set ViewTree owners on the AbstractComposeView
            // This is CRITICAL: AndroidComposeView.onAttachedToWindow() checks for these
            setViewTreeLifecycleOwner(this@SuggestionBarM3Wrapper)
            setViewTreeSavedStateRegistryOwner(this@SuggestionBarM3Wrapper)

            // Set custom composition context
            setParentCompositionContext(recomposer)
            android.util.Log.d("SuggestionBar", "✅ ViewTree lifecycle owners and Recomposer set on AbstractComposeView")
        }

        // Add ComposeView to FrameLayout
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Clean up the recomposer and lifecycle when the view is detached
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        recomposer.cancel()
        android.util.Log.d("SuggestionBar", "Lifecycle destroyed and recomposer cancelled on detach")
    }

    /**
     * Set suggestion words - compatibility API matching old SuggestionBar.
     *
     * Converts simple String list to rich Suggestion objects.
     * Assumes medium confidence (0.6) for all words unless specified.
     *
     * @param words List of suggestion words
     */
    fun setSuggestions(words: List<String>) {
        currentSuggestions.value = words.toSuggestions(confidence = 0.6f)
        android.util.Log.d("SuggestionBar", "Setting ${words.size} suggestions")
    }

    /**
     * Set suggestions with confidence scores.
     *
     * Enhanced API for neural prediction results.
     *
     * @param suggestions List of Suggestion objects with confidence scores
     */
    fun setSuggestionsWithConfidence(suggestions: List<Suggestion>) {
        currentSuggestions.value = suggestions
        android.util.Log.d("SuggestionBar", "Setting ${suggestions.size} suggestions with confidence")
    }

    /**
     * Clear all suggestions.
     */
    fun clearSuggestions() {
        currentSuggestions.value = emptyList()
        android.util.Log.d("SuggestionBar", "Clearing suggestions")
    }

    /**
     * Set suggestion selection callback - compatibility API.
     *
     * @param listener Callback invoked when user taps a suggestion
     */
    fun setOnSuggestionSelectedListener(listener: (String) -> Unit) {
        onSuggestionSelected = listener
    }

    /**
     * Set suggestion dismiss callback - v2.1 swipe-to-dismiss feature.
     *
     * @param listener Callback invoked when user swipes to dismiss a suggestion
     */
    fun setOnSuggestionDismissListener(listener: (String) -> Unit) {
        onSuggestionDismissed = listener
    }

    /**
     * Show word info dialog for a suggestion.
     * v2.1 Priority 2 Feature #4: Long-press word info.
     *
     * @param word The word to show information for
     */
    private fun showWordInfoDialog(word: String) {
        // Find suggestion to get confidence score
        val suggestion = currentSuggestions.value.find { it.word == word }

        wordInfoDialogWord.value = word
        wordInfoDialogConfidence.value = suggestion?.confidence
        showWordInfoDialog.value = true

        android.util.Log.d("SuggestionBar", "Showing word info for: $word (confidence: ${suggestion?.confidence})")
    }
}
