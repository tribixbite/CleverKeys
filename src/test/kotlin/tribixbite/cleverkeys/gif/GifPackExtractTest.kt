package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClassifiedIoException
import tribixbite.cleverkeys.IoFailureReason
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Review 2026-11-14 (low): a GIF pack is a user-supplied ZIP. Extraction must stay strictly
 * inside its temp directory — the old check compared without a trailing separator, so a sibling
 * whose name starts with the temp dir's (`../gif_pack_import_123X/…`) passed — and must stop a
 * zip bomb before it fills the cache (no size or entry cap existed).
 */
class GifPackExtractTest {

    private val root: File = Files.createTempDirectory("ck-gif-extract").toFile()
    private val dest = File(root, "gif_pack_import_123").apply { mkdirs() }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun cleanup() {
        unmockkAll()
        root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test
    fun aSiblingWhoseNameExtendsTheTempDirIsRefused() {
        val bytes = zip("../gif_pack_import_123X/evil.txt" to "x".toByteArray(), "manifest.json" to "{}".toByteArray())

        GifPackManager.extractZip(ByteArrayInputStream(bytes), dest)

        assertWithMessage("nothing written outside the temp dir")
            .that(File(root, "gif_pack_import_123X/evil.txt").exists()).isFalse()
        assertThat(File(dest, "manifest.json").isFile).isTrue()
    }

    @Test
    fun aParentEscapeIsRefused() {
        val bytes = zip("../escape.txt" to "x".toByteArray())

        GifPackManager.extractZip(ByteArrayInputStream(bytes), dest)

        assertThat(File(root, "escape.txt").exists()).isFalse()
    }

    @Test
    fun anArchiveExpandingPastTheByteCapFailsAsTooLarge() {
        val bytes = zip("a.bin" to ByteArray(4096), "b.bin" to ByteArray(4096))

        val thrown = runCatching {
            GifPackManager.extractZip(ByteArrayInputStream(bytes), dest, maxTotalBytes = 6_000L, maxEntries = 100)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(ClassifiedIoException::class.java)
        assertThat((thrown as ClassifiedIoException).reason).isEqualTo(IoFailureReason.TOO_LARGE)
    }

    @Test
    fun anArchiveWithTooManyEntriesFailsAsTooLarge() {
        val bytes = zip(*Array(5) { "f$it.txt" to "x".toByteArray() })

        val thrown = runCatching {
            GifPackManager.extractZip(ByteArrayInputStream(bytes), dest, maxTotalBytes = 1_000_000L, maxEntries = 3)
        }.exceptionOrNull()

        assertThat((thrown as? ClassifiedIoException)?.reason).isEqualTo(IoFailureReason.TOO_LARGE)
    }
}
