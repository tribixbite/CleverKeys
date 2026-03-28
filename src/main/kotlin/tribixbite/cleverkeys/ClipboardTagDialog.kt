package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * AlertDialog-based tag management for clipboard entries.
 *
 * Programmatic layout (no XML needed):
 * - Current tags as removable chips
 * - Existing tags from other entries as suggestion chips (tap to add)
 * - EditText for creating new tags
 *
 * Uses [Utils.show_dialog_on_ime] pattern for proper IME context display.
 */
object ClipboardTagDialog {

    /**
     * Show the tag management dialog for a clipboard entry.
     *
     * @param context Application/IME context
     * @param service ClipboardHistoryService for tag operations
     * @param tab Current tab (determines which table to update)
     * @param entry The entry being tagged
     * @param anchorView View providing windowToken for IME dialog display
     * @param onTagsChanged Callback invoked after any tag change (triggers data reload)
     */
    fun show(
        context: Context,
        service: ClipboardHistoryService?,
        tab: ClipboardTab,
        entry: ClipboardEntry,
        anchorView: View,
        onTagsChanged: () -> Unit
    ) {
        if (service == null) return
        // Only pinned and todos tabs support tags
        if (tab != ClipboardTab.PINNED && tab != ClipboardTab.TODOS) return

        val themedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog)
        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
        }

        // ── Build programmatic layout ──
        val rootLayout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        // Current tags section
        val currentTags = entry.tags.toMutableList()

        val currentLabel = TextView(themedContext).apply {
            text = "Current tags"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        rootLayout.addView(currentLabel)

        // Flow layout for current tag chips (removable)
        val currentChipsContainer = FlowLayout(themedContext, dp(4), dp(4))
        rootLayout.addView(currentChipsContainer)

        // Divider
        val divider1 = View(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            setBackgroundColor(0x33FFFFFF)
        }
        rootLayout.addView(divider1)

        // Suggestion section (existing tags not on this entry)
        val suggestionLabel = TextView(themedContext).apply {
            text = "Add from existing"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        rootLayout.addView(suggestionLabel)

        val suggestionChipsContainer = FlowLayout(themedContext, dp(4), dp(4))
        rootLayout.addView(suggestionChipsContainer)

        // Divider
        val divider2 = View(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
            setBackgroundColor(0x33FFFFFF)
        }
        rootLayout.addView(divider2)

        // New tag input
        val inputRow = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val newTagInput = EditText(themedContext).apply {
            hint = "New tag..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            setSingleLine(true)
            // inputType=none to prevent Android soft keyboard — we ARE the keyboard
            inputType = InputType.TYPE_NULL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(newTagInput)
        rootLayout.addView(inputRow)

        // Wrap in ScrollView for long tag lists
        val scrollView = ScrollView(themedContext).apply {
            addView(rootLayout)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Build dialog ──
        val dialog = AlertDialog.Builder(themedContext)
            .setTitle("Manage Tags")
            .setView(scrollView)
            .setPositiveButton("Add") { _, _ -> /* handled below */ }
            .setNegativeButton("Close", null)
            .create()

        // ── Helper to refresh chip displays ──
        fun refreshChips() {
            // Get all existing tags from the appropriate table
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
                    currentChipsContainer.addView(createChip(themedContext, dp, tag, isRemovable = true) {
                        currentTags.remove(tag)
                        saveTags(service, tab, entry.content, currentTags)
                        onTagsChanged()
                        refreshChips()
                    })
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
                    suggestionChipsContainer.addView(createChip(themedContext, dp, tag, isRemovable = false) {
                        currentTags.add(tag)
                        saveTags(service, tab, entry.content, currentTags)
                        onTagsChanged()
                        refreshChips()
                    })
                }
            }
        }

        refreshChips()

        // Override positive button to add new tag without dismissing dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
        }

        Utils.show_dialog_on_ime(dialog, anchorView.windowToken)
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
        isRemovable: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            val displayText = if (isRemovable) "$text  \u00D7" else "+ $text"
            this.text = displayText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (isRemovable) Color.WHITE else 0xAAFFFFFF.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            // Rounded background
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (isRemovable) {
                    // Resolve ?attr/colorKey — fall back to a dark gray
                    setColor(0xFF3A3A3A.toInt())
                } else {
                    setColor(0xFF2A2A2A.toInt())
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

    /**
     * Simple flow layout that wraps children horizontally.
     * Used for tag chips that may overflow a single line.
     */
    private class FlowLayout(
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
