package tribixbite.keyboard2

/**
 * Compose key system for accent and special character combinations
 * Kotlin implementation with sealed classes for type safety
 */
class ComposeKey {
    
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
            // Common compose sequences
            val sequences = mapOf(
                "a'" to "á",
                "a`" to "à", 
                "a^" to "â",
                "a~" to "ã",
                "a\"" to "ä",
                "a*" to "å",
                "e'" to "é",
                "e`" to "è",
                "e^" to "ê", 
                "e\"" to "ë",
                "i'" to "í",
                "i`" to "ì",
                "i^" to "î",
                "i\"" to "ï",
                "o'" to "ó",
                "o`" to "ò",
                "o^" to "ô",
                "o~" to "õ",
                "o\"" to "ö",
                "u'" to "ú",
                "u`" to "ù",
                "u^" to "û",
                "u\"" to "ü",
                "n~" to "ñ",
                "c," to "ç",
                "ss" to "ß",
                "ae" to "æ",
                "oe" to "œ",
                "th" to "þ",
                "dh" to "ð",
                "/o" to "ø",
                "/O" to "Ø"
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