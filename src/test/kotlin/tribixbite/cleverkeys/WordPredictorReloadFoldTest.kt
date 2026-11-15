package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.os.Trace
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Follow-up review 2026-11-15 of M11: fa/ckb dictionary keys are stored through
 * [ArabicScriptFold] on every full load, and lookups fold the typed word. The RELOAD after a
 * Dictionary Manager edit ([WordPredictor.reloadCustomAndUserWords]) wrote the user's words under
 * their raw keys, so a word saved with the Arabic kaf/yeh was not found by the folded lookup.
 */
class WordPredictorReloadFoldTest {

    private val arabicSpelling = "كتابي"   // ARABIC KAF and ARABIC YEH
    private val persianSpelling = "کتابی"  // KEHEH and FARSI YEH — the folded key

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just runs
        every { Trace.endSection() } just runs

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers {
            if (firstArg<String>() == "custom_words_fa") """{"$arabicSpelling": 500}""" else secondArg()
        }
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs
        mockkObject(UserDictionaryWords)
        every { UserDictionaryWords.read(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun WordPredictor.setField(name: String, value: Any?) {
        WordPredictor::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun WordPredictor.dictionaryKeys(): Set<String> =
        (WordPredictor::class.java.getDeclaredField("dictionary").apply { isAccessible = true }
            .get(this) as AtomicReference<MutableMap<String, Int>>).get().keys

    @Test
    fun aReloadedPersianUserWordIsStoredUnderItsFoldedKey() {
        // Objenesis, as WordPredictorHalfSpaceTest does: the constructor builds an
        // AsyncDictionaryLoader on the main Looper (an android.jar stub here). The fields the
        // reload reads are set to what the constructor gives them.
        val predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        predictor.setField("dictionary", AtomicReference(mutableMapOf<String, Int>()))
        predictor.setField("prefixIndex", AtomicReference(mutableMapOf<String, MutableSet<String>>()))
        predictor.setField("userWordOriginalCase", java.util.concurrent.ConcurrentHashMap<String, String>())
        predictor.setField("shadowedBaseFrequencies", java.util.concurrent.ConcurrentHashMap<String, Int>())
        predictor.setField("customAndUserWords", emptySet<String>())
        predictor.setField("context", mockk<Context>(relaxed = true))
        predictor.setField("currentLanguage", "fa")

        predictor.reloadCustomAndUserWords()

        val keys = predictor.dictionaryKeys()
        assertTrue("the folded key is what lookups use: $keys", persianSpelling in keys)
        assertFalse("no raw Arabic-letter key is left behind: $keys", arabicSpelling in keys)
    }
}
