package tribixbite.cleverkeys.clipboard

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClipboardDatabase

/**
 * [ClipboardDatabase.applySizeLimitBytes] end to end over a fake SQLite layer (ARM64 Termux has no
 * Robolectric runner): which History rows the total-size limit (`clipboard_limit_type` = size)
 * deletes, and which media paths it hands back for file cleanup.
 *
 * The fake answers the size query the way SQLite would: it keeps the rows that have not expired
 * (`expiry > ?`) and orders them by timestamp in the direction the query's `ORDER BY` names. So
 * the walk order is tested by its effect (the oldest go), not by the text of the query: until
 * 2026-11-24 the query walked oldest-first and the NEWEST rows were deleted (Pixel: 159 of its
 * newest clippings went after a restore). Pre-tag review 2026-11-25 (S1): a newest clipping
 * larger than the whole limit deleted every row, itself included.
 */
class ClipboardSizeLimitWalkTest {

    private val mb = 1024L * 1024L

    /** One History row as the size query sees it. */
    private data class Row(val id: Long, val timestamp: Long, val bytes: Long, val mediaPath: String? = null)

    /** Ids named by every `DELETE … IN (…)` the code under test ran, in order. */
    private val deletedIds = mutableListOf<Long>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkAll()

    /**
     * A [ClipboardDatabase] whose real [ClipboardDatabase.applySizeLimitBytes] runs against [rows]
     * (all unexpired) through a fake [SQLiteDatabase].
     */
    private fun databaseOver(rows: List<Row>): ClipboardDatabase {
        val sqlite = mockk<SQLiteDatabase>(relaxed = true)
        val sql = slot<String>()
        every { sqlite.rawQuery(capture(sql), any()) } answers {
            val query = sql.captured
            val descending = Regex("""ORDER BY\s+\w+\s+DESC""").containsMatchIn(query)
            require(descending || Regex("""ORDER BY\s+\w+\s+ASC""").containsMatchIn(query)) {
                "the size query must order by timestamp: $query"
            }
            val ordered = if (descending) rows.sortedByDescending { it.timestamp } else rows.sortedBy { it.timestamp }
            cursorOver(ordered)
        }
        val delete = slot<String>()
        every { sqlite.execSQL(capture(delete)) } answers {
            val inList = delete.captured.substringAfter("IN (").substringBefore(")")
            deletedIds += inList.split(',').map { it.trim().toLong() }
        }
        val database = mockk<ClipboardDatabase>()
        every { database.writableDatabase } returns sqlite
        every { database.applySizeLimitBytes(any(), any()) } answers { callOriginal() }
        return database
    }

    /** Columns as the size query selects them: 0 id, 1 bytes, 2 media path (nullable). */
    private fun cursorOver(rows: List<Row>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var position = -1
        every { cursor.moveToNext() } answers { ++position < rows.size }
        every { cursor.getLong(0) } answers { rows[position].id }
        every { cursor.getLong(1) } answers { rows[position].bytes }
        every { cursor.isNull(2) } answers { rows[position].mediaPath == null }
        every { cursor.getString(2) } answers { rows[position].mediaPath }
        return cursor
    }

    @Test
    fun theOldestRowsGoAndTheirMediaPathsComeBack() {
        // Inserted out of timestamp order on purpose: the walk must follow time, not insertion.
        val rows = listOf(
            Row(id = 1, timestamp = 1_000, bytes = 400 * 1024, mediaPath = "clipboard_media/0/old.png"),
            Row(id = 3, timestamp = 3_000, bytes = 400 * 1024),
            Row(id = 2, timestamp = 2_000, bytes = 400 * 1024),
            Row(id = 4, timestamp = 4_000, bytes = 300 * 1024),
        )
        val (removed, mediaPaths) = databaseOver(rows).applySizeLimitBytes(1)
        // Newest first: 4 (300 KB) + 3 (400 KB) = 700 KB fit in 1 MB; adding 2 makes 1,100 KB.
        assertThat(deletedIds).containsExactly(2L, 1L).inOrder()
        assertThat(removed).isEqualTo(2)
        assertThat(mediaPaths).containsExactly("clipboard_media/0/old.png")
    }

    @Test
    fun aNewestClippingLargerThanTheLimitIsKept() {
        val rows = listOf(
            Row(id = 1, timestamp = 1_000, bytes = 10),
            Row(id = 2, timestamp = 2_000, bytes = 10),
            Row(id = 3, timestamp = 3_000, bytes = 3 * mb, mediaPath = "clipboard_media/0/big.png"),
        )
        val (removed, mediaPaths) = databaseOver(rows).applySizeLimitBytes(1)
        assertThat(deletedIds).containsExactly(2L, 1L).inOrder()
        assertThat(removed).isEqualTo(2)
        assertThat(mediaPaths).isEmpty()
    }

    @Test
    fun aHistoryWithinTheLimitIsUntouched() {
        val rows = (1L..3L).map { Row(id = it, timestamp = it * 1_000, bytes = 100 * 1024) }
        val (removed, mediaPaths) = databaseOver(rows).applySizeLimitBytes(1)
        assertThat(deletedIds).isEmpty()
        assertThat(removed).isEqualTo(0)
        assertThat(mediaPaths).isEmpty()
    }
}
