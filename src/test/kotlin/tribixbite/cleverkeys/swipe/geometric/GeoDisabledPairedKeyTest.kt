package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Pins the GEOMETRIC engine's current behaviour for a disabled paired contraction key (test-gap
 * audit 2026-11-28, item 9; Saga r21 case 10). `we'll` is the display of the decoded surface
 * `well` (ContractionOverlay). CTC keeps a disabled paired key decodable at its own frequency
 * (`swipe/DisabledPairedSurfaces.kt`) and filters the disabled word from the slate, so `we'll`
 * stays. The geometric merge drops every disabled word ([GeometricUserWordMerge.merge]), so the
 * w-e-l-l trace can no longer decode `well` and the overlay never offers `we'll`.
 *
 * This is the KNOWN gap behind the TODO in GeometricEngineAdapter.dictionaryFor: the fix needs
 * the kept surfaces in the merge, a slate filter after the overlay and the overlay's
 * `isExcludedBase`, i.e. three coordinated adapter changes, left for 2.1. When it lands, the
 * second assertion flips: the decode then still finds `well`, and the adapter filters it.
 */
class GeoDisabledPairedKeyTest {

    private val config = GeometricEngineConfig()
    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val base = ArrayBackedDictionary("en", 1L, arrayOf("well", "will", "wall", "we", "ell"))

    private fun decodeWell(disabled: Set<String>): List<String> {
        val trace = GeoIdealTrace.of("well", qwerty, TemplateGenerator(config))!!
        val dict = GeometricUserWordMerge.merge(base, emptyList(), disabled, "en", 2L)
        return GeometricSwipeEngine(config).decode(GeoIdealTrace.request(trace, qwerty, dict)).words
    }

    @Test
    fun aDisabledPairedKeyIsNotDecodableOnTheGeometricEngine() {
        assertWithMessage("fixture: the ideal w-e-l-l trace decodes `well`").that(decodeWell(emptySet())).contains("well")
        // Current behaviour (the gap): with `well` disabled, nothing decodes it, so `we'll` is gone.
        assertWithMessage("geometric drops a disabled paired key (TODO GeometricEngineAdapter)")
            .that(decodeWell(setOf("well"))).doesNotContain("well")
    }
}
