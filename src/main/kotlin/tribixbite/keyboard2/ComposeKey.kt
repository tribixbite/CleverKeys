package tribixbite.keyboard2

import tribixbite.keyboard2.data.KeyValue
import java.util.*

/**
 * Compose key processing system for CleverKeys.
 * Handles dead keys and multi-character input sequences using a finite state machine.
 *
 * Features:
 * - Unicode compose sequence support
 * - Dead key processing
 * - Multi-character sequence matching
 * - State machine-based implementation
 * - Efficient binary search for transitions
 */
object ComposeKey {

    /**
     * Apply pending compose sequence to a KeyValue.
     * @param state Current compose state
     * @param keyValue Key value to process
     * @return Result KeyValue or null if no sequence matched
     */
    @JvmStatic
    fun apply(state: Int, keyValue: KeyValue): KeyValue? {
        return when (keyValue) {
            is KeyValue.CharKey -> apply(state, keyValue.char)
            is KeyValue.StringKey -> apply(state, keyValue.string)
            else -> null
        }
    }

    /**
     * Apply pending compose sequence to a character.
     * @param previousState Previous compose state
     * @param char Character to process
     * @return Result KeyValue or null if no sequence matched
     */
    @JvmStatic
    fun apply(previousState: Int, char: Char): KeyValue? {
        try {
            val states = ComposeKeyData.states
            val edges = ComposeKeyData.edges

            // Validate state bounds
            if (previousState < 0 || previousState >= states.size) {
                return null
            }

            val previousLength = edges[previousState]

            // Validate length bounds
            if (previousState + previousLength > states.size) {
                return null
            }

            // Binary search for transition character
            val searchResult = Arrays.binarySearch(
                states,
                previousState + 1,
                previousState + previousLength,
                char
            )

            if (searchResult < 0) {
                return null
            }

            val nextState = edges[searchResult]

            // Validate next state bounds
            if (nextState < 0 || nextState >= states.size) {
                return null
            }

            val nextHeader = states[nextState].code

            return when {
                nextHeader == 0 -> {
                    // Enter new intermediate state
                    KeyValue.makeComposePending(char.toString(), nextState, 0)
                }

                nextHeader == 0xFFFF -> {
                    // String final state
                    val nextLength = edges[nextState]
                    if (nextState + nextLength > states.size || nextLength < 2) {
                        return null
                    }

                    val resultString = String(
                        states,
                        nextState + 1,
                        nextLength - 1
                    )

                    KeyValue.getKeyByName(resultString)
                }

                nextHeader > 0 -> {
                    // Character final state
                    KeyValue.CharKey(nextHeader.toChar(), nextHeader.toChar().toString(), emptySet())
                }

                else -> null
            }
        } catch (e: Exception) {
            // Handle any bounds or processing errors gracefully
            return null
        }
    }

    /**
     * Apply each character of a string to a compose sequence.
     * @param previousState Previous compose state
     * @param string String to process character by character
     * @return Result KeyValue or null if no sequence matched
     */
    @JvmStatic
    fun apply(previousState: Int, string: String): KeyValue? {
        if (string.isEmpty()) return null

        var currentState = previousState

        for (i in string.indices) {
            val result = apply(currentState, string[i])
                ?: return null // No match found

            // If this is the last character, return the result
            if (i == string.length - 1) {
                return result
            }

            // For intermediate characters, must be compose pending
            if (result !is KeyValue.ComposePendingKey) {
                return null // Found final state before end of string
            }

            currentState = result.pendingCompose
        }

        return null // Should not reach here
    }

    /**
     * Check if a state is a valid compose state.
     * @param state State to validate
     * @return true if state is valid
     */
    @JvmStatic
    fun isValidState(state: Int): Boolean {
        return state >= 0 && state < ComposeKeyData.states.size
    }

    /**
     * Get all possible transitions from a given state.
     * @param state Current state
     * @return List of characters that have valid transitions
     */
    @JvmStatic
    fun getAvailableTransitions(state: Int): List<Char> {
        if (!isValidState(state)) return emptyList()

        val states = ComposeKeyData.states
        val edges = ComposeKeyData.edges
        val stateLength = edges[state]

        if (states[state].code != 0) {
            // Final state - no transitions
            return emptyList()
        }

        if (state + stateLength > states.size) {
            return emptyList()
        }

        return states.sliceArray(state + 1 until state + stateLength).toList()
    }

