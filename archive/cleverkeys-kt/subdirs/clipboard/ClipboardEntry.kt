package tribixbite.keyboard2.clipboard

import java.util.UUID

/**
 * Data model for clipboard history entry.
 *
 * Represents a single item in the clipboard history with:
 * - Unique ID for stable list keys
 * - Text content
 * - Timestamp for ordering
 * - Pin status for favorites
 *
 * Fixes Clipboard Bug #1: Proper data model (was just List<String>)
 */
data class ClipboardEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
) {
    /**
     * Preview text for display (first 100 characters).
     */
    val preview: String
        get() = if (text.length > 100) text.take(100) + "..." else text

    /**
     * Line count for multi-line entries.
     */
    val lineCount: Int
        get() = text.count { it == '\n' } + 1

    companion object {
        /**
         * Create from raw clipboard text.
         */
        fun from(text: String): ClipboardEntry {
            return ClipboardEntry(text = text)
        }

        /**
         * Create pinned entry.
         */
        fun pinned(text: String): ClipboardEntry {
            return ClipboardEntry(text = text, isPinned = true)
        }
    }
}
