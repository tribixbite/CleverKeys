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
    fun getNonPairedMappingNullForPairedBase() {
        // ORACLE-FLIP(2026-07-23 multilingual audit): "well" IS a valid word with a
        // contraction sibling — a PAIRED base. The shipped binary misclassified it into
        // the non-paired map (violating this method's documented contract), which made
        // the typing pipeline REPLACE "well"/"were" in the bar. loadMappings now
        // reclassifies paired bases out of the non-paired map; the mapping lives in
        // getPairedContractions (asserted below) and consumers inject it ALONGSIDE.
        assertNull(manager.getNonPairedMapping("well"))
        assertNull(manager.getNonPairedMapping("were"))
        assertEquals(listOf("we'll"), manager.getPairedContractions("well"))
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

    // =========================================================================
    // loadSwipeDisplayMappings — the swipe engines' language-scoped loader
    // =========================================================================

    /**
     * The swipe adapters overlay contractions on a slate decoded in ONE language, so they
     * must see ONLY that language's mappings. Loading the bundled ENGLISH base for every
     * language put English morphology in non-English slates — a `fr` decode of the real
     * French word `franco` also offered the English possessive `franco's`
     * (`CtcMultiLanguageInstrumentedTest`, 2026-08-16). `franco` is the exact probe here:
     * it is a French word, so a French beam CAN emit it, and its English pairing is what
     * used to fire.
     */
    @Test
    fun loadSwipeDisplayMappingsDropsEnglishForAnotherLanguage() {
        // Pre-condition: the English base (loaded by setup) really does pair "franco".
        assertNotNull(
            "fixture: the bundled English base must pair 'franco' with its possessive",
            manager.getPairedContractions("franco")
        )
        assertEquals("don't", manager.getNonPairedMapping("dont"))

        manager.loadSwipeDisplayMappings("fr")

        assertNull(
            "French swipes must not see the English possessive pairing for 'franco'",
            manager.getPairedContractions("franco")
        )
        assertNull(
            "French swipes must not see the English 'dont' -> \"don't\" alias ('dont' is " +
                "the 104th most common FRENCH word)",
            manager.getNonPairedMapping("dont")
        )
        // ...while French's own contractions ARE loaded.
        assertEquals("c'est", manager.getNonPairedMapping("cest"))
        assertEquals("j'ai", manager.getNonPairedMapping("jai"))
    }

    /**
     * A language ships its mappings in TWO files and the loader must read both: the
     * REPLACE-mode `contractions_fr.json` (the key is an alias with no reading of its own)
     * and the APPEND-mode `contraction_pairs_fr.json` (the key IS a French word, so the
     * overlay keeps it and merely offers the elision). Before 2026-08-17 there was only the
     * first file and the mode was guessed from frequency rank at runtime, which REPLACED
     * common French words: swiping the moon ("lune", rank 2,055) produced only "l'une".
     */
    @Test
    fun loadSwipeDisplayMappingsLoadsBothTheReplaceAndTheAppendFile() {
        manager.loadSwipeDisplayMappings("fr")

        assertEquals(
            "'lune' is a French word: it must be a PAIRED base, so the overlay keeps it",
            listOf("l'une"), manager.getPairedContractions("lune")
        )
        assertNull(
            "'lune' must not also be a non-paired alias — that would REPLACE the word",
            manager.getNonPairedMapping("lune")
        )
        assertTrue(manager.isKnownContraction("l'une"))
        // The alias half of the split is unchanged: "cest" is not a French word.
        assertNull(manager.getPairedContractions("cest"))
        assertEquals("c'est", manager.getNonPairedMapping("cest"))

        manager.loadSwipeDisplayMappings("it")
        assertEquals(listOf("l'ago"), manager.getPairedContractions("lago"))
        assertNull(
            "Italian swipes must not retain the French pairs file ('danse' is French-only; " +
                "the two files do share 9 keys, 'lune' among them)",
            manager.getPairedContractions("danse")
        )
    }

    /** A language that ships no contraction file must end up with NO overlay at all. */
    @Test
    fun loadSwipeDisplayMappingsLeavesNoMappingsForALanguageWithoutContractions() {
        manager.loadSwipeDisplayMappings("es")

        assertEquals(
            "contractions_es.json is empty — Spanish must get no contractions, not English's",
            0, manager.getNonPairedCount()
        )
        assertEquals(0, manager.getTotalKnownCount())
        assertNull(manager.getPairedContractions("franco"))
        assertNull(manager.getNonPairedMapping("dont"))
    }

    /**
     * The adapters reuse ONE manager instance across language switches, so every switch
     * must start from a cleared state — including the switch BACK to English, which must
     * restore the full base set.
     */
    @Test
    fun loadSwipeDisplayMappingsSwitchesLanguagesWithoutRetainingThePreviousOne() {
        manager.loadSwipeDisplayMappings("fr")
        assertEquals("c'est", manager.getNonPairedMapping("cest"))

        manager.loadSwipeDisplayMappings("de")
        assertNull(
            "German swipes must not retain French mappings",
            manager.getNonPairedMapping("cest")
        )
        assertEquals("d'or", manager.getNonPairedMapping("dor"))
        // German's OWN morphology: the clitic-"es" elisions added 2026-08-16 (Duden D 16),
        // every key verified present in the bundled German dictionary.
        assertEquals("geht's", manager.getNonPairedMapping("gehts"))
        assertEquals("gibt's", manager.getNonPairedMapping("gibts"))
        assertEquals("wenn's", manager.getNonPairedMapping("wenns"))
        assertNull(
            "German swipes must not see the English 'im' -> \"i'm\" alias ('im' is the " +
                "16th most common GERMAN word)",
            manager.getNonPairedMapping("im")
        )

        manager.loadSwipeDisplayMappings("en")
        assertEquals("don't", manager.getNonPairedMapping("dont"))
        assertNotNull(manager.getPairedContractions("well"))
        assertNull("English must not retain German mappings", manager.getNonPairedMapping("dor"))
    }

    /** Regional English variants keep the pre-fix English behavior. */
    @Test
    fun loadSwipeDisplayMappingsTreatsRegionalEnglishAsEnglish() {
        manager.loadSwipeDisplayMappings("en-GB")
        assertEquals("don't", manager.getNonPairedMapping("dont"))
        assertTrue(manager.getTotalKnownCount() > 0)
    }
}
