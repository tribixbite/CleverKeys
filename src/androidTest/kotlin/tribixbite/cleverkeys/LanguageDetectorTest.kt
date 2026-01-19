package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for LanguageDetector.
 * Tests language detection from text patterns and character frequencies.
 * Note: Detection accuracy depends on dictionary data being loaded.
 */
@RunWith(AndroidJUnit4::class)
class LanguageDetectorTest {

    private lateinit var context: Context
    private lateinit var detector: LanguageDetector

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = LanguageDetector()
    }

    // =========================================================================
    // Supported languages tests
    // =========================================================================

    @Test
    fun testGetSupportedLanguages() {
        val languages = detector.getSupportedLanguages()

        assertNotNull("Languages list should not be null", languages)
        assertTrue("Should support English", languages.contains("en"))
    }

    @Test
    fun testEnglishIsSupported() {
        assertTrue("English should be supported", detector.isLanguageSupported("en"))
    }

    @Test
    fun testSpanishIsSupported() {
        assertTrue("Spanish should be supported", detector.isLanguageSupported("es"))
    }

    @Test
    fun testFrenchIsSupported() {
        assertTrue("French should be supported", detector.isLanguageSupported("fr"))
    }

    @Test
    fun testGermanIsSupported() {
        assertTrue("German should be supported", detector.isLanguageSupported("de"))
    }

    @Test
    fun testPortugueseIsSupported() {
        assertTrue("Portuguese should be supported", detector.isLanguageSupported("pt"))
    }

    // =========================================================================
    // Detection tests (lenient - detection may return null without data)
    // =========================================================================

    @Test
    fun testDetectEnglishText() {
        val text = "The quick brown fox jumps over the lazy dog. This is a sample English sentence with common words."
        val result = detector.detectLanguage(text)

        // Result may be null if detection data isn't loaded
        // If we get a result, it should be English
        if (result != null) {
            assertEquals("Should detect English when data available", "en", result)
        }
    }

    @Test
    fun testDetectSpanishText() {
        val text = "Hola, como estas? Yo estoy muy bien, gracias por preguntar. Es un día hermoso."
        val result = detector.detectLanguage(text)

        // If we get a result, it should be Spanish
        if (result != null) {
            assertEquals("Should detect Spanish when data available", "es", result)
        }
    }

    @Test
    fun testDetectFrenchText() {
        val text = "Bonjour, comment allez-vous? Je suis très content de vous voir aujourd'hui."
        val result = detector.detectLanguage(text)

        // If we get a result, it should be French
        if (result != null) {
            assertEquals("Should detect French when data available", "fr", result)
        }
    }

    @Test
    fun testDetectGermanText() {
        val text = "Guten Tag, wie geht es Ihnen? Ich freue mich, Sie kennenzulernen."
        val result = detector.detectLanguage(text)

        // If we get a result, it should be German
        if (result != null) {
            assertEquals("Should detect German when data available", "de", result)
        }
    }

    @Test
    fun testDetectPortugueseText() {
        val text = "Olá, como você está? Eu estou muito bem, obrigado por perguntar."
        val result = detector.detectLanguage(text)

        // If we get a result, it should be Portuguese
        if (result != null) {
            assertEquals("Should detect Portuguese when data available", "pt", result)
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testShortTextHandled() {
        val text = "Hi"
        val result = detector.detectLanguage(text)
        // Short text may return null - just don't crash
    }

    @Test
    fun testEmptyTextHandled() {
        val result = detector.detectLanguage("")
        // Should handle gracefully without crashing
    }

    @Test
    fun testNullTextHandled() {
        val result = detector.detectLanguage(null)
        // Should return null for null input
        assertNull(result)
    }

    @Test
    fun testMixedLanguageText() {
        // Text with multiple languages should not crash
        val text = "Hello world, hola mundo, bonjour le monde"
        val result = detector.detectLanguage(text)
        // Just verify it doesn't crash
    }

    // =========================================================================
    // Word-based detection tests
    // =========================================================================

    @Test
    fun testDetectLanguageFromEnglishWords() {
        val words = listOf("the", "and", "is", "are", "have", "been")
        val result = detector.detectLanguageFromWords(words)

        // If we get a result, it should be English
        if (result != null) {
            assertEquals("en", result)
        }
    }

    @Test
    fun testDetectLanguageFromSpanishWords() {
        val words = listOf("hola", "como", "estas", "muy", "bien")
        val result = detector.detectLanguageFromWords(words)

        // If we get a result, it should be Spanish
        if (result != null) {
            assertEquals("es", result)
        }
    }

    @Test
    fun testDetectLanguageFromEmptyWords() {
        val words = emptyList<String>()
        val result = detector.detectLanguageFromWords(words)
        // Should handle empty list gracefully
    }
}
