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
 * German `ß` against both spellings a German list can have. Until 2026-11-25 the shipped one
 * spelled every such word with `ss` (wordfreq casefolds `ß` to `ss`: `gross`, `strasse`, `weiss`
 * and no `ß` at all), as an imported pack still may; since then it spells `ß` where Germany does
 * (`scripts/sharp_s.py`), so `ss` typed without a ß key must become that word.
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
        // Objenesis skips the initializer: no secondary-language disabled words.
        setField("secondaryDisabledWords", emptySet<String>())
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

    // ── The list since 2026-11-25 (scripts/sharp_s.py): Germany's spelling ──────────────────

    /** The rebuilt list's spellings: ß where Germany writes it, reformed `dass`, both `Maße` and `Masse`. */
    private val germanSharpS = mapOf(
        "groß" to 700_000, "gros" to 650_000, "große" to 600_000, "straße" to 690_000,
        "weiß" to 680_000, "gras" to 500_000, "heißt" to 640_000, "dass" to 720_000,
        "maße" to 560_000, "masse" to 540_000, "strand" to 560_000, "stra" to 300_000,
    )

    private fun bar(typed: String): List<String> =
        predictor.predictWordsWithContext(typed, emptyList()).words.map { it.lowercase() }

    @Test
    fun `ss typed for a sharp s word becomes Germany's spelling, as fur becomes fuer`() {
        dictionary(germanSharpS)
        assertWithMessage("strasse").that(predictor.autoCorrect("strasse")).isEqualTo("straße")
        assertWithMessage("weiss").that(predictor.autoCorrect("weiss")).isEqualTo("weiß")
        assertWithMessage("heisst").that(predictor.autoCorrect("heisst")).isEqualTo("heißt")
        assertWithMessage("the capital is kept").that(predictor.autoCorrect("Strasse")).isEqualTo("Straße")
    }

    @Test
    fun `typed gross is gross with a sharp s, not gros with one s dropped`() {
        // The doubled-letter collapse (gamees → games) would read `ss` as a stuck key.
        dictionary(germanSharpS)
        assertWithMessage("gross").that(predictor.autoCorrect("gross")).isEqualTo("groß")
        assertWithMessage("grosse").that(predictor.autoCorrect("grosse")).isEqualTo("große")
    }

    @Test
    fun `reformed ss words and real ss twins stay as typed`() {
        dictionary(germanSharpS)
        assertWithMessage("dass").that(predictor.autoCorrect("dass")).isEqualTo("dass")
        assertWithMessage("Masse is a word beside Maße").that(predictor.autoCorrect("masse")).isEqualTo("masse")
        assertWithMessage("maße").that(predictor.autoCorrect("maße")).isEqualTo("maße")
        assertWithMessage("strasse itself is not a word of this list").that(predictor.isInDictionary("strasse")).isFalse()
    }

    @Test
    fun `the bar offers the sharp s word while its ss is being typed`() {
        dictionary(germanSharpS)
        assertWithMessage("stras").that(bar("stras")).contains("straße")
        assertWithMessage("strass").that(bar("strass")).contains("straße")
        assertWithMessage("strasse").that(bar("strasse")).contains("straße")
        assertWithMessage("weiss").that(bar("weiss")).contains("weiß")
    }

    /**
     * Pre-tag review 2026-11-25 B1: the readings of a run of `s` grow like the Fibonacci numbers
     * (25 `s` gave 121,392 strings, about 35 ran the keyboard out of memory), and the ß step
     * built them on every space with autocorrect on, in every language. No German word needs more
     * than a handful, so a word with more than [WordPredictor.MAX_SHARP_S_READINGS] has none.
     */
    @Test(timeout = 10_000)
    fun `a long run of s builds no readings and corrects quickly`() {
        dictionary(germanSharpS)
        assertWithMessage("40 s").that(WordPredictor.sharpSReadings("s".repeat(40))).isEmpty()
        assertWithMessage("a word ending in 60 s").that(predictor.autoCorrect("gro" + "s".repeat(60)))
            .isNotEmpty()
    }

    @Test
    fun `real words keep every reading`() {
        // schlo + sss + tra + ss + e: the overlapping run reads two ways, the pair one more.
        assertWithMessage("schlossstrasse").that(WordPredictor.sharpSReadings("schlossstrasse"))
            .containsExactly("schloßstraße", "schloßstrasse", "schlosßtraße", "schlosßtrasse", "schlossstraße")
        assertWithMessage("at the cap").that(WordPredictor.sharpSReadings("assessassessasse"))
            .isNotEmpty()
    }

    /**
     * D2 (the secondary-language half): with English beside German, a typed `fuss` is an English
     * word, not German `fuß` typed without a ß key. Autocorrect changed it anyway: its "already a
     * word" check read the primary list only (the general case is in [WordPredictorFuzzyTypingTest]).
     */
    @Test
    fun `an ss word of the secondary language stays as typed`() {
        dictionary(germanSharpS + mapOf("fuß" to 600_000))
        val english = NormalizedPrefixIndex()
        english.addWord("fuss", 120)
        setField("secondaryIndex", english)
        setField("secondaryLanguageCode", "en")
        assertWithMessage("fuss").that(predictor.autoCorrect("fuss")).isEqualTo("fuss")
        assertWithMessage("strasse is no English word").that(predictor.autoCorrect("strasse")).isEqualTo("straße")
    }
}
