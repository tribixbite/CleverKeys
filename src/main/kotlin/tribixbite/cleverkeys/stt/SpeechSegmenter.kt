package tribixbite.cleverkeys.stt

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Endpointing settings for [SpeechSegmenter] (docs/specs/voice-input.md FR-10).
 *
 * The shape follows the official Moonshine runtime's `core/voice-activity-detector.cpp` (MIT):
 * 512-sample hops at 16 kHz, a 0.5 s look-behind put in front of every segment, and a maximum
 * segment length near which a boundary is forced. That runtime scores hops with the Silero VAD
 * model; the voice pack format does not carry one and the keyboard must stay engine-neutral, so
 * hops are scored here by energy against an adaptive noise floor instead.
 *
 * @property maxSegmentSeconds the engine's [SpeechEngine.maxSegmentSeconds].
 * @property marginDb a hop is voiced when it is this far above the noise floor.
 * @property minSpeechDb and never below this absolute level (dBFS RMS): a near-silent room
 *   (or digital silence) must not turn breath and hiss into speech.
 * @property initialNoiseDb the noise floor assumed before any audio was heard. Low on purpose:
 *   a user who starts talking at once must be heard at once. A louder room is learned within
 *   [noiseWindowSeconds].
 * @property noiseWindowSeconds the floor is the quietest hop of this window ("minimum
 *   statistics"). Speech always has quiet hops (closures, syllable edges) within a few seconds,
 *   so the floor follows the room, not the voice.
 * @property onsetVoicedHops / [onsetWindowHops] a segment opens when this many of the last
 *   hops were voiced (128 of 256 ms): a click or a knock does not open one.
 * @property hangoverSeconds a segment ends after this long without a voiced hop. Longer than
 *   the gaps between words and the breath after a comma, shorter than a pause between thoughts.
 * @property preRollSeconds audio before the onset decision put at the front of a segment, so a
 *   word's soft start is not cut (the official runtime's 8,192 samples).
 * @property softWindowSeconds near the bound, this much audio is held back so a forced
 *   boundary can be put at its quietest point instead of mid-word.
 */
data class SegmenterConfig(
    val sampleRate: Int = 16_000,
    val maxSegmentSeconds: Int = SpeechEngine.DEFAULT_MAX_SEGMENT_SECONDS,
    val marginDb: Double = 12.0,
    val minSpeechDb: Double = -50.0,
    val initialNoiseDb: Double = -60.0,
    val noiseWindowSeconds: Double = 3.0,
    val onsetVoicedHops: Int = 4,
    val onsetWindowHops: Int = 8,
    val hangoverSeconds: Double = 0.6,
    val preRollSeconds: Double = 0.512,
    val softWindowSeconds: Double = min(2.0, maxSegmentSeconds / 2.0),
) {
    init {
        require(sampleRate in 8_000..48_000) { "sample rate $sampleRate" }
        require(maxSegmentSeconds >= 1) { "max segment $maxSegmentSeconds s" }
        require(onsetVoicedHops in 1..onsetWindowHops) { "onset $onsetVoicedHops of $onsetWindowHops" }
        require(softWindowSeconds >= 0 && softWindowSeconds < maxSegmentSeconds) { "soft window $softWindowSeconds" }
    }

    /** 32 ms analysis hops (512 samples at 16 kHz, the official runtime's hop). */
    val hopSamples: Int get() = (sampleRate * HOP_SECONDS).roundToInt()

    companion object {
        const val HOP_SECONDS = 0.032

        /** The settings for [engine]: its sample rate and segment bound, defaults otherwise. */
        fun forEngine(engine: SpeechEngine): SegmenterConfig =
            SegmenterConfig(sampleRate = engine.sampleRate, maxSegmentSeconds = engine.maxSegmentSeconds)
    }
}

/** Why a segment ended. */
enum class SegmentEndReason {
    /** The speaker paused for [SegmenterConfig.hangoverSeconds]. */
    PAUSE,

    /** The segment reached the engine's bound without a pause; cut at its quietest point. */
    MAX_LENGTH,

    /** The listening period stopped ([SpeechSegmenter.flush]). */
    FLUSH,
}

/** Output of [SpeechSegmenter], in order. */
sealed interface SegmentEvent {
    /**
     * Audio belonging to the current segment. The first [Audio] after an [End] (or the first
     * ever) starts a new segment. Consecutive audio of one segment within one
     * [SpeechSegmenter.process] call arrives as ONE event, so an engine that decodes once per
     * call (a backlog) still decodes once.
     */
    class Audio(val pcm: FloatArray) : SegmentEvent

