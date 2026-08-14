package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import tribixbite.cleverkeys.BuildConfig
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.LanguagePreferenceKeys
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.PredictionTaskRunner
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.onnx.ModelLoader
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * G5 — the impurity boundary between the Android IME and the pure-JVM CTC swipe
 * engine (`swipe.ctc`, spec `docs/specs/ctc-swipe-engine.md`). Mirrors
 * [GeometricEngineAdapter]'s duties for the `ctc` value of `swipe_engine_mode`:
 *
 *  1. [KeyboardData] → [CtcLayout] via [KeyboardGeometry.computeKeyRects]: the 26
 *     a–z letter keys' centers, normalized over the LETTER-KEY BOUNDING BOX (the
 *     model's [0,1] frame — the shipped encoder was trained on paths normalized
 *     over the letter area with centers passed as `layout_keys`, NOT on FUTO's
 *     4/3-aspect device frame; do not use [CtcFeaturizer.normalizeRawY] here).
 *     Memoized per immutable KeyboardData instance + frame + params.
 *  2. `PointF` trace → normalized double arrays under the SAME letter-box affine.
 *  3. Dictionary → [CtcLexiconTrie]: bundled `dictionaries/en_enhanced.json`
 *     ({word: freq}, freq already on the AOSP-like 134..255 log scale the tuned
 *     λ expects — spec NFR-4), a–z-STRIPPED (`don't`→`dont`), with user custom
 *     words merged (freq clamped 1..255; custom overrides disabled) and disabled
 *     words removed. Content-hash `version` recomputed per ensure, so any user
 *     dictionary mutation rebuilds the trie without ContentObserver plumbing.
 *  4. ONNX session via the existing [ModelLoader] (XNNPACK-first,
 *     `onnx_xnnpack_threads` pref), built lazily on the decode thread;
 *     [warmUpAsync] front-loads session + trie + layout on layout/language switch.
 *
 * ## Concurrency contract (mirrors [GeometricEngineAdapter]'s WP9-audit-M-2 shape)
 *
 * All engine-side state (the ONNX session, the layout/trie/decoder memos) is confined
 * to the single background thread of [tasks], so none of it needs synchronization:
 *
 *  - [decodeAsync] submits in the runner's FOREGROUND slot: a new swipe cancels the
 *    previous swipe's decode (last-swipe-wins) and any in-flight prewarm.
 *  - [warmUpAsync] submits in the BACKGROUND slot: a prewarm supersedes an older
 *    prewarm but NEVER cancels a decode (the `onStartInputView` prewarm must not be
 *    able to silently drop a swipe on a same-field restart).
 *
 * Result delivery is guarded by a monotonic decode generation rather than the
 * worker's interrupt flag: only the newest decode may post to the main thread, so a
 * superseded decode can never deliver stale suggestions even if its cancellation
 * interrupt is missed.
 *
 * Scores are engine-relative (softmax over final scores × 1000) — never compared
 * across engines (router KDoc contract).
 */
class CtcEngineAdapter(private val context: Context) {

    companion object {
        private const val TAG = "CtcEngineAdapter"

        /** Shipped CTC emission encoder (CleverKeys-ML ctc/, `phaseM_kd_fresh_w1` fp16w). */
        const val MODEL_ASSET = "models/ctc_swipe_encoder.onnx"

        private const val DICT_ASSET = "dictionaries/en_enhanced.json"

        /** v1 model + lexicon are English; other languages degrade to empty. */
        private const val LANGUAGE = "en"

        /** Emission-column alphabet — a..z, the shipped model's training order. */
        private val ALPHABET = CharArray(26) { ('a' + it) }

        /**
         * Slate size handed to the suggestion pipeline. The bar renders ~5 and the
         * pipeline augments (possessives, contractions); beyond 8 the tail is noise.
         * Candidates are free at decode time (topK only truncates the final sort).
         */
        private const val TOP_K = 8
    }

    private val tasks = PredictionTaskRunner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ortEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Monotonic decode counter — see the class KDoc. Incremented on the main thread by
     * every [decodeAsync]; a decode may only deliver while it holds the newest value.
     */
    private val decodeGeneration = AtomicLong(0)

    // ── ONNX emission model (decode thread only) ────────────────────────────────
    private var emissionModel: OnnxCtcEmissionModel? = null
    private var modelLoadFailed = false

