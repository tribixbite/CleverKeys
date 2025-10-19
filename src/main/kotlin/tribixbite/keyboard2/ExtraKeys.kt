package tribixbite.keyboard2

/**
 * System for dynamically adding extra keys to keyboard layouts.
 *
 * Supports:
 * - Script-specific key additions (e.g., accents only on Latin layouts)
 * - Alternative key substitution (use alternative if main key unavailable)
 * - Position hints (place key next to another key)
 * - Intelligent merging of multiple extra key sources
 *
 * Example format: "accent_aigu:´@e|f11_placeholder@f10"
 * - accent_aigu is the key, ´ is alternative, place near 'e'
 * - f11_placeholder placed near f10
 */
data class ExtraKeys(
    val keys: List<ExtraKey>
) {
    /**
     * Add extra keys to destination map based on query context.
     * Keys already in dst might affect decisions (see ExtraKey.compute).
     */
    fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, query: Query) {
        keys.forEach { it.compute(dst, query) }
    }

    companion object {
        /** Empty extra keys collection */
        val EMPTY = ExtraKeys(emptyList())

        /**
         * Parse extra keys from string format: "key1:alt1:alt2@next_to|key2@next_to2"
         *
         * Format:
         * - Keys separated by "|"
         * - Alternatives separated by ":"
         * - Position hint after "@"
         *
         * Examples:
         * - "f11_placeholder" - Simple key
         * - "accent_aigu:´@e" - Key with alternative, place near 'e'
         * - "f11@f10|f12@f11" - Multiple keys with position hints
         */
        fun parse(script: String?, str: String): ExtraKeys {
            if (str.isBlank()) return EMPTY

            val keys = str.split("|").mapNotNull { keySpec ->
                if (keySpec.isBlank()) null
                else ExtraKey.parse(keySpec, script)
            }

            return ExtraKeys(keys)
        }

        /**
         * Merge multiple ExtraKeys collections.
         *
         * Behavior:
         * - Identical keys are merged (alternatives concatenated)
         * - Script conflicts generalize to null (any script)
         * - Position hints must match or become null
         */
        fun merge(keysList: List<ExtraKeys>): ExtraKeys {
            val mergedKeys = mutableMapOf<KeyValue, ExtraKey>()

            for (keys in keysList) {
                for (key in keys.keys) {
                    val existing = mergedKeys[key.kv]
                    val merged = if (existing != null) {
                        key.mergeWith(existing)
                    } else {
                        key
                    }
                    mergedKeys[key.kv] = merged
                }
            }

            return ExtraKeys(mergedKeys.values.toList())
        }
    }

    /**
     * Represents a single extra key with placement constraints.
     */
    data class ExtraKey(
        /** The key to add */
        val kv: KeyValue,

        /** Layout script constraint (null = any script) */
        val script: String?,

        /** Alternative keys - prevents addition if all present */
        val alternatives: List<KeyValue>,

        /** Preferred position hint (place next to this key) */
        val nextTo: KeyValue?
    ) {
        /**
         * Add this key to destination if conditions are met.
         *
         * Logic:
         * 1. If only 1 alternative and main key not in dst → use alternative
         * 2. Check script compatibility (null matches any)
         * 3. Check if any alternative is missing from layout
         * 4. If all pass → add with position hint
         */
        fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, query: Query) {
            // Use alternative if it's the only one and main key not already added
            val useAlternative = (alternatives.size == 1 && !dst.containsKey(kv))

            // Check script compatibility and alternative presence
            val scriptMatches = (query.script == null || script == null || query.script == script)
            val alternativesMissing = (alternatives.isEmpty() || !query.present.containsAll(alternatives))

            if (scriptMatches && alternativesMissing) {
                val keyToAdd = if (useAlternative) alternatives[0] else kv
                val pos = if (nextTo != null) {
                    KeyboardData.PreferredPos(nextTo)
                } else {
                    KeyboardData.PreferredPos.DEFAULT
                }
                dst[keyToAdd] = pos
            }
        }

        /**
         * Merge two ExtraKey instances (must have same kv).
         *
         * Rules:
         * - script: null if different, otherwise keep value
         * - alternatives: concatenate both lists
         * - nextTo: null if different, otherwise keep value
         */
        fun mergeWith(other: ExtraKey): ExtraKey {
            require(kv == other.kv) { "Cannot merge ExtraKeys with different KeyValues" }

            val mergedScript = oneOrNone(script, other.script)
            val mergedNextTo = oneOrNone(nextTo, other.nextTo)
            val mergedAlternatives = alternatives + other.alternatives

            return ExtraKey(kv, mergedScript, mergedAlternatives, mergedNextTo)
        }

        /**
         * Return a if b is null, b if a is null, a if equal, null otherwise.
         */
        private fun <E> oneOrNone(a: E?, b: E?): E? {
            return when {
                a == null -> b
                b == null -> a
                a == b -> a
                else -> null
            }
        }

        companion object {
            /**
             * Parse single extra key from string: "key:alt1:alt2@next_to"
             *
             * Format:
             * - Key name first
             * - Alternatives after ":"
             * - Position hint after "@"
             */
            fun parse(str: String, script: String?): ExtraKey? {
                if (str.isBlank()) return null

                // Split on @ for position hint
                val parts = str.split("@", limit = 2)
                val keyPart = parts[0]
                val nextToStr = parts.getOrNull(1)

                // Split key part on : for alternatives
                val keyNames = keyPart.split(":")
                if (keyNames.isEmpty()) return null

                // First is main key, rest are alternatives
                val mainKey = KeyValue.getKeyByName(keyNames[0]) ?: return null
                val alternatives = keyNames.drop(1).mapNotNull { KeyValue.getKeyByName(it) }
                val nextTo = nextToStr?.let { KeyValue.getKeyByName(it) }

                return ExtraKey(mainKey, script, alternatives, nextTo)
            }
        }
    }

    /**
     * Query context for deciding whether to add extra keys.
     */
    data class Query(
        /** Script of current layout (null = any script) */
        val script: String?,

        /** Keys already present on the layout */
        val present: Set<KeyValue>
    )
}
