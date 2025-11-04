package tribixbite.keyboard2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * View component that displays word suggestions above the keyboard.
 *
 * Features:
 * - Dynamic TextView creation for variable suggestion counts
 * - Theme integration for colors and styling
 * - Debug score display
 * - Configurable opacity (0-100%)
 * - "Always visible" mode to prevent UI reflow
 * - First suggestion highlighting
 * - Click listeners for suggestion selection
 * - Dividers between suggestions
 *
 * Usage:
 * ```kotlin
 * val suggestionBar = SuggestionBar(context, theme)
 * suggestionBar.setOnSuggestionSelectedListener { word ->
 *     // Handle suggestion selection
 * }
 * suggestionBar.setSuggestions(listOf("hello", "world", "test"))
 * ```
 *
 * Performance:
 * - Reuses Views when possible
 * - Lazy TextView creation
 * - Efficient layout updates
 *
 * Ported from Java to Kotlin with improvements.
 */
class SuggestionBar : LinearLayout {

    /**
     * Listener interface for suggestion selection events
     */
    fun interface OnSuggestionSelectedListener {
        fun onSuggestionSelected(word: String)
    }

    private val suggestionViews = mutableListOf<TextView>()
    private var listener: OnSuggestionSelectedListener? = null
    private val currentSuggestions = mutableListOf<String>()
    private val currentScores = mutableListOf<Int>()
    private var selectedIndex = -1
    private var theme: Theme? = null
    private var showDebugScores = false
    private var opacity = 90 // Default opacity
    private var alwaysVisible = true // Keep bar visible even when empty (default enabled)

    constructor(context: Context) : this(context, null as AttributeSet?)

    constructor(context: Context, theme: Theme?) : super(context) {
        this.theme = theme
        initialize(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        // Initialize theme to get colors
        theme = Theme(context, attrs)
        initialize(context)
    }

    private fun initialize(context: Context) {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        updateBackgroundOpacity()

        val padding = dpToPx(context, 8)
        setPadding(padding, padding, padding, padding)

        // Don't create fixed TextViews - they'll be created dynamically in setSuggestionsWithScores()
    }

    private fun createSuggestionView(context: Context, index: Int): TextView {
        return TextView(context).apply {
            // Use wrap_content for horizontal scrolling
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(0, 0, dpToPx(context, 4), 0) // Small right margin
            }

            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

            // Use theme label color for text with fallback
            val currentTheme = this@SuggestionBar.theme
            setTextColor(when {
                currentTheme != null && currentTheme.labelColor != 0 -> currentTheme.labelColor
                else -> Color.WHITE // Fallback to white text if theme not initialized
            })

            setPadding(dpToPx(context, 12), 0, dpToPx(context, 12), 0)
            maxLines = 2
            isClickable = true
            isFocusable = true
            minWidth = dpToPx(context, 80) // Minimum width for better touch targets

            // Set click listener
            setOnClickListener {
                if (index < currentSuggestions.size) {
                    listener?.onSuggestionSelected(currentSuggestions[index])
                }
            }
        }
    }

    private fun createDivider(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(context, 1),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(0, dpToPx(context, 4), 0, dpToPx(context, 4))
            }

