package tribixbite.cleverkeys

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review 2026-11-14 (low): the editor-command readers now take a caret's window from
 * `getSurroundingText` on API 31+ instead of copying the whole document with `getExtractedText`
 * ([EditorSelectionReader]). Host tests only had a mocked `SurroundingText`; this pins, against
 * TextView's real `EditableInputConnection`, that the new bounded read gives exactly what the old
 * whole-document read gives — absolute offsets far into a long document, the text on each side —
 * and that a selection (here a reversed one) still takes the direction-keeping read.
 */
@RunWith(AndroidJUnit4::class)
class EditorSelectionReaderInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** TextView's own InputConnection over [text] with the selection [start]..[end]. */
    private fun <T> withEditor(text: String, start: Int, end: Int = start, block: (InputConnection) -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync {
            val view = EditText(instrumentation.targetContext)
            view.setText(text)
            view.setSelection(start, end)
            val ic = view.onCreateInputConnection(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
            result = block(requireNotNull(ic) { "EditText gave no InputConnection" })
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** ~6,000 characters, so the caret's window is a small part of the document. */
    private val document = (1..600).joinToString(" ") { "word$it" }

    @Test
    fun aCaretDeepInADocumentReadsTheSameWindowBothWays() {
        assumeTrue("the bounded read exists on API 31+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val caret = document.indexOf("word400")
        val (bounded, whole) = withEditor(document, caret) { ic ->
            EditorSelectionReader.read(ic, 64, 64, 65_536, sdk = Build.VERSION.SDK_INT) to
                EditorSelectionReader.read(ic, 64, 64, 65_536, sdk = Build.VERSION_CODES.R)
        }
        assertNotNull("bounded read", bounded)
        assertNotNull("whole-document read", whole)
        assertEquals("absolute caret offset", caret, bounded!!.start)
        assertEquals(whole, bounded)
        assertEquals(document.substring(caret - 64, caret), bounded.before)
        assertEquals(document.substring(caret, caret + 64), bounded.after)
    }

    @Test
    fun theWordCommandWindowMatchesTheWholeDocumentRead() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val caret = document.indexOf("word512") + 2
        // The production reader the word/line/case commands call.
        val window = withEditor(document, caret) { ic -> EditorTextWindowReader.capture(ic, 200) }
        assertNotNull(window)
        assertEquals(caret, window!!.selStart)
        assertEquals(caret, window.selEnd)
        assertEquals(document.substring(caret - 200, caret), window.before)
        assertEquals(document.substring(caret, caret + 200), window.after)
    }

    @Test
    fun aReversedSelectionKeepsItsDirection() {
        val start = document.indexOf("word100")
        val end = start + "word100".length
        // Anchor after the caret: start > end, as a leftward selection reports it.
        val read = withEditor(document, end, start) { ic -> EditorSelectionReader.read(ic, 32, 32, 65_536) }
        assertNotNull(read)
        assertEquals(end, read!!.start)
        assertEquals(start, read.end)
        assertEquals("word100", read.selected)
    }

    @Test
    fun aCaretAtTheStartOfAnEmptyFieldReadsEmptySides() {
        val read = withEditor("", 0) { ic -> EditorSelectionReader.read(ic, 32, 32, 65_536) }
        assertNotNull(read)
        assertEquals(0, read!!.start)
        assertEquals("", read.before)
        assertEquals("", read.after)
    }
}
