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
    // Language switching
    // =========================================================================

    /**
     * ARC-079 removed `isLoading()` (and the per-language predictor cache behind it): load
     * state belongs to the one predictor the process owns, reached through
     * `PredictionCoordinator.getWordPredictor()`. What the manager still owns is the active
     * language and the user-word set scoped to it, so that is what this covers on device.
     */
    @Test
    fun testSetLanguageScopesUserWords() {
        val original = manager.getCurrentLanguage()
        try {
            manager.setLanguage("en")
            manager.addUserWord("testword123")
            assertTrue("Word should be present in its own language", manager.isUserWord("testword123"))

            manager.setLanguage("fr")
            assertFalse(
                "An English custom word must not be treated as user-owned in French",
                manager.isUserWord("testword123")
            )

            manager.setLanguage("en")
            assertTrue("Switching back restores the language's words", manager.isUserWord("testword123"))
        } finally {
            manager.setLanguage("en")
            manager.removeUserWord("testword123")
            manager.setLanguage(original)
        }
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
     * `replaceModeContractionFor` refuses to REPLACE a word the user added by hand, and since
     * 2026-08-29 it decides that with the single case-folded [DictionaryManager.isUserWordIgnoringCase]
     * (it used to probe [DictionaryManager.isUserWord] in three casings, which missed a word
     * stored as `DAngle`). This pins the behaviour that decision depends on, against real
     * on-device SharedPreferences.
     *
     * It matters because the shipped French REPLACE table holds ~18k `d'X` aliases — `dangle`,
     * `dalliance` and so on — that are ordinary strings someone may add as a name or a term of
     * art. If the lookup ever stopped matching the stored form, the guard would silently become
     * a no-op and the contraction file would start rewriting user-owned words again.
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
                "the capitalised form must match too",
                manager.isUserWord(stored.lowercase().replaceFirstChar { it.uppercaseChar() })
            )
            assertTrue(
                "the folded lookup the guard actually calls must match EVERY casing — this is " +
                    "the one that turned the guard from case-partial into total",
                manager.isUserWordIgnoringCase(stored.uppercase())
            )
            assertTrue(
                "…including the all-lowercase form the decoder normally produces",
                manager.isUserWordIgnoringCase(stored.lowercase())
            )
            assertFalse(
                "a word that was never added must NOT report as user-owned, or the guard " +
                    "would suppress every contraction rather than just the user's own",
                manager.isUserWordIgnoringCase("notaddedbyanyone98765")
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
        assertFalse(
            "cleanup must leave no residue in the folded view either",
            manager.isUserWordIgnoringCase(stored)
        )
    }
}
