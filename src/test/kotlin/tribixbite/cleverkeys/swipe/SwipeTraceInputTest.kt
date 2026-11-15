package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Review 2026-11-14 (swipe, low): both adapters snapshot the trace with
 * `timestamps.getOrElse(i) { 0L }`, so a path with fewer timestamps than points was decoded
 * with its tail stamped at t = 0 — time running backwards to the epoch of the gesture. The CTC
 * featurizer derives velocity from those stamps, so the decode it produced described a swipe
 * the user never made. A length mismatch means the recognizer handed over an inconsistent
 * trace; it is a degenerate swipe, decoded as no swipe at all, like an empty path.
 *
 * The geometric adapter's use of this rule is pinned behaviourally by
 * `GeometricEngineAdapterEdgeCaseTest`; the CTC adapter cannot be built on the JVM (ORT
 * natives), so its degenerate gate shares this one predicate.
 */
class SwipeTraceInputTest {

    @Test
    fun aTraceIsDecodableOnlyWhenEveryPointHasATimestamp() {
        assertWithMessage("a consistent trace").that(SwipeTraceInput.isDecodable(3, 3)).isTrue()
        assertWithMessage("an empty path").that(SwipeTraceInput.isDecodable(0, 0)).isFalse()
        assertWithMessage("fewer timestamps than points").that(SwipeTraceInput.isDecodable(3, 1)).isFalse()
        assertWithMessage("no timestamps at all").that(SwipeTraceInput.isDecodable(3, 0)).isFalse()
        assertWithMessage("more timestamps than points").that(SwipeTraceInput.isDecodable(2, 3)).isFalse()
    }
}
