package tribixbite.cleverkeys.stt

import java.io.Closeable
import java.io.File

/**
 * The engine-neutral speech-to-text contract (docs/specs/voice-input.md).
 *
 * Everything outside `stt/<engine>/` talks to recognition through these types only, so the
 * engine can be replaced (Moonshine v2 today; v3 or a successor later) by adding one class that
 * implements [SpeechEngineFactory], one entry in [SpeechEngineRegistry] and a new voice pack.
 * `SttArchitectureDriftTest` pins that no other file names an engine.
 *
 * Threading: an engine and its sessions are NOT thread-safe. The voice controller confines one
 * session to its single worker thread; [SpeechSession.cancel] is the one call allowed from
 * another thread (it only raises a flag the worker checks).
 */

/**
 * One recognition result for the audio received so far.
 *
 * @property text the whole transcript of the session so far, as the engine would type it
 *   (engine-normalised spacing; may be empty).
 * @property stableWordCount how many leading whitespace-separated words of [text] the engine
 *   will not revise any more. They may be committed to the editor; the rest is a pending tail
 *   that may still change on the next pass.
 * @property isFinal true for the result of [SpeechSession.finish]: nothing will change, every
 *   word is stable.
 */
data class SpeechHypothesis(
    val text: String,
    val stableWordCount: Int,
    val isFinal: Boolean,
) {
    /** The transcript split into words (whitespace-separated, empties dropped). */
    val words: List<String> get() = splitWords(text)

    /** The stable leading words, clamped to the word count. */
    val stableWords: List<String> get() = words.take(stableWordCount.coerceIn(0, words.size))

    /** The pending (still revisable) tail words. */
    val pendingWords: List<String> get() = words.drop(stableWordCount.coerceIn(0, words.size))

    companion object {
        val EMPTY = SpeechHypothesis("", 0, isFinal = false)

        /** Whitespace word split shared by every stability calculation. */
        fun splitWords(text: String): List<String> =
            text.split(WHITESPACE).filter { it.isNotEmpty() }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** What a loaded engine costs, for the settings screen and the log. */
data class SpeechMemoryReport(
    /** Bytes of model files the engine mapped or read. */
    val modelBytes: Long,
    /** Wall-clock milliseconds the load took. */
    val loadMillis: Long,
    /** Free-form engine detail (execution provider, thread count); never user text. */
    val detail: String,
)

/** Knobs every engine honours. */
data class SpeechEngineOptions(
    /** Intra-op threads for inference. 2 balances latency against the foreground app. */
    val threads: Int = DEFAULT_THREADS,
) {
    companion object {
        const val DEFAULT_THREADS = 2
    }
}

/** Raised for a pack an engine cannot load, or an inference failure. */
class SpeechEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Builds engines of one kind. Registered in [SpeechEngineRegistry] under [engineId]; a voice
 * pack names the id and an engine version that must fall in [versions].
 */
interface SpeechEngineFactory {
    /** The manifest `engine.id` this factory serves (e.g. `moonshine-v2-streaming`). */
    val engineId: String

    /** The manifest `engine.version` values this build can run. */
    val versions: IntRange

    /**
     * Load the engine from an installed pack directory (its files were size- and
     * sha256-verified at import).
     *
     * @throws SpeechEngineException when a required file is missing or unreadable.
     */
    fun load(packDir: File, options: SpeechEngineOptions): SpeechEngine
}

/** A loaded model. Holds native sessions: always [close] it. */
interface SpeechEngine : Closeable {
    /** The PCM sample rate [SpeechSession.accept] expects (16,000 for every current pack). */
    val sampleRate: Int

    /** Start an independent recognition session (one utterance / one listening period). */
    fun newSession(): SpeechSession

    fun memoryReport(): SpeechMemoryReport

    /**
     * The longest single segment (seconds of audio in one [SpeechSession]) this engine
     * transcribes coherently. The keyboard's [SpeechSegmenter] ends a segment at a pause and
     * forces a boundary here even without one (at the quietest point of the last two seconds),
     * then opens a fresh session, so a listening period can run far longer than one segment.
     * An engine that re-decodes the whole utterance on every pass falls apart beyond its
     * training length (Moonshine v2 tiny looped "some use of some use of…" on 46 s of
     * LibriSpeech, 2026-10-29) and gets slower with every second, so it declares a bound well
     * inside the coherent range. The default suits an attention model trained on ≤ 30 s.
     */
    val maxSegmentSeconds: Int get() = DEFAULT_MAX_SEGMENT_SECONDS

    companion object {
        /** [maxSegmentSeconds] for an engine that does not declare one. */
        const val DEFAULT_MAX_SEGMENT_SECONDS = 30
    }
}

/** One listening period. */
interface SpeechSession : Closeable {
    /**
     * Feed mono float PCM in [-1, 1] at [SpeechEngine.sampleRate]. The engine buffers as it
     * needs; it returns a new hypothesis when it ran a recognition pass, or null when the audio
     * only went into its buffer.
     *
     * @throws SpeechEngineException on inference failure.
     */
    fun accept(pcm: FloatArray): SpeechHypothesis?

    /**
     * No more audio: flush what is buffered and return the final hypothesis
     * ([SpeechHypothesis.isFinal], every word stable).
     */
    fun finish(): SpeechHypothesis

    /** Abandon the session. Safe from any thread; later calls return empty results. */
    fun cancel()

    /** True once [cancel] ran. */
    val isCancelled: Boolean
}
