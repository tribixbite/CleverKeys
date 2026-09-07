package tribixbite.cleverkeys.clipboard

import android.content.ClipData
import android.util.Log
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
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardMediaManager

/**
 * 2026-09-06 comprehensive-audit fixes D-4 and D-5 — service-tier hygiene around
 * removal and pruning (Objenesis + MockK, the ClipboardMediaDeletionCleanupTest idiom).
 *
 * D-4 — `removeHistoryEntry` used to decide "this is the current OS clip" by comparing the
 *   deleted content against history[0] instead of the ACTUAL `ClipboardManager.primaryClip`.
 *   Two real losses followed: deleting a #156 private entry (whose text never touched the
 *   OS clipboard by design) wiped the user's real clipboard, and so did deleting an entry
 *   superseded by a skipped capture (e.g. a password manager's IS_SENSITIVE clip).
 *
 * D-5 — count-based pruning (the DEFAULT limit type) DELETEd rows without surfacing their
 *   media_path, so pruned media files stayed on disk until the next process start's
 *   cleanupOrphans — an IME process routinely lives for days. The size-based twin already
 *   did reference-checked cleanup; count mode must match.
 */
class ClipboardServiceHygieneTest {

    private val objenesis = ObjenesisStd()

    private lateinit var database: ClipboardDatabase
    private lateinit var mediaManager: ClipboardMediaManager
    private lateinit var cm: android.content.ClipboardManager
    private lateinit var service: ClipboardHistoryService

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
        cm = mockk(relaxed = true)

        service = objenesis.newInstance(ClipboardHistoryService::class.java)
        service.setField("_database", database)
        service.setField("_mediaManager\$delegate", lazyOf(mediaManager))
        service.setField("_cm", cm)
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun givenPrimaryClipText(text: String?) {
        if (text == null) {
            every { cm.primaryClip } returns null
            return
        }
        val item = mockk<ClipData.Item>()
        every { item.text } returns text
        val clipData = mockk<ClipData>()
        every { clipData.itemCount } returns 1
        every { clipData.getItemAt(0) } returns item
        every { cm.primaryClip } returns clipData
    }

    private fun historyOf(vararg entries: ClipboardEntry) {
        every { database.getActiveClipboardEntries() } returns entries.toList()
    }

    // ----------------------------- D-4: clearing keyed on the REAL primary clip

    @Test
    fun deletingANewestPrivateEntryNeverClearsTheOsClipboard() {
        // The private entry's text never reached the OS clipboard (#156 design); the user's
        // real clip is something else entirely. Red today: history[0].content == clip →
        // "isCurrentClip" → clear.
        historyOf(ClipboardEntry(content = "secret note", timestamp = 2L, isPrivate = true))
        givenPrimaryClipText("other users clip")

        service.removeHistoryEntry("secret note")

        verify(exactly = 0) { cm.clearPrimaryClip() }
        verify(exactly = 0) { cm.setPrimaryClip(any()) }
    }

    @Test
    fun deletingAnEntrySupersededByASkippedCaptureNeverClearsTheOsClipboard() {
        // Copy A (captured) then a PM password with IS_SENSITIVE (skipped): the OS clipboard
        // holds the password, history[0] is A. Deleting A must not wipe the password clip.
        historyOf(ClipboardEntry(content = "A", timestamp = 2L))
        givenPrimaryClipText("hunter2-from-pm")

        service.removeHistoryEntry("A")

        verify(exactly = 0) { cm.clearPrimaryClip() }
        verify(exactly = 0) { cm.setPrimaryClip(any()) }
    }

    @Test
    fun deletingAPrivateEntryWhoseTextMatchesTheOsClipboardStillNeverClears() {
        // Even on a coincidental (or pre-private-copy) content match, a private row must
        // never touch the OS clipboard — its text was never placed there by CleverKeys.
        historyOf(ClipboardEntry(content = "same text", timestamp = 2L, isPrivate = true))
        givenPrimaryClipText("same text")

        service.removeHistoryEntry("same text")

        verify(exactly = 0) { cm.clearPrimaryClip() }
        verify(exactly = 0) { cm.setPrimaryClip(any()) }
    }

    @Test
    fun deletingTheEntryActuallyOnTheOsClipboardClearsIt() {
        // Positive control: the clear-on-delete feature still works when the deleted entry
        // IS the current OS clip. (android.jar stub SDK_INT == 0 → the setPrimaryClip
        // clear path; ClipData.newPlainText is stubbed.)
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns mockk()
        historyOf(ClipboardEntry(content = "current", timestamp = 2L))
        givenPrimaryClipText("current")

        service.removeHistoryEntry("current")

        verify(exactly = 1) { cm.setPrimaryClip(any()) }
    }

    @Test
    fun unreadablePrimaryClipMeansNoClear() {
        // Android 10+ can deny primaryClip when the IME isn't focused — best-effort: never
        // clear on uncertainty.
        historyOf(ClipboardEntry(content = "A", timestamp = 2L))
        every { cm.primaryClip } throws SecurityException("denied")

        service.removeHistoryEntry("A")

        verify(exactly = 0) { cm.clearPrimaryClip() }
        verify(exactly = 0) { cm.setPrimaryClip(any()) }
    }

    // ------------------- D-5: count-mode pruning cleans up pruned rows' media files

    @Test
    fun countPruneDeletesTheMediaFileOfAPrunedUnreferencedRow() {
        // Red today at the API level: applySizeLimit returns a bare Int — it cannot even
        // surface the pruned rows' media paths, so no caller can clean up.
        every { database.applySizeLimit(5) } returns Pair(2, listOf("clipboard_media/007/old.png"))
        every { database.isMediaPathReferenced("clipboard_media/007/old.png") } returns false

        service.pruneByCountAndCleanMedia(5)

        verify(exactly = 1) { mediaManager.deleteMedia("clipboard_media/007/old.png") }
    }

    @Test
    fun countPruneKeepsAMediaFileAPinnedOrTodoCopyStillReferences() {
        every { database.applySizeLimit(5) } returns Pair(1, listOf("clipboard_media/007/kept.png"))
        every { database.isMediaPathReferenced("clipboard_media/007/kept.png") } returns true

        service.pruneByCountAndCleanMedia(5)

        verify(exactly = 0) { mediaManager.deleteMedia(any()) }
    }

    @Test
    fun countPruneWithNonPositiveLimitIsANoOp() {
        service.pruneByCountAndCleanMedia(0)

        verify(exactly = 0) { database.applySizeLimit(any()) }
        verify(exactly = 0) { mediaManager.deleteMedia(any()) }
    }

    // ------------------------------------------------------------------ helpers

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