            // Use theme sublabel color with some transparency for divider
            val dividerColor = theme?.subLabelColor ?: Color.GRAY
            val transparentDivider = Color.argb(
                100,
                Color.red(dividerColor),
                Color.green(dividerColor),
                Color.blue(dividerColor)
            )
            setBackgroundColor(transparentDivider)
        }
    }

    /**
     * Set whether to show debug scores
     */
    fun setShowDebugScores(show: Boolean) {
        showDebugScores = show
    }

    /**
     * Set whether the suggestion bar should always remain visible.
     * This prevents UI rerendering issues from constant appear/disappear.
     */
    fun setAlwaysVisible(alwaysVisible: Boolean) {
        this.alwaysVisible = alwaysVisible
        if (this.alwaysVisible) {
            visibility = View.VISIBLE
        }
    }

    /**
     * Set the opacity of the suggestion bar
     *
     * @param opacity Opacity value from 0 to 100
     */
    fun setOpacity(opacity: Int) {
        this.opacity = opacity.coerceIn(0, 100)
        updateBackgroundOpacity()
    }

    /**
     * Update the background color with the current opacity
     */
    private fun updateBackgroundOpacity() {
        // Calculate alpha value from opacity percentage (0-100 -> 0-255)
        val alpha = (opacity * 255) / 100

        // Use theme colors with user-defined opacity
        val currentTheme = theme
        val backgroundColor = when {
            currentTheme != null && currentTheme.colorKey != 0 -> {
                val themeColor = currentTheme.colorKey
                Color.argb(
                    alpha,
                    Color.red(themeColor),
                    Color.green(themeColor),
                    Color.blue(themeColor)
                )
            }
            else -> {
                // Fallback colors if theme is not properly initialized
                Color.argb(alpha, 50, 50, 50) // Dark grey background
            }
        }

        setBackgroundColor(backgroundColor)
    }

    /**
     * Update the displayed suggestions
     */
    fun setSuggestions(suggestions: List<String>) {
        setSuggestionsWithScores(suggestions, null)
    }

    /**
     * Update the displayed suggestions with scores
     */
    fun setSuggestionsWithScores(suggestions: List<String>?, scores: List<Int>?) {
        currentSuggestions.clear()
        currentScores.clear()

        if (suggestions != null) {
            currentSuggestions.addAll(suggestions)
            if (scores != null && scores.size == suggestions.size) {
                currentScores.addAll(scores)
            }
        }

        // Clear existing views and suggestion list
        removeAllViews()
        suggestionViews.clear()

        // Dynamically create TextViews for all suggestions
        currentSuggestions.forEachIndexed { i, suggestion ->
            // Add divider before each suggestion except the first
            if (i > 0) {
                val divider = createDivider(context)
                addView(divider)
            }

            // Build suggestion text
            var suggestionText = suggestion

            // Add debug score if enabled and available
            if (showDebugScores && i < currentScores.size && currentScores.isNotEmpty()) {
                val score = currentScores[i]
                suggestionText = "$suggestion\n$score"
            }

            // Create and configure TextView
            val textView = createSuggestionView(context, i)
            textView.text = suggestionText

            // Highlight first suggestion with activated color
            val currentTheme = theme
            if (i == 0) {
                textView.typeface = Typeface.DEFAULT_BOLD
                textView.setTextColor(
                    when {
                        currentTheme != null && currentTheme.activatedColor != 0 -> currentTheme.activatedColor
                        else -> Color.CYAN
                    }
                )
            } else {
                textView.typeface = Typeface.DEFAULT
                textView.setTextColor(
                    when {
                        currentTheme != null && currentTheme.labelColor != 0 -> currentTheme.labelColor
                        else -> Color.WHITE
                    }
                )
            }

            addView(textView)
            suggestionViews.add(textView)
        }

        // Show or hide the entire bar based on suggestions (unless always visible mode)
        // NOTE: Visibility is now controlled by the parent HorizontalScrollView
        visibility = when {
            alwaysVisible -> View.VISIBLE // Always keep visible to prevent UI rerendering
            currentSuggestions.isEmpty() -> View.GONE
            else -> View.VISIBLE
        }
    }

    /**
     * Clear all suggestions (MODIFIED: always keep bar visible when CGR active)
     */
    fun clearSuggestions() {
        // ALWAYS show empty suggestions instead of hiding - prevents UI disappearing
        setSuggestions(emptyList())
        Log.d("SuggestionBar", "clearSuggestions called - showing empty list instead of hiding")
    }

    /**
     * Set the listener for suggestion selection
     */
    fun setOnSuggestionSelectedListener(listener: OnSuggestionSelectedListener) {
        this.listener = listener
    }

    /**
     * Get the currently displayed suggestions
     */
    fun getCurrentSuggestions(): List<String> {
        return currentSuggestions.toList()
    }

    /**
     * Check if there are any suggestions currently displayed
     */
    fun hasSuggestions(): Boolean {
        return currentSuggestions.isNotEmpty()
    }

    /**
     * Get the top (highest scoring) suggestion for auto-insertion
     */
    fun getTopSuggestion(): String? {
        return currentSuggestions.firstOrNull()
    }

    /**
     * Get the middle suggestion (index 2 for 5 suggestions, or first if fewer).
     * Used for auto-insertion on consecutive swipes.
     */
    fun getMiddleSuggestion(): String? {
        if (currentSuggestions.isEmpty()) {
            return null
        }

        // Return middle suggestion (index 2 for 5 suggestions)
        // Or first suggestion if we have fewer than 3
        val middleIndex = minOf(2, currentSuggestions.size / 2)
        return currentSuggestions[middleIndex]
    }

    /**
     * Convert dp to pixels
     */
    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
