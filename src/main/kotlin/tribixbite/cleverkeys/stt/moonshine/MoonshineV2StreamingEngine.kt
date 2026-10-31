package tribixbite.cleverkeys.stt.moonshine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import tribixbite.cleverkeys.stt.HypothesisStabilizer
import tribixbite.cleverkeys.stt.SpeechEngine
import tribixbite.cleverkeys.stt.SpeechEngineException
import tribixbite.cleverkeys.stt.SpeechEngineFactory
import tribixbite.cleverkeys.stt.SpeechEngineOptions
import tribixbite.cleverkeys.stt.SpeechHypothesis
import tribixbite.cleverkeys.stt.SpeechMemoryReport
import tribixbite.cleverkeys.stt.SpeechSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Moonshine v2 "tiny-streaming" (moonshine-ai, MIT) on the ONNX Runtime the app already ships
 * (`onnxruntime-android` 1.21.1; compatibility measured in
 * docs/research/2026-10-28-stt-engine-deep-dive.md §5.4).
 *
 * A Kotlin port of the official runtime `core/moonshine-streaming-model.cpp` (MIT) and of the
 * research spike `scripts/research/stt/moonshine_stream_ort.py`, over the five official graphs:
 *
 * ```
 *  frontend   audio chunk + 5 carried states (+ the split weights) → 50 Hz feature frames
 *  encoder    sliding window: 16·depth past frames + the new ones; only frames older than
 *             total_lookahead are stable, and each stable frame is encoded exactly once
 *  adapter    absolute position embeddings from a running pos_offset → decoder memory
 *  cross_kv   cross-attention K/V over the whole memory (recomputed when memory grew)
 *  decoder_kv greedy decode; the previous pass's tokens are re-verified in ONE call
 *             (speculative prefix), then decoding continues with a self K/V cache
 * ```
 *
 * Every recognition pass re-decodes the utterance (the decoder is attention-based), so word
 * stability comes from the engine-neutral [HypothesisStabilizer] (two passes agree). A pass runs
 * once per [CHUNK_SECONDS] of audio — 1 s measured as the floor for two-pass agreement.
 *
 * This package is the ONLY code that knows about Moonshine (`SttArchitectureDriftTest`).
 */
