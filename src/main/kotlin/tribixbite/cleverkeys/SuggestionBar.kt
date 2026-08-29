package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
    // View pool for recycled suggestion TextViews. R3 (audit finding #5): instead
    // of removeAllViews() + new TextView per suggestion per keystroke, we keep this
    // pool sized to the current suggestion count, rebind text/typeface/color/click,
    // and add/remove only the delta. Children are laid out as
    // [sug0, div, sug1, div, sug2, …] so a parallel divider pool tracks the "div"
    // slots (N-1 of them for N suggestions).
    private val suggestionViews: MutableList<TextView> = mutableListOf()
    private val dividerViews: MutableList<View> = mutableListOf()
    private var listener: OnSuggestionSelectedListener? = null
    private var inspectListener: OnSuggestionInspectedListener? = null
    private val currentSuggestions: MutableList<String> = mutableListOf()
    private val currentScores: MutableList<Int> = mutableListOf()

    // Task B (pipeline transparency): per-suggestion provenance parallel to
    // currentSuggestions. Empty when the caller provided no metas.
    private val currentMetas: MutableList<SuggestionMeta> = mutableListOf()
    private var showOriginMarkers = false
    private var provenancePopup: android.widget.PopupWindow? = null

    // M6 (review 2026-08-06): monotonic content generation, bumped on every
    // APPLIED bar-content change (setSuggestionsWithScores past the identical-
    // content skip, and showTemporaryMessage). Async producers snapshot it at
    // submit time and abort their post when the bar has moved on — prevents a
    // stale queued next-word post from overwriting newer bar state. Main-thread
    // only (all bar writes are), @Volatile so executor threads can snapshot.
    @Volatile
    private var contentGeneration = 0
    private var selectedIndex = -1
    private val theme: Theme?
    private var showDebugScores = false
    private var opacity = 90 // default opacity
    private var alwaysVisible = true // Keep bar visible even when empty (default enabled)

    // Password mode properties
    private var isPasswordMode = false
    private var isPasswordVisible = false
    private var allowSwipeInPasswordMode = false  // #39: Allow swipe predictions in password fields
    private var currentPasswordText = StringBuilder()
    private var passwordContainer: RelativeLayout? = null
    private var passwordTextView: TextView? = null
    private var eyeToggleView: ImageView? = null
    private var inputConnectionProvider: InputConnectionProvider? = null

    // Temporary message properties (for language toggle feedback, etc.)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var savedSuggestions: List<String> = emptyList()
    private var savedScores: List<Int> = emptyList()
    private var savedMetas: List<SuggestionMeta> = emptyList()
    private var isShowingTemporaryMessage = false
    private val restoreRunnable = Runnable {
        isShowingTemporaryMessage = false
        setSuggestionsWithScores(savedSuggestions, savedScores, savedMetas)
    }

    // #41: Emoji search mode properties
    private var isInEmojiSearchMode = false

    /**
     * Check if currently showing a temporary message.
     * v1.2.6: Used to prevent cursor sync from overwriting feedback messages.
     */
    fun isShowingMessage(): Boolean = isShowingTemporaryMessage

    /**
     * Interface for providing InputConnection to read actual field content.
     */
    fun interface InputConnectionProvider {
        fun getInputConnection(): InputConnection?
    }

    fun interface OnSuggestionSelectedListener {
        fun onSuggestionSelected(word: String)
    }

    /**
     * Task B: long-press provenance inspection. Fired when the user long-presses
     * a suggestion; the handler composes and displays the provenance sheet.
     */
    fun interface OnSuggestionInspectedListener {
        fun onSuggestionInspected(index: Int, word: String, meta: SuggestionMeta?)
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
                    // Record selection statistics for swipe predictions
                    SwipePerformanceStats.getInstance(context).recordSelection(index)

                    listener?.onSuggestionSelected(currentSuggestions[index])
                }
            }

            // Task B: long-press opens the provenance sheet for this suggestion.
            setOnLongClickListener {
                val inspector = inspectListener
                if (inspector != null && index < currentSuggestions.size) {
                    inspector.onSuggestionInspected(index, currentSuggestions[index], metaAt(index))
                    true
                } else {
                    false
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
     * Default layout params for a (non-centered) suggestion TextView:
     * WRAP_CONTENT width so the bar scrolls horizontally, MATCH_PARENT height,
     * with a small right margin. Recreated per bind so a view that was previously
     * used as the centered "Add to dictionary?" prompt (which switches to
     * MATCH_PARENT width) is reset back to the scrollable layout.
     */
    private fun defaultSuggestionLayoutParams(): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            setMargins(0, 0, dpToPx(context, 4), 0)
        }

    /**
     * R3: Reconcile the child views to the current [currentSuggestions] by
     * recycling pooled TextViews/dividers rather than reallocating them.
     *
     * Layout order is `[sug0, div0, sug1, div1, …, sug(n-1)]` — n suggestion
     * views and (n-1) dividers. Pool slot `i` is dedicated to suggestion `i` and
     * its click listener (bound once in [createSuggestionView]) references
     * `currentSuggestions[i]`, so reuse never changes click semantics.
     *
     * Only the child-count delta touches the ViewGroup: already-attached pooled
     * views stay put (the keystroke→keystroke hot path), views are re-attached
     * only if a prior imperative mode (temporary message / password / autofill)
     * detached them via removeAllViews(), and surplus children are detached.
     */
    private fun rebindSuggestionViews() {
        val count = currentSuggestions.size

        // Grow pools on demand (index-stable click closure captured at creation).
        while (suggestionViews.size < count) {
            suggestionViews.add(createSuggestionView(context, suggestionViews.size))
        }
        val dividerCount = if (count > 0) count - 1 else 0
        while (dividerViews.size < dividerCount) {
            dividerViews.add(createDivider(context))
        }

        // Build the desired ordered child sequence from the pools.
        val desired = ArrayList<View>(count + dividerCount)
        for (i in 0 until count) {
            val suggestion = Suggestion.parse(currentSuggestions[i])
            val isCenteredPrompt = suggestion is Suggestion.AddToDictionary && count == 1
            bindSuggestionView(suggestionViews[i], i, suggestion, isCenteredPrompt)
            if (i > 0) desired.add(dividerViews[i - 1])
            desired.add(suggestionViews[i])
        }

        reconcileChildren(desired)
    }

    /**
     * Rebind text, layout, typeface and color for a recycled suggestion view.
     * All per-frame display transforms flow through the typed [Suggestion] here —
     * no `startsWith`/`removePrefix` parsing remains in the render path.
     */
    private fun bindSuggestionView(
        view: TextView,
        index: Int,
        suggestion: Suggestion,
        isCenteredPrompt: Boolean
    ) {
        // Display text derived from the typed suggestion.
        val baseText: CharSequence = when (suggestion) {
            is Suggestion.AddToDictionary ->
                context.getString(R.string.suggestion_add_to_dictionary, suggestion.word)
            is Suggestion.ExactAdd -> suggestion.label
            is Suggestion.Word ->
                if (showDebugScores && index < currentScores.size && currentScores.isNotEmpty()) {
                    "${suggestion.text}\n${currentScores[index]}"
                } else {
                    suggestion.text
                }
        }

        // Task B Tier 2 (opt-in `suggestion_provenance_markers`): small colored
        // dot per suggestion showing which pipeline stage produced it.
        val meta = metaAt(index)
        view.text = if (showOriginMarkers && meta != null && suggestion is Suggestion.Word) {
            android.text.SpannableStringBuilder(baseText).apply {
                val start = length
                append(" ●")
                setSpan(
                    android.text.style.ForegroundColorSpan(originMarkerColor(meta.origin)),
                    start + 1, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    android.text.style.RelativeSizeSpan(0.6f),
                    start + 1, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            baseText
        }

        when {
            // Centered "Add to dictionary?" prompt when it is the only suggestion.
            isCenteredPrompt -> {
                view.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                view.gravity = Gravity.CENTER
                view.typeface = Typeface.DEFAULT_BOLD
                view.setTextColor(theme?.activatedColor?.takeIf { it != 0 } ?: Color.CYAN)
            }
            // #42: Exact typed word (italic, sublabel color for "tap to add").
            suggestion is Suggestion.ExactAdd -> {
                view.layoutParams = defaultSuggestionLayoutParams()
                view.gravity = Gravity.CENTER
                view.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
                view.setTextColor(theme?.subLabelColor?.takeIf { it != 0 } ?: Color.LTGRAY)
            }
            // Highlight first suggestion with activated color.
            index == 0 -> {
                view.layoutParams = defaultSuggestionLayoutParams()
                view.gravity = Gravity.CENTER
                view.setTypeface(Typeface.DEFAULT_BOLD, Typeface.NORMAL)
                view.setTextColor(theme?.activatedColor?.takeIf { it != 0 } ?: Color.CYAN)
            }
            else -> {
                view.layoutParams = defaultSuggestionLayoutParams()
                view.gravity = Gravity.CENTER
                view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                view.setTextColor(theme?.labelColor?.takeIf { it != 0 } ?: Color.WHITE)
            }
        }
        view.visibility = VISIBLE
    }

    /**
     * Reconcile the actual children of this LinearLayout to [desired] in order,
     * touching only the delta: detach any leftover child, and (re)attach/reorder
     * only where the current child differs from the desired one. Pooled views not
     * in [desired] (surplus suggestions/dividers) are detached but retained in the
     * pool for future reuse.
     */
    private fun reconcileChildren(desired: List<View>) {
        // Fast path: already correct (common for the dedup-skipped hot loop and
        // for repeated same-size updates where pooled views stayed attached).
        var i = 0
        while (i < desired.size) {
            val want = desired[i]
            val have = if (i < childCount) getChildAt(i) else null
            if (have !== want) {
                // Detach `want` from any stale position/parent, then insert at i.
                (want.parent as? ViewGroup)?.let { p -> if (p !== this) p.removeView(want) }
                if (indexOfChild(want) != -1) removeView(want)
                addView(want, i)
            }
            i++
        }
        // Remove surplus trailing children (larger previous suggestion set, or
        // leftover views from a prior imperative mode). Detach only — pooled
        // views remain in suggestionViews/dividerViews for reuse.
        while (childCount > desired.size) {
            removeViewAt(childCount - 1)
        }
    }

    /**
     * Set whether to show debug scores
     */
    fun setShowDebugScores(show: Boolean) {
        showDebugScores = show
    }

    /** Task B Tier 2: toggle per-suggestion origin markers (opt-in pref). */
    fun setShowOriginMarkers(show: Boolean) {
        showOriginMarkers = show
    }

    /** Provenance meta for suggestion slot [index], or null when none was supplied. */
    fun metaAt(index: Int): SuggestionMeta? = currentMetas.getOrNull(index)

    /**
     * Provenance meta for a displayed suggestion word (first match). Used by the
     * commit path to route taps by origin (e.g. a NEXT_WORD candidate appended
     * after swipe alternates must APPEND, not replace the auto-inserted word).
     */
    fun getMetaForSuggestion(word: String): SuggestionMeta? {
        val index = currentSuggestions.indexOf(word)
        return if (index >= 0) metaAt(index) else null
    }

    /** Fixed marker palette per origin (readable on light and dark key themes). */
    private fun originMarkerColor(origin: SuggestionOrigin): Int = when (origin) {
        SuggestionOrigin.GEOMETRIC -> 0xFF80CBC4.toInt()         // teal
        SuggestionOrigin.CTC -> 0xFF9FA8DA.toInt()               // indigo
        SuggestionOrigin.DICTIONARY_PREFIX -> 0xFF90CAF9.toInt() // blue
        SuggestionOrigin.CONTRACTION -> 0xFFFFCC80.toInt()       // orange
        SuggestionOrigin.POSSESSIVE -> 0xFFFFE082.toInt()        // amber
        SuggestionOrigin.EXACT_ADD -> 0xFFB0BEC5.toInt()         // gray
        SuggestionOrigin.NEXT_WORD -> 0xFFA5D6A7.toInt()         // green
        SuggestionOrigin.AUTOCORRECT -> 0xFFEF9A9A.toInt()       // red
    }

    /** Task B: register the long-press provenance inspection listener. */
    fun setOnSuggestionInspectedListener(listener: OnSuggestionInspectedListener?) {
        inspectListener = listener
    }

    /**
     * Task B Tier 1: display the provenance sheet as a popup anchored above the
     * bar (an IME cannot casually launch dialogs; a PopupWindow inside the IME
     * window is the same mechanism key previews use). Tapping the sheet or
     * outside it dismisses it.
     */
    fun showProvenancePopup(text: String) {
        dismissProvenancePopup()

        val content = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme?.labelColor?.takeIf { it != 0 } ?: Color.WHITE)
            val pad = dpToPx(context, 14)
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpToPx(context, 10).toFloat()
                val bg = theme?.colorKey?.takeIf { it != 0 } ?: Color.DKGRAY
                setColor(
                    Color.argb(242, Color.red(bg), Color.green(bg), Color.blue(bg))
                )
                setStroke(dpToPx(context, 1), theme?.subLabelColor?.takeIf { it != 0 } ?: Color.GRAY)
            }
        }
        val scroller = android.widget.ScrollView(context).apply { addView(content) }

        val popupWidth = (width * 0.92f).toInt().coerceAtLeast(dpToPx(context, 220))
        val popup = android.widget.PopupWindow(scroller, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = false // never steal focus from the edited field
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        content.setOnClickListener { popup.dismiss() }
        popup.setOnDismissListener { if (provenancePopup === popup) provenancePopup = null }

        // Measure so the sheet opens fully ABOVE the bar.
        scroller.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        provenancePopup = popup
        try {
            popup.showAsDropDown(this, (width - popupWidth) / 2, -height - scroller.measuredHeight)
        } catch (e: Exception) {
            // Window token gone mid-teardown — drop silently
            provenancePopup = null
        }
    }

    /** Dismiss any open provenance sheet (bar content changed / view detached). */
    fun dismissProvenancePopup() {
        provenancePopup?.dismiss()
        provenancePopup = null
    }

    /**
     * Current bar-content generation (M6). Snapshot before submitting async
     * work that will post bar content; compare on the main thread before
     * applying — a mismatch means the bar changed in between and the queued
     * post is stale.
     */
    fun contentGeneration(): Int = contentGeneration

    override fun onDetachedFromWindow() {
        dismissProvenancePopup()
        super.onDetachedFromWindow()
    }

    // ARC-084 (2026-08-29): setAlwaysVisible was here. Its ONLY caller was
    // KeyboardDimensionsHelper.checkCGRPredictions, deleted with the rest of the dead CGR
    // chain, and it only ever passed `true` — which [alwaysVisible] already defaults to.
    // Removing the setter is therefore behaviour-identical; the field stays because both
    // visibility branches below read it.

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
     * Update the displayed suggestions with scores (and optional provenance
     * metas parallel to the suggestion list — Task B transparency).
     */
    @JvmOverloads
    fun setSuggestionsWithScores(
        suggestions: List<String>?,
        scores: List<Int>?,
        metas: List<SuggestionMeta>? = null
    ) {
        // Skip suggestion updates in password mode, unless swipe on password is enabled (#39)
        if (isPasswordMode && !allowSwipeInPasswordMode) {
            return
        }

        // v1.2.6: Don't overwrite temporary messages (e.g., "Added to dictionary")
        if (isShowingTemporaryMessage) {
            Log.d(TAG, "setSuggestionsWithScores: skipped - temporary message is showing")
            return
        }

        // Skip re-render if content is identical — prevents visual flicker when
        // SuggestionHandler and InputCoordinator both post the same predictions.
        // Metas participate so a provenance change (e.g. swipe alternates gaining
        // appended next-word candidates) still re-renders.
        if (suggestions != null && suggestions == currentSuggestions.toList() &&
            (metas ?: emptyList()) == currentMetas.toList()
        ) {
            return
        }

        // Any content change invalidates an open provenance sheet.
        dismissProvenancePopup()

        // M6 (review 2026-08-06): every applied content change bumps the bar
        // generation so queued async posts (next-word prediction) can detect
        // that the bar moved on and abort instead of clobbering newer state.
        contentGeneration++

        currentSuggestions.clear()
        currentScores.clear()
        currentMetas.clear()

        if (suggestions != null) {
            currentSuggestions.addAll(suggestions)
            if (scores != null && scores.size == suggestions.size) {
                currentScores.addAll(scores)
            }
            if (metas != null && metas.size == suggestions.size) {
                currentMetas.addAll(metas)
            }
        }

        // R3 (audit finding #5): Recycle TextViews instead of removeAllViews() +
        // allocating a fresh TextView (and divider) per suggestion per keystroke.
        // The view pools ([suggestionViews]/[dividerViews]) are grown on demand and
        // rebound in place; only the child-count delta is added/removed. Click
        // listeners are bound once at pool-creation with an index-stable closure
        // (pool slot i always renders suggestion i), preserving click semantics.
        try {
            rebindSuggestionViews()
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
     * Clear all suggestions (the bar stays visible while [alwaysVisible] is set).
     */
    fun clearSuggestions() {
        // L1 (review 2026-08-06): the provenance sheet must never outlive its
        // content — dismiss BEFORE the guards below (password mode and the
        // temporary-message skip both early-return, and the identical-content
        // skip inside setSuggestionsWithScores bypasses its dismiss when the
        // bar is already empty).
        dismissProvenancePopup()

        // Don't clear password mode views
        if (isPasswordMode) {
            return
        }

        // v1.2.6: Don't clear if showing temporary message (e.g., "Added to dictionary")
        if (isShowingTemporaryMessage) {
            Log.d(TAG, "clearSuggestions: skipped - temporary message is showing")
            return
        }

        // Clear any inline autofill view
        clearInlineAutofillView()

        // ALWAYS show empty suggestions instead of hiding - prevents UI disappearing
        setSuggestions(emptyList())
        Log.d(TAG, "clearSuggestions called - showing empty list instead of hiding")
    }

    /**
     * Show a temporary message in the suggestion bar, then restore previous suggestions.
     * Used for feedback that can't use Toast (Android 13+ IME toast restrictions).
     *
     * @param message The message to display
     * @param durationMs How long to show the message (default 1500ms)
     * @param clearAfter If true, clear the bar after message instead of restoring (default false)
     * @since v1.2.0
     */
    fun showTemporaryMessage(message: String, durationMs: Long = 1500L, clearAfter: Boolean = false) {
        if (isPasswordMode) return  // Don't interrupt password mode

        // L1: temporary messages replace the bar content the sheet described.
        dismissProvenancePopup()

        // M6: this is an applied content change — stale async posts must abort.
        contentGeneration++

        // Cancel any pending restore
        mainHandler.removeCallbacks(restoreRunnable)

        // Save current suggestions if not already showing a temp message (and not clearing after)
        if (!isShowingTemporaryMessage && !clearAfter) {
            savedSuggestions = currentSuggestions.toList()
            savedScores = currentScores.toList()
            savedMetas = currentMetas.toList()
        } else if (clearAfter) {
            // Clear saved suggestions so nothing gets restored
            savedSuggestions = emptyList()
            savedScores = emptyList()
            savedMetas = emptyList()
        }
        isShowingTemporaryMessage = true

        // Clear and show single message
        removeAllViews()
        suggestionViews.clear()
        dividerViews.clear()
        currentSuggestions.clear()
        currentScores.clear()
        currentMetas.clear()

        val messageView = TextView(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme?.activatedColor?.takeIf { it != 0 } ?: Color.CYAN)
            typeface = Typeface.DEFAULT_BOLD
            text = message
        }
        addView(messageView)

        Log.d(TAG, "showTemporaryMessage: '$message' for ${durationMs}ms, clearAfter=$clearAfter")

        // Schedule restore
        mainHandler.postDelayed(restoreRunnable, durationMs)
    }

    /**
     * Clear saved suggestions so they won't be restored after temporary message.
     * Call this before showTemporaryMessage when you want to clear the bar after the message.
     *
     * @since v1.2.2
     */
    fun clearSavedSuggestions() {
        savedSuggestions = emptyList()
        savedScores = emptyList()
    }

    // ==================== Emoji Search Mode (#41) ====================

    /**
     * #41: Show emoji search status in the suggestion bar.
     * This is a dedicated mode that takes over the suggestion bar display.
     *
     * @param message The message to display (e.g., "Type to search emoji..." or "Search: \"banana\"")
     */
    fun showEmojiSearchStatus(message: String) {
        if (isPasswordMode) return

        // Cancel any pending temporary message restore
        mainHandler.removeCallbacks(restoreRunnable)
        isShowingTemporaryMessage = false

        // Enter emoji search display mode
        isInEmojiSearchMode = true

        // Clear and show single status message
        removeAllViews()
        suggestionViews.clear()
        dividerViews.clear()
        currentSuggestions.clear()
        currentScores.clear()
        currentMetas.clear()

        val statusView = TextView(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme?.subLabelColor?.takeIf { it != 0 } ?: Color.LTGRAY)
            typeface = Typeface.DEFAULT_BOLD
            text = message
        }
        addView(statusView)

        Log.d(TAG, "showEmojiSearchStatus: '$message'")
    }

    /**
     * #41: Exit emoji search mode and clear the display.
     * Restores normal suggestion bar behavior.
     */
    fun clearEmojiSearchStatus() {
        if (!isInEmojiSearchMode) return

        isInEmojiSearchMode = false

        // Clear the display
        removeAllViews()
        suggestionViews.clear()
        dividerViews.clear()
        currentSuggestions.clear()
        currentScores.clear()
        currentMetas.clear()

        // Show empty suggestions (maintains bar visibility if alwaysVisible)
        visibility = if (alwaysVisible) VISIBLE else GONE

        Log.d(TAG, "clearEmojiSearchStatus: exited emoji search mode")
    }

    /**
     * #41: Check if currently in emoji search mode.
     */
    fun isInEmojiSearchMode(): Boolean = isInEmojiSearchMode

    // ==================== Inline Autofill Support ====================
    // Track the inline autofill view to properly manage its lifecycle

    private var inlineAutofillView: View? = null
    private var isInlineAutofillMode = false

    /**
     * Set an inline autofill view to display password manager suggestions.
     * This replaces the normal suggestions with the autofill content.
     *
     * @param view The inline autofill view from InlineAutofillUtils
     */
    fun setInlineAutofillView(view: View?) {
        if (view == null) {
            clearInlineAutofillView()
            return
        }

        // #48: Allow autofill in password mode — password managers NEED to show
        // inline suggestions on password fields. Previous code blocked this silently,
        // causing Safe, Bitwarden, etc. to fail on login forms.
        // isPasswordMode only hides typed characters; autofill chips are separate.

        // Clear existing suggestions and views
        removeAllViews()
        suggestionViews.clear()
        dividerViews.clear()
        currentSuggestions.clear()
        currentScores.clear()
        currentMetas.clear()

        // #109: Remove padding so autofill chips get the full bar height.
        // Normal text suggestions use 8dp padding for spacing, but autofill chips
        // are rendered by the password manager at the spec height (40dp) and need
        // the full container height to avoid being clipped/cut off.
        setPadding(0, 0, 0, 0)

        // Add the inline autofill view
        inlineAutofillView = view
        isInlineAutofillMode = true

        addView(view, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // #109: Ensure bar is visible — password mode or empty suggestions could have
        // set visibility to GONE before autofill arrived
        visibility = VISIBLE

        Log.d(TAG, "setInlineAutofillView: Displaying inline autofill suggestions (padding removed for full height)")
    }

    /**
     * Clear the inline autofill view and return to normal suggestion mode.
     */
    private fun clearInlineAutofillView() {
        if (isInlineAutofillMode && inlineAutofillView != null) {
            removeView(inlineAutofillView)
            inlineAutofillView = null
            isInlineAutofillMode = false

            // #109: Restore normal padding after autofill is cleared
            val padding = dpToPx(context, 8)
            setPadding(padding, padding, padding, padding)

            Log.d(TAG, "clearInlineAutofillView: Cleared inline autofill view, padding restored")
        }
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
     * #39: Enable/disable swipe predictions in password mode.
     * When true, swipe typing results will be shown even in password fields.
     */
    fun setAllowSwipeInPasswordMode(allow: Boolean) {
        allowSwipeInPasswordMode = allow
        Log.d(TAG, "Swipe in password mode: ${if (allow) "enabled" else "disabled"}")
    }

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
    // ClickableViewAccessibility: the scrollView touch listener below only
    // coordinates scroll interception (requestDisallowInterceptTouchEvent) and
    // returns false so the ScrollView handles scrolling itself. It never consumes
    // a tap as a click, so performClick() has no meaning here.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPasswordModeViews() {
        // Clear any existing suggestion views
        removeAllViews()
        suggestionViews.clear()
        dividerViews.clear()

        // #109: Remove padding so password eye icon (36dp) and autofill chips
        // get the full 40dp bar height. Password views manage their own spacing.
        setPadding(0, 0, 0, 0)

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
        dividerViews.clear()

        // #109: Restore normal padding after leaving password/autofill mode
        val padding = dpToPx(context, 8)
        setPadding(padding, padding, padding, padding)
    }
}
