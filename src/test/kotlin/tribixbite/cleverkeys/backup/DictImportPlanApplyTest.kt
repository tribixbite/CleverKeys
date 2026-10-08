package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class DictImportPlanApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        // Default snapshots — empty current state
        every { prefs.getString(any(), any()) } returns "{}"
        every { prefs.getStringSet(any(), any()) } returns emptySet()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun apply_singleCommit_regardlessOfLanguageCount() {
        // Three languages — legacy code would call apply() three times
        // (BackupRestoreManager.kt:1248). New applier MUST coalesce to one commit().
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf(
                "en" to LangChanges(mapOf("foo" to 10), emptyList()),
                "de" to LangChanges(mapOf("hallo" to 20), emptyList()),
                "fr" to LangChanges(mapOf("bonjour" to 30), emptyList()),
            ),
            mergedCustomWordsByLang = mapOf(
                "en" to mapOf("foo" to 10),
                "de" to mapOf("hallo" to 20),
                "fr" to mapOf("bonjour" to 30),
            ),
            mergedDisabledWordsByLang = emptyMap(),
        )

        DictImportApplier.apply(
            plan = plan,
            excludedCustom = emptySet(),
            excludedDisabled = emptySet(),
            prefs = prefs,
        )

        verify(exactly = 1) { editor.commit() }
        verify(exactly = 3) { editor.putString(any(), any()) }   // 3 langs × custom
    }

    @Test
    fun apply_excludesByLangWord() {
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
            mergedCustomWordsByLang = mapOf("en" to mapOf("foo" to 1, "bar" to 2)),
            mergedDisabledWordsByLang = emptyMap(),
        )

        val (customApplied, _) = DictImportApplier.apply(
            plan = plan,
            excludedCustom = setOf(LangWord("en", "foo")),
            excludedDisabled = emptySet(),
            prefs = prefs,
        )

        assertThat(customApplied).isEqualTo(1)
        // Verify the editor saw only "bar" in the saved JSON. Captured via
        // a slot:
        val saved = slot<String>()
        verify { editor.putString(any(), capture(saved)) }
        assertThat(saved.captured).contains("bar")
        assertThat(saved.captured).doesNotContain("foo")
    }

    @Test
    fun apply_existingWord_notRecounted() {
        every { prefs.getString(any(), any()) } returns """{"foo":99}"""    // user already has foo
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(mapOf("foo" to 1, "bar" to 2), emptyList())),
            mergedCustomWordsByLang = mapOf("en" to mapOf("foo" to 1, "bar" to 2)),
            mergedDisabledWordsByLang = emptyMap(),
        )

        val (customApplied, _) = DictImportApplier.apply(
            plan, emptySet(), emptySet(), prefs
        )

        // "foo" already present — only "bar" counted.
        assertThat(customApplied).isEqualTo(1)
    }

    @Test
    fun apply_disabledWords_singleCommit() {
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(emptyMap(), listOf("bad"))),
            mergedCustomWordsByLang = emptyMap(),
            mergedDisabledWordsByLang = mapOf("en" to setOf("bad")),
        )

        val (_, disabledApplied) = DictImportApplier.apply(
            plan, emptySet(), emptySet(), prefs
        )

        assertThat(disabledApplied).isEqualTo(1)
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 1) { editor.putStringSet(any(), any()) }
    }

    @Test
    fun apply_commitFails_returnsCountsButLogsWarning() {
        // Symmetric to SettingsImportPlanApplyTest.apply_commitFails_*: when
        // commit() returns false, the applier still returns the would-be
        // counts (caller surfaces them in the result dialog) and emits a
        // single Log.w warning. No throw.
        every { editor.commit() } returns false
        val plan = DictImportPlan(
            sourceVersion = "1.4.0",
            perLanguage = mapOf("en" to LangChanges(mapOf("foo" to 1), emptyList())),
            mergedCustomWordsByLang = mapOf("en" to mapOf("foo" to 1)),
            mergedDisabledWordsByLang = emptyMap(),
        )

        val (customApplied, _) = DictImportApplier.apply(
            plan, emptySet(), emptySet(), prefs
        )

        assertThat(customApplied).isEqualTo(1)
        verify {
            Log.w("DictImportApplier", match<String> {
                it.contains("editor.commit() returned false")
            })
        }
    }

    // ── user swipe priority (2026-10-08) ─────────────────────────────────────────

    /** Capture what the applier writes per key. */
    private fun captureWrites(): MutableMap<String, String> {
        val writes = mutableMapOf<String, String>()
        every { editor.putString(any(), any()) } answers {
            writes[firstArg()] = secondArg()
            editor
        }
        return writes
    }

    /**
     * Full round trip: the export section ([tribixbite.cleverkeys.SwipePriority.toBackupSection])
     * → the backup JSON → [DictImportPlanBuilder] → [DictImportApplier] → the stored levels.
     */
    @Test
    fun swipePriorities_roundTripThroughABackup() {
        val levels = mapOf(
            "en" to mapOf(
                "adb" to tribixbite.cleverkeys.SwipePriority.HIGHEST,
                "wet" to tribixbite.cleverkeys.SwipePriority.HIGH,
            ),
        )
        val root = com.google.gson.JsonObject()
        root.add("custom_words_by_language", com.google.gson.JsonParser.parseString("""{"en":{"adb":255,"wet":255}}"""))
        root.add(tribixbite.cleverkeys.SwipePriority.BACKUP_SECTION, tribixbite.cleverkeys.SwipePriority.toBackupSection(levels))
        val plan = DictImportPlanBuilder.fromJson(root.toString(), emptyMap(), emptyMap())
        assertThat(plan.mergedSwipePrioritiesByLang).isEqualTo(levels)

        val writes = captureWrites()
        DictImportApplier.apply(plan, emptySet(), emptySet(), prefs)

        assertThat(tribixbite.cleverkeys.SwipePriority.parseMap(writes["swipe_priority_en"]))
            .isEqualTo(levels.getValue("en"))
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun swipePriorities_applyOnlyToImportedWordsAndNeverOverrideALocalLevel() {
        // Local state: `wet` already a custom word raised to HIGH here.
        every { prefs.getString("custom_words_en", any()) } returns """{"wet":255}"""
        every { prefs.getString("swipe_priority_en", any()) } returns """{"wet":1}"""
        val plan = DictImportPlan(
            sourceVersion = "2.0.0",
            perLanguage = mapOf("en" to LangChanges(mapOf("adb" to 255, "skip" to 255), emptyList())),
            mergedCustomWordsByLang = mapOf("en" to mapOf("adb" to 255, "skip" to 255, "wet" to 255)),
            mergedDisabledWordsByLang = emptyMap(),
            mergedSwipePrioritiesByLang = mapOf(
                "en" to mapOf(
                    "adb" to tribixbite.cleverkeys.SwipePriority.HIGHEST,
                    "wet" to tribixbite.cleverkeys.SwipePriority.HIGHEST, // local HIGH wins
                    "skip" to tribixbite.cleverkeys.SwipePriority.HIGH,   // deselected in preview
                    "nowhere" to tribixbite.cleverkeys.SwipePriority.HIGH, // not a custom word
                ),
            ),
        )
        val writes = captureWrites()
        DictImportApplier.apply(plan, setOf(LangWord("en", "skip")), emptySet(), prefs)

        assertThat(tribixbite.cleverkeys.SwipePriority.parseMap(writes["swipe_priority_en"])).containsExactly(
            "wet", tribixbite.cleverkeys.SwipePriority.HIGH,
            "adb", tribixbite.cleverkeys.SwipePriority.HIGHEST,
        )
    }

    @Test
    fun swipePriorities_absentSectionWritesNothing() {
        val plan = DictImportPlanBuilder.fromJson("""{"custom_words_by_language":{"en":{"adb":255}}}""", emptyMap(), emptyMap())
        assertThat(plan.mergedSwipePrioritiesByLang).isEmpty()
        val writes = captureWrites()
        DictImportApplier.apply(plan, emptySet(), emptySet(), prefs)
        assertThat(writes.keys).containsExactly("custom_words_en")
    }
}
