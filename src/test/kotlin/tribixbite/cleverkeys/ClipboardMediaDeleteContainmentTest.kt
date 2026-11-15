package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Review 2026-11-14 H3: an imported clipboard entry's `media_path` comes from an untrusted
 * backup JSON and is stored verbatim; the expiry sweep, prune and delete paths then call
 * [ClipboardMediaManager.deleteMedia] with it. Deletion must stay inside `clipboard_media/`
 * exactly like [ClipboardMediaManager.getMediaFile] does for reads.
 *
 * The fake `filesDir` is `<tmp>/files` so `../databases/…` resolves to a real sibling, as
 * `/data/data/<pkg>/databases` is on a device.
 */
class ClipboardMediaDeleteContainmentTest {

    private lateinit var appDir: File
    private lateinit var filesDir: File
    private lateinit var manager: ClipboardMediaManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        appDir = Files.createTempDirectory("ck-media-delete").toFile()
        filesDir = File(appDir, "files").apply { mkdirs() }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        manager = ClipboardMediaManager(context)
    }

    @After
    fun teardown() {
        unmockkAll()
        appDir.deleteRecursively()
    }

    private fun file(rel: String, base: File = appDir): File =
        File(base, rel).apply { parentFile?.mkdirs(); writeText("x") }

    @Test
    fun aTraversalPathOutsideFilesDirIsNotDeleted() {
        val db = file("databases/clipboard_history.db")

        manager.deleteMedia("../databases/clipboard_history.db")

        assertTrue("the database outside filesDir must survive", db.exists())
    }

    @Test
    fun aTraversalPathThroughTheMediaDirIsNotDeleted() {
        val prefs = file("short_swipe_customizations.json", filesDir)
        File(filesDir, "clipboard_media").mkdirs()  // so the `..` really resolves

        manager.deleteMedia("clipboard_media/../short_swipe_customizations.json")

        assertTrue("a file beside clipboard_media must survive", prefs.exists())
    }

    @Test
    fun theMediaDirItselfIsNotDeleted() {
        val mediaDir = File(filesDir, "clipboard_media").apply { mkdirs() }

        manager.deleteMedia("clipboard_media")
        manager.deleteMedia("clipboard_media/.")

        assertTrue("the media directory is never a deletion target", mediaDir.isDirectory)
    }

    @Test
    fun aRealMediaFileIsDeletedAndItsEmptyPartitionRemoved() {
        val media = file("clipboard_media/042/abc.png", filesDir)

        manager.deleteMedia("clipboard_media/042/abc.png")

        assertFalse("the media file is deleted", media.exists())
        assertFalse("its emptied partition directory is removed", media.parentFile!!.exists())
        assertTrue("the media root stays", File(filesDir, "clipboard_media").isDirectory)
    }
}
