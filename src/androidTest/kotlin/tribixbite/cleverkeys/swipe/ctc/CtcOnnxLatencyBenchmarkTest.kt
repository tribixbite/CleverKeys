package tribixbite.cleverkeys.swipe.ctc

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * REAL on-device latency measurement for the CleverKeys-ML CTC swipe encoders — the G3
 * latency question of `CleverKeys-ML/ctc/APP_INTEGRATION_PLAN.md` §3.
 *
 * This is a **measurement harness, not a gate**. Every assertion is a loose sanity bound
 * (roughly two orders of magnitude above the expected value), chosen so an emulator hiccup
 * can never turn this red; the payload is the `CtcLatencyBench` logcat lines, which carry
 * mean/p50/p90/p99 per model per session configuration. A tight gate belongs in the
 * separate `CtcLatencyGateTest` the integration plan describes, once the engine is wired.
 *
 * ## What is measured
 *  1. [encoderLatency_allModels_bothProviderConfigs] — `OrtSession.run` + read of
 *     `log_emissions` for each of the four Phase-E/Phase-F candidate encoders, under two
 *     session configurations:
 *      - **production** — a mirror of
 *        `tribixbite.cleverkeys.onnx.ModelLoader.createOptimizedSessionOptions` +
 *        `tryXnnpack`: `ALL_OPT`, memory-pattern on, optimized-graph disk cache,
 *        `setIntraOpNumThreads(0)` auto-detect, then XNNPACK with
 *        `intra_op_num_threads = Defaults.ONNX_XNNPACK_THREADS (2)` and the same
 *        `setIntraOpNumThreads` follow-up — falling back to plain CPU if XNNPACK is
 *        unavailable, exactly as production does.
 *      - **cpu1** — `ALL_OPT` with `intra_op = inter_op = 1`, no XNNPACK: the protocol
 *        `CleverKeys-ML/ctc/bench_latency.py` used for the laptop table, so this column is
 *        directly comparable to it.
 *  2. [fullDecodePath_ch128_beam100_e1] — the end-to-end post-gesture cost for the
 *     then-leading CANDIDATE, `ch128_s1234`. NOTE it is not, and never became, the ship
 *     model: that is `phaseM_kd_fresh_w1_s1234_fp16w` (a ch192 distilled student). Nothing
 *     here was measured on the shipped encoder.
 *     featurize → NN → [CtcEmissions.sliceFromHead] → [CtcBeamDecoder]
 *     at beam width 100 over the real bundled 98k-word `dictionaries/en_enhanced.json`
 *     trie, at the **E1** preset (γ 1.05, λ 1.1, β 0.2, α 0.0, γp 0.3734, βp 0.9882). These
 *     are E1's constants, NOT tunedV2's (0.9 / 4.0 / 0.25 / 0.25 / 0.9882) — the old name
 *     said tunedV2 and the numbers said E1, which is how a latency figure gets quoted
 *     against the wrong operating point.
 *     G3's question was whether NN+beam fits the then-current 100–300 ms budget.
 *
 * ## Laptop reference (`PHASE_F.md`, CPU EP, `intra_op = inter_op = 1`, batch 1, idle)
 * | model | laptop mean |
 * |---|---|
 * | `ch128_s1234`        | 0.455 ms (0.472–0.475 on the Phase-F harness) |
 * | `ch192_s1234`        | 0.877 ms (0.911–0.934 on the Phase-F harness) |
 * | `fast_resbn80_s1234` | 0.215 ms |
 * | `fast_resbn72_s1234` | 0.186 ms |
 * An x86_64 cloud emulator is neither that laptop nor a phone little core — finding out how
 * the ratio travels is the point of running this.
 *
 * ## Model assets (androidTest APK only — deliberately NOT in the app APK)
 * Copied verbatim from `CleverKeys-ML/ctc/artifacts/` on 2026-08-08:
 * ```
 * ch128_s1234.onnx         2,799,865 B  sha256 6c1144949e545f626419e1fa7b29e80f9ecf3e303886f30411fc37ae72c45c51
 * ch192_s1234.onnx         6,144,249 B  sha256 d5b5f10ea16f08743d0742b3c60aa37a469ada11c418a7f459d5ae4cff20c666
 * fast_resbn80_s1234.onnx  1,142,727 B  sha256 5e8c88756cbad5a5a8b8b3f289a990174fa6f3b6edfead46d8dbdb2927fb06f2
 * fast_resbn72_s1234.onnx    944,487 B  sha256 6567366b61bbbd04b5353f7f780aedb9aa507f7a87f52a381089cb54bf510985
 * ```
 * All four share one graph contract (opset 17, fully static shapes): inputs
 * `features [1,2,64]` f32, `layout_keys [1,64,2]` f32, `layout_mask [1,64]` bool; outputs
 * `log_emissions [1,32,65]` f32 (+ `coefficients`/`lambda` diagnostics, never fetched).
 *
 * Run (see `.claude/skills/ew-cli-testing.md` — the `EW_VERSION` pin is required):
 * ```
 * EW_VERSION=1.3.4 ew-cli \
 *   --app build/outputs/apk/debug/CleverKeys-*-x86_64.apk \
 *   --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
 *   --outputs-dir ~/ew-output --timeout 40m \
 *   --device model=Pixel7,version=34 --use-orchestrator \
 *   --test-targets "class tribixbite.cleverkeys.swipe.ctc.CtcOnnxLatencyBenchmarkTest"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CtcOnnxLatencyBenchmarkTest {

    private companion object {
        const val TAG = "CtcLatencyBench"

        /** Encoder schedule — 300 timed calls matches the laptop harness's round size. */
        const val ENCODER_WARMUP = 30
        const val ENCODER_ITERATIONS = 300

        /** Decode-path schedule — a beam over a 98k-word trie is orders slower than the NN. */
        const val DECODE_WARMUP = 5
        const val DECODE_ITERATIONS = 30

        /** Graph contract, identical across all four artifacts. */
        const val MAX_KEYS = CtcFeaturizer.MAX_KEYS   // 64
        const val HEAD_WIDTH = MAX_KEYS + 1           // 65 (blank at column 64)
        const val FRAMES = 32                         // T' for a T=64 input path
        const val IN_FEATURES = "features"
        const val IN_LAYOUT_KEYS = "layout_keys"
        const val IN_LAYOUT_MASK = "layout_mask"
        const val OUT_LOG_EMISSIONS = "log_emissions"

        /** Mirrors `Defaults.ONNX_XNNPACK_THREADS` — the production session's thread count. */
        const val PRODUCTION_XNNPACK_THREADS = 2

        /** Integration-plan D3: every ML validation decoded at width 100, so 100 it is. */
        const val BEAM_WIDTH = 100

        /** Planned adapter slate size (`TOP_K`). */
        const val TOP_K = 8

        /** Sanity ceilings ONLY — never tighten these here; see the class docs. */
        const val ENCODER_MEAN_SANITY_MS = 50.0
        const val DECODE_TOTAL_MEAN_SANITY_MS = 2000.0

        val MODELS = listOf(
            "ch128_s1234", "ch192_s1234", "fast_resbn80_s1234", "fast_resbn72_s1234",
        )
    }

    /** Session configuration under test. */
    private enum class SessionConfig(val label: String) {
        /** Mirror of `ModelLoader` (ALL_OPT + memory pattern + cache + XNNPACK @ 2 threads). */
        PRODUCTION("production-xnnpack2"),

        /** `bench_latency.py` protocol: CPU EP, `intra_op = inter_op = 1`, no XNNPACK. */
        CPU_SINGLE_THREAD("cpu-1thread"),
    }

    /** Latency distribution over one timed run, in milliseconds. */
    private data class Stats(
        val n: Int,
        val meanMs: Double,
        val p50Ms: Double,
        val p90Ms: Double,
        val p99Ms: Double,
        val minMs: Double,
        val maxMs: Double,
    ) {
        override fun toString(): String = String.format(
            "n=%d mean=%.3f p50=%.3f p90=%.3f p99=%.3f min=%.3f max=%.3f (ms)",
            n, meanMs, p50Ms, p90Ms, p99Ms, minMs, maxMs,
        )
    }

    /**
     * Nearest-rank percentiles (no interpolation), so every reported value is a real
     * observed sample — which is what a latency-budget conversation actually wants.
     */
    private fun stats(nanos: LongArray): Stats {
        require(nanos.isNotEmpty()) { "no samples" }
        val sorted = nanos.clone().also { it.sort() }
        fun pct(p: Double): Double {
            val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1] / 1e6
        }
        var sum = 0.0
        for (v in nanos) sum += v.toDouble()
        return Stats(
            n = nanos.size,
            meanMs = sum / nanos.size / 1e6,
            p50Ms = pct(50.0),
            p90Ms = pct(90.0),
            p99Ms = pct(99.0),
            minMs = sorted.first() / 1e6,
            maxMs = sorted.last() / 1e6,
        )
    }

    /**
     * Reads a model from the **test** APK's assets.
     *
     * The four bench models are NOT committed — they were 11.0 MB of byte-identical copies of
     * the ONNX files in `CleverKeys-ML/ctc/artifacts`, carried in the androidTest APK CI builds
     * and uploads on every run. Deleting the duplicates costs nothing recoverable and removes
     * that weight; this is a benchmark, not a gate, so it is opt-in.
     *
     * To run it, copy the four models back first:
     *
     *     cp ~/git/swype/CleverKeys-ML/ctc/artifacts/ch128_s1234.onnx \
     *        ~/git/swype/CleverKeys-ML/ctc/artifacts/ch192_s1234.onnx \
     *        ~/git/swype/CleverKeys-ML/ctc/artifacts/fast_resbn80_s1234.onnx \
     *        ~/git/swype/CleverKeys-ML/ctc/artifacts/fast_resbn72_s1234.onnx \
     *        src/androidTest/assets/ctc_bench/
     *
     * Fails loudly rather than skipping: a benchmark that silently no-ops reads as coverage it
     * does not provide, which is the same trap the oracle tests' `assumeTrue` removal fixed.
     */
    private fun readModelBytes(name: String): ByteArray = try {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("ctc_bench/$name.onnx").use { it.readBytes() }
    } catch (e: java.io.FileNotFoundException) {
        throw AssertionError(
            "ctc_bench/$name.onnx is not in the test APK. These arch-comparison models are " +
                "deliberately not committed — copy them from " +
                "CleverKeys-ML/ctc/artifacts/ into src/androidTest/assets/ctc_bench/ and " +
                "rebuild the androidTest APK. See this method's KDoc.",
            e
        )
    }

    /**
     * Build session options for [config].
     *
     * @param cacheKey unique per (model, config) so the optimized-graph caches never collide.
     * @return the options plus the execution-provider label actually achieved.
     */
    private fun buildOptions(
        config: SessionConfig,
        cacheKey: String,
    ): Pair<OrtSession.SessionOptions, String> {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setMemoryPatternOptimization(true)
        return when (config) {
            SessionConfig.PRODUCTION -> {
                opts.setIntraOpNumThreads(0) // auto-detect first, exactly as ModelLoader does
                try {
                    // MUST be the TARGET app's cacheDir, not the test APK's: instrumentation runs
                    // inside the app-under-test process (uid of `tribixbite.cleverkeys.debug`), so
                    // `getInstrumentation().context.cacheDir` — which points at
                    // `…/tribixbite.cleverkeys.debug.test/cache`, owned by a different uid — is
                    // NOT writable and ORT dies with
                    // "ORT_FAIL … SaveToOrtFormat Failed to save ORT format model to file".
                    // Production's ModelLoader caches into the app's own cacheDir, which is what
                    // targetContext gives us, so this is also the faithful mirror.
                    val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                    opts.setOptimizedModelFilePath(File(cacheDir, "ctc_bench_$cacheKey.ort").absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "optimized-model cache unavailable ($cacheKey): ${e.message}")
                }
                val ep = try {
                    val threads = PRODUCTION_XNNPACK_THREADS.coerceIn(1, 8)
                    opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                    opts.setIntraOpNumThreads(threads)
                    "XNNPACK"
                } catch (e: Exception) {
                    // Production's chain continues to NNAPI, but this encoder is a CPU-class
                    // graph and NNAPI would add a partitioning cost the measurement should
                    // not silently absorb. Report the honest fallback instead.
                    Log.w(TAG, "XNNPACK unavailable ($cacheKey): ${e.message}")
                    "CPU"
                }
                opts to ep
            }
            SessionConfig.CPU_SINGLE_THREAD -> {
                opts.setIntraOpNumThreads(1)
                opts.setInterOpNumThreads(1)
                opts to "CPU"
            }
        }
    }

    /** The production-shaped input trio, owning its three native tensors. */
    private class Inputs(
        env: OrtEnvironment,
        features: FloatArray,
        layout: CtcFeaturizer.PaddedLayout,
    ) : Closeable {
        private val featTensor: OnnxTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features),
            longArrayOf(1, 2, CtcFeaturizer.RESAMPLE_LENGTH.toLong()),
        )
        private val keysTensor: OnnxTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(layout.keys), longArrayOf(1, MAX_KEYS.toLong(), 2),
        )
        private val maskTensor: OnnxTensor = run {
            // ORT bool tensors are 1 byte/element via ByteBuffer + OnnxJavaType.BOOL.
            val bytes = ByteArray(MAX_KEYS) { if (layout.mask[it]) 1 else 0 }
            OnnxTensor.createTensor(
                env, ByteBuffer.wrap(bytes), longArrayOf(1, MAX_KEYS.toLong()), OnnxJavaType.BOOL,
            )
        }

        val map: Map<String, OnnxTensor> = mapOf(
            IN_FEATURES to featTensor,
            IN_LAYOUT_KEYS to keysTensor,
            IN_LAYOUT_MASK to maskTensor,
        )

        override fun close() {
            featTensor.close()
            keysTensor.close()
            maskTensor.close()
        }
    }

    /** The frozen `en_qwerty` layout, padded to the encoder's `[64,2]` + `[64]` tensors. */
    private fun benchLayout(): CtcFeaturizer.PaddedLayout = CtcFeaturizer.buildPaddedLayout(
        CtcLayout(CtcBenchFixture.ALPHABET, CtcBenchFixture.LAYOUT_CX, CtcBenchFixture.LAYOUT_CY),
    )

    /** The frozen realistic trace through the real featurizer (no shortcuts, no stubs). */
    private fun benchFeatures(): FloatArray = CtcFeaturizer.featurize(
        CtcBenchFixture.PATH_X, CtcBenchFixture.PATH_Y, CtcBenchFixture.PATH_T,
    )

    /**
     * One `session.run` + read of `log_emissions` into [dest] — the unit both benchmarks
     * time. Only `log_emissions` is requested, so the graph's diagnostic outputs are never
     * materialized (matching the planned production emission model).
     */
    private fun runOnce(session: OrtSession, inputs: Map<String, OnnxTensor>, dest: FloatArray) {
        session.run(inputs, setOf(OUT_LOG_EMISSIONS)).use { result ->
            val tensor = result.get(0) as OnnxTensor
            tensor.floatBuffer.get(dest)
        }
    }

    /**
     * Assert the emission tensor really is `[1, FRAMES, HEAD_WIDTH]` before timing it — a
     * silent shape change in a future export would otherwise be measured as a "fast model".
     */
    private fun assertEmissionShape(session: OrtSession, model: String) {
        val info = session.outputInfo[OUT_LOG_EMISSIONS]
            ?: throw AssertionError("$model has no $OUT_LOG_EMISSIONS output; has ${session.outputNames}")
        val shape = (info.info as TensorInfo).shape
        assertArrayEquals(
            "$model emission shape changed",
            longArrayOf(1, FRAMES.toLong(), HEAD_WIDTH.toLong()), shape,
        )
    }

    @Test
    fun encoderLatency_allModels_bothProviderConfigs() {
        val env = OrtEnvironment.getEnvironment()
        val dest = FloatArray(FRAMES * HEAD_WIDTH)
        val summary = StringBuilder()

        Inputs(env, benchFeatures(), benchLayout()).use { inputs ->
            for (model in MODELS) {
                val modelBytes = readModelBytes(model)
                for (config in SessionConfig.values()) {
                    val (opts, ep) = buildOptions(config, "${model}_${config.name.lowercase()}")
                    val loadStart = System.nanoTime()
                    val session = opts.use { env.createSession(modelBytes, it) }
                    val loadMs = (System.nanoTime() - loadStart) / 1e6

                    session.use { s ->
                        assertEmissionShape(s, model)

                        repeat(ENCODER_WARMUP) { runOnce(s, inputs.map, dest) }

                        val samples = LongArray(ENCODER_ITERATIONS)
                        for (i in 0 until ENCODER_ITERATIONS) {
                            val t0 = System.nanoTime()
                            runOnce(s, inputs.map, dest)
                            samples[i] = System.nanoTime() - t0
                        }

                        val st = stats(samples)
                        val line = "$model [${config.label}/$ep] $st " +
                            "load=${"%.1f".format(loadMs)}ms bytes=${modelBytes.size}"
                        Log.i(TAG, line)
                        summary.append(line).append('\n')

                        assertTrue(
                            "$model/${config.label} mean ${st.meanMs} ms exceeds the " +
                                "$ENCODER_MEAN_SANITY_MS ms sanity ceiling — something is badly wrong",
                            st.meanMs < ENCODER_MEAN_SANITY_MS,
                        )
                        // Guard against timing a no-op: emissions are log-softmax rows, so
                        // they must be non-trivial and non-positive.
                        assertTrue("$model produced all-zero emissions", dest.any { it != 0.0f })
                        assertTrue("$model emitted a positive log-prob", dest.all { it <= 1e-3f })
                    }
                }
            }
        }

        Log.i(TAG, "ENCODER SUMMARY\n$summary")
    }

    @Test
    fun fullDecodePath_ch128_beam100_e1() {
        val env = OrtEnvironment.getEnvironment()
        val model = "ch128_s1234"

        // E1 preset (integration plan D2), constructed inline. Deliberately NOT
        // CtcScoringParams.tunedV2 — these constants are E1's, and the two differ.
        val params = CtcScoringParams(
            gamma = 1.05, lambda = 1.1, beta = 0.2, alpha = 0.0,
            gammaPrune = 0.3734, betaPrune = 0.9882,
            beamWidth = BEAM_WIDTH, topK = TOP_K,
        )

        val trieStart = System.nanoTime()
        val trie = buildTrieFromBundledDictionary()
        val trieBuildMs = (System.nanoTime() - trieStart) / 1e6
        Log.i(TAG, "trie built: ${trie.wordCount} words in ${"%.1f".format(trieBuildMs)} ms")
        assertTrue("bundled trie looks empty (${trie.wordCount} words)", trie.wordCount > 50_000)

        val (opts, ep) = buildOptions(SessionConfig.PRODUCTION, "${model}_decode")
        val session = opts.use { env.createSession(readModelBytes(model), it) }
        val dest = FloatArray(FRAMES * HEAD_WIDTH)
        val numLetters = CtcBenchFixture.ALPHABET.size
        val layout = benchLayout()

        val featNanos = LongArray(DECODE_ITERATIONS)
        val nnNanos = LongArray(DECODE_ITERATIONS)
        val sliceNanos = LongArray(DECODE_ITERATIONS)
        val beamNanos = LongArray(DECODE_ITERATIONS)
        val totalNanos = LongArray(DECODE_ITERATIONS)
        var lastTop: List<CtcCandidate> = emptyList()

        session.use { s ->
            assertEmissionShape(s, model)

            // One pass = everything a real swipe pays after touch-up, tensor construction
            // included (the planned adapter builds fresh tensors per gesture).
            fun onePass(record: Int?): List<CtcCandidate> {
                val t0 = System.nanoTime()
                val features = benchFeatures()
                val t1 = System.nanoTime()
                Inputs(env, features, layout).use { inputs -> runOnce(s, inputs.map, dest) }
                val t2 = System.nanoTime()
                val emissions = CtcEmissions.sliceFromHead(dest, FRAMES, MAX_KEYS, numLetters)
                val t3 = System.nanoTime()
                val candidates = CtcBeamDecoder.decode(emissions, trie, params)
                val t4 = System.nanoTime()
                if (record != null) {
                    featNanos[record] = t1 - t0
                    nnNanos[record] = t2 - t1
                    sliceNanos[record] = t3 - t2
                    beamNanos[record] = t4 - t3
                    totalNanos[record] = t4 - t0
                }
                return candidates
            }

            repeat(DECODE_WARMUP) { lastTop = onePass(null) }
            for (i in 0 until DECODE_ITERATIONS) lastTop = onePass(i)
        }

        val totalSt = stats(totalNanos)
        Log.i(TAG, "DECODE PATH $model [$ep] beam=$BEAM_WIDTH topK=$TOP_K words=${trie.wordCount}")
        Log.i(TAG, "  featurize ${stats(featNanos)}")
        Log.i(TAG, "  nn        ${stats(nnNanos)}")
        Log.i(TAG, "  slice     ${stats(sliceNanos)}")
        Log.i(TAG, "  beam      ${stats(beamNanos)}")
        Log.i(TAG, "  TOTAL     $totalSt")
        Log.i(TAG, "  trieBuild ${"%.1f".format(trieBuildMs)} ms (one-off, at process start)")
        Log.i(TAG, "  top: " + lastTop.joinToString { "${it.word}=${"%.3f".format(it.finalScore)}" })

        assertTrue("decode returned no candidates", lastTop.isNotEmpty())
        assertTrue("decode returned an empty word", lastTop.all { it.word.isNotEmpty() })
        assertTrue(
            "decode-path mean ${totalSt.meanMs} ms exceeds the $DECODE_TOTAL_MEAN_SANITY_MS ms " +
                "sanity ceiling — something is badly wrong",
            totalSt.meanMs < DECODE_TOTAL_MEAN_SANITY_MS,
        )
    }

    /**
     * Build the CTC lexicon trie from the app's bundled `dictionaries/en_enhanced.json`
     * (98k words, values already on the AOSP-like 134–255 log scale — integration plan D4),
     * using the same `loadStrippingNonAlphabet` policy the planned adapter uses so
     * apostrophe forms (`don't` → `dont`) stay reachable from an a–z-only model.
     *
     * Read from the **target** context: the dictionary ships in the app APK, not the test
     * APK. Insertion order follows JSON document order (Android's [JSONObject] is backed by
     * a `LinkedHashMap`) and is preserved into a [LinkedHashMap], so trie child-edge
     * ordering — and therefore beam tie-breaking — is deterministic run to run.
     */
    private fun buildTrieFromBundledDictionary(): CtcLexiconTrie {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val json = targetContext.assets.open("dictionaries/en_enhanced.json").use {
            String(it.readBytes(), Charsets.UTF_8)
        }
        val obj = JSONObject(json)
        val words = LinkedHashMap<String, Double>(obj.length() * 2)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            words[k] = obj.optDouble(k, 1.0)
        }
        return CtcLexiconTrie.loadStrippingNonAlphabet(CtcBenchFixture.ALPHABET, words)
    }
}
