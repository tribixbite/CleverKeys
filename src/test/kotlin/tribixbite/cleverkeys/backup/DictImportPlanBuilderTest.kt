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
}
