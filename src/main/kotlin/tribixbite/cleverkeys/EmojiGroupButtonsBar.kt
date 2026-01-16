package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * Horizontal bar displaying emoji group category buttons.
 *
 * #41 v4: Category selection now clears search query via EmojiSearchManager.
 */
class EmojiGroupButtonsBar(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private var emojiGrid: EmojiGridView? = null
    private var searchManager: EmojiSearchManager? = null

    init {
        orientation = HORIZONTAL
        Emoji.init(context.resources)

        // Add clock button for recently used emojis
        addView(
            EmojiGroupButton(context, EmojiGridView.GROUP_LAST_USE, "\uD83D\uDD59"),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        // Add group buttons for each emoji category
        for (i in 0 until Emoji.getNumGroups()) {
            val first = Emoji.getEmojisByGroup(i)[0]
            addView(
                EmojiGroupButton(context, i, first.kv().getString()),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    /**
     * Set the search manager for clearing search on category selection.
     */
    fun setSearchManager(manager: EmojiSearchManager) {
        this.searchManager = manager
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
            // Clear search when category is selected
            searchManager?.onCategorySelected()
            getEmojiGrid().setEmojiGroup(groupId)
            return true
        }
    }
}
