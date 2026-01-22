package tribixbite.cleverkeys

import android.view.KeyCharacterMap
import android.view.KeyEvent

object KeyModifier {
    /** The optional modmap takes priority over modifiers usual behaviors. Set to
        [null] to disable. */
    private var modmap: Modmap? = null

    @JvmStatic
    fun set_modmap(mm: Modmap?) {
        modmap = mm
    }

    @JvmStatic
    @Deprecated("Use setModmap instead", ReplaceWith("setModmap(mm)"))
    fun setModmap(mm: Modmap?) {
        modmap = mm
    }

    /** Modify a key according to modifiers. */
    @JvmStatic
    fun modify(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? {
        if (k == null) return null
        val nMods = mods.size()
        var r: KeyValue = k
        for (i in 0 until nMods) {
            val mod = mods.get(i)
            if (mod != null) {
                r = modify(r, mod)
            }
        }
        /* Keys with an empty string are placeholder keys. */
        if (r.getString().isEmpty()) return null
        return r
    }

    @JvmStatic
    fun modify(k: KeyValue, mod: KeyValue): KeyValue {
        return when (mod.getKind()) {
            KeyValue.Kind.Modifier ->
                modify(k, mod.getModifier())
            KeyValue.Kind.Compose_pending ->
                applyComposePending(mod.getPendingCompose(), k)
            KeyValue.Kind.Hangul_initial -> {
                if (k == mod) // Allow typing the initial in letter form
                    KeyValue.makeStringKey(k.getString(), KeyValue.FLAG_GREYED)
                else
                    combineHangulInitial(k, mod.getHangulPrecomposed())
            }
            KeyValue.Kind.Hangul_medial ->
                combineHangulMedial(k, mod.getHangulPrecomposed())
            else -> k
        }
    }

    @JvmStatic
    fun modify(k: KeyValue, mod: KeyValue.Modifier): KeyValue {
        return when (mod) {
            KeyValue.Modifier.CTRL -> applyCtrl(k)
            KeyValue.Modifier.ALT,
            KeyValue.Modifier.META -> turnIntoKeyevent(k)
            KeyValue.Modifier.FN -> applyFn(k)
            KeyValue.Modifier.GESTURE -> applyGesture(k)
            KeyValue.Modifier.SHIFT -> applyShift(k)
            KeyValue.Modifier.GRAVE -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_GRAVE, '\u02CB')
            KeyValue.Modifier.AIGU -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_AIGU, '\u00B4')
            KeyValue.Modifier.CIRCONFLEXE -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_CIRCONFLEXE, '\u02C6')
            KeyValue.Modifier.TILDE -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_TILDE, '\u02DC')
            KeyValue.Modifier.CEDILLE -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_CEDILLE, '\u00B8')
            KeyValue.Modifier.TREMA -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_TREMA, '\u00A8')
            KeyValue.Modifier.CARON -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_CARON, '\u02C7')
            KeyValue.Modifier.RING -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_RING, '\u02DA')
            KeyValue.Modifier.MACRON -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_MACRON, '\u00AF')
            KeyValue.Modifier.OGONEK -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_OGONEK, '\u02DB')
            KeyValue.Modifier.DOT_ABOVE -> applyComposeOrDeadChar(k, ComposeKeyData.ACCENT_DOT_ABOVE, '\u02D9')
            KeyValue.Modifier.BREVE -> applyDeadChar(k, '\u02D8')
            KeyValue.Modifier.DOUBLE_AIGU -> applyCompose(k, ComposeKeyData.ACCENT_DOUBLE_AIGU)
            KeyValue.Modifier.ORDINAL -> applyCompose(k, ComposeKeyData.ACCENT_ORDINAL)
            KeyValue.Modifier.SUPERSCRIPT -> applyCompose(k, ComposeKeyData.ACCENT_SUPERSCRIPT)
            KeyValue.Modifier.SUBSCRIPT -> applyCompose(k, ComposeKeyData.ACCENT_SUBSCRIPT)
            KeyValue.Modifier.ARROWS -> applyCompose(k, ComposeKeyData.ACCENT_ARROWS)
            KeyValue.Modifier.BOX -> applyCompose(k, ComposeKeyData.ACCENT_BOX)
            KeyValue.Modifier.SLASH -> applyCompose(k, ComposeKeyData.ACCENT_SLASH)
            KeyValue.Modifier.BAR -> applyCompose(k, ComposeKeyData.ACCENT_BAR)
            KeyValue.Modifier.DOT_BELOW -> applyCompose(k, ComposeKeyData.ACCENT_DOT_BELOW)
            KeyValue.Modifier.HORN -> applyCompose(k, ComposeKeyData.ACCENT_HORN)
            KeyValue.Modifier.HOOK_ABOVE -> applyCompose(k, ComposeKeyData.ACCENT_HOOK_ABOVE)
            KeyValue.Modifier.DOUBLE_GRAVE -> applyCompose(k, ComposeKeyData.ACCENT_DOUBLE_GRAVE)
            KeyValue.Modifier.ARROW_RIGHT -> applyCombiningChar(k, "\u20D7")
            KeyValue.Modifier.SELECTION_MODE -> applySelectionMode(k)
            else -> k
        }
    }

    /** Modify a key after a long press. */
    @JvmStatic
    fun modify_long_press(k: KeyValue): KeyValue {
        if (k.getKind() == KeyValue.Kind.Event) {
            return when (k.getEvent()) {
                KeyValue.Event.CHANGE_METHOD_AUTO ->
                    KeyValue.getKeyByName("change_method")
                KeyValue.Event.SWITCH_VOICE_TYPING ->
                    KeyValue.getKeyByName("voice_typing_chooser")
                else -> k
            }
        }
        return k
    }

    @JvmStatic
    @Deprecated("Use modifyLongPress instead", ReplaceWith("modifyLongPress(k)"))
    fun modifyLongPress(k: KeyValue): KeyValue = modify_long_press(k)

    /** Return the compose state that modifies the numpad script. */
    @JvmStatic
    fun modify_numpad_script(numpadScript: String?): Int {
        if (numpadScript == null) return -1
        return when (numpadScript) {
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

    @JvmStatic
    @Deprecated("Use modifyNumpadScript instead", ReplaceWith("modifyNumpadScript(numpadScript)"))
    fun modifyNumpadScript(numpadScript: String?): Int = modify_numpad_script(numpadScript)

    /** Keys that do not match any sequence are greyed. */
    private fun applyComposePending(state: Int, kv: KeyValue): KeyValue {
        return when (kv.getKind()) {
            KeyValue.Kind.Char,
            KeyValue.Kind.String -> {
                val res = ComposeKey.apply(state, kv)
                // Grey-out characters not part of any sequence.
                if (res == null)
                    kv.withFlags(kv.getFlags() or KeyValue.FLAG_GREYED)
                else
                    res
            }
            /* Tapping compose again exits the pending sequence. */
            KeyValue.Kind.Compose_pending ->
                KeyValue.getKeyByName("compose_cancel")
            /* These keys are not greyed. */
            KeyValue.Kind.Event,
            KeyValue.Kind.Modifier ->
                kv
            /* Other keys cannot be part of sequences. */
            else ->
                kv.withFlags(kv.getFlags() or KeyValue.FLAG_GREYED)
        }
    }

    /** Apply the given compose state or fallback to the dead_char. */
    private fun applyComposeOrDeadChar(k: KeyValue, state: Int, deadChar: Char): KeyValue {
        val r = ComposeKey.apply(state, k)
        return r ?: applyDeadChar(k, deadChar)
    }

    private fun applyCompose(k: KeyValue, state: Int): KeyValue {
        val r = ComposeKey.apply(state, k)
        return r ?: k
    }

    private fun applyDeadChar(k: KeyValue, deadChar: Char): KeyValue {
        if (k.getKind() == KeyValue.Kind.Char) {
            val c = k.getChar()
            val modified = KeyCharacterMap.getDeadChar(deadChar.code, c.code).toChar()
            if (modified.code != 0 && modified != c) {
                return KeyValue.makeStringKey(modified.toString())
            }
        }
        return k
    }

    private fun applyCombiningChar(k: KeyValue, combining: String): KeyValue {
        if (k.getKind() == KeyValue.Kind.Char) {
            return KeyValue.makeStringKey(k.getChar().toString() + combining, k.getFlags())
        }
        return k
    }

    private fun applyShift(k: KeyValue): KeyValue {
        modmap?.let { mm ->
            val mapped = mm.get(Modmap.M.Shift, k)
            if (mapped != null) return mapped
        }
        val r = ComposeKey.apply(ComposeKeyData.shift, k)
        if (r != null) return r

        return when (k.getKind()) {
            KeyValue.Kind.Char -> {
                val kc = k.getChar()
                val c = kc.uppercaseChar()
                if (kc == c) k else k.withChar(c)
            }
            KeyValue.Kind.String -> {
                val ks = k.getString()
                val s = Utils.capitalize_string(ks)
                if (s == ks) k else KeyValue.makeStringKey(s, k.getFlags())
            }
            else -> k
        }
    }

    private fun applyFn(k: KeyValue): KeyValue {
        modmap?.let { mm ->
            val mapped = mm.get(Modmap.M.Fn, k)
            if (mapped != null) return mapped
        }
        val name: String? = when (k.getKind()) {
            KeyValue.Kind.Char,
            KeyValue.Kind.String -> {
                val r = ComposeKey.apply(ComposeKeyData.fn, k)
                return r ?: k
            }
            KeyValue.Kind.Keyevent -> applyFnKeyevent(k.getKeyevent())
            KeyValue.Kind.Event -> applyFnEvent(k.getEvent())
            KeyValue.Kind.Placeholder -> applyFnPlaceholder(k.getPlaceholder())
            KeyValue.Kind.Editing -> applyFnEditing(k.getEditing())
            else -> null
        }
        return if (name == null) k else KeyValue.getKeyByName(name)
    }

    private fun applyFnKeyevent(code: Int): String? {
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "page_up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "page_down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "home"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "end"
            KeyEvent.KEYCODE_ESCAPE -> "insert"
            KeyEvent.KEYCODE_TAB -> "\\t"
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END -> "removed"
            else -> null
        }
    }

    private fun applyFnEvent(ev: KeyValue.Event): String? {
        return when (ev) {
            KeyValue.Event.SWITCH_NUMERIC -> {
                // #77: Only transform to switch_greekmath if it's enabled in extra keys
                val config = Config.globalConfig()
                val greekMathKey = KeyValue.getKeyByName("switch_greekmath")
                if (config.extra_keys_param.containsKey(greekMathKey) ||
                    config.extra_keys_custom.containsKey(greekMathKey)) {
                    "switch_greekmath"
                } else {
                    null  // Greek/Math not enabled, don't transform
                }
            }
            else -> null
        }
    }

    private fun applyFnPlaceholder(p: KeyValue.Placeholder): String? {
        return when (p) {
            KeyValue.Placeholder.F11 -> "f11"
            KeyValue.Placeholder.F12 -> "f12"
            KeyValue.Placeholder.SHINDOT -> "shindot"
            KeyValue.Placeholder.SINDOT -> "sindot"
            KeyValue.Placeholder.OLE -> "ole"
            KeyValue.Placeholder.METEG -> "meteg"
            else -> null
        }
    }

    private fun applyFnEditing(p: KeyValue.Editing): String? {
        return when (p) {
            KeyValue.Editing.UNDO -> "redo"
            KeyValue.Editing.PASTE -> "pasteAsPlainText"
            else -> null
        }
    }

    private fun applyCtrl(k: KeyValue): KeyValue {
        var key = k
        modmap?.let { mm ->
            val mapped = mm.get(Modmap.M.Ctrl, k)
            // Do not return the modified character right away, first turn it into a
            // key event.
            if (mapped != null) {
                key = mapped
            }
        }
        return turnIntoKeyevent(key)
    }

    private fun turnIntoKeyevent(k: KeyValue): KeyValue {
        if (k.getKind() != KeyValue.Kind.Char) return k

        val e = when (k.getChar()) {
            'a' -> KeyEvent.KEYCODE_A
            'b' -> KeyEvent.KEYCODE_B
            'c' -> KeyEvent.KEYCODE_C
            'd' -> KeyEvent.KEYCODE_D
            'e' -> KeyEvent.KEYCODE_E
            'f' -> KeyEvent.KEYCODE_F
            'g' -> KeyEvent.KEYCODE_G
            'h' -> KeyEvent.KEYCODE_H
            'i' -> KeyEvent.KEYCODE_I
            'j' -> KeyEvent.KEYCODE_J
            'k' -> KeyEvent.KEYCODE_K
            'l' -> KeyEvent.KEYCODE_L
            'm' -> KeyEvent.KEYCODE_M
            'n' -> KeyEvent.KEYCODE_N
            'o' -> KeyEvent.KEYCODE_O
            'p' -> KeyEvent.KEYCODE_P
            'q' -> KeyEvent.KEYCODE_Q
            'r' -> KeyEvent.KEYCODE_R
            's' -> KeyEvent.KEYCODE_S
            't' -> KeyEvent.KEYCODE_T
            'u' -> KeyEvent.KEYCODE_U
            'v' -> KeyEvent.KEYCODE_V
            'w' -> KeyEvent.KEYCODE_W
            'x' -> KeyEvent.KEYCODE_X
            'y' -> KeyEvent.KEYCODE_Y
            'z' -> KeyEvent.KEYCODE_Z
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
        return k.withKeyevent(e)
    }

    /** Modify a key affected by a round-trip or a clockwise circle gesture. */
    private fun applyGesture(k: KeyValue): KeyValue {
        var modified = applyShift(k)
        if (modified != k) return modified

        modified = applyFn(k)
        if (modified != k) return modified

        val name: String? = when (k.getKind()) {
            KeyValue.Kind.Modifier -> {
                when (k.getModifier()) {
                    KeyValue.Modifier.SHIFT -> "capslock"
                    else -> null
                }
            }
            KeyValue.Kind.Keyevent -> {
                when (k.getKeyevent()) {
                    KeyEvent.KEYCODE_DEL -> "delete_word"
                    KeyEvent.KEYCODE_FORWARD_DEL -> "forward_delete_word"
                    else -> null
                }
            }
            else -> null
        }
        return if (name == null) k else KeyValue.getKeyByName(name)
    }

    private fun applySelectionMode(k: KeyValue): KeyValue {
        // #1142: Characters (including space) pass through unchanged to replace selection
        // Only non-character keys get special selection-mode handling
        val name: String? = when (k.getKind()) {
            KeyValue.Kind.Char -> null // Let characters replace selected text normally
            KeyValue.Kind.Slider -> {
                when (k.getSlider()) {
                    KeyValue.Slider.Cursor_left -> "selection_cursor_left"
                    KeyValue.Slider.Cursor_right -> "selection_cursor_right"
                    else -> null
                }
            }
            KeyValue.Kind.Keyevent -> {
                when (k.getKeyevent()) {
                    KeyEvent.KEYCODE_ESCAPE -> "selection_cancel"
                    else -> null
                }
            }
            else -> null
        }
        return if (name == null) k else KeyValue.getKeyByName(name)
    }

    /** Compose the precomposed initial with the medial [kv]. */
    private fun combineHangulInitial(kv: KeyValue, precomposed: Int): KeyValue {
        return when (kv.getKind()) {
            KeyValue.Kind.Char ->
                combineHangulInitial(kv, kv.getChar(), precomposed)
            KeyValue.Kind.Hangul_initial ->
                // No initials are expected to compose, grey out
                kv.withFlags(kv.getFlags() or KeyValue.FLAG_GREYED)
            else -> kv
        }
    }

    private fun combineHangulInitial(kv: KeyValue, medial: Char, precomposed: Int): KeyValue {
        val medialIdx = when (medial) {
            // Vowels
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
            // Grey-out uncomposable characters
            else -> return kv.withFlags(kv.getFlags() or KeyValue.FLAG_GREYED)
        }
        return KeyValue.makeHangulMedial(precomposed, medialIdx)
    }

    /** Combine the precomposed medial with the final [kv]. */
    private fun combineHangulMedial(kv: KeyValue, precomposed: Int): KeyValue {
        return when (kv.getKind()) {
            KeyValue.Kind.Char ->
                combineHangulMedial(kv, kv.getChar(), precomposed)
            KeyValue.Kind.Hangul_initial ->
                // Finals that can also be initials have this kind.
                combineHangulMedial(kv, kv.getString()[0], precomposed)
            else -> kv
        }
    }

    private fun combineHangulMedial(kv: KeyValue, c: Char, precomposed: Int): KeyValue {
        val finalIdx = when (c) {
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
            // Grey-out uncomposable characters
            else -> return kv.withFlags(kv.getFlags() or KeyValue.FLAG_GREYED)
        }
        return KeyValue.makeHangulFinal(precomposed, finalIdx)
    }
}
