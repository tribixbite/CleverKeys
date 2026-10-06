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
            val res = ctx.resources
            val name = keyTitle(res, keyName)
            val title = keyDescription(res, keyName)
                ?.let { res.getString(R.string.extra_key_title_with_description, name, it) }
                ?: name
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
            "clear_clipboard", // #168: opt-in current Android clipboard action
            "copy_private",  // #156: copy selection into CleverKeys' private clipboard (never OS clipboard)
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
                // #169: the bottom row's space-bar switch gestures are now `loc`, i.e.
                // governed by these checkboxes. Default ON so multi-layout installs with no
                // stored pref keep their layout-switch gestures; dropLayoutSwitchKeys still
                // hides them whenever only one layout is enabled, so single-layout defaults
                // are unchanged.
                "switch_forward", "switch_backward" -> true
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
                "clear_clipboard" -> id = R.string.cmd_clear_clipboard_desc
                "copy_private" -> id = R.string.key_descr_copy_private
                "cut" -> id = R.string.key_descr_cut
                "end" -> {
                    id = R.string.key_descr_end
                    additionalInfo = formatKeyCombination(res, arrayOf("fn", "right"))
                }
                "home" -> {
                    id = R.string.key_descr_home
                    additionalInfo = formatKeyCombination(res, arrayOf("fn", "left"))
                }
                "page_down" -> {
                    id = R.string.key_descr_page_down
                    additionalInfo = formatKeyCombination(res, arrayOf("fn", "down"))
                }
                "page_up" -> {
                    id = R.string.key_descr_page_up
                    additionalInfo = formatKeyCombination(res, arrayOf("fn", "up"))
                }
                "paste" -> id = R.string.key_descr_paste
                "pasteAsPlainText" -> {
                    id = R.string.key_descr_pasteAsPlainText
                    additionalInfo = formatKeyCombination(res, arrayOf("fn", "paste"))
                }
                "redo" -> {
                    id = R.string.key_descr_redo
                    additionalInfo = formatKeyCombination(res, arrayOf("fn", "undo"))
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

            val descr = res.getString(id)
            return additionalInfo
                ?.let { res.getString(R.string.extra_key_description_with_shortcut, descr, it) }
                ?: descr
        }

        /**
         * Human-readable, localized title of an extra key for the settings UI. Glyph labels
         * use private-use-area code points that only render with special_font.ttf, so the
         * settings list shows names instead. Key-cap abbreviations that read the same on every
         * keyboard (F11, F12, Esc, Fn) and literal symbol keys stay untranslated.
         */
        @JvmStatic
        fun keyTitle(res: Resources, keyName: String): String {
            KEY_TITLE_RES[keyName]?.let { return res.getString(it) }
            ACCENT_TITLES[keyName]?.let { (glyph, nameRes) ->
                val accentName = res.getString(nameRes)
                return if (glyph == null) accentName
                else res.getString(R.string.extra_key_title_accent, glyph, accentName)
            }
            return when (keyName) {
                "f11_placeholder" -> "F11"
                "f12_placeholder" -> "F12"
                // Key-cap abbreviations, identical on keyboards in every locale.
                "esc" -> "Esc"
                "fn" -> "Fn"
                // Symbols - show the actual symbol
                "€", "ß", "£", "§", "†", "ª", "º" -> keyName
                // Combining diacritics and anything unlisted: derive a readable name from the
                // key identifier (e.g. "combining_sukun" -> "Sukun").
                // TODO(i18n): the combining-diacritic names are transliterations derived from
                // key ids, not string resources.
                else -> keyName.removePrefix("combining_").replace("_", " ")
                    .replaceFirstChar { it.uppercase() }
            }
        }

        /** Extra keys whose settings title is a plain string resource. */
        private val KEY_TITLE_RES: Map<String, Int> = mapOf(
            "alt" to R.string.key_descr_alt,
            "meta" to R.string.key_descr_meta,
            "compose" to R.string.key_descr_compose,
            "voice_typing" to R.string.extra_key_title_voice,
            "switch_clipboard" to R.string.extra_key_title_clipboard,
            "change_method" to R.string.extra_key_title_switch_ime,
            "capslock" to R.string.extra_key_title_caps_lock,
            "tab" to R.string.key_descr_tab,
            "page_up" to R.string.key_descr_page_up,
            "page_down" to R.string.key_descr_page_down,
            "home" to R.string.key_descr_home,
            "end" to R.string.key_descr_end,
            "copy" to R.string.key_descr_copy,
            "paste" to R.string.key_descr_paste,
            "cut" to R.string.key_descr_cut,
            "clear_clipboard" to R.string.cmd_clear_clipboard,
            "copy_private" to R.string.extra_key_title_private_copy,
            "selectAll" to R.string.extra_key_title_select_all,
            "shareText" to R.string.extra_key_title_share,
            "pasteAsPlainText" to R.string.extra_key_title_paste_plain,
            "undo" to R.string.key_descr_undo,
            "redo" to R.string.key_descr_redo,
            "autofill" to R.string.extra_key_title_autofill,
            "delete_word" to R.string.extra_key_title_delete_word,
            "forward_delete_word" to R.string.extra_key_title_forward_delete_word,
            "superscript" to R.string.key_descr_superscript,
            "subscript" to R.string.key_descr_subscript,
            "switch_greekmath" to R.string.extra_key_title_greek_math,
            "switch_forward" to R.string.extra_key_title_next_layout,
            "switch_backward" to R.string.extra_key_title_previous_layout,
            "menu" to R.string.extra_key_title_menu,
            "scroll_lock" to R.string.extra_key_title_scroll_lock,
            "zwj" to R.string.extra_key_title_zwj,
            "zwnj" to R.string.extra_key_title_zwnj,
            "nbsp" to R.string.extra_key_title_nbsp,
            "nnbsp" to R.string.extra_key_title_nnbsp,
            // Keys that only appear inside shortcut descriptions ("Fn + Right arrow").
            "left" to R.string.key_descr_arrow_left,
            "right" to R.string.key_descr_arrow_right,
            "up" to R.string.key_descr_arrow_up,
            "down" to R.string.key_descr_arrow_down,
            "backspace" to R.string.key_descr_backspace,
            "forward_delete" to R.string.key_descr_delete
        )

        /**
         * Dead-key accents: the accent glyph shown before the name (null when the accent has
         * no standalone spacing glyph) and the localized accent name.
         */
        private val ACCENT_TITLES: Map<String, Pair<String?, Int>> = mapOf(
            "accent_aigu" to ("\u00B4" to R.string.extra_key_accent_acute),
            "accent_grave" to ("`" to R.string.extra_key_accent_grave),
            "accent_double_aigu" to ("\u02DD" to R.string.extra_key_accent_double_acute),
            "accent_dot_above" to ("\u02D9" to R.string.extra_key_accent_dot_above),
            "accent_circonflexe" to ("\u02C6" to R.string.extra_key_accent_circumflex),
            "accent_tilde" to ("\u02DC" to R.string.extra_key_accent_tilde),
            "accent_cedille" to ("\u00B8" to R.string.extra_key_accent_cedilla),
            "accent_trema" to ("\u00A8" to R.string.extra_key_accent_umlaut),
            "accent_ring" to ("\u02DA" to R.string.extra_key_accent_ring),
            "accent_caron" to ("\u02C7" to R.string.extra_key_accent_caron),
            "accent_macron" to ("\u00AF" to R.string.extra_key_accent_macron),
            "accent_ogonek" to ("\u02DB" to R.string.extra_key_accent_ogonek),
            "accent_breve" to ("\u02D8" to R.string.extra_key_accent_breve),
            "accent_slash" to ("/" to R.string.extra_key_accent_slash),
            "accent_bar" to ("\u2014" to R.string.extra_key_accent_bar),
            "accent_dot_below" to ("." to R.string.extra_key_accent_dot_below),
            "accent_hook_above" to (null to R.string.extra_key_accent_hook_above),
            "accent_horn" to (null to R.string.extra_key_accent_horn),
            "accent_double_grave" to (null to R.string.extra_key_accent_double_grave)
        )

        /** Format a key combination ("Fn + Right arrow") using localized key names. */
        @JvmStatic
        fun formatKeyCombination(res: Resources, keys: Array<String>): String =
            keys.map { keyTitle(res, it) }
                .reduce { acc, next -> res.getString(R.string.extra_key_combination, acc, next) }

        /** Explain a gesture on a key ("Gesture + Backspace") using localized names. */
        @JvmStatic
        fun formatKeyCombinationGesture(res: Resources, keyName: String): String =
            res.getString(
                R.string.extra_key_combination,
                res.getString(R.string.key_descr_gesture),
                keyTitle(res, keyName)
            )

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

        /**
         * #169: with a single enabled layout, `switch_forward`/`switch_backward` are no-op
         * keys — `LayoutModifier.modify_key` already strips their bottom-row instances in
         * that case, and this removes them from the computed extra-keys map so
         * `addExtraKeys` cannot re-add them just because their checkboxes default to ON.
         * With 2+ layouts the map is left untouched and the checkboxes are authoritative.
         */
        @JvmStatic
        fun <V> dropLayoutSwitchKeys(extraKeys: MutableMap<KeyValue, V>, layoutCount: Int) {
            if (layoutCount > 1) return
            extraKeys.remove(KeyValue.getKeyByName("switch_forward"))
            extraKeys.remove(KeyValue.getKeyByName("switch_backward"))
        }
    }
}
