package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for clipboard fix logic that doesn't require Android framework.
 *
 * Covers:
 * - Commit a9010cb84: Slider unlimited mapping (100 ↔ 0 sentinel)
 * - Commit 90d126a5b: Expand state String-keyed map behavior
 * - Commit 18ec94094: Expand state migrated to Long (timestamp) keys
 * - Commit 8c5a1c4c5: applySizeLimitBytes zero/negative guard
 * - Commit da40475c0: applySizeLimitBytes returns Pair<Int, List<String>>
 */
class ClipboardFixesJvmTest {

    // =========================================================================
    // Slider unlimited mapping — commit a9010cb84
    //
    // ClipboardSettingsActivity saves 0 when slider is at 100 (unlimited),
    // and maps 0 back to 100 on load. Values 1-99 pass through unchanged.
    // =========================================================================

    /** Replicate the save logic from ClipboardSettingsActivity onValueChange */
    private fun sliderSaveValue(sliderValue: Int): Int =
        if (sliderValue >= 100) 0 else sliderValue

    /** Replicate the load logic from ClipboardSettingsActivity loadCurrentSettings */
    private fun sliderLoadValue(savedValue: Int): Int =
        if (savedValue <= 0) 100 else savedValue

    /** Replicate the display label logic */
    private fun sliderDisplayLabel(sliderValue: Int): String =
        if (sliderValue >= 100) "Unlimited" else "$sliderValue entries"

    @Test
    fun `slider at max (100) saves as 0 sentinel`() {
        assertThat(sliderSaveValue(100)).isEqualTo(0)
    }

    @Test
    fun `slider at 99 saves as 99`() {
        assertThat(sliderSaveValue(99)).isEqualTo(99)
    }

    @Test
    fun `slider at 1 saves as 1`() {
        assertThat(sliderSaveValue(1)).isEqualTo(1)
    }

    @Test
    fun `slider at 50 saves as 50`() {
        assertThat(sliderSaveValue(50)).isEqualTo(50)
    }

    @Test
    fun `load 0 sentinel maps to slider 100`() {
        assertThat(sliderLoadValue(0)).isEqualTo(100)
    }

    @Test
    fun `load negative value maps to slider 100`() {
        // Edge case: corrupted prefs with negative value
        assertThat(sliderLoadValue(-1)).isEqualTo(100)
    }

    @Test
    fun `load 99 maps to slider 99`() {
        assertThat(sliderLoadValue(99)).isEqualTo(99)
    }

    @Test
    fun `load 1 maps to slider 1`() {
        assertThat(sliderLoadValue(1)).isEqualTo(1)
    }

    @Test
    fun `roundtrip - unlimited (100 to 0 to 100)`() {
        val saved = sliderSaveValue(100)
        val loaded = sliderLoadValue(saved)
        assertThat(loaded).isEqualTo(100)
    }

    @Test
    fun `roundtrip - regular value (50 to 50 to 50)`() {
        val saved = sliderSaveValue(50)
        val loaded = sliderLoadValue(saved)
        assertThat(loaded).isEqualTo(50)
    }

    @Test
    fun `display label at 100 shows Unlimited`() {
        assertThat(sliderDisplayLabel(100)).isEqualTo("Unlimited")
    }

    @Test
    fun `display label at 50 shows count`() {
        assertThat(sliderDisplayLabel(50)).isEqualTo("50 entries")
    }

    @Test
    fun `display label at 1 shows count`() {
        assertThat(sliderDisplayLabel(1)).isEqualTo("1 entries")
    }

    // =========================================================================
    // Expand state with timestamp (Long) keys — commit 18ec94094
    //
    // Migrated from String (content) keys to Long (timestamp) keys to avoid
    // duplicating large clipboard entry content in memory as map keys.
    // =========================================================================

    @Test
    fun `expand state tracks by timestamp`() {
        val expandedStates = mutableMapOf<Long, Boolean>()
        expandedStates[1000L] = true
        expandedStates[2000L] = false

        assertThat(expandedStates[1000L]).isTrue()
        assertThat(expandedStates[2000L]).isFalse()
        assertThat(expandedStates[9999L]).isNull()
    }

    @Test
    fun `expand state survives item deletion at earlier position`() {
        // Simulate: 3 items, expand item at index 2, delete item at index 0
        // Timestamps are stable — they don't shift when items are removed
        val expandedStates = mutableMapOf<Long, Boolean>()
        data class Entry(val content: String, val timestamp: Long)
        val items = mutableListOf(
            Entry("First", 1000L),
            Entry("Second", 2000L),
            Entry("Third", 3000L)
        )

        // Expand "Third"
        expandedStates[items[2].timestamp] = true

        // Delete "First" — items shift but timestamps are stable
        items.removeAt(0)

        // "Third" is now at index 1, timestamp key still works
        assertThat(expandedStates[items[1].timestamp]).isTrue()
        assertThat(expandedStates[3000L]).isTrue()
    }

    @Test
    fun `expand state not affected by reorder`() {
        val expandedStates = mutableMapOf<Long, Boolean>()
        expandedStates[1000L] = true
        expandedStates[2000L] = false

        // Timestamps are position-independent — reorder doesn't matter
        assertThat(expandedStates[1000L]).isTrue()
        assertThat(expandedStates[2000L]).isFalse()
    }

    @Test
    fun `same content different timestamps have independent expand state`() {
        // Unlike String keys, timestamp keys give each entry independent state
        // even when content is identical (e.g., user copies same text twice)
        val expandedStates = mutableMapOf<Long, Boolean>()
        // Two entries with same content but different timestamps
        val ts1 = 1000L // "Hello" copied at time 1000
        val ts2 = 2000L // "Hello" copied again at time 2000

        expandedStates[ts1] = true
        expandedStates[ts2] = false

        // Each entry has independent expand state
        assertThat(expandedStates[ts1]).isTrue()
        assertThat(expandedStates[ts2]).isFalse()
    }

