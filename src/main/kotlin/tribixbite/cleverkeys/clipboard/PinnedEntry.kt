package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.Date

/**
 * Data class representing an entry in the independent pinned_entries table.
 *
 * Pinned entries are COPIES of clipboard content — they have their own lifecycle,
 * independent of the clipboard history. Deleting from history does not affect pins.
 *
 * Position is REAL (Double) to allow midpoint insertion for drag-and-drop reordering
 * (e.g., insert between position 3.0 and 4.0 → 3.5) without reindexing all rows.
 */
data class PinnedEntry(
    val id: Long,
    val content: String,
    val contentHash: String,
    val createdTimestamp: Long,   // original clipboard copy time (millis)
    val pinnedTimestamp: Long,    // when user pinned it (millis)
    val position: Double,        // sort order (REAL for midpoint insertion)
    val tags: List<String> = emptyList(),
    val mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
    val thumbnailBlob: ByteArray? = null,
    val mediaPath: String? = null,
    val isPrivate: Boolean = false,     // #156: private-copy marker (COPY semantics — travels on pin)
    val sourcePackage: String? = null   // #156: provenance
) {
    /** Whether this entry contains non-text media */
    val isMedia: Boolean get() = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
    /** Age of this entry (pinned time) as a locale-free value; resolve with [RelativeTime.format]. */
    fun relativeTime(): RelativeTime = RelativeTime.of(pinnedTimestamp)

    /** Localized relative pinned time (e.g. "2h ago", "Yesterday"), shown after the entry text. */
    fun getRelativeTime(resources: Resources): String = relativeTime().format(resources)

    /** Format pinned timestamp as date string (e.g., "Nov 12") */
    fun formatDate(): String = ClipboardEntry.dateFormat().format(Date(pinnedTimestamp))

    /**
     * Get formatted text with pinned time appended.
     * Matches ClipboardEntry.getFormattedText() pattern for consistent UI.
     */
    fun getFormattedText(context: Context): Spannable {
        val timeStr = " · ${getRelativeTime(context.resources)}"
        val contentLen = content.length

        val spannable = SpannableStringBuilder(content).append(timeStr)
        spannable.setSpan(
            ForegroundColorSpan(getSecondaryColor(context)),
            contentLen,
            contentLen + timeStr.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /** Convert to ClipboardEntry for backward-compatible view rendering */
    fun toClipboardEntry(): ClipboardEntry = ClipboardEntry(
        content, pinnedTimestamp, mimeType, thumbnailBlob, mediaPath
    )

    /** Serialize tags to JSON string for DB storage */
    fun tagsToJson(): String {
        val arr = JSONArray()
        tags.forEach { arr.put(it) }
        return arr.toString()
    }

    companion object {
        private var cachedSecondaryColor: Int? = null

        private fun getSecondaryColor(context: Context): Int {
            return cachedSecondaryColor ?: ContextCompat.getColor(
                context, android.R.color.secondary_text_dark
            ).also { cachedSecondaryColor = it }
        }

        /** Parse JSON string to tag list */
        fun tagsFromJson(json: String?): List<String> {
            if (json.isNullOrBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
