package tribixbite.cleverkeys

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a clipboard entry with content and timestamp
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
            else -> formatDate()
        }
    }

    /**
     * Format timestamp as date string (e.g., "Nov 12")
     */
    fun formatDate(): String {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Get formatted text with timestamp appended
     * Returns SpannableString with timestamp in secondary color
     */
    fun getFormattedText(context: Context): SpannableString {
        val timeStr = " · ${getRelativeTime()}"
        val fullText = content + timeStr

        val spannable = SpannableString(fullText)

        // Apply secondary text color to timestamp portion
        val secondaryColor = context.resources.getColor(android.R.color.secondary_text_dark)
        spannable.setSpan(
            ForegroundColorSpan(secondaryColor),
            content.length,
            fullText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return spannable
    }
}
