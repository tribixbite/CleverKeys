package tribixbite.cleverkeys.gesture

/** Immutable letter segments, separate from the full gesture recognizer/trail. */
class ContinuousSwipe<K>(private val emit: (Segment<K>) -> Unit, private val overflow: () -> Unit) {
    data class Sample(val x: Float, val y: Float, val timestamp: Long)
    data class Segment<K>(val keys: List<K>, val samples: List<Sample>, val endedBySpace: Boolean)
    companion object {
        const val DWELL_MS = 280L
        const val MAX_POINTS = 2048
        const val MAX_SEGMENTS = 32
    }
    private val samples = mutableListOf<Sample>()
    private val keys = mutableListOf<K>()
    private var enteredSpaceAt: Long? = null
    private var boundaryInThisVisit = false
    private var emitted = 0
    private var stopped = false
    val hasBoundary: Boolean get() = emitted > 0

    /** Physical SPACE membership is supplied by the view; key slop is never used. */
    fun sample(x: Float, y: Float, timestamp: Long, monotonicTime: Long, key: K?, inSpace: Boolean, boundaryEligible: Boolean = inSpace) {
        if (stopped) return
        if (inSpace) {
            if (boundaryEligible) {
                if (enteredSpaceAt == null) enteredSpaceAt = monotonicTime
                dwell(monotonicTime)
            } else enteredSpaceAt = null
            return
        }
        enteredSpaceAt = null; boundaryInThisVisit = false
        // A new segment starts at its first letter, excluding the spacebar excursion.
        if (samples.isEmpty() && key == null) return
        if (!x.isFinite() || !y.isFinite()) return
        if (samples.size >= MAX_POINTS) { stopped = true; overflow(); return }
        if (samples.lastOrNull()?.timestamp?.let { timestamp <= it } == true) return
        samples.add(Sample(x, y, timestamp))
        if (key != null && keys.lastOrNull() != key) keys.add(key)
    }

    /** Called by a timer as well as MOVE events, so stationary holds work. */
    fun dwell(monotonicTime: Long) {
        val entered = enteredSpaceAt ?: return
        if (!stopped && !boundaryInThisVisit && keys.isNotEmpty() && monotonicTime - entered >= DWELL_MS) {
            boundaryInThisVisit = true
            flush()
        }
    }
    fun finish() { if (!stopped && hasBoundary) flush(false); stopped = true }
    fun cancel() { stopped = true; samples.clear(); keys.clear() }
    private fun flush(endedBySpace: Boolean = true) {
        if (keys.isEmpty() || samples.isEmpty()) return
        if (emitted >= MAX_SEGMENTS) { stopped = true; overflow(); return }
        val segment = Segment(keys.toList(), samples.toList(), endedBySpace)
        samples.clear(); keys.clear(); emitted++
        emit(segment)
    }
}

/** One in-flight decode; callbacks from a cancelled or replaced phrase cannot commit. */
class ContinuousSwipeQueue<S>(
    private val valid: () -> Boolean,
    private val dispatch: (S, () -> Boolean, (Boolean) -> Unit) -> Unit,
    private val aborted: () -> Unit,
) {
    private val pending = ArrayDeque<S>()
    private var active = false
    private var generation = 0L
    private var cancelled = false
    fun enqueue(segment: S) {
        if (cancelled) return
        if (pending.size >= ContinuousSwipe.MAX_SEGMENTS) { cancel(); aborted(); return }
        pending.addLast(segment); pump()
    }
    fun cancel() { generation++; cancelled = true; pending.clear(); active = false }
    private fun pump() {
        if (active || cancelled || pending.isEmpty()) return
        if (!valid()) { cancel(); aborted(); return }
        val segment = pending.removeFirst(); val ticket = generation
        active = true
        var completed = false
        try {
            dispatch(segment, { !cancelled && ticket == generation && valid() }) { success ->
                if (completed || cancelled || ticket != generation) return@dispatch
                completed = true; active = false
                if (!success) { cancel(); aborted() } else pump()
            }
        } catch (_: RuntimeException) {
            cancel(); aborted()
        }
    }
}
