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
 * Persian half-space and letter-variant folding in the REAL [WordPredictor] (decisions (c) and
 * (d), 2026-10-23; research `docs/research/2026-10-21-persian-zwnj.md` §4 and §7).
 *
 * Mock tier (the predictor touches `Log`/`Trace`); harnessed like [WordPredictorFuzzyTypingTest]:
 * Objenesis instance, reflection-injected dictionary and prefix index on the binary-dictionary
 * frequency scale, shipped autocorrect knobs with the prefix rule off (the widest sweep).
 *
 * Pins:
 *  - (d) autocorrect never adds, removes or moves a half-space: `میشود` stays `میشود` with only
 *    `می‌شود` in the dictionary, `می‌شود` stays with only `میشود`, `نامه‌ای` is not "corrected"
 *    to `نام‌های`; a correction that keeps the half-space still works;
 *  - (d) the other spelling is OFFERED in the bar (typed joined → half-space form; typed
 *    half-space → joined form when it is a word; both kept when both are words);
 *  - (c) `fa` lookups fold ي/ك/ى → ی/ک and ۀ/ة/ە → ه: the folded word is a dictionary word, it
 *    is never rewritten, and its completions are found; آ and hamza carriers are never folded;
 *    Kurdish `ckb` folds only ي/ك (ە stays a letter); Arabic and English fold nothing.
 */
class WordPredictorHalfSpaceTest {

    companion object {
        private const val Z = "‌"
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
        language("fa")
        dictionary(emptyMap())
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

    private fun language(code: String) = setField("currentLanguage", code)

    private fun bar(typed: String): List<String> = predictor.predictWordsWithContext(typed, emptyList()).words

    private val miShavad = "می${Z}شود"
    private val miShavadJoined = "میشود"
    private val common = mapOf("کتاب" to 800_000, "خوب" to 700_000, "میز" to 600_000, "شود" to 650_000)

    // ── (d) no autocorrect across the half-space ─────────────────────────────────────────

    @Test
    fun `joined typing is not autocorrected to the half-space form`() {
        dictionary(common + (miShavad to 900_000))
        assertWithMessage("space keeps the joined spelling").that(predictor.autoCorrect(miShavadJoined))
            .isEqualTo(miShavadJoined)
    }

    @Test
    fun `half-space typing is not autocorrected to the joined form`() {
        dictionary(common + (miShavadJoined to 900_000))
        assertWithMessage("space keeps the half-space spelling").that(predictor.autoCorrect(miShavad))
            .isEqualTo(miShavad)
    }

    @Test
    fun `a moved half-space is not a correction`() {
        dictionary(common + ("نام${Z}های" to 800_000))
        assertWithMessage("نامه‌ای (a letter) is not turned into نام‌های (names)")
            .that(predictor.autoCorrect("نامه${Z}ای")).isEqualTo("نامه${Z}ای")
        dictionary(common + ("نامه${Z}ای" to 800_000) + ("نام${Z}های" to 790_000))
        assertWithMessage("the joined string is not split for the user")
            .that(predictor.autoCorrect("نامهای")).isEqualTo("نامهای")
    }

    @Test
    fun `a correction that keeps the half-space still applies`() {
        dictionary(common + (miShavad to 900_000))
        assertWithMessage("doubled letter collapses inside a half-space word")
            .that(predictor.autoCorrect("می${Z}شوود")).isEqualTo(miShavad)
    }

    @Test
    fun `a dangling half-space word is left alone`() {
        dictionary(common + (miShavad to 900_000))
        assertWithMessage("می‌ + space is not rewritten").that(predictor.autoCorrect("می$Z")).isEqualTo("می$Z")
    }

    // ── (d) the other spelling is offered ────────────────────────────────────────────────

    @Test
    fun `joined typing offers the half-space form in the bar`() {
        dictionary(common + (miShavad to 900_000))
        assertWithMessage("bar for میشود").that(bar(miShavadJoined)).contains(miShavad)
    }

    @Test
    fun `half-space typing offers the joined form when it is a word`() {
        dictionary(common + (miShavadJoined to 900_000))
        assertWithMessage("bar for می‌شود").that(bar(miShavad)).contains(miShavadJoined)
    }

    @Test
    fun `when both spellings are words both are kept and offered`() {
        dictionary(common + (miShavad to 900_000) + (miShavadJoined to 850_000))
        assertWithMessage("joined typed stays").that(predictor.autoCorrect(miShavadJoined)).isEqualTo(miShavadJoined)
        assertWithMessage("half-space typed stays").that(predictor.autoCorrect(miShavad)).isEqualTo(miShavad)
        assertWithMessage("bar for the joined form").that(bar(miShavadJoined)).containsAtLeast(miShavadJoined, miShavad)
        assertWithMessage("bar for the half-space form").that(bar(miShavad)).containsAtLeast(miShavad, miShavadJoined)
    }

    @Test
    fun `both readings of a position-ambiguous joined word are offered`() {
        dictionary(common + ("نامه${Z}ای" to 800_000) + ("نام${Z}های" to 790_000))
        assertWithMessage("نامهای offers نامه‌ای and نام‌های")
            .that(bar("نامهای")).containsAtLeast("نامه${Z}ای", "نام${Z}های")
    }

    // ── (c) letter-variant folding, Persian only ─────────────────────────────────────────

    @Test
    fun `Arabic kaf and yeh find the Persian dictionary word and are not rewritten`() {
        dictionary(common + ("کاری" to 500_000))
        assertWithMessage("كتاب (Arabic kaf) is a dictionary word in fa").that(predictor.isInDictionary("كتاب")).isTrue()
        assertWithMessage("كاري (Arabic kaf + yeh)").that(predictor.isInDictionary("كاري")).isTrue()
        assertWithMessage("the user's spelling survives space").that(predictor.autoCorrect("كتاب")).isEqualTo("كتاب")
        assertWithMessage("completions found through the fold").that(bar("كتا")).contains("کتاب")
    }

    @Test
    fun `final heh variants fold to heh in fa`() {
        dictionary(common + ("خانه" to 700_000))
        for (variant in listOf("خانۀ", "خانة", "خانە")) {
            assertWithMessage("$variant is خانه in fa").that(predictor.isInDictionary(variant)).isTrue()
        }
    }

    @Test
    fun `alef madda and hamza carriers are never folded`() {
        dictionary(common + ("آب" to 700_000) + ("مسئله" to 600_000))
        assertWithMessage("اب is not آب").that(predictor.isInDictionary("اب")).isFalse()
        assertWithMessage("مسیله is not مسئله").that(predictor.isInDictionary("مسیله")).isFalse()
    }

    @Test
    fun `Kurdish keeps ae and only folds Arabic kaf and yeh`() {
        language("ckb")
        dictionary(mapOf("خانه" to 700_000, "کوردی" to 600_000))
        assertWithMessage("ە is a Sorani letter: خانە is not خانه").that(predictor.isInDictionary("خانە")).isFalse()
        assertWithMessage("ك/ي fold in ckb").that(predictor.isInDictionary("كوردي")).isTrue()
    }

    @Test
    fun `Arabic and English fold nothing`() {
        language("ar")
        dictionary(mapOf("کتاب" to 700_000))
        assertWithMessage("ar: كتاب is a different word").that(predictor.isInDictionary("كتاب")).isFalse()
        language("en")
        assertWithMessage("en: no fold either").that(predictor.isInDictionary("كتاب")).isFalse()
    }
}
