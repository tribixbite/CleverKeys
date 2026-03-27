package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Pure JVM tests for clipboard inline edit logic that doesn't require Android framework.
 *
 * Covers:
 * - EditEntryResult sealed class behavior
 * - ExpandedStates key migration during edit
 * - Content hash recomputation for dedup
 * - UTF-8 byte size validation for max_item_size_kb
 * - Trim normalization semantics
 */
class ClipboardEditJvmTest {

    // =========================================================================
    // EditEntryResult — sealed class behavior
    // =========================================================================

    @Test
    fun `EditEntryResult Success is singleton`() {
        val a = EditEntryResult.Success
        val b = EditEntryResult.Success
        assertThat(a).isSameInstanceAs(b)
    }

    @Test
    fun `EditEntryResult DuplicateConflict is singleton`() {
        val a = EditEntryResult.DuplicateConflict
        val b = EditEntryResult.DuplicateConflict
        assertThat(a).isSameInstanceAs(b)
    }

    @Test
    fun `EditEntryResult InvalidContent is singleton`() {
        val a = EditEntryResult.InvalidContent
        val b = EditEntryResult.InvalidContent
        assertThat(a).isSameInstanceAs(b)
    }

    @Test
    fun `EditEntryResult Error carries message`() {
        val error = EditEntryResult.Error("DB locked")
        assertThat(error.message).isEqualTo("DB locked")
    }

    @Test
    fun `EditEntryResult exhaustive when matching`() {
        // Verifies all branches are reachable — compiler enforces exhaustiveness
        val results = listOf(
            EditEntryResult.Success,
            EditEntryResult.DuplicateConflict,
            EditEntryResult.InvalidContent,
            EditEntryResult.Error("test")
        )
        val labels = results.map { result ->
            when (result) {
                is EditEntryResult.Success -> "success"
                is EditEntryResult.DuplicateConflict -> "duplicate"
                is EditEntryResult.InvalidContent -> "invalid"
                is EditEntryResult.Error -> "error:${result.message}"
            }
        }
        assertThat(labels).containsExactly("success", "duplicate", "invalid", "error:test")
    }

    // =========================================================================
    // ExpandedStates key migration — save_edit() logic
    //
    // When content changes from "old" to "new", the expanded state tracked by
    // the old content key must be migrated to the new key.
    // =========================================================================

    @Test
    fun `expandedStates migrates key on edit (expanded)`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        val oldContent = "Original text"
        val newContent = "Edited text"

        // Entry was expanded before edit
        expandedStates[oldContent] = true

        // Simulate save_edit() migration logic
        val wasExpanded = expandedStates.remove(oldContent)
        if (wasExpanded == true) expandedStates[newContent] = true

