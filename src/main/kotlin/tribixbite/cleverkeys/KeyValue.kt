package tribixbite.cleverkeys

import android.view.KeyEvent

/**
 * Immutable value class representing a keyboard key with bit-packed encoding.
 *
 * Encoding: FLAGS (8 bits) + KIND (4 bits) + VALUE (20 bits)
 * - FLAGS_OFFSET = 20
 * - KIND_OFFSET = 28
 * - Total: 32-bit int (_code field)
 *
 * The [_payload] determines the symbol rendered on the keyboard.
 * The [_code] encodes kind, flags, and value.
 */
class KeyValue private constructor(
    /** [_payload.toString()] is the symbol that is rendered on the keyboard. */
    private val _payload: Comparable<*>,
    /** Encodes Kind (KIND_BITS), flags (FLAGS_BITS), and value (VALUE_BITS). */
    private val _code: Int
) : Comparable<KeyValue> {

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
    }

    /**
     * Must be evaluated in the reverse order of their values.
     * Last is applied first.
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

    enum class Editing {
        COPY,
        PASTE,
        CUT,
        SELECT_ALL,
        PASTE_PLAIN,
        UNDO,
        REDO,
        // Android context menu actions
        REPLACE,
        SHARE,
        ASSIST,
        AUTOFILL,
        DELETE_WORD,
        FORWARD_DELETE_WORD,
        SELECTION_CANCEL,
        DELETE_LAST_WORD, // Smart delete of last auto-inserted or typed word
        CURSOR_DOC_START, // Move cursor to start of document (Ctrl+Home)
        CURSOR_DOC_END, // Move cursor to end of document (Ctrl+End)
    }

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

    enum class Kind {
        Char,
        Keyevent,
        Event,
        Compose_pending,
        Hangul_initial,
        Hangul_medial,
        Modifier,
        Editing,
        Placeholder,
        String, // [_payload] is also the string to output, value is unused.
        Slider, // [_payload] is a [KeyValue.Slider], value is slider repeatition.
        Macro, // [_payload] is a [KeyValue.Macro], value is unused.
    }

    enum class Slider(val symbol: String) {
        Cursor_left("\uE008"),
        Cursor_right("\uE006"),
        Cursor_up("\uE005"),
        Cursor_down("\uE007"),
        Selection_cursor_left("\uE008"),
        Selection_cursor_right("\uE006");

        override fun toString(): String = symbol
    }

    class Macro(
        val keys: Array<KeyValue>,
        private val _symbol: String
    ) : Comparable<Macro> {
        override fun toString(): String = _symbol

        override fun compareTo(other: Macro): Int {
            var d = keys.size - other.keys.size
            if (d != 0) return d
            for (i in keys.indices) {
                d = keys[i].compareTo(other.keys[i])
                if (d != 0) return d
            }
            return _symbol.compareTo(other._symbol)
        }
    }

    // Accessors - methods that work from both Kotlin and Java
    // (Kotlin allows property-style access like .kind instead of .getKind())
    fun getKind(): Kind = Kind.entries[(_code and KIND_BITS) ushr KIND_OFFSET]
    fun getFlags(): Int = (_code and FLAGS_BITS)
    fun getString(): String = _payload.toString()
    fun getChar(): Char = (_code and VALUE_BITS).toChar()
    fun getKeyevent(): Int = (_code and VALUE_BITS)
    fun getEvent(): Event = Event.entries[(_code and VALUE_BITS)]
    fun getModifier(): Modifier = Modifier.entries[(_code and VALUE_BITS)]
    fun getEditing(): Editing = Editing.entries[(_code and VALUE_BITS)]
    fun getPlaceholder(): Placeholder = Placeholder.entries[(_code and VALUE_BITS)]
    fun getSlider(): Slider = _payload as Slider
    fun getSliderRepeat(): Int = (_code and VALUE_BITS).toShort().toInt()
    fun getMacro(): Array<KeyValue> = (_payload as Macro).keys

    fun hasFlagsAny(has: Int): Boolean = ((_code and has) != 0)

    /** Defined only when [getKind() == Kind.Compose_pending]. */
    fun getPendingCompose(): Int = (_code and VALUE_BITS)

    /**
     * Defined only when [getKind()] is [Kind.Hangul_initial] or [Kind.Hangul_medial].
     */
    fun getHangulPrecomposed(): Int = (_code and VALUE_BITS)

    // Immutable update methods

    /** Update the char and the symbol. */
    fun withChar(c: Char): KeyValue = KeyValue(
        c.toString(),
        Kind.Char,
        c.code,
        getFlags() and (FLAG_KEY_FONT or FLAG_SMALLER_FONT).inv()
    )

    fun withKeyevent(code: Int): KeyValue = KeyValue(
        getString(),
        Kind.Keyevent,
        code,
        getFlags()
    )

    fun withFlags(f: Int): KeyValue = KeyValue(_payload, _code, _code, f)

    fun withSymbol(symbol: String): KeyValue {
        var f = getFlags() and (FLAG_KEY_FONT or FLAG_SMALLER_FONT).inv()
        return when (getKind()) {
            Kind.Char, Kind.Keyevent, Kind.Event, Kind.Compose_pending,
            Kind.Hangul_initial, Kind.Hangul_medial, Kind.Modifier,
            Kind.Editing, Kind.Placeholder -> {
                if (symbol.length > 1) f = f or FLAG_SMALLER_FONT
                KeyValue(symbol, _code, _code, f)
            }
            Kind.Macro -> makeMacro(symbol, getMacro(), f)
            else -> makeMacro(symbol, arrayOf(this), f)
        }
    }

    // Comparable implementation

    override fun equals(other: Any?): Boolean = sameKey(other as? KeyValue)

    override fun compareTo(other: KeyValue): Int {
        // Compare the kind and value first, then the flags.
        var d = (_code and FLAGS_BITS.inv()) - (other._code and FLAGS_BITS.inv())
        if (d != 0) return d
        d = _code - other._code
        if (d != 0) return d
        // Calls [compareTo] assuming that if [_code] matches, then [_payload] are of the same class.
        @Suppress("UNCHECKED_CAST")
        return (_payload as Comparable<Any>).compareTo(other._payload)
    }

    /** Type-safe alternative to [equals]. */
    fun sameKey(snd: KeyValue?): Boolean {
        if (snd == null) return false
        @Suppress("UNCHECKED_CAST")
        return _code == snd._code && (_payload as Comparable<Any>).compareTo(snd._payload) == 0
    }

    override fun hashCode(): Int = _payload.hashCode() + _code

    override fun toString(): String {
        val value = _code and VALUE_BITS
        return "[KeyValue ${getKind()}+${getFlags()}+$value \"${getString()}\"]"
    }

    // Primary constructor used internally
    private constructor(p: Comparable<*>, kind: Int, value: Int, flags: Int) : this(
        p, // Payload is already non-null from type system
        (kind and KIND_BITS) or (flags and FLAGS_BITS) or (value and VALUE_BITS)
    ) {
        // Type system ensures p is non-null
    }

    // Public constructor
    constructor(p: Comparable<*>, k: Kind, v: Int, f: Int) : this(
        p,
        (k.ordinal shl KIND_OFFSET),
        v,
        f
    )

    companion object {
        // Bit-packing constants
        private const val FLAGS_OFFSET = 20
        private const val KIND_OFFSET = 28

        // Flag constants (exported as public for external usage)
        // Key stay activated when pressed once.
        const val FLAG_LATCH = (1 shl FLAGS_OFFSET shl 0)
        // Key can be locked by typing twice when enabled in settings
        const val FLAG_DOUBLE_TAP_LOCK = (1 shl FLAGS_OFFSET shl 1)
        // Special keys are not repeated.
        // Special latchable keys don't clear latched modifiers.
        const val FLAG_SPECIAL = (1 shl FLAGS_OFFSET shl 2)
        // Whether the symbol should be greyed out. For example, keys that are not
        // part of the pending compose sequence.
        const val FLAG_GREYED = (1 shl FLAGS_OFFSET shl 3)
        // The special font is required to render this key.
        const val FLAG_KEY_FONT = (1 shl FLAGS_OFFSET shl 4)
        // 25% smaller symbols
        const val FLAG_SMALLER_FONT = (1 shl FLAGS_OFFSET shl 5)
        // Dimmer symbol
        const val FLAG_SECONDARY = (1 shl FLAGS_OFFSET shl 6)
        // Free: (1 << FLAGS_OFFSET << 7)

        // Ranges for the different components
        private const val FLAGS_BITS = (0b11111111 shl FLAGS_OFFSET) // 8 bits wide
        private const val KIND_BITS = (0b1111 shl KIND_OFFSET) // 4 bits wide
        private const val VALUE_BITS = 0b11111111111111111111 // 20 bits wide

        init {
            check((FLAGS_BITS and KIND_BITS) == 0) { "FLAGS and KIND overlap" }
            check((FLAGS_BITS or KIND_BITS).inv() == VALUE_BITS) { "FLAGS/KIND overlaps with VALUE" }
            check((FLAGS_BITS or KIND_BITS or VALUE_BITS) == -1) { "Holes in bit encoding" }
            // No kind is out of range
            check((((Kind.entries.size - 1) shl KIND_OFFSET) and KIND_BITS.inv()) == 0) {
                "Kind out of range"
            }
        }

        // Factory methods

        private fun charKey(symbol: String, c: Char, flags: Int): KeyValue =
            KeyValue(symbol, Kind.Char, c.code, flags)

        private fun charKey(symbol: Int, c: Char, flags: Int): KeyValue =
            charKey(symbol.toChar().toString(), c, flags or FLAG_KEY_FONT)

        private fun modifierKey(symbol: String, m: Modifier, flags: Int): KeyValue {
            var f = flags
            if (symbol.length > 1) f = f or FLAG_SMALLER_FONT
            return KeyValue(
                symbol,
                Kind.Modifier,
                m.ordinal,
                FLAG_LATCH or FLAG_SPECIAL or FLAG_SECONDARY or f
            )
        }

        private fun modifierKey(symbol: Int, m: Modifier, flags: Int): KeyValue =
            modifierKey(symbol.toChar().toString(), m, flags or FLAG_KEY_FONT)

        private fun diacritic(symbol: Int, m: Modifier): KeyValue = KeyValue(
            symbol.toChar().toString(),
            Kind.Modifier,
            m.ordinal,
            FLAG_LATCH or FLAG_SPECIAL or FLAG_KEY_FONT
        )

        private fun eventKey(symbol: String, e: Event, flags: Int): KeyValue =
            KeyValue(symbol, Kind.Event, e.ordinal, flags or FLAG_SPECIAL or FLAG_SECONDARY)

        private fun eventKey(symbol: Int, e: Event, flags: Int): KeyValue =
            eventKey(symbol.toChar().toString(), e, flags or FLAG_KEY_FONT)

        @JvmStatic
        fun keyeventKey(symbol: String, code: Int, flags: Int): KeyValue =
            KeyValue(symbol, Kind.Keyevent, code, flags or FLAG_SECONDARY)

        @JvmStatic
        fun keyeventKey(symbol: Int, code: Int, flags: Int): KeyValue =
            keyeventKey(symbol.toChar().toString(), code, flags or FLAG_KEY_FONT)

        private fun editingKey(symbol: String, action: Editing, flags: Int): KeyValue =
            KeyValue(symbol, Kind.Editing, action.ordinal, flags or FLAG_SPECIAL or FLAG_SECONDARY)

        private fun editingKey(symbol: String, action: Editing): KeyValue =
            editingKey(symbol, action, FLAG_SMALLER_FONT)

        private fun editingKey(symbol: Int, action: Editing): KeyValue =
            editingKey(symbol.toChar().toString(), action, FLAG_KEY_FONT)

        /**
         * A key that slides the property specified by [s] by the amount specified
         * with [repeatition].
         */
        @JvmStatic
        fun sliderKey(s: Slider, repeatition: Int): KeyValue {
            // Casting to a short then back to an int to preserve the sign bit.
            return KeyValue(
                s,
                Kind.Slider,
                repeatition.toShort().toInt() and 0xFFFF,
                FLAG_SPECIAL or FLAG_SECONDARY or FLAG_KEY_FONT
            )
        }

        /** A key that do nothing but has a unique ID. */
        private fun placeholderKey(id: Placeholder): KeyValue =
            KeyValue("", Kind.Placeholder, id.ordinal, 0)

        private fun placeholderKey(symbol: Int, id: Placeholder, flags: Int): KeyValue =
            KeyValue(symbol.toChar().toString(), Kind.Placeholder, id.ordinal, flags or FLAG_KEY_FONT)

        @JvmStatic
        fun makeStringKey(str: String): KeyValue = makeStringKey(str, 0)

        @JvmStatic
        fun makeCharKey(c: Char): KeyValue = makeCharKey(c, null, 0)

        @JvmStatic
        @JvmOverloads
        fun makeCharKey(c: Char, symbol: String?, flags: Int = 0): KeyValue {
            val sym = symbol ?: c.toString()
            return KeyValue(sym, Kind.Char, c.code, flags)
        }

        @JvmStatic
        fun makeCharKey(symbol: Int, c: Char, flags: Int): KeyValue =
            makeCharKey(c, symbol.toChar().toString(), flags or FLAG_KEY_FONT)

        @JvmStatic
        fun makeComposePending(symbol: String, state: Int, flags: Int): KeyValue =
            KeyValue(symbol, Kind.Compose_pending, state, flags or FLAG_LATCH)

        @JvmStatic
        fun makeComposePending(symbol: Int, state: Int, flags: Int): KeyValue =
            makeComposePending(symbol.toChar().toString(), state, flags or FLAG_KEY_FONT)

        @JvmStatic
        fun makeHangulInitial(symbol: String, initial_idx: Int): KeyValue =
            KeyValue(symbol, Kind.Hangul_initial, initial_idx * 588 + 44032, FLAG_LATCH)

        @JvmStatic
        fun makeHangulMedial(precomposed: Int, medial_idx: Int): KeyValue {
            val p = precomposed + medial_idx * 28
            return KeyValue(p.toChar().toString(), Kind.Hangul_medial, p, FLAG_LATCH)
        }

        @JvmStatic
        fun makeHangulFinal(precomposed: Int, final_idx: Int): KeyValue {
            val p = precomposed + final_idx
            return makeCharKey(p.toChar())
        }

        @JvmStatic
        fun makeActionKey(symbol: String): KeyValue =
            eventKey(symbol, Event.ACTION, FLAG_SMALLER_FONT)

        /**
         * Make a key that types a string. A char key is returned for a string of length 1.
         */
        @JvmStatic
        fun makeStringKey(str: String, flags: Int): KeyValue =
            if (str.length == 1)
                KeyValue(str, Kind.Char, str[0].code, flags)
            else
                KeyValue(str, Kind.String, 0, flags or FLAG_SMALLER_FONT)

        @JvmStatic
        fun makeMacro(symbol: String, keys: Array<KeyValue>, flags: Int): KeyValue {
            var f = flags
            if (symbol.length > 1) f = f or FLAG_SMALLER_FONT
            return KeyValue(Macro(keys, symbol), Kind.Macro, 0, f)
        }

        /** Make a modifier key for passing to [KeyModifier]. */
        @JvmStatic
        fun makeInternalModifier(mod: Modifier): KeyValue =
            KeyValue("", Kind.Modifier, mod.ordinal, 0)

        /**
         * Return a key by its name. If the given name doesn't correspond to any
         * special key, it is parsed with [KeyValueParser].
         */
        @JvmStatic
        fun getKeyByName(name: String): KeyValue {
            val k = getSpecialKeyByName(name)
            if (k != null) return k
            return try {
                KeyValueParser.parse(name)
            } catch (_e: KeyValueParser.ParseError) {
                makeStringKey(name)
            }
        }

        @JvmStatic
        fun getSpecialKeyByName(name: String): KeyValue? = when (name) {
            /* These symbols have special meaning when in `srcs/layouts` and are
               escaped in standard layouts. The backslash is not stripped when parsed
               from the custom layout option. */
            "\\?" -> makeStringKey("?")
            "\\#" -> makeStringKey("#")
            "\\@" -> makeStringKey("@")
            "\\\\" -> makeStringKey("\\")

            /* Modifiers and dead-keys */
            "shift" -> modifierKey(0xE00A, Modifier.SHIFT, FLAG_DOUBLE_TAP_LOCK)
            "ctrl" -> modifierKey("Ctrl", Modifier.CTRL, 0)
            "alt" -> modifierKey("Alt", Modifier.ALT, 0)
            "accent_aigu" -> diacritic(0xE050, Modifier.AIGU)
            "accent_caron" -> diacritic(0xE051, Modifier.CARON)
            "accent_cedille" -> diacritic(0xE052, Modifier.CEDILLE)
            "accent_circonflexe" -> diacritic(0xE053, Modifier.CIRCONFLEXE)
            "accent_grave" -> diacritic(0xE054, Modifier.GRAVE)
            "accent_macron" -> diacritic(0xE055, Modifier.MACRON)
            "accent_ring" -> diacritic(0xE056, Modifier.RING)
            "accent_tilde" -> diacritic(0xE057, Modifier.TILDE)
            "accent_trema" -> diacritic(0xE058, Modifier.TREMA)
            "accent_ogonek" -> diacritic(0xE059, Modifier.OGONEK)
            "accent_dot_above" -> diacritic(0xE05A, Modifier.DOT_ABOVE)
            "accent_double_aigu" -> diacritic(0xE05B, Modifier.DOUBLE_AIGU)
            "accent_slash" -> diacritic(0xE05C, Modifier.SLASH)
            "accent_arrow_right" -> diacritic(0xE05D, Modifier.ARROW_RIGHT)
            "accent_breve" -> diacritic(0xE05E, Modifier.BREVE)
            "accent_bar" -> diacritic(0xE05F, Modifier.BAR)
            "accent_dot_below" -> diacritic(0xE060, Modifier.DOT_BELOW)
            "accent_horn" -> diacritic(0xE061, Modifier.HORN)
            "accent_hook_above" -> diacritic(0xE062, Modifier.HOOK_ABOVE)
            "accent_double_grave" -> diacritic(0xE063, Modifier.DOUBLE_GRAVE)
            "superscript" -> modifierKey("Sup", Modifier.SUPERSCRIPT, 0)
            "subscript" -> modifierKey("Sub", Modifier.SUBSCRIPT, 0)
            "ordinal" -> modifierKey("Ord", Modifier.ORDINAL, 0)
            "arrows" -> modifierKey("Arr", Modifier.ARROWS, 0)
            "box" -> modifierKey("Box", Modifier.BOX, 0)
            "fn" -> modifierKey("Fn", Modifier.FN, 0)
            "meta" -> modifierKey("Meta", Modifier.META, 0)

            /* Combining diacritics */
            /* Glyphs is the corresponding dead-key + 0x0100. */
            "combining_dot_above" -> makeCharKey(0xE15A, '\u0307', 0)
            "combining_double_aigu" -> makeCharKey(0xE15B, '\u030B', 0)
            "combining_slash" -> makeCharKey(0xE15C, '\u0337', 0)
            "combining_arrow_right" -> makeCharKey(0xE15D, '\u20D7', 0)
            "combining_breve" -> makeCharKey(0xE15E, '\u0306', 0)
            "combining_bar" -> makeCharKey(0xE15F, '\u0335', 0)
            "combining_aigu" -> makeCharKey(0xE150, '\u0301', 0)
            "combining_caron" -> makeCharKey(0xE151, '\u030C', 0)
            "combining_cedille" -> makeCharKey(0xE152, '\u0327', 0)
            "combining_circonflexe" -> makeCharKey(0xE153, '\u0302', 0)
            "combining_grave" -> makeCharKey(0xE154, '\u0300', 0)
            "combining_macron" -> makeCharKey(0xE155, '\u0304', 0)
            "combining_ring" -> makeCharKey(0xE156, '\u030A', 0)
            "combining_tilde" -> makeCharKey(0xE157, '\u0303', 0)
            "combining_trema" -> makeCharKey(0xE158, '\u0308', 0)
            "combining_ogonek" -> makeCharKey(0xE159, '\u0328', 0)
            "combining_dot_below" -> makeCharKey(0xE160, '\u0323', 0)
            "combining_horn" -> makeCharKey(0xE161, '\u031B', 0)
            "combining_hook_above" -> makeCharKey(0xE162, '\u0309', 0)
            /* Combining diacritics that do not have a corresponding dead keys start
               at 0xE200. */
            "combining_vertical_tilde" -> makeCharKey(0xE200, '\u033E', 0)
            "combining_inverted_breve" -> makeCharKey(0xE201, '\u0311', 0)
            "combining_pokrytie" -> makeCharKey(0xE202, '\u0487', 0)
            "combining_slavonic_psili" -> makeCharKey(0xE203, '\u0486', 0)
            "combining_slavonic_dasia" -> makeCharKey(0xE204, '\u0485', 0)
            "combining_payerok" -> makeCharKey(0xE205, '\uA67D', 0)
            "combining_titlo" -> makeCharKey(0xE206, '\u0483', 0)
            "combining_vzmet" -> makeCharKey(0xE207, '\uA66F', 0)
            "combining_arabic_v" -> makeCharKey(0xE208, '\u065A', 0)
            "combining_arabic_inverted_v" -> makeCharKey(0xE209, '\u065B', 0)
            "combining_shaddah" -> makeCharKey(0xE210, '\u0651', 0)
            "combining_sukun" -> makeCharKey(0xE211, '\u0652', 0)
            "combining_fatha" -> makeCharKey(0xE212, '\u064E', 0)
            "combining_dammah" -> makeCharKey(0xE213, '\u064F', 0)
            "combining_kasra" -> makeCharKey(0xE214, '\u0650', 0)
            "combining_hamza_above" -> makeCharKey(0xE215, '\u0654', 0)
            "combining_hamza_below" -> makeCharKey(0xE216, '\u0655', 0)
            "combining_alef_above" -> makeCharKey(0xE217, '\u0670', 0)
            "combining_fathatan" -> makeCharKey(0xE218, '\u064B', 0)
            "combining_kasratan" -> makeCharKey(0xE219, '\u064D', 0)
            "combining_dammatan" -> makeCharKey(0xE220, '\u064C', 0)
            "combining_alef_below" -> makeCharKey(0xE221, '\u0656', 0)
            "combining_kavyka" -> makeCharKey(0xE222, '\uA67C', 0)
            "combining_palatalization" -> makeCharKey(0xE223, '\u0484', 0)

            /* Special event keys */
            "config" -> eventKey(0xE004, Event.CONFIG, FLAG_SMALLER_FONT)
            "switch_text" -> eventKey("ABC", Event.SWITCH_TEXT, FLAG_SMALLER_FONT)
            "switch_numeric" -> eventKey("123+", Event.SWITCH_NUMERIC, FLAG_SMALLER_FONT)
            "switch_emoji" -> eventKey(0xE001, Event.SWITCH_EMOJI, FLAG_SMALLER_FONT)
            "switch_back_emoji" -> eventKey("ABC", Event.SWITCH_BACK_EMOJI, 0)
            "switch_clipboard" -> eventKey(0xE017, Event.SWITCH_CLIPBOARD, 0)
            "switch_back_clipboard" -> eventKey("ABC", Event.SWITCH_BACK_CLIPBOARD, 0)
            "switch_forward" -> eventKey(0xE013, Event.SWITCH_FORWARD, FLAG_SMALLER_FONT)
            "switch_backward" -> eventKey(0xE014, Event.SWITCH_BACKWARD, FLAG_SMALLER_FONT)
            "switch_greekmath" -> eventKey("πλ∇¬", Event.SWITCH_GREEKMATH, FLAG_SMALLER_FONT)
            "change_method" -> eventKey(0xE009, Event.CHANGE_METHOD_PICKER, FLAG_SMALLER_FONT)
            "change_method_prev" -> eventKey(0xE009, Event.CHANGE_METHOD_AUTO, FLAG_SMALLER_FONT)
            "action" -> eventKey("Action", Event.ACTION, FLAG_SMALLER_FONT) // Will always be replaced
            "capslock" -> eventKey(0xE012, Event.CAPS_LOCK, 0)
            "voice_typing" -> eventKey(0xE015, Event.SWITCH_VOICE_TYPING, FLAG_SMALLER_FONT)
            "voice_typing_chooser" -> eventKey(0xE015, Event.SWITCH_VOICE_TYPING_CHOOSER, FLAG_SMALLER_FONT)

            /* Key events */
            "esc" -> keyeventKey("Esc", KeyEvent.KEYCODE_ESCAPE, FLAG_SMALLER_FONT)
            "enter" -> keyeventKey(0xE00E, KeyEvent.KEYCODE_ENTER, 0)
            "up" -> keyeventKey(0xE005, KeyEvent.KEYCODE_DPAD_UP, 0)
            "right" -> keyeventKey(0xE006, KeyEvent.KEYCODE_DPAD_RIGHT, FLAG_SMALLER_FONT)
            "down" -> keyeventKey(0xE007, KeyEvent.KEYCODE_DPAD_DOWN, 0)
            "left" -> keyeventKey(0xE008, KeyEvent.KEYCODE_DPAD_LEFT, FLAG_SMALLER_FONT)
            "page_up" -> keyeventKey(0xE002, KeyEvent.KEYCODE_PAGE_UP, 0)
            "page_down" -> keyeventKey(0xE003, KeyEvent.KEYCODE_PAGE_DOWN, 0)
            "home" -> keyeventKey(0xE00B, KeyEvent.KEYCODE_MOVE_HOME, FLAG_SMALLER_FONT)
            "end" -> keyeventKey(0xE00C, KeyEvent.KEYCODE_MOVE_END, FLAG_SMALLER_FONT)
            "backspace" -> keyeventKey(0xE011, KeyEvent.KEYCODE_DEL, 0)
            "delete" -> keyeventKey(0xE010, KeyEvent.KEYCODE_FORWARD_DEL, 0)
            "insert" -> keyeventKey("Ins", KeyEvent.KEYCODE_INSERT, FLAG_SMALLER_FONT)
            "f1" -> keyeventKey("F1", KeyEvent.KEYCODE_F1, 0)
            "f2" -> keyeventKey("F2", KeyEvent.KEYCODE_F2, 0)
            "f3" -> keyeventKey("F3", KeyEvent.KEYCODE_F3, 0)
            "f4" -> keyeventKey("F4", KeyEvent.KEYCODE_F4, 0)
            "f5" -> keyeventKey("F5", KeyEvent.KEYCODE_F5, 0)
            "f6" -> keyeventKey("F6", KeyEvent.KEYCODE_F6, 0)
            "f7" -> keyeventKey("F7", KeyEvent.KEYCODE_F7, 0)
            "f8" -> keyeventKey("F8", KeyEvent.KEYCODE_F8, 0)
            "f9" -> keyeventKey("F9", KeyEvent.KEYCODE_F9, 0)
            "f10" -> keyeventKey("F10", KeyEvent.KEYCODE_F10, 0)
            "f11" -> keyeventKey("F11", KeyEvent.KEYCODE_F11, FLAG_SMALLER_FONT)
            "f12" -> keyeventKey("F12", KeyEvent.KEYCODE_F12, FLAG_SMALLER_FONT)
            "tab" -> keyeventKey(0xE00F, KeyEvent.KEYCODE_TAB, FLAG_SMALLER_FONT)
            "menu" -> keyeventKey("Menu", KeyEvent.KEYCODE_MENU, FLAG_SMALLER_FONT)
            "scroll_lock" -> keyeventKey("Scrl", KeyEvent.KEYCODE_SCROLL_LOCK, FLAG_SMALLER_FONT)

            /* Spaces */
            "\\t" -> charKey("\\t", '\t', 0) // Send the tab character
            "\\n" -> charKey("\\n", '\n', 0) // Send the newline character
            "space" -> charKey(0xE00D, ' ', FLAG_SMALLER_FONT or FLAG_GREYED)
            "nbsp" -> charKey("\u237d", '\u00a0', FLAG_SMALLER_FONT)
            "nnbsp" -> charKey("\u2423", '\u202F', FLAG_SMALLER_FONT)

            /* bidi */
            "lrm" -> charKey("↱", '\u200e', 0) // Send left-to-right mark
            "rlm" -> charKey("↰", '\u200f', 0) // Send right-to-left mark
            "b(" -> charKey("(", ')', 0)
            "b)" -> charKey(")", '(', 0)
            "b[" -> charKey("[", ']', 0)
            "b]" -> charKey("]", '[', 0)
            "b{" -> charKey("{", '}', 0)
            "b}" -> charKey("}", '{', 0)
            "blt" -> charKey("<", '>', 0)
            "bgt" -> charKey(">", '<', 0)

            /* hebrew niqqud */
            "qamats" -> charKey("\u05E7\u05B8", '\u05B8', 0) // kamatz
            "patah" -> charKey("\u05E4\u05B7", '\u05B7', 0) // patach
            "sheva" -> charKey("\u05E9\u05B0", '\u05B0', 0)
            "dagesh" -> charKey("\u05D3\u05BC", '\u05BC', 0) // or mapiq
            "hiriq" -> charKey("\u05D7\u05B4", '\u05B4', 0)
            "segol" -> charKey("\u05E1\u05B6", '\u05B6', 0)
            "tsere" -> charKey("\u05E6\u05B5", '\u05B5', 0)
            "holam" -> charKey("\u05D5\u05B9", '\u05B9', 0)
            "qubuts" -> charKey("\u05E7\u05BB", '\u05BB', 0) // kubuts
            "hataf_patah" -> charKey("\u05D7\u05B2\u05E4\u05B7", '\u05B2', 0) // reduced patach
            "hataf_qamats" -> charKey("\u05D7\u05B3\u05E7\u05B8", '\u05B3', 0) // reduced kamatz
            "hataf_segol" -> charKey("\u05D7\u05B1\u05E1\u05B6", '\u05B1', 0) // reduced segol
            "shindot" -> charKey("\u05E9\u05C1", '\u05C1', 0)
            "shindot_placeholder" -> placeholderKey(Placeholder.SHINDOT)
            "sindot" -> charKey("\u05E9\u05C2", '\u05C2', 0)
            "sindot_placeholder" -> placeholderKey(Placeholder.SINDOT)
            /* hebrew punctuation */
            "geresh" -> charKey("\u05F3", '\u05F3', 0)
            "gershayim" -> charKey("\u05F4", '\u05F4', 0)
            "maqaf" -> charKey("\u05BE", '\u05BE', 0)
            /* hebrew biblical */
            "rafe" -> charKey("\u05E4\u05BF", '\u05BF', 0)
            "ole" -> charKey("\u05E2\u05AB", '\u05AB', 0)
            "ole_placeholder" -> placeholderKey(Placeholder.OLE)
            "meteg" -> charKey("\u05DE\u05BD", '\u05BD', 0) // or siluq or sof-pasuq
            "meteg_placeholder" -> placeholderKey(Placeholder.METEG)
            /* intending/preventing ligature - supported by many scripts*/
            "zwj" -> charKey(0xE019, '\u200D', 0) // zero-width joiner (provides ligature)
            "zwnj", "halfspace" -> charKey(0xE018, '\u200C', 0) // zero-width non joiner

            /* Editing keys */
            "copy" -> editingKey(0xE030, Editing.COPY)
            "paste" -> editingKey(0xE032, Editing.PASTE)
            "cut" -> editingKey(0xE031, Editing.CUT)
            "selectAll" -> editingKey(0xE033, Editing.SELECT_ALL)
            "shareText" -> editingKey(0xE034, Editing.SHARE)
            "pasteAsPlainText" -> editingKey(0xE035, Editing.PASTE_PLAIN)
            "undo" -> editingKey(0xE036, Editing.UNDO)
            "redo" -> editingKey(0xE037, Editing.REDO)
            "delete_word" -> editingKey(0xE01B, Editing.DELETE_WORD)
            "forward_delete_word" -> editingKey(0xE01C, Editing.FORWARD_DELETE_WORD)
            "delete_last_word" -> editingKey("word", Editing.DELETE_LAST_WORD)
            "cursor_left" -> sliderKey(Slider.Cursor_left, 1)
            "cursor_right" -> sliderKey(Slider.Cursor_right, 1)
            "cursor_up" -> sliderKey(Slider.Cursor_up, 1)
            "cursor_down" -> sliderKey(Slider.Cursor_down, 1)
            "selection_cancel" -> editingKey("Esc", Editing.SELECTION_CANCEL, FLAG_SMALLER_FONT)
            "selection_cursor_left" -> sliderKey(Slider.Selection_cursor_left, -1) // Move the left side of the selection
            "selection_cursor_right" -> sliderKey(Slider.Selection_cursor_right, 1)
            // These keys are not used
            "replaceText" -> editingKey("repl", Editing.REPLACE)
            "textAssist" -> editingKey(0xE038, Editing.ASSIST)
            "autofill" -> editingKey("auto", Editing.AUTOFILL)

            /* Document navigation - Ctrl+Home/End to move to start/end of entire text */
            "doc_home" -> editingKey(0xE00B, Editing.CURSOR_DOC_START, FLAG_SMALLER_FONT)
            "doc_end" -> editingKey(0xE00C, Editing.CURSOR_DOC_END, FLAG_SMALLER_FONT)

            /* The compose key */
            "compose" -> makeComposePending(0xE016, ComposeKeyData.compose, FLAG_SECONDARY)
            "compose_cancel" -> placeholderKey(0xE01A, Placeholder.COMPOSE_CANCEL, FLAG_SECONDARY)

            /* Placeholder keys */
            "removed" -> placeholderKey(Placeholder.REMOVED)
            "f11_placeholder" -> placeholderKey(Placeholder.F11)
            "f12_placeholder" -> placeholderKey(Placeholder.F12)

            // Korean Hangul
            "ㄱ" -> makeHangulInitial("ㄱ", 0)
            "ㄲ" -> makeHangulInitial("ㄲ", 1)
            "ㄴ" -> makeHangulInitial("ㄴ", 2)
            "ㄷ" -> makeHangulInitial("ㄷ", 3)
            "ㄸ" -> makeHangulInitial("ㄸ", 4)
            "ㄹ" -> makeHangulInitial("ㄹ", 5)
            "ㅁ" -> makeHangulInitial("ㅁ", 6)
            "ㅂ" -> makeHangulInitial("ㅂ", 7)
            "ㅃ" -> makeHangulInitial("ㅃ", 8)
            "ㅅ" -> makeHangulInitial("ㅅ", 9)
            "ㅆ" -> makeHangulInitial("ㅆ", 10)
            "ㅇ" -> makeHangulInitial("ㅇ", 11)
            "ㅈ" -> makeHangulInitial("ㅈ", 12)
            "ㅉ" -> makeHangulInitial("ㅉ", 13)
            "ㅊ" -> makeHangulInitial("ㅊ", 14)
            "ㅋ" -> makeHangulInitial("ㅋ", 15)
            "ㅌ" -> makeHangulInitial("ㅌ", 16)
            "ㅍ" -> makeHangulInitial("ㅍ", 17)
            "ㅎ" -> makeHangulInitial("ㅎ", 18)

            /* Tamil letters should be smaller on the keyboard. */
            "ஔ", "ந", "ல", "ழ", "௯", "க",
            "ஷ", "ே", "௨", "ஜ", "ங", "ன",
            "௦", "ை", "ூ", "ம", "ஆ", "௭",
            "௪", "ா", "ஶ", "௬", "வ", "ஸ",
            "௮", "ட", "ப", "ஈ", "௩", "ஒ",
            "ௌ", "உ", "௫", "ய", "ர", "ு",
            "இ", "ோ", "ஓ", "ஃ", "ற", "த",
            "௧", "ண", "ஏ", "ஊ", "ொ", "ஞ",
            "அ", "எ", "ச", "ெ", "ஐ", "ி",
            "௹", "ள", "ஹ", "௰", "ௐ", "௱",
            "௲", "௳" -> makeStringKey(name, FLAG_SMALLER_FONT)

            /* Sinhala letters to reduced size */
            "අ", "ආ", "ඇ", "ඈ", "ඉ",
            "ඊ", "උ", "ඌ", "ඍ", "ඎ",
            "ඏ", "ඐ", "එ", "ඒ", "ඓ",
            "ඔ", "ඕ", "ඖ", "ක", "ඛ",
            "ග", "ඝ", "ඞ", "ඟ", "ච",
            "ඡ", "ජ", "ඣ", "ඤ", "ඥ",
            "ඦ", "ට", "ඨ", "ඩ", "ඪ",
            "ණ", "ඬ", "ත", "ථ", "ද",
            "ධ", "න", "ඳ", "ප", "ඵ",
            "බ", "භ", "ම", "ඹ", "ය",
            "ර", "ල", "ව", "ශ", "ෂ",
            "ස", "හ", "ළ", "ෆ",
            /* Astrological numbers */
            "෦", "෧", "෨", "෩", "෪",
            "෫", "෬", "෭", "෮", "෯",
            "ෲ", "ෳ",
            /* Diacritics */
            "\u0d81", "\u0d82", "\u0d83", "\u0dca",
            "\u0dcf", "\u0dd0", "\u0dd1", "\u0dd2",
            "\u0dd3", "\u0dd4", "\u0dd6", "\u0dd8",
            "\u0dd9", "\u0dda", "\u0ddb", "\u0ddc",
            "\u0ddd", "\u0dde", "\u0ddf",
            /* Archaic digits */
            "𑇡", "𑇢", "𑇣", "𑇤", "𑇥",
            "𑇦", "𑇧", "𑇨", "𑇩", "𑇪",
            "𑇫", "𑇬", "𑇭", "𑇮", "𑇯",
            "𑇰", "𑇱", "𑇲", "𑇳", "𑇴",
            /* Extra */
            "෴", "₨" -> makeStringKey(name, FLAG_SMALLER_FONT)  // Rupee is not exclusively Sinhala sign

            /* Internal keys */
            "selection_mode" -> makeInternalModifier(Modifier.SELECTION_MODE)

            else -> null
        }
    }
}
