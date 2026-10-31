package tribixbite.cleverkeys.stt

import java.io.Closeable

/**
 * A listening period of any length over an engine that handles one segment at a time
 * (pure; `SegmentedRecognizerTest`, and the real model in `MoonshineV2StreamingEngineTest`).
 *
 * The [SpeechSegmenter] splits the microphone stream at pauses (and at the engine's
 * [SpeechEngine.maxSegmentSeconds] when there is none); each segment gets a NEW
 * [SpeechSession], so every engine state — decoder memory, carried frontend state, the
 * stabilizer's committed words — starts fresh, and the segment's final hypothesis is reported
 * as [Update.SegmentFinal] for the keyboard to commit. Silence between segments reaches no
 * session. Engine-neutral: nothing here knows which engine runs.
 *
 * Threading as for [SpeechSession]: one capture thread calls [accept]/[finish]/[close];
 * [cancel] may come from any thread.
 */
class SegmentedRecognizer(
    private val engine: SpeechEngine,
    private val segmenter: SpeechSegmenter = SpeechSegmenter(SegmenterConfig.forEngine(engine)),
) : Closeable {

    /** What one [accept] produced, in order. */
    sealed interface Update {
        val hypothesis: SpeechHypothesis

        /** The open segment's current hypothesis (not final). */
        data class Partial(override val hypothesis: SpeechHypothesis) : Update

        /**
         * A segment ended: its final hypothesis (every word stable); the next starts fresh.
         * [samples] is the audio the segment's session received.
         */
        data class SegmentFinal(override val hypothesis: SpeechHypothesis, val reason: SegmentEndReason, val samples: Int) : Update
    }

    @Volatile
    private var current: SpeechSession? = null

    @Volatile
    private var cancelled = false

    /** True once [cancel] ran. */
    val isCancelled: Boolean get() = cancelled

    /** Segments completed so far (reported as [Update.SegmentFinal]). */
    var segmentsFinished = 0
        private set

    /** Audio the open segment's session received so far. */
    private var segmentSamples = 0

    /** Audio of the last segment that ended (including the one [finish] closed). */
    var lastSegmentSamples = 0
        private set

    /** True while a segment (and so a session) is open. */
    val inSegment: Boolean get() = segmenter.inSegment

    /** Seconds since speech was last heard ([SpeechSegmenter.secondsSinceSpeech]). */
    val secondsSinceSpeech: Double get() = segmenter.secondsSinceSpeech

    /**
     * Feed microphone audio. Segment audio goes to the open session in one call per segment
     * (a backlog is decoded once); a segment end finishes its session.
     *
     * @throws SpeechEngineException on inference failure.
     */
    fun accept(pcm: FloatArray): List<Update> {
        if (cancelled) return emptyList()
        val out = ArrayList<Update>(2)
        for (event in segmenter.process(pcm)) {
            if (cancelled) return emptyList()
            when (event) {
                is SegmentEvent.Audio -> feed(event.pcm)?.let { out += Update.Partial(it) }
                is SegmentEvent.End -> finishSegment()?.let { out += Update.SegmentFinal(it, event.reason, lastSegmentSamples) }
            }
        }
        return if (cancelled) emptyList() else out
    }

    /**
     * No more audio: the open segment (if any) receives what the segmenter still held and its
     * final hypothesis is returned. Between segments the result is an empty final.
     */
    fun finish(): SpeechHypothesis {
        var final: SpeechHypothesis? = null
        if (!cancelled) {
            for (event in segmenter.flush()) {
                if (cancelled) break
                when (event) {
                    is SegmentEvent.Audio -> feed(event.pcm) // partials of a stopping period are not shown
                    is SegmentEvent.End -> final = finishSegment()
                }
            }
        }
        return if (cancelled) EMPTY_FINAL else final ?: EMPTY_FINAL
    }

    /** Abandon the period. Safe from any thread. */
    fun cancel() {
        cancelled = true
        current?.cancel()
    }

    override fun close() {
        current?.close()
        current = null
    }

    private fun feed(pcm: FloatArray): SpeechHypothesis? {
        val s = current ?: engine.newSession().also {
            current = it
            segmentSamples = 0
        }
        segmentSamples += pcm.size
        return s.accept(pcm)
    }

    private fun finishSegment(): SpeechHypothesis? {
        val s = current ?: return null
        lastSegmentSamples = segmentSamples
        segmentSamples = 0
        val h = try {
            s.finish()
        } finally {
            s.close()
            current = null
        }
        if (cancelled) return null
        segmentsFinished++
        return h
    }

    companion object {
        private val EMPTY_FINAL = SpeechHypothesis("", 0, isFinal = true)
    }
}
