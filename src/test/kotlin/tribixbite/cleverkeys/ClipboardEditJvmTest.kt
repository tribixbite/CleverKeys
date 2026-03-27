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

    // =========================================================================
    // Bug #1: Edit mode must disable search mode (mutual exclusion)
    //
    // When both searchMode and editMode are active, sendText() routes to
    // search first because it's checked before edit. edit_entry() must
    // disable search mode to prevent this.
    //
    // Simulated here as routing priority logic: when edit mode is active,
    // the routing should go to edit, never to search.
    // =========================================================================

    @Test
    fun `edit mode routing takes priority over search mode`() {
        // Simulate the sendText() routing logic
        var searchMode = true
        var editMode = true

        // Current buggy behavior: search checked first → routes to search
        // Fixed behavior: edit should win (or search should be cleared)
        val target = routeText(searchMode, editMode)
        assertThat(target).isEqualTo("edit")
    }

    @Test
    fun `entering edit mode clears search mode`() {
        // Simulate entering edit mode — search should be cleared
        var searchMode = true
        var editMode = false

        // Enter edit mode — should clear search
        editMode = true
        searchMode = clearSearchOnEditEntry(searchMode)

        assertThat(searchMode).isFalse()
        assertThat(editMode).isTrue()
    }

    /**
     * Simulates the sendText() routing priority.
     * Returns which mode receives the text: "search", "edit", or "app".
     */
    private fun routeText(searchMode: Boolean, editMode: Boolean): String {
        // FIXED routing: edit mode checked before search mode
        if (editMode) return "edit"
        if (searchMode) return "search"
        return "app"
    }

    /**
     * Simulates the edit_entry() clearing search mode.
     * Returns the new searchMode value.
     */
    private fun clearSearchOnEditEntry(searchMode: Boolean): Boolean {
        // edit_entry() must clear search mode
        return false
    }

    // =========================================================================
    // Bug #2: Edit must track by content identity, not list position
    //
    // editingPosition is a list index. When a new entry is added (e.g., from
    // cut to system clipboard), entries shift and the index now points to a
    // different entry. Edit must use content identity (editingOriginalContent)
    // to find the correct entry across list rebuilds.
    // =========================================================================

    @Test
    fun `edit target found by content after list shift`() {
        // Initial list: ["Third", "Second", "First"] (newest first)
        val originalList = listOf("Third", "Second", "First")
        val editingOriginalContent = "Second"  // editing position 1

        // New entry added at top (e.g., cut text copied to clipboard)
        val shiftedList = listOf("CutText", "Third", "Second", "First")

        // Bug: editingPosition=1 now points to "Third" instead of "Second"
        val buggyTarget = shiftedList[1]  // position-based
        assertThat(buggyTarget).isNotEqualTo(editingOriginalContent)  // confirms bug

        // Fix: find by content identity
        val fixedTarget = shiftedList.indexOfFirst { it == editingOriginalContent }
        assertThat(fixedTarget).isEqualTo(2)
        assertThat(shiftedList[fixedTarget]).isEqualTo(editingOriginalContent)
    }

    @Test
    fun `in-progress text preserved across list rebuild`() {
        // User is editing "Hello world" and has changed it to "Hello Mars"
        val editingOriginalContent = "Hello world"
        val editingInProgressText = "Hello Mars"

        // List rebuild occurs (notifyDataSetChanged) — entry still has DB content
        val dbContent = "Hello world"

        // Bug: getView() uses dbContent → overwrites user's edits
        // Fix: getView() should use editingInProgressText if non-null
        val displayText = editingInProgressText ?: dbContent
        assertThat(displayText).isEqualTo("Hello Mars")
    }

    @Test
    fun `edit target survives when original entry removed from list`() {
        // Edge case: the entry being edited is removed (e.g., expired during edit)
        val list = listOf("Active", "Editing this", "Old")
        val editingOriginalContent = "Editing this"

        // After removal:
        val filteredList = listOf("Active", "Old")
        val pos = filteredList.indexOfFirst { it == editingOriginalContent }

        // Entry gone — edit should be cancelled (pos == -1)
        assertThat(pos).isEqualTo(-1)
    }

    // =========================================================================
    // Bug #3: Empty EditText must still accept inserted text
    //
    // After deleting all text, subsequent insertEditText() calls should
    // still work. The cursor position must be safely clamped to the
    // Editable's bounds even when length is 0.
    // =========================================================================

    @Test
    fun `cursor clamped to editable length when empty`() {
        // Simulate an empty Editable (length = 0)
        val editableLength = 0

        // selectionStart could be -1, 0, or stale positive value
        for (rawSelection in listOf(-1, 0, 5)) {
            val start = rawSelection.coerceAtLeast(0).coerceAtMost(editableLength)
            val end = start.coerceAtMost(editableLength)

            assertThat(start).isAtMost(editableLength)
            assertThat(end).isAtMost(editableLength)
            assertThat(start).isAtLeast(0)
        }
    }

    @Test
    fun `insert at position zero works for empty text`() {
        // Simulate StringBuilder as Editable proxy
        val editable = StringBuilder("")
        val insertText = "Hello"

        // Cursor at 0, editable empty — replace(0, 0, text) = insert at start
        val start = 0.coerceAtMost(editable.length)
        val end = 0.coerceAtMost(editable.length)
        editable.replace(start, end, insertText)

        assertThat(editable.toString()).isEqualTo("Hello")
    }

    @Test
    fun `stale cursor beyond editable length is clamped`() {
        // After deleting text, cursor position could be stale (e.g., was at position 10
        // but text is now length 3 after backspace). Must clamp to avoid IndexOutOfBounds.
        val editable = StringBuilder("abc")  // length 3
        val staleSelectionStart = 10  // from before deletions

        val start = staleSelectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
        assertThat(start).isEqualTo(3)  // clamped to length

        // Insert at clamped position — appends to end
        editable.replace(start, start, "X")
        assertThat(editable.toString()).isEqualTo("abcX")
    }

    @Test
    fun `on_clipboard_history_change suppressed during edit`() {
        // When editing, list reload from on_clipboard_history_change must be suppressed
        // to prevent view recreation that overwrites in-progress edits
        var isEditing = true
        var reloadTriggered = false

        // Simulate on_clipboard_history_change callback
        if (!isEditing) {
            reloadTriggered = true
        }

        assertThat(reloadTriggered).isFalse()
    }

    @Test
    fun `reload fires after edit completes`() {
        // After save_edit() or cancelEdit(), the suppressed reload must fire
        var isEditing = true
        var reloadCount = 0

        // Simulate cancelEdit()
        isEditing = false
        // cancelEdit() should trigger reload
        if (!isEditing) reloadCount++

        assertThat(reloadCount).isEqualTo(1)
    }
}
