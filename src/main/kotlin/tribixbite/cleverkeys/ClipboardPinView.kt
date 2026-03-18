package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardPinView(ctx: Context, attrs: AttributeSet?) : MaxHeightListView(ctx, attrs) {

    private var entries: List<ClipboardEntry> = emptyList()
    private val adapter: ClipboardPinEntriesAdapter
    private val service: ClipboardHistoryService?
    // Track expanded state: position -> isExpanded
    private val expandedStates = mutableMapOf<Int, Boolean>()

    // Coroutine scope tied to window attach/detach lifecycle (#71: async DB loads)
    private var viewScope: CoroutineScope? = null
    private var loadJob: Job? = null

    init {
        service = ClipboardHistoryService.get_service(ctx)
        adapter = ClipboardPinEntriesAdapter()
        setAdapter(adapter)
        // Defer data load to onAttachedToWindow (async, off UI thread)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        refresh_pinned_items()
    }

    override fun onDetachedFromWindow() {
        loadJob?.cancel()
        loadJob = null
        viewScope?.cancel()
        viewScope = null
        super.onDetachedFromWindow()
    }

    /**
     * Refresh pinned items from database asynchronously.
     * #71: Was previously synchronous, causing UI freezes with many pinned entries.
     */
    fun refresh_pinned_items() {
        loadJob?.cancel()
        loadJob = viewScope?.launch {
            val pinnedEntries = withContext(Dispatchers.IO) {
                service?.getPinnedEntries() ?: emptyList()
            }
            // Back on Main thread
            entries = pinnedEntries
            adapter.notifyDataSetChanged()
            invalidate()
            updateParentMinHeight()
        }
    }

    /** Update parent ScrollView minHeight based on item count and user preference */
    private fun updateParentMinHeight() {
        if (entries.size >= 2) {
            // Read user preference for pinned section size (default 100dp = 2-3 rows)
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val minHeightDp = prefs.getString("clipboard_pinned_rows", "100")?.toIntOrNull() ?: 100
            val minHeightPx = (minHeightDp * resources.displayMetrics.density).toInt()
            minimumHeight = minHeightPx
        } else {
            // Clear minHeight when less than 2 items
            minimumHeight = 0
        }
    }

    /** Remove the entry at index [pos] entirely from database. */
    fun remove_entry(pos: Int) {
        if (pos < 0 || pos >= entries.size) return

        val clip = entries[pos].content

        // Delete entirely from database
        service?.removeHistoryEntry(clip)
        refresh_pinned_items()
    }

    /** Send the specified entry to the editor. */
    fun paste_entry(pos: Int) {
        ClipboardHistoryService.paste(entries[pos].content)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE && viewScope != null) {
            refresh_pinned_items()
        }
    }

    inner class ClipboardPinEntriesAdapter : BaseAdapter() {

        override fun getCount(): Int = entries.size

        override fun getItem(pos: Int): Any = entries[pos]

        override fun getItemId(pos: Int): Long = entries[pos].hashCode().toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: View.inflate(context, R.layout.clipboard_pin_entry, null)

            val entry = entries[pos]
            val text = entry.content
            val textView = v.findViewById<TextView>(R.id.clipboard_pin_text)
            val expandButton = v.findViewById<View>(R.id.clipboard_pin_expand)

            // Set text with timestamp appended
            textView.text = entry.getFormattedText(context)

            // Check if text contains newlines (multi-line)
            val isMultiLine = text.contains("\n")
            val isExpanded = expandedStates[pos] == true

            // Set maxLines based on expanded state (applies to all entries)
            if (isExpanded) {
                textView.maxLines = Int.MAX_VALUE
                textView.ellipsize = null
            } else {
                textView.maxLines = 1
                textView.ellipsize = TextUtils.TruncateAt.END
            }

            // Show expand button only for multi-line entries
            if (isMultiLine) {
                expandButton.visibility = View.VISIBLE
                expandButton.rotation = if (isExpanded) 180f else 0f

                // Handle expand button click for multi-line entries
                expandButton.setOnClickListener {
                    expandedStates[pos] = !isExpanded
                    notifyDataSetChanged()
                }
            } else {
                expandButton.visibility = View.GONE
            }

            // Make text clickable to expand/collapse (all entries)
            textView.setOnClickListener {
                expandedStates[pos] = !isExpanded
                notifyDataSetChanged()
            }

            v.findViewById<View>(R.id.clipboard_pin_paste).setOnClickListener {
                paste_entry(pos)
            }

            v.findViewById<View>(R.id.clipboard_pin_remove).setOnClickListener { view ->
                val d = AlertDialog.Builder(context)
                    .setTitle(R.string.clipboard_remove_confirm)
                    .setPositiveButton(R.string.clipboard_remove_confirmed) { _, _ ->
                        remove_entry(pos)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                Utils.show_dialog_on_ime(d, view.windowToken)
            }

            return v
        }
    }
}
