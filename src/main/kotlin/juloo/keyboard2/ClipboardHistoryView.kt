package juloo.keyboard2

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Clipboard history view with reactive updates
 * Kotlin implementation with Flow-based data binding
 */
class ClipboardHistoryView(context: Context) : LinearLayout(context) {
    
    companion object {
        private const val TAG = "ClipboardHistoryView"
    }
    
    private val clipboardService = ClipboardHistoryService(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var onItemSelected: ((String) -> Unit)? = null
    
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
     */
    private fun setupHistoryView() {
        // Header
        addView(TextView(context).apply {
            text = "📋 Clipboard History"
            textSize = 18f
            setPadding(16, 16, 16, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        
        // Scroll view for history items
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
            text = "Clear All"
            setOnClickListener { 
                clipboardService.clearHistory()
                context.toast("Clipboard history cleared")
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        
        addView(Button(context).apply {
            text = "Close"
            setOnClickListener { 
                visibility = GONE
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    
    /**
     * Observe clipboard history changes
     */
    private fun observeClipboardHistory() {
        scope.launch {
            clipboardService.getHistoryFlow()
                .flowOn(Dispatchers.Default)
                .collect { historyItems ->
                    withContext(Dispatchers.Main) {
                        updateHistoryDisplay(historyItems)
                    }
                }
        }
    }
    
    /**
     * Update history display
     */
    private fun updateHistoryDisplay(items: List<ClipboardHistoryService.ClipboardItem>) {
        val container = findViewById<LinearLayout>(android.R.id.list) ?: return
        container.removeAllViews()
        
        items.forEach { item ->
            container.addView(createHistoryItemView(item))
        }
    }
    
    /**
     * Create view for history item
     */
    private fun createHistoryItemView(item: ClipboardHistoryService.ClipboardItem): View {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(if (item.isPinned) 0x22FFFF00 else Color.TRANSPARENT)
            
            // Text content
            addView(TextView(context).apply {
                text = item.text.take(100) + if (item.text.length > 100) "..." else ""
                textSize = 14f
                maxLines = 2
                setOnClickListener { 
                    onItemSelected?.invoke(item.text)
                }
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            
            // Pin button
            addView(Button(context).apply {
                text = if (item.isPinned) "📌" else "📍"
                textSize = 12f
                setPadding(8, 4, 8, 4)
                setOnClickListener { 
                    clipboardService.togglePin(item)
                }
            })
            
            // Delete button
            addView(Button(context).apply {
                text = "🗑️"
                textSize = 12f
                setPadding(8, 4, 8, 4)
                setOnClickListener { 
                    clipboardService.deleteItem(item)
                }
            })
        }
    }
    
    /**
     * Show clipboard history
     */
    fun show() {
        visibility = VISIBLE
        clipboardService.startMonitoring()
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
        clipboardService.cleanup()
    }
}