        assertThat(expandedStates).doesNotContainKey(oldContent)
        assertThat(expandedStates[newContent]).isTrue()
    }

    @Test
    fun `expandedStates migrates key on edit (collapsed)`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        val oldContent = "Original"
        val newContent = "Edited"

        // Entry was explicitly collapsed
        expandedStates[oldContent] = false

        val wasExpanded = expandedStates.remove(oldContent)
        if (wasExpanded == true) expandedStates[newContent] = true

        // Old key removed, new key NOT set (was false, not true)
        assertThat(expandedStates).doesNotContainKey(oldContent)
        assertThat(expandedStates).doesNotContainKey(newContent)
    }

    @Test
    fun `expandedStates no-op when entry was never expanded`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        val oldContent = "Never expanded"
        val newContent = "Edited"

        val wasExpanded = expandedStates.remove(oldContent)
        if (wasExpanded == true) expandedStates[newContent] = true

        assertThat(expandedStates).isEmpty()
    }

    @Test
    fun `expandedStates unaffected entries survive edit`() {
        val expandedStates = mutableMapOf<String, Boolean>()
        expandedStates["Entry A"] = true
        expandedStates["Entry B"] = false
        expandedStates["Entry C"] = true

        // Edit "Entry B" -> "Entry B edited"
        val wasExpanded = expandedStates.remove("Entry B")
        if (wasExpanded == true) expandedStates["Entry B edited"] = true

        // Other entries untouched
        assertThat(expandedStates["Entry A"]).isTrue()
        assertThat(expandedStates["Entry C"]).isTrue()
        assertThat(expandedStates).hasSize(2) // B removed, B-edited not added (was false)
    }

    // =========================================================================
    // Content hash recomputation
    //
    // Database uses content.hashCode().toString() as the content_hash column.
    // After editing, the hash must be recomputed for the index to work correctly.
    // =========================================================================

    @Test
    fun `content hash changes when content changes`() {
        val original = "Hello world"
        val edited = "Hello world!"
        val hashBefore = original.hashCode().toString()
        val hashAfter = edited.hashCode().toString()

        assertThat(hashBefore).isNotEqualTo(hashAfter)
    }

    @Test
    fun `content hash is deterministic`() {
        val text = "Consistent hash"
        val hash1 = text.hashCode().toString()
        val hash2 = text.hashCode().toString()
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `trimmed content produces same hash regardless of whitespace`() {
        val padded = "  hello  "
        val trimmed = padded.trim()
        assertThat(trimmed.hashCode().toString()).isEqualTo("hello".hashCode().toString())
    }

    // =========================================================================
    // UTF-8 byte size validation — service layer size limit check
    //
    // editEntryContent() validates: sizeBytes > maxSizeKb * 1024
    // Uses UTF-8 encoding, so multi-byte chars increase size.
    // =========================================================================

    @Test
    fun `UTF-8 size for ASCII is 1 byte per char`() {
        val text = "Hello"
        val sizeBytes = text.toByteArray(StandardCharsets.UTF_8).size
        assertThat(sizeBytes).isEqualTo(5)
    }

    @Test
    fun `UTF-8 size for emoji is 4 bytes per char`() {
        val emoji = "\uD83D\uDE00" // 😀 is a surrogate pair in Java
        val sizeBytes = emoji.toByteArray(StandardCharsets.UTF_8).size
        assertThat(sizeBytes).isEqualTo(4)
    }

    @Test
    fun `UTF-8 size for CJK is 3 bytes per char`() {
        val cjk = "你好" // 2 CJK characters = 6 bytes
        val sizeBytes = cjk.toByteArray(StandardCharsets.UTF_8).size
        assertThat(sizeBytes).isEqualTo(6)
    }

    @Test
    fun `size limit check rejects content exceeding limit`() {
        val maxSizeKb = 1 // 1 KB limit
        val maxSizeBytes = maxSizeKb * 1024 // 1024 bytes

        // 1025 ASCII chars = 1025 bytes > 1024
        val oversizedContent = "A".repeat(1025)
        val sizeBytes = oversizedContent.toByteArray(StandardCharsets.UTF_8).size
        assertThat(sizeBytes > maxSizeBytes).isTrue()

        // 1024 ASCII chars = exactly 1024 bytes — not over limit
        val exactContent = "A".repeat(1024)
        val exactSize = exactContent.toByteArray(StandardCharsets.UTF_8).size
        assertThat(exactSize > maxSizeBytes).isFalse()
    }

    // =========================================================================
    // Trim normalization — no-op detection
    //
    // editEntryContent() returns Success early if trimmedOld == trimmedNew.
    // This prevents unnecessary DB writes when user adds/removes whitespace.
    // =========================================================================

    @Test
    fun `trim normalization detects no-op edits`() {
        val oldContent = "Hello world"
        val newContent = "  Hello world  "
        assertThat(oldContent.trim()).isEqualTo(newContent.trim())
    }

    @Test
    fun `trim normalization detects real changes`() {
        val oldContent = "Hello world"
        val newContent = "Hello world!"
        assertThat(oldContent.trim()).isNotEqualTo(newContent.trim())
    }

    @Test
    fun `blank content detected correctly`() {
        assertThat("".trim().isBlank()).isTrue()
        assertThat("   ".trim().isBlank()).isTrue()
        assertThat("\t\n".trim().isBlank()).isTrue()
        assertThat("a".trim().isBlank()).isFalse()
    }
}
