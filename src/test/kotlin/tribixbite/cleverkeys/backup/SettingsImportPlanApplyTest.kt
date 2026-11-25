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

    @Test
    fun apply_addedChangeOnFreshInstall_reportsNoDrift() {
        // G-4 (comprehensive audit 2026-09-06): for an ADDED change the builder stores the
        // EFFECTIVE default as `current` (e.g. Bool(false)), while the key is still ABSENT
        // from prefs. Comparing that default against the absent key counted every
        // fresh-install ADDED row as "drifted" — a 30-row import onto a clean install
        // logged "30 keys drifted" with zero concurrent activity.
        every { prefs.all } returns emptyMap()
        val plan = planWith(
            SettingsChange("k", PrefValue.Bool(false), PrefValue.Bool(true), ChangeType.ADDED)
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        verify(exactly = 1) { editor.putBoolean("k", true) }
        assertThat(result.driftCount).isEqualTo(0)
    }

    @Test
    fun apply_addedChangeWhoseKeyAppearedSinceBuild_stillCountsAsDrift() {
        // The ADDED exemption is only for the still-absent key; a key that got WRITTEN
        // between preview and apply (a real race) must keep tripping the diagnostic.
        every { prefs.all } returns mapOf("k" to true)
        val plan = planWith(
            SettingsChange("k", PrefValue.Bool(false), PrefValue.Bool(true), ChangeType.ADDED)
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

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
    fun apply_commitFails_throwsAReadWriteFailureAndAppliesNoShortSwipes() {
        // Review 2026-11-14 M7: commit() returning false (disk full / IPC failure) wrote
        // nothing, yet the dialog said "Applied: N". It is now a classified failure, and the
        // short swipes (applied after the commit) are never imported.
        every { editor.commit() } returns false
        val plan = planWith(
            SettingsChange("k", PrefValue.Unset, PrefValue.IntV(1), ChangeType.ADDED)
        ).copy(shortSwipeImportRawJson = """{"version":2,"mappings":{"a":{}}}""", shortSwipeImportSize = 1)

        val thrown = runCatching {
            runBlocking {
                SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs, ssImporter)
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(tribixbite.cleverkeys.ClassifiedIoException::class.java)
        assertThat((thrown as tribixbite.cleverkeys.ClassifiedIoException).reason)
            .isEqualTo(tribixbite.cleverkeys.IoFailureReason.READ_WRITE)
        coVerify(exactly = 0) { ssImporter.importFromJson(any(), any()) }
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

    /**
     * The Settings screen, `Config.set_clipboard_history_limit` and Reset All store the three
     * clipboard limits as Int; an import stored the String form the backup carries, so the stored
     * TYPE flipped with every restore (Pixel, 2026-11-24). Readers accept both, but the type must
     * not depend on whether the value was restored.
     */
    @Test
    fun apply_clipboardLimits_areStoredAsTheIntTheSettingsScreenWrites() {
        val plan = planWith(
            SettingsChange("clipboard_history_limit", PrefValue.Unset, PrefValue.Str("50"), ChangeType.ADDED),
            SettingsChange("clipboard_max_item_size_kb", PrefValue.Unset, PrefValue.Str("500"), ChangeType.ADDED),
            SettingsChange("clipboard_size_limit_mb", PrefValue.Unset, PrefValue.Str("5"), ChangeType.ADDED),
        )

        runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        verify(exactly = 1) { editor.putInt("clipboard_history_limit", 50) }
        verify(exactly = 1) { editor.putInt("clipboard_max_item_size_kb", 500) }
        verify(exactly = 1) { editor.putInt("clipboard_size_limit_mb", 5) }
        verify(exactly = 0) { editor.putString(match { it.startsWith("clipboard_") }, any()) }
    }

    /** The plan's String form of a limit stored as Int is the same value, not a drift. */
    @Test
    fun apply_aLimitStoredAsInt_isNotADriftFromItsStringForm() {
        every { prefs.all } returns mapOf("clipboard_history_limit" to 50)
        val plan = planWith(
            SettingsChange("clipboard_history_limit", PrefValue.Str("50"), PrefValue.Str("80"), ChangeType.MODIFIED),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        assertThat(result.driftCount).isEqualTo(0)
        verify(exactly = 1) { editor.putInt("clipboard_history_limit", 80) }
    }

    // ── 1.x settings-file dictionary words (review 2026-11-25 S9) ─────────────────────────

    private fun legacyPlan(dict: DictImportPlan, vararg changes: SettingsChange) =
        planWith(*changes).copy(legacyDictionary = dict)

    private fun legacyDict(
        custom: Map<String, Int> = emptyMap(),
        disabled: List<String> = emptyList(),
        priorities: Map<String, tribixbite.cleverkeys.SwipePriority> = emptyMap(),
    ) = DictImportPlan(
        sourceVersion = "1.2.9",
        perLanguage = mapOf("en" to LangChanges(custom, disabled)),
        mergedCustomWordsByLang = mapOf("en" to custom),
        mergedDisabledWordsByLang = if (disabled.isEmpty()) emptyMap() else mapOf("en" to disabled.toSet()),
        mergedSwipePrioritiesByLang = if (priorities.isEmpty()) emptyMap() else mapOf("en" to priorities),
        newSwipePriorityCount = priorities.size,
    )

    /** A failed dictionary write was logged while the import reported success. */
    @Test
    fun legacyWords_aFailedWriteFailsTheImportBeforeAnySettingIsWritten() {
        every { editor.commit() } returns false
        val plan = legacyPlan(
            legacyDict(custom = mapOf("zazen" to 120)),
            SettingsChange("b", PrefValue.Unset, PrefValue.Bool(true), ChangeType.ADDED),
        )

        val thrown = runCatching {
            runBlocking { SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter) }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(tribixbite.cleverkeys.ClassifiedIoException::class.java)
        verify(exactly = 0) { editor.putBoolean("b", any()) }
    }

    @Test
    fun legacyWords_leftOutInThePreview_areNotWritten() {
        val written = mutableMapOf<String, String>()
        every { editor.putString(any(), any()) } answers { written[firstArg()] = secondArg(); editor }
        val plan = legacyPlan(legacyDict(custom = mapOf("zazen" to 120, "Bostonian" to 100)))

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter,
                legacyWords = LegacyWordExclusions(custom = setOf(LangWord("en", "Bostonian"))),
            )
        }

        assertThat(written["custom_words_en"]).contains("zazen")
        assertThat(written["custom_words_en"]).doesNotContain("Bostonian")
        assertThat(result.customWordsAdded).isEqualTo(1)
    }

    @Test
    fun legacyWords_allLeftOut_writeNoDictionary() {
        val plan = legacyPlan(legacyDict(custom = mapOf("zazen" to 120), priorities = mapOf("zazen" to tribixbite.cleverkeys.SwipePriority.HIGH)))

        val result = runBlocking {
            SettingsImportApplier.apply(
                plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter,
                legacyWords = LegacyWordExclusions(custom = setOf(LangWord("en", "zazen")), includePriorities = false),
            )
        }

        assertThat(result.dictionaryChanged).isFalse()
        verify(exactly = 0) { editor.putString("custom_words_en", any()) }
    }

    /** A priorities-only change did not reload the keyboard (the broadcast keyed on words). */
    @Test
    fun aPriorityOnlyLegacyChange_countsAsADictionaryChange() {
        every { prefs.getString("custom_words_en", any()) } returns """{"zazen":120}"""
        val plan = legacyPlan(
            legacyDict(custom = mapOf("zazen" to 120), priorities = mapOf("zazen" to tribixbite.cleverkeys.SwipePriority.HIGHEST))
                .copy(perLanguage = emptyMap()),
        )

        val result = runBlocking {
            SettingsImportApplier.apply(plan, emptySet(), ShortSwipeImportMode.SKIP, prefs, ssImporter)
        }

        assertThat(result.customWordsAdded).isEqualTo(0)
        assertThat(result.swipePrioritiesAdded).isEqualTo(1)
        assertThat(result.dictionaryChanged).isTrue()
    }
}
