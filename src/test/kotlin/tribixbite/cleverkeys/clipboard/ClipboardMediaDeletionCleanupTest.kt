package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardMediaManager
import java.io.File
import java.nio.file.Files

/**
 * Companion pins for the media-delete affordance (see ClipboardMediaDeleteAffordanceTest):
 * now that every tab's UI can delete a media row, these pin that the STORE side of that path
 * does not orphan the on-disk media file — and, symmetrically, does not delete a file another
 * tab's COPY still references.
 *
 * Two tiers:
 *  - Service tier (Objenesis + mocks): `removeHistoryEntry` / `unpinEntry` / `removeFromTodo`
 *    each ask the DB for the deleted row's media_path and call
 *    `ClipboardMediaManager.deleteMedia` iff `isMediaPathReferenced` says no table still
 *    holds it (COPY semantics — pin/todo duplicate the media_path reference).
 *  - Disk tier (real ClipboardMediaManager over a temp filesDir, the ZipSlipTest idiom):
 *    `deleteMedia` really removes the file and prunes an emptied partition directory.
 *
 * These pinned behaviors already existed when the affordance fix landed (verified green on
 * arrival); they are regression guards, not fail-first captures.
 */
class ClipboardMediaDeletionCleanupTest {

    private val objenesis = ObjenesisStd()

    private lateinit var database: ClipboardDatabase
    private lateinit var mediaManager: ClipboardMediaManager
    private lateinit var service: ClipboardHistoryService

    private val mediaPath = "clipboard_media/007/abc123.png"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        database = mockk(relaxed = true)
        mediaManager = mockk(relaxed = true)

        service = objenesis.newInstance(ClipboardHistoryService::class.java)
        service.setField("_database", database)
        // _mediaManager is a `by lazy` val — inject an initialized Lazy over the mock
        service.setField("_mediaManager\$delegate", lazyOf(mediaManager))
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------ service tier: history

    @Test
    fun removeHistoryEntryDeletesTheMediaFileWhenNoTableStillReferencesIt() {
        every { database.getActiveClipboardEntries() } returns emptyList()
        every { database.removeClipboardEntry("IMG.png") } returns mediaPath
        every { database.isMediaPathReferenced(mediaPath) } returns false

        service.removeHistoryEntry("IMG.png")

        verify(exactly = 1) { mediaManager.deleteMedia(mediaPath) }
    }

    @Test
    fun removeHistoryEntryKeepsTheFileWhileAPinnedOrTodoCopyReferencesIt() {
        every { database.getActiveClipboardEntries() } returns emptyList()
        every { database.removeClipboardEntry("IMG.png") } returns mediaPath
        every { database.isMediaPathReferenced(mediaPath) } returns true

        service.removeHistoryEntry("IMG.png")

        verify(exactly = 0) { mediaManager.deleteMedia(any()) }
    }

    @Test
    fun removeHistoryEntryOfATextRowNeverTouchesTheMediaManager() {
        every { database.getActiveClipboardEntries() } returns emptyList()
        every { database.removeClipboardEntry("text") } returns null

        service.removeHistoryEntry("text")

        verify(exactly = 0) { mediaManager.deleteMedia(any()) }
    }

    // -------------------------------------------------- service tier: pinned / todo

    @Test
    fun unpinEntryDeletesTheMediaFileWhenUnreferenced() {
        every { database.unpinEntry("IMG.png") } returns mediaPath
        every { database.isMediaPathReferenced(mediaPath) } returns false

        service.unpinEntry("IMG.png")

        verify(exactly = 1) { mediaManager.deleteMedia(mediaPath) }
    }

    @Test
    fun removeFromTodoDeletesTheMediaFileWhenUnreferenced() {
        every { database.removeTodoEntry("clip.mp4") } returns mediaPath
        every { database.isMediaPathReferenced(mediaPath) } returns false

        service.removeFromTodo("clip.mp4")

        verify(exactly = 1) { mediaManager.deleteMedia(mediaPath) }
    }

    // ------------------------------------------------------------- disk tier

    @Test
    fun deleteMediaRemovesTheFileAndPrunesTheEmptiedPartitionDir() {
        val filesDir = Files.createTempDirectory("ck-media-del").toFile()
        val manager = ClipboardMediaManager(mockContext(filesDir))
        val partition = File(filesDir, "clipboard_media/007").apply { mkdirs() }
        val file = File(partition, "abc123.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        manager.deleteMedia("clipboard_media/007/abc123.png")

        assertThat(file.exists()).isFalse()
        assertThat(partition.exists()).isFalse()
        filesDir.deleteRecursively()
    }

    @Test
    fun deleteMediaKeepsThePartitionDirWhileSiblingsRemain() {
        val filesDir = Files.createTempDirectory("ck-media-keep").toFile()
        val manager = ClipboardMediaManager(mockContext(filesDir))
        val partition = File(filesDir, "clipboard_media/007").apply { mkdirs() }
        val doomed = File(partition, "abc123.png").apply { writeBytes(byteArrayOf(1)) }
        val sibling = File(partition, "def456.png").apply { writeBytes(byteArrayOf(2)) }

        manager.deleteMedia("clipboard_media/007/abc123.png")

        assertThat(doomed.exists()).isFalse()
        assertThat(sibling.exists()).isTrue()
        assertThat(partition.exists()).isTrue()
        filesDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    private fun mockContext(filesDir: File): android.content.Context {
        val ctx = mockk<android.content.Context>(relaxed = true)
        every { ctx.filesDir } returns filesDir
        return ctx
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError(
            "field '$name' not found on ${javaClass.name} — renamed or removed; re-point this test"
        )
    }
}
