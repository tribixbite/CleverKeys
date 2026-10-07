package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardMediaManager
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.EditEntryResult

/**
 * Service tier of the selection's bulk actions (2026-10-07): Add to Pinned / Add to Todos reuse
 * the per-entry COPY inserts inside one transaction; Merge stores one new History clipping;
 * Clean edits in place through the same path as the inline editor.
 */
class ClipboardBulkServiceTest {

    private val objenesis = ObjenesisStd()
    private lateinit var database: ClipboardDatabase
    private lateinit var service: ClipboardHistoryService
    private var notified = 0

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        database = mockk(relaxed = true)
        every { database.runInTransaction<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        service = objenesis.newInstance(ClipboardHistoryService::class.java)
        service.setField("_database", database)
        service.setField("_mediaManager\$delegate", lazyOf(mockk<ClipboardMediaManager>(relaxed = true)))
        notified = 0
        service.setOnClipboardHistoryChange { notified++ }
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun addToPinnedCopiesEveryRowWithItsMediaAndPrivacyInOneTransaction() {
        val text = ClipboardEntry("note", 10, rowId = 1, isPrivate = true, sourcePackage = "org.app")
        val photo = ClipboardEntry("p.png", 20, mimeType = "image/png", thumbnailBlob = byteArrayOf(1),
            mediaPath = "m/p.png", rowId = 2)
        val dup = ClipboardEntry("already", 30, rowId = 3)
        every { database.isPinned("already") } returns true
        every { database.pinEntry(any(), any(), any(), any(), any(), any(), any()) } returns true

        val result = service.copyEntriesTo(ClipboardTab.PINNED, listOf(text, photo, dup)).getOrThrow()

        assertThat(result).isEqualTo(ClipboardCopyResult(added = 2, alreadyPresent = 1, failed = 0))
        verify(exactly = 1) { database.runInTransaction<Any?>(any()) }
        verify { database.pinEntry("note", 10, ClipboardEntry.MIME_TEXT_PLAIN, null, null, true, "org.app") }
        verify { database.pinEntry("p.png", 20, "image/png", photo.thumbnailBlob, "m/p.png", false, null) }
        verify(exactly = 0) { database.pinEntry("already", any(), any(), any(), any(), any(), any()) }
        // Runs on IO: the list view is refreshed by the holder's main-thread observer instead.
        assertThat(notified).isEqualTo(0)
    }

    @Test
    fun addToTodosCountsInsertFailuresSeparatelyFromDuplicates() {
        val ok = ClipboardEntry("ok", 1, rowId = 1)
        val bad = ClipboardEntry("bad", 2, rowId = 2)
        every { database.isTodo(any()) } returns false
        every { database.addTodoEntry("ok", any(), any(), any(), any(), any(), any()) } returns true
        every { database.addTodoEntry("bad", any(), any(), any(), any(), any(), any()) } returns false

        val result = service.copyEntriesTo(ClipboardTab.TODOS, listOf(ok, bad)).getOrThrow()

        assertThat(result).isEqualTo(ClipboardCopyResult(added = 1, alreadyPresent = 0, failed = 1))
    }

    @Test
    fun historyIsNotACopyTarget() {
        assertThat(service.copyEntriesTo(ClipboardTab.HISTORY, emptyList()).isFailure).isTrue()
    }

    @Test
    fun mergeStoresOneNewHistoryClippingCarryingPrivacy() {
        every { database.addClipboardEntry(any(), any(), any(), any()) } returns true
        val plan = ClipboardBulkPlans.MergePlan("a\nb", sources = 2, skippedMedia = 0, isPrivate = true)

        assertThat(service.addMergedClip(plan).isSuccess).isTrue()

        verify(exactly = 1) { database.addClipboardEntry("a\nb", any(), true, null) }
        assertThat(notified).isEqualTo(0)
    }

    @Test
    fun aMergeTheDatabaseRefusesIsAFailure() {
        every { database.addClipboardEntry(any(), any(), any(), any()) } returns false
        val plan = ClipboardBulkPlans.MergePlan("a\nb", 2, 0, false)
        assertThat(service.addMergedClip(plan).isFailure).isTrue()
    }

    @Test
    fun cleanEditsInPlaceThroughTheInlineEditPathAndCountsOutcomes() {
        val spy = spyk(service)
        val a = ClipboardEntry("a  ", 1, rowId = 1)
        val b = ClipboardEntry("b\t", 2, rowId = 2)
        val photo = ClipboardEntry("p.png", 3, mimeType = "image/png", rowId = 3)
        val same = ClipboardEntry("same", 4, rowId = 4)
        every { spy.editEntryContent("a  ", "a", ClipboardTab.PINNED) } returns EditEntryResult.Success
        every { spy.editEntryContent("b\t", "b", ClipboardTab.PINNED) } returns EditEntryResult.DuplicateConflict
        val plan = ClipboardBulkPlans.planClean(listOf(a, b, photo, same))

        val result = spy.applyClean(ClipboardTab.PINNED, plan).getOrThrow()

        assertThat(result).isEqualTo(ClipboardCleanResult(cleaned = 1, unchanged = 1, skippedMedia = 1, failed = 1))
        verify(exactly = 2) { spy.editEntryContent(any(), any(), ClipboardTab.PINNED) }
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                it.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field '$name' not found on ${javaClass.name}")
    }
}
