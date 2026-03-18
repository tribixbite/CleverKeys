package tribixbite.cleverkeys

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a clipboard entry with content and timestamp.
 */
class ClipboardEntry(
    @JvmField val content: String,
    @JvmField val timestamp: Long // Unix timestamp in milliseconds
) {
    /**
     * Format timestamp as relative time (e.g., "2h ago", "Yesterday")
     */
    fun getRelativeTime(): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

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
            else -> DATE_FORMAT.format(Date(timestamp))
        }
    }

    /**
     * Format timestamp as date string (e.g., "Nov 12")
     */
    fun formatDate(): String {
        return DATE_FORMAT.format(Date(timestamp))
    }

    /**
     * Get formatted text with timestamp appended.
     * Uses SpannableStringBuilder to avoid intermediate String allocation —
     * content is appended directly without concatenation copy.
     * Color is cached to avoid repeated resource lookups.
     */
    fun getFormattedText(context: Context): Spannable {
        val timeStr = " · ${getRelativeTime()}"
        val contentLen = content.length

        // Append directly to builder — avoids content + timeStr intermediate String
        val spannable = SpannableStringBuilder(content).append(timeStr)

        spannable.setSpan(
            ForegroundColorSpan(getSecondaryColor(context)),
            contentLen,
            contentLen + timeStr.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return spannable
    }

    companion object {
        // Reuse across all instances — all calls are on Main thread (no thread-safety needed)
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())

        // Cache the secondary text color to avoid per-call resource lookups
        private var cachedSecondaryColor: Int? = null

        private fun getSecondaryColor(context: Context): Int {
            return cachedSecondaryColor ?: ContextCompat.getColor(
                context, android.R.color.secondary_text_dark
            ).also { cachedSecondaryColor = it }
        }
    }
}
