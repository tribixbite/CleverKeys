package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for AccentNormalizer in Android context.
 * Verifies accent handling works correctly with real Android locale/unicode support.
 */
@RunWith(AndroidJUnit4::class)
class AccentNormalizerIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    // =========================================================================
    // French accent tests
    // =========================================================================

    @Test
    fun testFrenchAccents() {
        assertEquals("cafe", AccentNormalizer.normalize("café"))
        assertEquals("ecole", AccentNormalizer.normalize("école"))
        assertEquals("francais", AccentNormalizer.normalize("français"))
        assertEquals("garcon", AccentNormalizer.normalize("garçon"))
    }

    @Test
    fun testFrenchCircumflex() {
        assertEquals("foret", AccentNormalizer.normalize("forêt"))
        assertEquals("hotel", AccentNormalizer.normalize("hôtel"))
        assertEquals("ile", AccentNormalizer.normalize("île"))
    }

    @Test
    fun testFrenchTrema() {
        assertEquals("noel", AccentNormalizer.normalize("noël"))
        assertEquals("naive", AccentNormalizer.normalize("naïve"))
    }

    // =========================================================================
    // Spanish accent tests
    // =========================================================================

    @Test
    fun testSpanishAccents() {
        assertEquals("espanol", AccentNormalizer.normalize("español"))
        assertEquals("nino", AccentNormalizer.normalize("niño"))
        assertEquals("manana", AccentNormalizer.normalize("mañana"))
    }

    @Test
    fun testSpanishAcuteAccents() {
        assertEquals("rapido", AccentNormalizer.normalize("rápido"))
        assertEquals("telefono", AccentNormalizer.normalize("teléfono"))
        assertEquals("musica", AccentNormalizer.normalize("música"))
    }

    @Test
    fun testSpanishInvertedMarks() {
        // Inverted question/exclamation should be preserved or handled
        val result = AccentNormalizer.normalize("¿Cómo estás?")
        assertTrue(result.contains("como") || result.contains("Como"))
    }

    // =========================================================================
    // German accent tests
    // =========================================================================

    @Test
    fun testGermanUmlauts() {
        assertEquals("munchen", AccentNormalizer.normalize("münchen"))
        assertEquals("uber", AccentNormalizer.normalize("über"))
        assertEquals("gross", AccentNormalizer.normalize("groß"))
    }

    @Test
    fun testGermanEszett() {
        // ß -> ss or s
        val result = AccentNormalizer.normalize("straße")
        assertTrue(result == "strasse" || result == "strase")
    }

    // =========================================================================
    // Portuguese accent tests
    // =========================================================================

    @Test
    fun testPortugueseAccents() {
        assertEquals("portugues", AccentNormalizer.normalize("português"))
        assertEquals("aviao", AccentNormalizer.normalize("avião"))
        assertEquals("coracao", AccentNormalizer.normalize("coração"))
    }

    @Test
    fun testPortugueseTilde() {
        assertEquals("nao", AccentNormalizer.normalize("não"))
        assertEquals("sao", AccentNormalizer.normalize("são"))
    }

    // =========================================================================
    // Mixed text tests
    // =========================================================================

    @Test
    fun testMixedAccentedText() {
        val input = "Café résumé naïve"
        val result = AccentNormalizer.normalize(input)

        assertTrue(result.contains("cafe") || result.contains("Cafe"))
        assertTrue(result.contains("resume"))
        assertTrue(result.contains("naive"))
    }

    @Test
    fun testNonAccentedText() {
        val input = "Hello World"
        val result = AccentNormalizer.normalize(input)
        // Normalizer may convert to lowercase
        assertTrue(result.equals("Hello World", ignoreCase = true))
    }

    @Test
    fun testAccentedUppercase() {
        val result = AccentNormalizer.normalize("CAFÉ")
        // May be uppercase or lowercase depending on implementation
        assertTrue(result.equals("cafe", ignoreCase = true))
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testEmptyString() {
        assertEquals("", AccentNormalizer.normalize(""))
    }

    @Test
    fun testOnlyAccents() {
        // String with just combining diacritical marks
        val result = AccentNormalizer.normalize("́̀̂")
        // Should handle without crashing
        assertNotNull(result)
    }

    @Test
    fun testLongAccentedText() {
        val input = "Éléphant café résumé naïve straße über München"
        val result = AccentNormalizer.normalize(input)

        assertNotNull(result)
        assertTrue(result.length > 0)
    }

    @Test
    fun testUnicodeNormalization() {
        // Test composed vs decomposed forms
        val composed = "é" // Single codepoint
        val decomposed = "é" // e + combining accent

        val normalizedComposed = AccentNormalizer.normalize(composed)
        val normalizedDecomposed = AccentNormalizer.normalize(decomposed)

        assertEquals("Both forms should normalize the same",
            normalizedComposed, normalizedDecomposed)
    }

    // =========================================================================
    // Performance test
    // =========================================================================

    @Test
    fun testNormalizationPerformance() {
        val input = "Café résumé naïve über München"
        val startTime = System.currentTimeMillis()

        for (i in 1..1000) {
            AccentNormalizer.normalize(input)
        }

        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("1000 normalizations should complete in under 500ms", elapsed < 500)
    }
}
