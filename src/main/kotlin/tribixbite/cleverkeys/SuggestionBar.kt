package tribixbite.cleverkeys

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * View component that displays word suggestions above the keyboard
 */
class SuggestionBar : LinearLayout {
    private val suggestionViews: MutableList<TextView> = mutableListOf()
    private var listener: OnSuggestionSelectedListener? = null
    private val currentSuggestions: MutableList<String> = mutableListOf()
    private val currentScores: MutableList<Int> = mutableListOf()
    private var selectedIndex = -1
    private val theme: Theme?
    private var showDebugScores = false
    private var opacity = 90 // default opacity
    private var alwaysVisible = true // Keep bar visible even when empty (default enabled)

    fun interface OnSuggestionSelectedListener {
        fun onSuggestionSelected(word: String)
    }

    constructor(context: Context) : this(context, null as AttributeSet?)

    constructor(context: Context, theme: Theme) : super(context) {
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
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(0, 0, dpToPx(context, 4), 0) // Small right margin
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

            // Use theme label color for text with fallback
            setTextColor(if (theme?.labelColor != 0) {
                theme?.labelColor ?: Color.WHITE
            } else {
                // Fallback to white text if theme not initialized
                Color.WHITE
            })

            setPadding(dpToPx(context, 12), 0, dpToPx(context, 12), 0)
            maxLines = 2
            isClickable = true
            isFocusable = true
            minWidth = dpToPx(context, 80) // Minimum width for better touch targets

            // Set click listener
            setOnClickListener {
                if (index < currentSuggestions.size) {
                    // Record selection statistics for neural predictions
                    NeuralPerformanceStats.getInstance(context).recordSelection(index)

                    listener?.onSuggestionSelected(currentSuggestions[index])
                }
            }
        }
    }

    private fun createDivider(context: Context): View {
        return View(context).apply {
            layoutParams = LayoutParams(
                dpToPx(context, 1),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(0, dpToPx(context, 4), 0, dpToPx(context, 4))
            }

            // Use theme sublabel color with some transparency for divider
            val dividerColor = theme?.subLabelColor ?: Color.GRAY
            setBackgroundColor(Color.argb(
                100,
                Color.red(dividerColor),
                Color.green(dividerColor),
                Color.blue(dividerColor)
            ))
        }
    }

    /**
     * Set whether to show debug scores
     */
    fun setShowDebugScores(show: Boolean) {
        showDebugScores = show
    }

    /**
     * Set whether the suggestion bar should always remain visible
     * This prevents UI rerendering issues from constant appear/disappear
     */
    fun setAlwaysVisible(alwaysVisible: Boolean) {
        this.alwaysVisible = alwaysVisible
        if (this.alwaysVisible) {
            visibility = VISIBLE
        }
    }

    /**
     * Set the opacity of the suggestion bar
     * @param opacity Opacity value from 0 to 100
     */
    fun setOpacity(opacity: Int) {
        this.opacity = max(0, min(100, opacity))
        updateBackgroundOpacity()
    }

    /**
     * Update the background color with the current opacity
     */
    private fun updateBackgroundOpacity() {
        // Calculate alpha value from opacity percentage (0-100 -> 0-255)
        val alpha = (opacity * 255) / 100

        // Use theme colors with user-defined opacity
        if (theme?.colorKey != 0) {
            val bgColor = theme?.colorKey ?: Color.DKGRAY
            setBackgroundColor(
                Color.argb(
                    alpha,
                    Color.red(bgColor),
                    Color.green(bgColor),
                    Color.blue(bgColor)
                )
            )
        } else {
            // Fallback colors if theme is not properly initialized
            setBackgroundColor(Color.argb(alpha, 50, 50, 50)) // Dark grey background
        }
    }

    /**
     * Update the displayed suggestions
     */
    fun setSuggestions(suggestions: List<String>?) {
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
        try {
            currentSuggestions.forEachIndexed { i, suggestion ->
                // Add divider before each suggestion except the first
                if (i > 0) {
                    addView(createDivider(context))
                }

                // Add debug score if enabled and available
                val displayText = if (showDebugScores && i < currentScores.size && currentScores.isNotEmpty()) {
                    "$suggestion\n${currentScores[i]}"
                } else {
                    suggestion
                }

                val textView = createSuggestionView(context, i).apply {
                    text = displayText

                    // Highlight first suggestion with activated color
                    if (i == 0) {
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(theme?.activatedColor?.takeIf { it != 0 } ?: Color.CYAN)
                    } else {
                        typeface = Typeface.DEFAULT
                        setTextColor(theme?.labelColor?.takeIf { it != 0 } ?: Color.WHITE)
                    }
                }

                // Remove from parent if already attached
                (textView.parent as? ViewGroup)?.removeView(textView)
                addView(textView)
                suggestionViews.add(textView)
            }
        } catch (e: Exception) {
            Log.e("SuggestionBar", "Error updating suggestion views: ${e.message}")
        }

        // Show or hide the entire bar based on suggestions (unless always visible mode)
        // NOTE: Visibility is now controlled by the parent HorizontalScrollView
        visibility = if (alwaysVisible) {
            VISIBLE // Always keep visible to prevent UI rerendering
        } else {
            if (currentSuggestions.isEmpty()) GONE else VISIBLE
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
    fun setOnSuggestionSelectedListener(listener: OnSuggestionSelectedListener?) {
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
     * Get the middle suggestion (index 2 for 5 suggestions, or first if fewer)
     * Used for auto-insertion on consecutive swipes
     */
    fun getMiddleSuggestion(): String? {
        if (currentSuggestions.isEmpty()) {
            return null
        }

        // Return middle suggestion (index 2 for 5 suggestions)
        // Or first suggestion if we have fewer than 3
        val middleIndex = min(2, currentSuggestions.size / 2)
        return currentSuggestions[middleIndex]
    }

    /**
     * Convert dp to pixels
     */
    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return round(dp * density).toInt()
    }
}
