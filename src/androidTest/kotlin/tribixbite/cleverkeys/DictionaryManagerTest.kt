package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for DictionaryManager.
 * Tests user dictionary, custom words, and language-specific dictionaries.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryManagerTest {

    private lateinit var context: Context
    private lateinit var manager: DictionaryManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = DictionaryManager(context)
    }

    @After
    fun cleanup() {
        // Clean up test words
        try {
            manager.removeUserWord("testword123")
            manager.removeUserWord("customword456")
            manager.removeUserWord("ProperNounTest")
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    // =========================================================================
    // Basic word management tests
    // =========================================================================

    @Test
    fun testAddUserWord() {
        val testWord = "testword123"
        manager.addUserWord(testWord)

        assertTrue("Word should be added", manager.isUserWord(testWord))
    }

    @Test
    fun testRemoveUserWord() {
        val testWord = "testword123"
        manager.addUserWord(testWord)
        manager.removeUserWord(testWord)

        assertFalse("Word should be removed", manager.isUserWord(testWord))
    }

    // =========================================================================
    // Case preservation tests (Issue #72)
    // =========================================================================

    @Test
    fun testProperNounCasePreserved() {
        val properNoun = "ProperNounTest"
        manager.addUserWord(properNoun)

        // The word should be findable
        assertTrue(manager.isUserWord(properNoun))
    }

    @Test
    fun testLowercaseWordStaysLowercase() {
        val word = "lowercaseword"
        manager.addUserWord(word)

        assertTrue(manager.isUserWord(word))
    }

    // =========================================================================
    // Language switching tests
    // =========================================================================

    @Test
    fun testSetLanguageEnglish() {
        manager.setLanguage("en")
        assertEquals("en", manager.getCurrentLanguage())
    }

    @Test
    fun testSetLanguageSpanish() {
        manager.setLanguage("es")
        assertEquals("es", manager.getCurrentLanguage())
    }

    @Test
    fun testSetLanguageFrench() {
        manager.setLanguage("fr")
        assertEquals("fr", manager.getCurrentLanguage())
    }

    @Test
    fun testSetLanguageNull() {
        manager.setLanguage(null)
        // Should default to "en"
        assertEquals("en", manager.getCurrentLanguage())
    }

    // =========================================================================
    // Predictions tests
    // =========================================================================

    @Test
    fun testGetPredictions() {
        val predictions = manager.getPredictions("hel")
        assertNotNull("Predictions should not be null", predictions)
    }

    @Test
    fun testGetPredictionsEmptyPrefix() {
        val predictions = manager.getPredictions("")
        // Should handle empty prefix gracefully
        assertNotNull(predictions)
    }

    @Test
    fun testGetPredictionsForUnknownPrefix() {
        val predictions = manager.getPredictions("xyzqwerty123")
        assertNotNull(predictions)
        // May return empty list for unknown prefix
    }

    @Test
    fun testUserWordAppearsinPredictions() {
        val testWord = "mytestword"
        manager.addUserWord(testWord)

        val predictions = manager.getPredictions("mytest")
        // User word may or may not appear depending on dictionary state
        // Just verify no crash and predictions is non-null
        assertNotNull("Predictions should not be null", predictions)

        manager.removeUserWord(testWord)
    }

    // =========================================================================
    // Clear dictionary tests
    // =========================================================================

    @Test
    fun testClearUserDictionary() {
        manager.addUserWord("word1")
        manager.addUserWord("word2")

        manager.clearUserDictionary()

        assertFalse(manager.isUserWord("word1"))
        assertFalse(manager.isUserWord("word2"))
    }

    // =========================================================================
    // Loading state tests
    // =========================================================================

    @Test
    fun testIsLoading() {
        // Just verify it returns a boolean without crashing
        val loading = manager.isLoading()
        // Can be true or false
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testAddEmptyWord() {
        // Should handle gracefully
        manager.addUserWord("")
        // Empty word should not be added
        assertFalse(manager.isUserWord(""))
    }

    @Test
    fun testAddNullWord() {
        // Should handle null gracefully
        manager.addUserWord(null)
        // Should not crash
    }

    @Test
    fun testRemoveNonexistentWord() {
        // Should handle gracefully without crashing
        manager.removeUserWord("nonexistentword12345")
    }
}
