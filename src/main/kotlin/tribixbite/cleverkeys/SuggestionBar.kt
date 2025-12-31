package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    // Password mode properties
    private var isPasswordMode = false
    private var isPasswordVisible = false
    private var currentPasswordText = StringBuilder()
    private var passwordContainer: RelativeLayout? = null
    private var passwordTextView: TextView? = null
    private var eyeToggleView: ImageView? = null
    private var inputConnectionProvider: InputConnectionProvider? = null

    /**
     * Interface for providing InputConnection to read actual field content.
     */
    fun interface InputConnectionProvider {
        fun getInputConnection(): InputConnection?
    }

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

        // Ensure minimum width to prevent UI collapse when empty
        // Without this, the bar appears as just a few pixels when there are no suggestions
        minimumWidth = dpToPx(context, 200)

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
        // Skip suggestion updates in password mode - don't show predictions
        if (isPasswordMode) {
            return
        }

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
        // Don't clear password mode views
        if (isPasswordMode) {
            return
        }

        // ALWAYS show empty suggestions instead of hiding - prevents UI disappearing
        setSuggestions(emptyList())
        Log.d(TAG, "clearSuggestions called - showing empty list instead of hiding")
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

    // ==================== Password Mode Methods ====================

    companion object {
        private const val TAG = "SuggestionBar"

        /**
         * Check if the given EditorInfo indicates a password or PIN field.
         * Detects all standard Android password input types.
         */
        @JvmStatic
        fun isPasswordField(info: EditorInfo?): Boolean {
            if (info == null) return false

            val inputType = info.inputType
            val typeClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION

            // Check for text password variations
            if (typeClass == InputType.TYPE_CLASS_TEXT) {
                when (variation) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> return true
                }
            }

            // Check for numeric PIN/password
            if (typeClass == InputType.TYPE_CLASS_NUMBER) {
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    return true
                }
            }

            // Also check for TYPE_TEXT_FLAG_NO_SUGGESTIONS as indicator of sensitive field
            // but don't treat as password (no eye toggle needed)

            return false
        }
    }

    /**
     * Set the InputConnection provider for reading actual field content.
     * This allows accurate password display even when cursor is moved.
     */
    fun setInputConnectionProvider(provider: InputConnectionProvider?) {
        inputConnectionProvider = provider
    }

    /**
     * Enable or disable password mode.
     * In password mode, predictions are hidden and an eye toggle is shown.
     */
    fun setPasswordMode(enabled: Boolean) {
        if (isPasswordMode == enabled) return

        isPasswordMode = enabled
        isPasswordVisible = false
        currentPasswordText.clear()

        if (enabled) {
            setupPasswordModeViews()
        } else {
            clearPasswordModeViews()
        }

        Log.d(TAG, "Password mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if currently in password mode.
     */
    fun isInPasswordMode(): Boolean = isPasswordMode

    /**
     * Update the password text being typed.
     * Now syncs with InputConnection for accuracy.
     */
    fun updatePasswordText(text: String) {
        if (!isPasswordMode) return
        syncPasswordWithField()
    }

    /**
     * Append a character to the password text.
     * Now syncs with InputConnection for accuracy.
     */
    fun appendPasswordChar(char: Char) {
        if (!isPasswordMode) return
        syncPasswordWithField()
    }

    /**
     * Delete the last character from password text.
     * Now syncs with InputConnection for accuracy.
     */
    fun deletePasswordChar() {
        if (!isPasswordMode) return
        syncPasswordWithField()
    }

    /**
     * Clear the password text (e.g., when field is cleared or focus changes).
     */
    fun clearPasswordText() {
        currentPasswordText.clear()
        updatePasswordDisplay()
    }

    /**
     * Setup the password mode views using RelativeLayout for true fixed positioning.
     * Key insight: Use START_OF constraint to make scroll view end where icon begins.
     * This prevents the scroll view content from pushing the icon off screen.
     */
    private fun setupPasswordModeViews() {
        // Clear any existing suggestion views
        removeAllViews()
        suggestionViews.clear()

        val iconSize = dpToPx(context, 36)
        val iconMargin = dpToPx(context, 8)

        // Create RelativeLayout container - this is key for proper constraint-based positioning
        passwordContainer = RelativeLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        // Create eye toggle FIRST and assign ID (needed for START_OF constraint)
        // Anchored to ALIGN_PARENT_END so it NEVER moves
        eyeToggleView = ImageView(context).apply {
            id = View.generateViewId()  // ID required for RelativeLayout rules

            val params = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)  // Fixed to right edge
                addRule(RelativeLayout.CENTER_VERTICAL)   // Centered vertically
                marginEnd = iconMargin
                marginStart = iconMargin
            }
            layoutParams = params

            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Toggle password visibility"

            // Set initial icon (visibility off = hidden)
            setImageDrawable(getVisibilityDrawable(false))
            // Apply theme color
            setColorFilter(theme?.subLabelColor?.takeIf { it != 0 } ?: Color.GRAY)

            setOnClickListener {
                togglePasswordVisibility()
            }

            // Add ripple effect
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        // Create HorizontalScrollView constrained to START_OF the icon
        // This creates a fixed boundary - content scrolls within, icon stays put
        val scrollView = HorizontalScrollView(context).apply {
            id = View.generateViewId()

            val params = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)  // Start at left edge
                addRule(RelativeLayout.START_OF, eyeToggleView!!.id)  // End where icon begins
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = params

            isHorizontalScrollBarEnabled = false // Hide scrollbar (cleaner UI)
            isClickable = true // Ensure touch events are caught
            isFocusable = true

            // fillViewport = true: stretches child to fill width when content is short (enables centering)
            // When content is long, child exceeds width and becomes scrollable
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Wrapper for centering
        val contentWrapper = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Revert to TextView but with touch interception fix on parent ScrollView
        passwordTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            
            setPadding(dpToPx(context, 16), 0, dpToPx(context, 16), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(theme?.labelColor?.takeIf { it != 0 } ?: Color.WHITE)
            typeface = Typeface.MONOSPACE
            text = ""
            letterSpacing = 0.15f
            
            // Single line settings
            maxLines = 1
            setHorizontallyScrolling(true)
            movementMethod = null
        }

        contentWrapper.addView(passwordTextView)
        scrollView.addView(contentWrapper)

        // CRITICAL FIX: Prevent parent keyboards/gesture detectors from stealing scroll events
        scrollView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false // Let ScrollView handle the actual scrolling
        }
        passwordContainer?.addView(eyeToggleView)   // Add icon first
        passwordContainer?.addView(scrollView)       // Add scroll view second

        addView(passwordContainer)
        updatePasswordDisplay()
    }

    /**
     * Get the appropriate visibility drawable based on state.
     */
    private fun getVisibilityDrawable(visible: Boolean): Drawable? {
        val resId = if (visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
        return ContextCompat.getDrawable(context, resId)
    }

    /**
     * Toggle password visibility and update the display.
     * Always reads actual text from InputConnection for accuracy.
     */
    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible

        // Always sync with actual field content when toggling
        refreshPasswordFromField()

        updatePasswordDisplay()

        // Update eye icon drawable and color
        eyeToggleView?.apply {
            setImageDrawable(getVisibilityDrawable(isPasswordVisible))
            val color = if (isPasswordVisible) {
                theme?.activatedColor?.takeIf { it != 0 } ?: Color.CYAN
            } else {
                theme?.subLabelColor?.takeIf { it != 0 } ?: Color.GRAY
            }
            setColorFilter(color)
        }

        Log.d(TAG, "Password visibility toggled: ${if (isPasswordVisible) "visible" else "hidden"}, ${currentPasswordText.length} chars")
    }

    /**
     * Read the actual password text from the input field via InputConnection.
     * This ensures accuracy for select-all+delete, cursor movement, etc.
     */
    private fun refreshPasswordFromField() {
        val ic = inputConnectionProvider?.getInputConnection() ?: return

        try {
            // Get text before and after cursor, combine for full content
            val beforeCursor = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
            val afterCursor = ic.getTextAfterCursor(1000, 0)?.toString() ?: ""
            val fullText = beforeCursor + afterCursor

            currentPasswordText.clear()
            currentPasswordText.append(fullText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read password from field", e)
        }
    }

    /**
     * Sync password display with actual field content.
     * Called after any input operation to handle select-all+delete, etc.
     */
    fun syncPasswordWithField() {
        if (!isPasswordMode) return
        refreshPasswordFromField()
        updatePasswordDisplay()
    }

    /**
     * Update the password display based on visibility state.
     * Shows dots (●) when hidden, actual text when visible.
     */
    private fun updatePasswordDisplay() {
        passwordTextView?.apply {
            if (currentPasswordText.isEmpty()) {
                text = ""
                visibility = View.VISIBLE  // Keep visible for layout stability
            } else if (isPasswordVisible) {
                // Show actual password text
                text = currentPasswordText.toString()
                visibility = View.VISIBLE
            } else {
                // Show dots for hidden password
                text = "●".repeat(currentPasswordText.length)
                visibility = View.VISIBLE
            }
        }
    }

    /**
     * Clear password mode views and restore normal suggestion bar behavior.
     */
    private fun clearPasswordModeViews() {
        passwordContainer = null
        passwordTextView = null
        eyeToggleView = null
        currentPasswordText.clear()
        isPasswordVisible = false

        // Clear all views - suggestions will be recreated as needed
        removeAllViews()
        suggestionViews.clear()
    }
}