    private fun modelOrNull(): OnnxCtcEmissionModel? {
        emissionModel?.let { return it }
        if (modelLoadFailed) return null // don't retry a hard failure per swipe
        return try {
            val threads = try {
                Config.globalConfig().onnx_xnnpack_threads
            } catch (e: Exception) {
                Defaults.ONNX_XNNPACK_THREADS
            }.coerceIn(1, 8)
            val loaded = ModelLoader(context, ortEnvironment)
                .loadModel(MODEL_ASSET, "CtcEncoder", true, threads)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "CTC encoder loaded (${loaded.executionProvider}, " +
                    "${loaded.modelSizeBytes} B)")
            }
            OnnxCtcEmissionModel(ortEnvironment, loaded.session).also { emissionModel = it }
        } catch (e: Exception) {
            Log.e(TAG, "CTC encoder load failed — ctc mode disabled this session", e)
            modelLoadFailed = true
            null
        }
    }

    // ── Layout memo (per immutable KeyboardData + frame + params) ───────────────

    /** [CtcLayout] plus the letter-box affine mapping view px → the model frame. */
    private class MappedLayout(
        val layout: CtcLayout,
        val padded: CtcFeaturizer.PaddedLayout,
        val originX: Float, val originY: Float,   // letter-box top-left, view px
        val invW: Float, val invH: Float,         // 1 / letter-box extent
    )

    private class LayoutMemo(
        val source: WeakReference<KeyboardData>,
        val params: KeyboardGeometry.Params,
        val frameWidthPx: Float,
        val frameHeightPx: Float,
        val mapped: MappedLayout?,
    )

    @Volatile
    private var layoutMemo: LayoutMemo? = null

    private fun layoutFor(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
    ): MappedLayout? {
        layoutMemo?.let { memo ->
            if (memo.source.get() === keyboard && memo.params == params &&
                memo.frameWidthPx == frameWidthPx && memo.frameHeightPx == frameHeightPx
            ) {
                return memo.mapped
            }
        }
        val built = try {
            buildMappedLayout(keyboard, params)
        } catch (e: Exception) {
            Log.e(TAG, "CtcLayout build failed", e)
            null
        }
        layoutMemo = LayoutMemo(WeakReference(keyboard), params, frameWidthPx, frameHeightPx, built)
        return built
    }

    /** The lowercase a–z letter of [kv] iff its label is exactly one such char. */
    private fun letterOf(kv: KeyValue): Char? {
        val raw = when (kv.getKind()) {
            KeyValue.Kind.Char -> kv.getChar().toString()
            KeyValue.Kind.String -> kv.getString()
            else -> return null
        }
        if (raw.length != 1) return null
        val c = raw.lowercase(Locale.ROOT)
        if (c.length != 1) return null
        return c[0].takeIf { it in 'a'..'z' }
    }

    /**
     * Builds the a..z [CtcLayout] from the final modified layout, or null when any
     * letter is missing (the router's QWERTY-Latin gate makes that unexpected).
     * First occurrence of a letter wins (deterministic row-major order).
     */
    private fun buildMappedLayout(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
    ): MappedLayout? {
        val rects = KeyboardGeometry.computeKeyRects(keyboard, params)
        if (rects.isEmpty()) return null

        val cx = FloatArray(26); val cy = FloatArray(26); val seen = BooleanArray(26)
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (rect in rects) {
            val letter = letterOf(rect.kv) ?: continue
            val i = letter - 'a'
            if (seen[i]) continue
            seen[i] = true
            cx[i] = (rect.bounds.left + rect.bounds.right) / 2f
            cy[i] = (rect.bounds.top + rect.bounds.bottom) / 2f
            if (rect.bounds.left < left) left = rect.bounds.left
            if (rect.bounds.top < top) top = rect.bounds.top
            if (rect.bounds.right > right) right = rect.bounds.right
            if (rect.bounds.bottom > bottom) bottom = rect.bounds.bottom
        }
        if (seen.any { !it }) return null // not a full a-z layout
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return null

        val invW = 1f / w
        val invH = 1f / h
        val normX = FloatArray(26) { (cx[it] - left) * invW }
        val normY = FloatArray(26) { (cy[it] - top) * invH }
        val layout = CtcLayout(ALPHABET.copyOf(), normX, normY)
        return MappedLayout(
            layout, CtcFeaturizer.buildPaddedLayout(layout), left, top, invW, invH
        )
    }

    // ── Lexicon trie memo (per user-dictionary content version) ─────────────────

    private class TrieMemo(val trie: CtcLexiconTrie, val version: Long)

    @Volatile
    private var trieMemo: TrieMemo? = null

    /**
     * The merged en lexicon trie (bundled base + custom − disabled), memoized by
     * content-hash version. `internal` so the instrumented latency gate can build
     * the production trie through the exact shipping merge path.
     */
    internal fun trieFor(): CtcLexiconTrie? {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val customJson = prefs.getString(LanguagePreferenceKeys.customWordsKey(LANGUAGE), "{}") ?: "{}"
        val disabled = prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey(LANGUAGE), emptySet())
            ?: emptySet()
        val version = contentVersion("asset:$DICT_ASSET", customJson, disabled)
        trieMemo?.let { if (it.version == version) return it.trie }

        val start = System.currentTimeMillis()
        val base = try {
            context.assets.open(DICT_ASSET).use { JSONObject(it.readBytes().decodeToString()) }
        } catch (e: Exception) {
            Log.e(TAG, "No CTC lexicon source ($DICT_ASSET)", e)
            trieMemo = null
            return null
        }
        val disabledLower = disabled.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

        // Custom words FIRST (freq clamped onto the 1..255 AOSP-like scale; custom
        // overrides disabled), then the base dictionary minus disabled words.
        // Insertion order only affects beam tie-breaks; LinkedHashMap keeps it
        // deterministic (base order = asset JSON order on Android's org.json).
        val merged = LinkedHashMap<String, Double>(base.length() + 64)
        if (customJson != "{}") {
            try {
                val obj = JSONObject(customJson)
                val it = obj.keys()
                while (it.hasNext()) {
                    val word = it.next()
                    if (word.isBlank()) continue
                    merged[word] = obj.optInt(word, 1000).coerceIn(1, 255).toDouble()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Malformed custom-words JSON — ignoring", e)
            }
        }
        val keys = base.keys()
        while (keys.hasNext()) {
            val word = keys.next()
            if (word.lowercase(Locale.ROOT) in disabledLower) continue
            if (word in merged) continue
            merged[word] = base.optInt(word, 1).coerceAtLeast(1).toDouble()
        }
        // STRIP loader: same non-alphabet policy as the offline tuning trie
        // (apostrophe forms reachable as their a-z surface).
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(ALPHABET, merged)
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(TAG, "CTC trie: ${trie.wordCount} words in " +
                "${System.currentTimeMillis() - start}ms (v=$version)")
        }
        val built = TrieMemo(trie, version)
        trieMemo = built
        return trie
    }

    /** Stable 64-bit content version over (source id, custom JSON, disabled set). */
    private fun contentVersion(sourceId: String, customJson: String, disabled: Set<String>): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sourceId.toByteArray(Charsets.UTF_8)); md.update(0)
        md.update(customJson.toByteArray(Charsets.UTF_8)); md.update(0)
        for (w in disabled.sorted()) {
            md.update(w.toByteArray(Charsets.UTF_8)); md.update(1)
        }
        val d = md.digest()
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v
    }

    // ── Decoder memo (per layout + trie + beam width) ───────────────────────────

    private var decoderMemo: CtcSwipeDecoder? = null
    private var decoderKey: Triple<MappedLayout, CtcLexiconTrie, Int>? = null

    private fun decoderFor(mapped: MappedLayout, trie: CtcLexiconTrie, beamWidth: Int): CtcSwipeDecoder {
        val key = Triple(mapped, trie, beamWidth)
        decoderMemo?.let { if (decoderKey == key) return it }
        val model = modelOrNull() ?: throw IllegalStateException("CTC model unavailable")
        val built = CtcSwipeDecoder(
            model, mapped.layout, trie,
            CtcScoringParams.tunedV2(beamWidth = beamWidth, topK = TOP_K)
        )
        decoderMemo = built
        decoderKey = key
        return built
    }

    // ── Public surface (mirrors GeometricEngineAdapter) ─────────────────────────

    /**
     * Decode a completed swipe on the background thread and deliver a
     * [PredictionResult] to [onResult] ON THE MAIN THREAD. Empty result when the
     * layout/model/lexicon is unavailable or [language] isn't English (v1) — the
     * caller treats that as a no-prediction swipe.
     *
     * Runs in the runner's FOREGROUND slot: it supersedes both an older decode and
     * any in-flight prewarm, and only the newest decode may deliver (see the class
     * KDoc's concurrency contract). A superseded decode calls back NOT AT ALL —
     * callers must not treat "no callback" as an error.
     *
     * Must be called on the main thread (snapshots the mutable PointF trace before
     * hopping threads).
     */
    fun decodeAsync(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
        swipePath: List<PointF>,
        timestamps: List<Long>,
        language: String,
        onResult: (PredictionResult) -> Unit,
    ) {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f || swipePath.isEmpty() ||
            !language.equals(LANGUAGE, ignoreCase = true)
        ) {
            // Still claim a generation: this degenerate swipe is the newest one, so an
            // older decode already in flight must not land on the bar after our empty result.
            decodeGeneration.incrementAndGet()
            onResult(PredictionResult(emptyList(), emptyList()))
            return
        }
        // Snapshot the mutable PointF trace NOW (raw view px; normalized later
        // on the decode thread once the letter-box affine is known).
        val n = swipePath.size
        val rawX = FloatArray(n); val rawY = FloatArray(n); val rawT = LongArray(n)
        for (i in 0 until n) {
            rawX[i] = swipePath[i].x
            rawY[i] = swipePath[i].y
            rawT[i] = timestamps.getOrElse(i) { 0L }
        }
        // Claim a generation BEFORE submitting: whichever decode holds the newest
        // generation when the work finishes is the only one allowed to post.
        val generation = decodeGeneration.incrementAndGet()
        tasks.cancelAndSubmit {
            try {
                val mapped = layoutFor(keyboard, params, frameWidthPx, frameHeightPx)
                val trie = if (mapped != null) trieFor() else null
                val model = if (trie != null) modelOrNull() else null
                val result = if (mapped == null || trie == null || model == null) {
                    PredictionResult(emptyList(), emptyList())
                } else {
                    val px = DoubleArray(n) { ((rawX[it] - mapped.originX) * mapped.invW).toDouble() }
                    val py = DoubleArray(n) { ((rawY[it] - mapped.originY) * mapped.invH).toDouble() }
                    val pt = DoubleArray(n) { rawT[it].toDouble() }
                    val beamWidth = Config.globalConfig().ctc_beam_width.coerceIn(10, 300)
                    val candidates = decoderFor(mapped, trie, beamWidth).decode(px, py, pt)
                    toPredictionResult(candidates)
                }
                postIfNewest(generation, result, onResult)
            } catch (e: InterruptedException) {
                // Cancelled by a newer swipe — drop silently.
            } catch (e: Exception) {
                Log.e(TAG, "CTC decode failed", e)
                postIfNewest(generation, PredictionResult(emptyList(), emptyList()), onResult)
            }
        }
    }

    /** Posts [result] to the main thread iff [generation] is still the newest decode. */
    private fun postIfNewest(
        generation: Long,
        result: PredictionResult,
        onResult: (PredictionResult) -> Unit,
    ) {
        if (decodeGeneration.get() != generation) return
        mainHandler.post {
            // Re-check on the main thread: a newer swipe can land between the background
            // check and this runnable, and the newest decode always owns the bar.
            if (decodeGeneration.get() == generation) onResult(result)
        }
    }

    /** Engine-relative scores: softmax over final scores × 1000 (geometric parity). */
    private fun toPredictionResult(candidates: List<CtcCandidate>): PredictionResult {
        if (candidates.isEmpty()) return PredictionResult(emptyList(), emptyList())
        val max = candidates.maxOf { it.finalScore }
        val exps = candidates.map { exp(it.finalScore - max) }
        val sum = exps.sum()
        val words = candidates.map { it.word }
        val scores = exps.map { ((it / sum) * 1000.0).roundToInt().coerceIn(0, 1000) }
        return PredictionResult(words, scores)
    }

    /**
     * Background warm-up: ONNX session + lexicon trie + layout mapping, so the
     * first real swipe decodes in warm-path time. Idempotent via the memos.
     *
     * Runs in the runner's BACKGROUND slot: a prewarm supersedes an older prewarm
     * but never cancels an in-flight [decodeAsync]. A decode may cancel THIS work —
     * that is intended (the user's swipe owns the thread; the decode then lazily
     * builds whatever the interrupted prewarm didn't finish).
     */
    fun warmUpAsync(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
        language: String,
    ) {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return
        if (!language.equals(LANGUAGE, ignoreCase = true)) return
        tasks.submitBackground {
            try {
                val mapped = layoutFor(keyboard, params, frameWidthPx, frameHeightPx)
                    ?: return@submitBackground
                val trie = trieFor() ?: return@submitBackground
                modelOrNull() ?: return@submitBackground
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "warmUp: model+trie(${trie.wordCount})+layout ready " +
                        "(letters=${mapped.layout.alphabet.size})")
                }
            } catch (e: InterruptedException) {
                // Superseded — decode's lazy path covers it.
            } catch (e: Exception) {
                Log.e(TAG, "CTC warmUp failed", e)
            }
        }
    }

    /**
     * Cancels in-flight work and shuts the background thread down (IME teardown).
     * The ORT session is intentionally NOT closed here: shutdown interrupts a
     * possibly-running `session.run`, and closing a session mid-run is UB in ORT.
     * The ~3 MB native session is reclaimed at process death — the same teardown
     * posture as the neural orchestrator's sessions.
     */
    fun shutdown() {
        tasks.shutdown()
    }
}
