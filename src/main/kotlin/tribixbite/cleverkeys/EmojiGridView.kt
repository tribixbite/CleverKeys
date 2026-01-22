package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView

class EmojiGridView(context: Context, attrs: AttributeSet?) :
    GridView(context, attrs), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private var emojiArray: List<Emoji> = emptyList()
    private val lastUsed: MutableMap<Emoji, Int> = mutableMapOf()
    private var saveScheduled = false  // Debounce flag for saveLastUsed

    // #41 v8: Reference to search manager for bypassing search routing on emoji selection
    private var searchManager: EmojiSearchManager? = null
    // #41 v10: Reference to service for suggestion bar messages (Toast suppressed on Android 13+ IME)
    private var service: CleverKeysService? = null

    // v1.2.6: Tooltip manager for displaying emoji names on long-press
    // Uses PopupWindow for reliable display within IME context
    private val tooltipManager: EmojiTooltipManager by lazy { EmojiTooltipManager(context) }
    private val tooltipDismissHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Set the search manager reference.
     * #41 v8: Needed to temporarily disable search routing when emoji is selected.
     */
    fun setSearchManager(manager: EmojiSearchManager) {
        this.searchManager = manager
    }

    /**
     * Set the service reference.
     * #41 v10: Needed for showing emoji name in suggestion bar on long-press.
     */
    fun setService(service: CleverKeysService) {
        this.service = service
    }

    init {
        Emoji.init(context.resources)
        onItemClickListener = this
        onItemLongClickListener = this  // Long-press shows emoji name in tooltip
        loadLastUsed()
        setEmojiGroup(if (lastUsed.isEmpty()) 0 else GROUP_LAST_USE)
        setupScrollListener()  // v1.2.6: Dismiss tooltip on scroll
    }

    fun setEmojiGroup(group: Int) {
        emojiArray = if (group == GROUP_LAST_USE) {
            getLastEmojis()
        } else if (group == GROUP_SEARCH) {
            emptyList() // Will be populated by search
        } else {
            Emoji.getEmojisByGroup(group)
        }
        adapter = EmojiViewAdapter(context, emojiArray)
    }

    /**
     * #41: Search emojis by name and display results.
     * @param query The search query
     * @return Number of results found
     */
    fun searchEmojis(query: String): Int {
        emojiArray = if (query.isBlank()) {
            getLastEmojis() // Show last used when empty
        } else {
            Emoji.searchByName(query)
        }
        adapter = EmojiViewAdapter(context, emojiArray)
        return emojiArray.size
    }

    override fun onItemClick(parent: AdapterView<*>?, v: View, pos: Int, id: Long) {
        val config = Config.globalConfig()
        val emoji = emojiArray[pos]
        val used = lastUsed[emoji]
        lastUsed[emoji] = (used ?: 0) + 1

        // #41 v8: Temporarily disable search routing so emoji goes to app, not search bar
        searchManager?.onEmojiSelected()

        config.handler?.key_up(emoji.kv(), Pointers.Modifiers.EMPTY)

        // #41 v8: Re-enable search routing for continued searching
        searchManager?.onEmojiInserted()

        scheduleSaveLastUsed()
    }

    /**
     * Debounced save - batches rapid emoji selections into single write
     */
    private fun scheduleSaveLastUsed() {
        if (saveScheduled) return
        saveScheduled = true
        postDelayed({
            saveScheduled = false
            saveLastUsed()
        }, 500)  // 500ms debounce
    }

    /**
     * v1.2.6: Long-press handler to show emoji name in tooltip popup.
     * Uses PopupWindow for reliable display within IME context (Toast has issues).
     */
    override fun onItemLongClick(parent: AdapterView<*>?, view: View?, pos: Int, id: Long): Boolean {
        if (pos < 0 || pos >= emojiArray.size || view == null) return false

        val emoji = emojiArray[pos]
        val emojiStr = emoji.kv().getString()
        val name = Emoji.getEmojiName(emojiStr) ?: "unknown"

        // Show tooltip anchored to the pressed emoji cell
        tooltipManager.show(view, emojiStr, name)

        // Auto-dismiss after 2 seconds
        tooltipDismissHandler.removeCallbacksAndMessages(null)
        tooltipDismissHandler.postDelayed({
            tooltipManager.dismiss()
        }, 2000)

        return true  // Consume the event
    }

    /**
     * Set up scroll listener to dismiss tooltip when scrolling.
     */
    private fun setupScrollListener() {
        setOnScrollListener(object : OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {
                if (scrollState != OnScrollListener.SCROLL_STATE_IDLE) {
                    tooltipDismissHandler.removeCallbacksAndMessages(null)
                    tooltipManager.dismiss()
                }
            }

            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                // Not needed
            }
        })
    }

    private fun getLastEmojis(): List<Emoji> {
        val list = lastUsed.keys.toMutableList()
        list.sortByDescending { lastUsed[it] ?: 0 }
        return list
    }

    private fun saveLastUsed() {
        val edit = try {
            emojiSharedPreferences().edit()
        } catch (_: Exception) {
            return
        }

        val set = lastUsed.map { (emoji, count) ->
            "$count-${emoji.kv().getString()}"
        }.toSet()

        edit.putStringSet(LAST_USE_PREF, set)
        edit.apply()
    }

    private fun loadLastUsed() {
        lastUsed.clear()
        val prefs = try {
            emojiSharedPreferences()
        } catch (_: Exception) {
            return
        }

        val lastUseSet = prefs.getStringSet(LAST_USE_PREF, null) ?: return

        for (emojiData in lastUseSet) {
            val data = emojiData.split("-", limit = 2)
            if (data.size != 2) continue

            val emoji = Emoji.getEmojiByString(data[1]) ?: continue
            lastUsed[emoji] = data[0].toIntOrNull() ?: continue
        }
    }

    private fun emojiSharedPreferences(): SharedPreferences {
        return context.getSharedPreferences("emoji_last_use", Context.MODE_PRIVATE)
    }

    class EmojiView(context: Context) : TextView(context) {
        private var defaultTextSize: Float = 0f

        init {
            // Store the default text size from theme for regular emojis
            defaultTextSize = textSize
            // Ensure single line for emoticons
            maxLines = 1
            isSingleLine = true
        }

        fun setEmoji(emoji: Emoji) {
            val emojiStr = emoji.kv().getString()
            text = emojiStr

            // Detect text emoticons (multi-character, ASCII-based like kaomoji)
            // Regular emojis: short strings mostly made of surrogate pairs or high codepoints
            // Emoticons: longer strings with ASCII characters like :) or ¯\_(ツ)_/¯
            val isTextEmoticon = isEmoticon(emojiStr)

            if (isTextEmoticon) {
                // Scale text size based on emoticon length to fit in cell
                // Column width is ~45sp, emoticons can be 2-20+ chars
                val len = emojiStr.length
                val scaledSize = when {
                    len <= 3 -> 16f   // Short: :) :D
                    len <= 6 -> 12f   // Medium: ;-) :-P
                    len <= 10 -> 9f   // Long: (╯°□°)
                    else -> 7f        // Very long: ¯\_(ツ)_/¯
                }
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledSize)
            } else {
                // Regular emoji - use default size from theme
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, defaultTextSize)
            }
        }

        /**
         * Detect if a string is a text emoticon vs a Unicode emoji.
         * Text emoticons contain ASCII punctuation/letters, while emojis are mostly
         * high Unicode codepoints (surrogate pairs) or emoji symbols (U+1F000+).
         */
        private fun isEmoticon(str: String): Boolean {
            if (str.length <= 2) return false  // Single emoji can be 1-2 chars (with variation selector)

            // Count ASCII printable chars (common in emoticons)
            var asciiCount = 0
            var emojiCount = 0
            for (char in str) {
                when {
                    char.code in 0x20..0x7E -> asciiCount++  // ASCII printable
                    Character.isHighSurrogate(char) || Character.isLowSurrogate(char) -> emojiCount++
                    char.code >= 0x2600 -> emojiCount++  // Unicode symbols/emoji
                }
            }

            // If more ASCII than emoji codepoints, it's likely an emoticon
            return asciiCount > emojiCount
        }
    }

    class EmojiViewAdapter(
        context: Context,
        private val emojiArray: List<Emoji>?
    ) : BaseAdapter() {

        private val buttonContext = ContextThemeWrapper(context, R.style.emojiGridButton)

        override fun getCount(): Int {
            return emojiArray?.size ?: 0
        }

        override fun getItem(pos: Int): Any? {
            return emojiArray?.get(pos)
        }

        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? EmojiView) ?: EmojiView(buttonContext)
            emojiArray?.get(pos)?.let { view.setEmoji(it) }
            return view
        }
    }

    companion object {
        const val GROUP_LAST_USE = -1
        const val GROUP_SEARCH = -2  // #41: Search mode
        private const val LAST_USE_PREF = "emoji_last_use"
    }
}
