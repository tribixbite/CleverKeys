package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
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

    // Password mode properties
    private var isPasswordMode = false
    private var isPasswordVisible = false
    private var currentPasswordText = StringBuilder()
    private var passwordTextView: TextView? = null
    private var passwordScrollView: HorizontalScrollView? = null
    private var eyeToggleView: View? = null
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
     * Called when user types or deletes characters in a password field.
     */
    fun updatePasswordText(text: String) {
        if (!isPasswordMode) return

        currentPasswordText.clear()
        currentPasswordText.append(text)

        updatePasswordDisplay()
    }

    /**
     * Append a character to the password text.
     */
    fun appendPasswordChar(char: Char) {
        if (!isPasswordMode) return

        currentPasswordText.append(char)
        updatePasswordDisplay()
    }

    /**
     * Delete the last character from password text.
     */
    fun deletePasswordChar() {
        if (!isPasswordMode || currentPasswordText.isEmpty()) return

        currentPasswordText.deleteCharAt(currentPasswordText.length - 1)
        updatePasswordDisplay()
    }

    /**
     * Clear the password text (e.g., when field is cleared or focus changes).
     */
    fun clearPasswordText() {
        currentPasswordText.clear()
        updatePasswordDisplay()
    }

    /**
     * Setup the password mode views (eye toggle fixed on right, scrollable password text).
     */
    private fun setupPasswordModeViews() {
        // Clear any existing suggestion views
        removeAllViews()
        suggestionViews.clear()

        // Create scrollable container for password text (takes remaining space)
        passwordScrollView = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Create password text view inside scroll view
        passwordTextView = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dpToPx(context, 12), 0, dpToPx(context, 8), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme?.labelColor?.takeIf { it != 0 } ?: Color.WHITE)
            typeface = Typeface.MONOSPACE  // Monospace for password display
            text = ""  // Empty initially
            visibility = View.GONE  // Hidden until eye toggle is pressed
            isSingleLine = true  // Ensure single line for horizontal scroll
        }
        passwordScrollView?.addView(passwordTextView)
        addView(passwordScrollView)

        // Create eye toggle button (fixed on right, never scrolls)
        eyeToggleView = EyeIconView(context, theme).apply {
            layoutParams = LayoutParams(
                dpToPx(context, 44),
                LayoutParams.MATCH_PARENT
            )
            setPadding(dpToPx(context, 8), dpToPx(context, 6), dpToPx(context, 10), dpToPx(context, 6))
            isClickable = true
            isFocusable = true
            setEyeOpen(false)  // Start with eye closed (password hidden)

            setOnClickListener {
                togglePasswordVisibility()
            }

            // Add ripple effect
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        addView(eyeToggleView)

        updatePasswordDisplay()
    }

    /**
     * Custom view that draws an eye icon with optional diagonal line (slash).
     */
    private class EyeIconView(context: Context, private val theme: Theme?) : View(context) {
        private var isEyeOpen = false
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun setEyeOpen(open: Boolean) {
            isEyeOpen = open
            updateColors()
            invalidate()
        }

        private fun updateColors() {
            val color = if (isEyeOpen) {
                theme?.activatedColor?.takeIf { it != 0 } ?: Color.CYAN
            } else {
                theme?.subLabelColor?.takeIf { it != 0 } ?: Color.GRAY
            }
            eyePaint.color = color
            slashPaint.color = color
            pupilPaint.color = color
        }

        init {
            updateColors()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2
            val cy = h / 2
            val eyeWidth = w * 0.7f
            val eyeHeight = h * 0.35f

            // Draw eye outline (almond shape using bezier curves)
            val path = Path()
            path.moveTo(cx - eyeWidth / 2, cy)
            // Top curve
            path.cubicTo(
                cx - eyeWidth / 4, cy - eyeHeight,
                cx + eyeWidth / 4, cy - eyeHeight,
                cx + eyeWidth / 2, cy
            )
            // Bottom curve
            path.cubicTo(
                cx + eyeWidth / 4, cy + eyeHeight,
                cx - eyeWidth / 4, cy + eyeHeight,
                cx - eyeWidth / 2, cy
            )
            canvas.drawPath(path, eyePaint)

            // Draw pupil (circle in center)
            val pupilRadius = eyeHeight * 0.5f
            canvas.drawCircle(cx, cy, pupilRadius, pupilPaint)

            // Draw inner highlight (small circle offset)
            pupilPaint.color = Color.argb(100, 255, 255, 255)
            canvas.drawCircle(cx - pupilRadius * 0.3f, cy - pupilRadius * 0.3f, pupilRadius * 0.25f, pupilPaint)
            updateColors()  // Reset pupil color

            // Draw diagonal slash when eye is closed (password hidden)
            if (!isEyeOpen) {
                val slashMargin = w * 0.15f
                canvas.drawLine(
                    slashMargin, h - slashMargin,
                    w - slashMargin, slashMargin,
                    slashPaint
                )
            }
        }
    }

    /**
     * Toggle password visibility and update the display.
     * When making visible, reads actual text from InputConnection for accuracy.
     */
    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible

        // When making password visible, read actual content from field
        // This handles cases where cursor was moved before backspace
        if (isPasswordVisible) {
            refreshPasswordFromField()
        }

        updatePasswordDisplay()

        // Update eye icon state
        (eyeToggleView as? EyeIconView)?.setEyeOpen(isPasswordVisible)

        Log.d(TAG, "Password visibility toggled: ${if (isPasswordVisible) "visible" else "hidden"}")
    }

    /**
     * Read the actual password text from the input field via InputConnection.
     * This ensures accuracy even when user moves cursor before deleting.
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

            Log.d(TAG, "Refreshed password from field: ${fullText.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read password from field", e)
        }
    }

    /**
     * Update the password display based on visibility state.
     */
    private fun updatePasswordDisplay() {
        passwordTextView?.apply {
            if (isPasswordVisible && currentPasswordText.isNotEmpty()) {
                text = currentPasswordText.toString()
                visibility = View.VISIBLE
            } else {
                text = ""
                visibility = View.GONE
            }
        }
    }

    /**
     * Clear password mode views and restore normal suggestion bar behavior.
     */
    private fun clearPasswordModeViews() {
        passwordTextView = null
        eyeToggleView = null
        currentPasswordText.clear()
        isPasswordVisible = false

        // Clear all views - suggestions will be recreated as needed
        removeAllViews()
        suggestionViews.clear()
    }
}