    /** The current segment is complete. */
    data class End(val reason: SegmentEndReason) : SegmentEvent
}

/**
 * Splits a microphone stream into speech segments at pauses (pure; `SpeechSegmenterTest`).
 *
 * The keyboard opens a fresh engine session per segment and commits each segment's final text,
 * so a listening period can run for minutes while every recognition pass covers one segment
 * only: cost per pass stays flat, and an engine whose decoder loses track of long utterances
 * (Moonshine v2 tiny past ~40 s) never sees one. Silence between segments is not passed on at
 * all — no decoding cost, nothing for a model to hallucinate on.
 *
 * Per 32 ms hop: RMS energy in dBFS; the noise floor is the quietest hop of the last
 * [SegmenterConfig.noiseWindowSeconds]; a hop is voiced above max(floor + margin, absolute gate).
 * A segment opens after [SegmenterConfig.onsetVoicedHops] voiced hops of the last
 * [SegmenterConfig.onsetWindowHops], starting with the pre-roll; it ends after
 * [SegmenterConfig.hangoverSeconds] without a voiced hop (those quiet hops are included: the
 * engine needs them to finish the last word). In the last [SegmenterConfig.softWindowSeconds]
 * before the engine's bound, hops are held back; a pause there ends the segment as usual, and
 * reaching the bound cuts it after the quietest held hop (energy averaged over three hops), the
 * rest starting the next segment at once. Nothing is lost or sent twice at a boundary.
 *
 * Not thread-safe: one listening period's capture thread owns it.
 */
class SpeechSegmenter(private val config: SegmenterConfig) {

    private val hop = config.hopSamples
    private val maxSamples = config.maxSegmentSeconds * config.sampleRate
    private val softStart = maxSamples - (config.softWindowSeconds * config.sampleRate).roundToInt()
    private val hangoverHops = ceil(config.hangoverSeconds / SegmenterConfig.HOP_SECONDS - 1e-9).toInt()

    /** Partial hop carried to the next [process] call. */
    private val partial = FloatArray(hop)
    private var partialLen = 0

    /** dBFS of the last hops (minimum statistics), primed with [SegmenterConfig.initialNoiseDb]. */
    private val noiseRing = DoubleArray(max(1, (config.noiseWindowSeconds / SegmenterConfig.HOP_SECONDS).roundToInt())) { config.initialNoiseDb }
    private var noiseIndex = 0

    /** Voiced flags of the last [SegmenterConfig.onsetWindowHops] hops. */
    private val onsetRing = BooleanArray(config.onsetWindowHops)
    private var onsetIndex = 0

    /** Recent audio while no segment is open: the pre-roll. */
    private val preRoll = FloatArray(max(hop, (config.preRollSeconds * config.sampleRate).roundToInt()))
    private var preRollLen = 0

    /** True while a segment is open. */
    var inSegment = false
        private set

    /** Samples of the open segment already handed out. */
    private var segmentSamples = 0

    /** Hops held back near the bound, with their energies. */
    private val held = ArrayList<FloatArray>()
    private val heldDb = ArrayList<Double>()
    private var heldSamples = 0

    private var hopsSinceVoiced = 0

    /** Hops since the last segment ended (or since the start) while no segment is open. */
    private var idleHops = 0

    /**
     * Seconds without speech: 0 while a segment is open, else the time since the last segment
     * ended (or since listening started). The keyboard's idle auto-stop reads it.
     */
    val secondsSinceSpeech: Double
        get() = if (inSegment) 0.0 else idleHops * hop.toDouble() / config.sampleRate

    /** Output under construction for one [process] call. */
    private var events = ArrayList<SegmentEvent>()
    private var pending = FloatArray(0)
    private var pendingLen = 0

    /** Feed audio; returns the segment events it completed, in order. */
    fun process(pcm: FloatArray): List<SegmentEvent> {
        var off = 0
        while (off < pcm.size) {
            val take = min(hop - partialLen, pcm.size - off)
            System.arraycopy(pcm, off, partial, partialLen, take)
            partialLen += take
            off += take
            if (partialLen == hop) {
                onHop(partial.copyOf())
                partialLen = 0
            }
        }
        return drain()
    }

    /**
     * The listening period is over: an open segment gets everything still held (the soft
     * window and the last partial hop) and ends with [SegmentEndReason.FLUSH]. Between segments
     * this returns nothing. The segmenter is ready for a new period afterwards.
     */
    fun flush(): List<SegmentEvent> {
        if (inSegment) {
            releaseHeld(held.size)
            if (partialLen > 0) emit(partial.copyOf(partialLen))
            endSegment(SegmentEndReason.FLUSH)
        }
        partialLen = 0
        preRollLen = 0
        return drain()
    }

