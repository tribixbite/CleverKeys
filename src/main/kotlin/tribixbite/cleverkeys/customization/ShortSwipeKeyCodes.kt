package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.a11y.KeyLabels

/**
 * The key code a custom short-swipe mapping is stored under, and the human name shown for it.
 *
 * At runtime a mapping is looked up by the string of the key's main value
 * (`key.keys[0].getString()`, lowercased; see `Pointers`). For a letter that is the letter; for a
 * key drawn with the key font it is the font's private-use glyph — Enter is U+E00E, Space U+E00D,
 * Tab U+E00F. Settings → Short Swipe Customization captures the key the user taps through a text
 * field instead, so it saw the TEXT those keys type: `"\n"`, `" "`, `"\t"`. Mappings stored under
 * those codes never fired and were never drawn, and the dialog title printed the raw newline
 * (maintainer report 2026-10-30, screenshots 9/10: `Customize "` + newline + `" Key`).
 *
 * [canonical] maps every code to the runtime one; the manager applies it on save, on load and on
 * every lookup, so an existing `"\n"` mapping starts working on Enter. [displayName] names a key
 * for titles and toasts without ever printing a control or private-use character.
 */
object ShortSwipeKeyCodes {

    /**
     * Typed text → the catalogue name of the key that types it. Only control text whose key is
     * drawn with a glyph needs this; a printable character is its own code.
     */
    private val TYPED_TEXT_KEYS: Map<String, String> = mapOf(
        "\n" to "enter",
        "\r" to "enter",
        " " to "space",
        "\t" to "tab",
    )

    /** The code [keyCode] is stored and looked up under: lowercase, typed text mapped to its key. */
    @JvmStatic
    fun canonical(keyCode: String): String {
        val keyName = TYPED_TEXT_KEYS[keyCode] ?: return keyCode.lowercase()
        return KeyValue.getKeyByName(keyName).getString().lowercase()
    }

    /** The code of a layout key whose main value is [main], or null when it has none. */
    @JvmStatic
    fun ofKey(main: KeyValue?): String? = main?.getString()?.let(::canonical)?.takeIf { it.isNotEmpty() }

    /**
     * Glyph code → the special key drawn with it, for every special key the command catalogue
     * names (Enter, Space, Tab, Shift, Backspace, the switch keys, voice keys …). Built once.
     */
    private val glyphKeys: Map<String, KeyValue> by lazy {
        val out = HashMap<String, KeyValue>()
        for (name in TYPED_TEXT_KEYS.values + CommandRegistry.ALL_COMMANDS.map { it.name }) {
            val kv = KeyValue.getSpecialKeyByName(name) ?: continue
            if (!kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)) continue
            out.putIfAbsent(kv.getString().lowercase(), kv)
        }
        out
    }

    /**
     * The key a stored [keyCode] stands for, as well as can be told from the code alone: the
     * special key drawn with that glyph, else a character key for a single character, else a
     * string key.
     */
    @JvmStatic
    fun keyValueOf(keyCode: String): KeyValue {
        val code = canonical(keyCode)
        glyphKeys[code]?.let { return it }
        return if (code.length == 1) KeyValue.makeCharKey(code[0]) else KeyValue.makeStringKey(code)
    }

    /**
     * Human name of a key for titles and toasts: "A" for a letter, the localized name for a
     * special key ("Enter", "Space", "Voice input"). [main] is the key's main value when the
     * layout key is known (preferred), else [keyCode] is resolved through [keyValueOf].
     * Never contains a control or private-use character.
     */
    @JvmStatic
    fun displayName(keyCode: String, main: KeyValue?, getString: (Int) -> String): String {
        val kv = main ?: keyValueOf(keyCode)
        val name = KeyLabels.describe(kv, getString)
        val printable = name.filterNot { Character.isISOControl(it) || it.code in 0xE000..0xF8FF }
        // A single letter reads as the key cap ("A"); names and symbols keep their case.
        return if (printable.length == 1) printable.uppercase() else printable.ifEmpty { "U+%04X".format(kv.getString().firstOrNull()?.code ?: 0) }
    }
}