class MoonshineV2StreamingEngine private constructor(
    private val env: OrtEnvironment,
    private val config: MoonshineConfig,
    private val frontend: OrtSession,
    private val frontendWeights: Map<String, OnnxTensor>,
    private val encoder: OrtSession,
    private val adapter: OrtSession,
    private val crossKv: OrtSession,
    private val decoder: OrtSession,
    private val tokenizer: MoonshineTokenizer,
    private val report: SpeechMemoryReport,
) : SpeechEngine {

    override val sampleRate: Int = SAMPLE_RATE

    /**
     * Measured on LibriSpeech test-clean concatenations, streamed (2026-10-29, JVM, ORT 1.21.1):
     * 23.6 s → 0/57 errors, 34.7 s → 2/89, 46.2 s → a repetition loop (151/125). 20 s keeps a
     * forced segment well inside the coherent range and every pass short (each pass re-decodes
     * the segment); the official runtime's own default is 15 s. The engine test pins it
     * (`anUtteranceAsLongAsTheEngineAllowsStaysCoherent`, `longDictationIsSegmented…`).
     */
    override val maxSegmentSeconds: Int = MAX_SEGMENT_SECONDS

    override fun newSession(): SpeechSession = Session()

    override fun memoryReport(): SpeechMemoryReport = report

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        frontendWeights.values.forEach { it.close() }
        for (s in listOf(frontend, encoder, adapter, crossKv, decoder)) {
            try {
                s.close()
            } catch (_: OrtException) {
                // Already closed; nothing to release.
            }
        }
    }

    /** The streaming config the graphs were exported with (`streaming_config.json`). */
    data class MoonshineConfig(
        val encoderDim: Int,
        val decoderDim: Int,
        val depth: Int,
        val nHeads: Int,
        val headDim: Int,
        val vocabSize: Int,
        val bosId: Int,
        val eosId: Int,
        val totalLookahead: Int,
        val maxSeqLen: Int,
        val stateShapes: Map<String, LongArray>,
    ) {
        companion object {
            /** Parse the JSON; throws on a missing key (reported as an unreadable pack). */
            fun parse(json: String): MoonshineConfig {
                val o = JSONObject(json)
                val shapes = o.getJSONObject("frontend_state_shapes")
                val stateShapes = STATE_NAMES.associateWith { name ->
                    val arr = shapes.getJSONArray(name)
                    LongArray(arr.length()) { arr.getLong(it) }.also { s ->
                        require(s.isNotEmpty() && s.all { it in 1..4096 }) { "bad shape for $name" }
                    }
                }
                return MoonshineConfig(
                    encoderDim = o.getInt("encoder_dim"),
                    decoderDim = o.getInt("decoder_dim"),
                    depth = o.getInt("depth"),
                    nHeads = o.getInt("nheads"),
                    headDim = o.getInt("head_dim"),
                    vocabSize = o.getInt("vocab_size"),
                    bosId = o.getInt("bos_id"),
                    eosId = o.getInt("eos_id"),
                    totalLookahead = o.getInt("total_lookahead"),
                    // Absent from the tiny-streaming config; the runtime's default is 448.
                    maxSeqLen = o.optInt("max_seq_len", 448).takeIf { it > 0 } ?: 448,
                    stateShapes = stateShapes,
                ).also {
                    require(it.encoderDim in 1..4096 && it.decoderDim in 1..4096 && it.depth in 1..64 &&
                        it.nHeads in 1..64 && it.headDim in 1..1024 && it.vocabSize in 2..(1 shl 18) &&
                        it.totalLookahead in 0..1024) { "implausible streaming config" }
                }
            }
        }
    }

    /**
     * One listening period: the frontend states, the accumulated features, the decoder memory
     * and the previous pass's tokens. Not thread-safe except [cancel].
     *
     * `internal` (not private) only so the engine test can read [decodePasses].
     */
    internal inner class Session : SpeechSession {
        private val states = HashMap<String, Any>() // name → FloatArray | LongArray
        private val featureDim = config.encoderDim

        /** Feature frames kept for the encoder window; [featureBase] frames were dropped before it. */
        private var features = FloatArray(featureDim * 512)
        private var featureCount = 0
        private var featureBase = 0

        private var emitted = 0
        private var posOffset = 0L
        private var memory = FloatArray(config.decoderDim * 512)
        private var memoryLen = 0
        private var tokens = IntArray(0)

        private val pending = FloatArray(CHUNK_SAMPLES)
        private var pendingLen = 0
        private val stabilizer = HypothesisStabilizer()

        @Volatile
        private var cancelled = false
        override val isCancelled: Boolean get() = cancelled

        private var finished = false

        /** Decoder passes run so far (the expensive step); read by the engine test. */
        internal var decodePasses = 0
            private set

        init {
            for ((name, shape) in config.stateShapes) {
                val n = shape.fold(1L) { a, b -> a * b }.toInt()
                states[name] = if (name in INT64_STATES) LongArray(n) else FloatArray(n)
            }
        }

        /**
         * Every whole [CHUNK_SECONDS] of [pcm] goes through the frontend and the encoder, but the
         * decoder (the expensive step: it re-reads the whole utterance) runs ONCE per call. A
         * caller that fell behind real time hands over its backlog in one call and catches up;
         * decoding it chunk by chunk would only grow the backlog (Saga 2026-10-29).
         */
        override fun accept(pcm: FloatArray): SpeechHypothesis? {
            if (cancelled || finished || closed) return null
            var offset = 0
            var fed = false
            while (offset < pcm.size) {
                val take = min(CHUNK_SAMPLES - pendingLen, pcm.size - offset)
                System.arraycopy(pcm, offset, pending, pendingLen, take)
                pendingLen += take
                offset += take
                if (pendingLen == CHUNK_SAMPLES) {
                    if (!feed(pending.copyOf(pendingLen), final = false)) return null
                    pendingLen = 0
                    fed = true
                }
            }
            return if (fed) decodePass(final = false) else null
        }

        override fun finish(): SpeechHypothesis {
            if (cancelled || closed) return SpeechHypothesis("", 0, isFinal = true)
            if (finished) return stabilizer.update("", isFinal = true)
            finished = true
            val tail = pending.copyOf(pendingLen)
            pendingLen = 0
            return pass(tail, final = true) ?: stabilizer.update(tokenizer.decode(tokens), isFinal = true)
        }

        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            cancelled = true
        }

        /** Feed [chunk], decode, fold into the stabilizer. Null when cancelled mid-pass. */
        private fun pass(chunk: FloatArray, final: Boolean): SpeechHypothesis? =
            if (feed(chunk, final)) decodePass(final) else null

        /** Frontend + encoder for [chunk] (memory grows). False when cancelled mid-way. */
        private fun feed(chunk: FloatArray, final: Boolean): Boolean {
            try {
                runFrontend(chunk)
                if (cancelled) return false
                encodeStable(final)
            } catch (e: OrtException) {
                throw SpeechEngineException("Moonshine inference failed: ${e.message}", e)
            }
            return !cancelled
        }

        /** Decode the memory so far and fold it into the stabilizer. Null when cancelled. */
        private fun decodePass(final: Boolean): SpeechHypothesis? {
            try {
                tokens = decode()
            } catch (e: OrtException) {
                throw SpeechEngineException("Moonshine inference failed: ${e.message}", e)
            }
            if (cancelled) return null
            return stabilizer.update(tokenizer.decode(tokens), isFinal = final)
        }

        // ---- frontend -------------------------------------------------------------------

        private fun runFrontend(chunk: FloatArray) {
            if (chunk.isEmpty()) return
            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs["audio_chunk"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, chunk.size.toLong()))
                for (name in STATE_NAMES) inputs[name] = stateTensor(name)
                inputs.putAll(frontendWeights)
                frontend.run(inputs, FRONTEND_OUTPUTS).use { out ->
                    val feats = out.get("features").get() as OnnxTensor
                    val shape = feats.info.shape
                    val n = shape[1].toInt()
                    if (n > 0) appendFeatures(feats.floatBuffer, n)
                    for (name in STATE_NAMES) readState(name, out.get("${name}_out").get())
                }
            } finally {
                // The weight tensors belong to the engine; only close what this call created.
                for ((name, t) in inputs) if (name !in frontendWeights) t.close()
            }
        }

        private fun stateTensor(name: String): OnnxTensor {
            val shape = config.stateShapes.getValue(name)
            return when (val v = states.getValue(name)) {
                is LongArray -> OnnxTensor.createTensor(env, LongBuffer.wrap(v), shape)
                is FloatArray -> OnnxTensor.createTensor(env, FloatBuffer.wrap(v), shape)
                else -> error("state $name")
            }
        }

        private fun readState(name: String, value: OnnxValue) {
            val t = value as OnnxTensor
            when (val dst = states.getValue(name)) {
                is LongArray -> t.longBuffer.get(dst)
                is FloatArray -> {
                    val buf = t.floatBuffer
                    // The conv buffers' last dimension is dynamic in the graph; it is always the
                    // configured width in practice. Copy what fits rather than trust it blindly.
                    buf.get(dst, 0, min(dst.size, buf.remaining()))
                }
            }
        }

        private fun appendFeatures(buf: FloatBuffer, frames: Int) {
            val need = (featureCount + frames) * featureDim
            if (need > features.size) features = features.copyOf(max(need, features.size * 2))
            buf.get(features, featureCount * featureDim, frames * featureDim)
            featureCount += frames
        }

        // ---- encoder + adapter ----------------------------------------------------------

        private fun encodeStable(final: Boolean) {
            val total = featureBase + featureCount
            val stable = if (final) total else max(0, total - config.totalLookahead)
            val newFrames = stable - emitted
            if (newFrames <= 0) return
            val windowStart = max(0, emitted - LEFT_CONTEXT_PER_LAYER * config.depth)
            val from = (windowStart - featureBase) * featureDim
            val windowFrames = total - windowStart
            val window = FloatBuffer.wrap(features, from, windowFrames * featureDim).slice()
            OnnxTensor.createTensor(env, window, longArrayOf(1, windowFrames.toLong(), featureDim.toLong())).use { input ->
                encoder.run(mapOf("features" to input)).use { encOut ->
                    val encoded = (encOut.get(0) as OnnxTensor).floatBuffer
                    val startIdx = emitted - windowStart
                    val piece = FloatArray(newFrames * config.encoderDim)
                    encoded.position(startIdx * config.encoderDim)
                    encoded.get(piece)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(piece), longArrayOf(1, newFrames.toLong(), config.encoderDim.toLong())).use { enc ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(posOffset)), longArrayOf(1)).use { pos ->
                            adapter.run(mapOf("encoded" to enc, "pos_offset" to pos)).use { adOut ->
                                appendMemory((adOut.get(0) as OnnxTensor).floatBuffer, newFrames)
                            }
                        }
                    }
                }
            }
            emitted = stable
            posOffset += newFrames
            trimFeatures()
        }

        /** Drop feature frames no future window can reach (older than the left context). */
        private fun trimFeatures() {
            val keepFrom = max(0, emitted - LEFT_CONTEXT_PER_LAYER * config.depth)
            val drop = keepFrom - featureBase
            if (drop <= 0 || drop < TRIM_THRESHOLD_FRAMES) return
            val remaining = featureCount - drop
            System.arraycopy(features, drop * featureDim, features, 0, remaining * featureDim)
            featureCount = remaining
            featureBase = keepFrom
        }

        private fun appendMemory(buf: FloatBuffer, frames: Int) {
            val need = (memoryLen + frames) * config.decoderDim
            if (need > memory.size) memory = memory.copyOf(max(need, memory.size * 2))
            buf.get(memory, memoryLen * config.decoderDim, frames * config.decoderDim)
            memoryLen += frames
        }

        // ---- decoder --------------------------------------------------------------------

        /** Greedy decode over the current memory with the previous tokens as a speculative prefix. */
        private fun decode(): IntArray {
            if (memoryLen == 0) return IntArray(0)
            decodePasses++
            val maxTokens = min(ceil(memoryLen * FRAME_SECONDS * TOKENS_PER_SECOND).toInt(), config.maxSeqLen)
            val memTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(memory, 0, memoryLen * config.decoderDim).slice(),
                longArrayOf(1, memoryLen.toLong(), config.decoderDim.toLong())
            )
            memTensor.use { mem ->
                crossKv.run(mapOf("memory" to mem)).use { cross ->
                    val kCross = cross.get("k_cross").get() as OnnxTensor
                    val vCross = cross.get("v_cross").get() as OnnxTensor
                    return Decoder(kCross, vCross).run(tokens, maxTokens)
                }
            }
        }

        /** One pass of `decode_full` against fixed cross K/V. */
        private inner class Decoder(private val kCross: OnnxTensor, private val vCross: OnnxTensor) {
            private var last: OrtSession.Result? = null
            private val emptyShape = longArrayOf(config.depth.toLong(), 1, config.nHeads.toLong(), 0, config.headDim.toLong())

            fun run(previous: IntArray, maxTokens: Int): IntArray {
                try {
                    val result = ArrayList<Int>(previous.size + 8)
                    val next: Int
                    if (previous.isNotEmpty()) {
                        // Verify the previous pass's tokens in one call: position t predicts t+1.
                        val prefix = IntArray(previous.size + 1).also { it[0] = config.bosId; previous.copyInto(it, 1) }
                        val logits = step(prefix, fresh = true)
                        var accepted = 0
                        while (accepted < previous.size && argmaxAt(logits, accepted) == previous[accepted]) accepted++
                        for (i in 0 until accepted) result += previous[i]
                        next = if (accepted == previous.size) {
                            argmaxAt(logits, previous.size)
                        } else {
                            // Diverged: rebuild the self-attention cache on the accepted prefix.
                            val again = step(prefix.copyOf(accepted + 1), fresh = true)
                            argmaxAt(again, accepted)
                        }
                    } else {
                        val logits = step(intArrayOf(config.bosId), fresh = true)
                        next = argmaxAt(logits, 0)
                    }
                    var current = next
                    while (current != config.eosId && result.size < maxTokens && !cancelled) {
                        result += current
                        val logits = step(intArrayOf(current), fresh = false)
                        current = argmaxAt(logits, 0)
                    }
                    return result.toIntArray()
                } finally {
                    last?.close()
                    last = null
                }
            }

            /**
             * Run the decoder on [ids]; [fresh] starts from an empty self-attention cache,
             * otherwise the previous step's cache is continued. Returns the logits rows.
             */
            private fun step(ids: IntArray, fresh: Boolean): Logits {
                val tok = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
                val created = ArrayList<OnnxTensor>(3).apply { add(tok) }
                val kSelf: OnnxTensor
                val vSelf: OnnxTensor
                val prev = last
                if (fresh || prev == null) {
                    kSelf = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape).also { created += it }
                    vSelf = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape).also { created += it }
                } else {
                    kSelf = prev.get("out_k_self").get() as OnnxTensor
                    vSelf = prev.get("out_v_self").get() as OnnxTensor
                }
                try {
                    val out = decoder.run(
                        mapOf("token" to tok, "k_self" to kSelf, "v_self" to vSelf, "out_k_cross" to kCross, "out_v_cross" to vCross),
                        DECODER_OUTPUTS,
                    )
                    val logits = Logits((out.get("logits").get() as OnnxTensor).floatBuffer, ids.size, config.vocabSize)
                    // The previous result held the cache we just consumed; release it now.
                    prev?.close()
                    last = out
                    return logits
                } finally {
                    created.forEach { it.close() }
                }
            }
        }
    }

    /** `[1, rows, vocab]` logits, copied out of ORT. */
    private class Logits(buf: FloatBuffer, val rows: Int, val vocab: Int) {
        val data = FloatArray(rows * vocab).also { buf.get(it) }
    }

    private fun argmaxAt(logits: Logits, row: Int): Int {
        val r = row.coerceIn(0, logits.rows - 1)
        val base = r * logits.vocab
        var best = 0
        var bestScore = logits.data[base]
        for (i in 1 until logits.vocab) {
            val v = logits.data[base + i]
            if (v > bestScore) {
                bestScore = v
                best = i
            }
        }
        return best
    }

    /** Registered in `SpeechEngineRegistry` under [ENGINE_ID]. */
    object Factory : SpeechEngineFactory {
        override val engineId: String = ENGINE_ID
        override val versions: IntRange = 1..1

        override fun load(packDir: File, options: SpeechEngineOptions): SpeechEngine {
            val started = System.nanoTime()
            val missing = REQUIRED_FILES.filterNot { File(packDir, it).isFile }
            if (missing.isNotEmpty()) throw SpeechEngineException("Moonshine pack is missing $missing")
            val config = try {
                MoonshineConfig.parse(File(packDir, CONFIG_FILE).readText())
            } catch (e: Exception) {
                throw SpeechEngineException("Unreadable $CONFIG_FILE: ${e.message}", e)
            }
            val tokenizer = try {
                MoonshineTokenizer.parse(File(packDir, TOKENIZER_FILE).readBytes())
            } catch (e: Exception) {
                throw SpeechEngineException("Unreadable $TOKENIZER_FILE: ${e.message}", e)
            }
            val env = OrtEnvironment.getEnvironment()
            val opened = ArrayList<OrtSession>(6)
            try {
                OrtSession.SessionOptions().use { so ->
                    so.setIntraOpNumThreads(options.threads.coerceIn(1, 8))
                    so.setInterOpNumThreads(1)
                    // ORT's BFC arena never shrinks, and every streaming pass has a new input
                    // shape: with it on, a 23 s utterance held 508 MB; off, 112 MB (research §5.3).
                    so.setCPUArenaAllocator(false)
                    so.setMemoryPatternOptimization(false)
                    fun open(name: String) = env.createSession(File(packDir, name).path, so).also { opened += it }
                    val frontend = open(FRONTEND_MODEL)
                    val weights = open(FRONTEND_WEIGHTS)
                    val encoder = open(ENCODER)
                    val adapter = open(ADAPTER)
                    val crossKv = open(CROSS_KV)
                    val decoder = open(DECODER)
                    // The split frontend keeps its float weights in a separate no-input graph so
                    // they survive ORT-format conversion; its outputs are fed back as inputs on
                    // every frontend call. Copied into engine-owned tensors, then the graph closes.
                    val weightTensors = HashMap<String, OnnxTensor>()
                    weights.run(emptyMap<String, OnnxTensor>()).use { out ->
                        for (name in weights.outputNames) {
                            val t = out.get(name).get() as OnnxTensor
                            weightTensors[name] = OnnxTensor.createTensor(env, t.floatBuffer, t.info.shape)
                        }
                    }
                    weights.close()
                    opened.remove(weights)
                    if (!frontend.inputNames.containsAll(weightTensors.keys)) {
                        weightTensors.values.forEach { it.close() }
                        throw SpeechEngineException("frontend weights do not match the frontend graph")
                    }
                    val bytes = REQUIRED_FILES.sumOf { File(packDir, it).length() }
                    val report = SpeechMemoryReport(
                        modelBytes = bytes,
                        loadMillis = (System.nanoTime() - started) / 1_000_000,
                        detail = "moonshine-v2-streaming tiny, CPU EP, ${options.threads} threads, arena off",
                    )
                    return MoonshineV2StreamingEngine(
                        env, config, frontend, weightTensors, encoder, adapter, crossKv, decoder, tokenizer, report,
                    )
                }
            } catch (e: Exception) {
                opened.forEach { runCatching { it.close() } }
                if (e is SpeechEngineException) throw e
                throw SpeechEngineException("Could not load the Moonshine graphs: ${e.message}", e)
            }
        }
    }

    companion object {
        const val ENGINE_ID = "moonshine-v2-streaming"
        const val SAMPLE_RATE = 16_000

        /** Seconds of audio per recognition pass (two-pass agreement floor, research §5.2). */
        const val CHUNK_SECONDS = 1.0

        /** See [maxSegmentSeconds]. */
        const val MAX_SEGMENT_SECONDS = 20
        val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_SECONDS).toInt()

        /** Encoder frame period (50 Hz) and the runtime's decode budget per second of audio. */
        private const val FRAME_SECONDS = 0.020
        private const val TOKENS_PER_SECOND = 6.5

        /** Per-layer left context of the sliding-window encoder (frames). */
        private const val LEFT_CONTEXT_PER_LAYER = 16

        /** Trim the feature buffer only once this many frames are droppable (avoid churn). */
        private const val TRIM_THRESHOLD_FRAMES = 256

        const val FRONTEND_MODEL = "frontend.model.ort"
        const val FRONTEND_WEIGHTS = "frontend.weights.ort"
        const val ENCODER = "encoder.ort"
        const val ADAPTER = "adapter.ort"
        const val CROSS_KV = "cross_kv.ort"
        const val DECODER = "decoder_kv.ort"
        const val TOKENIZER_FILE = "tokenizer.bin"
        const val CONFIG_FILE = "streaming_config.json"

        /** Every file the runtime opens; the voice pack must declare all of them. */
        val REQUIRED_FILES = listOf(
            FRONTEND_MODEL, FRONTEND_WEIGHTS, ENCODER, ADAPTER, CROSS_KV, DECODER, TOKENIZER_FILE, CONFIG_FILE,
        )

        private val STATE_NAMES = listOf("sample_buffer", "sample_len", "conv1_buffer", "conv2_buffer", "frame_count")
        private val INT64_STATES = setOf("sample_len", "frame_count")
        private val FRONTEND_OUTPUTS = setOf("features") + STATE_NAMES.map { "${it}_out" }
        private val DECODER_OUTPUTS = setOf("logits", "out_k_self", "out_v_self")
    }
}
