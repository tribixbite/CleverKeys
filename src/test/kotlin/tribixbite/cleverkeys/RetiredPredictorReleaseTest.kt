package tribixbite.cleverkeys

import android.os.Handler
import android.os.Trace
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.autocorrect.AutocorrectMemo
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * A retired service must not keep its dictionaries (docs/audit/2026-11-26-memory-analysis.md,
 * defect 1; the terminal-shutdown design of docs/audit/2026-09-10-memory-oom-root-cause.md).
 *
 * The framework keeps a retired `CleverKeysService` reachable through the IME back-callback's
 * JNI global. On the Saga that reached `SuggestionHandler.autocorrectMemo` → the retired
 * [WordPredictor], whose lookup caches still pointed at the full old maps after `shutdown()`
 * replaced them: `fuzzyAlphabetIndexRef` → the prefix index (9.71 MiB) and
 * `halfSpaceVariantsDict` → the dictionary (4.62 MiB). Pins:
 *  - [WordPredictor.shutdown] leaves the old dictionary and prefix index collectable;
 *  - a lookup that arrives after shutdown builds no cache;
 *  - a dictionary swap (language reload) leaves the previous maps collectable before any lookup;
 *  - [SuggestionHandler.shutdown] empties the memo, and a late bar fill memoizes nothing.
 *
 * Mock tier (`Log`/`Trace`). The predictor is harnessed like [WordPredictorFuzzyTypingTest]
 * (Objenesis instance, reflection-injected maps). Maps are created inside helpers that return
 * only [WeakReference]s, so no test-frame local roots them (CLAUDE.md "Heap-probe Java-local roots").
 */
class RetiredPredictorReleaseTest {

    private val objenesis = ObjenesisStd()
    private lateinit var config: Config
    private lateinit var predictor: WordPredictor

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just runs
        every { Trace.endSection() } just runs
        KeyAdjacency.resetLayout()

        config = objenesis.newInstance(Config::class.java)
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

        predictor = objenesis.newInstance(WordPredictor::class.java)
        predictor.setField("currentLanguage", "en")
        predictor.setField("contractionAliases", emptyMap<String, String>())
        predictor.setField("customAndUserWords", emptySet<String>())
        predictor.setField("disabledWords", mutableSetOf<String>())
        predictor.setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
        predictor.setField("shadowedBaseFrequencies", ConcurrentHashMap<String, Int>())
        predictor.setField("config", config)
        predictor.setField("cachedMaxFreqForSize", -1)
        predictor.setField("secondaryIndex", null)
        predictor.setField("secondaryLanguageCode", "none")
        predictor.setField("secondaryDisabledWords", emptySet<String>())
        // The real loader posts to the main looper; shutdown only needs its close().
        predictor.setField("asyncLoader", mockk<AsyncDictionaryLoader>(relaxed = true))
        predictor.setField("dictionary", AtomicReference<MutableMap<String, Int>>(HashMap()))
        predictor.setField("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(HashMap()))
    }

    @After
    fun tearDown() = unmockkAll()

    // ── WordPredictor ───────────────────────────────────────────────────────────────────

    @Test
    fun `shutdown leaves the old dictionary and prefix index collectable`() {
        val (dict, index) = installAndLookUp()
        assertWithMessage("the lookup built the half-space cache").that(fieldIsSet("halfSpaceVariants")).isTrue()
        assertWithMessage("the lookup built the fuzzy alphabet cache").that(fieldIsSet("fuzzyAlphabetCache")).isTrue()

        predictor.shutdown()

        assertWithMessage("the retired dictionary map is collectable").that(collected(dict)).isTrue()
        assertWithMessage("the retired prefix index is collectable").that(collected(index)).isTrue()
    }

    @Test
    fun `shutdown drops every lookup cache`() {
        installAndLookUp()
        predictor.shutdown()
        for (name in LOOKUP_CACHES) {
            assertWithMessage("$name after shutdown").that(fieldIsSet(name)).isFalse()
        }
    }

    @Test
    fun `a lookup after shutdown builds no cache`() {
        installAndLookUp()
        predictor.shutdown()

        predictor.predictWordsWithContext("plq", emptyList())
        predictor.autoCorrect("plq")

        for (name in LOOKUP_CACHES) {
            assertWithMessage("$name rebuilt by a late lookup").that(fieldIsSet(name)).isFalse()
        }
    }

