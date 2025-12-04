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
    GridView(context, attrs), AdapterView.OnItemClickListener {

    private var emojiArray: List<Emoji> = emptyList()
    private val lastUsed: MutableMap<Emoji, Int> = mutableMapOf()

    init {
        Emoji.init(context.resources)
        migrateOldPrefs() // TODO: Remove at some point in future
        onItemClickListener = this
        loadLastUsed()
        setEmojiGroup(if (lastUsed.isEmpty()) 0 else GROUP_LAST_USE)
    }

    fun setEmojiGroup(group: Int) {
        emojiArray = if (group == GROUP_LAST_USE) {
            getLastEmojis()
        } else {
            Emoji.getEmojisByGroup(group)
        }
        adapter = EmojiViewAdapter(context, emojiArray)
    }

    override fun onItemClick(parent: AdapterView<*>?, v: View, pos: Int, id: Long) {
        val config = Config.globalConfig()
        val emoji = emojiArray[pos]
        val used = lastUsed[emoji]
        lastUsed[emoji] = (used ?: 0) + 1
        config.handler?.key_up(emoji.kv(), Pointers.Modifiers.EMPTY)
        saveLastUsed() // TODO: opti
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

    private fun migrateOldPrefs() {
        val prefs = try {
            emojiSharedPreferences()
        } catch (e: Exception) {
            return
        }

        val lastUsed = prefs.getStringSet(LAST_USE_PREF, null)
        if (lastUsed != null && !prefs.getBoolean(MIGRATION_CHECK_KEY, false)) {
            val edit = prefs.edit()
            edit.clear()

            val lastUsedNew = mutableSetOf<String>()
            for (entry in lastUsed) {
                val data = entry.split("-", limit = 2)
                try {
                    val count = data[0].toInt()
                    val newValue = Emoji.mapOldNameToValue(data[1])
                    lastUsedNew.add("$count-$newValue")
                } catch (ignored: IllegalArgumentException) {
                }
            }
            edit.putStringSet(LAST_USE_PREF, lastUsedNew)
            edit.putBoolean(MIGRATION_CHECK_KEY, true)
            edit.apply()
        }
    }

    class EmojiView(context: Context) : TextView(context) {
        fun setEmoji(emoji: Emoji) {
            text = emoji.kv().getString()
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
        private const val LAST_USE_PREF = "emoji_last_use"
        private const val MIGRATION_CHECK_KEY = "MIGRATION_COMPLETE"
    }
}
