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
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.MemoryProbe
import tribixbite.cleverkeys.LanguagePreferenceKeys
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.PredictionTaskRunner
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.onnx.ModelLoader
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcBeamDecoder
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcCkdtLexicon
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer
import tribixbite.cleverkeys.swipe.ctc.CtcFuzzyRescue
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcRankMerger
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
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
 *  3. Dictionary → [CtcLexiconTrie], PER LANGUAGE ([CtcLanguageSupport]): en reads the
 *     bundled `dictionaries/en_enhanced.json` ({word: freq} on the AOSP-like 134..255
 *     scale the tuned λ=4.0 expects — spec NFR-4), a–z-STRIPPED (`don't`→`dont`);
 *     every other served language (fr/de/es and the provisional it/pt/sv) reads the
 *     bundled CKDT `dictionaries/<lang>_enhanced.bin` via the
 *     geometric engine's [CkdtDictionaryReader] at `freq = max(1, 255 − rank)`
 *     ([CtcCkdtLexicon]) and project it onto a–z ([CtcAzProjection]), keeping the
 *     canonical accented form for display. Both merge the language's user custom words
 *     (freq clamped 1..255; custom overrides disabled) minus its disabled words.
 *     Content-hash `version` (plus the language) keys the memo, so any user dictionary
 *     mutation rebuilds the trie without ContentObserver plumbing.
 *  4. ONNX session via the existing [ModelLoader] (XNNPACK-first,
 *     `onnx_xnnpack_threads` pref), built lazily on the decode thread;
 *     [warmUpAsync] front-loads session + trie + layout on layout/language switch.
 *  5. DISPLAY mapping of the a–z slate, before the shared pipeline: canonical accents
 *     ("cafe"→"café", CKDT languages) then contraction aliases ("dont"→"don't") via
 *     [ContractionOverlay] + the merged-lexicon frequency ordinals, mirroring
 *     [GeometricEngineAdapter]'s `applyContractionDisplay` (paired-first,
 *     real-word-ordinal-guarded).
 *
 * ## Concurrency contract (mirrors [GeometricEngineAdapter]'s WP9-audit-M-2 shape)
 *
 * All engine-side state (the ONNX session, the layout/trie/decoder memos, the
 * [ContractionManager]) is confined
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

        /**
         * True when the CTC engine serves [language] — the table lives in
         * [CtcLanguageSupport]: en, the 2026-08-15 validated additions fr/de/es, and the
         * 2026-08-18 provisional it/pt/sv. Seven in all; never hard-code the list here.
         *
         * [decodeAsync]/[warmUpAsync] gate on this as defense-in-depth; the PRIMARY gate
         * is upstream — `InputCoordinator.performCtcSwipeTyping` reads the active language
         * BEFORE dispatch and falls through to the geometric engine for an unserved one
         * (audit M1: ctc mode must never offer less coverage than geometric).
         */
        fun supportsLanguage(language: String?): Boolean = CtcLanguageSupport.isSupported(language)

        /** Emission-column alphabet — a..z, the shipped model's training order. */
        private val ALPHABET = CharArray(26) { ('a' + it) }

        /**
         * Slate size handed to the suggestion pipeline. The bar renders ~5 and the
         * pipeline augments possessives; contraction DISPLAY mapping happens in this
         * adapter ([applyContractionDisplay] — the pipeline does NOT map aliases, see
         * audit H1); beyond 8 the tail is noise. Candidates are free at decode time
         * (topK only truncates the final sort).
         */
        private const val TOP_K = 8

        /**
         * Audit L5: ONNX-session load failures retry up to this many times (a cold-boot
         * transient — e.g. a low-memory kill mid-extract — must not permanently disable
         * ctc for the IME's whole lifetime) before latching off for the session.
         */
        private const val MAX_MODEL_LOAD_ATTEMPTS = 3

        /**
         * How long [shutdown] waits for the decode thread before giving up and leaking the ORT
         * session. Runs on the MAIN thread during IME teardown, so it must stay well inside any
         * ANR budget; a decode still running after this is already anomalous, and leaking beats
         * closing a session out from under a live native call.
         */
        private const val SHUTDOWN_AWAIT_MS = 250L
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

    /** Failed load attempts so far (audit L5: bounded retry, then latch). */
    private var modelLoadAttempts = 0

    /**
     * True once the ONNX session has permanently failed to load for this adapter
     * ([MAX_MODEL_LOAD_ATTEMPTS] exhausted).
     *
     * Written on the decode thread by [modelOrNull], read on the MAIN thread by the dispatcher —
     * hence `@Volatile`.
     *
     * **The dispatcher MUST consult this before routing a swipe here.** Without a session the
     * decode can only produce an empty slate, and an empty slate is indistinguishable from "no
     * candidates" by the time it reaches the shared pipeline: the bar simply clears and swipe
     * appears to stop working, with no error surfaced and nothing for the user to act on.
     *
     * This became the app's only path to *no engine at all* on 2026-08-18. Before then the
     * default was `neural`, so a CTC load failure hit only opt-in users and a second ML engine
     * existed to land on. Now `ctc` is the default (`Config.SWIPE_ENGINE_MODE`), `Mode.fromPref`
     * funnels every unrecognised value here, and neural is deleted — while the language, layout
     * and router gates all hand off to geometric. Falling through to geometric on a dead session
     * keeps the same coverage promise those three gates already make.
     */
    @Volatile
    private var modelPermanentlyUnavailable = false

    /**
     * See [modelPermanentlyUnavailable]. Safe to call from the main thread; cheap enough for the
     * dispatch path (a volatile read).
     */
    fun isModelPermanentlyUnavailable(): Boolean = modelPermanentlyUnavailable

    private fun modelOrNull(): OnnxCtcEmissionModel? {
        emissionModel?.let { return it }
        // L5: bounded retry — each failed attempt is logged; after the budget is
        // exhausted the failure latches for the session (no per-swipe retry storm).
        if (modelLoadAttempts >= MAX_MODEL_LOAD_ATTEMPTS) return null
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
            modelLoadAttempts++
            val latched = modelLoadAttempts >= MAX_MODEL_LOAD_ATTEMPTS
            if (latched) modelPermanentlyUnavailable = true
            Log.e(
                TAG,
                "CTC encoder load failed (attempt $modelLoadAttempts/$MAX_MODEL_LOAD_ATTEMPTS)" +
                    // The previous wording claimed the mode had been switched off. Nothing did
                    // that — the decode simply returned an empty slate and the bar cleared, so
                    // readers went looking for a mode change that never happened. Say what the
                    // code now actually does.
                    if (latched) " — falling through to the geometric engine for this session"
                    else " — will retry",
                e
            )
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

    /**
     * True when this adapter can actually decode on [keyboard] — i.e. the layout exposes
     * all 26 a–z letter keys, so [buildMappedLayout] yields a `CtcLayout`.
     *
     * The router's gate is layout-METADATA only (name + Latin script, widened 2026-08-15 so
     * Dvorak/Colemak reach the layout-agnostic encoder). A Latin layout can still be missing
     * a letter — a custom/partial layout, say — and for those the caller MUST fall through
     * to the geometric engine rather than dispatch here, or the user gets a dead suggestion
     * bar where they previously had working geometric swipe. Callers:
     * `InputCoordinator.performCtcSwipeTyping` (dispatch) and the prewarm switch.
     *
     * Cheap: it reuses the same [layoutFor] memo the decode path fills, so a check
     * immediately followed by a decode costs one geometry build, not two. Safe to call from
     * the main thread (the underlying build is pure geometry — no ONNX, no dictionary I/O).
     */
    fun supportsLayout(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
    ): Boolean {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return false
        return layoutFor(keyboard, params, frameWidthPx, frameHeightPx) != null
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

    // ── Lexicon trie memo (per language + user-dictionary content version) ─────

    /**
     * The merged lexicon trie for ONE language, plus the two overlay tables the decode
     * results are rewritten through:
     *
     *  - [ordinals] — lowercase word → frequency-ordinal rank, feeding
     *    [ContractionOverlay]'s real-word guard exactly like [GeometricEngineAdapter]'s
     *    `DictMemo.ordinals` (see [CtcLexiconMerge.ordinals] for the threshold-separation
     *    numbers on the shipped en asset);
     *  - [display] — a–z surface → canonical accented form, EMPTY for the en JSON source
     *    and populated for CKDT sources ([CtcAzProjection.projectLexicon]).
     *
     * [language] is part of the memo IDENTITY, not just the content hash: a language
     * switch must never reuse the previous language's trie (the content hash alone would
     * be a very unlikely but silent way to do so, and reading it as identity makes the
     * invariant explicit).
     */
    private class TrieMemo(
        val language: String,
        val trie: CtcLexiconTrie,
        val ordinals: HashMap<String, Int>,
        val display: Map<String, String>,
        val fuzzyRescue: CtcFuzzyRescue,
        val version: Long,
    )

    /** Keep only the active primary/secondary tries; wider caching would retain ~19 MB each. */
    private val trieMemos = object : LinkedHashMap<String, TrieMemo>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrieMemo>?): Boolean =
            size > 2
    }

    /**
     * The merged lexicon trie for [language] — see [lexiconFor]. `internal` so the
     * instrumented latency gate can build the production trie through the exact shipping
     * merge path.
     */
    internal fun trieFor(language: String): CtcLexiconTrie? = lexiconFor(language)?.trie

    /**
     * The merged lexicon for [language] (bundled base + custom − disabled) plus the
     * contraction-guard ordinals and the accent-display map, memoized by
     * (language, content-hash version).
     *
     * SOURCE per language ([CtcLanguageSupport.SUPPORTED]):
     *
     *  - **en → `dictionaries/en_enhanced.json`** (audit L2 — deliberate, do NOT "unify"):
     *    `{word: freq}` whose frequencies are already on the AOSP-like 134..255 log scale
     *    the tuned λ=4.0 was fitted against (spec NFR-4), loaded a–z-STRIPPING without
     *    accent folding — byte-for-byte the vocabulary test-2400 validated. An installed
     *    en LANGPACK's CKDT `dictionary.bin` stores the INVERTED 255−rank scale that λ was
     *    NOT fitted for, so the langpack swap stays unsupported here (plan §7.1). Known
     *    limitation: the CTC en vocabulary can diverge from the source other engines see.
     *  - **fr/de/es/it/pt/sv → `dictionaries/<lang>_enhanced.bin`** (CKDT v2, the SAME asset the
     *    geometric engine reads, parsed by the SAME [CkdtDictionaryReader]) at
     *    `freq = max(1, 255 − rank)` and projected onto a–z with the canonical accented
     *    form kept for display. λ = 2.0 for that scale — see
     *    [CtcScoringParams.presetFor] and `docs/eval/2026-08-15-ctc-per-language-lambda.md`.
     *
     * User custom/disabled words are read from the PER-LANGUAGE preference keys
     * ([LanguagePreferenceKeys.customWordsKey] / [LanguagePreferenceKeys.disabledWordsKey]),
     * so each language merges its own personal dictionary.
     */
    private fun lexiconFor(language: String): TrieMemo? {
        val lang = CtcLanguageSupport.normalize(language)
        val source = CtcLanguageSupport.SUPPORTED[lang]
        val asset = CtcLanguageSupport.assetFor(lang)
        if (source == null || asset == null) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "No CTC lexicon for unsupported language '$language'")
            }
            return null
        }
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val customJson = prefs.getString(LanguagePreferenceKeys.customWordsKey(lang), "{}") ?: "{}"
        val disabled = prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey(lang), emptySet())
            ?: emptySet()
        val version = contentVersion("asset:$asset", customJson, disabled)
        trieMemos[lang]?.let { if (it.language == lang && it.version == version) return it }

        val start = System.currentTimeMillis()

        // Parse the Android-side inputs into pure pair lists, then delegate the merge
        // policy (custom-first, 1..255 clamp, custom-overrides-disabled, case-folded
        // dedupe — audit L3) to the unit-tested [CtcLexiconMerge]. Insertion order only
        // affects beam tie-breaks; LinkedHashMap keeps it deterministic (en: asset JSON
        // order on Android's org.json; CKDT: rank ascending, file order as tie-break).
        val basePairs = try {
            when (source) {
                CtcLanguageSupport.LexiconSource.EN_JSON -> {
                    val base = context.assets.open(asset)
                        .use { JSONObject(it.readBytes().decodeToString()) }
                    val pairs = ArrayList<Pair<String, Double>>(base.length())
                    val keys = base.keys()
                    while (keys.hasNext()) {
                        val word = keys.next()
                        pairs.add(word to base.optInt(word, 1).toDouble())
                    }
                    pairs
                }
                CtcLanguageSupport.LexiconSource.CKDT_BIN -> {
                    val entries = context.assets.open(asset)
                        .use { CkdtDictionaryReader.readEntries(it) }
                    CtcCkdtLexicon.frequencyPairs(entries)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "No CTC lexicon source ($asset)", e)
            trieMemos.remove(lang)
            return null
        }

        val customPairs = ArrayList<Pair<String, Int>>()
        if (customJson != "{}") {
            try {
                val obj = JSONObject(customJson)
                val it = obj.keys()
                while (it.hasNext()) {
                    val word = it.next()
                    customPairs.add(word to obj.optInt(word, 1000))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Malformed custom-words JSON for '$lang' — ignoring", e)
            }
        }
        // These three marks are deliberately UNSETTLED, unlike the startup ones. They run on
        // the CTC decode thread, and a settled mark costs 2 forced GCs plus 2x120 ms — roughly
        // 720 ms across the three, landing squarely inside the first decode. That inflates
        // every instrumented latency measurement taken from a LOCAL_BUILD build, which is the
        // only kind of build the probe reports from at all. Allocation deltas are the right
        // question on this path anyway: what matters here is how much the trie build churns,
        // not what it retains after collection.
        MemoryProbe.mark("ctc.baseParse") { "lang=$lang entries=${basePairs.size}" }

        val merged = CtcLexiconMerge.merge(basePairs, customPairs, disabled)
        val ordinals = CtcLexiconMerge.ordinals(merged)
        MemoryProbe.mark("ctc.mergeAndOrdinals") { "merged=${merged.size}" }

        val trie: CtcLexiconTrie
        val display: Map<String, String>
        // The REAL word frequencies actually loaded into the trie — captured per branch because
        // the two sources differ (raw merged map vs the a-z projection). Used to derive the
        // contraction-injection floor from this lexicon's rarest real word.
        val lexiconFrequencies: Collection<Double>
        val rescueFrequencies: Map<String, Double>
        when (source) {
            CtcLanguageSupport.LexiconSource.EN_JSON -> {
                // STRIP loader: same non-alphabet policy as the offline tuning trie
                // (apostrophe forms reachable as their a-z surface). NO accent folding —
                // this is the exact vocabulary the shipped λ/model were validated on;
                // en contractions are restored by the ContractionOverlay instead.
                trie = CtcLexiconTrie.loadStrippingNonAlphabet(ALPHABET, merged)
                display = emptyMap()
                lexiconFrequencies = merged.values
                rescueFrequencies = merged
            }
            CtcLanguageSupport.LexiconSource.CKDT_BIN -> {
                // NFD → drop combining marks → a–z, with explicit expansions for common
                // fixed-head letters (ß→ss, œ→oe, æ→ae, ø→o) and canonical display preserved.
                val projected = CtcAzProjection.projectLexicon(merged)
                trie = CtcLexiconTrie.loadFromFrequencyMap(ALPHABET, projected.freqs)
                display = projected.display
                lexiconFrequencies = projected.freqs.values
                rescueFrequencies = projected.freqs
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "CTC '$lang' projection: ${projected.records} records, " +
                        "${projected.untypeable} untypeable, ${projected.collisions} " +
                        "accent-strip collisions, ${display.size} display forms")
                }
            }
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(TAG, "CTC trie[$lang]: ${trie.wordCount} words in " +
                "${System.currentTimeMillis() - start}ms (v=$version)")
        }
        // Make the contraction mapping table REACHABLE. The overlay can only rewrite a
        // surface the beam actually produced, and a productive elision (fr "dabaissement" →
        // "d'abaissement") is not a dictionary word, so without this the mapping is inert.
        // Injected at a floor frequency so a pseudo-word never outranks real vocabulary —
        // see [CtcContractionKeys].
        // Injected one below the lexicon's RAREST REAL WORD, not at the scale's bottom. The
        // invariant is unchanged — every real word still strictly outranks every pseudo-word on
        // frequency — but injecting at 1.0 over-guarded it by an order of magnitude: the beam
        // score divides emission evidence by len^0.9 while the frequency bonus enters undivided,
        // so the ~8.5-nat gap to fr's rarest word demanded ~75 nats of raw evidence against the
        // 7-10 the shipped model actually produces. 49% of the fr alias table was unreachable as
        // a result. Derived per-lexicon because a constant cannot serve them all: the rarest real
        // word is 69 in fr but 12 in de, so any constant above 11 would break the invariant for
        // German. See [CtcContractionKeys.derivedFloor].
        val injectionFloor = CtcContractionKeys.derivedFloor(lexiconFrequencies)
        val injected = CtcContractionKeys.inject(
            trie, contractionsFor(lang).getAliasKeys(), injectionFloor
        )

        MemoryProbe.mark("ctc.trie") {
            "lang=$lang words=${trie.wordCount} nodes=${trie.nodeCount} " +
                "injectedAliases=$injected display=${display.size}"
        }
        val built = TrieMemo(
            lang, trie, ordinals, display,
            CtcFuzzyRescue.fromFrequencies(rescueFrequencies),
            version,
        )
        trieMemos[lang] = built
        return built
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

    // ── Display overlays (audit H1 — parity with GeometricEngineAdapter) ─────────────
    //
    // The beam emits a-z SURFACES; two things have to be put back before the slate
    // reaches the pipeline, in this order:
    //
    //  1. ACCENTS (CKDT languages) — the trie path is the a-z projection of a canonical
    //     word, so "café" would commit as "cafe" without the per-lexicon display map.
    //  2. APOSTROPHES (contractions) — every dictionary stores contractions as
    //     apostrophe-free ALIASES because apostrophe is not a swipe key: en_enhanced.json
    //     has ZERO apostrophe words ("dont", "im", "theyd") and the CKDT dictionaries have
    //     zero as well (fr "jai"/"cest", de "dor"). The pure decision matrix (paired-first
    //     keep+variant, real-word ordinal guard, junk-alias replace) lives in
    //     [ContractionOverlay] and is unit-tested in runPureTests; this adapter supplies
    //     the active language's mappings + the merged-lexicon frequency ordinals
    //     ([CtcLexiconMerge.ordinals]) exactly like the geometric twin.
    //
    // Accents first: contraction keys are a-z aliases, so an accented display form never
    // matches one, while an un-accented alias is unaffected by step 1.
    private val contractionManagers = object : LinkedHashMap<String, ContractionManager>(2, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ContractionManager>?
        ): Boolean = size > 2
    }

    /**
     * Lazily builds/reloads the contraction mapping for [language] (decode thread only).
     *
     * The mappings are scoped to the ACTIVE DECODE LANGUAGE
     * ([ContractionManager.loadSwipeDisplayMappings] executes the policy; the rule and its
     * rationale live in [SwipeContractionPolicy]): English keeps the bundled base set, every
     * other language gets ONLY its own file. Code-switching is a bug — English morphology
     * must not bleed into a sentence the user is typing in another language, which is the
     * same call `OptimizedVocabulary` made in v1.1.88 (clears the English
     * contractions before loading the target language's) and the same call the shared
     * pipeline already makes for possessives (`SuggestionHandler.shouldAugmentPossessives`).
     *
     * The manager instance is REUSED across language switches (the memo below is keyed by
     * [contractionLanguage]), so the loader must start from a cleared state on every switch
     * — it does, in both branches.
     *
     * [ContractionOverlay]'s real-word ordinal guard stays as defense in depth: it is still
     * load-bearing WITHIN a language, where a language's own file maps a key that is also
     * one of its common words (fr `la`→`l'a`, `les`→`l'es`, `ma`→`m'a`; de `im`). Those are
     * KEPT with the contraction merely appended, never substituted.
     */
    private fun contractionsFor(language: String): ContractionManager {
        contractionManagers[language]?.let { return it }
        val cm = ContractionManager(context)
        cm.loadSwipeDisplayMappings(language)
        contractionManagers[language] = cm
        return cm
    }

    /**
     * Rewrites a-z surfaces to their canonical accented forms (`cafe` → `café`) using the
     * lexicon's stripped→canonical map. A no-op for the en JSON source (empty map) and for
     * any surface whose canonical form is already un-accented.
     */
    private fun applyCanonicalDisplay(
        result: PredictionResult,
        display: Map<String, String>,
    ): PredictionResult {
        if (display.isEmpty() || result.words.isEmpty()) return result
        if (result.words.none { display.containsKey(it) }) return result
        return PredictionResult(result.words.map { display[it] ?: it }, result.scores)
    }

    /** Applies [ContractionOverlay] with [language]'s mappings + merged-lexicon ordinals. */
    private fun applyContractionDisplay(
        result: PredictionResult,
        language: String,
        ordinals: HashMap<String, Int>,
    ): PredictionResult {
        if (result.words.isEmpty()) return result
        val cm = contractionsFor(language)
        val (words, scores) = ContractionOverlay.apply(
            words = result.words,
            scores = result.scores,
            pairedVariants = { cm.getPairedContractions(it) },
            nonPairedMapping = { cm.getNonPairedMapping(it) },
            wordOrdinal = { ordinals[it] },
        )
        return PredictionResult(words, scores)
    }

    /** Both display overlays, in the order documented above. */
    private fun applyDisplay(result: PredictionResult, lexicon: TrieMemo): PredictionResult =
        applyContractionDisplay(
            applyCanonicalDisplay(result, lexicon.display),
            lexicon.language,
            lexicon.ordinals,
        )

    /**
     * Adds at most two bounded dictionary matches for the unconstrained greedy surface.
     *
     * The merge itself lives in [CtcFuzzyRescue.mergeIntoBeam] — pure, unit-tested and shared with
     * the pure-JVM replay engine. Rescued words are APPENDED BELOW the real beam into spare
     * [TOP_K] slots; they never displace or outrank a real beam word (CK-150-025). Empty beam
     * slates are still populated directly.
     */
    private fun applyFuzzyRescue(
        result: PredictionResult,
        greedy: String,
        lexicon: TrieMemo,
    ): PredictionResult {
        val rescued = lexicon.fuzzyRescue.find(greedy, result.words.toHashSet())
        if (rescued.isEmpty()) return result
        val (words, scores) =
            CtcFuzzyRescue.mergeIntoBeam(result.words, result.scores, rescued, TOP_K)
        return PredictionResult(words, scores)
    }

    // ── Public surface (mirrors GeometricEngineAdapter) ─────────────────────────

    /**
     * Decode a completed swipe on the background thread and deliver a
     * [PredictionResult] to [onResult] ON THE MAIN THREAD. Empty result when the
     * layout/model/lexicon is unavailable or [language] is not CTC-supported
     * ([supportsLanguage]) — the caller treats that as a no-prediction swipe.
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
        secondaryLanguage: String? = null,
        onResult: (PredictionResult) -> Unit,
    ) {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f || swipePath.isEmpty() ||
            !supportsLanguage(language)
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
                val primaryLexicon = if (mapped != null) lexiconFor(language) else null
                val secondary = secondaryLanguage
                    ?.takeIf { it != "none" && it != language && supportsLanguage(it) }
                val secondaryLexicon = if (mapped != null && secondary != null) {
                    lexiconFor(secondary)
                } else null
                val model = if (primaryLexicon != null) modelOrNull() else null
                val result = if (mapped == null || primaryLexicon == null || model == null) {
                    PredictionResult(emptyList(), emptyList())
                } else {
                    // Finger-occlusion compensation, applied to RAW Y before the letter-box
                    // affine — the fingertip hides the target, so touches land above the key the
                    // user aimed at. Expressed as a signed percent of ONE KEY ROW so it means
                    // the same thing at any keyboard height; the letter box spans three letter
                    // rows, hence the /3. Signed because the correction runs both ways.
                    //
                    // Default 0: see Defaults.FINGER_OCCLUSION_OFFSET for why the neural
                    // engine's 12.5% is NOT carried over. At 0 this costs one float multiply.
                    val occlusionPct = Config.globalConfig().finger_occlusion_offset
                    val rowHeightPx = (1f / mapped.invH) / 3f
                    val yShift = rowHeightPx * (occlusionPct / 100f)

                    val px = DoubleArray(n) { ((rawX[it] - mapped.originX) * mapped.invW).toDouble() }
                    val py = DoubleArray(n) {
                        ((rawY[it] + yShift - mapped.originY) * mapped.invH).toDouble()
                    }
                    val pt = DoubleArray(n) { rawT[it].toDouble() }
                    val beamWidth = Config.globalConfig().ctc_beam_width.coerceIn(10, 300)
                    // Run the encoder ONCE, then decode each active language against its own
                    // trie/preset. Raw final scores use different frequency scales and are never
                    // compared; CtcRankMerger combines the displayed slates by rank.
                    val features = CtcFeaturizer.featurize(px, py, pt)
                    val emissions = model.emit(features, mapped.padded)
                    val greedy = CtcBeamDecoder.greedy(emissions, mapped.layout.alphabet)
                    fun decodeLexicon(lexicon: TrieMemo): PredictionResult {
                        val candidates = CtcBeamDecoder.decode(
                            emissions,
                            lexicon.trie,
                            CtcScoringParams.presetFor(
                                lexicon.language, beamWidth = beamWidth, topK = TOP_K
                            ),
                        )
                        return applyDisplay(
                            applyFuzzyRescue(toPredictionResult(candidates), greedy, lexicon),
                            lexicon,
                        )
                    }

                    val primaryResult = decodeLexicon(primaryLexicon)
                    val secondaryResult = secondaryLexicon?.let(::decodeLexicon)
                    if (secondaryResult == null) {
                        primaryResult
                    } else {
                        val merged = CtcRankMerger.merge(
                            primaryLexicon.language,
                            primaryResult.words,
                            secondaryLexicon.language,
                            secondaryResult.words,
                            TOP_K,
                        )
                        PredictionResult(merged.map { it.word }, merged.map { it.score })
                    }
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
        secondaryLanguage: String? = null,
    ) {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return
        if (!supportsLanguage(language)) return
        tasks.submitBackground {
            try {
                val mapped = layoutFor(keyboard, params, frameWidthPx, frameHeightPx)
                    ?: return@submitBackground
                val trie = trieFor(language) ?: return@submitBackground
                secondaryLanguage
                    ?.takeIf { it != "none" && it != language && supportsLanguage(it) }
                    ?.let { trieFor(it) }
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
     * Cancels in-flight work, shuts the background thread down, and releases the ORT session
     * **only once that thread is confirmed dead** (IME teardown).
     *
     * The ordering is the whole point. `shutdownNow` interrupts the worker, but a task already
     * inside a native `session.run` keeps running until that call returns, and closing a session
     * mid-run is undefined behaviour in ORT. So the close is gated on
     * [PredictionTaskRunner.awaitTermination]: if the thread has not finished within
     * [SHUTDOWN_AWAIT_MS] the session is deliberately LEAKED to process death, because a leak is
     * strictly better than a native crash in a keyboard.
     *
     * Previously this method skipped the close entirely for that reason, which was safe but cost
     * ~3 MB of native memory per adapter lifecycle — and an adapter is rebuilt on IME teardown,
     * not just at process death. Waiting is the missing piece, not the close itself.
     *
     * The timeout is short: this runs on the main thread during teardown, and a decode that has
     * not returned in [SHUTDOWN_AWAIT_MS] is already anomalous.
     */
    fun shutdown() {
        tasks.shutdown()
        if (tasks.awaitTermination(SHUTDOWN_AWAIT_MS)) {
            emissionModel?.close()
            emissionModel = null
        } else if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.w(TAG, "decode thread still running after ${SHUTDOWN_AWAIT_MS}ms — " +
                "leaking the ORT session rather than closing it under a live session.run")
        }
    }
}
