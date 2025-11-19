package tribixbite.keyboard2

import android.view.KeyEvent
import java.util.*

/**
 * Modern Kotlin representation of keyboard key values
 * Replaces bit-packed integer encoding with type-safe sealed classes
 */
sealed class KeyValue : Comparable<KeyValue> {

    abstract val displayString: String
    abstract val flags: Set<Flag>

    /**
     * Type-safe flags using enum class with set operations
     */
    enum class Flag(val value: Int) {
        // Key behavioral flags
        LATCH(1 shl 0),                    // Key stays activated when pressed once
        DOUBLE_TAP_LOCK(1 shl 1),          // Key can be locked by typing twice
        SPECIAL(1 shl 2),                  // Special keys are not repeated
        GREYED(1 shl 3),                   // Symbol should be greyed out

        // Visual rendering flags
        KEY_FONT(1 shl 4),                 // Special font required to render
        SMALLER_FONT(1 shl 5),             // 25% smaller symbols
        SECONDARY(1 shl 6),                // Dimmer symbol
    }

    /**
     * Special keyboard events
     */
    enum class Event {
        CONFIG,
        SWITCH_TEXT,
        SWITCH_NUMERIC,
        SWITCH_EMOJI,
        SWITCH_BACK_EMOJI,
        SWITCH_CLIPBOARD,
        SWITCH_BACK_CLIPBOARD,
        CHANGE_METHOD_PICKER,
        CHANGE_METHOD_AUTO,
        ACTION,
        SWITCH_FORWARD,
        SWITCH_BACKWARD,
        SWITCH_GREEKMATH,
        CAPS_LOCK,
        SWITCH_VOICE_TYPING,
        SWITCH_VOICE_TYPING_CHOOSER,
        CONVERT_CASE_CYCLE,      // Cycle through case modes
        CONVERT_UPPERCASE,        // Convert to UPPERCASE
        CONVERT_LOWERCASE,        // Convert to lowercase
        CONVERT_TITLE_CASE,       // Convert to Title Case
        CURSOR_LEFT,              // Move cursor left by character
        CURSOR_RIGHT,             // Move cursor right by character
        CURSOR_WORD_LEFT,         // Move cursor left by word
        CURSOR_WORD_RIGHT,        // Move cursor right by word
        CURSOR_LINE_START,        // Move cursor to start of line
        CURSOR_LINE_END,          // Move cursor to end of line
        CURSOR_DOC_START,         // Move cursor to start of document
        CURSOR_DOC_END,           // Move cursor to end of document
        SELECT_ALL,               // Select all text
        SELECT_WORD,              // Select word at cursor
        SELECT_LINE,              // Select line at cursor
        CLEAR_SELECTION,          // Clear selection
        TWO_FINGER_SWIPE_LEFT,    // Two-finger swipe left
        TWO_FINGER_SWIPE_RIGHT,   // Two-finger swipe right
        TWO_FINGER_SWIPE_UP,      // Two-finger swipe up
        TWO_FINGER_SWIPE_DOWN,    // Two-finger swipe down
        THREE_FINGER_SWIPE_LEFT,  // Three-finger swipe left
        THREE_FINGER_SWIPE_RIGHT, // Three-finger swipe right
        THREE_FINGER_SWIPE_UP,    // Three-finger swipe up
        THREE_FINGER_SWIPE_DOWN,  // Three-finger swipe down
        PINCH_IN,                 // Pinch in gesture
        PINCH_OUT,                // Pinch out gesture
    }

    /**
     * Text modifiers and diacritics (applied in reverse order)
     */
    enum class Modifier {
        SHIFT,
        GESTURE,
        CTRL,
        ALT,
        META,
        DOUBLE_AIGU,
        DOT_ABOVE,
        DOT_BELOW,
        GRAVE,
        AIGU,
        CIRCONFLEXE,
        TILDE,
        CEDILLE,
        TREMA,
        HORN,
        HOOK_ABOVE,
        DOUBLE_GRAVE,
        SUPERSCRIPT,
        SUBSCRIPT,
        RING,
        CARON,
        MACRON,
        ORDINAL,
        ARROWS,
        BOX,
        OGONEK,
        SLASH,
        ARROW_RIGHT,
        BREVE,
        BAR,
        FN,
        SELECTION_MODE,
    }

