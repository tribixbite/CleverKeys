package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Lazy-loaded Trie-based emoji keyword index for fast prefix search.
 *
 * Features:
 * - 9,800+ keywords from Discord/Twemoji, Slack, GitHub, Google Noto, CLDR
 * - Prefix matching: "fi" finds "fire", "fireworks", "fish", etc.
 * - Background loading on IO thread (doesn't block keyboard startup)
 * - Memory efficient Trie structure
 *
 * Usage:
 *   EmojiKeywordIndex.prewarm(context)  // Call in service onCreate
 *   val results = EmojiKeywordIndex.search("pump")  // Returns ["🎃", ...]
 *
 * @since v1.2.6
 */
object EmojiKeywordIndex {
    private const val TAG = "EmojiKeywordIndex"
    private const val ASSET_FILE = "emoji_keywords.tsv"

    private var rootNode: TrieNode? = null
    private var loadJob: Job? = null
    private var isLoaded = false

    /**
     * Trie node for prefix-based emoji search.
     */
    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        val emojis = mutableListOf<String>()
    }

    /**
     * Start loading the keyword index in background.
     * Call this early (e.g., InputMethodService.onCreate) so data is ready
     * when user opens emoji search.
     */
    fun prewarm(context: Context) {
        if (isLoaded || loadJob?.isActive == true) return

        loadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val start = System.currentTimeMillis()
                val newRoot = TrieNode()
                var lineCount = 0

                context.assets.open(ASSET_FILE).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        reader.forEachLine { line ->
                            // Format: keyword\temoji1,emoji2,emoji3
                            val tabIndex = line.indexOf('\t')
                            if (tabIndex > 0) {
                                val keyword = line.substring(0, tabIndex)
                                val emojis = line.substring(tabIndex + 1)
                                insert(newRoot, keyword, emojis.split(','))
                                lineCount++
                            }
                        }
                    }
                }

                rootNode = newRoot
                isLoaded = true
                val elapsed = System.currentTimeMillis() - start
                Log.d(TAG, "Loaded $lineCount keywords in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load emoji keywords", e)
            }
        }
    }

    /**
     * Insert a keyword with its associated emojis into the Trie.
     */
    private fun insert(root: TrieNode, keyword: String, emojis: List<String>) {
        var node = root
        for (char in keyword.lowercase()) {
            node = node.children.getOrPut(char) { TrieNode() }
        }
        node.emojis.addAll(emojis)
    }

    /**
     * Search for emojis matching a keyword prefix.
     * Returns emojis where keyword starts with query (prefix match).
     *
     * @param query The search query (e.g., "pump" finds "pumpkin" → 🎃)
     * @param limit Maximum results to return
     * @return List of matching emoji strings, ordered by relevance
     */
    fun search(query: String, limit: Int = 50): List<String> {
        if (query.isBlank()) return emptyList()

        val root = rootNode
        if (root == null) {
            Log.w(TAG, "Search called before index loaded")
            return emptyList()
        }

        val normalizedQuery = query.lowercase().trim()
        val results = mutableSetOf<String>()

        // Navigate to query prefix node
        var node: TrieNode = root
        for (char in normalizedQuery) {
            val child = node.children[char] ?: return emptyList()
            node = child
        }

        // Collect all emojis from this node and descendants (prefix matches)
        collectEmojis(node, results, limit * 2)

        return results.take(limit).toList()
    }

    /**
     * Recursively collect emojis from a node and all its descendants.
     */
    private fun collectEmojis(node: TrieNode, results: MutableSet<String>, limit: Int) {
        if (results.size >= limit) return

        // Add emojis at this node (exact or prefix match)
        results.addAll(node.emojis.take(limit - results.size))

        // Recurse into children (broader prefix matches)
        for (child in node.children.values) {
            if (results.size >= limit) break
            collectEmojis(child, results, limit)
        }
    }

    /**
     * Check if the index is ready for searching.
     */
    fun isReady(): Boolean = isLoaded

    /**
     * Wait for loading to complete (for testing or guaranteed availability).
     */
    suspend fun awaitReady() {
        loadJob?.join()
    }

    /**
     * Get stats about the loaded index (for debugging).
     */
    fun getStats(): String {
        val root = rootNode ?: return "Not loaded"
        var nodeCount = 0
        var keywordCount = 0

        fun count(node: TrieNode) {
            nodeCount++
            if (node.emojis.isNotEmpty()) keywordCount++
            for (child in node.children.values) count(child)
        }
        count(root)

        return "Nodes: $nodeCount, Keywords: $keywordCount"
    }
}
