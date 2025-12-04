package tribixbite.cleverkeys

class ExtraKeys internal constructor(private val ks: Collection<ExtraKey>) {

    /**
     * Add the keys that should be added to the keyboard into [dst]. Keys
     * already added to [dst] might have an impact, see [ExtraKey.compute].
     */
    fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, q: Query) {
        for (k in ks) {
            k.compute(dst, q)
        }
    }

    internal class ExtraKey(
        /** The key to add. */
        val kv: KeyValue,
        /** The key will be added to layouts of the same script. If null, might be
         * added to layouts of any script. */
        val script: String?,
        /** The key will not be added to layout that already contain all the
         * alternatives. */
        val alternatives: List<KeyValue>,
        /** The key next to which to add. Might be [null]. */
        val nextTo: KeyValue?
    ) {
        /**
         * Whether the key should be added to the keyboard.
         */
        fun compute(dst: MutableMap<KeyValue, KeyboardData.PreferredPos>, q: Query) {
            // Add the alternative if it's the only one. The list of alternatives is
            // enforced to be complete by the merging step. The same [kv] will not
            // appear again in the list of extra keys with a different list of
            // alternatives.
            // Selecting the dead key in the "Add key to the keyboard" option would
            // disable this behavior for a key.
            val useAlternative = (alternatives.size == 1 && !dst.containsKey(kv))
            if ((q.script == null || script == null || q.script == script) &&
                (alternatives.isEmpty() || !q.present.containsAll(alternatives))) {
                val kvToAdd = if (useAlternative) alternatives[0] else kv
                var pos = KeyboardData.PreferredPos.DEFAULT
                if (nextTo != null) {
                    pos = KeyboardData.PreferredPos(pos)
                    pos.next_to = nextTo
                }
                dst[kvToAdd] = pos
            }
        }

        /**
         * Return a new key from two. [kv] are expected to be equal. [script] is
         * generalized to [null] on any conflict. [alternatives] are concatenated.
         */
        fun mergeWith(k2: ExtraKey): ExtraKey {
            val mergedScript = oneOrNone(script, k2.script)
            val mergedAlts = alternatives + k2.alternatives
            val mergedNextTo = oneOrNone(nextTo, k2.nextTo)
            return ExtraKey(kv, mergedScript, mergedAlts, mergedNextTo)
        }

        /**
         * If one of [a] or [b] is null, return the other. If [a] and [b] are
         * equal, return [a]. Otherwise, return null.
         */
        private fun <E> oneOrNone(a: E?, b: E?): E? {
            return when {
                a == null -> b
                b == null || a == b -> a
                else -> null
            }
        }

        companion object {
            /**
             * Extra keys are of the form "key name" or "key name:alt1:alt2@next_to".
             */
            @JvmStatic
            fun parse(str: String, script: String): ExtraKey {
                val splitOnAt = str.split("@", limit = 2)
                val keyNames = splitOnAt[0].split(":")
                val kv = KeyValue.getKeyByName(keyNames[0])
                val alts = keyNames.drop(1).map { KeyValue.getKeyByName(it) }
                val nextTo = if (splitOnAt.size > 1) {
                    KeyValue.getKeyByName(splitOnAt[1])
                } else {
                    null
                }
                return ExtraKey(kv, script, alts, nextTo)
            }
        }
    }

    class Query(
        /** Script of the current layout. Might be null. */
        val script: String?,
        /** Keys present on the layout. */
        val present: Set<KeyValue>
    )

    companion object {
        @JvmField
        val EMPTY = ExtraKeys(emptyList())

        @JvmStatic
        fun parse(script: String, str: String): ExtraKeys {
            val dst = mutableListOf<ExtraKey>()
            val ks = str.split("|")
            for (k in ks) {
                dst.add(ExtraKey.parse(k, script))
            }
            return ExtraKeys(dst)
        }

        /**
         * Merge identical keys. This is required to decide whether to add
         * alternatives. Script is generalized (set to null) on any conflict.
         */
        @JvmStatic
        fun merge(kss: List<ExtraKeys>): ExtraKeys {
            val mergedKeys = mutableMapOf<KeyValue, ExtraKey>()
            for (ks in kss) {
                for (k in ks.ks) {
                    val k2 = mergedKeys[k.kv]
                    val mergedKey = if (k2 != null) k.mergeWith(k2) else k
                    mergedKeys[k.kv] = mergedKey
                }
            }
            return ExtraKeys(mergedKeys.values)
        }
    }
}
