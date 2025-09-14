package juloo.keyboard2

/**
 * Represents a key value that can be sent to the input connection
 * Kotlin sealed class for type-safe key handling
 */
sealed class KeyValue {
    
    /**
     * Key kinds with sealed class pattern for exhaustive when statements
     */
    enum class Kind { Char, Event, String, Compose_pending, Modifier }
    
    abstract val kind: Kind
    
    /**
     * Character key
     */
    data class CharKey(val char: Char) : KeyValue() {
        override val kind = Kind.Char
    }
    
    /**
     * Event key (special keys like backspace, enter)
     */
    data class EventKey(val eventCode: Int) : KeyValue() {
        override val kind = Kind.Event
    }
    
    /**
     * String key (multiple characters)
     */
    data class StringKey(val string: String) : KeyValue() {
        override val kind = Kind.String
    }
    
    /**
     * Compose key for accent/modifier combinations
     */
    data class ComposeKey(val composePending: String) : KeyValue() {
        override val kind = Kind.Compose_pending
    }
    
    /**
     * Modifier key (shift, ctrl, alt, etc.)
     */
    data class ModifierKey(val modifier: Int) : KeyValue() {
        override val kind = Kind.Modifier
    }
    
    // Compatibility properties for Java interop
    open val char: Char get() = (this as? CharKey)?.char ?: '\u0000'
    open val eventCode: Int get() = (this as? EventKey)?.eventCode ?: 0
    open val string: String get() = (this as? StringKey)?.string ?: ""
    
    companion object {
        /**
         * Factory methods for creating key values
         */
        fun makeCharKey(c: Char) = CharKey(c)
        fun makeStringKey(s: String) = if (s.length == 1) CharKey(s[0]) else StringKey(s)
        fun makeEventKey(code: Int) = EventKey(code)
        fun makeComposeKey(pending: String) = ComposeKey(pending)
        fun makeModifierKey(mod: Int) = ModifierKey(mod)
        
        // Common special keys
        val BACKSPACE = EventKey(android.view.KeyEvent.KEYCODE_DEL)
        val ENTER = EventKey(android.view.KeyEvent.KEYCODE_ENTER)
        val SPACE = CharKey(' ')
        val SHIFT = ModifierKey(1)
    }
}