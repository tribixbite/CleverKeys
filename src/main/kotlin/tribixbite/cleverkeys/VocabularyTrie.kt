package tribixbite.cleverkeys

import android.util.Log

/**
 * A Trie data structure optimized for vocabulary prefix lookups during beam search.
 *
 * This enables constrained vocabulary search: the beam search can query `hasPrefix()`
 * before exploring a candidate path, avoiding computation on invalid word sequences.
 *
 * Performance characteristics:
 * - Insert: O(m) where m = word length
 * - HasPrefix: O(m) where m = prefix length
 * - Space: O(n * m) where n = vocabulary size, m = average word length
 *
 * Thread safety: NOT thread-safe. Build the trie once, then use read-only.
 */
class VocabularyTrie {
    private val root = TrieNode()
    private var wordCount = 0

    companion object {
        private const val TAG = "VocabularyTrie"
    }

    /**
     * Node in the trie. Each node represents a character position in words.
     *
     * @property subtreeWordCount Number of complete words reachable from this node
     *           (including this node if isEndOfWord). Used for LM fusion scoring.
     */
    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        var isEndOfWord = false
        var subtreeWordCount = 0  // Words reachable from this node (for LM boost)
    }

    /**
     * Insert a word into the trie. Case-insensitive (converts to lowercase).
     * Updates subtreeWordCount for all nodes along the path.
     *
     * @param word The word to insert (will be lowercased)
     */
    fun insert(word: String) {
        if (word.isEmpty()) return

        val lowerWord = word.lowercase()

        // Track path for subtreeWordCount updates
        val path = mutableListOf<TrieNode>()
        var current = root
        path.add(current)

        for (char in lowerWord) {
            current = current.children.getOrPut(char) { TrieNode() }
            path.add(current)
        }

        if (!current.isEndOfWord) {
            current.isEndOfWord = true
            wordCount++
            // Increment subtreeWordCount for all ancestors (including root and current)
            for (node in path) {
                node.subtreeWordCount++
            }
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

        val lowerPrefix = prefix.lowercase()
        var current = root

        for (char in lowerPrefix) {
            val next = current.children[char] ?: return false
            current = next
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
        val lowerPrefix = prefix.lowercase()
        var current = root

        for (char in lowerPrefix) {
            val next = current.children[char] ?: return emptySet()
            current = next
        }

        return current.children.keys
    }

    /**
     * Get the number of words reachable from a given prefix.
     * Used for LM fusion scoring in beam search - paths with more reachable
     * words get a probability boost proportional to log(count).
     *
     * @param prefix The prefix to check (will be lowercased)
     * @return Number of complete words that start with this prefix, or 0 if prefix not found
     */
    fun getSubtreeWordCount(prefix: String): Int {
        if (prefix.isEmpty()) return root.subtreeWordCount

        val lowerPrefix = prefix.lowercase()
        var current = root

        for (char in lowerPrefix) {
            val next = current.children[char] ?: return 0
            current = next
        }

        return current.subtreeWordCount
    }

    /**
     * Get allowed next characters with their subtree word counts.
     * Efficient single-traversal method for LM fusion scoring.
     *
     * @param prefix The prefix to check (will be lowercased)
     * @return Map of char -> subtreeWordCount, or empty map if prefix not found
     */
    fun getAllowedNextCharsWithCounts(prefix: String): Map<Char, Int> {
        val lowerPrefix = prefix.lowercase()
        var current = root

        for (char in lowerPrefix) {
            val next = current.children[char] ?: return emptyMap()
            current = next
        }

        return current.children.mapValues { it.value.subtreeWordCount }
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

        val lowerWord = word.lowercase()
        var current = root

        for (char in lowerWord) {
            val next = current.children[char] ?: return false
            current = next
        }

        return current.isEndOfWord
    }

    /**
     * Bulk insert words from a collection. More efficient than calling insert() repeatedly.
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
        for (child in node.children.values) {
            count += countNodes(child)
        }
        return count
    }

    /**
     * Clear all words from the trie.
     */
    fun clear() {
        root.children.clear()
        root.subtreeWordCount = 0
        wordCount = 0
    }

    /**
     * Log statistics about the trie (useful for debugging).
     */
    fun logStats() {
        val (words, nodes) = getStats()
        Log.d(TAG, "VocabularyTrie stats: $words words, $nodes nodes")
    }
}
