package tribixbite.cleverkeys

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Review 2026-11-14 (low): the typo purge deletes low-frequency n-grams containing a word no
 * lexicon knows, and defers while the user's secondary lexicon is still to be published
 * ([LearnedTypoPurgeScheduler]). It asked for the ACTIVE secondary, which is null on a layout
 * bound to a language (GH #186/#61) — and switching to such a layout unloads the secondary
 * index. So an English + Spanish user who switched to an English-bound layout had the English
 * store judged without the Spanish lexicon: the Spanish n-grams learned while bilingual and seen
 * at most twice were deleted. The purge must report the CONFIGURED secondary, which keeps it
 * deferred until the Spanish lexicon is back.
 */
class TypoPurgeBoundLayoutTest {

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

    private fun <T : Any> T.set(name: String, value: Any?): T = apply {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    /** English + Spanish configured; [boundTo] non-null = the current layout is bound to it. */
    private fun config(boundTo: String?): Config {
        val config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)
        config.set("user_primary_language", "en")
            .set("user_secondary_language", "es")
            .set("enable_multilang", true)
        // What recomputeActiveLanguages leaves for this layout.
        config.primary_language = boundTo ?: "en"
        config.active_secondary_language = if (boundTo == null) "es" else null
        config.layout_bound_language = boundTo
        return config
    }

    private fun lexiconState(config: Config, secondaryLoaded: Boolean): LearnedTypoPurgeScheduler.LexiconState {
        val predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
        predictor.set("dictionary", AtomicReference(mutableMapOf("hello" to 200)))
            .set("customAndUserWords", emptySet<String>())
            .set("config", config)
        if (secondaryLoaded) {
            predictor.set("secondaryIndex", NormalizedPrefixIndex("es").apply { buildFromList(listOf("días" to 180)) })
                .set("secondaryLanguageCode", "es")
        }
        val method = WordPredictor::class.java.getDeclaredMethod("lexiconState", String::class.java)
        method.isAccessible = true
        return method.invoke(predictor, "en") as LearnedTypoPurgeScheduler.LexiconState
    }

    @Test
    fun onABoundLayoutTheSpanishLexiconIsStillPending() {
        // The bound layout unloaded the secondary index.
        val state = lexiconState(config(boundTo = "en"), secondaryLoaded = false)
        assertEquals("es", state.configuredSecondary)
        assertTrue("the purge must wait for the Spanish lexicon", state.secondaryPending)
    }

    @Test
    fun onAnUnboundLayoutWithTheSpanishLexiconLoadedThePurgeMayRun() {
        val state = lexiconState(config(boundTo = null), secondaryLoaded = true)
        assertFalse(state.secondaryPending)
    }

    @Test
    fun aMonolingualUserIsNeverPending() {
        val config = config(boundTo = "en").set("enable_multilang", false)
        val state = lexiconState(config, secondaryLoaded = false)
        assertNull(state.configuredSecondary)
        assertFalse(state.secondaryPending)
    }
}
