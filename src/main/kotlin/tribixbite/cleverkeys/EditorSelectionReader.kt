package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * The editor's absolute selection plus bounded text on each side of it, as one read.
 * [start]/[end] keep the editor's direction (start may exceed end); [before] ends at the lower
 * bound and [after] starts at the upper one.
 */
internal data class EditorSelectionRead(
    val start: Int,
    val end: Int,
    val before: String,
    val selected: String,
    val after: String,
)

/**
 * Shared by [EditorTextWindowReader] and [EditorReadback]: both need ABSOLUTE selection offsets,
 * which only `getExtractedText` used to give. TextView ignores `hintMaxChars` and returns the
 * whole document, so every word/line/case command copied it across binder; past the binder limit
 * (the repo documents 524,000-character pastes) that threw, and the command was refused
 * (review 2026-11-14, low).
 *
 * On API 31+ a collapsed caret is read with `getSurroundingText`, a bounded window whose
 * `offset` is absolute (TextView's BaseInputConnection reports it). The extracted-text read stays
 * for older platforms, for editors that report no offset (-1), and for a NON-empty selection:
 * `getSurroundingText` normalises a reversed selection, and the commands need to know which end
 * is the caret.
 *
 * Fails closed like the callers: anything inconsistent gives null.
 */
internal object EditorSelectionReader {

    /**
     * Builds the whole-document request for the extracted-text read. A seam only because
     * `ExtractedTextRequest()` is an android.jar stub that throws in host tests; production never
     * replaces it.
     */
    @Volatile
    internal var newExtractedTextRequest: () -> ExtractedTextRequest = { ExtractedTextRequest() }

    /**
     * The platform level [read] branches on. A seam because host tests cannot force
     * `Build.VERSION.SDK_INT` reliably here: once the JIT has compiled [read], it treats the
     * static final as a constant and ignores a later write (a full-suite-only failure,
     * 2026-11-15). Production never replaces it.
     */
    @Volatile
    internal var platformSdk: () -> Int = { Build.VERSION.SDK_INT }

    /**
     * @param beforeLength / [afterLength] how much text to read on each side
     * @param maxSelection selections longer than this are refused, not read
     * @param sdk the platform level, injectable for tests
     */
    @SuppressLint("NewApi") // guarded by [sdk], which lint cannot see
    fun read(
        ic: InputConnection,
        beforeLength: Int,
        afterLength: Int,
        maxSelection: Long,
        sdk: Int = platformSdk(),
    ): EditorSelectionRead? {
        if (sdk >= Build.VERSION_CODES.S) {
            val surrounding = ic.getSurroundingText(beforeLength, afterLength, 0)
            if (surrounding != null && surrounding.offset >= 0 &&
                surrounding.selectionStart == surrounding.selectionEnd
            ) {
                val text = surrounding.text.toString()
                val caret = surrounding.selectionStart
                if (caret < 0 || caret > text.length) return null
                val before = text.substring(0, caret)
                val after = text.substring(caret)
                if (before.length > beforeLength || after.length > afterLength) return null
                val absolute = Math.addExact(surrounding.offset, caret)
                return EditorSelectionRead(absolute, absolute, before, "", after)
            }
        }
        return readExtracted(ic, beforeLength, afterLength, maxSelection)
    }

    private fun readExtracted(ic: InputConnection, beforeLength: Int, afterLength: Int, maxSelection: Long): EditorSelectionRead? {
        val extracted = ic.getExtractedText(newExtractedTextRequest().apply { hintMaxChars = 0 }, 0) ?: return null
        if (extracted.startOffset < 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        val start = Math.addExact(extracted.startOffset, extracted.selectionStart)
        val end = Math.addExact(extracted.startOffset, extracted.selectionEnd)
        val length = kotlin.math.abs(start.toLong() - end.toLong())
        if (length > maxSelection) return null
        val selected = if (start == end) "" else ic.getSelectedText(0)?.toString() ?: return null
        if (selected.length.toLong() != length) return null
        val before = ic.getTextBeforeCursor(beforeLength, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(afterLength, 0)?.toString() ?: return null
        if (before.length > beforeLength || after.length > afterLength) return null
        return EditorSelectionRead(start, end, before, selected, after)
    }
}
