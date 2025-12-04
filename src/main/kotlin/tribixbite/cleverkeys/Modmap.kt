package tribixbite.cleverkeys

import java.util.TreeMap

/** Stores key combinations that are applied by [KeyModifier]. */
class Modmap {
    enum class M { Shift, Fn, Ctrl }

    private val map: Array<MutableMap<KeyValue, KeyValue>?> = arrayOfNulls(M.values().size)

    fun add(m: M, a: KeyValue, b: KeyValue) {
        val i = m.ordinal
        if (map[i] == null) {
            map[i] = TreeMap()
        }
        map[i]?.put(a, b)
    }

    fun get(m: M, a: KeyValue): KeyValue? {
        val mm = map[m.ordinal]
        return mm?.get(a)
    }
}
