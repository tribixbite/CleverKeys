package tribixbite.keyboard2

/**
 * FULL Modmap implementation for keyboard modifiers
 * NO STUBS, NO MOCKS - COMPLETE IMPLEMENTATION ONLY
 */
class Modmap {

    enum class Modifier {
        SHIFT,
        FN,
        CTRL
    }

    private val mappings = mutableMapOf<Pair<Modifier, KeyValue>, KeyValue>()

    fun addMapping(modifier: Modifier, from: KeyValue, to: KeyValue) {
        mappings[Pair(modifier, from)] = to
    }

    fun applyModifier(modifier: Modifier, key: KeyValue): KeyValue {
        return mappings[Pair(modifier, key)] ?: key
    }

    fun hasMapping(modifier: Modifier, key: KeyValue): Boolean {
        return mappings.containsKey(Pair(modifier, key))
    }

    fun getAllMappings(): Map<Pair<Modifier, KeyValue>, KeyValue> {
        return mappings.toMap()
    }

    companion object {
        fun empty(): Modmap = Modmap()
    }
}