package tribixbite.cleverkeys

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Review 2026-11-14 (low): for a bilingual user the next-word READ filter and the learning WRITE
 * gate used different lexicons. The write gate ([LearnableWordPolicy]) admits words of the
 * secondary language; the read filter goes through [WordPredictor.isInDictionary], which checked
 * the primary map only, although the [Predictor] contract says "primary or secondary". English
 * primary, Spanish secondary: `buenos → días` was learned but not shown until `días` had been
 * committed three times, and never in an incognito field or with personalization off.
 */
class WordPredictorSecondaryLexiconTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun WordPredictor.setField(name: String, value: Any?) {
        WordPredictor::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    /** English primary map, Spanish secondary index, as a finished secondary load leaves them. */
    private fun bilingualPredictor(): WordPredictor {
        // Objenesis, as WordPredictorReloadFoldTest: the constructor needs the main Looper.
        val predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        predictor.setField("dictionary", AtomicReference(mutableMapOf("hello" to 200, "good" to 200)))
        predictor.setField("currentLanguage", "en")
        val spanish = NormalizedPrefixIndex("es").apply { buildFromList(listOf("días" to 180, "buenos" to 170)) }
        predictor.setField("secondaryIndex", spanish)
        predictor.setField("secondaryLanguageCode", "es")
        return predictor
    }

    @Test
    fun aSecondaryLanguageWordIsInTheDictionary() {
        val predictor = bilingualPredictor()
        assertTrue(predictor.isInDictionary("días"))
        // The incognito / personalization-off path: only the loaded dictionaries may admit.
        assertTrue(predictor.isInDictionary("días", fieldAllowsPersonalizedLearning = false))
    }

    @Test
    fun aWordInNeitherLexiconIsStillRejected() {
        val predictor = bilingualPredictor()
        assertFalse(predictor.isInDictionary("zqxv", fieldAllowsPersonalizedLearning = false))
        assertTrue("the primary map still answers", predictor.isInDictionary("hello", false))
    }

    /**
     * Follow-up review 2026-11-15: matched through the accent-free key, `dias` counted as known,
     * so typing it no longer offered "Add to dictionary" or the ExactAdd chip. The primary check
     * is exact; the secondary now is too (case still folds).
     */
    @Test
    fun anAccentlessSpellingIsNotTheSecondaryWord() {
        val predictor = bilingualPredictor()
        assertFalse(predictor.isInDictionary("dias", fieldAllowsPersonalizedLearning = false))
        assertTrue(predictor.isInDictionary("Días", fieldAllowsPersonalizedLearning = false))
    }

    @Test
    fun withNoSecondaryLoadedOnlyThePrimaryCounts() {
        val predictor = bilingualPredictor()
        predictor.setField("secondaryIndex", null)
        assertFalse(predictor.isInDictionary("días", fieldAllowsPersonalizedLearning = false))
    }
}
