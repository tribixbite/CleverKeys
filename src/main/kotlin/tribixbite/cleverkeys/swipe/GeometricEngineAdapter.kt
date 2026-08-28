package tribixbite.cleverkeys.swipe

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import tribixbite.cleverkeys.BuildConfig
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.LanguagePreferenceKeys
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.PredictionTaskRunner
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.geometric.ArrayBackedDictionary
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.GeoKnobRanges
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import tribixbite.cleverkeys.swipe.geometric.GeometricDictionary
import tribixbite.cleverkeys.swipe.geometric.GeometricEngineConfig
import tribixbite.cleverkeys.swipe.geometric.GeometricSwipeEngine
import tribixbite.cleverkeys.swipe.geometric.GeometricSwipeRequest
import tribixbite.cleverkeys.swipe.geometric.LayoutGeometry
import tribixbite.cleverkeys.swipe.geometric.SwipeKey
import tribixbite.cleverkeys.swipe.geometric.TracePoint
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * WP9 R-1 step 8 — the impurity boundary between the Android IME and the pure-JVM
 * geometric swipe engine (`swipe.geometric`, spec `docs/specs/geometric-swipe-engine.md`).
 *
 * Responsibilities (each a spec-documented adapter duty):
 *  1. [KeyboardData] → [LayoutGeometry] via [KeyboardGeometry.computeKeyRects] — the proven
 *     rect math the a11y virtual-view tree and tap hit-testing already use — normalized over
 *     the SAME view-pixel frame the swipe trace is measured in. Memoized per immutable
 *     KeyboardData instance + frame; fingerprint churn on orientation change is by-design
 *     (a rotation genuinely changes geometry).
 *  2. `PointF` trace → [TracePoint] (key-area-local px + the frame extents), with the
 *     `finger_occlusion_offset` Y shift applied at ingest via the shared [FingerOcclusion]
 *     math so the engine-agnostic slider means the same thing here as on CTC (ARC-005).
 *  3. Dictionary words → [GeometricDictionary]: reads the SAME source the production
 *     dictionary pipeline uses (imported langpack `files/langpacks/{code}/dictionary.bin`,
 *     else the bundled `dictionaries/{code}_enhanced.bin` asset — both V2 CKDT), MERGES
 *     custom words (prepended — user-added words get favorable frequency rank; custom
 *     overrides disabled, matching WordPredictor semantics) and FILTERS disabled words.
 *     The `version` generation token is a content hash of (source id, custom-words JSON,
 *     disabled set), recomputed on every ensure — any custom/disabled mutation therefore
 *     changes the engine cache key and forces a template-index rebuild without this class
 *     having to participate in the ContentObserver plumbing.
 *  4. Background [warmUpAsync] on layout/language switch so the first swipe avoids the
 *     150-400 ms synchronous Tier-A build. Memory ceiling is the engine's own
 *     `indexCacheCapacity=3` (≤ ~7.5 MB, spec NFR-2).
 *
 * ## Concurrency contract (WP9 audit M-2, 2026-08-11)
 *
 * All engine-side state (`engineInstance`, the geometry/dictionary memos, the
 * [ContractionManager]) is confined to ONE background thread — the single thread of [tasks].
 * [decodeAsync] and [warmUpAsync] therefore never run concurrently, and none of that state
 * needs synchronization. What they do NOT share is cancellation:
 *
 *  - [decodeAsync] submits in the runner's FOREGROUND slot: a new swipe cancels the previous
 *    swipe's decode (last-swipe-wins, mirroring AsyncPredictionHandler) and also cancels an
 *    in-flight prewarm so the user's gesture gets the thread immediately.
 *  - [warmUpAsync] submits in the BACKGROUND slot: a prewarm supersedes an older prewarm but
 *    NEVER cancels a decode. Before this split, the `onStartInputView` prewarm
 *    (`CleverKeysService` → `InputCoordinator.prewarmGeometricEngine`) could cancel an
 *    in-flight decode on a same-field restart, silently losing the swipe.
 *
 * Result delivery is guarded by a monotonic decode generation rather than the worker's
 * interrupt flag: only the newest decode may post to the main thread, so a superseded decode
 * can never deliver stale suggestions even if its cancellation interrupt is missed.
 *
 * Staleness of the *input field* is NOT this class's concern: the decode callback runs on the
 * main thread with the caller's captured InputConnection/EditorInfo, and the caller
 * (`InputCoordinator.performGeometricSwipeTyping`) re-checks
 * `InputCoordinator.isReplayInputStillCurrent` before committing.
 *
 * KeyboardData is immutable and safe to read off-main; the PointF trace is snapshotted
 * into pure [TracePoint]s on the caller thread before hopping.
 */
