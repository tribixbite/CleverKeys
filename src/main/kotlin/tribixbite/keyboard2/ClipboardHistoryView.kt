package tribixbite.keyboard2

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.*
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Clipboard history view with reactive updates and search/filter
 * Kotlin implementation with Flow-based data binding
 *
 * Fix for Bug #471: Added search/filter functionality for clipboard history
 */
class ClipboardHistoryView(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val TAG = "ClipboardHistoryView"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var onItemSelected: ((String) -> Unit)? = null

    // Store all clipboard items for filtering (Bug #471 fix)
    private var allClipboardItems: List<String> = emptyList()
    private var searchEditText: EditText? = null
    
    init {
        orientation = VERTICAL
        setupHistoryView()
        observeClipboardHistory()
    }
    
    /**
     * Set item selection callback
     */
    fun setOnItemSelectedListener(listener: (String) -> Unit) {
        onItemSelected = listener
    }
    
    /**
     * Setup initial view
     * Bug #471 fix: Added search field for filtering clipboard items
     */
    private fun setupHistoryView() {
        // Header
        addView(TextView(context).apply {
            text = context.getString(R.string.clipboard_history_title)
            textSize = 18f
            setPadding(16, 16, 16, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // Bug #471 fix: Search/Filter field
        searchEditText = EditText(context).apply {
            hint = context.getString(R.string.clipboard_search_hint)
            setPadding(16, 8, 16, 8)
            setSingleLine(true)

            // Real-time filtering as user types
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterClipboardItems(s?.toString() ?: "")
                }
            })

            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(searchEditText)

        // Scroll view for history items (now filtered)
        addView(ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                id = android.R.id.list // Use as container for history items
            })
        })

        // Control buttons
        addView(createControlButtons())
    }
    
    private fun createControlButtons() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(16, 8, 16, 16)

        addView(Button(context).apply {
            text = context.getString(R.string.clipboard_clear_all)
            setOnClickListener {
                scope.launch {
                    ClipboardHistoryService.getService(context)?.clearHistory()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.clipboard_empty_title),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(Button(context).apply {
            text = context.getString(R.string.clipboard_close)
            setOnClickListener {
                visibility = GONE
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    
    /**
     * Observe clipboard history changes
     * Bug #471 fix: Store all items for filtering
     */
    private fun observeClipboardHistory() {
        scope.launch {
            val service = ClipboardHistoryService.getService(context)
            service?.subscribeToHistoryChanges()
                ?.flowOn(Dispatchers.Default)
                ?.collect { historyItems ->
                    withContext(Dispatchers.Main) {
                        // Store all items for filtering (Bug #471)
                        allClipboardItems = historyItems
                        // Apply current filter
                        val query = searchEditText?.text?.toString() ?: ""
                        filterClipboardItems(query)
                    }
                }
        }
    }

    /**
     * Filter clipboard items based on search query
     * Bug #471 fix: Real-time filtering of clipboard history
     */
    private fun filterClipboardItems(query: String) {
        val filtered = if (query.isBlank()) {
            allClipboardItems
        } else {
            allClipboardItems.filter { item ->
                item.contains(query, ignoreCase = true)
            }
        }
        updateHistoryDisplay(filtered, query)
    }

    /**
     * Update history display with filtered items
     * Bug #471 fix: Show "No results" message when filter returns empty
     */
    private fun updateHistoryDisplay(items: List<String>, searchQuery: String = "") {
        val container = findViewById<LinearLayout>(android.R.id.list) ?: return
        container.removeAllViews()

        if (items.isEmpty()) {
            // Show "No results" message when filtered list is empty
            val message = if (searchQuery.isNotBlank()) {
                context.getString(R.string.clipboard_no_results)
            } else {
                context.getString(R.string.clipboard_empty_title)
            }

            container.addView(TextView(context).apply {
                text = message
                textSize = 14f
                setPadding(16, 32, 16, 32)
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
            })
        } else {
            items.forEach { item ->
                container.addView(createHistoryItemView(item))
            }
        }
    }
    
    /**
     * Create view for history item
     */
    private fun createHistoryItemView(item: String): View {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.TRANSPARENT)

            // Text content
            addView(TextView(context).apply {
                text = item.take(100) + if (item.length > 100) "..." else ""
                textSize = 14f
                maxLines = 2
                setOnClickListener {
                    onItemSelected?.invoke(item)
                }
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })

            // Pin button
            addView(Button(context).apply {
                text = context.getString(R.string.clipboard_pin)
                textSize = 12f
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    scope.launch {
                        ClipboardHistoryService.getService(context)?.setPinnedStatus(item, true)
                    }
                }
            })

            // Delete button
            addView(Button(context).apply {
                text = context.getString(R.string.clipboard_delete)
                textSize = 12f
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    scope.launch {
                        ClipboardHistoryService.getService(context)?.removeHistoryEntry(item)
                    }
                }
            })
        }
    }
    
    /**
     * Show clipboard history
     */
    fun show() {
        visibility = VISIBLE
        scope.launch {
            ClipboardHistoryService.getService(context)?.addCurrentClip()
        }
    }

    /**
     * Hide clipboard history
     */
    fun hide() {
        visibility = GONE
    }

    /**
     * Cleanup
     */
    fun cleanup() {
        scope.cancel()
    }
}