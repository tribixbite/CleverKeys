package tribixbite.cleverkeys

import android.os.Trace
import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.KeyAdjacency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Review 2026-11-14 (Stores, low) — checked and NOT a defect; this pins why.
 *
 * The review read [WordPredictor]'s `lowercase()` as the default-locale fold (so a Turkish
 * device would key `I` as dotless `ı`) while the swipe-correction, swipe-priority and
 * correction-policy stores fold with `lowercase(Locale.ROOT)`. Kotlin's `String.lowercase()` IS
 * locale-invariant — it is defined as `toLowerCase(Locale.ROOT)`; only the deprecated
 * `toLowerCase()` reads the default locale — so both sides already use one fold, the same one
 * the offline dictionary build uses (Python's `str.lower()`, scripts/build_dictionary.py).
 * This test passed before any change in that review round. It fails if a default-locale fold
 * (`toLowerCase()`, `lowercase(Locale.getDefault())`) ever replaces it.
 *
 * Mock tier (the predictor touches `Log`/`Trace`), harnessed like [WordPredictorHalfSpaceTest].
 */
class WordPredictorCaseFoldTest {

    private lateinit var predictor: WordPredictor
    private lateinit var savedLocale: Locale

    private fun setField(name: String, value: Any?) {
        val field = WordPredictor::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(predictor, value)
    }

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
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
        val config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)
        predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        val dict = HashMap(mapOf("i" to 900_000, "istanbul" to 400_000, "iyi" to 500_000))
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
        setField("currentLanguage", "en")
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        unmockkStatic(Log::class)
        unmockkStatic(Trace::class)
    }

    @Test
    fun `a Turkish device locale does not change the dictionary key`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertWithMessage("English I on a Turkish-locale phone").that(predictor.isInDictionary("I")).isTrue()
        assertWithMessage("ISTANBUL in capitals").that(predictor.isInDictionary("ISTANBUL")).isTrue()
        assertWithMessage("IYI in capitals").that(predictor.isInDictionary("IYI")).isTrue()
    }
}
