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
        // Fixed 2026-09: s-final words take the bare trailing apostrophe — "parents's"
        // (the old modern-style "add 's always") was malformed for plurals.
        assertEquals("James'", manager.generatePossessive("James"))
        assertEquals("parents'", manager.generatePossessive("parents"))
    }

    @Test
    fun generatePossessiveNullForAlreadyPossessiveInput() {
        // Fixed 2026-09: an input already containing an apostrophe is never re-augmented
        // (previously "Book's" -> "Book's's").
        assertNull(manager.generatePossessive("Book's"))
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

    // =========================================================================
    // TYPING path: cross-language collision demotion (2026-08-20)
    //
    // `loadTypingMappings` merges MULTIPLE languages into one map, which is the
    // difference from `loadSwipeDisplayMappings` above (one language per manager).
    // That merge had no provenance, so a REPLACE key of one active language was
    // applied to a real word of the other and destroyed it in its own slot.
    //
    // These run instrumented rather than pure because they are the only tests that
    // exercise the real wiring: real assets through a real AssetManager, the real
    // load-order policy, and the real demotion call inside `loadTypingMappings`.
    // `ContractionCollisionDemotionTest` pins the rule and
    // `ContractionCollisionDataTest` pins the sidecars, but both would still pass if
    // someone deleted the call — only these catch that.
    // =========================================================================

    /**
     * The 2026-07-23 paired-base reclassification must SURVIVE the typing load order.
     *
     * `loadEnglishBase` loads the base, loads the pairings, then removes every pairing base from
     * the non-paired map — that is what stopped typing "well" producing "we'll" and destroying
     * the word. But `loadTypingMappings` then calls `loadLanguageContractions("en")`, and
     * `contractions_en.json` contains 14 of those same bases (`well`, `shell`, `hell`, `were`,
     * `girls`, `states`, …). `loadContractionsFromStream` skips a key only when it is already in
     * the NON-PAIRED map — and reclassification had just removed it from there — so the second
     * load re-adds it as REPLACE and undoes the fix.
     *
     * Found by `ContractionCollisionScannerTest`, which reported `shell`/`girls`/`hell`/`states`
     * as collisions the shipped sidecars did not cover: the sidecar generator models English as
     * base-minus-pairings, which is what the runtime SHOULD hold, while the runtime scanner reads
     * what it ACTUALLY holds. The two disagreeing was the symptom.
     */
    @Test
    fun loadTypingMappingsKeepsPairedBasesOutOfTheReplaceMap() {
        manager.loadTypingMappings("en", null)

        for (base in listOf("well", "shell", "hell", "were", "girls", "states")) {
            assertNull(
                "'$base' is an ordinary English word — a REPLACE mapping substitutes the " +
                    "contraction and destroys it in its own slot, which is exactly what the " +
                    "2026-07-23 reclassification fixed for the swipe path",
                manager.getNonPairedMapping(base)
            )
            assertNotNull(
                "'$base' must still offer its contraction as a PAIRED variant",
                manager.getPairedContractions(base)
            )
        }
        // The aliases that genuinely have no reading of their own are unaffected.
        assertEquals("don't", manager.getNonPairedMapping("dont"))
        assertEquals("can't", manager.getNonPairedMapping("cant"))
    }

    /**
     * The highest-frequency casualty: `dont` is an English REPLACE key AND one of the
     * commonest words in French (the relative pronoun). An fr+en user typing it got `don't`.
     */
    @Test
    fun loadTypingMappingsDoesNotLetEnglishDestroyACommonFrenchWord() {
        manager.loadTypingMappings("fr", "en")

        assertNull(
            "'dont' is a common French word — while it is a REPLACE key the tap path " +
                "substitutes \"don't\" and the French word is destroyed in its own slot",
            manager.getNonPairedMapping("dont")
        )
        assertTrue(
            "the English elision must remain reachable as an APPEND variant, not vanish",
            manager.getPairedContractions("dont")?.contains("don't") == true
        )
        // An English key with no French reading is untouched: the guard must be surgical.
        assertEquals("can't", manager.getNonPairedMapping("cant"))
    }

    /** Same defect in German, where `im` (in dem) is far commoner still. */
    @Test
    fun loadTypingMappingsDoesNotLetEnglishDestroyACommonGermanWord() {
        manager.loadTypingMappings("de", "en")

        assertNull(
            "'im' is German for 'in dem' — it must not be rewritten to \"I'm\"",
            manager.getNonPairedMapping("im")
        )
        assertTrue(manager.getPairedContractions("im")?.contains("i'm") == true)
    }

    /**
     * The bug is BIDIRECTIONAL, and this is the half that is easy to forget: German's curated
     * clitic table maps `hats` -> `hat's`, and `hats` is an ordinary English word.
     */
    @Test
    fun loadTypingMappingsDoesNotLetGermanDestroyAnEnglishWord() {
        manager.loadTypingMappings("de", "en")

        assertNull(
            "'hats' is an English word; de's curated clitic table must not rewrite it",
            manager.getNonPairedMapping("hats")
        )
        assertTrue(manager.getPairedContractions("hats")?.contains("hat's") == true)
    }

    /**
     * The other side of the contract, and the reason the sidecar stores WHICH languages
     * collide rather than a boolean: a demotion must only fire for a language the user has
     * actually enabled. An English-only user's `dont` -> `don't` is correct and must survive.
     */
    @Test
    fun loadTypingMappingsLeavesAMonolingualUserUntouched() {
        manager.loadTypingMappings("en", null)

        assertEquals(
            "for an English-only user 'dont' has no competing reading — demoting it here " +
                "would remove a correct mapping to fix a problem they do not have",
            "don't", manager.getNonPairedMapping("dont")
        )
        assertEquals("can't", manager.getNonPairedMapping("cant"))
    }

    /**
     * `rendezvous` is the entry this whole mechanism was built for. It is legitimate French,
     * and also an English lexicon word and a German one, so it was held out of the shipped
     * data entirely until the demotion existed. Both halves of its contract are asserted here
     * because either one alone is satisfiable by a broken implementation.
     */
    @Test
    fun loadTypingMappingsKeepsRendezvousForFrenchOnlyButDemotesItAlongsideEnglish() {
        manager.loadTypingMappings("fr", null)
        assertEquals(
            "an fr-only user must get the French hyphenation — nothing of theirs collides",
            "rendez-vous", manager.getNonPairedMapping("rendezvous")
        )

        manager.loadTypingMappings("fr", "en")
        assertNull(
            "with English active, 'rendezvous' is a word the user may be typing in English",
            manager.getNonPairedMapping("rendezvous")
        )
        assertTrue(
            "and the French hyphenation stays offered alongside it",
            manager.getPairedContractions("rendezvous")?.contains("rendez-vous") == true
        )
    }

    /**
     * The demotion must not be a bulk cull. If it removed a large fraction of the REPLACE
     * table, the fix would itself be the regression — users would stop getting elisions they
     * rely on. Measured worst case is fr+en at ~158 of ~18k keys.
     */
    @Test
    fun loadTypingMappingsDemotesOnlyASmallFractionOfTheTable() {
        manager.loadTypingMappings("fr", "en")
        val afterBoth = manager.getNonPairedCount()

        manager.loadTypingMappings("fr", null)
        val frOnly = manager.getNonPairedCount()

        assertTrue("fr-only must load the bulk of the French table", frOnly > 17_000)
        // fr+en loads MORE files than fr-only (the English base too), so the counts are not
        // directly comparable — assert the shortfall is small rather than comparing equality.
        assertTrue(
            "fr+en kept $afterBoth of a table whose French half alone is $frOnly — a " +
                "shortfall this large would mean the collision data is over-broad",
            afterBoth > frOnly - 500
        )
    }

    /**
     * The wiring itself. If `loadTypingMappings` stopped calling the demotion, every pure test
     * would still pass and the casualties above would silently return — so this asserts the
     * observable difference between "the collision data exists" and "it is applied".
     */
    @Test
    fun loadTypingMappingsActuallyAppliesTheDemotionRatherThanJustShippingTheData() {
        manager.loadTypingMappings("fr", "en")
        val demotedKeys = listOf("dont", "im", "ive", "rendezvous")

        for (key in demotedKeys) {
            assertNull(
                "'$key' is still a REPLACE key for an fr+en user — the demotion is not wired " +
                    "into loadTypingMappings, or the fr/en sidecars were not read",
                manager.getNonPairedMapping(key)
            )
            assertNotNull(
                "'$key' lost its elision entirely — demotion must MOVE it to PAIRED, not drop it",
                manager.getPairedContractions(key)
            )
        }
    }
}
