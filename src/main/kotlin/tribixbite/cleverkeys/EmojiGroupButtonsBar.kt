package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

class EmojiGroupButtonsBar(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private var emojiGrid: EmojiGridView? = null

    init {
        Emoji.init(context.resources)
        addGroup(EmojiGridView.GROUP_LAST_USE, "\uD83D\uDD59")
        for (i in 0 until Emoji.getNumGroups()) {
            val first = Emoji.getEmojisByGroup(i)[0]
            addGroup(i, first.kv().getString())
        }
    }

    private fun addGroup(id: Int, symbol: String) {
        addView(
            EmojiGroupButton(context, id, symbol),
            LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT, 1f)
        )
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
            getEmojiGrid().setEmojiGroup(groupId)
            return true
        }
    }
}
