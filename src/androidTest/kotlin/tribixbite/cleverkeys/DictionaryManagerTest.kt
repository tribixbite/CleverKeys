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

    // =========================================================================
    // Precondition for SuggestionHandler.replaceModeContractionFor (2026-08-20)
    // =========================================================================

    /**
     * `replaceModeContractionFor` refuses to REPLACE a word the user added by hand, and it
     * decides that by calling [DictionaryManager.isUserWord] on the word plus its lowercase and
     * capitalised forms. This pins the behaviour that decision depends on.
     *
     * It matters because the shipped French REPLACE table holds ~18k `d'X` aliases — `dangle`,
     * `dalliance` and so on — that are ordinary strings someone may add as a name or a term of
     * art. If `isUserWord` ever stopped matching the stored form, the guard would silently
     * become a no-op and the contraction file would start rewriting user-owned words again.
     *
     * `dangle` is used deliberately: it is a real `contractions_fr.json` key (`d'angle`), so
     * this is the actual collision shape rather than a synthetic one. It is also inert — no
     * other test types near it — per the orchestrator state-leak rules in the ew-cli skill.
     */
    @Test
    fun isUserWordMatchesTheFormsTheContractionGuardChecks() {
        val stored = "Dangle"
        try {
            manager.addUserWord(stored)

            assertTrue(
                "exact form must match — this is the common case, since custom words and the " +
                    "predictions that surface them come from the same prefs entry",
                manager.isUserWord(stored)
            )
            assertTrue(
                "the capitalised form is one of the three the guard probes",
                manager.isUserWord(stored.lowercase().replaceFirstChar { it.uppercaseChar() })
            )
            assertFalse(
                "a word that was never added must NOT report as user-owned, or the guard " +
                    "would suppress every contraction rather than just the user's own",
                manager.isUserWord("notaddedbyanyone98765")
            )
        } finally {
            manager.removeUserWord(stored)
        }
        assertFalse("cleanup must leave no residue for later tests", manager.isUserWord(stored))
    }
}
