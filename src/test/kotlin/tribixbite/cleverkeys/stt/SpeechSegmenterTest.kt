package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [SpeechSegmenter] on synthetic 16 kHz audio: syllable-modulated tones stand in for speech,
 * digital silence and Gaussian noise for pauses and rooms. Levels are RMS dBFS, the unit the
 * segmenter works in.
 *
 * Real speech is never stationary: its energy drops 20–40 dB several times a second (stop
 * closures, syllable edges). [speech] models that with a 4 Hz syllable rate and a 60 ms dip per
 * syllable; the noise-floor tracker relies on those dips, as it would on a real voice.
 */
class SpeechSegmenterTest {

    private val rate = 16_000

    // ---- signal helpers -------------------------------------------------------------------

    /** A sine at [db] dBFS RMS. */
    private fun tone(seconds: Double, db: Double = -20.0, hz: Double = 220.0): FloatArray {
        val amp = 10.0.pow(db / 20) * sqrt(2.0)
        return FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
    }

    /** "Speech": a tone at [db] whose last 60 ms of every 250 ms syllable is 40 dB quieter. */
    private fun speech(seconds: Double, db: Double = -20.0): FloatArray {
        val t = tone(seconds, db)
        val syllable = rate / 4
        val dip = rate * 60 / 1000
        for (i in t.indices) if (i % syllable >= syllable - dip) t[i] *= 0.01f
        return t
    }

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())

    /** Deterministic Gaussian noise at [db] dBFS RMS. */
    private fun noise(seconds: Double, db: Double, seed: Long = 7): FloatArray {
        val r = java.util.Random(seed)
        val sd = 10.0.pow(db / 20)
        return FloatArray((seconds * rate).toInt()) { (r.nextGaussian() * sd).toFloat() }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    private fun segmenter(maxSegmentSeconds: Int = 30) =
        SpeechSegmenter(SegmenterConfig(sampleRate = rate, maxSegmentSeconds = maxSegmentSeconds))

    /** One closed or open segment as the events describe it. */
    private data class Seg(val samples: Int, val end: SegmentEndReason?)

    /** Feed [pcm] in [piece]-sample calls (then flush when [flush]); fold the events into segments. */
    private fun run(s: SpeechSegmenter, pcm: FloatArray, piece: Int = 5_120, flush: Boolean = false): List<Seg> {
        val events = ArrayList<SegmentEvent>()
        var off = 0
        while (off < pcm.size) {
            val n = minOf(piece, pcm.size - off)
            events += s.process(pcm.copyOfRange(off, off + n))
            off += n
        }
        if (flush) events += s.flush()
        return fold(events)
    }

    private fun fold(events: List<SegmentEvent>): List<Seg> {
        val segs = ArrayList<Seg>()
        var open = -1
        for (e in events) when (e) {
            is SegmentEvent.Audio -> open = (if (open < 0) 0 else open) + e.pcm.size
            is SegmentEvent.End -> {
                assertWithMessage("an End always closes an open segment").that(open).isAtLeast(0)
                segs += Seg(open, e.reason)
                open = -1
            }
        }
        if (open >= 0) segs += Seg(open, null)
        return segs
    }

    private fun secs(samples: Int) = samples.toDouble() / rate

    // ---- tests ----------------------------------------------------------------------------

    @Test
    fun silenceAndQuietNoiseNeverOpenASegment() {
        assertThat(run(segmenter(), silence(10.0), flush = true)).isEmpty()
        val s = segmenter()
        assertThat(run(s, noise(10.0, db = -62.0), flush = false)).isEmpty()
        assertWithMessage("silence counts toward the idle stop").that(s.secondsSinceSpeech).isWithin(0.05).of(10.0)
        assertThat(s.inSegment).isFalse()
    }

    @Test
    fun aBurstBetweenPausesIsOneSegmentWithPreRollAndHangover() {
        val s = segmenter()
        val segs = run(s, concat(silence(1.0), speech(2.0), silence(1.5)))
        assertThat(segs).hasSize(1)
        assertThat(segs[0].end).isEqualTo(SegmentEndReason.PAUSE)
        // Pre-roll (0.5 s, the official runtime's 8192-sample look-behind) reaching back from the
        // onset decision (4 frames = 128 ms into the speech) + the rest of the speech + the
        // hangover (0.6 s of silence the engine needs to finish the last word).
        assertThat(secs(segs[0].samples)).isWithin(0.1).of(ONSET_TO_END + 2.0)
        assertThat(s.inSegment).isFalse()
        assertThat(s.secondsSinceSpeech).isWithin(0.1).of(1.5 - 0.6)
    }

    @Test
    fun speechAtTheVeryStartLosesNothing() {
        // No silence before the first word: the pre-roll covers the onset delay, so every
        // sample of the burst reaches the engine.
        val segs = run(segmenter(), concat(speech(1.5), silence(1.0)))
        assertThat(segs).hasSize(1)
        assertThat(segs[0].samples).isAtLeast(speech(1.5).size)
    }

    @Test
    fun aPauseLongerThanTheHangoverSplitsAndAShortOneDoesNot() {
        val split = run(segmenter(), concat(silence(0.5), speech(1.5), silence(1.0), speech(1.5), silence(1.0)))
        assertThat(split.map { it.end }).containsExactly(SegmentEndReason.PAUSE, SegmentEndReason.PAUSE)
        // A breath between words (0.3 s) is not a pause.
        val joined = run(segmenter(), concat(silence(0.5), speech(1.5), silence(0.3), speech(1.5), silence(1.0)))
        assertThat(joined).hasSize(1)
    }

    @Test
    fun aClickIsNotSpeech() {
        val click = tone(0.04, db = -6.0, hz = 2_000.0)
        assertThat(run(segmenter(), concat(silence(1.0), click, silence(1.0), click, silence(1.0)), flush = true)).isEmpty()
    }

    @Test
    fun aSteadyNoiseFloorIsLearnedAndSpeechAboveItIsFound() {
        // A loud room (-40 dBFS, above the -50 dBFS absolute gate). Within the noise window the
        // floor is learned; from then on the noise opens nothing and speech 20 dB above it is found.
        val s = segmenter()
        run(s, noise(4.0, db = -40.0, seed = 1), flush = true) // learning; may open one segment
        val later = run(s, noise(6.0, db = -40.0, seed = 2))
        assertWithMessage("noise after the floor was learned").that(later).isEmpty()
        val speech = concat(noise(0.5, -40.0, 3), mix(speech(2.0, db = -20.0), noise(2.0, -40.0, 4)), noise(1.5, -40.0, 5))
        val segs = run(s, speech)
        assertThat(segs).hasSize(1)
        assertThat(secs(segs[0].samples)).isWithin(0.1).of(ONSET_TO_END + 2.0)
    }

    @Test
    fun continuousSpeechIsCutAtTheEngineBound() {
        // 50 s without a pause and a 20 s bound: segments end at the bound by force, every sample
        // reaches exactly one segment (nothing lost or doubled at a forced boundary).
        val pcm = concat(speech(50.0), silence(1.0))
        val segs = run(segmenter(maxSegmentSeconds = 20), pcm)
        assertThat(segs.map { it.end }).containsExactly(
            SegmentEndReason.MAX_LENGTH, SegmentEndReason.MAX_LENGTH, SegmentEndReason.PAUSE,
        ).inOrder()
        for (seg in segs) assertThat(secs(seg.samples)).isAtMost(20.0)
        // The first segment has no pre-roll (speech started at sample 0); later ones continue
        // the speech exactly where the previous one stopped; the last ends with the hangover.
        assertThat(secs(segs.sumOf { it.samples })).isWithin(0.1).of(50.0 + 0.6)
    }

    @Test
    fun aForcedBoundaryLandsOnTheQuietestPointOfTheLastTwoSeconds() {
        // Continuous speech with a short dip (0.2 s, shorter than the hangover: not a pause)
        // 1.2 s before the bound. The cut must fall inside the dip, not mid-word at the bound.
        val bound = 20
        val dipAt = bound - 1.2
        val pcm = concat(speech(dipAt), silence(0.2), speech(30.0 - dipAt - 0.2))
        val segs = run(segmenter(maxSegmentSeconds = bound), pcm)
        assertThat(segs.first().end).isEqualTo(SegmentEndReason.MAX_LENGTH)
        assertThat(secs(segs.first().samples)).isIn(com.google.common.collect.Range.closed(dipAt, dipAt + 0.2))
        // A dip earlier than the last two seconds is ignored (cut by the bound as usual).
        val early = concat(speech(bound - 6.0), silence(0.2), speech(30.0 - bound + 5.8))
        val segsEarly = run(segmenter(maxSegmentSeconds = bound), early)
        assertThat(secs(segsEarly.first().samples)).isAtLeast(bound - 2.0)
    }

    @Test
    fun flushClosesAnOpenSegmentWithEverythingItHeld() {
        val s = segmenter(maxSegmentSeconds = 20)
        // 19 s of speech: inside the last two seconds before the bound, audio is held back to
        // choose a cut point. Stop must still deliver all of it.
        val segs = run(s, speech(19.0), flush = true)
        assertThat(segs).hasSize(1)
        assertThat(segs[0].end).isEqualTo(SegmentEndReason.FLUSH)
        assertThat(segs[0].samples).isEqualTo(speech(19.0).size)
        assertThat(s.flush()).isEmpty()
        // Flushing between segments yields nothing.
        assertThat(segmenter().flush()).isEmpty()
    }

    @Test
    fun theResultDoesNotDependOnHowTheAudioIsChunked() {
        val pcm = concat(silence(0.7), speech(3.0), silence(0.9), speech(2.0, db = -30.0), silence(0.2), speech(1.0), silence(2.0))
        val reference = run(segmenter(), pcm, piece = pcm.size, flush = true)
        assertThat(reference).hasSize(2)
        for (piece in listOf(1, 511, 512, 513, 1_600, 5_120, 16_000)) {
            assertWithMessage("piece $piece").that(run(segmenter(), pcm, piece = piece, flush = true)).isEqualTo(reference)
        }
    }

    @Test
    fun secondsSinceSpeechResetsWhileSpeaking() {
        val s = segmenter()
        run(s, silence(3.0))
        assertThat(s.secondsSinceSpeech).isWithin(0.05).of(3.0)
        run(s, speech(1.0))
        assertThat(s.inSegment).isTrue()
        assertThat(s.secondsSinceSpeech).isEqualTo(0.0)
        run(s, silence(2.0))
        assertThat(s.inSegment).isFalse()
        assertThat(s.secondsSinceSpeech).isWithin(0.1).of(2.0 - 0.6)
    }

    @Test
    fun theConfigFollowsTheEngine() {
        val engine = object : SpeechEngine {
            override val sampleRate = 16_000
            override val maxSegmentSeconds = 12
            override fun newSession(): SpeechSession = error("unused")
            override fun memoryReport() = SpeechMemoryReport(0, 0, "")
            override fun close() = Unit
        }
        val c = SegmenterConfig.forEngine(engine)
        assertThat(c.sampleRate).isEqualTo(16_000)
        assertThat(c.maxSegmentSeconds).isEqualTo(12)
        // A bound shorter than the look-back window still leaves room for speech.
        assertThat(SegmenterConfig(maxSegmentSeconds = 1).softWindowSeconds).isLessThan(1.0)
    }

    private fun mix(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + b[it] }

    private companion object {
        /** A segment's length beyond its speech: pre-roll minus the onset delay, plus the hangover. */
        const val ONSET_TO_END = 0.512 - 4 * 0.032 + 19 * 0.032
    }
}
