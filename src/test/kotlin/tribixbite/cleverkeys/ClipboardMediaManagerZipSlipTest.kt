package tribixbite.cleverkeys

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Path-traversal (zip-slip) hardening for [ClipboardMediaManager.getMediaFile].
 *
 * A clipboard/full backup ZIP is untrusted input: `entry.name` is written straight
 * into `File(filesDir, entry.name)` during import. Before this hardening a crafted
 * name like `clipboard_media/../../databases/clipboard.db` resolved OUTSIDE filesDir
 * and could clobber app-private files. `getMediaFile` now canonicalizes and rejects
 * any target that escapes filesDir with a [SecurityException].
 *
 * `getMediaFile` reads only `context.filesDir`, so a MockK Context backed by a real
 * temp directory exercises the real canonicalization logic — this runs under the
 * on-device `runMockTests` task (no Robolectric needed).
 */
class ClipboardMediaManagerZipSlipTest {

    private lateinit var baseDir: File
    private lateinit var manager: ClipboardMediaManager

    @Before
    fun setup() {
        baseDir = Files.createTempDirectory("ck-zipslip").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns baseDir
        manager = ClipboardMediaManager(context)
    }

    @Test
    fun getMediaFile_rejectsParentTraversalIntoDatabases() {
        val evil = "clipboard_media/../../databases/clipboard.db"
        try {
            manager.getMediaFile(evil)
            fail("Expected SecurityException for traversal path: $evil")
        } catch (e: SecurityException) {
            assertTrue(
                "message should name the offending escape",
                e.message?.contains("escapes") == true ||
                    e.message?.contains("clipboard.db") == true
            )
        }
    }

    @Test
    fun getMediaFile_rejectsSingleLevelEscape() {
        val evil = "clipboard_media/../evil.txt"
        try {
            manager.getMediaFile(evil)
            fail("Expected SecurityException for traversal path: $evil")
        } catch (e: SecurityException) {
            assertTrue(
                "message should reference escape",
                e.message?.contains("escapes") == true
            )
        }
    }

    @Test
    fun getMediaFile_allowsLegitimateMediaPath() {
        // A normal partitioned media path stays inside filesDir and must resolve.
        val legit = "clipboard_media/042/abc.png"
        val file = manager.getMediaFile(legit)

        val canonicalBase = baseDir.canonicalPath
        assertTrue(
            "resolved file must live under filesDir: ${file.canonicalPath}",
            file.canonicalPath == canonicalBase ||
                file.canonicalPath.startsWith(canonicalBase + File.separator)
        )
        // And it must be the expected concrete location (no mangling of the name).
        assertEquals(
            File(baseDir, legit).canonicalPath,
            file.canonicalPath
        )
    }
}
