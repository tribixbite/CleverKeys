package tribixbite.cleverkeys.swipe

/**
 * The degenerate-trace rule both swipe adapters apply before decoding (review 2026-11-14).
 *
 * The gesture layer hands over a path and a parallel timestamp list (the recognizer appends
 * to both together, and `RawSwipeTrace` enforces it for the CTC trace). Both adapters used to
 * snapshot the trace with `timestamps.getOrElse(i) { 0L }`, so a mismatched pair decoded with
 * its tail stamped at t = 0 — a velocity profile the user never drew, which the CTC
 * featurizer reads directly. A mismatch means the trace is inconsistent; it is answered like
 * an empty path, as no swipe.
 *
 * Pure (no Android) so `runPureTests` pins it (`SwipeTraceInputTest`).
 */
object SwipeTraceInput {
    /** True when the trace has points and exactly one timestamp per point. */
    fun isDecodable(pointCount: Int, timestampCount: Int): Boolean =
        pointCount > 0 && pointCount == timestampCount
}
