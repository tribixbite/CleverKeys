package tribixbite.keyboard2

/**
 * Modmap for custom key remapping matching Java KeyModifier.Modmap
 * Provides shift, fn, and ctrl key mappings
 */
class Modmap(
    val shift: Map<KeyValue, KeyValue>? = null,
    val fn: Map<KeyValue, KeyValue>? = null,
    val ctrl: Map<KeyValue, KeyValue>? = null
) {
    /**
     * Builder for constructing Modmap with fluent API
     */
    class Builder {
        private val shiftMap = mutableMapOf<KeyValue, KeyValue>()
        private val fnMap = mutableMapOf<KeyValue, KeyValue>()
        private val ctrlMap = mutableMapOf<KeyValue, KeyValue>()

        fun addShift(from: KeyValue, to: KeyValue): Builder {
            shiftMap[from] = to
            return this
        }

        fun addFn(from: KeyValue, to: KeyValue): Builder {
            fnMap[from] = to
            return this
        }

        fun addCtrl(from: KeyValue, to: KeyValue): Builder {
            ctrlMap[from] = to
            return this
        }

        fun build(): Modmap {
            return Modmap(
                shift = shiftMap.ifEmpty { null },
                fn = fnMap.ifEmpty { null },
                ctrl = ctrlMap.ifEmpty { null }
            )
        }
    }

    companion object {
        fun builder(): Builder = Builder()
        fun empty(): Modmap = Modmap()
    }
}
