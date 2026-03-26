package tribixbite.cleverkeys

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing an entry in the independent todo_entries table.
 *
 * Todo entries are COPIES of clipboard content with status tracking.
 * They have their own lifecycle, independent of clipboard history.
 *
 * Status workflow: active → planned → completed (or any transition).
 * Position is REAL (Double) for midpoint insertion during drag-and-drop reordering.
 */
data class TodoEntry(
    val id: Long,
    val content: String,
    val contentHash: String,
    val createdTimestamp: Long,   // original clipboard copy time (millis)
    val addedTimestamp: Long,     // when added to todo list (millis)
    val position: Double,        // sort order (REAL for midpoint insertion)
    val status: String = STATUS_ACTIVE,
    val tags: List<String> = emptyList()
) {
    /** Whether this todo is marked completed */
    val isCompleted: Boolean get() = status == STATUS_COMPLETED

    /** Whether this todo is in planned state */
    val isPlanned: Boolean get() = status == STATUS_PLANNED

    /** Whether this todo is active */
    val isActive: Boolean get() = status == STATUS_ACTIVE

    /** Format added time as relative time (e.g., "2h ago", "Yesterday") */
    fun getRelativeTime(): String {
        val now = System.currentTimeMillis()
        val diff = now - addedTimestamp

        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> DATE_FORMAT.format(Date(addedTimestamp))
        }
    }

    /** Format added timestamp as date string (e.g., "Nov 12") */
    fun formatDate(): String = DATE_FORMAT.format(Date(addedTimestamp))

    /**
     * Get formatted text with status indicator and time appended.
     * Completed items get strikethrough styling.
     * Status prefix: [active] none, [planned] clock, [completed] check.
     */
    fun getFormattedText(context: Context): Spannable {
        val statusPrefix = when (status) {
            STATUS_COMPLETED -> "[done] "
            STATUS_PLANNED -> "[plan] "
            else -> ""
        }
        val timeStr = " · ${getRelativeTime()}"
        val fullContent = "$statusPrefix$content"
        val contentLen = fullContent.length

        val spannable = SpannableStringBuilder(fullContent).append(timeStr)

        // Strikethrough for completed items (content only, not timestamp)
        if (isCompleted) {
            spannable.setSpan(
                StrikethroughSpan(),
                statusPrefix.length,
                statusPrefix.length + content.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Gray timestamp
        spannable.setSpan(
            ForegroundColorSpan(getSecondaryColor(context)),
            contentLen,
            contentLen + timeStr.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /** Convert to ClipboardEntry for backward-compatible view rendering */
    fun toClipboardEntry(): ClipboardEntry = ClipboardEntry(content, addedTimestamp)

    /** Serialize tags to JSON string for DB storage */
    fun tagsToJson(): String {
        val arr = JSONArray()
        tags.forEach { arr.put(it) }
        return arr.toString()
    }

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_PLANNED = "planned"
        const val STATUS_COMPLETED = "completed"

        /** All valid status values */
        val VALID_STATUSES = setOf(STATUS_ACTIVE, STATUS_PLANNED, STATUS_COMPLETED)

        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())

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