    /**
     * Check if a state is a final state.
     * @param state State to check
     * @return true if state is final (produces output)
     */
    @JvmStatic
    fun isFinalState(state: Int): Boolean {
        if (!isValidState(state)) return false
        return ComposeKeyData.states[state].code != 0
    }

    /**
     * Get the result of a final state.
     * @param state Final state
     * @return Result KeyValue or null if state is not final
     */
    @JvmStatic
    fun getFinalStateResult(state: Int): KeyValue? {
        if (!isFinalState(state)) return null

        val states = ComposeKeyData.states
        val edges = ComposeKeyData.edges
        val header = states[state].code

        return when {
            header == 0xFFFF -> {
                // String final state
                val length = edges[state]
                if (state + length > states.size || length < 2) {
                    null
                } else {
                    val resultString = String(states, state + 1, length - 1)
                    KeyValue.getKeyByName(resultString)
                }
            }

            header > 0 -> {
                // Character final state
                KeyValue.CharKey(header.toChar(), header.toChar().toString(), emptySet())
            }

            else -> null
        }
    }

    /**
     * Reset compose state to initial state.
     * @return Initial compose state (typically 0)
     */
    @JvmStatic
    fun getInitialState(): Int = 0

    /**
     * Get compose sequence statistics.
     * @return Statistics about the compose data
     */
    @JvmStatic
    fun getStatistics(): ComposeKeyData.ComposeDataStatistics {
        return ComposeKeyData.getDataStatistics()
    }

    /**
     * Validate the compose key data integrity.
     * @return true if data is valid
     */
    @JvmStatic
    fun validateData(): Boolean {
        return ComposeKeyData.validateData()
    }

    /**
     * Legacy compose system for backward compatibility.
     * Provides simple dead key and accent functionality.
     */
    class LegacyComposeSystem {

        companion object {
            private const val TAG = "ComposeKey"
            private val composeSequences = mutableMapOf<String, String>()

            init {
                loadComposeSequences()
            }

            /**
             * Load compose sequences from data
             */
            private fun loadComposeSequences() {
                // Common compose sequences for legacy support
                val sequences = mapOf(
                    "a'" to "á", "a`" to "à", "a^" to "â", "a~" to "ã", "a\"" to "ä", "a*" to "å",
                    "e'" to "é", "e`" to "è", "e^" to "ê", "e\"" to "ë",
                    "i'" to "í", "i`" to "ì", "i^" to "î", "i\"" to "ï",
                    "o'" to "ó", "o`" to "ò", "o^" to "ô", "o~" to "õ", "o\"" to "ö",
                    "u'" to "ú", "u`" to "ù", "u^" to "û", "u\"" to "ü",
                    "n~" to "ñ", "c," to "ç", "ss" to "ß", "ae" to "æ", "oe" to "œ",
                    "th" to "þ", "dh" to "ð", "/o" to "ø", "/O" to "Ø"
                )

                composeSequences.putAll(sequences)
                android.util.Log.d(TAG, "Loaded ${composeSequences.size} compose sequences")
            }

            /**
             * Process compose sequence
             */
            fun processCompose(sequence: String): String? {
                return composeSequences[sequence.lowercase()]
            }

            /**
             * Check if character starts a compose sequence
             */
            fun isComposeStarter(char: Char): Boolean {
                return composeSequences.keys.any { it.startsWith(char.toString(), ignoreCase = true) }
            }

            /**
             * Get all possible completions for partial sequence
             */
            fun getCompletions(partial: String): List<String> {
                return composeSequences.filterKeys {
                    it.startsWith(partial, ignoreCase = true) && it.length > partial.length
                }.values.toList()
            }
        }

        /**
         * Compose state for tracking multi-key sequences
         */
        data class ComposeState(
            val sequence: String = "",
            val isActive: Boolean = false
        ) {
            /**
             * Add character to compose sequence
             */
            fun addChar(char: Char): ComposeState {
                val newSequence = sequence + char
                val result = processCompose(newSequence)

                return if (result != null) {
                    // Complete sequence found
                    ComposeState("", false)
                } else {
                    // Continue building sequence
                    ComposeState(newSequence, true)
                }
            }

            /**
             * Get result if sequence is complete
             */
            fun getResult(): String? {
                return processCompose(sequence)
            }

            /**
             * Cancel compose
             */
            fun cancel(): ComposeState {
                return ComposeState("", false)
            }
        }
    }
}