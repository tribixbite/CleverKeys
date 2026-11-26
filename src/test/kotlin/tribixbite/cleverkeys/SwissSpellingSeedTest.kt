package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Locale
import org.junit.Test

/**
 * D2: the first value of `pref_german_swiss_spelling` ([LanguagePrefs.seedGermanSwissSpelling]).
 *
 * The key is absent until German is served for the first time; it is then written once, from the
 * system languages (on for de-CH / de-LI), and never replaced: not by a later change of the system
 * language, and not over the user's switch, a restored backup or a second call.
 *
 * Mock tier: the reader goes through `Config.safeGetBoolean`.
 */
class SwissSpellingSeedTest {

    private val swissPhone = listOf(Locale.forLanguageTag("de-CH"), Locale.forLanguageTag("en-US"))
    private val germanPhone = listOf(Locale.forLanguageTag("de-DE"))
    private val key = LanguagePrefs.GERMAN_SWISS_SPELLING

    @Test
    fun `the key is the documented one`() {
        assertThat(key).isEqualTo("pref_german_swiss_spelling")
        assertWithMessage("a change of it changes what the lexicons hold").that(LanguagePrefs.SERVING_KEYS).contains(key)
    }

    @Test
    fun `nothing is written while German is not served`() {
        val prefs = TypedFakePreferences()
        assertThat(LanguagePrefs.seedGermanSwissSpelling(prefs, listOf("en", null), swissPhone)).isNull()
        assertThat(LanguagePrefs.seedGermanSwissSpelling(prefs, listOf("fr", "nl"), swissPhone)).isNull()
        assertThat(prefs.contains(key)).isFalse()
        assertWithMessage("absent reads as the static default").that(LanguagePrefs.germanSwissSpelling(prefs)).isFalse()
    }

    @Test
    fun `a Swiss phone starts with ss the first time German is served, as primary or secondary`() {
        for (served in listOf(listOf("de", null), listOf("en", "de"), listOf("en", null, "de"))) {
            val prefs = TypedFakePreferences()
            assertWithMessage("$served").that(LanguagePrefs.seedGermanSwissSpelling(prefs, served, swissPhone)).isTrue()
            assertWithMessage("written explicitly").that(prefs.values[key]).isEqualTo(true)
            assertThat(LanguagePrefs.germanSwissSpelling(prefs)).isTrue()
            assertWithMessage("one synchronous write").that(prefs.commits).isEqualTo(1)
        }
    }

    @Test
    fun `a German phone gets an explicit off`() {
        val prefs = TypedFakePreferences()
        assertThat(LanguagePrefs.seedGermanSwissSpelling(prefs, listOf("de"), germanPhone)).isFalse()
        assertWithMessage("explicit, so a later move to Switzerland does not flip it").that(prefs.values[key]).isEqualTo(false)
        assertThat(LanguagePrefs.seedGermanSwissSpelling(prefs, listOf("de"), swissPhone)).isNull()
        assertThat(LanguagePrefs.germanSwissSpelling(prefs)).isFalse()
    }

    @Test
    fun `a stored choice is never replaced`() {
        // The user (or a restored backup) turned it off on a Swiss phone.
        val prefs = TypedFakePreferences(mapOf(key to false))
        assertThat(LanguagePrefs.seedGermanSwissSpelling(prefs, listOf("de"), swissPhone)).isNull()
        assertThat(prefs.values[key]).isEqualTo(false)
        assertThat(prefs.commits).isEqualTo(0)
    }

    @Test
    fun `a mistyped stored value is read like every other preference`() {
        // A backup import writes the import table's type; a hand-edited file may hold a string.
        assertThat(LanguagePrefs.germanSwissSpelling(TypedFakePreferences(mapOf(key to "true")))).isTrue()
        assertThat(LanguagePrefs.germanSwissSpelling(TypedFakePreferences(mapOf(key to 0)))).isFalse()
    }
}
