package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for ContractionManager.
 * Covers contraction loading (binary/JSON), lookup, possessive generation,
 * and language-specific contractions.
 * Requires real Context for asset access (binary contraction files).
 */
@RunWith(AndroidJUnit4::class)
class ContractionManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ContractionManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = ContractionManager(context)
        manager.loadMappings()
    }

    // =========================================================================
    // Loading
    // =========================================================================

    @Test
    fun loadMappingsLoadsData() {
        assertTrue("Should load non-paired contractions", manager.getNonPairedCount() > 0)
        assertTrue("Should load known contractions", manager.getTotalKnownCount() > 0)
        assertTrue("Known count >= non-paired count",
            manager.getTotalKnownCount() >= manager.getNonPairedCount())
    }

    @Test
    fun loadMappingsLoadsCoreEnglishContractions() {
        // Core English contractions should always be loaded
        assertTrue(manager.isKnownContraction("don't"))
        assertTrue(manager.isKnownContraction("can't"))
        assertTrue(manager.isKnownContraction("won't"))
        assertTrue(manager.isKnownContraction("it's"))
        assertTrue(manager.isKnownContraction("i'm"))
        assertTrue(manager.isKnownContraction("we'll"))
    }

    @Test
    fun reloadClearsPreviousData() {
        val initialCount = manager.getNonPairedCount()
        manager.loadMappings() // Reload
        assertEquals("Reload should not double entries", initialCount, manager.getNonPairedCount())
    }

    // =========================================================================
    // isKnownContraction
    // =========================================================================

    @Test
    fun isKnownContractionTrueForApostropheForm() {
        assertTrue(manager.isKnownContraction("don't"))
        assertTrue(manager.isKnownContraction("we'll"))
    }

    @Test
    fun isKnownContractionCaseInsensitive() {
        assertTrue(manager.isKnownContraction("Don't"))
        assertTrue(manager.isKnownContraction("DON'T"))
    }

    @Test
    fun isKnownContractionFalseForRegularWord() {
        assertFalse(manager.isKnownContraction("hello"))
        assertFalse(manager.isKnownContraction("world"))
    }

    // =========================================================================
    // isContractionKey
    // =========================================================================

    @Test
    fun isContractionKeyTrueForApostropheFreeForm() {
        assertTrue(manager.isContractionKey("dont"))
        assertTrue(manager.isContractionKey("cant"))
    }

    @Test
    fun isContractionKeyCaseInsensitive() {
        assertTrue(manager.isContractionKey("Dont"))
        assertTrue(manager.isContractionKey("CANT"))
    }

    @Test
    fun isContractionKeyFalseForRegularWord() {
        assertFalse(manager.isContractionKey("hello"))
    }

    // =========================================================================
    // getNonPairedMapping
    // =========================================================================

    @Test
    fun getNonPairedMappingReturnContraction() {
        assertEquals("don't", manager.getNonPairedMapping("dont"))
        assertEquals("can't", manager.getNonPairedMapping("cant"))
    }

    @Test
    fun getNonPairedMappingCaseInsensitive() {
        assertNotNull(manager.getNonPairedMapping("DONT"))
    }

    @Test
    fun getNonPairedMappingNullForUnknown() {
        assertNull(manager.getNonPairedMapping("hello"))
    }

    @Test
    fun getNonPairedMappingReturnsValueForWell() {
        // "well" -> "we'll" is stored as a non-paired mapping in the binary data
        val result = manager.getNonPairedMapping("well")
        assertEquals("we'll", result)
    }

    // =========================================================================
    // generatePossessive
    // =========================================================================

    @Test
    fun generatePossessiveAddsApostropheS() {
        assertEquals("cat's", manager.generatePossessive("cat"))
        assertEquals("dog's", manager.generatePossessive("dog"))
    }

    @Test
    fun generatePossessiveWorksForSEndingWord() {
        // Modern style: even words ending in 's' get 's
        assertEquals("James's", manager.generatePossessive("James"))
    }

    @Test
    fun generatePossessiveNullForNullInput() {
        assertNull(manager.generatePossessive(null))
    }

    @Test
    fun generatePossessiveNullForEmptyInput() {
        assertNull(manager.generatePossessive(""))
    }

    @Test
    fun generatePossessiveNullForContraction() {
        assertNull(manager.generatePossessive("don't"))
    }

    @Test
    fun generatePossessiveNullForFunctionWord() {
        assertNull(manager.generatePossessive("he"))
        assertNull(manager.generatePossessive("they"))
        assertNull(manager.generatePossessive("will"))
    }

    // =========================================================================
    // shouldGeneratePossessive
    // =========================================================================

    @Test
    fun shouldGeneratePossessiveTrueForNouns() {
        assertTrue(manager.shouldGeneratePossessive("cat"))
        assertTrue(manager.shouldGeneratePossessive("John"))
    }

    @Test
    fun shouldGeneratePossessiveFalseForPronouns() {
        assertFalse(manager.shouldGeneratePossessive("he"))
        assertFalse(manager.shouldGeneratePossessive("they"))
    }

    // =========================================================================
    // Language-specific contractions
    // =========================================================================

    @Test
    fun loadFrenchContractionsIfAvailable() {
        val initialCount = manager.getTotalKnownCount()
        manager.loadLanguageContractions("fr")
        // May or may not have French file — just ensure no crash
        assertTrue(manager.getTotalKnownCount() >= initialCount)
    }

    @Test
    fun loadNonExistentLanguageDoesNotCrash() {
        manager.loadLanguageContractions("xx")
        // No crash = success
        assertTrue(manager.getNonPairedCount() > 0) // English still loaded
    }
}
