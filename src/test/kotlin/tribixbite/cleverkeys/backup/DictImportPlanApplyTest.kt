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
}
