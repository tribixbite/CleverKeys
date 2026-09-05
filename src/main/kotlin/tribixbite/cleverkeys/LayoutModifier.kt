package tribixbite.cleverkeys

import android.content.res.Resources
import android.util.LruCache
import android.view.KeyEvent
import java.util.TreeMap
import tribixbite.cleverkeys.prefs.ExtraKeysPreference

object LayoutModifier {
    private lateinit var globalConfig: Config
    private lateinit var bottom_row: KeyboardData.Row
    private lateinit var number_row_no_symbols: KeyboardData.Row
    private lateinit var number_row_symbols: KeyboardData.Row
    private lateinit var num_pad: KeyboardData
    // Lazy so loading the object off-device (mock-tier JVM tests exercising the pure
    // predicates below) does not hit the android.jar LruCache stub constructor.
    private val layoutCache by lazy { LruCache<String, KeyboardData>(10) }

    /**
     * Update the layout according to the configuration.
     * - Remove the switching key if it isn't needed
     * - Remove "localized" keys from other locales (not in 'extra_keys')
     * - Replace the action key to show the right label
     * - Swap the enter and action keys
     * - Add the optional numpad and number row
     * - Add the extra keys
     */
    @JvmStatic
    fun modify_layout(kw: KeyboardData): KeyboardData {
        // Include layout count in cache key so switch keys update when layouts added/removed
        val cacheKey = "${kw.name ?: ""}_${globalConfig.version}_${globalConfig.layouts.size}"
        layoutCache.get(cacheKey)?.let { return it }

        // Extra keys are removed from the set as they are encountered during the
        // first iteration then automatically added.
        val extra_keys = TreeMap<KeyValue, KeyboardData.PreferredPos>()
        val remove_keys = mutableSetOf<KeyValue>()

        // Make sure the config key is accessible to avoid being locked in a custom layout.
        extra_keys[KeyValue.getKeyByName("config")] = KeyboardData.PreferredPos.ANYWHERE
        extra_keys.putAll(globalConfig.extra_keys_param)
        extra_keys.putAll(globalConfig.extra_keys_custom)
        // #169: with one layout the switch keys are no-ops; keep them out of the extra-keys
        // map so the default-ON checkboxes don't re-add what modify_key strips below.
        ExtraKeysPreference.dropLayoutSwitchKeys(extra_keys, globalConfig.layouts.size)

        // Number row and numpads are added after the modification pass to allow
        // removing the number keys from the main layout.
        var added_number_row: KeyboardData.Row? = null
        var added_numpad: KeyboardData? = null

        if (globalConfig.show_numpad) {
            added_numpad = modify_numpad(num_pad, kw)
            remove_keys.addAll(added_numpad.getKeys().keys)
        } else if (globalConfig.add_number_row && !kw.embedded_number_row) {
            // The numpad removes the number row
            added_number_row = modify_number_row(
                if (globalConfig.number_row_symbols) number_row_symbols else number_row_no_symbols,
                kw
            )
            remove_keys.addAll(added_number_row.getKeys(0).keys)
        }

        // Add the bottom row before computing the extra keys
        var newKw = if (kw.bottom_row) kw.insert_row(bottom_row, kw.rows.size) else kw

        // Compose keys to add to the layout
        // 'extra_keys_keyset' reflects changes made to 'extra_keys'
        val extra_keys_keyset = extra_keys.keys
        // 'kw_keys' contains the keys present on the layout without any extra keys
        val kw_keys = kw.getKeys().keys

        val extraKeysSubtype = globalConfig.extra_keys_subtype
        if (extraKeysSubtype != null && kw.locale_extra_keys) {
            val present = mutableSetOf<KeyValue>()
            present.addAll(kw_keys)
            present.addAll(extra_keys_keyset)
            extraKeysSubtype.compute(
                extra_keys,
                ExtraKeys.Query(kw.script, present)
            )
        }

        newKw = newKw.mapKeys(object : KeyboardData.MapKeyValues() {
            override fun apply(key: KeyValue, localized: Boolean): KeyValue? {
                if (localized && !extra_keys.containsKey(key)) return null
                if (remove_keys.contains(key)) return null
                return modify_key(key)
            }
        })

        if (added_numpad != null) {
            newKw = newKw.addNumPad(added_numpad)
        }

        // Add extra keys that are not on the layout (including 'loc' keys)
        extra_keys_keyset.removeAll(newKw.getKeys().keys)
        if (extra_keys.isNotEmpty()) {
            newKw = newKw.addExtraKeys(extra_keys.entries.iterator())
        }

        // Avoid adding extra keys to the number row
        if (added_number_row != null) {
            newKw = newKw.insert_row(added_number_row, 0)
        }

        layoutCache.put(cacheKey, newKw)
        return newKw
    }

