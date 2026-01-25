package tribixbite.cleverkeys.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import tribixbite.cleverkeys.*

/** This class implements the "extra keys" preference but also defines the
    possible extra keys. Migrated to AndroidX Preference library. */
class ExtraKeysPreference(context: Context, attrs: AttributeSet?) : PreferenceCategory(context, attrs) {
    private var attached = false /** Whether it has already been attached. */

    init {
        isOrderingAsAdded = true
    }

    override fun onAttached() {
        super.onAttached()
        if (attached) return
        attached = true
        for (keyName in EXTRA_KEYS) {
            addPreference(ExtraKeyCheckBoxPreference(context, keyName, defaultChecked(keyName)))
        }
    }

    class ExtraKeyCheckBoxPreference(
        ctx: Context,
        private val keyName: String,
        defaultChecked: Boolean
    ) : CheckBoxPreference(ctx) {
        init {
            val kv = KeyValue.getKeyByName(keyName)
            var title = keyTitle(keyName, kv)
            keyDescription(ctx.resources, keyName)?.let {
                title += " ($it)"
            }
            key = prefKeyOfKeyName(keyName)
            setDefaultValue(defaultChecked)
            setTitle(title)
            if (Build.VERSION.SDK_INT >= 26) {
                isSingleLineTitle = false
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val title = holder.findViewById(android.R.id.title) as? TextView
            title?.typeface = Theme.getKeyFont(context)
        }
    }

    companion object {
        /** Array of the keys that can be selected. */
        @JvmField
        val EXTRA_KEYS = arrayOf(
            "alt",
            "meta",
            "compose",
            "voice_typing",
            "switch_clipboard",
            "accent_aigu",
            "accent_grave",
            "accent_double_aigu",
            "accent_dot_above",
            "accent_circonflexe",
            "accent_tilde",
            "accent_cedille",
            "accent_trema",
            "accent_ring",
            "accent_caron",
            "accent_macron",
            "accent_ogonek",
            "accent_breve",
            "accent_slash",
            "accent_bar",
            "accent_dot_below",
            "accent_hook_above",
            "accent_horn",
            "accent_double_grave",
            "€",
            "ß",
            "£",
            "§",
            "†",
            "ª",
            "º",
            "zwj",
            "zwnj",
            "nbsp",
            "nnbsp",
            "tab",
            "esc",
            "page_up",
            "page_down",
            "home",
            "end",
            "switch_greekmath",
            "switch_forward",
            "switch_backward",
            "change_method",
            "capslock",
            "copy",
            "paste",
            "cut",
            "selectAll",
            "shareText",
            "pasteAsPlainText",
            "undo",
            "redo",
            "autofill",
            "delete_word",
            "forward_delete_word",
            "superscript",
            "subscript",
            "f11_placeholder",
            "f12_placeholder",
            "menu",
            "scroll_lock",
            "combining_dot_above",
            "combining_double_aigu",
            "combining_slash",
            "combining_arrow_right",
            "combining_breve",
            "combining_bar",
            "combining_aigu",
            "combining_caron",
            "combining_cedille",
            "combining_circonflexe",
            "combining_grave",
            "combining_macron",
            "combining_ring",
            "combining_tilde",
            "combining_trema",
            "combining_ogonek",
            "combining_dot_below",
            "combining_horn",
            "combining_hook_above",
            "combining_vertical_tilde",
            "combining_inverted_breve",
            "combining_pokrytie",
            "combining_slavonic_psili",
            "combining_slavonic_dasia",
            "combining_payerok",
            "combining_titlo",
            "combining_vzmet",
            "combining_arabic_v",
            "combining_arabic_inverted_v",
            "combining_shaddah",
            "combining_sukun",
            "combining_fatha",
            "combining_dammah",
            "combining_kasra",
            "combining_hamza_above",
            "combining_hamza_below",
            "combining_alef_above",
            "combining_fathatan",
            "combining_kasratan",
            "combining_dammatan",
            "combining_alef_below",
            "combining_kavyka",
            "combining_palatalization"
        )

        /** Alias for EXTRA_KEYS for compatibility with ExtraKeysConfigActivity */
        @JvmField
        val extraKeys: List<String> = EXTRA_KEYS.toList()

        /** Whether an extra key is enabled by default. */
        @JvmStatic
        fun defaultChecked(name: String): Boolean {
            return when (name) {
                "voice_typing", "change_method", "switch_clipboard", "compose",
                "tab", "esc", "f11_placeholder", "f12_placeholder",
                "cut", "copy", "paste", "undo",
                "home", "end", "page_up", "page_down", "menu" -> true
                else -> false
            }
        }

        /** Text that describe a key. Might be null. */
        @JvmStatic
        fun keyDescription(res: Resources, name: String): String? {
            var id = 0
            var additionalInfo: String? = null

            when (name) {
                "capslock" -> id = R.string.key_descr_capslock
                "change_method" -> id = R.string.key_descr_change_method
                "compose" -> id = R.string.key_descr_compose
                "copy" -> id = R.string.key_descr_copy
                "cut" -> id = R.string.key_descr_cut
                "end" -> {
                    id = R.string.key_descr_end
                    additionalInfo = formatKeyCombination(arrayOf("fn", "right"))
                }
                "home" -> {
                    id = R.string.key_descr_home
                    additionalInfo = formatKeyCombination(arrayOf("fn", "left"))
                }
                "page_down" -> {
                    id = R.string.key_descr_page_down
                    additionalInfo = formatKeyCombination(arrayOf("fn", "down"))
                }
                "page_up" -> {
                    id = R.string.key_descr_page_up
                    additionalInfo = formatKeyCombination(arrayOf("fn", "up"))
                }
                "paste" -> id = R.string.key_descr_paste
                "pasteAsPlainText" -> {
                    id = R.string.key_descr_pasteAsPlainText
                    additionalInfo = formatKeyCombination(arrayOf("fn", "paste"))
                }
                "redo" -> {
                    id = R.string.key_descr_redo
                    additionalInfo = formatKeyCombination(arrayOf("fn", "undo"))
                }
                "delete_word" -> {
                    id = R.string.key_descr_delete_word
                    additionalInfo = formatKeyCombinationGesture(res, "backspace")
                }
                "forward_delete_word" -> {
                    id = R.string.key_descr_forward_delete_word
                    additionalInfo = formatKeyCombinationGesture(res, "forward_delete")
                }
                "selectAll" -> id = R.string.key_descr_selectAll
                "subscript" -> id = R.string.key_descr_subscript
                "superscript" -> id = R.string.key_descr_superscript
                "switch_greekmath" -> id = R.string.key_descr_switch_greekmath
                "switch_forward" -> id = R.string.key_descr_switch_forward
                "switch_backward" -> id = R.string.key_descr_switch_backward
                "undo" -> id = R.string.key_descr_undo
                "voice_typing" -> id = R.string.key_descr_voice_typing
                "ª" -> id = R.string.key_descr_ª
                "º" -> id = R.string.key_descr_º
                "switch_clipboard" -> id = R.string.key_descr_clipboard
                "zwj" -> id = R.string.key_descr_zwj
                "zwnj" -> id = R.string.key_descr_zwnj
                "nbsp" -> id = R.string.key_descr_nbsp
                "nnbsp" -> id = R.string.key_descr_nnbsp
                "accent_aigu", "accent_grave", "accent_double_aigu", "accent_dot_above",
                "accent_circonflexe", "accent_tilde", "accent_cedille", "accent_trema",
                "accent_ring", "accent_caron", "accent_macron", "accent_ogonek",
                "accent_breve", "accent_slash", "accent_bar", "accent_dot_below",
                "accent_hook_above", "accent_horn", "accent_double_grave" ->
                    id = R.string.key_descr_dead_key
                "combining_dot_above", "combining_double_aigu", "combining_slash",
                "combining_arrow_right", "combining_breve", "combining_bar",
                "combining_aigu", "combining_caron", "combining_cedille",
                "combining_circonflexe", "combining_grave", "combining_macron",
                "combining_ring", "combining_tilde", "combining_trema",
                "combining_ogonek", "combining_dot_below", "combining_horn",
                "combining_hook_above", "combining_vertical_tilde", "combining_inverted_breve",
                "combining_pokrytie", "combining_slavonic_psili", "combining_slavonic_dasia",
                "combining_payerok", "combining_titlo", "combining_vzmet",
                "combining_arabic_v", "combining_arabic_inverted_v", "combining_shaddah",
                "combining_sukun", "combining_fatha", "combining_dammah",
                "combining_kasra", "combining_hamza_above", "combining_hamza_below",
                "combining_alef_above", "combining_fathatan", "combining_kasratan",
                "combining_dammatan", "combining_alef_below", "combining_kavyka",
                "combining_palatalization" ->
                    id = R.string.key_descr_combining
            }

            if (id == 0) return additionalInfo

            var descr = res.getString(id)
            if (additionalInfo != null) {
                descr += "  —  $additionalInfo"
            }
            return descr
        }

        @JvmStatic
        fun keyTitle(keyName: String, kv: KeyValue): String {
            // Return human-readable names for settings UI instead of glyph characters
            // Glyphs use private use area Unicode that only renders with special_font.ttf
            return when (keyName) {
                "f11_placeholder" -> "F11"
                "f12_placeholder" -> "F12"
                "alt" -> "Alt"
                "meta" -> "Meta"
                "compose" -> "Compose"
                "voice_typing" -> "Voice"
                "switch_clipboard" -> "Clipboard"
                "change_method" -> "Switch IME"
                "capslock" -> "Caps Lock"
                "tab" -> "Tab"
                "esc" -> "Esc"
                "page_up" -> "Page Up"
                "page_down" -> "Page Down"
                "home" -> "Home"
                "end" -> "End"
                "copy" -> "Copy"
                "paste" -> "Paste"
                "cut" -> "Cut"
                "selectAll" -> "Select All"
                "shareText" -> "Share"
                "pasteAsPlainText" -> "Paste Plain"
                "undo" -> "Undo"
                "redo" -> "Redo"
                "delete_word" -> "Delete Word"
                "forward_delete_word" -> "Fwd Delete Word"
                "superscript" -> "Superscript"
                "subscript" -> "Subscript"
                "switch_greekmath" -> "Greek/Math"
                "switch_forward" -> "Next Layout"
                "switch_backward" -> "Previous Layout"
                "menu" -> "Menu"
                "scroll_lock" -> "Scroll Lock"
                "zwj" -> "ZWJ (Joiner)"
                "zwnj" -> "ZWNJ (Non-Joiner)"
                "nbsp" -> "NBSP (Space)"
                "nnbsp" -> "NNBSP (Narrow Space)"
                // Accents - show the accent name
                "accent_aigu" -> "´ Acute"
                "accent_grave" -> "` Grave"
                "accent_double_aigu" -> "˝ Double Acute"
                "accent_dot_above" -> "˙ Dot Above"
                "accent_circonflexe" -> "ˆ Circumflex"
                "accent_tilde" -> "˜ Tilde"
                "accent_cedille" -> "¸ Cedilla"
                "accent_trema" -> "¨ Umlaut"
                "accent_ring" -> "˚ Ring"
                "accent_caron" -> "ˇ Caron"
                "accent_macron" -> "¯ Macron"
                "accent_ogonek" -> "˛ Ogonek"
                "accent_breve" -> "˘ Breve"
                "accent_slash" -> "/ Slash"
                "accent_bar" -> "— Bar"
                "accent_dot_below" -> ". Dot Below"
                "accent_hook_above" -> "Hook Above"
                "accent_horn" -> "Horn"
                "accent_double_grave" -> "Double Grave"
                // Symbols - show the actual symbol
                "€", "ß", "£", "§", "†", "ª", "º" -> keyName
                // Combining characters - show name
                else -> if (keyName.startsWith("combining_")) {
                    keyName.removePrefix("combining_").replace("_", " ").replaceFirstChar { it.uppercase() }
                } else {
                    // Fallback: use key name as-is (human readable)
                    keyName.replace("_", " ").replaceFirstChar { it.uppercase() }
                }
            }
        }

        /** Format a key combination using readable names */
        @JvmStatic
        fun formatKeyCombination(keys: Array<String>): String {
            return keys.joinToString(" + ") { keyName ->
                keyTitle(keyName, KeyValue.getKeyByName(keyName))
            }
        }

        /** Explain a gesture on a key using readable names */
        @JvmStatic
        fun formatKeyCombinationGesture(res: Resources, keyName: String): String {
            return res.getString(R.string.key_descr_gesture) + " + " +
                    keyTitle(keyName, KeyValue.getKeyByName(keyName))
        }

        /** Place an extra key next to the key specified by the first argument, on
            bottom-right preferably or on the bottom-left. If the specified key is not
            on the layout, place on the specified row and column. */
        @JvmStatic
        fun mkPreferredPos(
            nextToKey: String?,
            row: Int,
            col: Int,
            preferBottomRight: Boolean
        ): KeyboardData.PreferredPos {
            val nextTo = nextToKey?.let { KeyValue.getKeyByName(it) }
            val (d1, d2) = if (preferBottomRight) 4 to 3 else 3 to 4 // Preferred direction and fallback
            return KeyboardData.PreferredPos(
                nextTo,
                arrayOf(
                    KeyboardData.KeyPos(row, col, d1),
                    KeyboardData.KeyPos(row, col, d2),
                    KeyboardData.KeyPos(row, -1, d1),
                    KeyboardData.KeyPos(row, -1, d2),
                    KeyboardData.KeyPos(-1, -1, -1)
                )
            )
        }

        @JvmStatic
        fun keyPreferredPos(keyName: String): KeyboardData.PreferredPos {
            return when (keyName) {
                "cut" -> mkPreferredPos("x", 2, 2, true)
                "copy" -> mkPreferredPos("c", 2, 3, true)
                "paste" -> mkPreferredPos("v", 2, 4, true)
                "undo" -> mkPreferredPos("z", 2, 1, true)
                "selectAll" -> mkPreferredPos("a", 1, 0, true)
                "redo" -> mkPreferredPos("y", 0, 5, true)
                "f11_placeholder" -> mkPreferredPos("9", 0, 8, false)
                "f12_placeholder" -> mkPreferredPos("0", 0, 9, false)
                "menu" -> mkPreferredPos("shift", 2, 0, true)              
                "delete_word" -> mkPreferredPos("backspace", -1, -1, false)
                "forward_delete_word" -> mkPreferredPos("backspace", -1, -1, true)
                // Layout switching keys - place near space bar on bottom row
                "switch_forward" -> mkPreferredPos("space", 3, 3, true)
                "switch_backward" -> mkPreferredPos("space", 3, 2, false)
                else -> KeyboardData.PreferredPos.DEFAULT
            }
        }

        /** Get the set of enabled extra keys. */
        @JvmStatic
        @Deprecated("Use getExtraKeys instead", ReplaceWith("getExtraKeys(prefs)"))
        fun get_extra_keys(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            return getExtraKeys(prefs)
        }

        @JvmStatic
        fun getExtraKeys(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            val ks = mutableMapOf<KeyValue, KeyboardData.PreferredPos>()
            for (keyName in EXTRA_KEYS) {
                if (prefs.getBoolean(prefKeyOfKeyName(keyName), defaultChecked(keyName))) {
                    ks[KeyValue.getKeyByName(keyName)] = keyPreferredPos(keyName)
                }
            }
            return ks
        }

        @JvmStatic
        fun prefKeyOfKeyName(keyName: String): String {
            return "extra_key_$keyName"
        }
    }
}