    @Test
    fun `a dictionary swap leaves the previous maps collectable before any lookup`() {
        val (dict, index) = installAndLookUp()
        // What a language reload does: publish new maps into the same references.
        predictor.atomicField<MutableMap<String, Int>>("dictionary").set(HashMap(mapOf("play" to 900_000)))
        predictor.atomicField<MutableMap<String, MutableSet<String>>>("prefixIndex").set(HashMap())

        assertWithMessage("the replaced dictionary map is collectable").that(collected(dict)).isTrue()
        assertWithMessage("the replaced prefix index is collectable").that(collected(index)).isTrue()
    }

    @Test
    fun `the caches still serve the live dictionary`() {
        installAndLookUp()
        val words = predictor.predictWordsWithContext("plq", emptyList()).words
        assertWithMessage("a one-key typo of play is still offered").that(words).contains("play")
    }

    // ── SuggestionHandler ───────────────────────────────────────────────────────────────

    @Test
    fun `handler shutdown empties the autocorrect memo`() {
        val handler = handlerWithMemo()
        handler.memoizedAutoCorrect("teh")
        assertWithMessage("the bar memoized a correction").that(handler.memo().isEmpty).isFalse()

        handler.shutdown()

        assertWithMessage("memo after shutdown").that(handler.memo().isEmpty).isTrue()
    }

    @Test
    fun `a late bar fill after handler shutdown memoizes nothing`() {
        val handler = handlerWithMemo()
        handler.shutdown()

        val result = handler.memoizedAutoCorrect("teh")

        assertWithMessage("the late caller still gets its answer").that(result).isEqualTo("the")
        assertWithMessage("memo after a late fill").that(handler.memo().isEmpty).isTrue()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    /**
     * Installs a small English dictionary and runs the lookups that build the caches
     * (half-space variants: every bar lookup; fuzzy alphabet: a weak 3-letter prefix).
     * Returns only weak references to the installed maps.
     */
    private fun installAndLookUp(): Pair<WeakReference<Any>, WeakReference<Any>> {
        val dict = HashMap(mapOf("play" to 900_000, "plan" to 800_000, "place" to 700_000, "the" to 950_000))
        val index = HashMap<String, MutableSet<String>>()
        for (w in dict.keys) for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
        predictor.atomicField<MutableMap<String, Int>>("dictionary").set(dict)
        predictor.atomicField<MutableMap<String, MutableSet<String>>>("prefixIndex").set(index)
        predictor.predictWordsWithContext("plq", emptyList())
        return WeakReference<Any>(dict) to WeakReference<Any>(index)
    }

    private fun handlerWithMemo(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("config", config)
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        handler.setField("mainHandler", mockk<Handler>(relaxed = true))
        handler.setField("autocorrectMemo", AutocorrectMemo())
        return handler
    }

    private fun SuggestionHandler.memoizedAutoCorrect(word: String): String {
        val serving = mockk<Predictor>(relaxed = true)
        every { serving.autoCorrect(any()) } returns "the"
        val method = SuggestionHandler::class.java.getDeclaredMethod(
            "memoizedAutoCorrect", Predictor::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(this, serving, word) as String
    }

    private fun SuggestionHandler.memo(): AutocorrectMemo {
        val field = SuggestionHandler::class.java.getDeclaredField("autocorrectMemo")
        field.isAccessible = true
        return field.get(this) as AutocorrectMemo
    }

    private fun fieldIsSet(name: String): Boolean {
        val field = WordPredictor::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(predictor) != null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> WordPredictor.atomicField(name: String): AtomicReference<T> {
        val field = WordPredictor::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as AtomicReference<T>
    }

    private fun Any.setField(name: String, value: Any?) {
        val field = this::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(20) {
            System.gc()
            if (ref.get() == null) return true
            @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(1 shl 20)
            Thread.sleep(10)
        }
        return ref.get() == null
    }

    private companion object {
        /** Every lazily built lookup cache of [WordPredictor] that refers to dictionary data. */
        val LOOKUP_CACHES = listOf(
            "halfSpaceVariants", "halfSpaceVariantsDict", "fuzzyAlphabetCache", "fuzzyAlphabetIndexRef",
        )
    }
}