    /**
     * Text editing operations
     */
    enum class Editing {
        COPY,
        PASTE,
        CUT,
        SELECT_ALL,
        PASTE_PLAIN,
        UNDO,
        REDO,
        REPLACE,
        SHARE,
        ASSIST,
        AUTOFILL,
        DELETE_WORD,
        FORWARD_DELETE_WORD,
        SELECTION_CANCEL,
    }

    /**
     * Placeholder keys for layout consistency
     */
    enum class Placeholder {
        REMOVED,
        COMPOSE_CANCEL,
        F11,
        F12,
        SHINDOT,
        SINDOT,
        OLE,
        METEG
    }


    // Key type implementations with type safety

    /**
     * Single character key
     */
    data class CharKey(
        val char: Char,
        override val displayString: String = char.toString(),
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Android KeyEvent key
     */
    data class KeyEventKey(
        val keyCode: Int,
        override val displayString: String,
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Special keyboard event key
     */
    data class EventKey(
        val event: Event,
        override val displayString: String,
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Multi-character string key
     */
    data class StringKey(
        val string: String,
        override val displayString: String = string,
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Compose/accent key for diacritic combinations
     */
    data class ComposePendingKey(
        val pendingCompose: Int,
        override val displayString: String,
        override val flags: Set<Flag> = setOf(Flag.LATCH)
    ) : KeyValue()

    /**
     * Korean Hangul initial consonant key
     */
    data class HangulInitialKey(
        val initialIndex: Int,
        override val displayString: String,
        override val flags: Set<Flag> = setOf(Flag.LATCH)
    ) : KeyValue() {
        val precomposed: Int = initialIndex * 588 + 44032
    }

    /**
     * Korean Hangul medial vowel key
     */
    data class HangulMedialKey(
        val precomposed: Int,
        val medialIndex: Int,
        override val displayString: String = String(intArrayOf(precomposed + medialIndex * 28), 0, 1),
        override val flags: Set<Flag> = setOf(Flag.LATCH)
    ) : KeyValue()

    /**
     * Text modifier key
     */
    data class ModifierKey(
        val modifier: Modifier,
        override val displayString: String,
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Text editing operation key
     */
    data class EditingKey(
        val editing: Editing,
        override val displayString: String,
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Placeholder key for consistent layout
     */
    data class PlaceholderKey(
        val placeholder: Placeholder,
        override val displayString: String = "",
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue()

    /**
     * Slider key for continuous input
     */
    data class SliderKey(
        val slider: Slider,
        val repeat: Int,
        override val displayString: String,
        override val flags: Set<Flag> = setOf(Flag.SPECIAL, Flag.SECONDARY, Flag.KEY_FONT)
    ) : KeyValue()

    /**
     * Macro key for multiple key sequences
     */
    data class MacroKey(
        val keys: Array<KeyValue>,
        override val displayString: String,
        override val flags: Set<Flag> = emptySet()
    ) : KeyValue() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MacroKey) return false
            return keys.contentEquals(other.keys) &&
                   displayString == other.displayString &&
                   flags == other.flags
        }

        override fun hashCode(): Int {
            return Objects.hash(keys.contentHashCode(), displayString, flags)
        }
    }

    // Utility methods for compatibility and convenience

    fun hasFlag(flag: Flag): Boolean = flag in flags
    fun hasFlagsAny(vararg checkFlags: Flag): Boolean = checkFlags.any { it in flags }

    fun withFlags(vararg newFlags: Flag): KeyValue = withFlags(newFlags.toSet())

    fun withFlags(newFlags: Set<Flag>): KeyValue = when (this) {
        is CharKey -> copy(flags = newFlags)
        is KeyEventKey -> copy(flags = newFlags)
        is EventKey -> copy(flags = newFlags)
        is StringKey -> copy(flags = newFlags)
        is ComposePendingKey -> copy(flags = newFlags)
        is HangulInitialKey -> copy(flags = newFlags)
        is HangulMedialKey -> copy(flags = newFlags)
        is ModifierKey -> copy(flags = newFlags)
        is EditingKey -> copy(flags = newFlags)
        is PlaceholderKey -> copy(flags = newFlags)
        is SliderKey -> copy(flags = newFlags)
        is MacroKey -> copy(flags = newFlags)
    }

    fun withSymbol(newSymbol: String): KeyValue {
        val adjustedFlags = flags - setOf(Flag.KEY_FONT, Flag.SMALLER_FONT) +
                           if (newSymbol.length > 1) setOf(Flag.SMALLER_FONT) else emptySet()

        return when (this) {
            is CharKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is KeyEventKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is EventKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is StringKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is ComposePendingKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is HangulInitialKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is HangulMedialKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is ModifierKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is EditingKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is PlaceholderKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is SliderKey -> copy(displayString = newSymbol, flags = adjustedFlags)
            is MacroKey -> copy(displayString = newSymbol, flags = adjustedFlags)
        }
    }

    fun withChar(newChar: Char): KeyValue {
        val newSymbol = newChar.toString()
        val adjustedFlags = flags - setOf(Flag.KEY_FONT, Flag.SMALLER_FONT)
        return CharKey(newChar, newSymbol, adjustedFlags)
    }

    /**
     * Check if this is a slider key
     */
    fun isSlider(): Boolean = this is SliderKey

    /**
     * Get slider value if this is a slider key
     */
    fun getSliderValue(): Slider? = (this as? SliderKey)?.slider

    /**
     * Get slider repeat value if this is a slider key
     */
    fun getSliderRepeat(): Int = (this as? SliderKey)?.repeat ?: 0

    fun withKeyEvent(keyCode: Int): KeyValue = when (this) {
        is KeyEventKey -> copy(keyCode = keyCode)
        else -> KeyEventKey(keyCode, displayString, flags)
    }

    // Type checking methods for safe casting
    fun isChar(): Boolean = this is CharKey
    fun isKeyEvent(): Boolean = this is KeyEventKey
    fun isEvent(): Boolean = this is EventKey
    fun isString(): Boolean = this is StringKey
    fun isComposePending(): Boolean = this is ComposePendingKey
    fun isModifier(): Boolean = this is ModifierKey
    fun isEditing(): Boolean = this is EditingKey
    fun isMacro(): Boolean = this is MacroKey

    // Safe casting with default values
    fun getCharValue(): Char = (this as? CharKey)?.char ?: '\u0000'
    fun getKeyEventValue(): Int = (this as? KeyEventKey)?.keyCode ?: 0
    fun getEventValue(): Event? = (this as? EventKey)?.event
    fun getStringValue(): String = (this as? StringKey)?.string ?: displayString
    fun getModifierValue(): Modifier? = (this as? ModifierKey)?.modifier
    fun getEditingValue(): Editing? = (this as? EditingKey)?.editing
    fun getMacroValue(): Array<KeyValue> = (this as? MacroKey)?.keys ?: emptyArray()

    // Comparison for sorting and equality
    override fun compareTo(other: KeyValue): Int {
        // Compare by type first, then by content
        val typeOrder = when (this) {
            is CharKey -> 0
            is KeyEventKey -> 1
            is EventKey -> 2
            is StringKey -> 3
            is ComposePendingKey -> 4
            is HangulInitialKey -> 5
            is HangulMedialKey -> 6
            is ModifierKey -> 7
            is EditingKey -> 8
            is PlaceholderKey -> 9
            is SliderKey -> 10
            is MacroKey -> 11
        }

        val otherTypeOrder = when (other) {
            is CharKey -> 0
            is KeyEventKey -> 1
            is EventKey -> 2
            is StringKey -> 3
            is ComposePendingKey -> 4
            is HangulInitialKey -> 5
            is HangulMedialKey -> 6
            is ModifierKey -> 7
            is EditingKey -> 8
            is PlaceholderKey -> 9
            is SliderKey -> 10
            is MacroKey -> 11
        }

        return if (typeOrder != otherTypeOrder) {
            typeOrder - otherTypeOrder
        } else {
            displayString.compareTo(other.displayString)
        }
    }

    /**
     * Slider implementation for continuous input
     */
    data class Slider(
        val increment: Float,
        val precision: Int = 2
    ) {
        fun getDisplayValue(repeat: Int): String {
            val value = increment * repeat
            return "%.${precision}f".format(value)
        }
    }

    companion object {

        // Factory methods for creating common key types

        fun makeCharKey(char: Char, symbol: String? = null, vararg flags: Flag): CharKey =
            CharKey(char, symbol ?: char.toString(), flags.toSet())

        fun makeCharKey(symbolCode: Int, char: Char, vararg flags: Flag): CharKey =
            CharKey(char, String(intArrayOf(symbolCode), 0, 1), (flags.toSet() + Flag.KEY_FONT))

        fun makeStringKey(string: String, vararg flags: Flag): KeyValue =
            if (string.length == 1) {
                CharKey(string[0], string, flags.toSet())
            } else {
                StringKey(string, string, (flags.toSet() + Flag.SMALLER_FONT))
            }

        fun makeKeyEventKey(symbol: String, keyCode: Int, vararg flags: Flag): KeyEventKey =
            KeyEventKey(keyCode, symbol, flags.toSet())

        fun makeKeyEventKey(symbolCode: Int, keyCode: Int, vararg flags: Flag): KeyEventKey =
            KeyEventKey(keyCode, String(intArrayOf(symbolCode), 0, 1), (flags.toSet() + Flag.KEY_FONT))

        fun makeEventKey(symbol: String, event: Event, vararg flags: Flag): EventKey =
            EventKey(event, symbol, flags.toSet())

        fun makeEventKey(symbolCode: Int, event: Event, vararg flags: Flag): EventKey =
            EventKey(event, String(intArrayOf(symbolCode), 0, 1), (flags.toSet() + Flag.KEY_FONT))

        fun makeModifierKey(symbol: String, modifier: Modifier, vararg flags: Flag): ModifierKey =
            ModifierKey(modifier, symbol, flags.toSet())

        fun makeModifierKey(symbolCode: Int, modifier: Modifier, vararg flags: Flag): ModifierKey =
            ModifierKey(modifier, String(intArrayOf(symbolCode), 0, 1), (flags.toSet() + Flag.KEY_FONT))

        fun makeComposePending(symbol: String, state: Int, vararg flags: Flag): ComposePendingKey =
            ComposePendingKey(state, symbol, (flags.toSet() + Flag.LATCH))

        fun makeComposePending(symbolCode: Int, state: Int, vararg flags: Flag): ComposePendingKey =
            ComposePendingKey(state, String(intArrayOf(symbolCode), 0, 1), (flags.toSet() + Flag.LATCH + Flag.KEY_FONT))

        fun makeHangulInitial(symbol: String, initialIndex: Int): HangulInitialKey =
            HangulInitialKey(initialIndex, symbol)

        fun makeHangulMedial(precomposed: Int, medialIndex: Int): HangulMedialKey =
            HangulMedialKey(precomposed, medialIndex)

        fun makeHangulFinal(precomposed: Int, finalIndex: Int): CharKey =
            CharKey((precomposed + finalIndex).toChar())

        fun makeSlider(slider: Slider, repeat: Int = 0): SliderKey =
            SliderKey(slider, repeat, slider.getDisplayValue(repeat))

        fun makeMacro(symbol: String, keys: Array<KeyValue>, vararg flags: Flag): MacroKey {
            val adjustedFlags = if (symbol.length > 1) (flags.toSet() + Flag.SMALLER_FONT) else flags.toSet()
            return MacroKey(keys, symbol, adjustedFlags)
        }

        fun makeActionKey(symbol: String): EventKey =
            EventKey(Event.ACTION, symbol, setOf(Flag.SMALLER_FONT))

        fun makeInternalModifier(modifier: Modifier): ModifierKey =
            ModifierKey(modifier, "")

        fun makePlaceholder(placeholder: Placeholder): PlaceholderKey =
            PlaceholderKey(placeholder)

        fun makePlaceholder(symbolCode: Int, placeholder: Placeholder, vararg flags: Flag): PlaceholderKey =
            PlaceholderKey(placeholder, String(intArrayOf(symbolCode), 0, 1), (flags.toSet() + Flag.KEY_FONT))

        // Helper methods for diacritics and modifiers
        private fun diacritic(symbolCode: Int, modifier: Modifier): ModifierKey =
            makeModifierKey(symbolCode, modifier, Flag.LATCH)

        private fun charKey(symbol: String, char: Char, vararg flags: Flag): CharKey =
            makeCharKey(char, symbol, *flags)

        private fun charKey(symbolCode: Int, char: Char, vararg flags: Flag): CharKey =
            makeCharKey(symbolCode, char, *flags)

        // Common predefined keys
        val SHIFT = makeModifierKey(0xE00A, Modifier.SHIFT, Flag.DOUBLE_TAP_LOCK)
        val CTRL = makeModifierKey("Ctrl", Modifier.CTRL)
        val ALT = makeModifierKey("Alt", Modifier.ALT)
        val META = makeModifierKey("Meta", Modifier.META)

        val BACKSPACE = makeKeyEventKey(0xE011, KeyEvent.KEYCODE_DEL)
        val DELETE = makeKeyEventKey(0xE010, KeyEvent.KEYCODE_FORWARD_DEL)
        val ENTER = makeKeyEventKey(0xE00E, KeyEvent.KEYCODE_ENTER)
        val SPACE = makeCharKey(0xE00D, ' ', Flag.SMALLER_FONT, Flag.GREYED)
        val TAB = makeKeyEventKey(0xE00F, KeyEvent.KEYCODE_TAB, Flag.SMALLER_FONT)
        val ESC = makeKeyEventKey("Esc", KeyEvent.KEYCODE_ESCAPE, Flag.SMALLER_FONT)

        val UP = makeKeyEventKey(0xE005, KeyEvent.KEYCODE_DPAD_UP)
        val DOWN = makeKeyEventKey(0xE007, KeyEvent.KEYCODE_DPAD_DOWN)
        val LEFT = makeKeyEventKey(0xE008, KeyEvent.KEYCODE_DPAD_LEFT, Flag.SMALLER_FONT)
        val RIGHT = makeKeyEventKey(0xE006, KeyEvent.KEYCODE_DPAD_RIGHT, Flag.SMALLER_FONT)

        // Named key registry for lookup by string name
        private val namedKeys = mutableMapOf<String, KeyValue>()

        init {
            // Register common named keys
            registerNamedKeys()
        }

        private fun registerNamedKeys() {
            // Escaped special characters
            namedKeys["\\?"] = makeStringKey("?")
            namedKeys["\\#"] = makeStringKey("#")
            namedKeys["\\@"] = makeStringKey("@")
            namedKeys["\\\\"] = makeStringKey("\\")

            // Modifiers and dead keys
            namedKeys["shift"] = SHIFT
            namedKeys["ctrl"] = CTRL
            namedKeys["alt"] = ALT
            namedKeys["meta"] = META

            // Diacritics
            namedKeys["accent_aigu"] = diacritic(0xE050, Modifier.AIGU)
            namedKeys["accent_grave"] = diacritic(0xE054, Modifier.GRAVE)
            namedKeys["accent_circonflexe"] = diacritic(0xE053, Modifier.CIRCONFLEXE)
            namedKeys["accent_tilde"] = diacritic(0xE057, Modifier.TILDE)
            namedKeys["accent_trema"] = diacritic(0xE058, Modifier.TREMA)
            namedKeys["accent_cedille"] = diacritic(0xE052, Modifier.CEDILLE)
            namedKeys["accent_macron"] = diacritic(0xE055, Modifier.MACRON)
            namedKeys["accent_ring"] = diacritic(0xE056, Modifier.RING)
            namedKeys["accent_caron"] = diacritic(0xE051, Modifier.CARON)
            namedKeys["accent_ogonek"] = diacritic(0xE059, Modifier.OGONEK)
            namedKeys["accent_dot_above"] = diacritic(0xE05A, Modifier.DOT_ABOVE)
            namedKeys["accent_dot_below"] = diacritic(0xE060, Modifier.DOT_BELOW)
            namedKeys["accent_double_aigu"] = diacritic(0xE05B, Modifier.DOUBLE_AIGU)
            namedKeys["accent_double_grave"] = diacritic(0xE063, Modifier.DOUBLE_GRAVE)
            namedKeys["accent_slash"] = diacritic(0xE05C, Modifier.SLASH)
            namedKeys["accent_arrow_right"] = diacritic(0xE05D, Modifier.ARROW_RIGHT)
            namedKeys["accent_breve"] = diacritic(0xE05E, Modifier.BREVE)
            namedKeys["accent_bar"] = diacritic(0xE05F, Modifier.BAR)
            namedKeys["accent_horn"] = diacritic(0xE061, Modifier.HORN)
            namedKeys["accent_hook_above"] = diacritic(0xE062, Modifier.HOOK_ABOVE)

            // Special modifiers
            namedKeys["superscript"] = makeModifierKey("Sup", Modifier.SUPERSCRIPT)
            namedKeys["subscript"] = makeModifierKey("Sub", Modifier.SUBSCRIPT)
            namedKeys["ordinal"] = makeModifierKey("Ord", Modifier.ORDINAL)
            namedKeys["arrows"] = makeModifierKey("Arr", Modifier.ARROWS)
            namedKeys["box"] = makeModifierKey("Box", Modifier.BOX)
            namedKeys["fn"] = makeModifierKey("Fn", Modifier.FN)

            // Event keys
            namedKeys["config"] = makeEventKey(0xE004, Event.CONFIG, Flag.SMALLER_FONT)
            namedKeys["switch_text"] = makeEventKey("ABC", Event.SWITCH_TEXT, Flag.SMALLER_FONT)
            namedKeys["switch_numeric"] = makeEventKey("123+", Event.SWITCH_NUMERIC, Flag.SMALLER_FONT)
            namedKeys["switch_emoji"] = makeEventKey(0xE001, Event.SWITCH_EMOJI, Flag.SMALLER_FONT)
            namedKeys["switch_back_emoji"] = makeEventKey("ABC", Event.SWITCH_BACK_EMOJI)
            namedKeys["switch_clipboard"] = makeEventKey(0xE017, Event.SWITCH_CLIPBOARD)
            namedKeys["switch_back_clipboard"] = makeEventKey("ABC", Event.SWITCH_BACK_CLIPBOARD)
            namedKeys["switch_forward"] = makeEventKey(0xE013, Event.SWITCH_FORWARD, Flag.SMALLER_FONT)
            namedKeys["switch_backward"] = makeEventKey(0xE014, Event.SWITCH_BACKWARD, Flag.SMALLER_FONT)
            namedKeys["switch_greekmath"] = makeEventKey("πλ∇¬", Event.SWITCH_GREEKMATH, Flag.SMALLER_FONT)
            namedKeys["change_method"] = makeEventKey(0xE009, Event.CHANGE_METHOD_PICKER, Flag.SMALLER_FONT)
            namedKeys["change_method_prev"] = makeEventKey(0xE009, Event.CHANGE_METHOD_AUTO, Flag.SMALLER_FONT)
            namedKeys["action"] = makeEventKey("Action", Event.ACTION, Flag.SMALLER_FONT)
            namedKeys["capslock"] = makeEventKey(0xE012, Event.CAPS_LOCK)
            namedKeys["voice_typing"] = makeEventKey(0xE015, Event.SWITCH_VOICE_TYPING, Flag.SMALLER_FONT)
            namedKeys["voice_typing_chooser"] = makeEventKey(0xE015, Event.SWITCH_VOICE_TYPING_CHOOSER, Flag.SMALLER_FONT)

            // Navigation keys
            namedKeys["esc"] = ESC
            namedKeys["enter"] = ENTER
            namedKeys["up"] = UP
            namedKeys["down"] = DOWN
            namedKeys["left"] = LEFT
            namedKeys["right"] = RIGHT
            namedKeys["page_up"] = makeKeyEventKey(0xE002, KeyEvent.KEYCODE_PAGE_UP)
            namedKeys["page_down"] = makeKeyEventKey(0xE003, KeyEvent.KEYCODE_PAGE_DOWN)
            namedKeys["home"] = makeKeyEventKey(0xE00B, KeyEvent.KEYCODE_MOVE_HOME, Flag.SMALLER_FONT)
            namedKeys["end"] = makeKeyEventKey(0xE00C, KeyEvent.KEYCODE_MOVE_END, Flag.SMALLER_FONT)
            namedKeys["backspace"] = BACKSPACE
            namedKeys["delete"] = DELETE
            namedKeys["insert"] = makeKeyEventKey("Ins", KeyEvent.KEYCODE_INSERT, Flag.SMALLER_FONT)
            namedKeys["tab"] = TAB
            namedKeys["menu"] = makeKeyEventKey("Menu", KeyEvent.KEYCODE_MENU, Flag.SMALLER_FONT)

            // Function keys
            for (i in 1..12) {
                namedKeys["f$i"] = if (i >= 11) {
                    makeKeyEventKey("F$i", KeyEvent.KEYCODE_F1 + i - 1, Flag.SMALLER_FONT)
                } else {
                    makeKeyEventKey("F$i", KeyEvent.KEYCODE_F1 + i - 1)
                }
            }

            // Special spaces and characters
            namedKeys["\\t"] = charKey("\\t", '\t')
            namedKeys["\\n"] = charKey("\\n", '\n')
            namedKeys["space"] = SPACE
            namedKeys["nbsp"] = charKey("\u237d", '\u00a0', Flag.SMALLER_FONT)
            namedKeys["nnbsp"] = charKey("\u2423", '\u202F', Flag.SMALLER_FONT)

            // Bidirectional text marks
            namedKeys["lrm"] = charKey("↱", '\u200e')
            namedKeys["rlm"] = charKey("↰", '\u200f')

            // Bidirectional brackets
            namedKeys["b("] = charKey("(", ')')
            namedKeys["b)"] = charKey(")", '(')
            namedKeys["b["] = charKey("[", ']')
            namedKeys["b]"] = charKey("]", '[')
            namedKeys["b{"] = charKey("{", '}')
            namedKeys["b}"] = charKey("}", '{')
            namedKeys["blt"] = charKey("<", '>')
            namedKeys["bgt"] = charKey(">", '<')
        }

        /**
         * Get a key by its string name, with fallback parsing
         */
        fun getKeyByName(name: String): KeyValue {
            return namedKeys[name] ?: try {
                // Try parsing as a key value expression
                parseKeyValue(name)
            } catch (e: Exception) {
                // Fall back to string key
                makeStringKey(name)
            }
        }

        /**
         * Get a special key by name (null if not found)
         */
        fun getSpecialKeyByName(name: String): KeyValue? = namedKeys[name]

        /**
         * Simple key value parser for basic expressions
         */
        private fun parseKeyValue(expression: String): KeyValue {
            // Simple parser for basic key expressions
            // Can be extended for more complex parsing
            return when {
                expression.startsWith("char:") -> {
                    val char = expression.substring(5).firstOrNull() ?: ' '
                    makeCharKey(char)
                }
                expression.startsWith("string:") -> {
                    makeStringKey(expression.substring(7))
                }
                else -> makeStringKey(expression)
            }
        }

        /**
         * Register a custom named key
         */
        fun registerNamedKey(name: String, key: KeyValue) {
            namedKeys[name] = key
        }
    }
}