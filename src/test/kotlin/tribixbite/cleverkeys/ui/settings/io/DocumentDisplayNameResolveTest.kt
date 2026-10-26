package tribixbite.cleverkeys.ui.settings.io

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Android half of the "File: 932" fix: [resolveDocumentDisplayName] asks the provider for
 * [OpenableColumns.DISPLAY_NAME] and only falls back to the path when the provider cannot
 * answer. Pure policy: [DocumentNamePolicyTest].
 */
class DocumentDisplayNameResolveTest {

    /** [text] documents the real URI; a mock's toString is only reached on the last fallback. */
    @Suppress("UNUSED_PARAMETER")
    private fun uri(scheme: String, segment: String?, text: String): Uri = mockk {
        every { this@mockk.scheme } returns scheme
        every { lastPathSegment } returns segment
    }

    private fun cursorWith(name: String?): Cursor = mockk(relaxUnitFun = true) {
        every { getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { moveToFirst() } returns true
        every { isNull(0) } returns (name == null)
        every { getString(0) } returns name
        every { close() } returns Unit
    }

    @Test
    fun `a downloads document shows its display name, not its numeric id`() {
        val doc = uri("content", "932", "content://com.android.providers.downloads.documents/document/932")
        val resolver = mockk<ContentResolver> {
            every { query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } returns
                cursorWith("cleverkeys-dictionary.json")
        }
        assertThat(resolveDocumentDisplayName(resolver, doc)).isEqualTo("cleverkeys-dictionary.json")
    }

    @Test
    fun `a provider failure falls back to the path-derived name`() {
        val doc = uri("content", "primary:Download/backup.zip", "content://ext/document/primary%3ADownload%2Fbackup.zip")
        val resolver = mockk<ContentResolver> {
            every { query(any<Uri>(), any(), any(), any(), any()) } throws SecurityException("grant revoked")
        }
        assertThat(resolveDocumentDisplayName(resolver, doc)).isEqualTo("backup.zip")
    }

    @Test
    fun `a null display name or no cursor falls back too`() {
        val doc = uri("content", "msf:77", "content://media/document/msf%3A77")
        val nullName = mockk<ContentResolver> {
            every { query(any<Uri>(), any(), any(), any(), any()) } returns cursorWith(null)
        }
        assertThat(resolveDocumentDisplayName(nullName, doc)).isEqualTo("77")
        val noCursor = mockk<ContentResolver> {
            every { query(any<Uri>(), any(), any(), any(), any()) } returns null
        }
        assertThat(resolveDocumentDisplayName(noCursor, doc)).isEqualTo("77")
    }

    @Test
    fun `a headless file uri is never queried`() {
        val file = uri("file", "settings.json", "file:///sdcard/settings.json")
        val resolver = mockk<ContentResolver>()
        assertThat(resolveDocumentDisplayName(resolver, file)).isEqualTo("settings.json")
        verify(exactly = 0) { resolver.query(any<Uri>(), any(), any(), any(), any()) }
    }
}
