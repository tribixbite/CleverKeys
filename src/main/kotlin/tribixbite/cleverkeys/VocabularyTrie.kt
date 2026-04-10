package tribixbite.cleverkeys

import android.util.Log

/**
 * A Trie data structure optimized for vocabulary prefix lookups during beam search.
 *
 * This enables constrained vocabulary search: the beam search can query `hasPrefix()`
 * before exploring a candidate path, avoiding computation on invalid word sequences.
 *
 * MEMORY OPTIMIZATION: Uses compact parallel arrays (CharArray + Array<TrieNode?>)
 * instead of HashMap<Char, TrieNode> per node. For 50k English words (~180k nodes):
 * - HashMap version: ~45MB (LinkedHashMap overhead + Entry objects + boxed Char keys)
 * - Array version: ~11MB (raw arrays, no boxing, no entry objects)
 * Savings: ~34MB per trie instance
 *
 * The tradeoff is O(k) linear scan for child lookup where k = number of children (max 26),
 * but this is faster than HashMap in practice due to CPU cache locality on small arrays.
 *
 * Performance characteristics:
 * - Insert: O(m) where m = word length
 * - HasPrefix: O(m) where m = prefix length
 * - Space: O(n * m) where n = vocabulary size, m = average word length (compact)
 *
 * Thread safety: NOT thread-safe. Build the trie once, then use read-only.
 */
class VocabularyTrie {
    private var root = TrieNode()
    private var wordCount = 0

    companion object {
        private const val TAG = "VocabularyTrie"
        // Shared empty arrays — all leaf nodes reference these (zero per-node allocation)
        private val EMPTY_KEYS = CharArray(0)
        private val EMPTY_CHILDREN = emptyArray<TrieNode?>()
    }

    /**
     * Compact trie node using parallel sorted arrays instead of HashMap.
     *
     * Memory per node (64-bit JVM):
     * - Object header: 16 bytes
     * - keys ref: 8 bytes, children ref: 8 bytes, isEndOfWord: 4 bytes = 36 bytes base
     * - Leaf node (0 children): 36 bytes (shares EMPTY_KEYS/EMPTY_CHILDREN singletons)
     * - Node with 3 children: 36 + CharArray(3)=24 + Array(3)=40 = 100 bytes
     *
     * Compare HashMap version:
     * - Empty LinkedHashMap: ~64 bytes + 36 bytes TrieNode = 100 bytes minimum
     * - With 3 entries: 100 + table(128) + 3×Entry(76) = 456 bytes
     */
    private class TrieNode {
        var keys: CharArray = EMPTY_KEYS
        var children: Array<TrieNode?> = EMPTY_CHILDREN
        var isEndOfWord = false

        /** Get child node for character, or null if not found. O(k) linear scan. */
        fun getChild(char: Char): TrieNode? {
            val k = keys
            for (i in k.indices) {
                if (k[i] == char) return children[i]
            }
            return null
        }

        /**
         * Get or create child node for character.
         * Grows parallel arrays by 1 on new insertion. O(k) amortized since max k=26.
         */
        fun getOrCreateChild(char: Char): TrieNode {
            val k = keys
            for (i in k.indices) {
                if (k[i] == char) return children[i]!!
            }
            // Not found — grow arrays by 1
            val oldSize = k.size
            val newSize = oldSize + 1

            val newKeys = CharArray(newSize)
            k.copyInto(newKeys)
            newKeys[oldSize] = char

            val newChildren = arrayOfNulls<TrieNode>(newSize)
            children.copyInto(newChildren)
            val node = TrieNode()
            newChildren[oldSize] = node

            keys = newKeys
            children = newChildren
            return node
        }

        /** Number of children. */
        fun childCount(): Int = keys.size
    }

