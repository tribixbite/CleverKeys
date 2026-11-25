package tribixbite.cleverkeys

import android.os.Trace
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Pixel 2026-11-24 (release r7, English primary, Dutch secondary from the imported nl pack): a word
 * disabled in Dictionary Manager's "Disabled [NL]" tab was still offered. The predictor read only
 * the primary language's disabled words ([WordPredictor.isWordDisabled]), and both places that
 * add secondary candidates (the exact prefix lookup and the fuzzy fill) checked those alone.
 *
 * The secondary language's disabled words now filter its candidates. A word the user added back as
 * a custom word of that language wins, as for the primary language.
 *
 * Harness as [WordPredictorSecondaryLexiconTest] / `SpaceCommitPreviewTest`: an Objenesis
 * predictor with a tiny English map and a Dutch secondary index, through the real
 * [WordPredictor.predictWordsWithContext].
 */
class WordPredictorSecondaryUserWordsTest {

    private lateinit var predictor: WordPredictor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just runs
        every { Trace.endSection() } just runs
        KeyAdjacency.resetLayout()

        val config = org.objenesis.ObjenesisStd().newInstance(Config::class.java).apply {
            autocorrect_enabled = true
            autocorrect_min_word_length = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
            autocorrect_char_match_threshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
            autocorrect_max_length_diff = Defaults.AUTOCORRECT_MAX_LENGTH_DIFF
            autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
            autocorrect_prefix_length = Defaults.AUTOCORRECT_PREFIX_LENGTH
            prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
            prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
            context_source = Defaults.CONTEXT_SOURCE
            personalization_weight = Defaults.PERSONALIZATION_WEIGHT
            secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
            autocorrect_tuning = null
        }
        predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        val english = mutableMapOf("fiets" to 120, "the" to 255)
        predictor.setField("dictionary", AtomicReference<MutableMap<String, Int>>(english))
        predictor.setField("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(prefixIndexOf(english.keys)))
        predictor.setField("contractionAliases", emptyMap<String, String>())
        predictor.setField("customAndUserWords", emptySet<String>())
        predictor.setField("disabledWords", mutableSetOf<String>())
        predictor.setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        predictor.setField("config", config)
        predictor.setField("cachedMaxFreqForSize", -1)
        predictor.setField("currentLanguage", "en")
        val dutch = NormalizedPrefixIndex("nl").apply {
            buildFromList(listOf("fietsenstalling" to 60, "fietsenstallingen" to 90))
        }
        predictor.setField("secondaryIndex", dutch)
        predictor.setField("secondaryLanguageCode", "nl")
        predictor.setField("secondaryDisabledWords", emptySet<String>())
    }

    @After
    fun tearDown() = unmockkAll()

    private fun WordPredictor.setField(name: String, value: Any?) {
        WordPredictor::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    /** Every prefix of every word, as the primary prefix index holds them. */
    private fun prefixIndexOf(words: Collection<String>): MutableMap<String, MutableSet<String>> {
        val index = HashMap<String, MutableSet<String>>()
        for (w in words) for (i in 1..w.length) index.getOrPut(w.substring(0, i)) { HashSet() }.add(w)
        return index
    }

    private fun bar(typed: String): List<String> =
        predictor.predictWordsWithContext(typed, emptyList()).words.map { it.lowercase() }

    @Test
    fun aSecondaryWordIsOffered() {
        assertTrue(bar("fietsenstal").contains("fietsenstalling"))
    }

    @Test
    fun aWordDisabledInTheSecondaryLanguage_isNotOffered() {
        predictor.setField("secondaryDisabledWords", setOf("fietsenstalling"))

        val words = bar("fietsenstal")
        assertFalse("disabled in Dutch, so never offered while Dutch is secondary", words.contains("fietsenstalling"))
        assertTrue("its other words still are", words.contains("fietsenstallingen"))
    }

    /** A primary-language disabled word keeps filtering the secondary candidates, as before. */
    @Test
    fun aWordDisabledInThePrimaryLanguage_isStillNotOffered() {
        predictor.setField("disabledWords", mutableSetOf("fietsenstalling"))

        assertFalse(bar("fietsenstal").contains("fietsenstalling"))
    }

    /** The disabled set the predictor keeps: lowercase, less the language's own custom words. */
    @Test
    fun theSecondaryDisabledSet_isLowercaseAndLetsTheLanguagesCustomWordsWin() {
        val set = WordPredictor.secondaryDisabledSet(
            disabled = setOf("Fietsenstalling", "gezellig"),
            customWordsJson = """{"Gezellig": 255}""",
        )
        assertEquals(setOf("fietsenstalling"), set)
        assertEquals(emptySet<String>(), WordPredictor.secondaryDisabledSet(emptySet(), "{}"))
        assertEquals(setOf("x"), WordPredictor.secondaryDisabledSet(setOf("x"), "not json"))
    }
}
