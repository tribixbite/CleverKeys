package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.KeyValue

/**
 * Which execution path a COMMAND mapping (custom short swipe or popover slot) takes.
 *
 * Most commands run through [CustomShortSwipeExecutor], with the keyboard-level ones (events,
 * editing actions) finished by `Keyboard2View.onCustomShortSwipe`. A few kinds of key cannot run
 * there, because their effect lives in the key pipeline itself:
 * - a modifier or dead key (`shift`, `ctrl`, `accent_aigu`, `superscript`) latches onto the
 *   next key typed;
 * - `compose` waits for the next key;
 * - timestamp, macro and slider keys are evaluated by `KeyEventHandler` on key-up.
 *
 * Before this routing those commands reached `onCustomShortSwipe`, which logged "Unhandled
 * KeyValue kind" and did nothing. They are now emitted exactly as if the key had been pressed,
 * through the same path a layout's own subkey takes (`Pointers.emitSubkeyValue`).
 */
object CommandRouting {

    /** Key kinds that only work when pressed through the key pipeline. */
    private val KEY_PIPELINE_KINDS: Set<KeyValue.Kind> = setOf(
        KeyValue.Kind.Modifier,
        KeyValue.Kind.Compose_pending,
        KeyValue.Kind.Timestamp,
        KeyValue.Kind.Macro,
        KeyValue.Kind.Slider,
        KeyValue.Kind.Hangul_initial,
        KeyValue.Kind.Hangul_medial,
    )

    /**
     * The key to press for [mapping], or null when it runs through the executor. Only COMMAND
     * mappings qualify; the `removed` placeholder never does anything.
     */
    fun keyPipelineValue(mapping: ShortSwipeMapping): KeyValue? {
        if (mapping.actionType != ActionType.COMMAND || mapping.isRemoval) return null
        return keyPipelineValue(mapping.actionValue)
    }

    /** The key to press for the command named [commandName], or null (see [keyPipelineValue]). */
    fun keyPipelineValue(commandName: String): KeyValue? {
        // getKeyByName is total: an unknown name comes back as a String key, which is not a
        // pipeline kind, so legacy AvailableCommand names (SCREAMING_SNAKE) fall through to null.
        val kv = KeyValue.getKeyByName(commandName)
        return kv.takeIf { it.getKind() in KEY_PIPELINE_KINDS }
    }
}