    private fun onHop(frame: FloatArray) {
        val db = levelDb(frame)
        val floor = noiseRing.min()
        noiseRing[noiseIndex] = db
        noiseIndex = (noiseIndex + 1) % noiseRing.size
        val voiced = db > max(floor + config.marginDb, config.minSpeechDb)
        onsetRing[onsetIndex] = voiced
        onsetIndex = (onsetIndex + 1) % onsetRing.size
        hopsSinceVoiced = if (voiced) 0 else hopsSinceVoiced + 1

        if (!inSegment) {
            idleHops++
            pushPreRoll(frame)
            if (onsetRing.count { it } >= config.onsetVoicedHops) startSegment()
            return
        }

        if (segmentSamples + heldSamples + frame.size > softStart) {
            held += frame
            heldDb += db
            heldSamples += frame.size
        } else {
            emit(frame)
        }
        when {
            hopsSinceVoiced >= hangoverHops -> {
                releaseHeld(held.size)
                endSegment(SegmentEndReason.PAUSE)
            }
            // Cut before the NEXT hop would cross the bound: a bound that is not a whole number
            // of hops (10 s = 312.5) must never be exceeded (SegmentedRecognizerTest).
            segmentSamples + heldSamples + hop > maxSamples -> forceBoundary()
        }
    }

    private fun startSegment() {
        inSegment = true
        idleHops = 0
        segmentSamples = 0
        emit(preRoll.copyOf(preRollLen))
        preRollLen = 0
    }

    /** At the bound: cut after the quietest held hop; the hops after it open the next segment. */
    private fun forceBoundary() {
        var cut = held.size - 1
        var best = Double.MAX_VALUE
        for (i in held.indices) {
            val lo = max(0, i - 1)
            val hi = min(held.size - 1, i + 1)
            var sum = 0.0
            for (j in lo..hi) sum += heldDb[j]
            val smoothed = sum / (hi - lo + 1)
            if (smoothed <= best) { // ties go to the later hop: more of the segment stays live
                best = smoothed
                cut = i
            }
        }
        releaseHeld(cut + 1)
        endSegment(SegmentEndReason.MAX_LENGTH)
        // Speech continues: the remaining held hops start the next segment immediately.
        inSegment = true
        idleHops = 0
        segmentSamples = 0
        releaseHeld(held.size)
    }

    /** Emit the first [count] held hops as segment audio. */
    private fun releaseHeld(count: Int) {
        repeat(count) {
            val f = held.removeAt(0)
            heldDb.removeAt(0)
            heldSamples -= f.size
            emit(f)
        }
    }

    private fun endSegment(reason: SegmentEndReason) {
        closePending()
        events += SegmentEvent.End(reason)
        inSegment = false
        segmentSamples = 0
        idleHops = 0
    }

    private fun emit(samples: FloatArray) {
        if (samples.isEmpty()) return
        if (pendingLen + samples.size > pending.size) pending = pending.copyOf(max(pending.size * 2, pendingLen + samples.size))
        System.arraycopy(samples, 0, pending, pendingLen, samples.size)
        pendingLen += samples.size
        segmentSamples += samples.size
    }

    private fun closePending() {
        if (pendingLen > 0) events += SegmentEvent.Audio(pending.copyOf(pendingLen))
        pendingLen = 0
    }

    private fun drain(): List<SegmentEvent> {
        closePending()
        val out = events
        events = ArrayList()
        return out
    }

    private fun pushPreRoll(frame: FloatArray) {
        val overflow = preRollLen + frame.size - preRoll.size
        if (overflow > 0) {
            System.arraycopy(preRoll, overflow, preRoll, 0, preRollLen - overflow)
            preRollLen -= overflow
        }
        System.arraycopy(frame, 0, preRoll, preRollLen, frame.size)
        preRollLen += frame.size
    }

    companion object {
        /** Level of digital silence (log of zero), well below any gate. */
        const val SILENCE_DB = -120.0

        /** RMS level of [frame] in dBFS (full-scale sine ≈ -3 dB). */
        fun levelDb(frame: FloatArray): Double {
            if (frame.isEmpty()) return SILENCE_DB
            var sum = 0.0
            for (x in frame) sum += x.toDouble() * x
            val ms = sum / frame.size
            return if (ms <= 0.0) SILENCE_DB else max(SILENCE_DB, 10 * log10(ms))
        }
    }
}
