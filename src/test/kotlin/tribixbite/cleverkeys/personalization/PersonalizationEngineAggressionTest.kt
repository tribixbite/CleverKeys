package tribixbite.cleverkeys.personalization

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-11-14 (pre-existing, low): [PersonalizationEngine] read its stored learning
 * aggression with `LearningAggression.valueOf`, which throws on any unknown string (a value
 * from a newer build restored from backup, a hand-edited prefs file). The throw escaped the
 * engine's constructor, so the predictor had no personalization at all. An unknown or
 * wrongly typed value now falls back to the default, BALANCED.
 */
class PersonalizationEngineAggressionTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkObject(UserVocabulary.Companion)
        every { UserVocabulary.getInstance(any()) } returns mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getBoolean(any(), any()) } returns true
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `an unknown stored aggression falls back to the default`() {
        every { prefs.getString("learning_aggression", any()) } returns "TURBO"
        val engine = PersonalizationEngine(context)
        assertThat(engine.getLearningAggression()).isEqualTo(PersonalizationEngine.LearningAggression.BALANCED)
    }

    @Test
    fun `a wrongly typed stored aggression falls back to the default`() {
        every { prefs.getString("learning_aggression", any()) } throws ClassCastException("Integer cannot be cast to String")
        val engine = PersonalizationEngine(context)
        assertThat(engine.getLearningAggression()).isEqualTo(PersonalizationEngine.LearningAggression.BALANCED)
    }

    @Test
    fun `a known stored aggression is kept`() {
        every { prefs.getString("learning_aggression", any()) } returns "AGGRESSIVE"
        val engine = PersonalizationEngine(context)
        assertThat(engine.getLearningAggression()).isEqualTo(PersonalizationEngine.LearningAggression.AGGRESSIVE)
    }
}
