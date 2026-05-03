package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class BackupRestoreManagerHeadlessTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        every { prefs.all } returns emptyMap()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun applierWithReplaceMode_invokesImportFromJsonWithMergeFalse() {
        // Verifies the contract that REPLACE mode → ShortSwipeImporter receives
        // merge=false. The headless `importConfig` public method passes
        // ShortSwipeImportMode.REPLACE so it preserves the legacy destructive
        // semantics. Don't change importConfig's mode without a coordinated
        // Termux-automation announcement — this test guards the contract.
        val ssImporter: ShortSwipeImporter = mockk(relaxed = true) {
            coEvery { importFromJson(any(), any()) } returns 0
        }

        val plan = SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = ScreenMetrics(1080, 2400, 3.0f),
            currentScreen = ScreenMetrics(1080, 2400, 3.0f),
            changes = emptyList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = emptyList(),
            shortSwipeImportSize = 1,
            shortSwipeImportRawJson = """{"q":{"up":"DEL"}}""",
        )

        kotlinx.coroutines.runBlocking {
            SettingsImportApplier.apply(
                plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs, ssImporter
            )
        }

        coVerify(exactly = 1) { ssImporter.importFromJson(any(), merge = false) }
    }
}
