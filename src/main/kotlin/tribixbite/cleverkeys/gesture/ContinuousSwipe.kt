package tribixbite.cleverkeys.gesture

/** Immutable letter segments, separate from the full gesture recognizer/trail. */
class ContinuousSwipe<K>(private val emit: (Segment<K>) -> Unit, private val overflow: () -> Unit) {
    data class Sample(val x: Float, val y: Float, val timestamp: Long)
    /**
     * One word of a phrase. [samples] are the raw (unsmoothed) letter samples both engines
     * have always received. [lift] is the finger-lift sample, present only on the FINAL
     * segment of a gesture whose finger lifted on letters; it is CTC-only, see [ctcSamples].
     */
    data class Segment<K>(
        val keys: List<K>,
        val samples: List<Sample>,
        val endedBySpace: Boolean,
        val lift: Sample? = null,
    ) {
        /** The CTC encoder's input: [samples] plus the [lift] sample when there is one. */
        val ctcSamples: List<Sample> get() = if (lift == null) samples else samples + lift
    }
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
    /** Whether the most recent sample was on the spacebar (a lift there adds no CTC sample). */
    private var lastSampleInSpace = false
    val hasBoundary: Boolean get() = emitted > 0

    /** Physical SPACE membership is supplied by the view; key slop is never used. */
    fun sample(x: Float, y: Float, timestamp: Long, monotonicTime: Long, key: K?, inSpace: Boolean, boundaryEligible: Boolean = inSpace) {
        if (stopped) return
        lastSampleInSpace = inSpace
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
    /**
     * Ends the gesture at finger lift, flushing the final segment of a phrase.
     * [lift] (ACTION_UP position and time) becomes that segment's [Segment.lift] when the
     * finger lifted on letters (not on the spacebar, whose position would read as a false
     * final key), the coordinates are finite and it is strictly later than the last sample.
     */
    fun finish(lift: Sample? = null) {
        if (!stopped && hasBoundary) {
            val last = samples.lastOrNull()
            val accepted = lift?.takeIf {
                !lastSampleInSpace && last != null && it.x.isFinite() && it.y.isFinite() &&
                    it.timestamp > last.timestamp
            }
            flush(false, accepted)
        }
        stopped = true
    }
    fun cancel() { stopped = true; samples.clear(); keys.clear() }
    private fun flush(endedBySpace: Boolean = true, lift: Sample? = null) {
        if (keys.isEmpty() || samples.isEmpty()) return
        if (emitted >= MAX_SEGMENTS) { stopped = true; overflow(); return }
        val segment = Segment(keys.toList(), samples.toList(), endedBySpace, lift)
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

    /** A segment is waiting or its decode/commit has not acknowledged yet. */
    val hasOutstandingWork: Boolean get() = !cancelled && (active || pending.isNotEmpty())
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

/**
 * Decides whether an editor selection callback belongs to a continuous phrase's own
 * commits. Positions only: the view separately re-reads the whole editor state before
 * every dispatch ([ContinuousSwipeQueue]'s `valid`) and verifies every write, so a callback
 * judged "own" here can never authorize a commit into a changed editor.
 *
 * Android reports a commit's carets asynchronously, usually after the synchronous commit
 * code (and [complete]) returned — and a multi-write commit (typed word's separator, then
 * "word ") reports an intermediate caret first. So the positions of the last accepted
 * commit stay owned until the next commit completes, not just while it is being written.
 */
class ContinuousSelectionGate {
    enum class Decision { IGNORE, CANCEL }
    companion object {
        const val MAX_RECORDED = 8
        /** Late own callbacks tolerated per accepted commit before treating them as foreign. */
        const val MAX_LATE = 8
    }
    private var committing = false
    private val recorded = ArrayList<Pair<Int, Int>>()
    private var owned: Set<Int> = emptySet()
    private var late = 0

    fun reset() { committing = false; recorded.clear(); owned = emptySet(); late = 0 }
    fun prepareCommit() { committing = true; recorded.clear() }

    /** Returns whether the commit, including callbacks recorded during it, is accepted. */
    fun complete(accepted: Boolean, oldStart: Int?, nowStart: Int?): Boolean {
        val current = setOfNotNull(oldStart, oldStart?.plus(1), nowStart, nowStart?.minus(1))
        // A callback recorded while this commit was written may still be the previous one's.
        val ok = accepted && nowStart != null &&
            recorded.all { (start, end) -> start == end && (start in current || start in owned) }
        committing = false; recorded.clear()
        owned = if (ok) current else emptySet()
        late = 0
        return ok
    }

    /** [expected] is the collapsed caret of the last verified phrase state. */
    fun onSelection(start: Int, end: Int, expected: Int): Decision {
        if (committing) {
            if (recorded.size >= MAX_RECORDED) return Decision.CANCEL
            recorded.add(start to end)
            return Decision.IGNORE
        }
        if (start != end || (start != expected && start !in owned)) return Decision.CANCEL
        return if (++late > MAX_LATE) Decision.CANCEL else Decision.IGNORE
    }
}
