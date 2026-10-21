package tribixbite.cleverkeys

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ml.SwipeMLDataStore

/**
 * Audit L14 / L15 (2026-10-20): resources that were closed only on the happy path, or not at all.
 *
 *  - **L14** `Utils.read_all_utf8` never closed its reader, and `LayoutsPreference` called it on a
 *    raw-resource stream without `use {}`, leaking one stream per custom-layout dialog.
 *  - **L15** `SwipeMLDataStore`'s read methods called `cursor.close()` after the loop, so an
 *    `SQLiteException` from `moveToNext()` (a corrupt page, a full disk on a WAL checkpoint)
 *    leaked the cursor. Every other cursor site in the app already used `use {}`.
 */
class ResourceClosingTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    // --------------------------------------------------------------------------- L14

    /** An input stream that records whether it was closed. */
    private class TrackingStream(text: String) : InputStream() {
        private val inner = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        var closed = false
            private set

        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {
            closed = true
        }
    }

    @Test
    fun readAllUtf8_returnsTheTextAndClosesTheStream() {
        val stream = TrackingStream("<keyboard>ü</keyboard>")
        assertThat(Utils.read_all_utf8(stream)).isEqualTo("<keyboard>ü</keyboard>")
        assertThat(stream.closed).isTrue()
    }

    // --------------------------------------------------------------------------- L15

    /**
     * Stands in for the `SQLiteException` a corrupt page raises mid-iteration (the android.jar stub
     * cannot construct the real one on the host JVM; any RuntimeException takes the same path).
     */
    private class CorruptPageException : RuntimeException("database disk image is malformed")

    /** A cursor whose iteration fails the way a corrupt database page does. */
    private fun failingCursor(): Cursor = mockk(relaxed = true) {
        every { moveToNext() } throws CorruptPageException()
        every { moveToFirst() } throws CorruptPageException()
    }

    /** A store whose only real parts are the read methods under test. */
    private fun storeOver(db: SQLiteDatabase): SwipeMLDataStore = mockk {
        every { readableDatabase } returns db
        every { loadAllData() } answers { callOriginal() }
        every { loadDataBySource(any()) } answers { callOriginal() }
        every { loadRecentData(any()) } answers { callOriginal() }
        every { searchByWord(any(), any(), any()) } answers { callOriginal() }
        every { loadPaginatedData(any(), any()) } answers { callOriginal() }
        every { countSearchResults(any()) } answers { callOriginal() }
    }

    private fun assertClosesOnFailure(read: (SwipeMLDataStore) -> Unit) {
        val cursor = failingCursor()
        val db = mockk<SQLiteDatabase> {
            every { query(any(), any(), any(), any(), any(), any(), any()) } returns cursor
            every { query(any(), any(), any(), any(), any(), any(), any(), any()) } returns cursor
            every { rawQuery(any(), any()) } returns cursor
        }
        assertThrows(CorruptPageException::class.java) { read(storeOver(db)) }
        verify(exactly = 1) { cursor.close() }
    }

    @Test
    fun loadAllData_closesTheCursorWhenIterationFails() = assertClosesOnFailure { it.loadAllData() }

    @Test
    fun loadDataBySource_closesTheCursorWhenIterationFails() =
        assertClosesOnFailure { it.loadDataBySource("user_selection") }

    @Test
    fun loadRecentData_closesTheCursorWhenIterationFails() = assertClosesOnFailure { it.loadRecentData(10) }

    @Test
    fun searchByWord_closesTheCursorWhenIterationFails() = assertClosesOnFailure { it.searchByWord("he", 10, 0) }

    @Test
    fun loadPaginatedData_closesTheCursorWhenIterationFails() =
        assertClosesOnFailure { it.loadPaginatedData(10, 0) }

    @Test
    fun countSearchResults_closesTheCursorWhenReadingFails() {
        assertClosesOnFailure { it.countSearchResults("") }
        assertClosesOnFailure { it.countSearchResults("he") }
    }
}
