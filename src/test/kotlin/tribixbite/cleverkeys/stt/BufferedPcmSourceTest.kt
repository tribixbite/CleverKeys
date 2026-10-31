package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BufferedPcmSource] decouples reading the microphone from recognising it
 * (docs/specs/voice-input.md NFR-1, device test 2026-10-29).
 *
 * The bug it fixes: the capture thread ran each recognition pass between two `AudioRecord`
 * reads. Every pass re-decodes the utterance, so after ~35 s one pass took longer than the
 * recorder's 4 s ring; AudioFlinger logged "RecordThread: buffer overflow" five times on the
 * Saga and whole sentences were missing from the final text.
 *
 * [RingMic] models that recorder: a producer writes [CHUNK] samples every tick into a ring of
 * [RING] samples and counts every sample it overwrites before anyone read it.
 */
class BufferedPcmSourceTest {

    /** A device recorder: fixed-size ring, real-time producer, overruns counted. */
    private class RingMic(private val chunks: Int) : PcmSource {
        val overwritten = AtomicInteger(0)
        val produced = AtomicInteger(0)
        private val lock = Object()
        private val ring = FloatArray(RING)
        private var head = 0L // samples written
        private var tail = 0L // samples read
        private var producer: Thread? = null
        @Volatile private var closed = false

        override fun start() {
            producer = Thread {
                var value = 0
                repeat(chunks) {
                    if (closed) return@Thread
                    synchronized(lock) {
                        for (i in 0 until CHUNK) {
                            if (head - tail >= RING) {
                                tail++ // the oldest unread sample is lost
                                overwritten.incrementAndGet()
                            }
                            ring[(head % RING).toInt()] = (value++ % 1000).toFloat()
                            head++
                        }
                        produced.addAndGet(CHUNK)
                        lock.notifyAll()
                    }
                    Thread.sleep(TICK_MILLIS)
                }
                synchronized(lock) {
                    closed = true
                    lock.notifyAll()
                }
            }.apply { start() }
        }

        override fun read(buffer: FloatArray): Int = synchronized(lock) {
            while (head == tail && !closed) lock.wait()
            if (head == tail) return 0
            val n = minOf(buffer.size.toLong(), head - tail).toInt()
            for (i in 0 until n) buffer[i] = ring[((tail + i) % RING).toInt()]
            tail += n
            n
        }

