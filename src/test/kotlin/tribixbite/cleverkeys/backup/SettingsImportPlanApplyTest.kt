package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class SettingsImportPlanApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var ssImporter: ShortSwipeImporter

    private val screen = ScreenMetrics(1080, 2400, 3.0f)

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
        ssImporter = mockk(relaxed = true)
        coEvery { ssImporter.importFromJson(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun planWith(vararg changes: SettingsChange) = SettingsImportPlan(
        sourceVersion = "1.4.0",
        sourceScreen = screen,
        currentScreen = screen,
        changes = changes.toList(),
        parseSkippedKeys = emptyList(),
        internalRemoves = emptyList(),
        shortSwipeImportSize = 0,
        shortSwipeImportRawJson = null,
    )

    @Test
    fun apply_eachPrefValueVariant_dispatchesCorrectEditorMethod() {
        val plan = planWith(
            SettingsChange("b", PrefValue.Unset, PrefValue.Bool(true), ChangeType.ADDED),
            SettingsChange("i", PrefValue.Unset, PrefValue.IntV(42), ChangeType.ADDED),
            SettingsChange("f", PrefValue.Unset, PrefValue.FloatV(3.14f), ChangeType.ADDED),
            SettingsChange("s", PrefValue.Unset, PrefValue.Str("hi"), ChangeType.ADDED),
            SettingsChange("layouts", PrefValue.Unset, PrefValue.JsonBlob("[]"), ChangeType.ADDED),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = plan,
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }

        verify(exactly = 1) { editor.putBoolean("b", true) }
        verify(exactly = 1) { editor.putInt("i", 42) }
        verify(exactly = 1) { editor.putFloat("f", 3.14f) }
        verify(exactly = 1) { editor.putString("s", "hi") }
        verify(exactly = 1) { editor.putString("layouts", "[]") }
        verify(exactly = 1) { editor.commit() }
        assertThat(result.importedCount).isEqualTo(5)
        assertThat(result.excludedByUserCount).isEqualTo(0)
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }

    @Test
    fun apply_omitsExcludedKeys() {
        val plan = planWith(
            SettingsChange("a", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED),
            SettingsChange("b", PrefValue.Unset, PrefValue.IntV(2), ChangeType.ADDED),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = plan,
                excludedKeys = setOf("a"),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }

        verify(exactly = 0) { editor.putInt("a", any()) }
        verify(exactly = 1) { editor.putInt("b", 2) }
        assertThat(result.importedCount).isEqualTo(1)
        assertThat(result.excludedByUserCount).isEqualTo(1)
    }

    @Test
    fun apply_executesInternalRemovesEvenWhenChangesEmpty() {
        val plan = SettingsImportPlan(
            sourceVersion = "1.4.0",
            sourceScreen = screen,
            currentScreen = screen,
            changes = emptyList(),
            parseSkippedKeys = emptyList(),
            internalRemoves = listOf("legacy_key"),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )

        runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        verify(exactly = 1) { editor.remove("legacy_key") }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun apply_driftCount_incrementsWhenCurrentChangedSinceBuild() {
        // Plan recorded current value = 50 at build time. By the time apply runs,
        // prefs.all returns 99 — drift!
        every { prefs.all } returns mapOf("key_height" to 99)
        val plan = planWith(
            SettingsChange("key_height", PrefValue.IntV(50), PrefValue.IntV(60), ChangeType.MODIFIED)
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        // The user's selection is still honored — value 60 is still written.
        verify(exactly = 1) { editor.putInt("key_height", 60) }
        // But drift is logged.
        assertThat(result.driftCount).isEqualTo(1)
    }

    private fun planWithShortSwipe(rawJson: String, size: Int = 1) = SettingsImportPlan(
        sourceVersion = "1.4.0",
        sourceScreen = screen,
        currentScreen = screen,
        changes = emptyList(),
        parseSkippedKeys = emptyList(),
        internalRemoves = emptyList(),
        shortSwipeImportSize = size,
        shortSwipeImportRawJson = rawJson,
    )

    @Test
    fun apply_shortSwipeSkip_doesNotCallImporter() {
        runBlocking {
            SettingsImportApplier.apply(
                plan = planWithShortSwipe("""{"q":{"up":"DEL"}}"""),
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.SKIP,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }

    @Test
    fun apply_shortSwipeMerge_invokesImporterWithMergeTrue() {
        coEvery { ssImporter.importFromJson(any(), merge = true) } returns 3
        val result = runBlocking {
            SettingsImportApplier.apply(
                plan = planWithShortSwipe("""{"q":{"up":"DEL"}}"""),
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.MERGE,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }
        coVerify(exactly = 1) { ssImporter.importFromJson(any(), merge = true) }
        assertThat(result.shortSwipeCustomizationsImported).isEqualTo(3)
    }

    @Test
    fun apply_shortSwipeReplace_invokesImporterWithMergeFalse() {
        coEvery { ssImporter.importFromJson(any(), merge = false) } returns 2
        runBlocking {
            SettingsImportApplier.apply(
                plan = planWithShortSwipe("""{"q":{"up":"DEL"}}"""),
                excludedKeys = emptySet(),
                shortSwipeMode = ShortSwipeImportMode.REPLACE,
                prefs = prefs,
                shortSwipeImporter = ssImporter,
            )
        }
        coVerify(exactly = 1) { ssImporter.importFromJson(any(), merge = false) }
    }

    @Test
    fun apply_shortSwipeNullRawJson_skipsRegardlessOfMode() {
        val plan = planWith()   // no short-swipe at all
        runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.MERGE, prefs, ssImporter)
        }
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
    }

    @Test
    fun apply_commitFails_returnsResultButLogsWarning() {
        // commit() returning false is rare (disk full / IPC failure) but
        // must not crash — the result is still returned so the caller can
        // surface the import counts; a Log.w warning is the only side effect.
        every { editor.commit() } returns false
        val plan = planWith(
            SettingsChange("k", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED)
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        // The result still reports "applied" because commit was attempted.
        assertThat(result.importedCount).isEqualTo(1)
        // And the warning was emitted.
        verify {
            Log.w("SettingsImportApplier", match<String> {
                it.contains("editor.commit() returned false")
            })
        }
    }

    @Test
    fun apply_unsetSentinelInDispatch_throws() {
        // PrefValue.Unset must never reach dispatchPut — internalRemoves is the
        // correct path. Guard against a future bug where someone wires Unset
        // into a Put op.
        val plan = planWith(
            SettingsChange("k", PrefValue.Unset, PrefValue.Unset, ChangeType.ADDED)
        )

        try {
            runBlocking {
                SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
            }
            assert(false) { "Expected IllegalStateException" }
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("PrefValue.Unset is illegal")
        }
    }
}
