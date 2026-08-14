package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import tribixbite.cleverkeys.swipe.ctc.CtcEmissionModel
import tribixbite.cleverkeys.swipe.ctc.CtcEmissions
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer

/**
 * Production [CtcEmissionModel] over onnxruntime-android — the G3 closure of the
 * retrain-fork seam (`docs/specs/ctc-swipe-engine.md` FR-5).
 *
 * Runs the CleverKeys-trained CTC swipe encoder (`models/ctc_swipe_encoder.onnx`,
 * CleverKeys-ML `ctc/` Phase M finalist `phaseM_kd_fresh_w1`, fp16w). Graph contract
 * (opset 17, fully static shapes, verified at export):
 *  - `features`     `[1, 2, 64]`  float32 — [CtcFeaturizer.featurize] output.
 *  - `layout_keys`  `[1, 64, 2]`  float32 — [CtcFeaturizer.PaddedLayout.keys]
 *    (interleaved cx,cy is exactly the row-major `[64, 2]` layout).
 *  - `layout_mask`  `[1, 64]`     bool    — [CtcFeaturizer.PaddedLayout.mask].
 *  - `log_emissions` `[1, 32, 65]` float32 — full head; blank at column 64
 *    (`MAX_KEYS`), sliced to the active alphabet via [CtcEmissions.sliceFromHead].
 *  (The graph's `coefficients`/`lambda` outputs are diagnostics and are not fetched.)
 *
 * Threading: [OrtSession.run] is thread-safe, but callers ([CtcEngineAdapter])
 * serialize all calls on one background thread anyway. The session is owned by this
 * object; [close] releases it.
 */
class OnnxCtcEmissionModel(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : CtcEmissionModel {

    companion object {
        const val INPUT_FEATURES = "features"
        const val INPUT_LAYOUT_KEYS = "layout_keys"
        const val INPUT_LAYOUT_MASK = "layout_mask"
        const val OUTPUT_LOG_EMISSIONS = "log_emissions"

        /** Full-head width = MAX_KEYS + 1 (blank column at index MAX_KEYS). */
        const val HEAD_WIDTH = CtcFeaturizer.MAX_KEYS + 1
    }

    override fun emit(features: FloatArray, layout: CtcFeaturizer.PaddedLayout): CtcEmissions {
        require(features.size == 2 * CtcFeaturizer.RESAMPLE_LENGTH) {
            "features length ${features.size} != ${2 * CtcFeaturizer.RESAMPLE_LENGTH}"
        }
        require(layout.keys.size == CtcFeaturizer.MAX_KEYS * 2) {
            "layout keys length ${layout.keys.size} != ${CtcFeaturizer.MAX_KEYS * 2}"
        }
        val numLetters = layout.mask.count { it }
        require(numLetters in 1..CtcFeaturizer.MAX_KEYS) { "empty layout mask" }

        // ORT bool tensors are 1 byte/element via ByteBuffer + OnnxJavaType.BOOL.
        val maskBytes = ByteArray(CtcFeaturizer.MAX_KEYS)
        for (i in maskBytes.indices) maskBytes[i] = if (layout.mask[i]) 1 else 0

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features),
            longArrayOf(1, 2, CtcFeaturizer.RESAMPLE_LENGTH.toLong())
        ).use { featTensor ->
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(layout.keys),
                longArrayOf(1, CtcFeaturizer.MAX_KEYS.toLong(), 2)
            ).use { keysTensor ->
                OnnxTensor.createTensor(
                    env, ByteBuffer.wrap(maskBytes),
                    longArrayOf(1, CtcFeaturizer.MAX_KEYS.toLong()), OnnxJavaType.BOOL
                ).use { maskTensor ->
                    session.run(
                        mapOf(
                            INPUT_FEATURES to featTensor,
                            INPUT_LAYOUT_KEYS to keysTensor,
                            INPUT_LAYOUT_MASK to maskTensor,
                        ),
                        setOf(OUTPUT_LOG_EMISSIONS)
                    ).use { result ->
                        val out = result.get(0) as OnnxTensor
                        val shape = out.info.shape // [1, frames, HEAD_WIDTH]
                        val frames = shape[1].toInt()
                        val headWidth = shape[2].toInt()
                        require(headWidth == HEAD_WIDTH) {
                            "unexpected head width $headWidth (expected $HEAD_WIDTH)"
                        }
                        val full = FloatArray(frames * headWidth)
                        out.floatBuffer.get(full)
                        return CtcEmissions.sliceFromHead(
                            full, frames, CtcFeaturizer.MAX_KEYS, numLetters
                        )
                    }
                }
            }
        }
    }

    /** Releases the native session (call only from the owning decode thread). */
    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            // Session close failures are non-actionable at teardown.
        }
    }
}