class GeometricEngineAdapter(private val context: Context) {

    companion object {
        private const val TAG = "GeometricEngineAdapter"

        /** Corner slots 1..8 of [KeyboardData.Key.keys] (0 is the center). */
        private const val FIRST_CORNER_SLOT = 1
        private const val LAST_CORNER_SLOT = 8
    }

    private val tasks = PredictionTaskRunner()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Monotonic decode counter — see the class KDoc. Incremented on the main thread by every
     * [decodeAsync]; a decode may only deliver while it still holds the newest value.
     */
    private val decodeGeneration = AtomicLong(0)

    // ── Engine (rebuilt when the user-tunable knobs change) ─────────────────────────
    // The three Full Geometric Settings knobs are baked into the immutable
    // GeometricEngineConfig, so a knob change requires a fresh engine (and with it a fresh
    // TemplateCache — the next decode/warmUp re-warms in background; settings changes are
    // rare, so the occasional rebuild is fine). All other knobs stay at the calibrated
    // defaults. Accessed only on the adapter's single background thread.
    private var engineInstance: GeometricSwipeEngine? = null
    private var engineKnobs: Triple<Int, Float, Float>? = null

    private fun engineFor(): GeometricSwipeEngine {
        val config = Config.globalConfig()
        // Clamp to the SAME ranges the Full Geometric Settings sliders expose
        // ([GeoKnobRanges] is the single source of truth both sides read). Config values can
        // arrive from a hand-edited or imported settings backup, which bypasses the sliders —
        // without this the engine could run knobs the UI cannot display or undo (audit m-2).
        val knobs = Triple(
            GeoKnobRanges.clampMaxResults(config.geo_max_results),
            GeoKnobRanges.clampFrequencyWeight(config.geo_frequency_weight),
            GeoKnobRanges.clampEndpointInsetKw(config.geo_endpoint_inset_kw),
        )
        engineInstance?.let { if (engineKnobs == knobs) return it }
        val built = GeometricSwipeEngine(
            GeometricEngineConfig(
                maxResults = knobs.first,
                frequencyWeight = knobs.second,
                endpointInsetKw = knobs.third,
            )
        )
        engineInstance = built
        engineKnobs = knobs
        return built
    }

    // ── Geometry memo (per immutable KeyboardData instance + frame + params) ────────

    /**
     * The pure [LayoutGeometry] plus the two VIEW-PIXEL facts the pure model deliberately
     * does not carry: the letter keys' bounding-box height and how many keyboard rows they
     * occupy. [FingerOcclusion] turns those into the ingest Y shift (ARC-005) — the same
     * pair [CtcEngineAdapter] derives from its letter-box affine, so one knob means one
     * thing on both engines.
     */
    private class MappedGeometry(
        val geometry: LayoutGeometry,
        val letterBoxHeightPx: Float,
        val letterRowCount: Int,
    )

    private class GeometryMemo(
        val source: WeakReference<KeyboardData>,
        val params: KeyboardGeometry.Params,
        val frameWidthPx: Float,
        val frameHeightPx: Float,
        val mapped: MappedGeometry?,
    )

    @Volatile
    private var geometryMemo: GeometryMemo? = null

