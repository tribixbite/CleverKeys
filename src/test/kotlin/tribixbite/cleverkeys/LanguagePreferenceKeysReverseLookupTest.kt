package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguagePreferenceKeysReverseLookupTest {

    @Test
    fun customWordsKey_andReverse_roundTrip() {
        val key = LanguagePreferenceKeys.customWordsKey("en")
        assertThat(key).isEqualTo("custom_words_en")
        assertThat(LanguagePreferenceKeys.languageFromCustomWordsKey(key)).isEqualTo("en")
    }

    @Test
    fun reverseLookup_unrelatedKey_returnsNull() {
        assertThat(LanguagePreferenceKeys.languageFromCustomWordsKey("custom_words")).isNull()  // prefix-only
        assertThat(LanguagePreferenceKeys.languageFromCustomWordsKey("layouts")).isNull()
        assertThat(LanguagePreferenceKeys.languageFromCustomWordsKey("custom_word_en")).isNull()
    }

    @Test
    fun disabledWordsKey_andReverse_roundTrip() {
        val key = LanguagePreferenceKeys.disabledWordsKey("fr")
        assertThat(LanguagePreferenceKeys.languageFromDisabledWordsKey(key)).isEqualTo("fr")
    }

    @Test
    fun reverseLookup_emptyLanguageSuffix_returnsNull() {
        assertThat(LanguagePreferenceKeys.languageFromCustomWordsKey("custom_words_")).isNull()
        assertThat(LanguagePreferenceKeys.languageFromDisabledWordsKey("disabled_words_")).isNull()
    }

    @Test
    fun reverseLookup_caseFolding_consistentWithCustomWordsKey() {
        // customWordsKey lowercases its input, so "EN" produces "custom_words_en".
        // The reverse must round-trip through that lowercased form, NOT the raw input.
        val key = LanguagePreferenceKeys.customWordsKey("EN")
        assertThat(key).isEqualTo("custom_words_en")
        assertThat(LanguagePreferenceKeys.languageFromCustomWordsKey(key)).isEqualTo("en")
    }
}