    /**
     * KeyValues that HAVE an Extra Keys checkbox — the population [paneLocKeyStripped]
     * may strip on the numpad-family panes. Built once from the same registry the
     * checkboxes render from, so the two can never disagree about which keys are governed.
     */
    private val extraKeysCheckboxKeys: Set<KeyValue> by lazy {
        ExtraKeysPreference.EXTRA_KEYS.mapNotNullTo(HashSet()) { name ->
            try { KeyValue.getKeyByName(name) } catch (_: Exception) { null }
        }
    }

    /**
     * #77: whether a `loc` key on a numpad-family pane (numeric, greekmath, side numpad)
     * is stripped. The numeric pane baked `switch_greekmath` in non-`loc` and
     * [modify_numpad] never honoured `loc` at all, so the Greek/Math toggle was
     * unconditionally present — unremovable from a custom layout (the pane is a shipped
     * layout), untouched by `locale_extra_keys="false"`, and deaf to the Extra Keys
     * checkbox the v1.2.8 release note claimed governed it.
     *
     * Rule: a `loc` pane key is stripped iff it is CHECKBOX-GOVERNED (has an Extra Keys
     * entry) and not present in the enabled extra-keys maps — the same predicate
     * `modify_layout` applies to text layouts and `KeyModifier.applyFnEvent` applies to
     * the Fn rewrite. Ungoverned `loc` pane keys (a hypothetical `loc ctrl`, say — no
     * checkbox exists for ctrl; every `loc` key the shipped panes carry today IS
     * governed) keep the legacy always-shown behaviour, because stripping one would
     * leave the user no way to re-enable it.
     *
     * Deliberately takes the maps as parameters (not [globalConfig]) so the predicate is
     * testable in pure JVM without standing up a Config.
     */
    internal fun paneLocKeyStripped(
        key: KeyValue,
        localized: Boolean,
        extraKeysParam: Map<KeyValue, *>,
        extraKeysCustom: Map<KeyValue, *>,
    ): Boolean =
        localized && extraKeysCheckboxKeys.contains(key) &&
            !extraKeysParam.containsKey(key) && !extraKeysCustom.containsKey(key)

    /**
     * Handle the numpad layout. The [main_kw] is used to adapt the numpad to
     * the main layout's script.
     */
    @JvmStatic
    fun modify_numpad(kw: KeyboardData, main_kw: KeyboardData): KeyboardData {
        val map_digit = KeyModifier.modify_numpad_script(main_kw.numpad_script)
        return kw.mapKeys(object : KeyboardData.MapKeyValues() {
            override fun apply(key: KeyValue, localized: Boolean): KeyValue? {
                // #77: before the kind dispatch — the Char branch returns early and must
                // not shadow the strip for localized char keys.
                if (paneLocKeyStripped(
                        key, localized,
                        globalConfig.extra_keys_param, globalConfig.extra_keys_custom,
                    )
                ) {
                    return null
                }
                when (key.getKind()) {
                    KeyValue.Kind.Char -> {
                        val prev_c = key.getChar()
                        var c = prev_c
                        if (globalConfig.inverse_numpad) {
                            c = inverse_numpad_char(c)
                        }
                        if (map_digit != -1) {
                            val modified = ComposeKey.apply(map_digit, c)
                            if (modified != null) { // Was modified by script
                                return modified
                            }
                        }
                        if (prev_c != c) { // Was inverted
                            return key.withChar(c)
                        }
                        return key // Don't fallback into [modify_key]
                    }
                    else -> {}
                }
                return modify_key(key)
            }
        })
    }

