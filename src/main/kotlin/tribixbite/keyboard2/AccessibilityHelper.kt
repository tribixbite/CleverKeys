package tribixbite.keyboard2

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility support for CleverKeys
 * Kotlin implementation with modern accessibility patterns
 */
class AccessibilityHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "AccessibilityHelper"
    }
    
    /**
     * Setup accessibility for keyboard view
     */
    fun setupKeyboardAccessibility(view: View, keys: List<Keyboard2View.DrawnKey>) {
        view.apply {
            // Enable accessibility
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            
            // Set content description
            contentDescription = "CleverKeys keyboard with neural swipe prediction"
            
            // Setup custom accessibility delegate
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    
                    info.className = "Keyboard"
                    info.contentDescription = "CleverKeys virtual keyboard"
                    info.isClickable = true
                    info.isEnabled = true
                    
                    // Add virtual children for each key
                    keys.forEachIndexed { index, key ->
                        val childInfo = AccessibilityNodeInfo.obtain().apply {
                            setBoundsInParent(Rect(
                                key.bounds.left.toInt(),
                                key.bounds.top.toInt(), 
                                key.bounds.right.toInt(),
                                key.bounds.bottom.toInt()
                            ))
                            contentDescription = getKeyDescription(key.keyValue)
                            className = "Button"
                            isClickable = true
                            isEnabled = true
                            addAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        info.addChild(host, index)
                    }
                }
                
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    when (action) {
                        AccessibilityNodeInfo.ACTION_CLICK -> {
                            // Handle accessibility click
                            return true
                        }
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        }
    }
    
    /**
     * Get accessibility description for key
     */
    private fun getKeyDescription(keyValue: KeyValue): String {
        return when (keyValue.kind) {
            KeyValue.Kind.Char -> {
                val char = keyValue.char
                when {
                    char.isLetter() -> "Letter ${char.uppercase()}"
                    char.isDigit() -> "Number $char"
                    char == ' ' -> "Space"
                    else -> "Symbol $char"
                }
            }
            KeyValue.Kind.Event -> {
                when (keyValue.eventCode) {
                    android.view.KeyEvent.KEYCODE_DEL -> "Backspace"
                    android.view.KeyEvent.KEYCODE_ENTER -> "Enter"
                    android.view.KeyEvent.KEYCODE_TAB -> "Tab"
                    else -> "Special key ${keyValue.eventCode}"
                }
            }
            KeyValue.Kind.String -> "Text: ${keyValue.string}"
            KeyValue.Kind.Modifier -> "Modifier key"
            else -> "Key"
        }
    }
    
    /**
     * Announce prediction results to screen reader
     */
    fun announcePredictions(view: View, predictions: List<String>) {
        if (predictions.isNotEmpty()) {
            val announcement = "Predictions available: ${predictions.take(3).joinToString(", ")}"
            view.announceForAccessibility(announcement)
        }
    }
    
    /**
     * Announce swipe gesture feedback
     */
    fun announceSwipeGesture(view: View, gestureType: String, result: String?) {
        val announcement = when {
            result != null -> "Swipe $gestureType completed: $result"
            else -> "Swipe $gestureType detected"
        }
        view.announceForAccessibility(announcement)
    }
    
    /**
     * Setup accessibility for emoji grid
     */
    fun setupEmojiAccessibility(emojiView: EmojiGridView, emojis: List<Emoji.EmojiData>) {
        emojiView.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Emoji grid with ${emojis.size} emojis"
        }
    }
    
    /**
     * Setup accessibility for suggestion bar
     */
    fun setupSuggestionAccessibility(suggestionBar: SuggestionBar, suggestions: List<String>) {
        suggestionBar.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = if (suggestions.isNotEmpty()) {
                "Suggestions: ${suggestions.joinToString(", ")}"
            } else {
                "No suggestions available"
            }
        }
    }
}