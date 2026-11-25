package tribixbite.cleverkeys.clipboard

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.ClipboardDatabase
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.TypedFakePreferences
import java.io.File

/**
 * Pre-tag review 2026-11-25 (S8): rows a clipboard restore writes take their expiry from the
 * STORED Entry Duration (`clipboard_history_duration`), never from the in-memory Config.
 *
 * Only the keyboard service's preference listener refreshes `Config.globalConfig()`. In a process
 * where the keyboard is not running (a restore on a new phone, the headless backup activity,
 * Settings alone) Config keeps the values from when it was built, or was never built at all and
 * read as "never expire". A full restore imports the settings first and the clipboard right
 * after, so the restored rows got the OLD duration, not the one just restored. Same class as the
 * learned-vocabulary cap (`UserVocabulary.storedCap`, 2026-11-24).
 *
 * Mock tier: [TypedFakePreferences] throws on a type mismatch like Android, and the safe reader
 * logs the recovery through `android.util.Log`, which is mocked here.
 */
class ClipboardRestoreExpiryTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkAll()

    private val databaseSource =
        File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardDatabase.kt").readText()

    /** The body of `importFromJSON` up to the first per-table helper. */
    private val importBody: String = databaseSource.substring(
        databaseSource.indexOf("fun importFromJSON("),
        databaseSource.indexOf("private fun importHistoryEntries(")
    )

    @Test
    fun theRestoreNeverReadsTheExpiryFromTheInMemoryConfig() {
        assertWithMessage("importFromJSON must not use the Config-backed TTL")
            .that(importBody).doesNotContain("getHistoryTtlMs()")
        assertWithMessage("importFromJSON must not read the in-memory Config")
            .that(importBody).doesNotContain("globalConfig")
        assertWithMessage("importFromJSON reads the app's settings store")
            .that(importBody).contains("restoredRowExpiry(\n                DirectBootAwarePreferences.get_shared_preferences(appContext)")
    }

    @Test
    fun aRestoredRowLivesAsLongAsTheStoredDurationSays() {
        // The settings import just stored one hour; a stale or absent Config plays no part.
        val settings = TypedFakePreferences(mapOf(ClipboardHistoryService.PREF_HISTORY_DURATION to "60"))
        assertThat(ClipboardDatabase.restoredRowExpiry(settings, now)).isEqualTo(now + 60 * minute)
    }

    @Test
    fun neverExpireAndAnUnsetDurationKeepTheRowForever() {
        val never = TypedFakePreferences(mapOf(ClipboardHistoryService.PREF_HISTORY_DURATION to "-1"))
        assertThat(ClipboardDatabase.restoredRowExpiry(never, now)).isEqualTo(Long.MAX_VALUE)
        assertThat(ClipboardDatabase.restoredRowExpiry(TypedFakePreferences(), now)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun aDurationStoredAsAnIntIsStillRead() {
        // An import may store a numeric string setting with another type; the safe reader recovers.
        val settings = TypedFakePreferences(mapOf(ClipboardHistoryService.PREF_HISTORY_DURATION to 30))
        assertThat(ClipboardDatabase.restoredRowExpiry(settings, now)).isEqualTo(now + 30 * minute)
    }

    @Test
    fun anUnparsableDurationFallsBackToNeverExpire() {
        val settings = TypedFakePreferences(mapOf(ClipboardHistoryService.PREF_HISTORY_DURATION to "soon"))
        assertThat(ClipboardDatabase.restoredRowExpiry(settings, now)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun aZeroDurationExpiresAtOnceAndAHugeOneSaturates() {
        val zero = TypedFakePreferences(mapOf(ClipboardHistoryService.PREF_HISTORY_DURATION to "0"))
        assertThat(ClipboardDatabase.restoredRowExpiry(zero, now)).isEqualTo(now)
        assertThat(ClipboardHistoryService.expiryAt(Long.MAX_VALUE - 1, now)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun theKeyboardsOwnTtlUsesTheSameMinutesToMillisRule() {
        assertThat(ClipboardHistoryService.ttlMsForDurationMinutes(-1)).isEqualTo(Long.MAX_VALUE)
        assertThat(ClipboardHistoryService.ttlMsForDurationMinutes(5)).isEqualTo(5 * minute)
    }
}
