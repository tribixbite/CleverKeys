package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure JVM tests for PrefixBoostTrie binary parsing and Aho-Corasick traversal.
 *
 * PrefixBoostTrie requires android.content.Context in its constructor, so we use
 * reflection to call loadFromStream(InputStream) directly and then test the public
 * getNextState/getBoost/getStats API.
 *
 * TensorFactory is skipped entirely — all methods require OrtEnvironment + OnnxTensor
 * which cannot be instantiated without ONNX Runtime native libraries.
 */
class PrefixBoostTrieTest {

    // We create a real PrefixBoostTrie via Unsafe/reflection to skip the Context param
    private lateinit var trie: PrefixBoostTrie
    private lateinit var loadFromStreamMethod: Method

    @Before
    fun setUp() {
        // Allocate instance without calling constructor (bypasses Context requirement)
        val unsafe = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafe.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafeInstance = unsafeField.get(null)
        val allocateMethod = unsafe.getMethod("allocateInstance", Class::class.java)
        trie = allocateMethod.invoke(unsafeInstance, PrefixBoostTrie::class.java) as PrefixBoostTrie

        // Get the private loadFromStream method
        loadFromStreamMethod = PrefixBoostTrie::class.java.getDeclaredMethod(
            "loadFromStream", java.io.InputStream::class.java
        )
        loadFromStreamMethod.isAccessible = true
    }

    // =========================================================================
    // Binary format builder
    // =========================================================================

    /**
     * Build a PBST v2 binary blob from a simple trie specification.
     *
     * @param nodes List of nodes. Each node has:
     *   - edges: List of (charIndex: Byte, targetNode: Int) sorted by charIndex
     *   - failureLink: Int (node index to fall back to; 0 = root)
     *   - boost: Float
     *
     * Node 0 is always the root.
     */
    private data class TrieNode(
        val edges: List<Pair<Byte, Int>> = emptyList(),
        val failureLink: Int = 0,
        val boost: Float = 0f
    )

    private fun buildBinary(nodes: List<TrieNode>): ByteArray {
        val nodeCount = nodes.size
        val allEdges = mutableListOf<Pair<Byte, Int>>()
        val nodeOffsets = mutableListOf<Int>()

        for (node in nodes) {
            nodeOffsets.add(allEdges.size)
            allEdges.addAll(node.edges)
        }
        nodeOffsets.add(allEdges.size) // sentinel for last node

        val edgeCount = allEdges.size

        // Calculate total size
        val headerSize = 16 // magic + version + nodeCount + edgeCount
        val offsetsSize = (nodeCount + 1) * 4
        val keysSize = edgeCount
        val targetsSize = edgeCount * 4
        val failsSize = nodeCount * 4
        val boostsSize = nodeCount * 4
        val totalSize = headerSize + offsetsSize + keysSize + targetsSize + failsSize + boostsSize

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        buffer.putInt(0x54534250) // "PBST" magic
        buffer.putInt(2) // version
        buffer.putInt(nodeCount)
        buffer.putInt(edgeCount)

        // Node offsets (nodeCount + 1 ints)
        for (offset in nodeOffsets) {
            buffer.putInt(offset)
        }

        // Edge keys (edgeCount bytes)
        for ((key, _) in allEdges) {
            buffer.put(key)
        }

        // Edge targets (edgeCount ints)
        for ((_, target) in allEdges) {
            buffer.putInt(target)
        }

        // Failure links (nodeCount ints)
        for (node in nodes) {
            buffer.putInt(node.failureLink)
        }

        // Boost values (nodeCount floats)
        for (node in nodes) {
            buffer.putFloat(node.boost)
        }

        return buffer.array()
    }

    /**
     * Load binary data into the trie instance via reflection.
     */
    private fun loadTrie(data: ByteArray) {
        loadFromStreamMethod.invoke(trie, ByteArrayInputStream(data))
    }

    // =========================================================================
    // Simple trie: root -> 'a' -> node1 (boost=1.5)
    // =========================================================================