    /**
     * Insert a word into the trie. Case-insensitive (converts to lowercase).
     *
     * @param word The word to insert (will be lowercased)
     */
    fun insert(word: String) {
        if (word.isEmpty()) return

        val lowerWord = word.lowercase()
        var current = root

        for (char in lowerWord) {
            current = current.getOrCreateChild(char)
        }

        if (!current.isEndOfWord) {
            current.isEndOfWord = true
            wordCount++
        }
    }

    /**
     * Check if the trie contains any word with the given prefix.
     * Case-insensitive (converts to lowercase).
     *
     * This is the key method called during beam search to validate candidate paths.
     *
     * @param prefix The prefix to check (will be lowercased)
     * @return true if at least one word starts with this prefix, false otherwise
     */
    fun hasPrefix(prefix: String): Boolean {
        if (prefix.isEmpty()) return true // Empty prefix is valid

        var current = root

        for (char in prefix) {
            current = current.getChild(char.lowercaseChar()) ?: return false
        }

        return true
    }

    /**
     * Get all allowed next characters for a given prefix.
     * Case-insensitive (converts to lowercase).
     *
     * @param prefix The prefix to check (will be lowercased)
     * @return Set of valid next characters, or empty set if prefix not found
     */
    fun getAllowedNextChars(prefix: String): Set<Char> {
        var current = root

        for (char in prefix) {
            current = current.getChild(char.lowercaseChar()) ?: return emptySet()
        }

        // Build set from compact key array
        val k = current.keys
        if (k.isEmpty()) return emptySet()
        val result = HashSet<Char>(k.size)
        for (c in k) result.add(c)
        return result
    }

    /**
     * Check if the trie contains this exact word.
     * Case-insensitive (converts to lowercase).
     *
     * @param word The word to check (will be lowercased)
     * @return true if this exact word exists in the trie
     */
    fun containsWord(word: String): Boolean {
        if (word.isEmpty()) return false

        var current = root

        for (char in word) {
            current = current.getChild(char.lowercaseChar()) ?: return false
        }

        return current.isEndOfWord
    }

    /**
     * Bulk insert words from a collection. Convenience wrapper over insert().
     *
     * @param words Collection of words to insert
     */
    fun insertAll(words: Collection<String>) {
        words.forEach { insert(it) }
    }

    /**
     * Get statistics about the trie.
     *
     * @return Pair of (wordCount, nodeCount)
     */
    fun getStats(): Pair<Int, Int> {
        return Pair(wordCount, countNodes(root))
    }

    private fun countNodes(node: TrieNode): Int {
        var count = 1 // Count this node
        val c = node.children
        for (child in c) {
            if (child != null) count += countNodes(child)
        }
        return count
    }

    /**
     * Clear all words from the trie.
     */
    fun clear() {
        root = TrieNode()
        wordCount = 0
    }

    /**
     * Estimate memory usage in bytes.
     * Useful for diagnostics — counts object headers, arrays, and references.
     */
    fun estimateMemoryBytes(): Long {
        return estimateNodeMemory(root)
    }

    private fun estimateNodeMemory(node: TrieNode): Long {
        // TrieNode object: 16 (header) + 8 (keys ref) + 8 (children ref) + 4 (bool) = 36→40 aligned
        var bytes: Long = 40
        val k = node.keys
        if (k.isNotEmpty()) {
            // CharArray: 16 (header) + 4 (length) + 2*size, aligned to 8
            bytes += 16 + 4 + ((k.size * 2L + 7) and 7L.inv())
            // Array<TrieNode?>: 16 (header) + 4 (length) + 8*size
            bytes += 16 + 4 + k.size * 8L
        }
        // Recurse into children
        for (child in node.children) {
            if (child != null) bytes += estimateNodeMemory(child)
        }
        return bytes
    }

    /**
     * Log statistics about the trie (useful for debugging).
     */
    fun logStats() {
        val (words, nodes) = getStats()
        val memMB = estimateMemoryBytes() / (1024.0 * 1024.0)
        Log.d(TAG, "VocabularyTrie stats: $words words, $nodes nodes, ~${"%.1f".format(memMB)}MB")
    }
}
