package tribixbite.cleverkeys

import android.graphics.PointF
import tribixbite.cleverkeys.gesture.ContinuousSwipe
import kotlin.collections.List

/**
 * Result class for swipe data.
 *
 * [path] is the recognizer's SMOOTHED path (trailing moving average) and is what the
 * geometric engine and the ML capture consume; [rawTrace] is the unsmoothed trace the CTC
 * engine featurizes (see [RawSwipeTrace]). Both are immutable snapshots taken in
 * `endSwipe()` on the main thread, so the result can cross to a decode thread unchanged.
 */
data class SwipeResult(
    @JvmField val keys: List<KeyboardData.Key>?, // Can be null
    @JvmField val path: List<PointF>?, // Can be null
    @JvmField val timestamps: List<Long>?, // Can be null
    @JvmField val totalDistance: Float,
    @JvmField val isSwipeTyping: Boolean,
    /** CTC-only input; null when the gesture produced no swipe (same as [path]). */
    @JvmField val rawTrace: RawSwipeTrace? = null,
)

/**
 * The touch trace the CTC encoder featurizes (short-word investigation, 2026-10-07,
 * `docs/eval/2026-10-07-short-word-ctc.md` §5).
 *
 * The shipped encoder was trained on RAW touch samples. Feeding it the recognizer's smoothed
 * path lagged the endpoint behind the finger, and because no sample was recorded at lift a
 * final dwell on the last key — the evidence the encoder uses to read `ad` rather than an
 * overshooting `as` — vanished. This trace is therefore the recognizer's accepted samples
 * WITHOUT smoothing (the noise-threshold drop still applies: that is the configuration the
 * eval measured as "proposed") plus at most ONE sample at finger lift.
 *
 * Invariants (checked at construction): non-empty, [points] and [timestamps] are parallel.
 * Timestamps are non-decreasing because both producers (the recognizer and the
 * continuous-swipe segmenter) drop non-advancing samples. The lists are fresh copies owned
 * by this object, so the trace can cross to the decode thread unchanged.
 *
 * Memory: the trace copies the recognizer's existing raw-sample list, which always has
 * exactly as many entries as the smoothed path (one per accepted sample), plus at most one
 * sample. The recognizer caps neither list (gesture duration bounds them); continuous-swipe
 * segments stay under [ContinuousSwipe.MAX_POINTS] + 1.
 */
class RawSwipeTrace private constructor(
    @JvmField val points: List<PointF>,
    @JvmField val timestamps: List<Long>,
) {
    init {
        require(points.isNotEmpty()) { "a raw swipe trace needs at least one sample" }
        require(points.size == timestamps.size) {
            "points/timestamps must be parallel (${points.size}/${timestamps.size})"
        }
    }

    override fun toString(): String = "RawSwipeTrace(n=${points.size})"

    companion object {
        /**
         * Copies [points]/[timestamps] (accepted touch samples, oldest first) and appends
         * [lift] as ONE terminal sample when it is finite and strictly later than the last
         * sample — a lift in the same millisecond carries no extra time and would only be a
         * duplicate for the featurizer's time resample. Returns null for an empty or
         * non-parallel input.
         *
         * The lift keeps a final stop's DURATION, which the noise drop otherwise erases:
         * stationary samples are discarded, so without it the timeline ended at the last
         * moving sample. The eval measured the unclamped form, whose stops were short (the
         * `ad` case: 200 ms); the gap is now clamped to [ContinuousSwipe.MAX_LIFT_GAP_MS]
         * (500 ms) so a long final hold cannot fill most of the encoder's 64 time-resampled
         * columns with one stationary point (typing audit, 2026-10-08).
         */
        fun withLift(
            points: List<PointF>,
            timestamps: List<Long>,
            lift: ContinuousSwipe.Sample?,
        ): RawSwipeTrace? {
            if (points.isEmpty() || points.size != timestamps.size) return null
            val useLift = lift != null && lift.x.isFinite() && lift.y.isFinite() &&
                lift.timestamp > timestamps.last()
            val n = points.size + if (useLift) 1 else 0
            val outPoints = ArrayList<PointF>(n)
            points.mapTo(outPoints) { PointF(it.x, it.y) }
            val outTimes = ArrayList<Long>(n)
            outTimes.addAll(timestamps)
            if (useLift) {
                val clamped = ContinuousSwipe.clampLift(timestamps.last(), lift!!)
                outPoints.add(PointF(clamped.x, clamped.y))
                outTimes.add(clamped.timestamp)
            }
            return RawSwipeTrace(outPoints, outTimes)
        }
    }
}
