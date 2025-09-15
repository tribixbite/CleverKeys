package tribixbite.keyboard2

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.graphics.Color

/**
 * Suggestion bar for displaying prediction results
 * Kotlin implementation with clean UI patterns
 */
class SuggestionBar(context: Context) : LinearLayout(context) {
    
    companion object {
        private const val TAG = "SuggestionBar"
        private const val MAX_SUGGESTIONS = 5
    }
    
    private val suggestionButtons = mutableListOf<Button>()
    private var onSuggestionSelected: ((String) -> Unit)? = null
    
    init {
        orientation = HORIZONTAL
        setupSuggestionButtons()
    }
    
    private fun setupSuggestionButtons() {
        repeat(MAX_SUGGESTIONS) { index ->
            val button = Button(context).apply {
                text = ""
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    if (text.isNotBlank()) {
                        onSuggestionSelected?.invoke(text.toString())
                    }
                }
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                visibility = GONE
            }
            
            suggestionButtons.add(button)
            addView(button)
        }
    }
    
    /**
     * Set suggestion words
     */
    fun setSuggestions(words: List<String>) {
        logD("Setting ${words.size} suggestions")
        
        suggestionButtons.forEachIndexed { index, button ->
            if (index < words.size) {
                button.text = words[index]
                button.visibility = VISIBLE
            } else {
                button.text = ""
                button.visibility = GONE
            }
        }
    }
    
    /**
     * Clear all suggestions
     */
    fun clearSuggestions() {
        logD("Clearing suggestions")
        suggestionButtons.forEach { button ->
            button.text = ""
            button.visibility = GONE
        }
    }
    
    /**
     * Set suggestion selection callback
     */
    fun setOnSuggestionSelectedListener(listener: (String) -> Unit) {
        onSuggestionSelected = listener
    }
}