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

    @Test
    fun existingWord_filteredFromDeltas() {
        val json = """{"custom_words_by_language":{"en":{"foo":10,"bar":20}}}"""
        val current = mapOf("en" to mapOf("foo" to 5))   // user already has "foo"

        val plan = DictImportPlanBuilder.fromJson(json, current, emptyMap())

        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords).doesNotContainKey("foo")    // filtered
        assertThat(en.newCustomWords).containsKey("bar")           // genuinely new
    }

    @Test
    fun legacyDisabledWords_routedToEnglish() {
        val json = """{"disabled_words":["bad","words"]}"""

        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())

        val en = plan.perLanguage["en"]!!
        assertThat(en.newDisabledWords).containsExactly("bad", "words")
        assertThat(en.newCustomWords).isEmpty()
    }

    @Test
    fun caseDifferentVariants_renderAsDistinctEntries() {
        // "foo" and "FOO" are distinct in current importDictionaries —
        // preserved here so the preview UI shows them as two rows.
        val json = """{"custom_words_by_language":{"en":{"foo":10,"FOO":20}}}"""

        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())

        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords).containsExactly("foo", 10, "FOO", 20)
    }

    @Test
    fun legacyUserWords_bareStringEntries_acceptedWithDefaultFrequency() {
        val json = """{"user_words":["foo","bar"]}"""
        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())
        val en = plan.perLanguage["en"]!!
        // Both bare-string entries land with DEFAULT_USER_WORD_FREQ = 100.
        assertThat(en.newCustomWords).containsExactly("foo", 100, "bar", 100)
    }

    @Test
    fun legacyUserWords_objectMissingFrequency_usesDefault() {
        val json = """{"user_words":[{"word":"foo"}]}"""
        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())
        val en = plan.perLanguage["en"]!!
        assertThat(en.newCustomWords["foo"]).isEqualTo(100)
    }

    @Test
    fun learnedDataSections_areCountedAndCarriedInThePlan() {
        val json = """{
            "custom_words_by_language":{"en":{"visible":50}},
            "learned_bigrams_by_language":{
                "en":[{"a":1},{"b":2}],
                "fr":[{"c":3}]
            },
            "learned_trigrams_by_language":{"en":[{"t":1}]},
            "user_vocabulary":[{"word":"one"},{"word":"two"}]
        }""".trimIndent()

        val plan = DictImportPlanBuilder.fromJson(json, emptyMap(), emptyMap())

        assertThat(plan.learnedData.bigramEntries).isEqualTo(3)
        assertThat(plan.learnedData.trigramEntries).isEqualTo(1)
        assertThat(plan.learnedData.vocabularyWords).isEqualTo(2)
        assertThat(plan.learnedData.vocabularyPresent).isTrue()
        assertThat(plan.learnedData.hasEffect).isTrue()
        assertThat(plan.learnedData.totalEntries).isEqualTo(6)
        assertThat(plan.learnedData.rawJson).contains("learned_bigrams_by_language")
        assertThat(plan.learnedData.rawJson).contains("learned_trigrams_by_language")
        assertThat(plan.learnedData.rawJson).contains("user_vocabulary")
        assertThat(plan.learnedData.rawJson).doesNotContain("custom_words_by_language")
    }

    @Test
    fun emptyVocabularySection_isStillAPreviewedReplaceOperation() {
        val plan = DictImportPlanBuilder.fromJson(
            """{"user_vocabulary":[]}""",
            emptyMap(),
            emptyMap(),
        )

        assertThat(plan.perLanguage).isEmpty()
        assertThat(plan.learnedData.vocabularyWords).isEqualTo(0)
        assertThat(plan.learnedData.vocabularyPresent).isTrue()
        assertThat(plan.learnedData.hasEffect).isTrue()
        assertThat(plan.learnedData.rawJson).contains("user_vocabulary")
    }

    /**
     * ARC-094 no-noise guarantee (2026-09-01 audit fill-in): a payload carrying NO learned
     * sections must yield the inert NONE plan, so the preview renders no learned-data card.
     * This was guaranteed by the default but never asserted — an accidental fallback to a
     * non-NONE default would have added a phantom row to every dictionary-only import.
     */
    @Test
    fun payloadWithoutLearnedSections_yieldsNonePlan_soPreviewAddsNoRow() {
        val plan = DictImportPlanBuilder.fromJson(
            jsonString = """{"custom_words_by_language":{"en":{"foo":50}}}""",
            currentCustomByLang = emptyMap(),
            currentDisabledByLang = emptyMap(),
        )

        // Value equality, deliberately not identity: the builder constructs an equal inert
        // plan rather than returning the NONE singleton, and the no-noise guarantee hinges
        // on hasEffect/rawJson, not on which instance carries them.
        assertThat(plan.learnedData).isEqualTo(LearnedDataImportPlan.NONE)
        assertThat(plan.learnedData.hasEffect).isFalse()
        assertThat(plan.learnedData.rawJson).isNull()
    }
}
