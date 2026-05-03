package tribixbite.cleverkeys.backup

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.Before
import org.junit.Test

class DictImportPlanApplyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        // Default snapshots — empty current state
        every { prefs.getString(any(), any()) } returns "{}"
        every { prefs.getStringSet(any(), any()) } returns emptySet()
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
}
