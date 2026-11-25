package tribixbite.cleverkeys

import android.os.Trace
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * German `ß` against the shipped German dictionary, which spells every such word with `ss`
 * (`scripts/build_wordlist.py`: wordfreq casefolds `ß` to `ss`, so `de_enhanced.bin` holds
 * `gross`, `strasse`, `weiss` and no `ß` at all).
 *
 * Saga 2026-11-25 (release r8, Neo 2 bound to German): typing `groß` and space committed `gros`.
 * The typed word was not a dictionary word, so space ran the correction sweep, and `gros` (a word
 * in that list) won. A word typed with `ß` whose `ss` spelling is a dictionary word is the same
 * word in Germany's spelling: it stays as typed and counts as known (no Add-to-dictionary offer).
 *
 * Mock tier (the predictor touches `Log`/`Trace`); harnessed like [WordPredictorHalfSpaceTest].
 */
class WordPredictorSharpSTest {

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var config: Config

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
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
            config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)
            predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            unmockkStatic(Log::class)
            unmockkStatic(Trace::class)
        }

        private fun setField(name: String, value: Any?) {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(predictor, value)
        }
    }

    /** The shipped list's spellings (binary-dictionary scale): `ss` throughout, and `gros`. */
    private val german = mapOf(
        "gross" to 700_000, "gros" to 650_000, "grosse" to 600_000, "strasse" to 690_000,
        "weiss" to 680_000, "gras" to 500_000, "heisst" to 640_000,
    )

    @Before
    fun resetState() {
        config.prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
        config.prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
        config.context_source = Defaults.CONTEXT_SOURCE
        config.personalization_weight = Defaults.PERSONALIZATION_WEIGHT
        config.secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
        config.autocorrect_enabled = true
        config.autocorrect_min_word_length = 2
        config.autocorrect_char_match_threshold = 0.65f
        config.autocorrect_max_length_diff = 2
        config.autocorrect_confidence_min_frequency = FrequencyFloor.SLIDER_MIN
        config.autocorrect_prefix_length = 0
        config.swipe_debug_detailed_logging = false
        setField("currentLanguage", "de")
        dictionary(german)
    }

    /** Install [words] (binary-dictionary scale) as the primary dictionary, with its prefix index. */
    private fun dictionary(words: Map<String, Int>) {
        val dict = HashMap(words)
        val index = HashMap<String, MutableSet<String>>()
        for (w in dict.keys) for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
        setField("dictionary", AtomicReference<MutableMap<String, Int>>(dict))
        setField("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(index))
        setField("contractionAliases", emptyMap<String, String>())
        setField("customAndUserWords", emptySet<String>())
        setField("disabledWords", mutableSetOf<String>())
        setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        setField("config", config)
        setField("cachedMaxFreqForSize", -1)
        setField("secondaryIndex", null)
        setField("secondaryLanguageCode", "none")
    }

    @Test
    fun `a word typed with sharp s is not corrected when its ss spelling is a word`() {
        assertWithMessage("space keeps groß (it committed gros)").that(predictor.autoCorrect("groß")).isEqualTo("groß")
        assertWithMessage("weiß").that(predictor.autoCorrect("weiß")).isEqualTo("weiß")
        assertWithMessage("heißt").that(predictor.autoCorrect("heißt")).isEqualTo("heißt")
    }

    @Test
    fun `the typed capital survives too`() {
        assertWithMessage("Straße at a sentence start").that(predictor.autoCorrect("Straße")).isEqualTo("Straße")
    }

    @Test
    fun `a sharp s spelling of a dictionary word is a known word`() {
        assertWithMessage("no Add-to-dictionary offer for weiß").that(predictor.isInDictionary("weiß")).isTrue()
        assertWithMessage("Straße").that(predictor.isInDictionary("Straße")).isTrue()
    }

    @Test
    fun `a sharp s word whose ss spelling is not a word is still a typo`() {
        // graß → grass, not in the list: the fold makes no word valid that the list lacks.
        assertWithMessage("graß is unknown").that(predictor.isInDictionary("graß")).isFalse()
        assertWithMessage("graß is corrected like any typo").that(predictor.autoCorrect("graß")).isNotEqualTo("graß")
    }

    @Test
    fun `ss spellings are unchanged`() {
        assertWithMessage("gross").that(predictor.autoCorrect("gross")).isEqualTo("gross")
        assertWithMessage("gros is a word of its own").that(predictor.autoCorrect("gros")).isEqualTo("gros")
        assertWithMessage("strasse").that(predictor.isInDictionary("strasse")).isTrue()
    }

    @Test
    fun `a dictionary that spells sharp s keeps its own entries`() {
        // An imported pack (or a rebuilt list) with ß spellings: the plain lookup finds them first.
        dictionary(mapOf("groß" to 700_000, "gros" to 650_000, "straße" to 690_000))
        assertWithMessage("groß").that(predictor.autoCorrect("groß")).isEqualTo("groß")
        assertWithMessage("Straße").that(predictor.isInDictionary("Straße")).isTrue()
    }
}
