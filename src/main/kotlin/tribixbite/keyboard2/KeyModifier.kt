package tribixbite.keyboard2

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Key modifier system matching Java KeyModifier.java
 * Handles shift, ctrl, alt, fn, gesture, and diacritic modifiers
 */
object KeyModifier {

    // Modmap for custom key remapping (initially null)
    private var modmap: Modmap? = null

    /**
     * Set modmap for keyboard layout customization
     */
    fun set_modmap(mm: Modmap?) {
        modmap = mm
    }

    /**
     * Main modifier application - applies all modifiers in sequence
     * Matches Java modify(KeyValue k, Pointers.Modifiers mods)
     */
    fun modify(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? {
        if (k == null) return null

        var result: KeyValue? = k
        for (i in 0 until mods.size()) {
            val mod = mods.get(i)
            result = modify(result, mod)
        }
        return result
    }

    /**
     * Modify key value by KeyValue modifier
     */
    private fun modify(k: KeyValue?, mod: KeyValue): KeyValue? {
        if (k == null) return null

        return when {
            mod.isModifier() -> {
                val modifier = mod.getModifierValue()
                if (modifier != null) modify(k, modifier) else k
            }
            else -> k
        }
    }

    /**
     * Modify key value by Modifier enum - the core switch statement
     * Matches Java modify(KeyValue k, KeyValue.Modifier mod)
     */
    fun modify(k: KeyValue?, mod: KeyValue.Modifier): KeyValue? {
        if (k == null) return null

        return when (mod) {
            KeyValue.Modifier.SHIFT -> apply_shift(k)
            KeyValue.Modifier.CTRL -> apply_ctrl(k)
            KeyValue.Modifier.ALT, KeyValue.Modifier.META -> turn_into_keyevent(k)
            KeyValue.Modifier.FN -> apply_fn(k)
            KeyValue.Modifier.GESTURE -> apply_gesture(k)

            // Diacritical modifiers
            KeyValue.Modifier.GRAVE -> apply_dead_char(k, '\u0300')
            KeyValue.Modifier.AIGU -> apply_dead_char(k, '\u0301')
            KeyValue.Modifier.CIRCONFLEXE -> apply_dead_char(k, '\u0302')
            KeyValue.Modifier.TILDE -> apply_dead_char(k, '\u0303')
            KeyValue.Modifier.CEDILLE -> apply_dead_char(k, '\u0327')
            KeyValue.Modifier.TREMA -> apply_dead_char(k, '\u0308')
            KeyValue.Modifier.CARON -> apply_dead_char(k, '\u030C')
            KeyValue.Modifier.RING -> apply_dead_char(k, '\u030A')
            KeyValue.Modifier.MACRON -> apply_dead_char(k, '\u0304')
            KeyValue.Modifier.OGONEK -> apply_dead_char(k, '\u0328')
            KeyValue.Modifier.DOT_ABOVE -> apply_dead_char(k, '\u0307')
            KeyValue.Modifier.BREVE -> apply_dead_char(k, '\u0306')
            KeyValue.Modifier.DOUBLE_AIGU -> apply_compose(k, ComposeKeyData.ACCENT_DOUBLE_AIGU)
            KeyValue.Modifier.ORDINAL -> apply_compose(k, ComposeKeyData.ACCENT_ORDINAL)
            KeyValue.Modifier.SUPERSCRIPT -> apply_compose(k, ComposeKeyData.ACCENT_SUPERSCRIPT)
            KeyValue.Modifier.SUBSCRIPT -> apply_compose(k, ComposeKeyData.ACCENT_SUBSCRIPT)
            KeyValue.Modifier.ARROWS -> apply_compose(k, ComposeKeyData.ACCENT_ARROWS)
            KeyValue.Modifier.BOX -> apply_compose(k, ComposeKeyData.ACCENT_BOX)
            KeyValue.Modifier.SLASH -> apply_compose(k, ComposeKeyData.ACCENT_SLASH)
            KeyValue.Modifier.BAR -> apply_compose(k, ComposeKeyData.ACCENT_BAR)
            KeyValue.Modifier.DOT_BELOW -> apply_compose(k, ComposeKeyData.ACCENT_DOT_BELOW)
            KeyValue.Modifier.HORN -> apply_compose(k, ComposeKeyData.ACCENT_HORN)
            KeyValue.Modifier.HOOK_ABOVE -> apply_compose(k, ComposeKeyData.ACCENT_HOOK_ABOVE)
            KeyValue.Modifier.DOUBLE_GRAVE -> apply_dead_char(k, '\u030F')
            KeyValue.Modifier.ARROW_RIGHT -> apply_combining_char(k, "\u20D7")
            KeyValue.Modifier.SELECTION_MODE -> apply_selection_mode(k)

            else -> k
        }
    }

    /**
     * Modify key for long press action
     */
    fun modifyLongPress(k: KeyValue): KeyValue {
        // Check for Event keys that have long press variants
        if (k.isEvent()) {
            val event = k.getEventValue()
            return when (event) {
                KeyValue.Event.CHANGE_METHOD_AUTO -> KeyValue.getKeyByName("change_method")
                KeyValue.Event.SWITCH_VOICE_TYPING -> KeyValue.getKeyByName("voice_typing_chooser")
                else -> k
            }
        }
        return k
    }

    /**
     * Modify numpad script for different number systems
     */
    fun modify_numpad_script(numpad_script: String?): Int {
        if (numpad_script == null) return -1
        return when (numpad_script) {
            "hindu-arabic" -> ComposeKeyData.NUMPAD_HINDU
            "bengali" -> ComposeKeyData.NUMPAD_BENGALI
            "devanagari" -> ComposeKeyData.NUMPAD_DEVANAGARI
            "persian" -> ComposeKeyData.NUMPAD_PERSIAN
            "gujarati" -> ComposeKeyData.NUMPAD_GUJARATI
            "kannada" -> ComposeKeyData.NUMPAD_KANNADA
            "tamil" -> ComposeKeyData.NUMPAD_TAMIL
            else -> -1
        }
    }

    // ========== Apply Methods ==========

    /**
     * Apply shift modifier
     */
    private fun apply_shift(k: KeyValue): KeyValue? {
        // Check modmap first
        modmap?.shift?.get(k)?.let { return it }

        // Try compose table for shift mappings
        val composed = apply_compose(k, ComposeKeyData.shift)
        if (composed != null && composed != k) return composed

        // Apply shift to character
        if (k.isChar()) {
            val c = k.getCharValue()
            val upper = Character.toUpperCase(c)
            if (upper != c) {
                return k.withChar(upper)
            }
        }

        // Apply shift to string
        if (k.isString()) {
            val str = k.getStringValue()
            if (str.isNotEmpty()) {
                val upper = str.uppercase()
                if (upper != str) {
                    return k.withSymbol(upper)
                }
            }
        }

        return k
    }

    /**
     * Apply ctrl modifier - convert to KeyEvent
     */
    private fun apply_ctrl(k: KeyValue): KeyValue? {
        // Check modmap first
        modmap?.ctrl?.get(k)?.let { return it }

        return turn_into_keyevent(k)
    }

    /**
     * Apply fn modifier
     */
    private fun apply_fn(k: KeyValue): KeyValue? {
        // Check modmap first
        modmap?.fn?.get(k)?.let { return it }

        // Try compose table for fn mappings
        val composed = apply_compose(k, ComposeKeyData.fn)
        if (composed != null && composed != k) return composed

        // Handle KeyEvent cases
        if (k.isKeyEvent()) {
            return apply_fn_keyevent(k)
        }

        // Handle Event cases
        if (k.isEvent()) {
            return apply_fn_event(k)
        }

        // Handle placeholder cases
        if (k.isPlaceholder()) {
            return apply_fn_placeholder(k)
        }

        // Handle editing keys
        if (k.isEditing()) {
            return apply_fn_editing(k)
        }

        return k
    }

    private fun apply_fn_keyevent(k: KeyValue): KeyValue {
        val keycode = k.getKeyEventValue()
        val name = when (keycode) {
            KeyEvent.KEYCODE_DPAD_UP -> "page_up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "page_down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "home"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "end"
            KeyEvent.KEYCODE_ESCAPE -> "insert"
            KeyEvent.KEYCODE_TAB -> return KeyValue.makeCharKey('\t')
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> return KeyValue.makePlaceholder(KeyValue.Placeholder.REMOVED)
            else -> return k
        }
        return KeyValue.getKeyByName(name)
    }

    private fun apply_fn_event(k: KeyValue): KeyValue {
        val event = k.getEventValue()
        return when (event) {
            KeyValue.Event.SWITCH_NUMERIC -> KeyValue.getKeyByName("switch_greekmath")
            else -> k
        }
    }

    private fun apply_fn_placeholder(k: KeyValue): KeyValue {
        val placeholder = k.getPlaceholderValue()
        val name = when (placeholder) {
            KeyValue.Placeholder.F11 -> "f11"
            KeyValue.Placeholder.F12 -> "f12"
            else -> return k
        }
        return KeyValue.getKeyByName(name)
    }

    private fun apply_fn_editing(k: KeyValue): KeyValue {
        val editing = k.getEditingValue()
        val name = when (editing) {
            KeyValue.Editing.UNDO -> "redo"
            KeyValue.Editing.PASTE -> "pasteAsPlainText"
            else -> return k
        }
        return KeyValue.getKeyByName(name)
    }

    /**
     * Apply gesture modifier (for roundtrip/circle gestures)
     */
    private fun apply_gesture(k: KeyValue): KeyValue? {
        // Try shift first
        val shifted = apply_shift(k)
        if (shifted != null && shifted != k) return shifted

        // Try fn
        val fned = apply_fn(k)
        if (fned != null && fned != k) return fned

        // Special cases
        if (k.isModifier()) {
            val mod = k.getModifierValue()
            if (mod == KeyValue.Modifier.SHIFT) {
                return KeyValue.getKeyByName("capslock")
            }
        }

        if (k.isKeyEvent()) {
            val keycode = k.getKeyEventValue()
            val name = when (keycode) {
                KeyEvent.KEYCODE_DEL -> "delete_word"
                KeyEvent.KEYCODE_FORWARD_DEL -> "forward_delete_word"
                else -> return k
            }
            return KeyValue.getKeyByName(name)
        }

        return k
    }

    /**
     * Apply selection mode modifier
     */
    private fun apply_selection_mode(k: KeyValue): KeyValue {
        // Space cancels selection
        if (k.isChar() && k.getCharValue() == ' ') {
            return KeyValue.getKeyByName("selection_cancel")
        }

        // Slider keys in selection mode
        if (k.isSlider()) {
            val slider = k.getSliderValue()
            val name = when (slider) {
                KeyValue.Slider.Cursor_left -> "selection_cursor_left"
                KeyValue.Slider.Cursor_right -> "selection_cursor_right"
                else -> return k
            }
            return KeyValue.getKeyByName(name)
        }

        // Escape cancels selection
        if (k.isKeyEvent() && k.getKeyEventValue() == KeyEvent.KEYCODE_ESCAPE) {
            return KeyValue.getKeyByName("selection_cancel")
        }

        return k
    }

    /**
     * Apply dead character (diacritic) to key
     */
    private fun apply_dead_char(k: KeyValue, deadChar: Char): KeyValue? {
        if (k.isChar()) {
            val c = k.getCharValue()
            val modified = KeyCharacterMap.getDeadChar(deadChar.code, c.code)
            if (modified != 0 && modified != c.code) {
                return k.withChar(modified.toChar())
            }
        }
        return k
    }

    /**
     * Apply compose table to key using ComposeKeyData state machine
     * @param k The key to modify
     * @param composeIndex The starting index in the ComposeKeyData state machine
     */
    private fun apply_compose(k: KeyValue, composeIndex: Int): KeyValue? {
        if (!k.isChar()) return k

        val c = k.getCharValue()

        // Navigate the compose state machine
        try {
            val states = ComposeKeyData.states
            val edges = ComposeKeyData.edges

            // State at composeIndex
            val header = states[composeIndex].code
            val length = edges[composeIndex]

            // Header 0 means intermediate state with transitions
            if (header == 0) {
                // Binary search for character in transitions
                var lo = 1
                var hi = length - 1
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val midChar = states[composeIndex + mid].code
                    when {
                        midChar < c.code -> lo = mid + 1
                        midChar > c.code -> hi = mid - 1
                        else -> {
                            // Found the character - get the target state
                            val targetState = edges[composeIndex + mid]
                            val targetHeader = states[targetState].code
                            val targetLength = edges[targetState]

                            return when {
                                // Single character result
                                targetHeader > 0 && targetHeader != 0xFFFF -> {
                                    k.withChar(targetHeader.toChar())
                                }
                                // String result
                                targetHeader == 0xFFFF -> {
                                    val sb = StringBuilder()
                                    for (i in 1 until targetLength) {
                                        sb.append(states[targetState + i])
                                    }
                                    k.withSymbol(sb.toString())
                                }
                                // Another intermediate state - not supported in single apply
                                else -> k
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ComposeKeyData not initialized or error - return unchanged
        }

        return k
    }

    /**
     * Apply combining character to key
     */
    private fun apply_combining_char(k: KeyValue, combining: String): KeyValue {
        if (k.isChar()) {
            return k.withSymbol(k.getCharValue().toString() + combining)
        }
        return k
    }

    /**
     * Convert character key to KeyEvent key
     * Critical for Ctrl+A, Ctrl+C, Ctrl+V, etc.
     */
    private fun turn_into_keyevent(k: KeyValue): KeyValue? {
        if (!k.isChar()) return k

        val c = k.getCharValue()
        val keycode = when (c) {
            'a', 'A' -> KeyEvent.KEYCODE_A
            'b', 'B' -> KeyEvent.KEYCODE_B
            'c', 'C' -> KeyEvent.KEYCODE_C
            'd', 'D' -> KeyEvent.KEYCODE_D
            'e', 'E' -> KeyEvent.KEYCODE_E
            'f', 'F' -> KeyEvent.KEYCODE_F
            'g', 'G' -> KeyEvent.KEYCODE_G
            'h', 'H' -> KeyEvent.KEYCODE_H
            'i', 'I' -> KeyEvent.KEYCODE_I
            'j', 'J' -> KeyEvent.KEYCODE_J
            'k', 'K' -> KeyEvent.KEYCODE_K
            'l', 'L' -> KeyEvent.KEYCODE_L
            'm', 'M' -> KeyEvent.KEYCODE_M
            'n', 'N' -> KeyEvent.KEYCODE_N
            'o', 'O' -> KeyEvent.KEYCODE_O
            'p', 'P' -> KeyEvent.KEYCODE_P
            'q', 'Q' -> KeyEvent.KEYCODE_Q
            'r', 'R' -> KeyEvent.KEYCODE_R
            's', 'S' -> KeyEvent.KEYCODE_S
            't', 'T' -> KeyEvent.KEYCODE_T
            'u', 'U' -> KeyEvent.KEYCODE_U
            'v', 'V' -> KeyEvent.KEYCODE_V
            'w', 'W' -> KeyEvent.KEYCODE_W
            'x', 'X' -> KeyEvent.KEYCODE_X
            'y', 'Y' -> KeyEvent.KEYCODE_Y
            'z', 'Z' -> KeyEvent.KEYCODE_Z
            '0' -> KeyEvent.KEYCODE_0
            '1' -> KeyEvent.KEYCODE_1
            '2' -> KeyEvent.KEYCODE_2
            '3' -> KeyEvent.KEYCODE_3
            '4' -> KeyEvent.KEYCODE_4
            '5' -> KeyEvent.KEYCODE_5
            '6' -> KeyEvent.KEYCODE_6
            '7' -> KeyEvent.KEYCODE_7
            '8' -> KeyEvent.KEYCODE_8
            '9' -> KeyEvent.KEYCODE_9
            '`' -> KeyEvent.KEYCODE_GRAVE
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '/' -> KeyEvent.KEYCODE_SLASH
            '@' -> KeyEvent.KEYCODE_AT
            '+' -> KeyEvent.KEYCODE_PLUS
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '*' -> KeyEvent.KEYCODE_STAR
            '#' -> KeyEvent.KEYCODE_POUND
            '(' -> KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
            ')' -> KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
            ' ' -> KeyEvent.KEYCODE_SPACE
            else -> return k
        }

        return k.withKeyEvent(keycode)
    }

    /**
     * Hangul initial consonant combination
     */
    fun combine_hangul_initial(medial: Char): Int {
        return when (medial) {
            'ㅏ' -> 0
            'ㅐ' -> 1
            'ㅑ' -> 2
            'ㅒ' -> 3
            'ㅓ' -> 4
            'ㅔ' -> 5
            'ㅕ' -> 6
            'ㅖ' -> 7
            'ㅗ' -> 8
            'ㅘ' -> 9
            'ㅙ' -> 10
            'ㅚ' -> 11
            'ㅛ' -> 12
            'ㅜ' -> 13
            'ㅝ' -> 14
            'ㅞ' -> 15
            'ㅟ' -> 16
            'ㅠ' -> 17
            'ㅡ' -> 18
            'ㅢ' -> 19
            'ㅣ' -> 20
            else -> -1
        }
    }

    /**
     * Hangul medial vowel combination
     */
    fun combine_hangul_medial(final_cons: Char): Int {
        return when (final_cons) {
            ' ' -> 0
            'ㄱ' -> 1
            'ㄲ' -> 2
            'ㄳ' -> 3
            'ㄴ' -> 4
            'ㄵ' -> 5
            'ㄶ' -> 6
            'ㄷ' -> 7
            'ㄹ' -> 8
            'ㄺ' -> 9
            'ㄻ' -> 10
            'ㄼ' -> 11
            'ㄽ' -> 12
            'ㄾ' -> 13
            'ㄿ' -> 14
            'ㅀ' -> 15
            'ㅁ' -> 16
            'ㅂ' -> 17
            'ㅄ' -> 18
            'ㅅ' -> 19
            'ㅆ' -> 20
            'ㅇ' -> 21
            'ㅈ' -> 22
            'ㅊ' -> 23
            'ㅋ' -> 24
            'ㅌ' -> 25
            'ㅍ' -> 26
            'ㅎ' -> 27
            else -> -1
        }
    }
}
