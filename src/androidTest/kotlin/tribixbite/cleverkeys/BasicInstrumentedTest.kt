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
        // Debug builds use `applicationIdSuffix '.debug'`; release uses the bare id.
        // startsWith covers both flavors without per-build matchers.
        assertTrue(
            "expected package id to start with tribixbite.cleverkeys, got ${context.packageName}",
            context.packageName.startsWith("tribixbite.cleverkeys")
        )
    }

    @Test
    fun testContextNotNull() {
        assertNotNull(context)
        // The instrumentation target must be the app under test, with real
        // resources and a usable application context.
        assertNotNull("Application context must be available", context.applicationContext)
        assertTrue(
            "App label resource must resolve",
            context.getString(R.string.app_name).isNotBlank()
        )
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
    fun testDictionaryWordCreation() {
        val word = DictionaryWord("test", 500, WordSource.MAIN, true)
        assertEquals("test", word.word)
        assertEquals(500, word.frequency)
        assertEquals(WordSource.MAIN, word.source)
        assertTrue(word.enabled)
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
        // ARC-044: the old form swallowed the NPE, so the test passed whether
        // or not Config was reachable. Initialize it the way every other suite
        // does and require the singleton to be live.
        assertTrue(
            "Config must be initializable in the instrumented context",
            TestConfigHelper.ensureConfigInitialized(context)
        )
        val config = Config.globalConfig()
        assertSame(
            "globalConfig must be a stable singleton",
            config, Config.globalConfig()
        )
    }
}
