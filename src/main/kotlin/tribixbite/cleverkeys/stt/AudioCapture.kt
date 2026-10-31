package tribixbite.cleverkeys.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * A source of mono float PCM for a [SpeechSession]. The microphone in production
 * ([MicrophoneCapture]); a WAV or a synthetic signal in tests.
 */
interface PcmSource : AutoCloseable {
    /** Start delivering audio. Throws [MicUnavailableException] when the device refuses. */
    fun start()

    /**
     * Fill [buffer] with up to its size of samples in [-1, 1]; blocks until some are available.
     * Returns the count (0 at the end of a finite source), or -1 on a device error.
     */
    fun read(buffer: FloatArray): Int
}

/** The microphone could not be opened (busy in a call, policy, permission revoked mid-way). */
class MicUnavailableException(message: String) : Exception(message)

/**
 * The microphone through [AudioRecord]: 16 kHz, mono, 16-bit PCM, `VOICE_RECOGNITION` source
 * (tuned for ASR: no automatic gain tricks meant for calls). Opened only while voice input is
 * listening and released at once afterwards; nothing is written anywhere — samples go straight
 * from the recorder's buffer to the recogniser and are dropped.
 *
 * The internal buffer holds [BUFFER_SECONDS] of audio so a slow recognition pass (the decoder
 * re-decodes the utterance once per second) never overruns the recorder.
 */
class MicrophoneCapture(private val sampleRate: Int) : PcmSource {

    private var record: AudioRecord? = null
    private var shorts = ShortArray(0)

    @SuppressLint("MissingPermission") // MicPermissionGate checked RECORD_AUDIO before any start()
    override fun start() {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw MicUnavailableException("getMinBufferSize=$min")
        val bytes = maxOf(min, sampleRate * 2 * BUFFER_SECONDS)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
        } catch (e: Exception) {
            throw MicUnavailableException("AudioRecord: ${e.message}")
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw MicUnavailableException("AudioRecord not initialised")
        }
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            r.release()
            throw MicUnavailableException("startRecording: ${e.message}")
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            throw MicUnavailableException("recorder did not start (microphone busy?)")
        }
        record = r
    }

    override fun read(buffer: FloatArray): Int {
        val r = record ?: return -1
        if (shorts.size < buffer.size) shorts = ShortArray(buffer.size)
        val n = r.read(shorts, 0, buffer.size)
        if (n < 0) return -1
        for (i in 0 until n) buffer[i] = shorts[i] / 32768f
        return n
    }

    override fun close() {
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Never started or already stopped.
            }
            it.release()
        }
        record = null
    }

    companion object {
        const val BUFFER_SECONDS = 4
    }
}

/**
 * Decouples reading the microphone from recognising it (device test 2026-10-29).
 *
 * The recogniser runs one pass per second of audio and every pass re-decodes the utterance, so
 * passes get slower as the utterance grows. When the capture thread both read the recorder and
 * ran those passes, a pass longer than the recorder's ring (4 s) overran it: on the Saga
 * AudioFlinger logged "RecordThread: buffer overflow" after ~35 s of speech and whole sentences
 * were missing from the final text. Here a dedicated thread ([THREAD_NAME]) keeps draining
 * [inner] into a queue, so the device ring never fills; the recogniser takes from the queue at
 * its own pace, and [read] hands it EVERYTHING queued (up to its buffer) so a recogniser that
 * fell behind can decode the backlog in one pass and catch up.
 *
 * Bounds: after [maxSamples] in total the reader stops and the source ends, as if the recorder
 * had (the listening period's own limit is shorter). Memory is bounded by [maxQueuedSamples]:
 * a recogniser that has that much audio unread is not catching up, and the source ends the same
 * way (2026-10-31: endpointing lets a period run for minutes, so the total is no longer a memory
 * bound). Audio stays in memory only.
 *
 * [read] keeps returning queued audio after [close], then 0 — the caller drains what was
 * spoken before stop was pressed. A device error ends the stream with -1 after the audio
 * queued before it.
 */
class BufferedPcmSource(
    private val inner: PcmSource,
    private val maxSamples: Int,
    private val maxQueuedSamples: Int = maxSamples,
    private val blockSamples: Int = DEFAULT_BLOCK_SAMPLES,
) : PcmSource {

    private val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()

    /** Samples put in [queue] and not yet read. */
    private val queued = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    private var running = false
    private var reader: Thread? = null

    /** The queue head being consumed (or a sticky end marker), and how far into it. */
    private var carry: FloatArray? = null
    private var carryOffset = 0

    override fun start() {
        inner.start() // a refusal reaches the caller before any thread exists
        running = true
        reader = Thread(::readLoop, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        val block = FloatArray(blockSamples)
        var total = 0L
        var failed = false
        try {
            while (running && total < maxSamples) {
                val n = inner.read(block)
                if (n < 0) {
                    failed = true
                    break
                }
                if (n == 0) break
                val take = minOf(n.toLong(), maxSamples - total).toInt()
                if (queued.get() + take > maxQueuedSamples) break // the consumer stalled
                queued.addAndGet(take.toLong())
                queue.put(block.copyOf(take))
                total += take
            }
        } catch (e: Exception) {
            // The recorder failed mid-read (released under us, device error): end with an error
            // unless we were closing anyway.
            failed = running
        } finally {
            queue.put(if (failed) ERROR else END)
        }
    }

    override fun read(buffer: FloatArray): Int {
        var n = 0
        while (n < buffer.size) {
            // Block only for the first piece; after that take what is already queued.
            val piece = carry ?: (if (n == 0) queue.take() else queue.poll()) ?: break
            if (piece === END || piece === ERROR) {
                carry = piece // sticky: every later read reports the end too
                return if (n > 0) n else if (piece === ERROR) -1 else 0
            }
            val k = minOf(buffer.size - n, piece.size - carryOffset)
            System.arraycopy(piece, carryOffset, buffer, n, k)
            queued.addAndGet(-k.toLong())
            n += k
            carryOffset += k
            if (carryOffset == piece.size) {
                carry = null
                carryOffset = 0
            } else {
                carry = piece
            }
        }
        return n
    }

    override fun close() {
        if (!running && reader == null) {
            inner.close()
            return
        }
        running = false
        // Let the reader finish its current block (≤ blockSamples of audio) before the recorder
        // is released, so it never reads a released AudioRecord.
        reader?.join(JOIN_MILLIS)
        inner.close()
        reader?.join(JOIN_MILLIS)
        reader = null
    }

    companion object {
        const val THREAD_NAME = "voice-mic-reader"

        /** 100 ms reads: low latency, and the reader re-checks [running] ten times a second. */
        const val DEFAULT_BLOCK_SAMPLES = 1_600

        private const val JOIN_MILLIS = 1_000L

        private val END = FloatArray(0)
        private val ERROR = FloatArray(0)
    }
}
