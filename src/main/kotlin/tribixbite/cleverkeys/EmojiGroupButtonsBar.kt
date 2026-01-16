package tribixbite.cleverkeys

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class EmojiGroupButtonsBar(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private var emojiGrid: EmojiGridView? = null
    private var searchInput: EditText? = null
    private var isSearchMode = false
    private var groupButtonsContainer: LinearLayout? = null

    init {
        orientation = HORIZONTAL
        Emoji.init(context.resources)

        // Create container for group buttons
        groupButtonsContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(groupButtonsContainer)

        // Add group buttons to container
        groupButtonsContainer?.addView(
            EmojiGroupButton(context, EmojiGridView.GROUP_LAST_USE, "\uD83D\uDD59"),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        for (i in 0 until Emoji.getNumGroups()) {
            val first = Emoji.getEmojisByGroup(i)[0]
            groupButtonsContainer?.addView(
                EmojiGroupButton(context, i, first.kv().getString()),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        // #41: Add search button
        addView(
            EmojiGroupButton(context, EmojiGridView.GROUP_SEARCH, "🔍"),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )

        // Create search input (hidden initially)
        searchInput = EditText(context).apply {
            hint = "Search emoji..."
            visibility = View.GONE
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            textSize = 14f

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    getEmojiGrid().searchEmojis(s?.toString() ?: "")
                }
            })
        }
    }

    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        if (isSearchMode) {
            // Show search input, hide group buttons
            groupButtonsContainer?.visibility = View.GONE
            if (searchInput?.parent == null) {
                addView(searchInput, 0, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            searchInput?.visibility = View.VISIBLE
            searchInput?.requestFocus()
            getEmojiGrid().setEmojiGroup(EmojiGridView.GROUP_SEARCH)
        } else {
            // Hide search input, show group buttons
            searchInput?.visibility = View.GONE
            searchInput?.setText("")
            groupButtonsContainer?.visibility = View.VISIBLE
            getEmojiGrid().setEmojiGroup(EmojiGridView.GROUP_LAST_USE)
        }
    }

    private fun getEmojiGrid(): EmojiGridView {
        return emojiGrid ?: run {
            val grid = (parent as ViewGroup).findViewById<EmojiGridView>(R.id.emoji_grid)
            emojiGrid = grid
            grid
        }
    }

    inner class EmojiGroupButton(
        context: Context,
        private val groupId: Int,
        symbol: String
    ) : Button(ContextThemeWrapper(context, R.style.emojiTypeButton), null, 0),
        View.OnTouchListener {

        init {
            text = symbol
            setOnTouchListener(this)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN) {
                return false
            }
            if (groupId == EmojiGridView.GROUP_SEARCH) {
                toggleSearchMode()
            } else {
                // Exit search mode if in it
                if (isSearchMode) {
                    isSearchMode = false
                    searchInput?.visibility = View.GONE
                    searchInput?.setText("")
                    groupButtonsContainer?.visibility = View.VISIBLE
                }
                getEmojiGrid().setEmojiGroup(groupId)
            }
            return true
        }
    }
}
