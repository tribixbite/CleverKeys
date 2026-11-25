package tribixbite.cleverkeys

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pixel 2026-11-24 (release r7, English primary, Dutch secondary): Dutch custom words imported
 * from a dictionary backup were not offered until the secondary dictionary was next loaded (a
 * language change, a keyboard restart). The primary language's words reach the predictor through
 * [UserDictionaryObserver], which listens to that one language's key; nothing listened to the
 * secondary language's `custom_words_<lang>` or `disabled_words_<lang>`, so a word added to,
 * imported into or disabled in it changed nothing until then.
 *
 * Now a change to either preference of the secondary language rebuilds its dictionary, as a
 * language change does. Other languages' words, and the primary's, take their existing paths.
 */
class PredictionCoordinatorSecondaryWordsTest {

    private lateinit var predictor: Predictor
    private lateinit var coordinator: PredictionCoordinator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        predictor = mockk(relaxed = true)
        coordinator = org.objenesis.ObjenesisStd().newInstance(PredictionCoordinator::class.java)
        PredictionCoordinator::class.java.getDeclaredField("wordPredictor").apply { isAccessible = true }
            .set(coordinator, predictor)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun aSecondaryLanguagesCustomOrDisabledWords_rebuildItsDictionary() {
        coordinator.reloadWordPredictorSecondaryDictionary("nl")

        coordinator.onUserWordsChanged("custom_words_nl")
        coordinator.onUserWordsChanged("disabled_words_nl")

        verify(exactly = 3) { predictor.loadSecondaryDictionaryAsync("nl", any()) }
    }

    @Test
    fun otherLanguagesAndOtherKeys_rebuildNothing() {
        coordinator.reloadWordPredictorSecondaryDictionary("nl")

        coordinator.onUserWordsChanged("custom_words_en")
        coordinator.onUserWordsChanged("disabled_words_de")
        coordinator.onUserWordsChanged("swipe_priority_nl")
        coordinator.onUserWordsChanged("pref_secondary_prediction_weight")

        verify(exactly = 1) { predictor.loadSecondaryDictionaryAsync("nl", any()) }
    }

    @Test
    fun withNoSecondaryLanguage_nothingIsLoaded() {
        coordinator.reloadWordPredictorSecondaryDictionary("none")

        coordinator.onUserWordsChanged("custom_words_none")
        coordinator.onUserWordsChanged("custom_words_nl")

        verify(exactly = 0) { predictor.loadSecondaryDictionaryAsync(any(), any()) }
    }
}
