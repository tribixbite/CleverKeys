package tribixbite.cleverkeys.a11y

import android.view.KeyEvent
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.R

/**
 * Pure spoken-label resolver for a [KeyValue] — the content description a screen
 * reader (TalkBack) announces for a key. Kept Android-free: string resources are
 * injected via a `(Int)->String` resolver so this is unit-testable on the JVM
 * with a fake resolver. The live view passes `context::getString`.
 *
 * ## Why not just use `kv.getString()`?
 * Most special keys render with `special_font.ttf` — their symbol is a **Private
 * Use Area** codepoint (0xE000-range, e.g. shift = 0xE00A, backspace = 0xE011).
 * TTS reads those as garbage ("private use character U+E00A") or silence. So:
 *
 * **HARD RULE:** if [KeyValue.hasFlagsAny]`(FLAG_KEY_FONT)` is set and no explicit
 * mapping matched, we NEVER fall back to `getString()`. We fall back to a
 * Kind + value name instead. Only plain (non-font) keys may echo their symbol.
 */
object KeyLabels {

    /** Resolve a [KeyValue] to a spoken label. Never returns an empty string. */
    fun describe(kv: KeyValue, getString: (Int) -> String): String {
        val explicit = describeExplicit(kv, getString)
        if (explicit != null && explicit.isNotEmpty()) return explicit

        // No explicit mapping. Decide whether echoing the payload symbol is safe.
        val symbol = kv.getString()
        if (kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT) || isPrivateUse(symbol)) {
            // PUA glyph or font-key with no mapping — echoing would read as garbage.
            return kindFallback(kv)
        }
        return symbol.ifEmpty { kindFallback(kv) }
    }

    /**
     * Explicit, human-meaningful label for keys we recognize, or null if none
     * applies (caller then decides whether the raw symbol is safe to echo).
     */
    private fun describeExplicit(kv: KeyValue, getString: (Int) -> String): String? =
        when (kv.getKind()) {
            KeyValue.Kind.Char -> describeChar(kv, getString)
            KeyValue.Kind.Keyevent -> describeKeyevent(kv, getString)
            KeyValue.Kind.Event -> describeEvent(kv, getString)
            KeyValue.Kind.Modifier -> describeModifier(kv, getString)
            KeyValue.Kind.Editing -> describeEditing(kv, getString)
            KeyValue.Kind.Slider -> describeSlider(kv, getString)
            KeyValue.Kind.Compose_pending -> getString(R.string.key_descr_compose)
            // String/Macro/Timestamp: the payload symbol IS meaningful user text
            // (a snippet/emoticon/timestamp preview). Non-font, so echo it.
            KeyValue.Kind.String,
            KeyValue.Kind.Macro,
            KeyValue.Kind.Timestamp -> kv.getString().ifEmpty { null }
            else -> null
        }

    private fun describeChar(kv: KeyValue, getString: (Int) -> String): String? {
        // Compare on codepoint — several of these are visually-identical spaces
        // (U+0020 / U+00A0 / U+202F) that a char-literal `when` can't disambiguate.
        return when (kv.getChar().code) {
            0x0020 -> getString(R.string.key_descr_space)
            0x00A0 -> getString(R.string.key_descr_nbsp)          // non-breaking space
            0x202F -> getString(R.string.key_descr_nnbsp)         // narrow non-breaking space
            0x0009 -> getString(R.string.key_descr_tab)           // '\t'
            0x000A -> getString(R.string.key_descr_enter)         // '\n'
            0x200D -> getString(R.string.key_descr_zwj)           // zero-width joiner
            0x200C -> getString(R.string.key_descr_zwnj)          // zero-width non-joiner
            else -> {
                val c = kv.getChar()
                // Combining diacritics carry FLAG_KEY_FONT and a PUA symbol but a
                // real combining char value — describe generically, don't echo.
                if (isCombining(c) || kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)) {
                    getString(R.string.key_descr_combining)
                } else {
                    // Plain visible character — announce it as-is (letters read
                    // as "A"/"a", digits/punct read naturally by the TTS engine).
                    c.toString()
                }
            }
        }
    }

    private fun describeKeyevent(kv: KeyValue, getString: (Int) -> String): String? =
        when (kv.getKeyevent()) {
            KeyEvent.KEYCODE_DEL -> getString(R.string.key_descr_backspace)
            KeyEvent.KEYCODE_FORWARD_DEL -> getString(R.string.key_descr_delete)
            KeyEvent.KEYCODE_ENTER -> getString(R.string.key_descr_enter)
            KeyEvent.KEYCODE_TAB -> getString(R.string.key_descr_tab)
            KeyEvent.KEYCODE_ESCAPE -> getString(R.string.key_descr_esc)
            KeyEvent.KEYCODE_DPAD_LEFT -> getString(R.string.key_descr_arrow_left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> getString(R.string.key_descr_arrow_right)
            KeyEvent.KEYCODE_DPAD_UP -> getString(R.string.key_descr_arrow_up)
            KeyEvent.KEYCODE_DPAD_DOWN -> getString(R.string.key_descr_arrow_down)
            KeyEvent.KEYCODE_MOVE_HOME -> getString(R.string.key_descr_home)
            KeyEvent.KEYCODE_MOVE_END -> getString(R.string.key_descr_end)
            KeyEvent.KEYCODE_PAGE_UP -> getString(R.string.key_descr_page_up)
            KeyEvent.KEYCODE_PAGE_DOWN -> getString(R.string.key_descr_page_down)
            else -> null   // function keys, media keys, etc. — echo symbol if safe
        }

    private fun describeEvent(kv: KeyValue, getString: (Int) -> String): String? =
        when (kv.getEvent()) {
            KeyValue.Event.SWITCH_TEXT,
            KeyValue.Event.SWITCH_BACK_EMOJI,
            KeyValue.Event.SWITCH_BACK_CLIPBOARD,
            KeyValue.Event.SWITCH_BACK_GIF -> getString(R.string.key_descr_switch_text)
            KeyValue.Event.SWITCH_NUMERIC -> getString(R.string.key_descr_switch_numeric)
            KeyValue.Event.SWITCH_EMOJI -> getString(R.string.key_descr_switch_emoji)
            KeyValue.Event.SWITCH_GIF -> getString(R.string.key_descr_switch_gif)
            KeyValue.Event.SWITCH_CLIPBOARD -> getString(R.string.key_descr_clipboard)
            KeyValue.Event.SWITCH_GREEKMATH -> getString(R.string.key_descr_switch_greekmath)
            KeyValue.Event.SWITCH_FORWARD -> getString(R.string.key_descr_switch_forward)
            KeyValue.Event.SWITCH_BACKWARD -> getString(R.string.key_descr_switch_backward)
            KeyValue.Event.CHANGE_METHOD_PICKER,
            KeyValue.Event.CHANGE_METHOD_AUTO -> getString(R.string.key_descr_change_method)
            KeyValue.Event.CAPS_LOCK -> getString(R.string.key_descr_capslock)
            KeyValue.Event.SWITCH_VOICE_TYPING,
            KeyValue.Event.SWITCH_VOICE_TYPING_CHOOSER -> getString(R.string.key_descr_voice_typing)
            KeyValue.Event.CONFIG -> getString(R.string.key_descr_config)
            KeyValue.Event.ACTION -> getString(R.string.key_descr_action)
        }

    private fun describeModifier(kv: KeyValue, getString: (Int) -> String): String? =
        when (kv.getModifier()) {
            KeyValue.Modifier.SHIFT -> getString(R.string.key_descr_shift)
            KeyValue.Modifier.CTRL -> getString(R.string.key_descr_ctrl)
            KeyValue.Modifier.ALT -> getString(R.string.key_descr_alt)
            KeyValue.Modifier.META -> getString(R.string.key_descr_meta)
            KeyValue.Modifier.FN -> getString(R.string.key_descr_fn)
            KeyValue.Modifier.SUPERSCRIPT -> getString(R.string.key_descr_superscript)
            KeyValue.Modifier.SUBSCRIPT -> getString(R.string.key_descr_subscript)
            KeyValue.Modifier.SELECTION_MODE -> getString(R.string.key_descr_selection_mode)
            // The remaining modifiers are dead-key accents (aigu, grave, tréma,
            // combining marks, …). They all carry FLAG_KEY_FONT PUA glyphs.
            else -> getString(R.string.key_descr_dead_key)
        }

    private fun describeEditing(kv: KeyValue, getString: (Int) -> String): String? =
        when (kv.getEditing()) {
            KeyValue.Editing.COPY -> getString(R.string.key_descr_copy)
            KeyValue.Editing.COPY_PRIVATE -> getString(R.string.key_descr_copy_private)
            KeyValue.Editing.PASTE -> getString(R.string.key_descr_paste)
            KeyValue.Editing.CUT -> getString(R.string.key_descr_cut)
            KeyValue.Editing.SELECT_ALL -> getString(R.string.key_descr_selectAll)
            KeyValue.Editing.PASTE_PLAIN -> getString(R.string.key_descr_pasteAsPlainText)
            KeyValue.Editing.UNDO -> getString(R.string.key_descr_undo)
            KeyValue.Editing.REDO -> getString(R.string.key_descr_redo)
            KeyValue.Editing.DELETE_WORD,
            KeyValue.Editing.DELETE_LAST_WORD -> getString(R.string.key_descr_delete_word)
            KeyValue.Editing.FORWARD_DELETE_WORD -> getString(R.string.key_descr_forward_delete_word)
            KeyValue.Editing.AUTOFILL -> getString(R.string.key_descr_autofill)
            KeyValue.Editing.CURSOR_DOC_START -> getString(R.string.key_descr_doc_home)
            KeyValue.Editing.CURSOR_DOC_END -> getString(R.string.key_descr_doc_end)
            KeyValue.Editing.CLEAR -> getString(R.string.key_descr_clear)
            KeyValue.Editing.SELECTION_CANCEL -> getString(R.string.key_descr_selection_cancel)
            // REPLACE/SHARE/ASSIST are context-menu actions with real text labels
            // supplied by the framework; echo their (non-font) symbol.
            KeyValue.Editing.REPLACE,
            KeyValue.Editing.SHARE,
            KeyValue.Editing.ASSIST -> kv.getString().ifEmpty { null }
        }

    private fun describeSlider(kv: KeyValue, getString: (Int) -> String): String? =
        when (kv.getSlider()) {
            KeyValue.Slider.Cursor_left -> getString(R.string.key_descr_cursor_left)
            KeyValue.Slider.Cursor_right -> getString(R.string.key_descr_cursor_right)
            KeyValue.Slider.Cursor_up -> getString(R.string.key_descr_cursor_up)
            KeyValue.Slider.Cursor_down -> getString(R.string.key_descr_cursor_down)
            KeyValue.Slider.Selection_cursor_left -> getString(R.string.key_descr_selection_cursor_left)
            KeyValue.Slider.Selection_cursor_right -> getString(R.string.key_descr_selection_cursor_right)
        }

    /**
     * Last-resort label when nothing else matched and the symbol is unsafe to
     * echo (font/PUA). Uses the Kind + value name so the reader says something
     * meaningful ("Shift", "Key 131") instead of a garbage glyph.
     */
    private fun kindFallback(kv: KeyValue): String = when (kv.getKind()) {
        KeyValue.Kind.Modifier -> kv.getModifier().name.lowercase().replaceFirstChar { it.uppercase() }
        KeyValue.Kind.Event -> kv.getEvent().name.lowercase().replace('_', ' ')
        KeyValue.Kind.Editing -> kv.getEditing().name.lowercase().replace('_', ' ')
        KeyValue.Kind.Keyevent -> "Key ${kv.getKeyevent()}"
        KeyValue.Kind.Placeholder -> kv.getPlaceholder().name.lowercase().replace('_', ' ')
        else -> kv.getKind().name
    }

    /** Unicode private-use-area codepoint (BMP range used by special_font.ttf). */
    private fun isPrivateUse(s: String): Boolean =
        s.isNotEmpty() && s.all { it.code in 0xE000..0xF8FF }

    /** True for combining diacritical marks (they attach to the previous glyph). */
    private fun isCombining(c: Char): Boolean =
        c.code in 0x0300..0x036F ||      // Combining Diacritical Marks
            c.code in 0x0483..0x0489 ||  // Cyrillic combining
            c.code in 0x064B..0x0655 ||  // Arabic diacritics (harakat)
            c.code == 0x0670 || c.code == 0x0656 ||
            c.code in 0x20D0..0x20FF     // Combining Diacritical Marks for Symbols
}
