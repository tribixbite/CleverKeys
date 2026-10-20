package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Audit M6 (2026-10-20): the Layout Manager's XML import/export swallowed every failure
 * (`catch (e: Exception) { // Handle error }`), so a failed export looked like success and a
 * failed import left the editor unchanged with no message. [LayoutXmlTransfer] now treats a
 * missing stream as a failure and renders the localized reason the editor shows in a toast.
 */
class LayoutXmlTransferTest {

    @Test
    fun readReturnsTheDocumentAsUtf8() {
        val xml = "<keyboard name=\"Ünïcode\"/>"
        assertEquals(xml, LayoutXmlTransfer.read { ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)) })
    }

    @Test
    fun writeStoresTheDocumentAsUtf8() {
        val out = ByteArrayOutputStream()
        LayoutXmlTransfer.write({ out }, "<keyboard name=\"Ünïcode\"/>")
        assertEquals("<keyboard name=\"Ünïcode\"/>", out.toString("UTF-8"))
    }

    @Test
    fun aProviderThatOpensNoInputStreamIsAFailureNotAnEmptyLayout() {
        val e = assertThrows(ClassifiedIoException::class.java) { LayoutXmlTransfer.read { null } }
        assertEquals(IoFailureReason.FILE_NOT_FOUND, e.reason)
    }

    @Test
    fun aProviderThatOpensNoOutputStreamIsAFailureNotASilentSuccess() {
        val e = assertThrows(ClassifiedIoException::class.java) { LayoutXmlTransfer.write({ null }, "<x/>") }
        assertEquals(IoFailureReason.READ_WRITE, e.reason)
    }

    @Test
    fun importFailureMessageNamesTheOperationAndTheReason() {
        val msg = LayoutXmlTransfer.failureMessage(
            R.string.layout_manager_import_failed, FileNotFoundException("gone"), EnglishResourceText
        )
        assertEquals(
            "Could not import the layout file:\n" + EnglishResourceText.string(R.string.io_error_file_not_found),
            msg,
        )
    }

    @Test
    fun exportFailureMessageClassifiesAFullDisk() {
        val msg = LayoutXmlTransfer.failureMessage(
            R.string.layout_manager_export_failed,
            IOException("write failed: ENOSPC (No space left on device)"),
            EnglishResourceText,
        )
        assertEquals(
            "Could not export the layout file:\n" + EnglishResourceText.string(R.string.io_error_out_of_space),
            msg,
        )
    }
}
