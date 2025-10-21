package tribixbite.keyboard2.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import tribixbite.keyboard2.theme.KeyboardTheme

/**
 * View-based wrapper for Material 3 SuggestionBar (Composable).
 *
 * Provides the same API as old SuggestionBar.kt for seamless integration
 * into existing View-based architecture.
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
class SuggestionBarM3Wrapper(context: Context) : FrameLayout(context) {

    // Mutable state for suggestions (use .value to access/modify)
    private val currentSuggestions = mutableStateOf<List<Suggestion>>(emptyList())
    private var onSuggestionSelected: ((String) -> Unit)? = null

    private val composeView: ComposeView

    init {
        composeView = ComposeView(context).apply {
            setContent {
                KeyboardTheme {
                    SuggestionBarM3(
                        suggestions = currentSuggestions.value,
                        onSuggestionClick = { word ->
                            onSuggestionSelected?.invoke(word)
                        },
                        onSuggestionLongPress = { word ->
                            // TODO: Show word info dialog
                            android.util.Log.d("SuggestionBar", "Long press on: $word")
                        }
                    )
                }
            }
        }

        // Add ComposeView to FrameLayout
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
}
