package tribixbite.cleverkeys.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.widget.EditText

/**
 * The clipboard pane's inline entry editor (`clipboard_entry_edit_field`), an [EditText] the IME
 * owns: the keyboard writes it with setText / setSelection (ClipboardHistoryView), and touches on
 * it are the platform's own (caret, selection handles, the text toolbar). Two things differ from a
 * stock EditText (maintainer 2026-11-26, a URL that wraps mid-word):
 *
 *  - A tap at the end of a line that wraps inside a word puts the caret AFTER the line's last
 *    character ([getOffsetForPosition] → [SoftWrapTap]); the platform puts it before.
 *  - Every caret / selection change is reported to [selectionListener], so the view's tracked
 *    caret (what a row re-bind puts back after its setText) follows where the user tapped, dragged
 *    a handle or long-pressed. Before, only the keyboard's own edits moved the tracked caret, so a
 *    re-bind (a refused save showing its reason, a page move, a load landing during the edit) put
 *    the caret back where the keyboard last left it: "it is just in the wrong place".
 *
 * A plain [EditText], not AppCompatEditText: the IME's theme is never a Theme.AppCompat
 * (same reason as `EmojiGridView.EmojiView`).
 */
@SuppressLint("AppCompatCustomView") // AppCompat widgets cannot work in the IME's theme
class ClipboardEditField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    /**
     * Told the new selection ([start], [end]) after every change, the keyboard's own setText /
     * setSelection included (they leave the tracked caret where they set it anyway). Null until
     * the list binds the row; the platform calls [onSelectionChanged] from TextView's constructor,
     * before this property's initializer runs, which is why it is nullable and read defensively.
     */
    var selectionListener: ((start: Int, end: Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionListener?.invoke(selStart, selEnd)
    }

    /**
     * The platform's offset for a touch at ([x], [y]), with the soft-wrap correction of
     * [SoftWrapTap]. The Editor maps a tap (and a long press, a double tap, a drop) through this
     * public method, so overriding it moves exactly those; caret and handle DRAGS go through the
     * package-private `getOffsetAtCoordinate` and keep the platform's rule.
     */
    override fun getOffsetForPosition(x: Float, y: Float): Int {
        val platform = super.getOffsetForPosition(x, y)
        val layout = layout ?: return platform
        val content = text ?: return platform
        if (platform < 0 || layout.lineCount < 2) return platform
        val line = layout.getLineForOffset(platform)
        val lineEnd = layout.getLineEnd(line)
        if (line >= layout.lineCount - 1 || lineEnd <= 0 || lineEnd > content.length) return platform
        val rtl = layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT
        val trailingX = if (rtl) layout.getLineLeft(line) else layout.getLineRight(line)
        // The platform's own last reachable offset on this line: what a tap beyond its end gets.
        val clamp = layout.getOffsetForHorizontal(line, if (rtl) trailingX - 1f else trailingX + 1f)
        val end = SoftWrapTap.LineEnd(
            lineEnd = lineEnd,
            isLastLine = false,
            lastChar = content[lineEnd - 1],
            clampOffset = clamp,
            leadingX = layout.getPrimaryHorizontal(clamp),
            trailingX = trailingX,
            rtl = rtl,
            directionsAgree = layout.isRtlCharAt(lineEnd - 1) == rtl,
        )
        return SoftWrapTap.offsetForTap(platform, localX(x), end)
    }

    /**
     * [x] in the Layout's coordinates, exactly as TextView's own (hidden)
     * `convertToLocalHorizontalCoordinate` computes it for [getOffsetForPosition].
     */
    private fun localX(x: Float): Float {
        val inside = (x - totalPaddingLeft).coerceAtLeast(0f).coerceAtMost((width - totalPaddingRight - 1).toFloat())
        return inside + scrollX
    }
}
