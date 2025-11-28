package tribixbite.keyboard2

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Screen reader integration manager for TalkBack support
 * Provides accessibility node tree with proper content descriptions
 * Fix for Bug #377 (CATASTROPHIC): NO screen reader mode - violates ADA/WCAG
 */
class ScreenReaderManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenReaderManager"
    }

    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var isScreenReaderActive = false

    /**
     * Check if screen reader (TalkBack) is active
     */
    fun isScreenReaderActive(): Boolean {
        return accessibilityManager.isEnabled &&
               accessibilityManager.isTouchExplorationEnabled
    }

    /**
     * Initialize screen reader support for keyboard view
     */
    fun initializeScreenReader(keyboardView: View, getKeyAtPosition: (Float, Float) -> KeyboardData.Key?) {
        // Update screen reader state
        isScreenReaderActive = isScreenReaderActive()

        if (!isScreenReaderActive) {
            Log.d(TAG, "Screen reader not active, skipping initialization")
            return
        }

        Log.d(TAG, "Initializing screen reader support for keyboard")

        // Make view important for accessibility
        keyboardView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        // Set up accessibility delegate for custom touch exploration
        ViewCompat.setAccessibilityDelegate(keyboardView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                // Set keyboard role
                info.className = "android.widget.GridView"
                info.contentDescription = "Keyboard"

                // Enable touch exploration
                info.addAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)
                info.addAction(AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS)

                // Add key selection actions
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        "Tap to type character"
                    )
                )
            }

            override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onPopulateAccessibilityEvent(host, event)

                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                        event.contentDescription = "Keyboard view focused"
                    }
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                        // Announce current key when focused
                        event.text.add("Exploring keyboard")
                    }
                }
            }
        })

        Log.d(TAG, "✅ Screen reader support initialized")
    }

    /**
     * Create accessibility node info for a specific key
     * TODO: Fix function signature - Key doesn't have x/y/width/height properties
     * TODO: Fix setParent call - parent parameter type mismatch
     * This function is part of Bug #377 accessibility work and needs proper integration
     */
    fun createKeyAccessibilityNode(
        key: KeyboardData.Key,
        parentView: View,
        keyBounds: android.graphics.Rect,
        viewId: Int
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()

        // Set parent and view ID
        node.setParent(parentView)
        node.setSource(parentView, viewId)

        // Set bounds (key position)
        node.setBoundsInParent(keyBounds)

        // Set content description based on key type
        val keyValue = key.keys[0] ?: KeyValue.CharKey(' ', " ")
        node.contentDescription = getKeyDescription(keyValue)

        // Mark as clickable
        node.isClickable = true
        node.isFocusable = true
        node.isEnabled = true

        // Add click action
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        return node
    }

    /**
     * Get human-readable description for a key
     */
    private fun getKeyDescription(keyValue: KeyValue): String {
        return when (keyValue) {
            // Character keys
            is KeyValue.CharKey -> {
                val char = keyValue.char
                when {
                    char.isLetter() -> {
                        if (char.isUpperCase()) {
                            "Capital ${char.lowercaseChar()}"
                        } else {
                            char.toString()
                        }
                    }
                    char.isDigit() -> char.toString()
                    else -> getSpecialCharacterName(char)
                }
            }

            // Special keys
            is KeyValue.EventKey -> {
                when (keyValue.event) {
                    KeyValue.Event.ACTION -> "Enter"
                    KeyValue.Event.SWITCH_TEXT -> "Switch to text layout"
                    KeyValue.Event.SWITCH_NUMERIC -> "Switch to numbers"
                    KeyValue.Event.SWITCH_EMOJI -> "Switch to emoji"
                    KeyValue.Event.CONFIG -> "Settings"
                    KeyValue.Event.CAPS_LOCK -> "Caps lock"
                    else -> "Special key"
                }
            }

            // KeyEvent keys
            is KeyValue.KeyEventKey -> {
                when (keyValue.keyCode) {
                    android.view.KeyEvent.KEYCODE_DEL -> "Backspace"
                    android.view.KeyEvent.KEYCODE_ENTER -> "Enter"
                    android.view.KeyEvent.KEYCODE_SPACE -> "Space"
                    android.view.KeyEvent.KEYCODE_TAB -> "Tab"
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "Cursor left"
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "Cursor right"
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> "Cursor up"
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "Cursor down"
                    else -> "Key code ${keyValue.keyCode}"
                }
            }

            // Modifier keys
            is KeyValue.ModifierKey -> {
                when (keyValue.modifier) {
                    KeyValue.Modifier.SHIFT -> "Shift"
                    KeyValue.Modifier.CTRL -> "Control"
                    KeyValue.Modifier.ALT -> "Alt"
                    KeyValue.Modifier.META -> "Meta"
                    else -> "Modifier ${keyValue.modifier.name}"
                }
            }

            // String keys
            is KeyValue.StringKey -> keyValue.string

            // Compose keys
            is KeyValue.ComposePendingKey -> "Compose key"

            else -> "Unknown key"
        }
    }

    /**
     * Get special character names (same as VoiceGuidanceEngine for consistency)
     */
    private fun getSpecialCharacterName(char: Char): String {
        return when (char) {
            ' ' -> "Space"
            '\n' -> "New line"
            '\t' -> "Tab"
            '.' -> "Period"
            ',' -> "Comma"
            '!' -> "Exclamation mark"
            '?' -> "Question mark"
            ';' -> "Semicolon"
            ':' -> "Colon"
            '\'' -> "Apostrophe"
            '"' -> "Quote"
            '-' -> "Hyphen"
            '_' -> "Underscore"
            '/' -> "Slash"
            '\\' -> "Backslash"
            '|' -> "Pipe"
            '(' -> "Left parenthesis"
            ')' -> "Right parenthesis"
            '[' -> "Left bracket"
            ']' -> "Right bracket"
            '{' -> "Left brace"
            '}' -> "Right brace"
            '<' -> "Less than"
            '>' -> "Greater than"
            '=' -> "Equals"
            '+' -> "Plus"
            '*' -> "Asterisk"
            '&' -> "Ampersand"
            '%' -> "Percent"
            '$' -> "Dollar sign"
            '#' -> "Hash"
            '@' -> "At sign"
            '~' -> "Tilde"
            '`' -> "Backtick"
            '^' -> "Caret"
            else -> "Symbol"
        }
    }

    /**
     * Announce text for screen reader
     */
    fun announceForAccessibility(view: View, text: String) {
        if (!isScreenReaderActive()) return

        try {
            view.announceForAccessibility(text)
            Log.d(TAG, "Announced: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to announce text", e)
        }
    }

    /**
     * Announce key press for screen reader
     */
    fun announceKeyPress(view: View, keyValue: KeyValue) {
        if (!isScreenReaderActive()) return

        val description = getKeyDescription(keyValue)
        announceForAccessibility(view, description)
    }

    /**
     * Announce suggestions for screen reader
     */
    fun announceSuggestions(view: View, suggestions: List<String>) {
        if (!isScreenReaderActive()) return

        if (suggestions.isEmpty()) {
            announceForAccessibility(view, "No suggestions available")
            return
        }

        val message = "Suggestions: " + suggestions.joinToString(", ")
        announceForAccessibility(view, message)
    }

    /**
     * Announce layout change for screen reader
     */
    fun announceLayoutChange(view: View, layoutName: String) {
        if (!isScreenReaderActive()) return

        announceForAccessibility(view, "Switched to $layoutName layout")
    }

    /**
     * Announce modifier state change for screen reader
     */
    fun announceModifierChange(view: View, modifier: String, active: Boolean) {
        if (!isScreenReaderActive()) return

        val state = if (active) "activated" else "deactivated"
        announceForAccessibility(view, "$modifier $state")
    }

    /**
     * Create virtual view hierarchy for TalkBack exploration
     */
    fun createVirtualViewHierarchy(
        keyboardView: View,
        keys: List<KeyboardData.Key>,
        onKeyVirtualClick: (Int) -> Unit
    ) {
        if (!isScreenReaderActive()) return

        Log.d(TAG, "Creating virtual view hierarchy for ${keys.size} keys")

        // Set up custom accessibility provider for virtual views
        ViewCompat.setAccessibilityDelegate(keyboardView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)

                // Add virtual children for each key
                keys.forEachIndexed { index, key ->
                    info.addChild(host, index)
                }
            }

            override fun performAccessibilityAction(
                host: View,
                action: Int,
                args: Bundle?
            ): Boolean {
                when (action) {
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                        // Key received focus through touch exploration
                        return true
                    }
                    AccessibilityNodeInfo.ACTION_CLICK -> {
                        // Key was double-tapped
                        // Extract virtual view ID from args if available
                        return true
                    }
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    /**
     * Update screen reader state (call when accessibility settings change)
     */
    fun updateScreenReaderState(): Boolean {
        val wasActive = isScreenReaderActive
        isScreenReaderActive = isScreenReaderActive()

        if (wasActive != isScreenReaderActive) {
            Log.d(TAG, "Screen reader state changed: $isScreenReaderActive")
        }

        return isScreenReaderActive
    }
}
