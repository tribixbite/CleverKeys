package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a clipboard entry with content and timestamp.
 * v4 adds media support: mimeType, thumbnailBlob, mediaPath for non-text content.
 */
class ClipboardEntry(
    @JvmField val content: String,
    @JvmField val timestamp: Long, // Unix timestamp in milliseconds
    @JvmField val mimeType: String = MIME_TEXT_PLAIN,
    @JvmField val thumbnailBlob: ByteArray? = null,
    @JvmField val mediaPath: String? = null,
    // Optional fields carried from pinned_entries/todo_entries tables for view rendering.
    // Defaults preserve backward compat — history entries get emptyList() + null.
    @JvmField val tags: List<String> = emptyList(),
    @JvmField val todoStatus: String? = null,
    // #156: private-copy marker. isPrivate → 🔒 badge + confirm-before-OS-clipboard.
    // sourcePackage → provenance line ("via <app>" / "direct launch"). Defaults preserve compat.
    @JvmField val isPrivate: Boolean = false,
    @JvmField val sourcePackage: String? = null,
    @JvmField val rowId: Long = 0,
    @JvmField val sizeBytes: Long = ClipboardSizePolicy.utf8Bytes(content) + (thumbnailBlob?.size ?: 0)
) {
    /** Immutable IO-enriched copy; payload size excludes SQLite overhead and shared-file accounting. */
    fun withSizeBytes(bytes: Long) = ClipboardEntry(content, timestamp, mimeType, thumbnailBlob,
        mediaPath, tags, todoStatus, isPrivate, sourcePackage, rowId, bytes)

    /** Whether this entry contains non-text media (image, video, PDF, etc.) */
    val isMedia: Boolean get() = mimeType != MIME_TEXT_PLAIN

    /** Whether this entry is an image (JPEG, PNG, WebP, GIF, etc.) */
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /** Whether this entry is a video */
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** Whether this entry is a PDF document */
    val isPdf: Boolean get() = mimeType == "application/pdf"

    /** Whether this entry has a renderable thumbnail */
    val hasThumbnail: Boolean get() = thumbnailBlob != null
    /** Age of this entry as a locale-free value; resolve with [RelativeTime.format]. */
    fun relativeTime(): RelativeTime = RelativeTime.of(timestamp)

    /** Localized relative age (e.g. "2h ago", "Yesterday"), shown after the entry text. */
    fun getRelativeTime(resources: Resources): String = relativeTime().format(resources)

    /**
     * Format timestamp as date string (e.g., "Nov 12")
     */
    fun formatDate(): String {
        return dateFormat().format(Date(timestamp))
    }

    /** Shared row metadata for text, media and todo rendering. */
    fun metadataText(context: Context): String = "\u00A0\u00B7\u00A0" +
        getRelativeTime(context.resources).replace(' ', '\u00A0') + "\u00A0\u00B7\u00A0" +
        android.text.format.Formatter.formatShortFileSize(context, sizeBytes).replace(' ', '\u00A0')

    /**
     * Get formatted text with timestamp appended.
     * Uses SpannableStringBuilder to avoid intermediate String allocation —
     * content is appended directly without concatenation copy.
     * Color is cached to avoid repeated resource lookups.
     */
    fun getFormattedText(context: Context): Spannable {
        // Use non-breaking spaces (\u00A0) so the time suffix never wraps mid-unit
        val timeStr = metadataText(context)
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
        const val MIME_TEXT_PLAIN = "text/plain"

        // Built per call so the user's current default locale is honored even
        // after a runtime locale change (SimpleDateFormat is not thread-safe, so
        // a fresh instance also avoids sharing mutable state across threads).
        internal fun dateFormat() = SimpleDateFormat("MMM d", Locale.getDefault())

        // Cache the secondary text color to avoid per-call resource lookups
        private var cachedSecondaryColor: Int? = null

        private fun getSecondaryColor(context: Context): Int {
            return cachedSecondaryColor ?: ContextCompat.getColor(
                context, android.R.color.secondary_text_dark
            ).also { cachedSecondaryColor = it }
        }
    }
}

/**
 * Relative age of a clipboard / pinned / todo entry, shared by [ClipboardEntry], [PinnedEntry]
 * and [TodoEntry] (which previously each carried a copy of the same English-only formatter).
 *
 * The bucketing is pure and locale-free so it can be asserted without resources; [format]
 * resolves the user-facing text from string/plural resources in the app locale. Buckets:
 * under 60 s → [JustNow]; under 60 min → [MinutesAgo]; under 24 h → [HoursAgo];
 * exactly one whole day → [Yesterday]; under 7 days → [DaysAgo]; otherwise [OnDate].
 */
sealed class RelativeTime {
    object JustNow : RelativeTime()
    data class MinutesAgo(val minutes: Int) : RelativeTime()
    data class HoursAgo(val hours: Int) : RelativeTime()
    object Yesterday : RelativeTime()
    data class DaysAgo(val days: Int) : RelativeTime()

    /** Seven days or older: shown as a short date ("MMM d" in the default locale). */
    data class OnDate(val timestamp: Long) : RelativeTime()

    /** Localized display text for this age. */
    fun format(resources: Resources): String = when (this) {
        JustNow -> resources.getString(R.string.clipboard_time_just_now)
        is MinutesAgo -> resources.getQuantityString(R.plurals.clipboard_time_minutes_ago, minutes, minutes)
        is HoursAgo -> resources.getQuantityString(R.plurals.clipboard_time_hours_ago, hours, hours)
        Yesterday -> resources.getString(R.string.clipboard_time_yesterday)
        is DaysAgo -> resources.getQuantityString(R.plurals.clipboard_time_days_ago, days, days)
        is OnDate -> ClipboardEntry.dateFormat().format(Date(timestamp))
    }

    companion object {
        /** Bucket the age of [timestamp] (epoch millis) relative to [now]. */
        fun of(timestamp: Long, now: Long = System.currentTimeMillis()): RelativeTime {
            val seconds = (now - timestamp) / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            return when {
                seconds < 60 -> JustNow
                minutes < 60 -> MinutesAgo(minutes.toInt())
                hours < 24 -> HoursAgo(hours.toInt())
                days == 1L -> Yesterday
                days < 7 -> DaysAgo(days.toInt())
                else -> OnDate(timestamp)
            }
        }
    }
}

/** Allocation-free UTF-8 payload sizing, including Java's one-byte replacement for lone surrogates. */
object ClipboardSizePolicy {
    fun utf8Bytes(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i < text.length && text[i].isLowSurrogate() -> { i++; 4 }
                c.isSurrogate() -> 1
                else -> 3
            }
        }
        return bytes
    }

    /** Inclusive bounds; null maximum means unlimited. */
    fun matches(bytes: Long, minimum: Long, maximum: Long?): Boolean =
        bytes >= minimum && (maximum == null || bytes <= maximum)
}

/** Frozen confirmation scope: current tab, ALL matching pages, never a live query. */
data class ClipboardDeleteSnapshot(val tab: ClipboardTab, val entries: List<ClipboardEntry>) {
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }
}
