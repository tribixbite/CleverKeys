package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [ShortSwipeCustomizationManager.buildByKeyIndex] — the per-key render index
 * that replaced the former per-frame `mappingCache.values.filter{}.associateBy{}` scan in
 * `getMappingsForKey` (R2, ui-layer audit finding #4).
 *
 * The index MUST stay behaviourally identical to the old lookup for every key: these tests build
 * the reference result with the *original* filter/associateBy expression and assert the new index
 * produces the same map after add / remove / clear / import-shaped mutations of the mapping set.
 *
 * Pure: only [ShortSwipeMapping] / [SwipeDirection] / [ActionType] are touched (no Android/IO).
 */
class ShortSwipeByKeyIndexTest {

    // --- helpers -------------------------------------------------------------

    private fun mapping(
        keyCode: String,
        direction: SwipeDirection,
        display: String = "x",
    ): ShortSwipeMapping = ShortSwipeMapping(
        keyCode = keyCode,
        direction = direction,
        displayText = display,
        actionType = ActionType.TEXT,
        actionValue = "v-$keyCode-${direction.name}",
    )

    /**
     * The EXACT semantics of the pre-R2 `getMappingsForKey` for a single query key, used as the
     * behavioural oracle: filter the flat collection by raw `keyCode` equality against the
     * lowercased query, then `associateBy { direction }`.
     */
    private fun oldGetMappingsForKey(
        all: Collection<ShortSwipeMapping>,
        queryKeyCode: String,
    ): Map<SwipeDirection, ShortSwipeMapping> {
        val normalizedKey = queryKeyCode.lowercase()
        return all.filter { it.keyCode == normalizedKey }.associateBy { it.direction }
    }

    /** Look a key up through the new index the way production's getMappingsForKey does. */
    private fun indexedGetMappingsForKey(
        all: Collection<ShortSwipeMapping>,
        queryKeyCode: String,
    ): Map<SwipeDirection, ShortSwipeMapping> {
        val index = ShortSwipeCustomizationManager.buildByKeyIndex(all)
        return index[queryKeyCode.lowercase()] ?: emptyMap()
    }

    /** Assert index result == old filter/associateBy result for a set of query keys. */
    private fun assertConsistent(all: Collection<ShortSwipeMapping>, vararg queryKeys: String) {
        for (q in queryKeys) {
            assertThat(indexedGetMappingsForKey(all, q))
                .isEqualTo(oldGetMappingsForKey(all, q))
        }
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun `empty set yields empty index and empty per-key lookups`() {
        val all = emptyList<ShortSwipeMapping>()
        assertThat(ShortSwipeCustomizationManager.buildByKeyIndex(all)).isEmpty()
        assertConsistent(all, "a", "b", "space")
    }

    @Test
    fun `single mapping is reachable by its key and no other`() {
        val all = listOf(mapping("a", SwipeDirection.N))
        assertConsistent(all, "a", "b")
        assertThat(indexedGetMappingsForKey(all, "a")).containsKey(SwipeDirection.N)
        assertThat(indexedGetMappingsForKey(all, "b")).isEmpty()
    }

    @Test
    fun `multiple directions on one key group under that key`() {
        val all = listOf(
            mapping("a", SwipeDirection.N),
            mapping("a", SwipeDirection.S),
            mapping("a", SwipeDirection.E),
        )
        val result = indexedGetMappingsForKey(all, "a")
        assertThat(result.keys)
            .containsExactly(SwipeDirection.N, SwipeDirection.S, SwipeDirection.E)
        assertConsistent(all, "a")
    }

    @Test
    fun `mappings for different keys stay isolated`() {
        val all = listOf(
            mapping("a", SwipeDirection.N),
            mapping("b", SwipeDirection.N),
            mapping("b", SwipeDirection.S),
            mapping("space", SwipeDirection.E),
        )
        assertThat(indexedGetMappingsForKey(all, "a").keys).containsExactly(SwipeDirection.N)
        assertThat(indexedGetMappingsForKey(all, "b").keys)
            .containsExactly(SwipeDirection.N, SwipeDirection.S)
        assertThat(indexedGetMappingsForKey(all, "space").keys).containsExactly(SwipeDirection.E)
        assertConsistent(all, "a", "b", "space", "z")
    }

    @Test
    fun `query key is lowercased before lookup - uppercase query resolves to lowercase-stored key`() {
        // Stored keyCode "a"; production lowercases the query, so "A" must resolve to it —
        // mirroring the old filter which compared it.keyCode against queryKeyCode.lowercase().
        val all = listOf(mapping("a", SwipeDirection.N))
        assertThat(indexedGetMappingsForKey(all, "A")).isEqualTo(indexedGetMappingsForKey(all, "a"))
        assertConsistent(all, "A", "a")
    }

    @Test
    fun `add mutation - index reflects the added mapping and matches oracle`() {
        val base = mutableListOf(mapping("a", SwipeDirection.N))
        assertConsistent(base, "a", "b")
        // Simulate setMapping("b", S)
        base.add(mapping("b", SwipeDirection.S))
        assertConsistent(base, "a", "b")
        assertThat(indexedGetMappingsForKey(base, "b").keys).containsExactly(SwipeDirection.S)
    }

    @Test
    fun `remove mutation - removed direction disappears, sibling directions remain`() {
        val all = mutableListOf(
            mapping("a", SwipeDirection.N),
            mapping("a", SwipeDirection.S),
        )
        // Simulate removeMapping("a", N)
        all.removeAll { it.keyCode == "a" && it.direction == SwipeDirection.N }
        assertConsistent(all, "a")
        assertThat(indexedGetMappingsForKey(all, "a").keys).containsExactly(SwipeDirection.S)
    }

    @Test
    fun `removeMappingsForKey mutation - whole key drops out`() {
        val all = mutableListOf(
            mapping("a", SwipeDirection.N),
            mapping("a", SwipeDirection.S),
            mapping("b", SwipeDirection.E),
        )
        // Simulate removeMappingsForKey("a")
        all.removeAll { it.keyCode == "a" }
        assertConsistent(all, "a", "b")
        assertThat(indexedGetMappingsForKey(all, "a")).isEmpty()
        assertThat(indexedGetMappingsForKey(all, "b").keys).containsExactly(SwipeDirection.E)
    }

    @Test
    fun `clear mutation - index empties`() {
        val all = mutableListOf(
            mapping("a", SwipeDirection.N),
            mapping("b", SwipeDirection.S),
        )
        all.clear() // Simulate resetAll()
        assertThat(ShortSwipeCustomizationManager.buildByKeyIndex(all)).isEmpty()
        assertConsistent(all, "a", "b")
    }

    @Test
    fun `import replace mutation - old keys gone, new keys present`() {
        val before = listOf(
            mapping("a", SwipeDirection.N),
            mapping("b", SwipeDirection.S),
        )
        // Simulate importFromJson(merge=false): cache cleared then repopulated.
        val after = listOf(
            mapping("c", SwipeDirection.E),
            mapping("c", SwipeDirection.W),
        )
        assertConsistent(before, "a", "b", "c")
        assertConsistent(after, "a", "b", "c")
        assertThat(indexedGetMappingsForKey(after, "a")).isEmpty()
        assertThat(indexedGetMappingsForKey(after, "c").keys)
            .containsExactly(SwipeDirection.E, SwipeDirection.W)
    }

    @Test
    fun `import merge mutation - union of keys`() {
        // Simulate importFromMappings(merge=true): existing kept, new added/overwritten by key.
        val merged = listOf(
            mapping("a", SwipeDirection.N),   // existing
            mapping("b", SwipeDirection.S),   // imported
            mapping("a", SwipeDirection.E),   // imported extra direction on existing key
        )
        assertConsistent(merged, "a", "b", "z")
        assertThat(indexedGetMappingsForKey(merged, "a").keys)
            .containsExactly(SwipeDirection.N, SwipeDirection.E)
    }

    @Test
    fun `full 8-direction key across a realistic mix stays consistent`() {
        val all = SwipeDirection.entries.map { mapping("a", it, display = it.name.take(2)) } +
            listOf(mapping("b", SwipeDirection.N), mapping("space", SwipeDirection.S))
        assertConsistent(all, "a", "b", "space", "missing")
        assertThat(indexedGetMappingsForKey(all, "a").keys)
            .containsExactlyElementsIn(SwipeDirection.entries)
    }

    @Test
    fun `index inner maps are equal in value to associateBy for the same mapping instances`() {
        val all = listOf(
            mapping("a", SwipeDirection.N),
            mapping("a", SwipeDirection.S),
            mapping("b", SwipeDirection.E),
        )
        val index = ShortSwipeCustomizationManager.buildByKeyIndex(all)
        // Value identity: the index must hold the SAME mapping instances (no copies).
        assertThat(index["a"]!![SwipeDirection.N]).isSameInstanceAs(all[0])
        assertThat(index["a"]!![SwipeDirection.S]).isSameInstanceAs(all[1])
        assertThat(index["b"]!![SwipeDirection.E]).isSameInstanceAs(all[2])
    }
}
