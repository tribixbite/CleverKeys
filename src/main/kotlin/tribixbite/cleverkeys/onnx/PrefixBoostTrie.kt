package tribixbite.cleverkeys.onnx

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * A zero-allocation, memory-mapped Aho-Corasick trie for prefix boosting.
 *
 * This trie stores precomputed boosts for character sequences that are common
 * in the target language but rare in English. During beam search, these boosts
 * are applied to logits to prevent the English-trained NN from pruning valid
 * foreign language paths.
 *
 * Binary format (Version 2 - Sparse):
 * - Header: Magic "PBST" (4) + Version (4) + NodeCount (4) + EdgeCount (4)
 * - Node Offsets: (NodeCount + 1) * 4 bytes
 * - Edge Keys: EdgeCount bytes (char indices 0-25)
 * - Edge Targets: EdgeCount * 4 bytes
 * - Failure Links: NodeCount * 4 bytes
 * - Boost Values: NodeCount * 4 bytes
 *
 * Performance characteristics:
 * - getNextState: O(k) where k = avg children per node (~4)
 * - getBoost: O(k) linear scan over sorted edges
 * - Zero heap allocations during lookup
 * - Memory-mapped file doesn't use Java heap
 */
class PrefixBoostTrie(private val context: Context) {

    companion object {
        private const val TAG = "PrefixBoostTrie"
        private const val MAGIC = 0x54534250  // "PBST" little-endian
        private const val ASSET_PATH = "prefix_boosts"
    }

    // Memory-mapped buffer sections
    private var nodeOffsets: IntBuffer? = null
    private var edgeKeys: ByteBuffer? = null
    private var edgeTargets: IntBuffer? = null
    private var failureLinks: IntBuffer? = null
    private var boostValues: FloatBuffer? = null

    private var nodeCount = 0
    private var edgeCount = 0
    private var isLoaded = false
    private var loadedLanguage: String? = null

    /**
     * Load prefix boost trie for a language from assets.
     *
     * @param langCode Language code (e.g., "fr", "de", "es")
     * @return true if loaded successfully
     */
    fun loadFromAssets(langCode: String): Boolean {
        if (langCode == loadedLanguage && isLoaded) {
            return true  // Already loaded
        }

        // English doesn't need boosts
        if (langCode == "en") {
            unload()
            loadedLanguage = "en"
            return true
        }

        val assetPath = "$ASSET_PATH/$langCode.bin"
        return try {
            context.assets.open(assetPath).use { inputStream ->
                loadFromStream(inputStream)
            }
            loadedLanguage = langCode
            Log.i(TAG, "Loaded prefix boosts for $langCode: $nodeCount nodes, $edgeCount edges")
            true
        } catch (e: Exception) {
            Log.w(TAG, "No prefix boosts available for $langCode: ${e.message}")
            unload()
            false
        }
    }

    /**
     * Load trie from an input stream.
     */
    private fun loadFromStream(inputStream: InputStream) {
        // Read entire file into ByteBuffer
        val bytes = inputStream.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Parse header
        val magic = buffer.int
        if (magic != MAGIC) {
            throw IllegalArgumentException("Invalid magic: expected PBST, got ${Integer.toHexString(magic)}")
        }

        val version = buffer.int
        if (version != 2) {
            throw IllegalArgumentException("Unsupported version: $version (expected 2)")
        }

        nodeCount = buffer.int
        edgeCount = buffer.int

        // Calculate section sizes
        val headerSize = 16
        val offsetsSize = (nodeCount + 1) * 4
        val keysSize = edgeCount
        val targetsSize = edgeCount * 4
        val failsSize = nodeCount * 4
        val boostsSize = nodeCount * 4

        // Slice buffer into sections
        var pos = headerSize

        // Node offsets: (NodeCount + 1) ints
        buffer.position(pos)
        buffer.limit(pos + offsetsSize)
        nodeOffsets = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        pos += offsetsSize

        // Edge keys: EdgeCount bytes
        buffer.position(pos)
        buffer.limit(pos + keysSize)
        edgeKeys = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        pos += keysSize

        // Edge targets: EdgeCount ints
        buffer.position(pos)
        buffer.limit(pos + targetsSize)
        edgeTargets = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        pos += targetsSize

        // Failure links: NodeCount ints
        buffer.position(pos)
        buffer.limit(pos + failsSize)
        failureLinks = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        pos += failsSize

        // Boost values: NodeCount floats
        buffer.position(pos)
        buffer.limit(pos + boostsSize)
        boostValues = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        isLoaded = true
    }

