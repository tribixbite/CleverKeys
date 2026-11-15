package tribixbite.cleverkeys.prefs

import android.content.SharedPreferences

/**
 * Extra Keys "Reset to defaults" (review 2026-11-14 M25). The screen used to write every key —
 * about 131 of them — with its own `apply()`, so on a fresh install (no key stored yet) each one
 * was a new preference and the keyboard rebuilt its Config once per key; it asked nothing first
 * and could not be undone.
 *
 * Now the screen plans the reset ([plan]: only the keys whose shown state differs from their
 * default), asks with the number of keys that change, writes them in ONE editor ([write]) and
 * offers Undo, which writes [Plan.undo] the same way. SharedPreferences still notifies its
 * listeners once per changed key, so the keyboard refreshes once per key that actually changes —
 * no longer once per key in the catalogue. Pinned by `ExtraKeysResetTest`.
 */
object ExtraKeysReset {

    /**
     * @property reset the keys the reset changes, with their default value.
     * @property undo the same keys with the value they had before the reset.
     */
    data class Plan(val reset: Map<String, Boolean>, val undo: Map<String, Boolean>)

    /**
     * The reset of [keys] given the screen's [current] state (a key missing from it is off, as
     * its checkbox shows it) and each key's default from [defaultOf].
     */
    fun plan(current: Map<String, Boolean>, keys: Iterable<String>, defaultOf: (String) -> Boolean): Plan {
        val reset = LinkedHashMap<String, Boolean>()
        val undo = LinkedHashMap<String, Boolean>()
        for (key in keys) {
            val now = current[key] ?: false
            val default = defaultOf(key)
            if (now != default) {
                reset[key] = default
                undo[key] = now
            }
        }
        return Plan(reset, undo)
    }

    /** Writes [values] (key name → enabled) with one editor and one `apply()`; nothing when empty. */
    fun write(prefs: SharedPreferences, values: Map<String, Boolean>, prefKeyOf: (String) -> String) {
        if (values.isEmpty()) return
        val editor = prefs.edit()
        for ((key, enabled) in values) editor.putBoolean(prefKeyOf(key), enabled)
        editor.apply()
    }
}
