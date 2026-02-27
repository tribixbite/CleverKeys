package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import tribixbite.cleverkeys.gif.Gif
import tribixbite.cleverkeys.gif.GifAssetManager
import tribixbite.cleverkeys.gif.GifGridManager
import tribixbite.cleverkeys.gif.GifGroupButtonsBar

/**
 * Handles keyboard events and state changes for CleverKeysService.
 *
 * This class centralizes logic for:
 * - Keyboard event handling (special keys, layout switching)
 * - View state management (shift, compose, selection)
 * - Layout switching (text, numeric, emoji, clipboard)
 * - Input method switching
 * - Clipboard and emoji pane management
 *
 * Responsibilities:
 * - Handle special key events (CONFIG, SWITCH_TEXT, SWITCH_NUMERIC, etc.)
 * - Manage keyboard view state updates
 * - Coordinate with managers for layout, clipboard, and input operations
 * - Bridge between KeyEventHandler and CleverKeysService
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - Manager initialization
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.368).
 */
class KeyboardReceiver(
    private val context: Context,
    private val keyboard2: CleverKeysService,
    private val keyboardView: Keyboard2View,
    private val layoutManager: LayoutManager,
    private val clipboardManager: ClipboardManager,
    private val contextTracker: PredictionContextTracker,
    private val inputCoordinator: InputCoordinator,
    private val subtypeManager: SubtypeManager,
    private val handler: Handler
) : KeyEventHandler.IReceiver {

    // View references
    private var emojiPane: ViewGroup? = null
    private var contentPaneContainer: android.widget.FrameLayout? = null
    private var topPane: android.widget.FrameLayout? = null
    private var scrollView: android.widget.HorizontalScrollView? = null
    private var suggestionBarHeight: Int = 0
    private var contentPaneHeight: Int = 0

    // Track if content pane is showing (to reset on keyboard hide)
    private var isContentPaneShowing: Boolean = false

    // Track which pane is currently visible for toggle behavior
    private var currentPaneType: PaneType = PaneType.NONE

    private enum class PaneType { NONE, EMOJI, CLIPBOARD, GIF }

    // #41: Emoji search manager (uses suggestion bar for status display)
    private var emojiSearchManager: EmojiSearchManager? = null

    // GIF search: reference to search EditText for key routing
    private var gifSearchInput: EditText? = null
    // GIF search active flag — like EmojiSearchManager.searchActive / ClipboardManager.searchMode
    private var gifSearchActive: Boolean = false

    /**
     * Sets references to views for content pane management.
     *
     * @param emojiPane Emoji pane view
     * @param contentPaneContainer Container for emoji/clipboard panes
     * @param topPane The FrameLayout that holds either scrollView or contentPaneContainer
     * @param scrollView The HorizontalScrollView containing suggestion bar
     * @param suggestionBarHeight Height of suggestion bar in pixels
     * @param contentPaneHeight Height of content pane in pixels
     */
    fun setViewReferences(
        emojiPane: ViewGroup?,
        contentPaneContainer: android.widget.FrameLayout?,
        topPane: android.widget.FrameLayout? = null,
        scrollView: android.widget.HorizontalScrollView? = null,
        suggestionBarHeight: Int = 0,
        contentPaneHeight: Int = 0
    ) {
        this.emojiPane = emojiPane
        this.contentPaneContainer = contentPaneContainer
        this.topPane = topPane
        this.scrollView = scrollView
        this.suggestionBarHeight = suggestionBarHeight
        this.contentPaneHeight = contentPaneHeight

        // Set up clipboard close button callback to trigger SWITCH_BACK_CLIPBOARD event
        clipboardManager.setOnCloseCallback {
            handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
        }
    }

    /**
     * Show content pane and hide suggestion bar.
     * Uses simple view swapping in topPane.
     */
    private fun showContentPane() {
        val top = topPane ?: return
        val content = contentPaneContainer ?: return
        val scroll = scrollView ?: return

        SuggestionBarInitializer.switchToContentPaneMode(top, content, scroll, contentPaneHeight)
        isContentPaneShowing = true
    }

    /**
     * Hide content pane and show suggestion bar.
     * Uses simple view swapping in topPane.
     */
    private fun hideContentPane() {
        // CRITICAL: Always reset state flag, even if views are null
        // Otherwise toggle logic will think pane is still showing
        isContentPaneShowing = false

        val top = topPane
        val content = contentPaneContainer
        val scroll = scrollView

        if (top == null || content == null || scroll == null) {
            return
        }

        SuggestionBarInitializer.switchToSuggestionBarMode(top, content, scroll, suggestionBarHeight)
    }

    /**
     * Reset content pane state when keyboard hides (e.g., app switch).
     * Call this from CleverKeysService.onFinishInputView().
     */
    fun resetContentPaneState() {
        // CRITICAL: Always reset state, even if views are null
        // This prevents stale state after app switches
        if (isContentPaneShowing) {
            hideContentPane()  // This now always resets isContentPaneShowing
        }

        // Always reset pane type to prevent toggle issues
        currentPaneType = PaneType.NONE
        emojiSearchManager?.onPaneClosed()
        clipboardManager.resetSearchOnHide()
        // Clear GIF search state so isGifPaneOpen() returns false
        gifSearchActive = false
        gifSearchInput = null
    }

    /**
     * #41: Sets the emoji search manager.
     * Called from CleverKeysService after initialization.
     */
    fun setEmojiSearchManager(manager: EmojiSearchManager) {
        this.emojiSearchManager = manager
    }

    override fun handle_event_key(ev: KeyValue.Event) {
        when (ev) {
            KeyValue.Event.CONFIG -> {
                val intent = Intent(context, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            KeyValue.Event.SWITCH_TEXT -> {
                keyboardView.setKeyboard(layoutManager.clearSpecialLayout())
            }

            KeyValue.Event.SWITCH_NUMERIC -> {
                val resId = keyboard2.resources.getIdentifier("numeric", "raw", keyboard2.packageName)
                val numpad = layoutManager.loadNumpad(resId)
                if (numpad != null) {
                    keyboardView.setKeyboard(numpad)
                }
            }

            KeyValue.Event.SWITCH_EMOJI -> {
                // Toggle behavior: if emoji pane already visible, close it
                if (currentPaneType == PaneType.EMOJI && isContentPaneShowing) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_EMOJI)
                    return
                }

                // Always inflate fresh to avoid stale view issues after app switch
                emojiPane = keyboard2.inflate_view(R.layout.emoji_pane) as ViewGroup

                // Capture for null safety
                val pane = emojiPane

                // Show emoji pane in content container (keyboard stays visible below)
                contentPaneContainer?.let { container ->
                    container.removeAllViews()
                    // Detach pane from any existing parent first
                    (pane?.parent as? ViewGroup)?.removeView(pane)
                    // Set pane with explicit height
                    pane?.layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        contentPaneHeight
                    )
                    container.addView(pane)
                    showContentPane()
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    if (pane != null) {
                        keyboard2.setInputView(pane)
                    }
                }

                currentPaneType = PaneType.EMOJI

                // #41 v4: Initialize emoji search manager with the pane and notify pane opened
                emojiPane?.let { pane ->
                    emojiSearchManager?.initialize(pane)
                    // Auto-detect context word for initial search query
                    val textBeforeCursor = keyboard2.currentInputConnection
                        ?.getTextBeforeCursor(100, 0)
                    val contextWord = emojiSearchManager?.extractWordBeforeCursor(textBeforeCursor)
                    emojiSearchManager?.onPaneOpened(contextWord)

                    // Wire up search manager to category buttons
                    pane.findViewById<EmojiGroupButtonsBar>(R.id.emoji_group_buttons)
                        ?.setSearchManager(emojiSearchManager!!)

                    // #41 v8: Wire up search manager to emoji grid for selection bypass
                    pane.findViewById<EmojiGridView>(R.id.emoji_grid)?.let { grid ->
                        grid.setSearchManager(emojiSearchManager!!)
                        // #41 v10: Wire up service for suggestion bar messages on long-press
                        grid.setService(keyboard2)
                    }

                    // #41 v10: Close button callback to return to keyboard
                    emojiSearchManager?.setOnCloseCallback {
                        handle_event_key(KeyValue.Event.SWITCH_BACK_EMOJI)
                    }
                }
            }

            KeyValue.Event.SWITCH_CLIPBOARD -> {
                // Toggle behavior: if clipboard pane already visible, close it
                if (currentPaneType == PaneType.CLIPBOARD && isContentPaneShowing) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
                    return
                }

                // SECURITY: Block clipboard access on lock screen (contains PII)
                if (DirectBootManager.getInstance(context).isDeviceLocked) {
                    return
                }

                // Get clipboard pane from manager (lazy initialization)
                val clipboardPane = clipboardManager.getClipboardPane(keyboard2.layoutInflater)

                // Reset search mode and clear any previous search when showing clipboard pane
                clipboardManager.resetSearchOnShow()

                // Show clipboard pane in content container (keyboard stays visible below)
                contentPaneContainer?.let { container ->
                    container.removeAllViews()
                    // Detach pane from any existing parent first
                    (clipboardPane.parent as? ViewGroup)?.removeView(clipboardPane)
                    // Set pane with explicit height
                    clipboardPane.layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        contentPaneHeight
                    )
                    container.addView(clipboardPane)
                    showContentPane()
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    keyboard2.setInputView(clipboardPane)
                }

                currentPaneType = PaneType.CLIPBOARD
            }

            KeyValue.Event.SWITCH_GIF -> {
                // GIF panel is opt-in — ignore key if disabled in settings
                if (!Config.globalConfig().gif_enabled) return

                // Toggle behavior: if GIF pane already visible, close it
                if (gifSearchActive) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_GIF)
                    return
                }

                // Inflate fresh GIF pane layout
                val gifPaneView = keyboard2.inflate_view(R.layout.gif_pane) as ViewGroup

                // Show GIF pane in content container (keyboard stays visible below)
                contentPaneContainer?.let { container ->
                    container.removeAllViews()
                    (gifPaneView.parent as? ViewGroup)?.removeView(gifPaneView)
                    gifPaneView.layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        contentPaneHeight
                    )
                    container.addView(gifPaneView)
                    showContentPane()
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    keyboard2.setInputView(gifPaneView)
                }

                currentPaneType = PaneType.GIF

                // Wire up GIF grid with RecyclerView + Coil
                val recyclerView = gifPaneView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.gif_grid)
                val gifColumns = Config.globalConfig().gif_thumbnail_columns
                val gifGrid = recyclerView?.let { GifGridManager(context, it, gifColumns) }
                gifGrid?.onGifSelected = { gif ->
                    // Tap inserts the Giphy URL as text into the input field
                    val url = gif.getGiphyUrl()
                    val ic = keyboard2.currentInputConnection
                    android.util.Log.d("GifPanel", "onGifSelected: url=$url ic=${ic != null} searchText='${gif.searchText}'")
                    if (url != null && ic != null) {
                        val ok = ic.commitText(url, 1)
                        android.util.Log.d("GifPanel", "commitText result=$ok")
                    } else {
                        // Fallback: if commitText can't work, copy URL to clipboard
                        if (url != null) {
                            val clip = android.content.ClipData.newPlainText("GIF URL", url)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(clip)
                            Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No URL for this GIF", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Record usage and close GIF pane
                    handle_event_key(KeyValue.Event.SWITCH_BACK_GIF)
                }

                // Long press: IME-safe PopupWindow with title + copy actions
                // (PopupMenu fails in IME context — no window token)
                gifGrid?.onGifLongPress = { gif, anchor ->
                    showGifPopup(gif, anchor)
                }

                // Wire up search bar — store reference and activate routing flag
                // (same pattern as EmojiSearchManager.searchActive / ClipboardManager.searchMode)
                val searchInput = gifPaneView.findViewById<EditText>(R.id.gif_search_input)
                gifSearchInput = searchInput
                gifSearchActive = true
                searchInput?.requestFocus()
                val searchClear = gifPaneView.findViewById<ImageButton>(R.id.gif_search_clear)
                val noResults = gifPaneView.findViewById<TextView>(R.id.gif_no_results)

                searchInput?.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString()?.trim() ?: ""
                        searchClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                        gifGrid?.search(query)
                        // Show/hide no results message
                        val count = gifGrid?.getResultCount() ?: 0
                        noResults?.visibility = if (query.isNotEmpty() && count == 0) View.VISIBLE else View.GONE
                    }
                })

                searchClear?.setOnClickListener {
                    searchInput?.text?.clear()
                }

                // Wire up close button
                gifPaneView.findViewById<ImageButton>(R.id.gif_close_button)?.setOnClickListener {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_GIF)
                }

                // Wire up pagination controls
                val paginationBar = gifPaneView.findViewById<android.widget.LinearLayout>(R.id.gif_pagination_bar)
                val pageInfo = gifPaneView.findViewById<TextView>(R.id.gif_page_info)
                val pagePrev = gifPaneView.findViewById<TextView>(R.id.gif_page_prev)
                val pageNext = gifPaneView.findViewById<TextView>(R.id.gif_page_next)

                gifGrid?.onPaginationChanged = { needsPagination, currentPage, totalPages ->
                    paginationBar?.visibility = if (needsPagination) View.VISIBLE else View.GONE
                    pageInfo?.text = "$currentPage / $totalPages"
                    pagePrev?.alpha = if (gifGrid.hasPreviousPage()) 1.0f else 0.3f
                    pageNext?.alpha = if (gifGrid.hasNextPage()) 1.0f else 0.3f
                }

                pagePrev?.setOnClickListener { gifGrid?.previousPage() }
                pageNext?.setOnClickListener { gifGrid?.nextPage() }

                // Wire up category buttons — clear search + switch grid category
                val groupButtons = gifPaneView.findViewById<GifGroupButtonsBar>(R.id.gif_group_buttons)
                groupButtons?.setOnCategorySelectedListener {
                    searchInput?.text?.clear()
                }
                groupButtons?.onCategoryChanged = { category ->
                    gifGrid?.setCategory(category)
                }
            }

            KeyValue.Event.SWITCH_BACK_EMOJI,
            KeyValue.Event.SWITCH_BACK_CLIPBOARD,
            KeyValue.Event.SWITCH_BACK_GIF -> {
                // Exit clipboard search mode when switching back
                clipboardManager.resetSearchOnHide()

                // #41 v4: Notify emoji search manager pane is closing
                emojiSearchManager?.onPaneClosed()

                // Clear GIF search state when pane closes
                gifSearchActive = false
                gifSearchInput = null

                // Reset pane tracking
                currentPaneType = PaneType.NONE

                // Swap back: hide content pane, show suggestion bar, resize wrapper
                hideContentPane()

                // Fallback for when predictions disabled
                if (contentPaneContainer == null) {
                    keyboard2.setInputView(keyboardView)
                }
            }

            KeyValue.Event.CHANGE_METHOD_PICKER -> {
                subtypeManager.inputMethodManager.showInputMethodPicker()
            }

            KeyValue.Event.CHANGE_METHOD_AUTO -> {
                if (Build.VERSION.SDK_INT < 28) {
                    keyboard2.getConnectionToken()?.let { token ->
                        subtypeManager.inputMethodManager.switchToLastInputMethod(token)
                    }
                } else {
                    keyboard2.switchToNextInputMethod(false)
                }
            }

            KeyValue.Event.ACTION -> {
                keyboard2.currentInputConnection?.performEditorAction(keyboard2.actionId)
            }

            KeyValue.Event.SWITCH_FORWARD -> {
                if (layoutManager.getLayoutCount() > 1) {
                    keyboardView.setKeyboard(layoutManager.incrTextLayout(1))
                }
            }

            KeyValue.Event.SWITCH_BACKWARD -> {
                if (layoutManager.getLayoutCount() > 1) {
                    keyboardView.setKeyboard(layoutManager.incrTextLayout(-1))
                }
            }

            KeyValue.Event.SWITCH_GREEKMATH -> {
                val greekmath = layoutManager.loadNumpad(R.xml.greekmath)
                if (greekmath != null) {
                    keyboardView.setKeyboard(greekmath)
                }
            }

            KeyValue.Event.CAPS_LOCK -> {
                set_shift_state(true, true)
            }

            KeyValue.Event.SWITCH_VOICE_TYPING -> {
                if (!VoiceImeSwitcher.switch_to_voice_ime(
                        keyboard2,
                        subtypeManager.inputMethodManager,
                        Config.globalPrefs()
                    )
                ) {
                    keyboard2.getConfig()?.shouldOfferVoiceTyping = false
                }
            }

            KeyValue.Event.SWITCH_VOICE_TYPING_CHOOSER -> {
                VoiceImeSwitcher.choose_voice_ime(
                    keyboard2,
                    subtypeManager.inputMethodManager,
                    Config.globalPrefs()
                )
            }

            else -> {} // Unhandled events
        }
    }

    /**
     * Show an IME-safe popup for GIF long-press.
     * Uses PopupWindow (not PopupMenu) because IME views lack a window token
     * for standard menus. Same approach as EmojiTooltipManager.
     */
    private fun showGifPopup(gif: Gif, anchor: View) {
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val cornerRadius = 8 * density

        val background = GradientDrawable().apply {
            setColor(0xEE222222.toInt())
            this.cornerRadius = cornerRadius
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            this.background = background
        }

        // Title: display name of the GIF
        val titleView = TextView(context).apply {
            text = gif.getDisplayName()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxWidth = (220 * density).toInt()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(titleView)

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = false // Must be false — focusable popup steals focus from content pane
            isTouchable = true
            isOutsideTouchable = true
            elevation = 8f
        }

        // Helper to create a tappable action row
        fun addAction(label: String, onClick: () -> Unit) {
            val actionView = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF90CAF9.toInt()) // Light blue
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener {
                    onClick()
                    popup.dismiss()
                }
            }
            container.addView(actionView)
        }

        // "Copy URL" — copies the Giphy animated GIF URL
        val url = gif.getGiphyUrl()
        if (url != null) {
            addAction("Copy URL") {
                val clip = android.content.ClipData.newPlainText("GIF URL", url)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
            }
        }

        // "Copy GIF" — only shown when full animated file exists on device
        val fullGifFile = java.io.File(context.filesDir, gif.getFullPath())
        if (fullGifFile.exists()) {
            addAction("Copy GIF") {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        fullGifFile
                    )
                    val clip = android.content.ClipData.newUri(
                        context.contentResolver,
                        gif.getDisplayName(),
                        uri
                    )
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(clip)
                    Toast.makeText(context, "GIF copied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.w("KeyboardReceiver", "Copy GIF failed: ${e.message}")
                }
            }
        }

        // "Copy keywords"
        val keywords = gif.getKeywords()
        if (keywords.isNotEmpty()) {
            addAction("Copy keywords") {
                val clip = android.content.ClipData.newPlainText("GIF keywords", keywords.joinToString(", "))
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(context, "Keywords copied", Toast.LENGTH_SHORT).show()
            }
        }

        // Show anchored above the tapped cell (same positioning as EmojiTooltipManager)
        try {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = container.measuredWidth
            val popupHeight = container.measuredHeight
            val offsetX = (anchor.width - popupWidth) / 2
            val offsetY = -anchor.height - popupHeight - (8 * density).toInt()
            popup.showAsDropDown(anchor, offsetX, offsetY, Gravity.TOP or Gravity.START)
        } catch (e: Exception) {
            android.util.Log.w("KeyboardReceiver", "GIF popup failed: ${e.message}")
        }
    }

    override fun set_shift_state(state: Boolean, lock: Boolean) {
        keyboardView.set_shift_state(state, lock)
    }

    override fun set_compose_pending(pending: Boolean) {
        keyboardView.set_compose_pending(pending)
    }

    override fun selection_state_changed(selectionIsOngoing: Boolean) {
        keyboardView.set_selection_state(selectionIsOngoing)
    }

    override fun getCurrentInputConnection(): InputConnection? {
        return keyboard2.currentInputConnection
    }

    override fun getHandler(): Handler {
        return handler
    }

    override fun handle_text_typed(text: String) {
        // Reset swipe tracking when regular typing occurs
        contextTracker.setWasLastInputSwipe(false)
        inputCoordinator.resetSwipeData()
        keyboard2.handleRegularTyping(text)
    }

    override fun handle_backspace() {
        keyboard2.handleBackspace()
    }

    override fun handle_delete_last_word() {
        keyboard2.handleDeleteLastWord()
    }

    override fun isClipboardSearchMode(): Boolean {
        return clipboardManager.isInSearchMode()
    }

    override fun appendToClipboardSearch(text: String) {
        clipboardManager.appendToSearch(text)
    }

    override fun backspaceClipboardSearch() {
        clipboardManager.deleteFromSearch()
    }

    override fun exitClipboardSearchMode() {
        clipboardManager.clearSearch()
    }

    // #41 v5: Emoji search routes typing to visible EditText (IME can't type into own views)
    override fun isEmojiPaneOpen(): Boolean {
        return emojiSearchManager?.isEmojiPaneOpen() ?: false
    }

    override fun appendToEmojiSearch(text: String) {
        emojiSearchManager?.appendToSearch(text)
    }

    override fun backspaceEmojiSearch() {
        emojiSearchManager?.backspaceSearch()
    }

    // GIF search routing — uses dedicated boolean flag like emoji/clipboard
    // (searchActive flag is independent of content pane state flags which get
    // reset by resetContentPaneState() on onFinishInputView)
    override fun isGifPaneOpen(): Boolean {
        return gifSearchActive
    }

    override fun appendToGifSearch(text: String) {
        // Use setText + setSelection (same pattern as EmojiSearchManager.appendToSearch)
        // EditText.append() doesn't reliably work when the IME owns the view
        val input = gifSearchInput ?: return
        val current = input.text?.toString() ?: ""
        val newText = current + text
        input.setText(newText)
        input.setSelection(newText.length)
    }

    override fun backspaceGifSearch() {
        val input = gifSearchInput ?: return
        val current = input.text?.toString() ?: ""
        if (current.isNotEmpty()) {
            val newText = current.dropLast(1)
            input.setText(newText)
            input.setSelection(newText.length)
        }
    }
}