    // ── Dictionary memo (per language + content-hash version) ───────────────────────
    /**
     * The merged dictionary plus its lowercase word → ordinal-rank map. The ordinal map
     * feeds [ContractionOverlay]'s real-word guard (see its KDoc for the audit numbers):
     * an alias that ranks among the language's common words must never be replaced.
     */
    private class DictMemo(
        val dictionary: GeometricDictionary,
        val ordinals: HashMap<String, Int>,
    )

    /**
     * Two slots, access-ordered — the same discipline (and the same shape) as
     * [CtcEngineAdapter]'s `trieMemos`, adopted 2026-08-28 (ARC-015).
     *
     * A single slot meant an en↔fr toggle re-read the CKDT `dictionary.bin` and rebuilt the
     * ordinal map on EVERY switch, on the decode thread, in front of the user's first swipe
     * in the new language — and bilingual toggling is exactly the traffic this adapter sees.
     * Two slots hold the pair a bilingual user actually alternates between; a third language
     * evicts the least recently used one, so the retained set stays bounded at the previous
     * language's merged word array plus its ordinal map. The engine's template index is
     * unaffected — that has its own `indexCacheCapacity=3` ceiling (spec NFR-2).
     *
     * Confined to the adapter's single background thread (class KDoc), like every other
     * memo here, so the map needs no synchronization; the field is `val` so its reference
     * is safely published.
     */
    private val dictionaryMemos = object : LinkedHashMap<String, DictMemo>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DictMemo>?): Boolean =
            size > 2
    }

    // ── Contraction display overlay (parity with the deleted vocabulary layer) ──────
    // Every dictionary stores contractions as apostrophe-free ALIASES ("theyd", "cest")
    // because the display forms are untypeable — apostrophe is not a swipe key
    // (LayoutProjection tier-5 skips them). The overlay mirrors the old emission logic
    // (paired-first, real-word-guarded replace-vs-variant); the pure decision matrix
    // lives in [ContractionOverlay] and is unit-tested in runPureTests.
    private var contractionManager: ContractionManager? = null
    private var contractionLanguage: String? = null

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
     * Until 2026-08-16 this adapter loaded the bundled ENGLISH base for EVERY language
     * (mirroring the typing pipeline's `PreferenceUIUpdateHandler`/`ManagerInitializer`
     * order), which let an English pairing keyed on a word that also exists in the active
     * language inject an English variant — a `fr` decode of `franco` also offering
     * `franco's`, whose base `francos` is not a French word.
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
        val existing = contractionManager
        if (existing != null && contractionLanguage == language) return existing
        val cm = existing ?: ContractionManager(context)
        cm.loadSwipeDisplayMappings(language)
        contractionManager = cm
        contractionLanguage = language
        return cm
    }

    /** Applies [ContractionOverlay] with this language's mappings + dictionary ranks. */
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

    /**
     * Decode a completed swipe on a background thread and deliver the engine's
     * [PredictionResult] to [onResult] ON THE MAIN THREAD. Delivers an EMPTY result when
     * the layout yields no usable geometry or no dictionary exists for [language] — the
     * caller treats that exactly like a no-prediction swipe (bar cleared by the pipeline).
     *
     * Runs in the runner's FOREGROUND slot: it supersedes both an older decode and any
     * in-flight prewarm, and only the newest decode may deliver a result (see the class
     * KDoc's concurrency contract). A superseded decode calls back NOT AT ALL — callers must
     * not treat "no callback" as an error.
     *
     * Must be called on the main thread (reads live view-derived state; snapshots the
     * mutable PointF trace before hopping threads).
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
        if (frameWidthPx <= 0f || frameHeightPx <= 0f || swipePath.isEmpty()) {
            // Still claim a generation: this degenerate swipe is the newest one, so an older
            // decode already in flight must not land on the bar after our empty result.
            decodeGeneration.incrementAndGet()
            onResult(PredictionResult(emptyList(), emptyList()))
            return
        }
        // Snapshot the trace into pure immutable points NOW (PointF is mutable).
        val points = ArrayList<TracePoint>(swipePath.size)
        for (i in swipePath.indices) {
            val p = swipePath[i]
            points.add(TracePoint(p.x, p.y, timestamps.getOrElse(i) { 0L }))
        }
        // Claim a generation BEFORE submitting: whichever decode holds the newest generation
        // when the work finishes is the only one allowed to post. Independent of the worker's
        // interrupt flag, so a missed/absorbed interrupt cannot leak a stale suggestion list.
        val generation = decodeGeneration.incrementAndGet()
        tasks.cancelAndSubmit {
            try {
                val mapped = geometryFor(keyboard, params, frameWidthPx, frameHeightPx)
                val memo = if (mapped != null) dictionaryFor(language) else null
                val result = if (mapped == null || memo == null) {
                    PredictionResult(emptyList(), emptyList())
                } else {
                    // Finger-occlusion compensation at ingest, in RAW view px, before the
                    // pure engine normalizes over the frame — the same shift, from the same
                    // pref and the same letter-box math, that CtcEngineAdapter applies
                    // (ARC-005: the slider is engine-agnostic, so a geometric-served cell
                    // must not silently ignore it). Off by default, and at 0 the original
                    // trace list is passed through untouched.
                    val yShift = FingerOcclusion.yShiftPx(
                        Config.globalConfig().finger_occlusion_offset,
                        letterBoxHeightPx = mapped.letterBoxHeightPx,
                        letterRowCount = mapped.letterRowCount,
                    )
                    val trace = if (yShift == 0f) points else {
                        ArrayList<TracePoint>(points.size).also { shifted ->
                            for (p in points) shifted.add(TracePoint(p.x, p.y + yShift, p.tMillis))
                        }
                    }
                    applyContractionDisplay(
                        engineFor().decode(
                            GeometricSwipeRequest(
                                trace, frameWidthPx, frameHeightPx, mapped.geometry, memo.dictionary
                            )
                        ),
                        language,
                        memo.ordinals
                    )
                }
                postIfNewest(generation, result, onResult)
            } catch (e: InterruptedException) {
                // Cancelled by a newer swipe — drop silently.
            } catch (e: Exception) {
                Log.e(TAG, "Geometric decode failed", e)
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

    /**
     * Background warm-up: builds the layout geometry, the merged dictionary, and the
     * engine's Tier-A template index so the first real swipe decodes in warm-path time.
     * Idempotent — the engine's cache makes a repeat warmUp a no-op.
     *
     * Runs in the runner's BACKGROUND slot (audit M-2): a prewarm supersedes an older prewarm
     * but never cancels an in-flight [decodeAsync]. A decode may cancel THIS work — that is
     * intended (the user's swipe owns the thread; the decode then builds what it needs).
     */
    fun warmUpAsync(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
        language: String,
    ) {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return
        tasks.submitBackground {
            try {
                val mapped = geometryFor(keyboard, params, frameWidthPx, frameHeightPx)
                    ?: return@submitBackground
                val memo = dictionaryFor(language) ?: return@submitBackground
                val warm = engineFor().warmUp(mapped.geometry, memo.dictionary)
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(
                        TAG,
                        "warmUp($language): typeable=${warm.typeableWordCount} " +
                            "(${(warm.typeableFraction * 100).toInt()}%) in ${warm.buildMillis}ms" +
                            if (warm.deadLayout) " DEAD-LAYOUT" else ""
                    )
                }
            } catch (e: InterruptedException) {
                // Superseded — fine, decode's synchronous fallback covers it.
            } catch (e: Exception) {
                Log.e(TAG, "Geometric warmUp failed", e)
            }
        }
    }

    /** Cancels in-flight work and shuts the background thread down (IME teardown). */
    fun shutdown() {
        tasks.shutdown()
    }

    // ═══════════════════════════ Geometry conversion ═══════════════════════════

    private fun geometryFor(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
    ): MappedGeometry? {
        geometryMemo?.let { memo ->
            if (memo.source.get() === keyboard && memo.params == params &&
                memo.frameWidthPx == frameWidthPx && memo.frameHeightPx == frameHeightPx
            ) {
                return memo.mapped
            }
        }
        val built = try {
            buildGeometry(keyboard, params, frameWidthPx, frameHeightPx)
        } catch (e: Exception) {
            Log.e(TAG, "LayoutGeometry build failed", e)
            null
        }
        geometryMemo = GeometryMemo(WeakReference(keyboard), params, frameWidthPx, frameHeightPx, built)
        return built
    }

    /**
     * Builds the pure geometry model from the FINAL modified layout, applying the spec's
     * key-classification rules (geometric-swipe-engine.md § Geometry & Script Abstraction):
     *  - letter node ⇔ center label is 1+ codepoints, all `Character.isLetter`;
     *  - alias-host node ⇔ non-letter center but ≥1 single-codepoint letter corner;
     *  - corner letters (slots 1-8) enter the codepoint → host-key alias table
     *    (multi-codepoint corner labels ignored in v1);
     *  - case folding per codepoint via [Locale.ROOT] lowercase.
     * `loc` resolution already happened upstream (the live KeyboardData is post
     * LayoutModifier.modify_layout), so surviving corners are real KeyValues here.
     *
     * Also measures the LETTER-KEY BOUNDING BOX in view px and the number of keyboard rows
     * its letter keys occupy — [FingerOcclusion]'s two inputs (ARC-005). Measured over the
     * same letter-node rects the geometry is built from, which is the union
     * [CtcEngineAdapter.buildMappedLayout] takes over its a–z keys, so both engines shift a
     * trace identically on any layout both can serve.
     */
    private fun buildGeometry(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
    ): MappedGeometry? {
        val rects = KeyboardGeometry.computeKeyRects(keyboard, params)
        if (rects.isEmpty()) return null

        val keys = ArrayList<SwipeKey>(rects.size)
        val chars = HashMap<Int, MutableList<Int>>()
        val aliases = HashMap<Int, Int>()
        var letterWidthSum = 0f
        var letterCount = 0
        // Letter-key bounding box in view px + the distinct rows those letters live in.
        var letterTopPx = Float.MAX_VALUE
        var letterBottomPx = -Float.MAX_VALUE
        val letterRows = HashSet<Int>(4)

        // computeKeyRects walks rows row-major, emitting one rect per key with a non-null
        // center (placeholders excluded). Recover (row, col) by walking the same order.
        var rectIdx = 0
        for ((rowIdx, row) in keyboard.rows.withIndex()) {
            var colIdx = -1
            for (key in row.keys) {
                if (key.keys[0] == null) continue
                colIdx++
                val rect = rects[rectIdx++]

                val centerLabel = letterLabelOf(rect.kv)
                val isLetterNode = centerLabel != null

                // Single-codepoint letter corners → alias table (multi-codepoint ignored, v1).
                val cornerAliases = ArrayList<Int>(2)
                for (slot in FIRST_CORNER_SLOT..LAST_CORNER_SLOT) {
                    val cornerKv = key.keys.getOrNull(slot) ?: continue
                    val cornerLabel = letterLabelOf(cornerKv) ?: continue
                    if (Character.charCount(cornerLabel.codePointAt(0)) == cornerLabel.length) {
                        cornerAliases.add(cornerLabel.codePointAt(0))
                    }
                }

                if (!isLetterNode && cornerAliases.isEmpty()) continue // not a swipe node

                val id = keys.size
                val wPx = rect.bounds.right - rect.bounds.left
                val hPx = rect.bounds.bottom - rect.bounds.top
                val wU = wPx / frameWidthPx
                keys.add(
                    SwipeKey(
                        id = id,
                        label = centerLabel ?: "",
                        cx = (rect.bounds.left + rect.bounds.right) / 2f / frameWidthPx,
                        cy = (rect.bounds.top + rect.bounds.bottom) / 2f / frameHeightPx,
                        w = wU,
                        h = hPx / frameHeightPx,
                        row = rowIdx,
                        col = colIdx,
                        isLetterNode = isLetterNode,
                    )
                )
                if (isLetterNode) {
                    letterWidthSum += wU
                    letterCount++
                    if (rect.bounds.top < letterTopPx) letterTopPx = rect.bounds.top
                    if (rect.bounds.bottom > letterBottomPx) letterBottomPx = rect.bounds.bottom
                    letterRows.add(rowIdx)
                    // chars: tier-1 (single-codepoint center) and tier-2 (multi-codepoint
                    // component fallback) entries both map each codepoint → this key.
                    var i = 0
                    while (i < centerLabel!!.length) {
                        val cp = centerLabel.codePointAt(i)
                        chars.getOrPut(cp) { ArrayList(1) }.add(id)
                        i += Character.charCount(cp)
                    }
                }
                for (cp in cornerAliases) {
                    // First host in row-major order wins (deterministic).
                    if (!aliases.containsKey(cp)) aliases[cp] = id
                }
            }
        }

        if (letterCount == 0) return null // nothing swipeable on this layout

        return MappedGeometry(
            geometry = LayoutGeometry(
                keys = keys,
                chars = chars.mapValues { (_, ids) -> ids.toIntArray() },
                aliases = aliases,
                aspect = frameWidthPx / frameHeightPx,
                meanKeyWidth = letterWidthSum / letterCount,
            ),
            letterBoxHeightPx = letterBottomPx - letterTopPx,
            letterRowCount = letterRows.size,
        )
    }

    /**
     * The case-folded label of [kv] iff it is an all-letter Char/String key, else null.
     * Non-printing kinds (Event, Keyevent, Modifier, …) and labels containing any
     * non-letter codepoint (digits, punctuation, Greek layout's `;`) yield null.
     */
    private fun letterLabelOf(kv: KeyValue): String? {
        val raw = when (kv.getKind()) {
            KeyValue.Kind.Char -> kv.getChar().toString()
            KeyValue.Kind.String -> kv.getString()
            else -> return null
        }
        if (raw.isEmpty()) return null
        val folded = raw.lowercase(Locale.ROOT)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (!Character.isLetter(cp)) return null
            i += Character.charCount(cp)
        }
        return folded
    }

    // ═══════════════════════════ Dictionary conversion ═══════════════════════════

    private fun dictionaryFor(language: String): DictMemo? {
        // TODO: this is the THIRD language-normalization in the swipe package and the only
        // one that does not strip a region subtag — `CtcLanguageSupport.normalize` and
        // `SwipeContractionPolicy.baseSubtag` (which delegates to it) both cut at `-`/`_`.
        // A region-bearing tag would therefore key this path's custom/disabled words to
        // `custom_words_fr-ca` while the CTC path uses `custom_words_fr`, silently splitting
        // one user's personal dictionary across two engines. Latent today, NOT a live bug:
        // every producer feeds a bare code (`Locale.getDefault().language`, and the
        // `pref_primary_language*` settings lists), and `DictionaryManager.setLanguage`
        // stores what it is given without normalizing. Fix by normalizing here too if a
        // region-bearing tag ever becomes reachable — do not add a fourth copy.
        val lang = language.lowercase(Locale.ROOT)
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val customJson = prefs.getString(LanguagePreferenceKeys.customWordsKey(lang), "{}") ?: "{}"
        val disabled = prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey(lang), emptySet())
            ?: emptySet()

        val langpackFile = File(context.filesDir, "langpacks/$lang/dictionary.bin")
        val sourceId = if (langpackFile.isFile) {
            "langpack:${langpackFile.path}:${langpackFile.length()}:${langpackFile.lastModified()}"
        } else {
            "asset:dictionaries/${lang}_enhanced.bin"
        }
        val version = contentVersion(sourceId, customJson, disabled)

        // The LANGUAGE is part of the memo identity, not just the content hash — the same
        // invariant `CtcEngineAdapter.lexiconFor` states: a language switch may never reuse
        // the previous language's dictionary, and reading the language explicitly (rather
        // than trusting the map key alone) keeps that legible at the hit site.
        dictionaryMemos[lang]?.let { memo ->
            if (memo.dictionary.language == lang && memo.dictionary.version == version) return memo
        }

        val base = try {
            if (langpackFile.isFile) {
                langpackFile.inputStream().use { CkdtDictionaryReader.read(it, lang, version) }
            } else {
                context.assets.open("dictionaries/${lang}_enhanced.bin")
                    .use { CkdtDictionaryReader.read(it, lang, version) }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "No geometric dictionary source for '$lang': ${e.javaClass.simpleName}")
            }
            // Drop only THIS language's slot: the other slot may still hold a perfectly good
            // dictionary for the language the user toggles back to.
            dictionaryMemos.remove(lang)
            return null
        }

        val merged = mergeUserWords(base, customJson, disabled, lang, version)
        // Lowercase word → ordinal rank, for ContractionOverlay's real-word guard. First
        // occurrence wins (ties can only come from case-variant duplicates).
        val ordinals = HashMap<String, Int>(merged.size * 2)
        for (i in 0 until merged.size) {
            // API 21 HAZARD: the *default* `Map#putIfAbsent` is API 24 (Java 8) and
            // throws NoSuchMethodError on Android 5.0–6.0 — `minSdk` is 21. Only
            // `ConcurrentMap#putIfAbsent` is API 1, and this receiver is a plain
            // HashMap. `containsKey` + set is the API 21-safe first-wins insert
            // (single-threaded build, so no atomicity is lost).
            val key = merged.word(i).lowercase(Locale.ROOT)
            if (!ordinals.containsKey(key)) ordinals[key] = i
        }
        val built = DictMemo(merged, ordinals)
        dictionaryMemos[lang] = built
        return built
    }

    /**
     * Overlay user state on the CKDT base: custom words are PREPENDED in (frequency desc,
     * word asc) order — ordinal rank feeds the engine's `−λ_f·ln(1+rank)` prior only
     * logarithmically, so front-loading a handful of user words is a mild, deliberate
     * boost and never buries the head of the base dictionary. Disabled words are removed
     * (custom overrides disabled, matching WordPredictor's customAndUserWords semantics).
     */
    private fun mergeUserWords(
        base: GeometricDictionary,
        customJson: String,
        disabled: Set<String>,
        language: String,
        version: Long,
    ): GeometricDictionary {
        val custom = ArrayList<Pair<String, Int>>()
        if (customJson != "{}") {
            try {
                val obj = JSONObject(customJson)
                val it = obj.keys()
                while (it.hasNext()) {
                    val word = it.next()
                    if (word.isNotBlank()) custom.add(word to obj.optInt(word, 1000))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Malformed custom-words JSON for '$language' — ignoring", e)
            }
        }
        if (custom.isEmpty() && disabled.isEmpty()) return base

        custom.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val customLower = custom.mapTo(HashSet()) { it.first.lowercase(Locale.ROOT) }
        val disabledLower = disabled.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

        val words = ArrayList<String>(base.size + custom.size)
        custom.mapTo(words) { it.first }
        for (i in 0 until base.size) {
            val w = base.word(i)
            val lower = w.lowercase(Locale.ROOT)
            if (lower in customLower || lower in disabledLower) continue
            words.add(w)
        }
        return ArrayBackedDictionary(language, version, words.toTypedArray())
    }

    /** Stable 64-bit content version over (source identity, custom JSON, disabled set). */
    private fun contentVersion(sourceId: String, customJson: String, disabled: Set<String>): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sourceId.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(customJson.toByteArray(Charsets.UTF_8))
        md.update(0)
        for (w in disabled.sorted()) {
            md.update(w.toByteArray(Charsets.UTF_8))
            md.update(1)
        }
        val d = md.digest()
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v
    }
}
