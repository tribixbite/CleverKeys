package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DictImportPlanBuilderTest {

    @Test
    fun v2Format_perLanguageDeltas() {
        val json = """{"custom_words_by_language":{"en":{"foo":50,"bar":100}}}"""

        val plan = DictImportPlanBuilder.fromJson(
            jsonString = json,
            currentCustomByLang = emptyMap(),
            currentDisabledByLang = emptyMap(),
        )

        val en = plan.perLanguage["en"]
        assertThat(en).isNotNull()
        assertThat(en!!.newCustomWords).containsEntry("foo", 50)
        assertThat(en.newCustomWords).containsEntry("bar", 100)
        assertThat(en.newCustomWords).hasSize(2)
    }

    @Test
    fun mixed_v2AndLegacy_firstWriterWins() {
        // Same word "foo" appears in all three sources with different
        // frequencies. Legacy code achieves first-writer-wins via the
        // !containsKey check at BackupRestoreManager.kt:1242 — the new
        // builder must replicate exactly via LinkedHashMap.putIfAbsent.
        val json = """{
            "custom_words_by_language":{"en":{"foo":10}},
            "custom_words":{"foo":20},
            "user_words":[{"word":"foo","frequency":30}]
        }""".trimIndent()

        val plan = DictImportPlanBuilder.fromJson(
            jsonString = json,
            currentCustomByLang = emptyMap(),
            currentDisabledByLang = emptyMap(),
        )

        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords["foo"]).isEqualTo(10)
        assertThat(en.newCustomWords).hasSize(1)
        // mergedCustomWordsByLang should also reflect this
        assertThat(plan.mergedCustomWordsByLang["en"]).containsExactly("foo", 10)
    }
}
