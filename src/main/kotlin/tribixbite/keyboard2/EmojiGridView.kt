package tribixbite.keyboard2

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import kotlinx.coroutines.*

/**
 * Emoji grid view for emoji selection
 * Kotlin implementation with reactive emoji loading
 */
class EmojiGridView(context: Context) : GridLayout(context) {
    
    companion object {
        private const val TAG = "EmojiGridView"
        private const val EMOJI_SIZE_DP = 48
        private const val EMOJI_COLUMNS = 8

        // Group constants for emoji categories
        const val GROUP_LAST_USE = -1
    }
    
    private val emoji = Emoji.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var onEmojiSelected: ((String) -> Unit)? = null
    
    init {
        columnCount = EMOJI_COLUMNS
        setupEmojis()
    }
    
    /**
     * Set emoji selection callback
     */
    fun setOnEmojiSelectedListener(listener: (String) -> Unit) {
        onEmojiSelected = listener
    }
    
    /**
     * Setup emoji grid
     */
    private fun setupEmojis() {
        scope.launch {
            try {
                if (emoji.loadEmojis()) {
                    val recentEmojis = emoji.getRecentEmojis(context)
                    
                    withContext(Dispatchers.Main) {
                        populateEmojiGrid(recentEmojis)
                    }
                }
            } catch (e: Exception) {
                logE("Failed to load emojis", e)
            }
        }
    }
    
    /**
     * Populate grid with emoji buttons
     */
    private fun populateEmojiGrid(emojis: List<Emoji.EmojiData>) {
        removeAllViews()
        
        emojis.forEach { emojiData ->
            val button = EmojiButton(context, emojiData)
            button.setOnClickListener {
                onEmojiSelected?.invoke(emojiData.emoji)
            }
            addView(button)
        }
    }
    
    /**
     * Show emoji group
     */
    fun showGroup(group: String) {
        scope.launch {
            val groupEmojis = emoji.getEmojisByGroup(group)
            withContext(Dispatchers.Main) {
                populateEmojiGrid(groupEmojis)
            }
        }
    }
    
    /**
     * Search emojis
     */
    fun searchEmojis(query: String) {
        scope.launch {
            val searchResults = emoji.searchEmojis(query)
            withContext(Dispatchers.Main) {
                populateEmojiGrid(searchResults)
            }
        }
    }
    
    /**
     * Individual emoji button
     */
    private class EmojiButton(context: Context, private val emojiData: Emoji.EmojiData) : View(context) {
        
        private val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = Utils.dpToPx(32f, resources.displayMetrics)
        }
        
        init {
            layoutParams = GridLayout.LayoutParams().apply {
                width = Utils.dpToPx(EMOJI_SIZE_DP.toFloat(), resources.displayMetrics).toInt()
                height = Utils.dpToPx(EMOJI_SIZE_DP.toFloat(), resources.displayMetrics).toInt()
                setMargins(4, 4, 4, 4)
            }

            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true

            // Accessibility: Set content description for screen readers
            contentDescription = emojiData.description
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val centerX = width / 2f
            val centerY = height / 2f - (paint.ascent() + paint.descent()) / 2
            
            canvas.drawText(emojiData.emoji, centerX, centerY, paint)
        }
        
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    setBackgroundColor(0x22FFFFFF)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    setBackgroundColor(Color.TRANSPARENT)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    setBackgroundColor(Color.TRANSPARENT)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /**
     * Set emoji group (compatible with original EmojiGroupButtonsBar)
     */
    fun setEmojiGroup(groupId: Int) {
        scope.launch {
            try {
                val emojis = if (groupId == GROUP_LAST_USE) {
                    // Show recently used emojis
                    emoji.getRecentEmojis(context)
                } else {
                    // Show emojis from specific group
                    emoji.getEmojisByGroupIndex(groupId)
                }

                withContext(Dispatchers.Main) {
                    populateEmojiGrid(emojis)
                }
            } catch (e: Exception) {
                logE("Failed to set emoji group $groupId", e)
            }
        }
    }

    /**
     * Cleanup coroutines when view is detached
     * Bug #135 fix: Automatic cleanup instead of manual cleanup() call
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    /**
     * Manual cleanup (deprecated - use onDetachedFromWindow)
     */
    @Deprecated("Cleanup is now automatic via onDetachedFromWindow()", ReplaceWith(""))
    fun cleanup() {
        scope.cancel()
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        android.util.Log.e(TAG, message, throwable)
    }
}