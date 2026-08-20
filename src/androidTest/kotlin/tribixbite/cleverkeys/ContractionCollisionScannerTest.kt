package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ContractionCollisionScanner] — the language-selection-time scan.
 *
 * ## What this covers that the pure tests do not
 *
 * `ContractionCollisionDemotionTest` pins the demotion RULE on synthetic maps, and
 * `ContractionCollisionDataTest` pins the shipped sidecars against a recomputation. Neither
 * touches the scanner, because it reads real assets and installed language packs through a real
 * `Context` and caches through `SharedPreferences`.
 *
 * The scan exists for one reason: an IMPORTED language pack cannot have a shipped
 * `contraction_collisions_<lang>.json` sidecar, because its contraction file and dictionary
 * arrive on the device long after the build. Bundled languages are already covered, so the
 * scanner's correctness is mostly about what it does NOT report and about the cache scope.
 *
 * No language pack is installed on the test device, so the pack-collision path cannot be
 * exercised end to end here. What IS asserted is everything reachable without one — that bundled
 * combinations produce no spurious pack collisions, that a single language is a no-op, and that
 * the cache refuses to apply itself to a different language set, which is the property that makes
 * a stale cache harmless.
 */
@RunWith(AndroidJUnit4::class)
class ContractionCollisionScannerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun cleanup() {
        // The scan caches into real prefs. Leaving a cache behind would leak into any later test
        // that loads typing mappings — see the orchestrator state-leak rules in the ew-cli skill.
        // `commit()`, not `apply()`: the orchestrator kills the process the moment a test ends.
        DirectBootAwarePreferences.get_shared_preferences(context).edit()
            .remove(ContractionCollisionScanner.PREFS_KEY)
            .remove(ContractionCollisionScanner.PREFS_SCOPE_KEY)
            .commit()
    }

    @Test
    fun aSingleActiveLanguageCannotCollide() {
        val report = ContractionCollisionScanner.scan(context, setOf("fr"))

        assertFalse(
            "one language cannot collide with itself — every REPLACE key it holds was " +
                "classified against its own lexicon at generation time",
            report.hasPackCollisions
        )
        assertEquals(0, report.bundledCollisionCount)
    }

    @Test
    fun noneAndBlankSelectionsAreIgnored() {
        // The secondary/alternate selectors use the literal "none", and the alternates default
        // to it. Treating that as a language would try to read `contractions_none.json`.
        val report = ContractionCollisionScanner.scan(context, setOf("fr", "none", ""))
        assertFalse(report.hasPackCollisions)
        assertEquals(setOf("fr"), report.scannedLanguages)
    }

    @Test
    fun regionalTagsAreNormalisedToTheBaseLanguage() {
        // "en-GB" must scan as English, or its sidecar and lexicon are both missed.
        val report = ContractionCollisionScanner.scan(context, setOf("fr", "en-GB"))
        assertEquals(setOf("fr", "en"), report.scannedLanguages)
    }

    /**
     * The load-bearing negative: with only BUNDLED languages active, the scan must report no
     * PACK collisions, because every one of them is already in a shipped sidecar.
     *
     * If this ever fails it means the sidecars and the scanner disagree about the same data —
     * either the sidecars are stale (regenerate with
     * `python3 scripts/build_contraction_collisions.py`) or the scanner is modelling the English
     * base differently from the generator.
     */
    @Test
    fun bundledLanguagesProduceNoPackCollisionsBecauseTheSidecarsCoverThemAll() {
        for (pair in listOf(setOf("fr", "en"), setOf("de", "en"), setOf("it", "en"))) {
            val report = ContractionCollisionScanner.scan(context, pair)
            assertTrue(
                "$pair reported ${report.packCollisions.size} uncovered collisions " +
                    "(${report.packCollisions.keys.take(8)}) — the shipped sidecars and the " +
                    "runtime scanner disagree about the same bundled data",
                report.packCollisions.isEmpty()
            )
            assertTrue(
                "$pair found no collisions AT ALL, which contradicts the measured data — the " +
                    "scanner is not reading the contraction files or the lexicons",
                report.bundledCollisionCount > 0
            )
        }
    }

    /** fr+en is the worst measured pair; its known casualties must be seen by the scanner. */
    @Test
    fun theScannerSeesTheKnownFrenchEnglishCasualties() {
        val report = ContractionCollisionScanner.scan(context, setOf("fr", "en"))
        assertTrue(
            "fr+en has ~158 measured collisions; the scanner counted " +
                "${report.bundledCollisionCount}",
            report.bundledCollisionCount >= 100
        )
    }

    // ── cache scope ──────────────────────────────────────────────────────────────────

    @Test
    fun aCachedScanIsOnlyAppliedToTheLanguageSetItWasComputedFor() {
        val report = ContractionCollisionScanner.Report(
            packCollisions = mapOf("probeword" to setOf("en")),
            bundledCollisionCount = 0,
            examples = emptyList(),
            scannedLanguages = setOf("nl", "en"),
        )
        ContractionCollisionScanner.cache(context, report)

        assertEquals(
            "the cache must apply to the exact set it was computed for",
            setOf("en"),
            ContractionCollisionScanner.cachedFor(context, setOf("nl", "en"))["probeword"]
        )
        assertTrue(
            "a cache computed for nl+en must NOT be applied to fr+en — demoting keys for a " +
                "language that is no longer active would suppress correct contractions, which " +
                "is a new wrong behaviour rather than the old missing one",
            ContractionCollisionScanner.cachedFor(context, setOf("fr", "en")).isEmpty()
        )
        assertTrue(
            "nor to a subset",
            ContractionCollisionScanner.cachedFor(context, setOf("nl")).isEmpty()
        )
    }

    @Test
    fun cacheScopeIgnoresOrderingAndNoneEntries() {
        val report = ContractionCollisionScanner.Report(
            packCollisions = mapOf("probeword" to setOf("en")),
            bundledCollisionCount = 0,
            examples = emptyList(),
            scannedLanguages = setOf("en", "nl"),
        )
        ContractionCollisionScanner.cache(context, report)

        // Same set, different iteration order and with the selectors' literal "none" present.
        assertFalse(
            "scope matching must be order-independent and must drop 'none', or the cache is " +
                "discarded on every load and the feature silently does nothing",
            ContractionCollisionScanner.cachedFor(context, setOf("nl", "en", "none")).isEmpty()
        )
    }

    @Test
    fun anAbsentCacheIsEmptyRatherThanAnError() {
        DirectBootAwarePreferences.get_shared_preferences(context).edit()
            .remove(ContractionCollisionScanner.PREFS_KEY)
            .remove(ContractionCollisionScanner.PREFS_SCOPE_KEY)
            .commit()

        assertTrue(
            "a device that has never opened Settings has no cache; that must degrade to the " +
                "shipped sidecars alone, not throw on every language load",
            ContractionCollisionScanner.cachedFor(context, setOf("fr", "en")).isEmpty()
        )
    }
}
