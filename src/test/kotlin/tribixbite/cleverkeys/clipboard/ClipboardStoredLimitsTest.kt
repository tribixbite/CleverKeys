package tribixbite.cleverkeys.clipboard

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardHistoryLimits
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.ClipboardMediaManager
import tribixbite.cleverkeys.ClipboardTab
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.EditEntryResult
import tribixbite.cleverkeys.TypedFakePreferences

/**
 * Pre-tag follow-up to S8 (2026-11-25): every History limit the clipboard service enforces —
 * the limit type, the entry count, the total size, the per-item size and the Entry Duration —
 * comes from the STORED settings, never from the in-memory Config.
 *
 * Only the keyboard service's preference listener refreshes `Config.globalConfig()`. A full
 * restore in a process where the keyboard is not running (a new phone, the headless backup
 * activity, Settings alone) writes the settings and leaves Config holding the old values, so the
 * next capture used to be pruned by the OLD limit type and values. Here Config always holds the
 * old values and the settings store the new ones; every case fails if the service reads Config.
 *
 * Mock tier: the service is built without its constructor (objenesis) over a relaxed database.
 */
class ClipboardStoredLimitsTest {

    private val objenesis = ObjenesisStd()
    private lateinit var database: ClipboardDatabase
    private lateinit var service: ClipboardHistoryService
    private lateinit var settings: TypedFakePreferences
    private val minute = 60_000L

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        // The stale in-memory Config: what Settings showed before the restore.
        val stale = mockk<Config>(relaxed = true)
        stale.clipboard_history_enabled = true
        stale.clipboard_limit_type = "count"
        stale.clipboard_history_limit = 50
        stale.clipboard_size_limit_mb = 5
        stale.clipboard_max_item_size_kb = 1024
        stale.clipboard_history_duration = -1
        mockkObject(Config.Companion)
        every { Config.globalConfigOrNull() } returns stale
        every { Config.globalConfig() } returns stale

        // The settings store the restore just wrote (each test fills it).
        settings = TypedFakePreferences()
        // A @JvmStatic member of an object: the static entry point is what callers reach.
        mockkStatic(DirectBootAwarePreferences::class)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns settings

        database = mockk(relaxed = true)
        every { database.addClipboardEntry(any(), any(), any(), any()) } returns true
        every { database.applySizeLimit(any()) } returns Pair(0, emptyList())
        every { database.applySizeLimitBytes(any(), any()) } returns Pair(0, emptyList())
        service = objenesis.newInstance(ClipboardHistoryService::class.java)
        service.setField("_context", mockk<Context>(relaxed = true))
        service.setField("_database", database)
        service.setField("_mediaManager\$delegate", lazyOf(mockk<ClipboardMediaManager>(relaxed = true)))
    }

    @After
    fun teardown() = unmockkAll()

    private fun merge() = service.addMergedClip(ClipboardBulkPlans.MergePlan("a\nb", 2, 0, false)).getOrThrow()

    @Test
    fun aRestoredByteLimitPrunesByTotalSize() {
        // Import types: the import table stores these as String; Settings stores the MB as Int.
        settings.values["clipboard_limit_type"] = "size"
        settings.values["clipboard_size_limit_mb"] = 1

        merge()

        verify(exactly = 1) { database.applySizeLimitBytes(1, any()) }
        verify(exactly = 0) { database.applySizeLimit(any()) }
    }

    @Test
    fun aRestoredSmallerCountPrunesToIt() {
        settings.values["clipboard_limit_type"] = "count"
        settings.values["clipboard_history_limit"] = "10"

        merge()

        verify(exactly = 1) { database.applySizeLimit(10) }
        verify(exactly = 0) { database.applySizeLimitBytes(any(), any()) }
    }

    @Test
    fun theListShowsAtMostTheStoredCount() {
        settings.values["clipboard_history_limit"] = 2
        every { database.getActiveClipboardEntries() } returns
            (1..5).map { ClipboardEntry("e$it", it.toLong(), rowId = it.toLong()) }

        assertThat(service.clearExpiredAndGetHistory().map { it.content }).containsExactly("e1", "e2").inOrder()
    }

    @Test
    fun anEditLargerThanTheStoredItemLimitIsRefused() {
        settings.values["clipboard_max_item_size_kb"] = 64
        val big = "x".repeat(100 * 1024)

        val outcome = service.editEntryFollowingClipboard("old", big, ClipboardTab.HISTORY, fillEmptyClipboard = false)

        assertThat(outcome.result).isEqualTo(EditEntryResult.InvalidContent)
        verify(exactly = 0) { database.updateHistoryEntryContent(any(), any()) }
    }

    @Test
    fun aNewClippingExpiresByTheStoredDuration() {
        settings.values[ClipboardHistoryService.PREF_HISTORY_DURATION] = "60"
        val expiry = slot<Long>()
        every { database.addClipboardEntry(any(), capture(expiry), any(), any()) } returns true
        val before = System.currentTimeMillis()

        merge()

        assertWithMessage("an hour from the merge, not never (the stale Config's -1)")
            .that(expiry.captured).isIn(com.google.common.collect.Range.closed(
                before + 60 * minute, System.currentTimeMillis() + 60 * minute))
    }

    @Test
    fun theReaderTakesEitherStoredTypeAndClampsLikeConfig() {
        assertThat(ClipboardHistoryLimits.fromSettings(TypedFakePreferences())).isEqualTo(
            ClipboardHistoryLimits(ClipboardHistoryLimits.LIMIT_TYPE_COUNT, 0, 5, 512))
        val imported = TypedFakePreferences(mapOf(
            "clipboard_limit_type" to "size",
            "clipboard_history_limit" to "900",       // above SettingsRanges' 500
            "clipboard_size_limit_mb" to 3,           // Int, as Settings writes it
            "clipboard_max_item_size_kb" to " 8 ",    // below the 64 floor, padded
        ))
        val limits = ClipboardHistoryLimits.fromSettings(imported)
        assertThat(limits).isEqualTo(ClipboardHistoryLimits("size", 500, 3, 64))
        assertThat(limits.bySize).isTrue()
    }

    @Test
    fun configRefreshAndTheServiceShareTheReader() {
        // One parser: Config.refresh assigns its four clipboard fields from it.
        val config = java.io.File("src/main/kotlin/tribixbite/cleverkeys/Config.kt").readText()
        assertThat(config).contains("ClipboardHistoryLimits.fromSettings(_prefs)")
        val service = java.io.File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt").readText()
        assertWithMessage("no clipboard limit is read from the in-memory Config")
            .that(service).doesNotContainMatch(
                """globalConfig(OrNull)?\(\)\??\.clipboard_(history_limit|limit_type|size_limit_mb|max_item_size_kb|history_duration)""")
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