    /**
     * Unload current trie to free memory.
     */
    fun unload() {
        nodeOffsets = null
        edgeKeys = null
        edgeTargets = null
        failureLinks = null
        boostValues = null
        nodeCount = 0
        edgeCount = 0
        isLoaded = false
        loadedLanguage = null
    }

    /**
     * Advance trie state based on input character.
     *
     * Uses Aho-Corasick failure links for automatic longest-suffix fallback.
     * If no transition exists, follows failure links until one is found or root is reached.
     *
     * @param currentState Current state index (start with 0)
     * @param char Input character (must be 'a'..'z')
     * @return New state index
     */
    fun getNextState(currentState: Int, char: Char): Int {
        if (!isLoaded) return 0
        if (char < 'a' || char > 'z') return 0  // Reset on non-alpha

        val charIdx = (char - 'a').toByte()
        var state = currentState

        // Follow failure links until we find a transition or hit root
        while (true) {
            val nextState = findTransition(state, charIdx)
            if (nextState != -1) {
                return nextState
            }

            if (state == 0) {
                return 0  // At root, no transition -> stay at root
            }

            // Follow failure link to longest proper suffix
            state = failureLinks!!.get(state)
        }
    }

    /**
     * Find transition from state for given character index.
     *
     * Uses binary search over sorted edges for O(log k) lookup.
     *
     * @return Target state or -1 if no transition exists
     */
    private fun findTransition(state: Int, charIdx: Byte): Int {
        val startIdx = nodeOffsets!!.get(state)
        val endIdx = nodeOffsets!!.get(state + 1)

        // Linear scan over edges (edges are sorted by key)
        // For small fan-out (~4 edges avg), linear scan is faster than binary search
        for (i in startIdx until endIdx) {
            val key = edgeKeys!!.get(i)
            if (key == charIdx) {
                return edgeTargets!!.get(i)
            }
            if (key > charIdx) {
                break  // Keys are sorted, no need to continue
            }
        }
        return -1
    }

    /**
     * Get boost value for a candidate character given current state.
     *
     * This is the main interface for beam search. Given the current prefix
     * (represented by state), returns the boost to add to the logit for
     * the specified next character.
     *
     * @param currentState Current beam state (representing the prefix)
     * @param char Candidate character being predicted
     * @return Boost value to add to logit, or 0.0 if none
     */
    fun getBoost(currentState: Int, char: Char): Float {
        if (!isLoaded) return 0f
        if (char < 'a' || char > 'z') return 0f

        val charIdx = (char - 'a').toByte()

        // Find the target node for this transition
        val nextState = findTransition(currentState, charIdx)
        if (nextState == -1) {
            return 0f
        }

        // Return the boost stored at the target node
        return boostValues!!.get(nextState)
    }

    /**
     * Check if trie is loaded.
     */
    fun hasBoosts(): Boolean = isLoaded

    /**
     * Get currently loaded language.
     */
    fun getLoadedLanguage(): String? = loadedLanguage

    /**
     * Get statistics about loaded trie.
     */
    fun getStats(): TrieStats {
        if (!isLoaded) {
            return TrieStats(0, 0, 0f, 0f)
        }

        var maxBoost = 0f
        var sumBoost = 0f
        var boostCount = 0

        for (i in 0 until nodeCount) {
            val boost = boostValues!!.get(i)
            if (boost > 0f) {
                maxBoost = maxOf(maxBoost, boost)
                sumBoost += boost
                boostCount++
            }
        }

        return TrieStats(
            nodeCount = nodeCount,
            edgeCount = edgeCount,
            maxBoost = maxBoost,
            avgBoost = if (boostCount > 0) sumBoost / boostCount else 0f
        )
    }

    data class TrieStats(
        val nodeCount: Int,
        val edgeCount: Int,
        val maxBoost: Float,
        val avgBoost: Float
    )
}
