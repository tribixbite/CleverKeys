package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.langpack.LanguagePackManager
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * Dictionary Manager's German list through the real [MainDictionarySource] (Fable audit C,
 * 2026-11-26), loaded from a JSON word list as the bundled fallback path does:
 * - C1: [MainDictionarySource.onRefresh] (every toggle in any tab) re-synced rows against the RAW
 *   stored set, so a Swiss row whose shipped `straße` was disabled came back enabled while the
 *   keyboard still disables `strasse`; merged twins likewise. Load, refresh and toggle now share
 *   one check;
 * - C3: disabling a Swiss row stored its `ss` spelling, which the shipped list (and the keyboard
 *   with the switch off) does not hold; it now stores the shipped spellings it was merged from;
 * - C4: [MainDictionarySource.loadedLanguages] counts a cache entry only in the spelling the
 *   language now loads in;
 * - C5: a search before the list was loaded returned nothing and never loaded it.
 */
class MainDictionarySourceSpellingTest {

    private val prefs = MockSharedPreferences()
    private val context = mockk<Context>(relaxed = true)

    /** The German list, best first, as the bundled JSON fallback ships it (lowercase). */
    private val germanJson = """{"haus": 250, "straße": 240, "maße": 200, "masse": 190, "gross": 150, "groß": 160}"""

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        // A @JvmStatic member of an object: the static entry point is what callers reach.
        mockkStatic(DirectBootAwarePreferences::class)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        mockkObject(LanguagePackManager.Companion)
        every { LanguagePackManager.getInstance(any()) } throws IllegalStateException("no packs in this test")
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers {
            val name = firstArg<String>()
            if (name == "dictionaries/de_enhanced.json") ByteArrayInputStream(germanJson.toByteArray())
            else throw FileNotFoundException(name)
        }
        every { context.assets } returns assets
        MainDictionarySource.invalidateCache()
    }

    @After
    fun teardown() {
        MainDictionarySource.invalidateCache()
        unmockkAll()
    }

    private fun swiss(on: Boolean) {
        prefs.edit().putBoolean(LanguagePrefs.GERMAN_SWISS_SPELLING, on).commit()
    }

    private fun disabled() = DisabledDictionarySource(prefs, "de")

    private fun source() = MainDictionarySource(context, disabled(), "de")

    private suspend fun MainDictionarySource.row(word: String) = getAllWords().single { it.word == word }

    @Test
    fun aSwissRowDisabledThroughItsShippedSpellingStaysDisabledAfterARefresh() = runBlocking<Unit> {
        swiss(true)
        disabled().setWordEnabled("straße", false)
        val source = source()
        assertWithMessage("loaded disabled").that(source.row("strasse").enabled).isFalse()

        // Any toggle in any tab: the fragment re-syncs every cached row.
        source.toggleWord("haus", false)
        source.onRefresh()

        assertWithMessage("the keyboard still disables strasse (SwissSpelling.foldDisabled)")
            .that(source.row("strasse").enabled).isFalse()
    }

    @Test
    fun aMergedRowDisabledThroughOneTwinStaysDisabledAfterARefresh() = runBlocking<Unit> {
        swiss(true)
        disabled().setWordEnabled("maße", false)
        val source = source()
        assertThat(source.row("masse").enabled).isFalse()

        source.onRefresh()

        assertThat(source.row("masse").enabled).isFalse()
    }

    @Test
    fun disablingASwissRowStoresTheShippedSpellingsItWasMergedFrom() = runBlocking<Unit> {
        swiss(true)
        val source = source()
        source.getAllWords()

        source.toggleWord("strasse", false)
        source.toggleWord("masse", false)

        assertWithMessage("what the shipped list and the keyboard with Swiss spelling off hold")
            .that(disabled().getDisabledWords()).containsExactly("straße", "maße", "masse")
    }

    @Test
    fun theSwissRowDisabledThatWayIsDisabledWithTheSwitchOffToo() = runBlocking<Unit> {
        swiss(true)
        source().apply { getAllWords(); toggleWord("strasse", false) }

        swiss(false)
        MainDictionarySource.invalidateCache()
        assertThat(source().row("straße").enabled).isFalse()
    }

    @Test
    fun enablingASwissRowEnablesEveryShippedSpelling() = runBlocking<Unit> {
        swiss(true)
        disabled().setWordEnabled("maße", false)
        val source = source()
        source.getAllWords()

        source.toggleWord("masse", true)
        source.onRefresh()

        assertThat(disabled().getDisabledWords()).isEmpty()
        assertThat(source.row("masse").enabled).isTrue()
    }

    @Test
    fun aSearchBeforeTheListLoadedLoadsIt() = runBlocking<Unit> {
        swiss(true)
        val found = source().searchWords("str")
        assertThat(found.map { it.word }).containsExactly("strasse")
    }

    @Test
    fun aCacheEntryInTheOtherSpellingDoesNotCountAsLoaded() = runBlocking<Unit> {
        swiss(false)
        source().getAllWords() // cached in Germany spelling

        assertThat(MainDictionarySource.loadedLanguages { it == "de" }).doesNotContain("de")
        assertThat(MainDictionarySource.loadedLanguages { false }).contains("de")
    }
}
