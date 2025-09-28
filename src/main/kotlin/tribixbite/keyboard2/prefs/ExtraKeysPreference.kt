package tribixbite.keyboard2.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.preference.CheckBoxPreference
import android.preference.PreferenceCategory
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import tribixbite.keyboard2.*
import tribixbite.keyboard2.KeyValue
import tribixbite.keyboard2.KeyboardData
import tribixbite.keyboard2.Theme

/**
 * Extra keys preference management for CleverKeys.
 * Handles selection and configuration of additional keys available on the keyboard.
 *
 * Features:
 * - Comprehensive set of extra keys (accents, symbols, functions)
 * - Dynamic preference generation with descriptions
 * - Preferred positioning for extra keys
 * - Integration with neural engine for special functions
 */
class ExtraKeysPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    companion object {
        /** Array of available extra keys */
        @JvmField
        val extraKeys = arrayOf(
            // System keys
            "alt", "meta", "compose", "voice_typing", "switch_clipboard",

            // Accent keys
            "accent_aigu", "accent_grave", "accent_double_aigu", "accent_dot_above",
            "accent_circonflexe", "accent_tilde", "accent_cedille", "accent_trema",
            "accent_ring", "accent_caron", "accent_macron", "accent_ogonek",
            "accent_breve", "accent_slash", "accent_bar", "accent_dot_below",
            "accent_hook_above", "accent_horn", "accent_double_grave",

            // Special symbols
            "€", "ß", "£", "§", "†", "ª", "º",

            // Special characters
            "zwj", "zwnj", "nbsp", "nnbsp",

            // Navigation
            "tab", "esc", "page_up", "page_down", "home", "end",

            // Functions
            "switch_greekmath", "change_method", "capslock",

            // Editing
            "copy", "paste", "cut", "selectAll", "shareText", "pasteAsPlainText",
            "undo", "redo", "delete_word", "forward_delete_word",

            // Formatting
            "superscript", "subscript",

            // Placeholders
            "f11_placeholder", "f12_placeholder", "menu", "scroll_lock",

            // Combining characters (extensive Unicode combining diacriticals)
            "combining_dot_above", "combining_double_aigu", "combining_slash",
            "combining_arrow_right", "combining_breve", "combining_bar",
            "combining_aigu", "combining_caron", "combining_cedille",
            "combining_circonflexe", "combining_grave", "combining_macron",
            "combining_ring", "combining_tilde", "combining_trema",
            "combining_ogonek", "combining_dot_below", "combining_horn",
            "combining_hook_above", "combining_vertical_tilde",
            "combining_inverted_breve", "combining_pokrytie",
            "combining_slavonic_psili", "combining_slavonic_dasia",
            "combining_payerok", "combining_titlo", "combining_vzmet",
            "combining_arabic_v", "combining_arabic_inverted_v",
            "combining_shaddah", "combining_sukun", "combining_fatha",
            "combining_dammah", "combining_kasra", "combining_hamza_above",
            "combining_hamza_below", "combining_alef_above",
            "combining_fathatan", "combining_kasratan", "combining_dammatan",
            "combining_alef_below", "combining_kavyka", "combining_palatalization"
        )

        /**
         * Determine if an extra key is enabled by default.
         */
        @JvmStatic
        fun defaultChecked(name: String): Boolean {
            return when (name) {
                "voice_typing", "change_method", "switch_clipboard", "compose",
                "tab", "esc", "f11_placeholder", "f12_placeholder" -> true
                else -> false
            }
        }

        /**
         * Get description text for a key. May return null if no description available.
         */
        @JvmStatic
        fun keyDescription(resources: Resources, name: String): String? {
            var description = when (name) {
                "capslock" -> "Caps Lock"
                "change_method" -> "Change Input Method"
                "compose" -> "Compose"
                "copy" -> "Copy"
                "cut" -> "Cut"
                "end" -> "End"
                "home" -> "Home"
                "page_down" -> "Page Down"
                "page_up" -> "Page Up"
                "paste" -> "Paste"
                "pasteAsPlainText" -> "Paste as Plain Text"
                "redo" -> "Redo"
                "delete_word" -> "Delete Word"
                "forward_delete_word" -> "Forward Delete Word"
                "selectAll" -> "Select All"
                "subscript" -> "Subscript"
                "superscript" -> "Superscript"
                "switch_greekmath" -> "Greek Math"
                "undo" -> "Undo"
                "voice_typing" -> "Voice Typing"
                "ª" -> "Feminine Ordinal"
                "º" -> "Masculine Ordinal"
                "switch_clipboard" -> "Clipboard"
                "zwj" -> "Zero Width Joiner"
                "zwnj" -> "Zero Width Non-Joiner"
                "nbsp" -> "Non-Breaking Space"
                "nnbsp" -> "Narrow Non-Breaking Space"
                else -> when {
                    name.startsWith("accent_") -> "Dead Key"
                    name.startsWith("combining_") -> "Combining Character"
                    else -> null
                }
            }

            // Add additional info for certain keys
            val additionalInfo = when (name) {
                "end" -> formatKeyCombination(arrayOf("fn", "right"))
                "home" -> formatKeyCombination(arrayOf("fn", "left"))
                "page_down" -> formatKeyCombination(arrayOf("fn", "down"))
                "page_up" -> formatKeyCombination(arrayOf("fn", "up"))
                "pasteAsPlainText" -> formatKeyCombination(arrayOf("fn", "paste"))
                "redo" -> formatKeyCombination(arrayOf("fn", "undo"))
                "delete_word" -> formatKeyCombinationGesture(resources, "backspace")
                "forward_delete_word" -> formatKeyCombinationGesture(resources, "forward_delete")
                else -> null
            }

            if (additionalInfo != null) {
                description = if (description != null) {
                    "$description  —  $additionalInfo"
                } else {
                    additionalInfo
                }
            }

            return description
        }

