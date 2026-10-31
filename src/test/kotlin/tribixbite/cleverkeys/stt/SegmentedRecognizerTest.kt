package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [SegmentedRecognizer]: one engine session per speech segment, finals in order, a fresh
 * session (reset state) for every segment, nothing decoded during silence.
 */
class SegmentedRecognizerTest {

    private val rate = 16_000

    /** Records what each session received; its text names the session and its sample count. */
    private class FakeEngine(override val maxSegmentSeconds: Int = 30) : SpeechEngine {
        override val sampleRate = 16_000
        val sessions = ArrayList<FakeSession>()
        override fun newSession(): SpeechSession {
            check(sessions.none { it.open }) { "a new session while another is still open" }
            return FakeSession(sessions.size + 1).also { sessions += it }
        }
        override fun memoryReport() = SpeechMemoryReport(0, 0, "fake")
        override fun close() = Unit
    }

    private class FakeSession(val id: Int) : SpeechSession {
        var samples = 0
        var accepts = 0
        var open = true
        var finished = false
        @Volatile
        private var cancelled = false
        override val isCancelled get() = cancelled

        override fun accept(pcm: FloatArray): SpeechHypothesis? {
            check(open && !finished)
            accepts++
            samples += pcm.size
            return SpeechHypothesis("segment$id partial", 1, isFinal = false)
        }

        override fun finish(): SpeechHypothesis {
            check(open && !finished)
            finished = true
            return SpeechHypothesis("segment$id final", 2, isFinal = true)
        }

        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            open = false
        }
    }

    private fun speech(seconds: Double): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i ->
            val gate = if (i % (rate / 4) >= rate / 4 - 960) 0.01f else 1f
            gate * 0.14f * sin(2 * PI * 220 * i / rate).toFloat()
        }
    }

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())

    private fun feed(r: SegmentedRecognizer, pcm: FloatArray, piece: Int = 5_120): List<SegmentedRecognizer.Update> {
        val out = ArrayList<SegmentedRecognizer.Update>()
        var off = 0
        while (off < pcm.size) {
            val n = minOf(piece, pcm.size - off)
            out += r.accept(pcm.copyOfRange(off, off + n))
            off += n
        }
        return out
    }

    @Test
    fun twoSegmentsFinaliseInOrderEachInAFreshSession() {
        val engine = FakeEngine()
        val r = SegmentedRecognizer(engine)
        val updates = feed(r, silence(0.5) + speech(2.0) + silence(1.2) + speech(2.0) + silence(1.2))
        val finals = updates.filterIsInstance<SegmentedRecognizer.Update.SegmentFinal>()
        assertThat(finals.map { it.hypothesis.text }).containsExactly("segment1 final", "segment2 final").inOrder()
        assertThat(finals.map { it.reason }).containsExactly(SegmentEndReason.PAUSE, SegmentEndReason.PAUSE)
        // Each segment's partials come before its final, from its own session.
        val firstFinal = updates.indexOf(finals[0])
        assertThat(updates.subList(0, firstFinal).map { it.hypothesis.text }.toSet()).containsExactly("segment1 partial")
        assertThat(updates.drop(firstFinal + 1).filterIsInstance<SegmentedRecognizer.Update.Partial>().map { it.hypothesis.text }.toSet())
            .containsExactly("segment2 partial")
        assertThat(engine.sessions).hasSize(2)
        assertThat(engine.sessions.all { !it.open && it.finished }).isTrue()
        assertThat(r.segmentsFinished).isEqualTo(2)
        // Silence is never decoded: each session got its segment only (pre-roll + speech + hangover).
        for (s in engine.sessions) assertThat(s.samples.toDouble() / rate).isWithin(0.15).of(2.0 + 0.512 - 0.128 + 0.608)
        // Nothing open now: stop yields an empty final and opens no session.
        val final = r.finish()
        assertThat(final.isFinal).isTrue()
        assertThat(final.text).isEmpty()
        assertThat(engine.sessions).hasSize(2)
    }

    @Test
    fun stopMidSegmentReturnsThatSegmentsFinal() {
        val engine = FakeEngine()
        val r = SegmentedRecognizer(engine)
        feed(r, silence(0.3) + speech(1.0))
        assertThat(r.inSegment).isTrue()
        val final = r.finish()
        assertThat(final.text).isEqualTo("segment1 final")
        assertThat(final.isFinal).isTrue()
        assertThat(engine.sessions.single().open).isFalse()
    }

    @Test
    fun aBacklogIsOneAcceptPerSegment() {
        val engine = FakeEngine()
        val r = SegmentedRecognizer(engine)
        // Six seconds in one read (a recogniser that fell behind): one accept for the open
        // segment, so the engine decodes the backlog once.
        r.accept(speech(6.0))
        assertThat(engine.sessions.single().accepts).isEqualTo(1)
    }

    @Test
    fun theEngineBoundForcesANewSessionWithoutAPause() {
        val engine = FakeEngine(maxSegmentSeconds = 10)
        val r = SegmentedRecognizer(engine)
        val updates = feed(r, speech(25.0))
        val finals = updates.filterIsInstance<SegmentedRecognizer.Update.SegmentFinal>()
        assertThat(finals.map { it.reason }).containsExactly(SegmentEndReason.MAX_LENGTH, SegmentEndReason.MAX_LENGTH)
        assertThat(engine.sessions).hasSize(3)
        for (s in engine.sessions) assertThat(s.samples).isAtMost(10 * rate)
        r.finish()
        assertThat(engine.sessions.sumOf { it.samples }).isEqualTo(speech(25.0).size)
    }

    @Test
    fun cancelAbandonsTheOpenSessionAndIgnoresLaterAudio() {
        val engine = FakeEngine()
        val r = SegmentedRecognizer(engine)
        feed(r, speech(1.5))
        r.cancel()
        assertThat(r.isCancelled).isTrue()
        assertThat(engine.sessions.single().isCancelled).isTrue()
        assertThat(r.accept(silence(2.0) + speech(2.0) + silence(1.0))).isEmpty()
        assertThat(engine.sessions).hasSize(1)
        assertThat(r.finish().text).isEmpty()
        r.close()
        assertThat(engine.sessions.single().open).isFalse()
    }

    @Test
    fun secondsSinceSpeechComesFromTheSegmenter() {
        val r = SegmentedRecognizer(FakeEngine())
        feed(r, silence(4.0))
        assertThat(r.secondsSinceSpeech).isWithin(0.05).of(4.0)
        feed(r, speech(1.0))
        assertThat(r.secondsSinceSpeech).isEqualTo(0.0)
    }
}
