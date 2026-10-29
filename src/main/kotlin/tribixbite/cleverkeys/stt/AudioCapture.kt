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