        /**
         * Get display title for a key.
         */
        @JvmStatic
        fun keyTitle(keyName: String, keyValue: KeyValue): String {
            return when (keyName) {
                "f11_placeholder" -> "F11"
                "f12_placeholder" -> "F12"
                else -> keyValue.displayString
            }
        }

        /**
         * Format a key combination display.
         */
        @JvmStatic
        fun formatKeyCombination(keys: Array<String>): String {
            return keys.joinToString(" + ") { keyName ->
                KeyValue.getKeyByName(keyName).displayString
            }
        }

        /**
         * Format a gesture-based key combination.
         */
        @JvmStatic
        fun formatKeyCombinationGesture(resources: Resources, keyName: String): String {
            return "Gesture + ${KeyValue.getKeyByName(keyName).displayString}"
        }

        /**
         * Create preferred position for extra key placement.
         */
        @JvmStatic
        fun createPreferredPos(
            nextToKey: String?,
            row: Int,
            col: Int,
            preferBottomRight: Boolean
        ): KeyboardData.PreferredPos {
            val nextTo = nextToKey?.let { KeyValue.getKeyByName(it) }
            val d1: Int
            val d2: Int

            if (preferBottomRight) {
                d1 = 4
                d2 = 3
            } else {
                d1 = 3
                d2 = 4
            }

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

        /**
         * Get preferred position for a specific key.
         */
        @JvmStatic
        fun keyPreferredPos(keyName: String): KeyboardData.PreferredPos {
            return when (keyName) {
                "cut" -> createPreferredPos("x", 2, 2, true)
                "copy" -> createPreferredPos("c", 2, 3, true)
                "paste" -> createPreferredPos("v", 2, 4, true)
                "undo" -> createPreferredPos("z", 2, 1, true)
                "selectAll" -> createPreferredPos("a", 1, 0, true)
                "redo" -> createPreferredPos("y", 0, 5, true)
                "f11_placeholder" -> createPreferredPos("9", 0, 8, false)
                "f12_placeholder" -> createPreferredPos("0", 0, 9, false)
                "delete_word" -> createPreferredPos("backspace", -1, -1, false)
                "forward_delete_word" -> createPreferredPos("backspace", -1, -1, true)
                else -> KeyboardData.PreferredPos.DEFAULT
            }
        }

        /**
         * Get the set of enabled extra keys from preferences.
         */
        @JvmStatic
        fun getExtraKeys(prefs: SharedPreferences): Map<KeyValue, KeyboardData.PreferredPos> {
            val keys = mutableMapOf<KeyValue, KeyboardData.PreferredPos>()

            for (keyName in extraKeys) {
                val prefKey = prefKeyOfKeyName(keyName)
                val isEnabled = prefs.getBoolean(prefKey, defaultChecked(keyName))

                if (isEnabled) {
                    val keyValue = KeyValue.getKeyByName(keyName)
                    val preferredPos = keyPreferredPos(keyName)
                    keys[keyValue] = preferredPos
                }
            }

            return keys
        }

        /**
         * Convert key name to preference key.
         */
        @JvmStatic
        fun prefKeyOfKeyName(keyName: String): String {
            return "extra_key_$keyName"
        }
    }

    /** Whether the preference has been attached to activity */
    private var isAttached = false

    init {
        // Order preferences as added
    }

    override fun onAttachedToActivity() {
        if (isAttached) return
        isAttached = true

        // Add checkbox preference for each extra key
        for (keyName in extraKeys) {
            val checkboxPref = ExtraKeyCheckBoxPreference(
                context,
                keyName,
                defaultChecked(keyName)
            )
            addPreference(checkboxPref)
        }
    }

    /**
     * Custom checkbox preference for extra keys.
     */
    private class ExtraKeyCheckBoxPreference(
        context: Context,
        keyName: String,
        defaultChecked: Boolean
    ) : CheckBoxPreference(context) {

        init {
            val keyValue = KeyValue.getKeyByName(keyName)
            val title = keyTitle(keyName, keyValue)
            val description = keyDescription(context.resources, keyName)

            val displayTitle = if (description != null) {
                "$title ($description)"
            } else {
                title
            }

            key = prefKeyOfKeyName(keyName)
            setDefaultValue(defaultChecked)
            setTitle(displayTitle)

            // Enable multi-line titles on API 26+
            if (Build.VERSION.SDK_INT >= 26) {
                isSingleLineTitle = false
            }
        }

        override fun onBindView(view: View) {
            super.onBindView(view)

            // Apply keyboard font to title
            val titleView = view.findViewById<TextView>(android.R.id.title)
            titleView.typeface = Theme.getKeyFont(context)
        }
    }
}