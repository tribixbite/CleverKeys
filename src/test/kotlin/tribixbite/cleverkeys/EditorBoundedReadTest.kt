package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-11-14 (low): the editor-command readers ([EditorTextWindowReader.capture],
 * [EditorReadback.capture]) asked `getExtractedText` for the absolute selection. TextView ignores
 * the size hint and returns the whole document, so each word/line/case command copied it across
 * binder; on a document past the binder limit the call throws and the command is refused.
 *
 * Modelled here as an editor whose `getExtractedText` throws (what the binder does with a
 * too-large reply) but which answers `getSurroundingText` — a bounded window with an absolute
 * offset, as TextView's BaseInputConnection gives on API 31+ (the reader's platform-level seam
 * says 34).
 */
class EditorBoundedReadTest {

    private val realRequest = EditorSelectionReader.newExtractedTextRequest
    private val realSdk = EditorSelectionReader.platformSdk

    @Before
    fun setUp() {
        // The reader's own seam, not a write to Build.VERSION.SDK_INT: late in the full suite
        // the JIT has folded that static final into the compiled reader and ignores the write.
        EditorSelectionReader.platformSdk = { 34 }
        // android.jar's ExtractedTextRequest() throws "Stub!"; the fallback read needs one.
        EditorSelectionReader.newExtractedTextRequest = { mockk(relaxed = true) }
    }

    @After
    fun tearDown() {
        EditorSelectionReader.newExtractedTextRequest = realRequest
        EditorSelectionReader.platformSdk = realSdk
    }

    /** Caret at absolute offset 600,010 in a huge document: "hello world" around it. */
    private fun hugeDocumentEditor(selectionStart: Int = 6, selectionEnd: Int = 6): InputConnection {
        val window = mockk<SurroundingText>()
        every { window.text } returns "hello world"
        every { window.selectionStart } returns selectionStart
        every { window.selectionEnd } returns selectionEnd
        every { window.offset } returns 600_004
        return mockk(relaxed = true) {
            every { getSurroundingText(any(), any(), any()) } returns window
            every { getExtractedText(any(), any()) } throws RuntimeException("TransactionTooLargeException")
        }
    }

    @Test
    fun aWordCommandReadsACaretWithoutCopyingTheDocument() {
        val ic = hugeDocumentEditor()

        val window = EditorTextWindowReader.capture(ic, reach = 64)

        assertWithMessage("the bounded read answers").that(window).isNotNull()
        assertThat(window!!.selStart).isEqualTo(600_010)
        assertThat(window.selEnd).isEqualTo(600_010)
        assertThat(window.before).isEqualTo("hello ")
        assertThat(window.after).isEqualTo("world")
        verify(exactly = 0) { ic.getExtractedText(any(), any()) }
    }

    @Test
    fun theTemplateReadbackIsBoundedToo() {
        val ic = hugeDocumentEditor()

        val receipt = EditorReadback.capture(ic)

        assertWithMessage("the bounded read answers").that(receipt).isNotNull()
        assertThat(receipt!!.start).isEqualTo(600_010)
        assertThat(receipt.collapsed).isTrue()
        verify(exactly = 0) { ic.getExtractedText(any(), any()) }
    }

    /**
     * A non-empty selection still takes the extracted-text read: `getSurroundingText` reports a
     * reversed selection in normalised order, and the commands need to know where the caret is.
     * Here that read throws, so the command fails closed, as before.
     */
    @Test
    fun aSelectionStillUsesTheDirectionPreservingRead() {
        val ic = hugeDocumentEditor(selectionStart = 0, selectionEnd = 5)

        assertThat(EditorTextWindowReader.capture(ic, reach = 64)).isNull()
        verify { ic.getExtractedText(any(), any()) }
    }

    /** An editor that reports no offset (-1, the interface default) falls back too. */
    @Test
    fun anUnknownOffsetFallsBackToTheExtractedRead() {
        val window = mockk<SurroundingText>()
        every { window.text } returns "hello"
        every { window.selectionStart } returns 5
        every { window.selectionEnd } returns 5
        every { window.offset } returns -1
        // Objenesis: the android.jar stub constructor throws; the fields are plain public ones.
        val extracted = org.objenesis.ObjenesisStd().newInstance(android.view.inputmethod.ExtractedText::class.java).apply {
            startOffset = 0; selectionStart = 5; selectionEnd = 5
        }
        val ic = mockk<InputConnection>(relaxed = true) {
            every { getSurroundingText(any(), any(), any()) } returns window
            every { getExtractedText(any(), any()) } returns extracted
            every { getTextBeforeCursor(any(), any()) } returns "hello"
            every { getTextAfterCursor(any(), any()) } returns ""
        }

        val read = EditorTextWindowReader.capture(ic, reach = 64)

        assertThat(read!!.selStart).isEqualTo(5)
        assertThat(read.before).isEqualTo("hello")
    }
}
