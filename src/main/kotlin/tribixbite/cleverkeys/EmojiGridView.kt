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
        onItemLongClickListener = this  // #41 v10: Long-press shows emoji name
        loadLastUsed()
        setEmojiGroup(if (lastUsed.isEmpty()) 0 else GROUP_LAST_USE)
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
     * #41 v10: Long-press handler to show emoji name in suggestion bar.
     * Uses showSuggestionBarMessage() instead of Toast (suppressed on Android 13+ IME).
     */
    override fun onItemLongClick(parent: AdapterView<*>?, view: View?, pos: Int, id: Long): Boolean {
        if (pos < 0 || pos >= emojiArray.size) return false

        val emoji = emojiArray[pos]
        val emojiStr = emoji.kv().getString()
        val name = Emoji.getEmojiName(emojiStr) ?: emojiStr

        // Format: "😀 grinning" or just the emoji if no name found
        val displayText = if (name != emojiStr) "$emojiStr $name" else emojiStr
        service?.showSuggestionBarMessage(displayText, 2000L)
        return true  // Consume the event
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
        init {
            // Enable auto-sizing for text emoticons that are wider than single emojis
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            }
        }

        fun setEmoji(emoji: Emoji) {
            val emojiStr = emoji.kv().getString()
            text = emojiStr

            // Detect text emoticons (multi-character, non-emoji text like kaomoji)
            // Single emojis are typically 1-4 codepoints, emoticons are longer ASCII/Unicode text
            val isTextEmoticon = emojiStr.length > 4 && !emojiStr.all { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) || it.code > 0x1F000 }

            if (isTextEmoticon) {
                // Use smaller text for emoticons to prevent overflow
                // Auto-sizing handles this on API 26+, but set max for older devices
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    textSize = 12f // Smaller fixed size for pre-O devices
                }
            }
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
