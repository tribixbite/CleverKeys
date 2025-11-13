package tribixbite.keyboard2

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import kotlinx.coroutines.*
import tribixbite.keyboard2.R

/**
 * Emoji group buttons bar for category navigation
 *
 * Creates horizontal buttons for emoji categories (Recent, People, Animals, etc.)
 * Each button shows the first emoji of its category and switches the emoji grid
 * when tapped.
 */
class EmojiGroupButtonsBar : LinearLayout {

    companion object {
        private const val TAG = "EmojiGroupButtonsBar"
    }

    private var emojiGrid: EmojiGridView? = null
    private val emoji by lazy { Emoji.getInstance(context) }

    constructor(context: Context, attrs: AttributeSet? = null) : super(context, attrs) {
        setupGroupButtons()
    }

    /**
     * Initialize emoji groups and create buttons
     * Bug #253 fix: Synchronous initialization like Java (no coroutines needed)
     */
    private fun setupGroupButtons() {
        orientation = HORIZONTAL

        try {
            // Emoji system already initialized via getInstance
            createGroupButtons()
        } catch (e: Exception) {
            logE("Failed to setup emoji group buttons", e)
        }
    }

    /**
     * Create buttons for all emoji groups
     */
    private fun createGroupButtons() {
        // Add "Recent" button first
        addGroupButton(EmojiGridView.GROUP_LAST_USE, "🕙") // Clock emoji for recent

        // Add buttons for each emoji group
        val numGroups = emoji.getNumGroups()
        for (i in 0 until numGroups) {
            val firstEmoji = emoji.getFirstEmojiOfGroup(i)
            val symbol = firstEmoji?.emoji ?: "😀"
            addGroupButton(i, symbol)
        }
    }

    /**
     * Add a single group button
     */
    private fun addGroupButton(groupId: Int, symbol: String) {
        val button = EmojiGroupButton(context, groupId, symbol)
        val layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            1.0f // Equal weight for all buttons
        )
        addView(button, layoutParams)
    }

    /**
     * Get the emoji grid view (lazy initialization)
     * Bug #134 fix: Use correct R.id.emoji_grid instead of android.R.id.list
     */
    private fun getEmojiGrid(): EmojiGridView? {
        if (emojiGrid == null) {
            // Find the emoji grid in the parent hierarchy
            val parentGroup = parent as? ViewGroup
            emojiGrid = parentGroup?.findViewById(R.id.emoji_grid) // Bug #134 fix
        }
        return emojiGrid
    }

    /**
     * Individual emoji group button
     * Bug #254 fix: Use ContextThemeWrapper for proper button styling
     */
    private inner class EmojiGroupButton(
        context: Context,
        private val groupId: Int,
        symbol: String
    ) : Button(ContextThemeWrapper(context, R.style.emojiTypeButton), null, 0), View.OnTouchListener {

        init {
            text = symbol
            setOnTouchListener(this)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN) {
                return false
            }

            // Switch emoji grid to this group
            getEmojiGrid()?.setEmojiGroup(groupId)
            return true
        }
    }

    /**
     * Set the emoji grid reference manually if needed
     */
    fun setEmojiGrid(grid: EmojiGridView) {
        emojiGrid = grid
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        android.util.Log.e(TAG, message, throwable)
    }
}