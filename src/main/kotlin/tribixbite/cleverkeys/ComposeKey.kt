package tribixbite.cleverkeys

import java.util.Arrays

object ComposeKey {
    /**
     * Apply the pending compose sequence to [kv]. Returns [null] if no sequence
     * matched.
     */
    @JvmStatic
    fun apply(state: Int, kv: KeyValue): KeyValue? {
        return when (kv.getKind()) {
            KeyValue.Kind.Char -> apply(state, kv.getChar())
            KeyValue.Kind.String -> apply(state, kv.getString())
            else -> null
        }
    }

    /**
     * Apply the pending compose sequence to char [c]. Returns [null] if no
     * sequence matched.
     */
    @JvmStatic
    fun apply(prev: Int, c: Char): KeyValue? {
        val states = ComposeKeyData.states
        val edges = ComposeKeyData.edges
        val prevLength = edges[prev].toInt()
        var next = Arrays.binarySearch(states, prev + 1, prev + prevLength, c)
        if (next < 0) {
            return null
        }
        next = edges[next].toInt()
        val nextHeader = states[next].code

        return when {
            nextHeader == 0 -> // Enter a new intermediate state
                KeyValue.makeComposePending(c.toString(), next, 0)
            nextHeader == 0xFFFF -> { // String final state
                val nextLength = edges[next].toInt()
                KeyValue.getKeyByName(String(states, next + 1, nextLength - 1))
            }
            else -> // Character final state
                KeyValue.makeCharKey(nextHeader.toChar())
        }
    }

    /**
     * Apply each char of a string to a sequence. Returns [null] if no sequence
     * matched.
     */
    @JvmStatic
    fun apply(prevState: Int, s: String): KeyValue? {
        val len = s.length
        if (len == 0) return null

        var prev = prevState
        for (i in 0 until len) {
            val k = apply(prev, s[i]) ?: return null
            if (i >= len - 1) return k
            if (k.getKind() != KeyValue.Kind.Compose_pending) {
                return null // Found a final state before the end of [s]
            }
            prev = k.getPendingCompose()
        }
        return null
    }

    /**
     * The state machine is comprised of two arrays.
     *
     * The [states] array represents the different states and the associated
     * transitions:
     * - The first cell is the header cell, [states[s]].
     * - If the header is equal to [0],
     *   The remaining cells are the transitions characters, sorted
     *   alphabetically.
     * - If the header is positive,
     *   This is a final state, [states[s]] is the result of the sequence.
     *   In this case, [edges[s]] must be equal to [1].
     * - If the header is equal to [-1],
     *   This is a final state, the remaining cells represent the result string
     *   which starts at index [s + 1] and has a length of [edges[s] - 1].
     *
     * The [edges] array represents the transition state corresponding to each
     * accepted inputs.
     * - If [states[s]] is a header cell, [edges[s]] is the number of cells
     *   occupied by the state [s], including the header cell.
     * - If [states[s]] is a transition, [edges[s]] is the index of the state to
     *   jump into.
     * - If [states[s]] is a part of a final state, [edges[s]] is not used.
     */
}
