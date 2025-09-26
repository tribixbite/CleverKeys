package tribixbite.keyboard2

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import tribixbite.keyboard2.R

/**
 * Clipboard pin view for managing pinned clipboard entries
 *
 * Extends NonScrollListView to show a list of pinned clipboard items
 * with paste and remove functionality. Items are persisted to SharedPreferences
 * using JSON serialization.
 */
class ClipboardPinView : NonScrollListView {

    companion object {
        private const val TAG = "ClipboardPinView"

        /** Preference file name that stores pinned clipboards */
        private const val PERSIST_FILE_NAME = "clipboards"

        /** Preference name for pinned clipboards */
        private const val PERSIST_PREF = "pinned"

        /**
         * Load pinned clipboard entries from SharedPreferences
         */
        fun loadFromPrefs(store: SharedPreferences, destination: MutableList<String>) {
            val arrayString = store.getString(PERSIST_PREF, null) ?: return

            try {
                val jsonArray = JSONArray(arrayString)
                for (i in 0 until jsonArray.length()) {
                    destination.add(jsonArray.getString(i))
                }
            } catch (e: JSONException) {
                android.util.Log.e(TAG, "Failed to load pinned clipboards", e)
            }
        }

        /**
         * Save pinned clipboard entries to SharedPreferences
         */
        fun saveToPrefs(store: SharedPreferences, entries: List<String>) {
            val jsonArray = JSONArray()
            entries.forEach { entry ->
                jsonArray.put(entry)
            }

            store.edit()
                .putString(PERSIST_PREF, jsonArray.toString())
                .apply() // Use apply() instead of commit() for better performance
        }
    }

    private val entries = mutableListOf<String>()
    private lateinit var adapter: ClipboardPinEntriesAdapter
    private lateinit var persistStore: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize()
    }

    /**
     * Initialize the clipboard pin view
     */
    private fun initialize() {
        persistStore = context.getSharedPreferences("pinned_clipboards", Context.MODE_PRIVATE)
        loadFromPrefs()
        adapter = ClipboardPinEntriesAdapter()
        setAdapter(adapter)
    }

    /**
     * Pin a clipboard entry and persist the change
     */
    fun addEntry(text: String) {
        if (text.isNotBlank() && !entries.contains(text)) {
            entries.add(text)
            adapter.notifyDataSetChanged()
            persist()
            invalidate()
        }
    }

    /**
     * Remove the entry at the specified position and persist the change
     */
    fun removeEntry(position: Int) {
        if (position in 0 until entries.size) {
            entries.removeAt(position)
            adapter.notifyDataSetChanged()
            persist()
            invalidate()
        }
    }

    /**
     * Paste the specified entry to the editor
     */
    fun pasteEntry(position: Int) {
        if (position in 0 until entries.size) {
            scope.launch {
                try {
                    ClipboardHistoryService.paste(entries[position])
                } catch (e: Exception) {
                    logE("Failed to paste clipboard entry", e)
                }
            }
        }
    }

    /**
     * Persist current entries to SharedPreferences
     */
    private fun persist() {
        scope.launch(Dispatchers.IO) {
            saveToPrefs(persistStore, entries)
        }
    }

    /**
     * Load entries from SharedPreferences
     */
    private fun loadFromPrefs() {
        loadFromPrefs(persistStore, entries)
    }

    /**
     * Cleanup coroutines
     */
    fun cleanup() {
        scope.cancel()
    }

    /**
     * Adapter for clipboard pin entries
     */
    private inner class ClipboardPinEntriesAdapter : BaseAdapter() {

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): String = entries[position]

        override fun getItemId(position: Int): Long = entries[position].hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.clipboard_pin_entry, parent, false)

            // Set the text content
            val textView = view.findViewById<TextView>(R.id.clipboard_pin_text)
            textView.text = entries[position]

            // Set up paste button
            val pasteButton = view.findViewById<View>(R.id.clipboard_pin_paste)
            pasteButton.setOnClickListener { pasteEntry(position) }

            // Set up remove button with confirmation dialog
            val removeButton = view.findViewById<View>(R.id.clipboard_pin_remove)
            removeButton.setOnClickListener { showRemoveConfirmDialog(position) }

            return view
        }

        /**
         * Show confirmation dialog before removing entry
         */
        private fun showRemoveConfirmDialog(position: Int) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.clipboard_remove_confirm)
                .setPositiveButton(R.string.clipboard_remove_confirmed) { _, _ ->
                    removeEntry(position)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            // Show dialog on IME using utility function
            Utils.showDialogOnIme(dialog, rootView.windowToken)
        }
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        android.util.Log.e(TAG, message, throwable)
    }

}