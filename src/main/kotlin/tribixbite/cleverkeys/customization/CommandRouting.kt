package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.KeyValue

/**
 * Which execution path a custom mapping (custom short swipe or popover slot) takes.
 *
 * Most commands run through [CustomShortSwipeExecutor], with the keyboard-level ones (events,
 * editing actions) finished by `Keyboard2View.onCustomShortSwipe`. Some mappings must instead be
 * pressed through the key pipeline (`Pointers.emitSubkeyValue` → `KeyEventHandler`), exactly as
 * a layout's own subkey is, because their effect lives there:
 * - a modifier or dead key (`shift`, `ctrl`, `accent_aigu`, `superscript`) latches onto the
 *   next key typed;
 * - `compose` waits for the next key;
 * - timestamp, macro and slider keys are evaluated by `KeyEventHandler` on key-up;
 * - a TYPED value — a single-character TEXT mapping, or a catalogue command that resolves to a
 *   character or string key (`space`, `nbsp`, niqqud, combining marks) — gets autocapitalisation,
 *   smart punctuation, automatic-space handling, inline search/edit routing and typed-word /
 *   terminal tracking only through `KeyEventHandler`. The executor's raw `commitText` bypassed
 *   all of them (popover/palette audit, 2026-10-08).
 *
 * Multi-character TEXT mappings stay literal macros on the executor: splitting `'s` or a
 * signature into keys would change their semantics (CLAUDE.md "Custom apostrophe routing").
 */
object CommandRouting {

    /** Key kinds that only work when pressed through the key pipeline. */
    private val KEY_PIPELINE_KINDS: Set<KeyValue.Kind> = setOf(
        KeyValue.Kind.Modifier,
        KeyValue.Kind.Compose_pending,
        KeyValue.Kind.Template,
        KeyValue.Kind.Timestamp,
        KeyValue.Kind.Macro,
        KeyValue.Kind.Slider,
        KeyValue.Kind.Hangul_initial,
        KeyValue.Kind.Hangul_medial,
    )

    /**
     * The key to press for [mapping], or null when it runs through the executor. COMMAND mappings
     * (never the `removed` placeholder) and single-character TEXT mappings qualify.
     */
    fun keyPipelineValue(mapping: ShortSwipeMapping): KeyValue? = when {
        mapping.isRemoval -> null
        mapping.actionType == ActionType.COMMAND -> keyPipelineValue(mapping.actionValue)
        mapping.actionType == ActionType.TEXT -> typedTextValue(mapping.actionValue)
        else -> null
    }

    /** The key to press for the command named [commandName], or null (see [keyPipelineValue]). */
    fun keyPipelineValue(commandName: String): KeyValue? {
        // getKeyByName is total: an unknown name comes back as a String key, which is not a
        // pipeline kind, so legacy AvailableCommand names (SCREAMING_SNAKE) fall through to null.
        val kv = KeyValue.getKeyByName(commandName)
        return kv.takeIf { it.getKind() in KEY_PIPELINE_KINDS || isTypedCommandKey(commandName, it) }
    }

    /**
     * The TYPED part of [keyPipelineValue]: a character or string key that `KeyEventHandler.key_up`
     * types directly. `Keyboard2View.onCustomShortSwipe` uses it for callers that reach the view
     * without going through `Pointers`; latching kinds need a pointer and are never returned.
     */
    fun typedValue(mapping: ShortSwipeMapping): KeyValue? =
        keyPipelineValue(mapping)?.takeIf { it.getKind() == KeyValue.Kind.Char || it.getKind() == KeyValue.Kind.String }

    /**
     * [text] as one typed key when it is exactly one code point and not a control character
     * (a newline or tab mapping keeps its literal commit). A BMP character is a Char key, the
     * same value a layout key for it carries; a supplementary character (most emoji) is a
     * String key, which `KeyEventHandler` types the same way.
     */
    internal fun typedTextValue(text: String): KeyValue? {
        if (text.isEmpty() || text.codePointCount(0, text.length) != 1) return null
        val cp = text.codePointAt(0)
        if (Character.isISOControl(cp)) return null
        return if (text.length == 1) KeyValue.makeCharKey(text[0]) else KeyValue.makeStringKey(text)
    }

    /**
     * A catalogue command whose name resolves to a real character or string key. A String key
     * that merely spells the command's own name is getKeyByName's "unknown name" fallback, not a
     * key: those commands are handled by name on the executor (`CommandRoutingTest`).
     */
    private fun isTypedCommandKey(commandName: String, kv: KeyValue): Boolean = when (kv.getKind()) {
        KeyValue.Kind.Char -> true
        KeyValue.Kind.String -> kv.getString() != commandName
        else -> false
    }
}
