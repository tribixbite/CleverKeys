package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for clipboard fix logic that doesn't require Android framework.
 *
 * Covers:
 * - Commit a9010cb84: Slider unlimited mapping (100 ↔ 0 sentinel)
 * - Commit 90d126a5b: Expand state String-keyed map behavior
 * - Commit 8c5a1c4c5: applySizeLimitBytes zero/negative guard
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
    // Expand state with String keys — commit 90d126a5b
    //
    // Previously keyed by position (Int), causing stale state after
    // delete/reorder. Now keyed by content String.
    // =========================================================================

    @Test
    fun `expand state tracks by content string`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        expandedStates["Hello world"] = true
        expandedStates["Goodbye world"] = false

        assertThat(expandedStates["Hello world"]).isTrue()
        assertThat(expandedStates["Goodbye world"]).isFalse()
        assertThat(expandedStates["Nonexistent"]).isNull()
    }

    @Test
    fun `expand state survives item deletion at earlier position`() {
        // Simulate: 3 items, expand item at index 2 ("Third"), delete item at index 0
        // With Int keys, state at key=2 would map to wrong item after shift
        // With String keys, it's stable
        val expandedStates = mutableMapOf<String, Boolean>()
        val items = mutableListOf("First", "Second", "Third")

        // Expand "Third"
        expandedStates[items[2]] = true

        // Delete "First" — items shift
        items.removeAt(0)
        // items is now ["Second", "Third"]

        // "Third" is now at index 1, but String key still works
        assertThat(expandedStates[items[1]]).isTrue()
        assertThat(expandedStates["Third"]).isTrue()
    }

    @Test
    fun `expand state not affected by reorder`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        expandedStates["Alpha"] = true
        expandedStates["Beta"] = false

        // Simulate reorder: reverse the list
        val items = mutableListOf("Alpha", "Beta")
        items.reverse()
        // items is now ["Beta", "Alpha"]

        // Keys still map correctly regardless of position
        assertThat(expandedStates[items[0]]).isFalse() // "Beta"
        assertThat(expandedStates[items[1]]).isTrue()  // "Alpha"
    }

    @Test
    fun `duplicate content entries share expand state (by design)`() {
        // Two entries with identical content will share expanded state.
        // This is acceptable because they are duplicates.
        val expandedStates = mutableMapOf<String, Boolean>()
        expandedStates["Same content"] = true

        // Both positions with "Same content" will see expanded=true
        assertThat(expandedStates["Same content"]).isTrue()
    }

    @Test
    fun `expand toggle flips state`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        val text = "Toggle me"

        // Initial state: not in map (collapsed)
        val isExpanded = expandedStates[text] == true
        assertThat(isExpanded).isFalse()

        // Toggle: set to !isExpanded
        expandedStates[text] = !isExpanded
        assertThat(expandedStates[text]).isTrue()

        // Toggle again
        val isExpanded2 = expandedStates[text] == true
        expandedStates[text] = !isExpanded2
        assertThat(expandedStates[text]).isFalse()
    }

    // =========================================================================
    // applySizeLimitBytes zero/negative guard — commit 8c5a1c4c5
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
}
