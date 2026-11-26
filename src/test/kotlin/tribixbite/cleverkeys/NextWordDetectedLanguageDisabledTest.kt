package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.contextaware.ContextContinuation
import tribixbite.cleverkeys.contextaware.ContextModel

/**
 * 2026-11-26 (Fable review follow-up): since `2b005218b` an Auto-Detect switch moves only the
 * NEXT-WORD language ([WordPredictor] `currentLanguage`); the disabled words stay the loaded
 * primary dictionary's (`dictionaryLanguage`). The next-word filter (`NextWordPredictor.candidatesFor`)
 * asks [WordPredictor.isWordDisabled], which reads the primary's list only, so after a detected
 * switch to German a word disabled in Dictionary Manager's GERMAN tab still came back as a German
 * next-word continuation — the learned tier and the shipped `.cklm` seed alike.
 *
 * Pins: in the detected language both next-word sources drop that language's disabled words (and
 * their apostrophe-free keys, the form every dictionary stores a contraction under); back on the
 * primary they drop nothing of the other language's list (the primary's list stays the
 * `isWordDisabled` filter's job, unchanged); a custom word of the detected language overrides its
 * disable, as in the typing path ([WordPredictor.secondaryDisabledSet]).
 */
class NextWordDetectedLanguageDisabledTest {

    private val objenesis = ObjenesisStd()
    private lateinit var predictor: WordPredictor
    private lateinit var config: Config
    private lateinit var prefs: SharedPreferences

    private val seed = listOf("doch", "schon", "geht's", "colour").map { StaticBigramSeed.Continuation(it, 1f) }
    private val learned = listOf("doch", "schon", "geht's", "colour").map { ContextContinuation(it, 5, 0.5f) }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        config = mockk(relaxed = true)
        config.on_device_learning_enabled = true
        config.context_aware_predictions_enabled = true
        config.primary_language = "en"
        config.active_secondary_language = "de"
        config.auto_detect_language = true

        prefs = mockk(relaxed = true)
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        every { prefs.getString(any(), any()) } returns "{}"
        every { prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("en"), any()) } returns setOf("colour")
        // `gehts` is the dictionary key of the display form `geht's`.
        every { prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("de"), any()) } returns setOf("doch", "gehts")
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs

        val bigrams = mockk<BigramModel>(relaxed = true)
        every { bigrams.getPredictions(any(), any()) } returns seed
        val context = mockk<ContextModel>(relaxed = true)
        every { context.getNextWordCandidates(any(), any()) } returns learned

        predictor = objenesis.newInstance(WordPredictor::class.java)
        predictor.setField("config", config)
        predictor.setField("context", mockk<Context>(relaxed = true))
        predictor.setField("bigramModel", bigrams)
        predictor.setField("contextModel", context)
        predictor.setField("recentWords", mutableListOf<String>())
        predictor.setField("customAndUserWords", mutableSetOf<String>())
        predictor.setField("disabledWords", mutableSetOf("colour"))
        predictor.setField("dictionaryLanguageOrNull", "en")
        predictor.setField("currentLanguage", "en")
        predictor.setField("secondaryLanguageCode", "none")
        predictor.setField("secondaryDisabledWords", emptySet<String>())
    }

    @After
    fun teardown() = unmockkAll()

    private fun seedWords() = predictor.getStaticNextWordSeed(listOf("ja"), 10).map { it.word }
    private fun learnedWords() = predictor.getNextWordCandidates(listOf("ja"), 10).map { it.word }

    /** German detected while English is the loaded dictionary. */
    private fun detectGerman() = predictor.setField("currentLanguage", "de")

    @Test
    fun theDetectedLanguagesDisabledWordsLeaveItsNextWordOutput_secondaryLoaded() {
        // The usual case: German is the loaded secondary, its list published with the index.
        predictor.setField("secondaryLanguageCode", "de")
        predictor.setField("secondaryDisabledWords", setOf("doch", "gehts"))
        detectGerman()

        assertWithMessage("shipped seed in German").that(seedWords()).containsExactly("schon", "colour").inOrder()
        assertWithMessage("learned tier in German").that(learnedWords()).containsExactly("schon", "colour").inOrder()
    }

    @Test
    fun theDetectedLanguagesDisabledWordsLeaveItsNextWordOutput_readFromItsPreferences() {
        // The secondary index is not published yet (an async load): the list is read for German.
        detectGerman()

        assertWithMessage("shipped seed in German").that(seedWords()).containsExactly("schon", "colour").inOrder()
        assertWithMessage("learned tier in German").that(learnedWords()).containsExactly("schon", "colour").inOrder()
    }

    @Test
    fun backOnThePrimaryTheOtherLanguagesListFiltersNothing() {
        predictor.setField("secondaryLanguageCode", "de")
        predictor.setField("secondaryDisabledWords", setOf("doch", "gehts"))

        // English is the next-word language: the primary's list stays the isWordDisabled
        // filter's job (NextWordPredictor), German's does not apply.
        assertWithMessage("seed on the primary").that(seedWords()).containsExactly("doch", "schon", "geht's", "colour").inOrder()
        assertWithMessage("learned on the primary").that(learnedWords()).containsExactly("doch", "schon", "geht's", "colour").inOrder()
        assertWithMessage("the primary's own disabled word is still disabled").that(predictor.isWordDisabled("colour")).isTrue()
    }

    @Test
    fun aCustomWordOfTheDetectedLanguageOverridesItsDisable() {
        every { prefs.getString(LanguagePreferenceKeys.customWordsKey("de"), any()) } returns """{"doch": 200}"""
        detectGerman()

        assertWithMessage("re-added as a custom German word").that(seedWords()).contains("doch")
        assertWithMessage("gehts still disabled").that(seedWords()).doesNotContain("geht's")
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                f.set(this, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("no field $name")
    }
}
