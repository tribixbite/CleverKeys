package tribixbite.cleverkeys

import android.os.Trace
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Review 2026-11-14 (low): a dictionary whose header is valid but whose body is cut short or
 * damaged threw unchecked exceptions (BufferUnderflowException) past the loaders'
 * `catch (IOException)`, into the async loader, which then re-ran the whole load on the main
 * thread. It is now an ordinary failed load.
 */
class BinaryDictionaryCorruptBodyTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just runs
        every { Trace.endSection() } just runs
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun aHeaderValidPackCutShortIsAFailedLoadNotAThrow() {
        val shipped = File("src/main/assets/dictionaries/en_enhanced.bin").readBytes()
        // Keep the header and the first part of the word list: every offset still points inside the
        // file, but the words run out mid-entry.
        val truncated = Files.createTempFile("ck-corrupt", ".bin").toFile()
        try {
            truncated.writeBytes(shipped.copyOf(4096))
            val loaded = BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile(
                truncated, HashMap(), HashMap(),
            )
            assertFalse("a corrupt body must be reported as a failed load", loaded)
        } finally {
            truncated.delete()
        }
    }
}