        override fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
            producer?.join(2_000)
        }
    }

    /** Read [source] to its end with a consumer that is slower than real time. */
    private fun drainSlowly(source: PcmSource): FloatArray {
        val out = ArrayList<Float>()
        val buf = FloatArray(CHUNK)
        source.start()
        try {
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                for (i in 0 until n) out += buf[i]
                Thread.sleep(SLOW_CONSUMER_MILLIS) // a recognition pass
            }
        } finally {
            source.close()
        }
        return out.toFloatArray()
    }

    @Test(timeout = 20_000)
    fun aSlowRecogniserLosesNoAudio() {
        val mic = RingMic(chunks = 40)
        val got = drainSlowly(BufferedPcmSource(mic, maxSamples = 1_000_000))
        assertThat(mic.overwritten.get()).isEqualTo(0)
        assertThat(got.size).isEqualTo(mic.produced.get())
        // Order is kept: the producer wrote 0,1,2,…(mod 1000).
        for (i in got.indices) assertThat(got[i]).isEqualTo((i % 1000).toFloat())
    }

    @Test(timeout = 20_000)
    fun theUnbufferedRecorderOverrunsWithTheSameConsumer() {
        // The control: the same consumer reading the recorder directly loses audio. If this
        // ever stops failing to keep up, the test above proves nothing.
        val mic = RingMic(chunks = 40)
        drainSlowly(mic)
        assertThat(mic.overwritten.get()).isGreaterThan(0)
    }

    @Test(timeout = 20_000)
    fun readReturnsEverythingQueuedUpToTheBufferSize() {
        val mic = RingMic(chunks = 10)
        val source = BufferedPcmSource(mic, maxSamples = 1_000_000)
        source.start()
        try {
            Thread.sleep(TICK_MILLIS * 14L) // let the whole clip queue up
            val big = FloatArray(CHUNK * 20)
            val n = source.read(big)
            // One call drains the backlog, so the recogniser can decode it in one pass.
            assertThat(n).isEqualTo(CHUNK * 10)
            assertThat(source.read(big)).isEqualTo(0)
        } finally {
            source.close()
        }
    }

    @Test(timeout = 20_000)
    fun aDeviceErrorIsReportedAfterTheQueuedAudio() {
        val failing = object : PcmSource {
            var calls = 0
            override fun start() = Unit
            override fun read(buffer: FloatArray): Int {
                calls++
                if (calls > 2) return -1
                buffer.fill(0.5f, 0, 100)
                return 100
            }
            override fun close() = Unit
        }
        val source = BufferedPcmSource(failing, maxSamples = 10_000)
        source.start()
        val buf = FloatArray(1_000)
        var total = 0
        var last: Int
        while (true) {
            last = source.read(buf)
            if (last <= 0) break
            total += last
        }
        source.close()
        assertThat(total).isEqualTo(200)
        assertThat(last).isEqualTo(-1)
    }

    @Test(timeout = 20_000)
    fun aStartFailureReachesTheCaller() {
        val refusing = object : PcmSource {
            override fun start() = throw MicUnavailableException("busy")
            override fun read(buffer: FloatArray): Int = -1
            override fun close() = Unit
        }
        val source = BufferedPcmSource(refusing, maxSamples = 10_000)
        val thrown = runCatching { source.start() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(MicUnavailableException::class.java)
        source.close()
    }

    @Test(timeout = 20_000)
    fun beyondTheCapTheSourceEnds() {
        // A runaway session can never grow the queue without bound: past maxSamples the reader
        // stops and the consumer sees the end of the audio.
        val endless = object : PcmSource {
            override fun start() = Unit
            override fun read(buffer: FloatArray): Int {
                buffer.fill(0.1f)
                return buffer.size
            }
            override fun close() = Unit
        }
        val source = BufferedPcmSource(endless, maxSamples = 50_000)
        source.start()
        val buf = FloatArray(4_000)
        var total = 0
        while (true) {
            val n = source.read(buf)
            if (n <= 0) break
            total += n
        }
        source.close()
        assertThat(total).isAtMost(50_000)
        assertThat(total).isAtLeast(50_000 - 4_000)
    }

    /**
     * Endpointing lets a period run for minutes, so the total cap is the session length; what
     * bounds memory is the backlog. A recogniser that stops consuming must not let the queue
     * grow to minutes of audio: past maxQueuedSamples unread, the source ends, while a consumer
     * that keeps up reads far beyond that amount.
     */
    @Test(timeout = 20_000)
    fun theBacklogIsBoundedSeparatelyFromTheSessionLength() {
        val endless = object : PcmSource {
            override fun start() = Unit
            override fun read(buffer: FloatArray): Int {
                Thread.sleep(1)
                buffer.fill(0.1f)
                return buffer.size
            }
            override fun close() = Unit
        }
        // A stalled consumer: nothing read until the reader has given up.
        val stalled = BufferedPcmSource(endless, maxSamples = 10_000_000, maxQueuedSamples = 20_000, blockSamples = 1_000)
        stalled.start()
        Thread.sleep(500)
        val buf = FloatArray(100_000)
        var total = 0
        while (true) {
            val n = stalled.read(buf)
            if (n <= 0) break
            total += n
        }
        stalled.close()
        assertThat(total).isAtMost(20_000)
        // A consumer that keeps up reads past the backlog bound.
        val live = BufferedPcmSource(endless, maxSamples = 60_000, maxQueuedSamples = 20_000, blockSamples = 1_000)
        live.start()
        var got = 0
        while (true) {
            val n = live.read(buf)
            if (n <= 0) break
            got += n
        }
        live.close()
        assertThat(got).isEqualTo(60_000)
    }

    @Test(timeout = 20_000)
    fun closeStopsTheReaderThread() {
        val mic = RingMic(chunks = 1_000)
        val source = BufferedPcmSource(mic, maxSamples = 10_000_000)
        source.start()
        Thread.sleep(TICK_MILLIS * 3L)
        source.close()
        val producedAtClose = mic.produced.get()
        Thread.sleep(TICK_MILLIS * 5L)
        assertThat(mic.produced.get()).isAtMost(producedAtClose + CHUNK)
        assertThat(Thread.getAllStackTraces().keys.none { it.name == BufferedPcmSource.THREAD_NAME && it.isAlive }).isTrue()
    }

    private companion object {
        const val CHUNK = 160
        const val RING = CHUNK * 4
        const val TICK_MILLIS = 10L
        const val SLOW_CONSUMER_MILLIS = 25L
    }
}