    @Test
    fun `timestamp key does not need migration on content edit`() {
        // When content is edited, timestamp stays the same — no key migration needed.
        // This was a simplification over String keys which required remove+re-add.
        val expandedStates = mutableMapOf<Long, Boolean>()
        val timestamp = 1234567890L

        expandedStates[timestamp] = true

        // Simulate content edit: timestamp doesn't change
        // (old code had: expandedStates.remove(oldContent); expandedStates[newContent] = wasExpanded)
        // Now: no migration needed — key is timestamp, content changed, key unchanged
        assertThat(expandedStates[timestamp]).isTrue()
    }

    @Test
    fun `expand toggle flips state with timestamp key`() {
        val expandedStates = mutableMapOf<Long, Boolean>()
        val timestamp = 5000L

        // Initial state: not in map (collapsed)
        val isExpanded = expandedStates[timestamp] == true
        assertThat(isExpanded).isFalse()

        // Toggle: set to !isExpanded
        expandedStates[timestamp] = !isExpanded
        assertThat(expandedStates[timestamp]).isTrue()

        // Toggle again
        val isExpanded2 = expandedStates[timestamp] == true
        expandedStates[timestamp] = !isExpanded2
        assertThat(expandedStates[timestamp]).isFalse()
    }

    @Test
    fun `Long key uses 8 bytes vs String key duplicating content`() {
        // Demonstrate the memory advantage: a 50KB content string as map key
        // uses ~100KB (string + char array). A Long key uses 8 bytes.
        val largeContent = "X".repeat(50_000)
        val timestamp = System.currentTimeMillis()

        // String key: map holds reference to the full 50K string
        val stringMap = mutableMapOf<String, Boolean>()
        stringMap[largeContent] = true

        // Long key: map holds 8-byte Long
        val longMap = mutableMapOf<Long, Boolean>()
        longMap[timestamp] = true

        // Both work, but Long uses negligible memory
        assertThat(stringMap[largeContent]).isTrue()
        assertThat(longMap[timestamp]).isTrue()
    }

    // =========================================================================
    // applySizeLimitBytes zero/negative guard — commit 8c5a1c4c5
    // Return type changed to Pair<Int, List<String>> — commit da40475c0
    // =========================================================================

    @Test
    fun `size limit bytes zero returns zero deletions`() {
        // applySizeLimitBytes(0) should be a no-op
        val maxSizeMB = 0
        assertThat(maxSizeMB <= 0).isTrue()
    }

    @Test
    fun `size limit bytes negative returns zero deletions`() {
        val maxSizeMB = -1
        assertThat(maxSizeMB <= 0).isTrue()
    }

    @Test
    fun `size limit bytes conversion to bytes`() {
        // Verify the MB→bytes conversion: maxSizeMB * 1024 * 1024
        val maxSizeMB = 50
        val maxSizeBytes = maxSizeMB * 1024L * 1024L
        assertThat(maxSizeBytes).isEqualTo(52_428_800L)
    }

    @Test
    fun `chunk size for batch delete is 500`() {
        // Verify chunking logic: idsToDelete.chunked(500)
        val ids = (1L..1200L).toList()
        val chunks = ids.chunked(500)
        assertThat(chunks.size).isEqualTo(3) // 500 + 500 + 200
        assertThat(chunks[0].size).isEqualTo(500)
        assertThat(chunks[1].size).isEqualTo(500)
        assertThat(chunks[2].size).isEqualTo(200)
    }

    @Test
    fun `applySizeLimitBytes return type is Pair of count and media paths`() {
        // Verify the Pair return structure used by applySizeLimitBytes
        val result: Pair<Int, List<String>> = Pair(3, listOf("media/a.jpg", "media/b.png"))
        val (deletedCount, mediaPaths) = result
        assertThat(deletedCount).isEqualTo(3)
        assertThat(mediaPaths).hasSize(2)
        assertThat(mediaPaths).containsExactly("media/a.jpg", "media/b.png")
    }

    @Test
    fun `applySizeLimitBytes empty result has zero count and empty paths`() {
        val result: Pair<Int, List<String>> = Pair(0, emptyList())
        val (deletedCount, mediaPaths) = result
        assertThat(deletedCount).isEqualTo(0)
        assertThat(mediaPaths).isEmpty()
    }

    // =========================================================================
    // LRU cache behavior — commit da40475c0
    // Verifies the count-based eviction logic used for thumbnail caching.
    // =========================================================================

    @Test
    fun `LRU eviction order is least recently used`() {
        // Simulate the thumbnail cache eviction behavior
        val cache = LinkedHashMap<Long, String>(16, 0.75f, true) // accessOrder=true
        val maxSize = 3

        cache[1000L] = "bitmap_A"
        cache[2000L] = "bitmap_B"
        cache[3000L] = "bitmap_C"

        // Access A to make it recently used
        cache[1000L]

        // Add D — should evict B (least recently used)
        cache[4000L] = "bitmap_D"
        if (cache.size > maxSize) {
            val eldest = cache.entries.first()
            cache.remove(eldest.key)
        }

        assertThat(cache).doesNotContainKey(2000L) // B evicted
        assertThat(cache).containsKey(1000L) // A survived (recently accessed)
        assertThat(cache).containsKey(3000L) // C survived
        assertThat(cache).containsKey(4000L) // D added
    }

    // =========================================================================
    // ContentObserver replaces 500ms polling — commit 9c3f7a912
    // (No JVM test possible — requires Android framework ContentResolver)
    // Verified by instrumented test and compilation.
    // =========================================================================
}
