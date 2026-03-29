package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Inline tag management panel for clipboard entries.
 *
 * Builds a programmatic layout into a provided [LinearLayout] container within
 * [clipboard_pane.xml]. This replaces the entry list while active, keeping the
 * keyboard visible below. Key events are routed via the standard IME key routing
 * chain (see `ime-key-routing.md` skill).
 *
 * Layout:
 * - Header row: truncated entry preview + Close button
 * - Current tags as removable chips
 * - Existing tags from other entries as suggestion chips (tap to add)
 * - EditText for creating new tags + Add button
 *
 * @see ClipboardManager.showTagPanel for activation
 * @see ClipboardManager.hideTagPanel for deactivation
 */
object ClipboardTagPanel {

    /**
     * Populate the inline tag panel with chips and input for an entry.
     *
     * @param container The LinearLayout to populate (clipboard_tag_panel_content)
     * @param context Application/IME context
     * @param service ClipboardHistoryService for tag operations
     * @param tab Current tab (determines which table to update)
     * @param entry The entry being tagged
     * @param onTagsChanged Callback invoked after any tag change (triggers data reload)
     * @param onClose Callback to close the tag panel
     * @return The EditText for key routing registration, or null if tab doesn't support tags
     */
    fun populate(
        container: LinearLayout,
        context: Context,
        service: ClipboardHistoryService?,
        tab: ClipboardTab,
        entry: ClipboardEntry,
        onTagsChanged: () -> Unit,
        onClose: () -> Unit
    ): EditText? {
        if (service == null) return null
        // Only pinned and todos tabs support tags
        if (tab != ClipboardTab.PINNED && tab != ClipboardTab.TODOS) return null

        container.removeAllViews()

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        // Resolve theme colors for consistent styling
        val labelColor = resolveThemeColor(context, R.attr.colorLabel, Color.WHITE)
        val subLabelColor = resolveThemeColor(context, R.attr.colorSubLabel, 0x99FFFFFF.toInt())
        val keyBg = resolveThemeColor(context, R.attr.colorKey, 0xFF3A3A3A.toInt())

        // ── Header row: entry preview + Close button ──
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // "Tags for:" label + truncated entry content
        val headerText = TextView(context).apply {
            val preview = entry.content.take(40).replace('\n', ' ')
            val suffix = if (entry.content.length > 40) "..." else ""
            text = "Tags: $preview$suffix"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(labelColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        headerRow.addView(headerText)

        // Close button (X)
        val closeButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(labelColor)
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            contentDescription = "Close tag panel"
            setOnClickListener { onClose() }
        }
        headerRow.addView(closeButton)
        container.addView(headerRow)

        // ── Current tags section ──
        val currentTags = entry.tags.toMutableList()

        val currentLabel = TextView(context).apply {
            text = "Current tags"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(subLabelColor)
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(currentLabel)

        // Flow layout for current tag chips (removable)
        val currentChipsContainer = FlowLayout(context, dp(4), dp(4))
        container.addView(currentChipsContainer)

        // Divider
        val divider1 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            setBackgroundColor(0x33FFFFFF)
        }
        container.addView(divider1)

        // ── Suggestion section (existing tags not on this entry) ──
        val suggestionLabel = TextView(context).apply {
            text = "Add from existing"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(subLabelColor)
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(suggestionLabel)

        val suggestionChipsContainer = FlowLayout(context, dp(4), dp(4))
        container.addView(suggestionChipsContainer)

        // Divider
        val divider2 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            setBackgroundColor(0x33FFFFFF)
        }
        container.addView(divider2)

        // ── New tag input row ──
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val newTagInput = EditText(context).apply {
            hint = "New tag..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(labelColor)
            setHintTextColor(subLabelColor)
            setSingleLine(true)
            // inputType=none prevents Android soft keyboard — we ARE the keyboard
            inputType = InputType.TYPE_NULL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        inputRow.addView(newTagInput)

        // Add button
        val addButton = TextView(context).apply {
            text = "Add"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(labelColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(keyBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        inputRow.addView(addButton)
        container.addView(inputRow)

        // ── Helper to refresh chip displays ──
        fun refreshChips() {
            val allTags = when (tab) {
                ClipboardTab.PINNED -> service.getAllPinnedTags()
                ClipboardTab.TODOS -> service.getAllTodoTags()
                else -> emptySet()
            }
            val unusedTags = allTags - currentTags.toSet()

            // Current tag chips (with remove "x" action)
            currentChipsContainer.removeAllViews()
            if (currentTags.isEmpty()) {
                currentLabel.text = "No tags"
            } else {
                currentLabel.text = "Current tags"
                for (tag in currentTags) {
                    currentChipsContainer.addView(
                        createChip(context, dp, tag, labelColor, subLabelColor, keyBg,
                            isRemovable = true) {
                            currentTags.remove(tag)
                            saveTags(service, tab, entry.content, currentTags)
                            onTagsChanged()
                            refreshChips()
                        }
                    )
                }
            }

            // Suggestion chips (tap to add)
            suggestionChipsContainer.removeAllViews()
            if (unusedTags.isEmpty()) {
                suggestionLabel.visibility = View.GONE
                suggestionChipsContainer.visibility = View.GONE
                divider2.visibility = if (currentTags.isEmpty()) View.GONE else View.VISIBLE
            } else {
                suggestionLabel.visibility = View.VISIBLE
                suggestionChipsContainer.visibility = View.VISIBLE
                divider2.visibility = View.VISIBLE
                for (tag in unusedTags.sorted()) {
                    suggestionChipsContainer.addView(
                        createChip(context, dp, tag, labelColor, subLabelColor, keyBg,
                            isRemovable = false) {
                            currentTags.add(tag)
                            saveTags(service, tab, entry.content, currentTags)
                            onTagsChanged()
                            refreshChips()
                        }
                    )
                }
            }
        }

        refreshChips()

        // Add button click handler
        addButton.setOnClickListener {
            val newTag = newTagInput.text.toString().trim().lowercase()
            if (newTag.isEmpty()) {
                Toast.makeText(context, "Enter a tag name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (currentTags.contains(newTag)) {
                Toast.makeText(context, "Tag already exists", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentTags.add(newTag)
            newTagInput.text.clear()
            saveTags(service, tab, entry.content, currentTags)
            onTagsChanged()
            refreshChips()
        }

        return newTagInput
    }

    /** Resolve a theme attribute color, falling back to [fallback] if unavailable */
    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            tv.data
        } else {
            fallback
        }
    }

    /** Persist tag changes to the correct table via service */
    private fun saveTags(
        service: ClipboardHistoryService,
        tab: ClipboardTab,
        content: String,
        tags: List<String>
    ) {
        when (tab) {
            ClipboardTab.PINNED -> service.setPinnedTags(content, tags)
            ClipboardTab.TODOS -> service.setTodoTags(content, tags)
            else -> {} // History doesn't have tags
        }
    }

    /** Create a styled tag chip TextView */
    private fun createChip(
        context: Context,
        dp: (Int) -> Int,
        text: String,
        labelColor: Int,
        subLabelColor: Int,
        keyBg: Int,
        isRemovable: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            val displayText = if (isRemovable) "$text  \u00D7" else "+ $text"
            this.text = displayText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (isRemovable) labelColor else subLabelColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            // Rounded background
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (isRemovable) {
                    setColor(keyBg)
                } else {
                    // Slightly darker for suggestion chips
                    setColor(adjustAlpha(keyBg, 0.7f))
                }
            }
            setOnClickListener { onClick() }

            // Margins via layout params
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** Darken a color by multiplying RGB channels */
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    /**
     * Simple flow layout that wraps children horizontally.
     * Used for tag chips that may overflow a single line.
     */
    class FlowLayout(
        context: Context,
        private val hSpacing: Int,
        private val vSpacing: Int
    ) : ViewGroup(context) {

        init {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            var currentLineWidth = 0
            var currentLineHeight = 0
            var totalHeight = 0
            var totalWidth = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth + hSpacing
                val childHeight = child.measuredHeight

                if (currentLineWidth + childWidth > maxWidth) {
                    // Wrap to next line
                    totalHeight += currentLineHeight + vSpacing
                    totalWidth = maxOf(totalWidth, currentLineWidth)
                    currentLineWidth = childWidth
                    currentLineHeight = childHeight
                } else {
                    currentLineWidth += childWidth
                    currentLineHeight = maxOf(currentLineHeight, childHeight)
                }
            }
            // Account for last line
            totalHeight += currentLineHeight
            totalWidth = maxOf(totalWidth, currentLineWidth)

            setMeasuredDimension(
                resolveSize(totalWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = r - l
            var x = 0
            var y = 0
            var lineHeight = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight

                if (x + childWidth > maxWidth) {
                    // Wrap to next line
                    x = 0
                    y += lineHeight + vSpacing
                    lineHeight = 0
                }

                child.layout(x, y, x + childWidth, y + childHeight)
                x += childWidth + hSpacing
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
    }
}