    /**
     * Modify the pin entry layout. [main_kw] is used to map the digits into the
     * same script.
     */
    @JvmStatic
    fun modify_pinentry(kw: KeyboardData, main_kw: KeyboardData): KeyboardData {
        val m = numpad_script_map(main_kw.numpad_script)
        return if (m == null) kw else kw.mapKeys(m)
    }

    /** Modify the number row according to [main_kw]'s script. */
    private fun modify_number_row(row: KeyboardData.Row, main_kw: KeyboardData): KeyboardData.Row {
        val m = numpad_script_map(main_kw.numpad_script)
        return if (m == null) row else row.mapKeys(m)
    }

    private fun numpad_script_map(numpad_script: String?): KeyboardData.MapKeyValues? {
        if (numpad_script == null) return null
        val map_digit = KeyModifier.modify_numpad_script(numpad_script)
        if (map_digit == -1) return null

        return object : KeyboardData.MapKeyValues() {
            override fun apply(key: KeyValue, localized: Boolean): KeyValue? {
                val modified = ComposeKey.apply(map_digit, key)
                return modified ?: key
            }
        }
    }

    /** Modify keys on the main layout and on the numpad according to the config. */
    private fun modify_key(orig: KeyValue): KeyValue? {
        when (orig.getKind()) {
            KeyValue.Kind.Event -> {
                when (orig.getEvent()) {
                    KeyValue.Event.CHANGE_METHOD_PICKER -> {
                        if (globalConfig.switch_input_immediate) {
                            return KeyValue.getKeyByName("change_method_prev")
                        }
                    }
                    KeyValue.Event.ACTION -> {
                        val label = globalConfig.actionLabel  // Local variable for smart cast
                        if (label == null) return null // Remove the action key
                        if (globalConfig.swapEnterActionKey) {
                            return KeyValue.getKeyByName("enter")
                        }
                        return KeyValue.makeActionKey(label)
                    }
                    KeyValue.Event.SWITCH_FORWARD,
                    KeyValue.Event.SWITCH_BACKWARD -> {
                        // Show both switch keys when 2+ layouts, hide when only 1 layout
                        return if (globalConfig.layouts.size > 1) orig else null
                    }
                    KeyValue.Event.SWITCH_VOICE_TYPING,
                    KeyValue.Event.SWITCH_VOICE_TYPING_CHOOSER -> {
                        return if (globalConfig.shouldOfferVoiceTyping) orig else null
                    }
                    else -> {}
                }
            }
            KeyValue.Kind.Keyevent -> {
                when (orig.getKeyevent()) {
                    KeyEvent.KEYCODE_ENTER -> {
                        val label = globalConfig.actionLabel  // Local variable for smart cast
                        if (globalConfig.swapEnterActionKey && label != null) {
                            return KeyValue.makeActionKey(label)
                        }
                    }
                }
            }
            else -> {}
        }
        return orig
    }

    private fun inverse_numpad_char(c: Char): Char {
        return when (c) {
            '7' -> '1'
            '8' -> '2'
            '9' -> '3'
            '1' -> '7'
            '2' -> '8'
            '3' -> '9'
            else -> c
        }
    }

    @JvmStatic
    fun init(globalConfig_: Config, res: Resources) {
        globalConfig = globalConfig_
        try {
            number_row_no_symbols = KeyboardData.load_row(res, R.xml.number_row_no_symbols)
            number_row_symbols = KeyboardData.load_row(res, R.xml.number_row)
            bottom_row = KeyboardData.load_row(res, R.xml.bottom_row)
            num_pad = KeyboardData.load_num_pad(res)
        } catch (e: Exception) {
            throw RuntimeException(e.message) // Not recoverable
        }
    }
}
