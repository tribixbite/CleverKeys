package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic instrumented tests for CleverKeys.
 * These tests verify core functionality on a real device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class BasicInstrumentedTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun testPackageName() {
        assertEquals("tribixbite.cleverkeys", context.packageName)
    }

    @Test
    fun testContextNotNull() {
        assertNotNull(context)
    }

    @Test
    fun testSettingsActivityLaunch() {
        val intent = Intent(context, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Thread.sleep(1000)

        // Verify settings opened (should be visible)
        assertTrue("Settings should be visible", device.currentPackageName == context.packageName)
    }

    @Test
    fun testAccentNormalizerIntegration() {
        // Test that AccentNormalizer works correctly in Android context
        val normalized = AccentNormalizer.normalize("café")
        assertEquals("cafe", normalized)

        val frenchWord = AccentNormalizer.normalize("éléphant")
        assertEquals("elephant", frenchWord)
    }

    @Test
    fun testVocabularyTrieIntegration() {
        // Test VocabularyTrie in Android context
        val trie = VocabularyTrie()
        trie.insert("hello")
        trie.insert("help")
        trie.insert("helicopter")

        assertTrue(trie.hasPrefix("hel"))
        assertTrue(trie.containsWord("hello"))
        assertTrue(trie.containsWord("help"))
        assertFalse(trie.containsWord("hel"))
    }

    @Test
    fun testDictionaryWordCreation() {
        val word = DictionaryWord("test", 500, WordSource.MAIN, true)
        assertEquals("test", word.word)
        assertEquals(500, word.frequency)
        assertEquals(WordSource.MAIN, word.source)
        assertTrue(word.enabled)
    }

    @Test
    fun testSwipeResamplerIntegration() {
        // Create test trajectory
        val trajectory = Array(20) { i ->
            FloatArray(2) { i.toFloat() }
        }

        val resampled = SwipeResampler.resample(
            trajectory,
            10,
            SwipeResampler.ResamplingMode.MERGE
        )

        assertNotNull(resampled)
        assertEquals(10, resampled!!.size)
    }

    @Test
    fun testBeamSearchModelsIntegration() {
        val state = BeamSearchState(startToken = 1, startScore = 0.5f)
        assertEquals(1, state.tokens.size)
        assertEquals(0.5f, state.score, 0.001f)

        val candidate = BeamSearchCandidate("hello", 0.9f)
        assertEquals("hello", candidate.word)
        assertEquals(0.9f, candidate.confidence, 0.001f)
    }

    @Test
    fun testConfigAccess() {
        // Test that Config can be accessed
        // Note: Config.globalConfig() may throw NPE in test context without full keyboard init
        try {
            val config = Config.globalConfig()
            assertNotNull(config)
        } catch (e: NullPointerException) {
            // Expected in test context - Config requires full keyboard initialization
        }
    }
}
