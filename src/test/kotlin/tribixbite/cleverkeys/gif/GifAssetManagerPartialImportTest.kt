package tribixbite.cleverkeys.gif

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Audit E-11 (2026-09-06) — a PARTIAL thumbnail copy must be visible to the caller.
 *
 * `importThumbnails` swallowed per-file copy failures (log-and-continue), returning only
 * the success count — so a disk-full ENOSPC partway through a big pack produced
 * `thumbCount = k > 0`, sailed past GifPackManager's ARC-038 `thumbCount == 0` rollback
 * gate, and the import returned Success with the remaining tiles blank and no
 * user-visible signal. The importer now returns [ThumbnailImportResult] (imported AND
 * failed counts) and GifPackManager rolls back whenever `failed > 0`.
 *
 * Real file IO against a temp tree (same tier as GifPackThumbnailValidationTest); the
 * failing copy is provoked by pre-creating the destination path as a NON-EMPTY DIRECTORY,
 * which `copyTo(overwrite = true)` cannot delete — the same catch branch ENOSPC lands in.
 *
 * RED (pre-fix): the failure was unrepresentable — importThumbnails returned Int (the
 * success count), so this contract did not compile; the pre-fix behavior it replaces
 * (partial import reported as plain success) is documented in the audit finding.
 */
class GifAssetManagerPartialImportTest {

    private val objenesis = org.objenesis.ObjenesisStd()

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var srcThumbs: File
    private lateinit var manager: GifAssetManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        root = createTempDir(prefix = "gif-partial-import")
        filesDir = File(root, "files").apply { mkdirs() }
        srcThumbs = File(root, "thumbs").apply { mkdirs() }

        val context = mockk<Context>()
        every { context.filesDir } returns filesDir

        // Private constructor + android-typed enclosing class: allocate and seed (the
        // KeyboardReceiverPaneHostTest idiom).
        manager = objenesis.newInstance(GifAssetManager::class.java)
        val ctxField = GifAssetManager::class.java.getDeclaredField("context")
        ctxField.isAccessible = true
        ctxField.set(manager, context)
    }

    @After
    fun teardown() {
        root.deleteRecursively()
        unmockkAll()
    }

    private fun srcThumb(id: Long) {
        File(srcThumbs, "%06d.webp".format(id)).writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun partialCopyFailureIsCounted() = runBlocking {
        srcThumb(1); srcThumb(2); srcThumb(3)

        // Make id 2's DESTINATION un-writable: a non-empty directory at the target path.
        val blockedDest = File(filesDir, Gif.getPartitionedPath(Gif.THUMBS_DIR, 2L))
        blockedDest.mkdirs()
        File(blockedDest, "occupant").writeBytes(byteArrayOf(9))

        val result = manager.importThumbnails(srcThumbs)

        assertWithMessage(
            "a failed thumbnail copy must surface in the result — swallowing it is how a " +
                "disk-full import shipped a mostly-blank pack as Success (audit E-11)"
        ).that(result.failed).isEqualTo(1)
        assertThat(result.imported).isEqualTo(2)
    }

    @Test
    fun cleanCopyReportsZeroFailures() = runBlocking {
        srcThumb(10); srcThumb(11)

        val result = manager.importThumbnails(srcThumbs)

        assertThat(result).isEqualTo(ThumbnailImportResult(imported = 2, failed = 0))
    }
}