    private fun buildSingleCharTrie(): ByteArray {
        // Node 0 (root): edge 'a'(0) -> node 1, no boost
        // Node 1: no edges, boost=1.5
        return buildBinary(listOf(
            TrieNode(edges = listOf(0.toByte() to 1), failureLink = 0, boost = 0f),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 1.5f)
        ))
    }

    @Test
    fun `hasBoosts returns false before loading`() {
        assertThat(trie.hasBoosts()).isFalse()
    }

    @Test
    fun `hasBoosts returns true after loading`() {
        loadTrie(buildSingleCharTrie())
        assertThat(trie.hasBoosts()).isTrue()
    }

    @Test
    fun `getNextState transitions on valid character`() {
        loadTrie(buildSingleCharTrie())

        val nextState = trie.getNextState(0, 'a')
        assertThat(nextState).isEqualTo(1)
    }

    @Test
    fun `getNextState returns root for invalid character`() {
        loadTrie(buildSingleCharTrie())

        // 'b' has no edge from root
        val nextState = trie.getNextState(0, 'b')
        assertThat(nextState).isEqualTo(0)
    }

    @Test
    fun `getNextState returns root for non-alpha characters`() {
        loadTrie(buildSingleCharTrie())

        assertThat(trie.getNextState(0, ' ')).isEqualTo(0)
        assertThat(trie.getNextState(0, '1')).isEqualTo(0)
        assertThat(trie.getNextState(0, 'A')).isEqualTo(0) // uppercase not supported
        assertThat(trie.getNextState(0, '!')).isEqualTo(0)
    }

    @Test
    fun `getBoost returns correct value for matching transition`() {
        loadTrie(buildSingleCharTrie())

        // From root, transition on 'a' -> node 1, which has boost=1.5
        val boost = trie.getBoost(0, 'a')
        assertThat(boost).isWithin(0.001f).of(1.5f)
    }

    @Test
    fun `getBoost returns zero for non-matching character`() {
        loadTrie(buildSingleCharTrie())

        val boost = trie.getBoost(0, 'b')
        assertThat(boost).isWithin(0.001f).of(0f)
    }

    @Test
    fun `getBoost returns zero for non-alpha character`() {
        loadTrie(buildSingleCharTrie())

        assertThat(trie.getBoost(0, '!')).isWithin(0.001f).of(0f)
        assertThat(trie.getBoost(0, 'Z')).isWithin(0.001f).of(0f)
    }

    // =========================================================================
    // Multi-character trie: "ab" and "ac"
    // =========================================================================

    private fun buildBranchingTrie(): ByteArray {
        // Node 0 (root): edge 'a'(0) -> node 1
        // Node 1: edge 'b'(1) -> node 2, edge 'c'(2) -> node 3, boost=0.5
        // Node 2: boost=2.0 (end of "ab")
        // Node 3: boost=3.0 (end of "ac")
        return buildBinary(listOf(
            TrieNode(edges = listOf(0.toByte() to 1), failureLink = 0, boost = 0f),
            TrieNode(edges = listOf(1.toByte() to 2, 2.toByte() to 3), failureLink = 0, boost = 0.5f),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 2.0f),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 3.0f)
        ))
    }

    @Test
    fun `multi-step traversal reaches correct node`() {
        loadTrie(buildBranchingTrie())

        // root --'a'--> 1 --'b'--> 2
        val state1 = trie.getNextState(0, 'a')
        assertThat(state1).isEqualTo(1)

        val state2 = trie.getNextState(state1, 'b')
        assertThat(state2).isEqualTo(2)
    }

    @Test
    fun `branching trie returns correct boost for each branch`() {
        loadTrie(buildBranchingTrie())

        val state1 = trie.getNextState(0, 'a')

        // "ab" path -> node 2, boost=2.0
        assertThat(trie.getBoost(state1, 'b')).isWithin(0.001f).of(2.0f)

        // "ac" path -> node 3, boost=3.0
        assertThat(trie.getBoost(state1, 'c')).isWithin(0.001f).of(3.0f)
    }

    @Test
    fun `intermediate node has its own boost`() {
        loadTrie(buildBranchingTrie())

        // Node 1 (reached by 'a') has boost=0.5
        assertThat(trie.getBoost(0, 'a')).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `no transition from leaf node returns root`() {
        loadTrie(buildBranchingTrie())

        // Navigate to leaf node 2 ("ab")
        val s1 = trie.getNextState(0, 'a')
        val s2 = trie.getNextState(s1, 'b')

        // Node 2 has no edges; failure link is 0 (root), root has no 'x' edge -> stays at root
        val s3 = trie.getNextState(s2, 'x')
        assertThat(s3).isEqualTo(0)
    }

    // =========================================================================
    // Aho-Corasick failure links
    // =========================================================================

    private fun buildFailureLinkTrie(): ByteArray {
        // Trie for patterns "ab" and "b" to test failure links
        //
        // Node 0 (root): edges 'a'(0)->1, 'b'(1)->3
        // Node 1: edge 'b'(1)->2, failureLink=0, boost=0
        // Node 2: no edges, failureLink=3, boost=1.0  ("ab" matched)
        // Node 3: no edges, failureLink=0, boost=0.8  ("b" matched)
        //
        // After matching "ab", the failure link from node 2 -> node 3
        // means "b" is also matched (longest suffix that's a prefix)
        return buildBinary(listOf(
            TrieNode(edges = listOf(0.toByte() to 1, 1.toByte() to 3), failureLink = 0, boost = 0f),
            TrieNode(edges = listOf(1.toByte() to 2), failureLink = 0, boost = 0f),
            TrieNode(edges = emptyList(), failureLink = 3, boost = 1.0f),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 0.8f)
        ))
    }

    @Test
    fun `failure link is followed when no direct transition exists`() {
        loadTrie(buildFailureLinkTrie())

        // Navigate: root --'a'--> 1 --'b'--> 2
        val s1 = trie.getNextState(0, 'a')
        val s2 = trie.getNextState(s1, 'b')
        assertThat(s2).isEqualTo(2)

        // From node 2, try 'b'. Node 2 has no edges.
        // Follow failure link to node 3, which also has no 'b' edge.
        // Follow failure link to root (0), which has 'b'(1)->3.
        // Result: transition to node 3
        val s3 = trie.getNextState(s2, 'b')
        assertThat(s3).isEqualTo(3)
    }

    @Test
    fun `failure link chain reaches root correctly`() {
        loadTrie(buildFailureLinkTrie())

        // Navigate to node 2 via "ab"
        val s1 = trie.getNextState(0, 'a')
        val s2 = trie.getNextState(s1, 'b')

        // From node 2, try 'a'. Node 2 has no edges.
        // Follow failure link to node 3 (no 'a' edge).
        // Follow failure link to root (0), which has 'a'(0)->1.
        // Result: transition to node 1
        val s3 = trie.getNextState(s2, 'a')
        assertThat(s3).isEqualTo(1)
    }

    // =========================================================================
    // getStats tests
    // =========================================================================

    @Test
    fun `getStats returns zeros when not loaded`() {
        val stats = trie.getStats()
        assertThat(stats.nodeCount).isEqualTo(0)
        assertThat(stats.edgeCount).isEqualTo(0)
        assertThat(stats.maxBoost).isWithin(0.001f).of(0f)
        assertThat(stats.avgBoost).isWithin(0.001f).of(0f)
    }

    @Test
    fun `getStats reflects loaded trie structure`() {
        loadTrie(buildBranchingTrie())

        val stats = trie.getStats()
        assertThat(stats.nodeCount).isEqualTo(4)
        assertThat(stats.edgeCount).isEqualTo(3) // a->1, b->2, c->3
    }

    @Test
    fun `getStats computes max and avg boost correctly`() {
        loadTrie(buildBranchingTrie())

        val stats = trie.getStats()
        // Boosts: 0, 0.5, 2.0, 3.0 — only positive values counted
        assertThat(stats.maxBoost).isWithin(0.001f).of(3.0f)
        // avg of 0.5, 2.0, 3.0 = 5.5 / 3 ≈ 1.833
        assertThat(stats.avgBoost).isWithin(0.01f).of(5.5f / 3f)
    }

    // =========================================================================
    // Unload tests
    // =========================================================================

    @Test
    fun `unload clears trie state`() {
        loadTrie(buildSingleCharTrie())
        assertThat(trie.hasBoosts()).isTrue()

        trie.unload()
        assertThat(trie.hasBoosts()).isFalse()
    }

    @Test
    fun `getNextState returns root after unload`() {
        loadTrie(buildSingleCharTrie())
        trie.unload()

        assertThat(trie.getNextState(0, 'a')).isEqualTo(0)
    }

    @Test
    fun `getBoost returns zero after unload`() {
        loadTrie(buildSingleCharTrie())
        trie.unload()

        assertThat(trie.getBoost(0, 'a')).isWithin(0.001f).of(0f)
    }

    @Test
    fun `getStats returns zeros after unload`() {
        loadTrie(buildSingleCharTrie())
        trie.unload()

        val stats = trie.getStats()
        assertThat(stats.nodeCount).isEqualTo(0)
        assertThat(stats.edgeCount).isEqualTo(0)
    }

    @Test
    fun `getLoadedLanguage returns null after unload`() {
        loadTrie(buildSingleCharTrie())
        trie.unload()

        assertThat(trie.getLoadedLanguage()).isNull()
    }

    // =========================================================================
    // Binary format validation
    // =========================================================================

    @Test
    fun `loadFromStream rejects invalid magic number`() {
        val badMagic = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        badMagic.putInt(0xDEADBEEF.toInt()) // wrong magic
        badMagic.putInt(2)
        badMagic.putInt(0)
        badMagic.putInt(0)

        try {
            loadTrie(badMagic.array())
            assertThat(false).isTrue() // should not reach here
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Reflection wraps the real exception
            assertThat(e.cause).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `loadFromStream rejects unsupported version`() {
        val badVersion = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        badVersion.putInt(0x54534250) // correct magic
        badVersion.putInt(99) // wrong version
        badVersion.putInt(0)
        badVersion.putInt(0)

        try {
            loadTrie(badVersion.array())
            assertThat(false).isTrue() // should not reach here
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertThat(e.cause).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(e.cause!!.message).contains("version")
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `empty trie with only root node`() {
        // Root node with no edges, no boosts
        val data = buildBinary(listOf(
            TrieNode(edges = emptyList(), failureLink = 0, boost = 0f)
        ))
        loadTrie(data)

        assertThat(trie.hasBoosts()).isTrue()
        assertThat(trie.getNextState(0, 'a')).isEqualTo(0)
        assertThat(trie.getBoost(0, 'a')).isWithin(0.001f).of(0f)

        val stats = trie.getStats()
        assertThat(stats.nodeCount).isEqualTo(1)
        assertThat(stats.edgeCount).isEqualTo(0)
    }

    @Test
    fun `deep linear chain traversal`() {
        // Build a chain: root -> a -> b -> c -> d (each has distinct boost)
        val data = buildBinary(listOf(
            TrieNode(edges = listOf(0.toByte() to 1), failureLink = 0, boost = 0f),   // root
            TrieNode(edges = listOf(1.toByte() to 2), failureLink = 0, boost = 0.1f), // after 'a'
            TrieNode(edges = listOf(2.toByte() to 3), failureLink = 0, boost = 0.2f), // after 'ab'
            TrieNode(edges = listOf(3.toByte() to 4), failureLink = 0, boost = 0.3f), // after 'abc'
            TrieNode(edges = emptyList(), failureLink = 0, boost = 0.4f)              // after 'abcd'
        ))
        loadTrie(data)

        var state = 0
        state = trie.getNextState(state, 'a')
        assertThat(state).isEqualTo(1)

        state = trie.getNextState(state, 'b')
        assertThat(state).isEqualTo(2)

        state = trie.getNextState(state, 'c')
        assertThat(state).isEqualTo(3)

        state = trie.getNextState(state, 'd')
        assertThat(state).isEqualTo(4)

        // Verify accumulated traversal boost at final node
        assertThat(trie.getBoost(3, 'd')).isWithin(0.001f).of(0.4f)
    }

    @Test
    fun `all 26 characters can be used as edge keys`() {
        // Build root with edges for all 26 letters
        val edges = (0 until 26).map { i -> i.toByte() to (i + 1) }
        val nodes = mutableListOf(
            TrieNode(edges = edges, failureLink = 0, boost = 0f)
        )
        // Add 26 child nodes with distinct boosts
        for (i in 0 until 26) {
            nodes.add(TrieNode(edges = emptyList(), failureLink = 0, boost = (i + 1).toFloat()))
        }

        val data = buildBinary(nodes)
        loadTrie(data)

        // Verify each letter transitions to the correct node with correct boost
        for (i in 0 until 26) {
            val char = ('a' + i)
            val nextState = trie.getNextState(0, char)
            assertThat(nextState).isEqualTo(i + 1)
            assertThat(trie.getBoost(0, char)).isWithin(0.001f).of((i + 1).toFloat())
        }
    }

    @Test
    fun `sorted edge keys enable early termination in linear scan`() {
        // Build root with edges for 'a', 'c', 'z' (sorted, gaps)
        val data = buildBinary(listOf(
            TrieNode(
                edges = listOf(0.toByte() to 1, 2.toByte() to 2, 25.toByte() to 3),
                failureLink = 0, boost = 0f
            ),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 1.0f),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 2.0f),
            TrieNode(edges = emptyList(), failureLink = 0, boost = 3.0f)
        ))
        loadTrie(data)

        // 'a' = idx 0 -> node 1
        assertThat(trie.getNextState(0, 'a')).isEqualTo(1)
        // 'b' = idx 1, falls between 0 and 2 -> early break, return root
        assertThat(trie.getNextState(0, 'b')).isEqualTo(0)
        // 'c' = idx 2 -> node 2
        assertThat(trie.getNextState(0, 'c')).isEqualTo(2)
        // 'z' = idx 25 -> node 3
        assertThat(trie.getNextState(0, 'z')).isEqualTo(3)
    }

    @Test
    fun `reload after unload works correctly`() {
        loadTrie(buildSingleCharTrie())
        assertThat(trie.getNextState(0, 'a')).isEqualTo(1)

        trie.unload()
        assertThat(trie.hasBoosts()).isFalse()

        // Reload with different data (branching trie)
        loadTrie(buildBranchingTrie())
        assertThat(trie.hasBoosts()).isTrue()
        assertThat(trie.getNextState(0, 'a')).isEqualTo(1)

        val s1 = trie.getNextState(0, 'a')
        assertThat(trie.getNextState(s1, 'b')).isEqualTo(2)
        assertThat(trie.getNextState(s1, 'c')).isEqualTo(3)
    }
}
