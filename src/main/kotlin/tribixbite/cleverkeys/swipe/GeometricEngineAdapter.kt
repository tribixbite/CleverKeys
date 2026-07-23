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
 *  2. `PointF` trace → [TracePoint] (key-area-local px + the frame extents).
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
 * Threading: conversion + decode run on a single background thread (the engine's `decode`
 * is synchronous and allocation-light); results post to the main thread. A new decode
 * cancels the in-flight one (last-swipe-wins, mirroring AsyncPredictionHandler).
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
        val knobs = Triple(
            config.geo_max_results.coerceIn(1, 32),
            config.geo_frequency_weight.coerceIn(0f, 1f),
            config.geo_endpoint_inset_kw.coerceIn(0f, 2f),
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
    private class GeometryMemo(
        val source: WeakReference<KeyboardData>,
        val params: KeyboardGeometry.Params,
        val frameWidthPx: Float,
        val frameHeightPx: Float,
        val geometry: LayoutGeometry?,
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

    @Volatile
    private var dictionaryMemo: DictMemo? = null

    // ── Contraction display overlay (parity with the neural vocab layer) ────────────
    // Every dictionary stores contractions as apostrophe-free ALIASES ("theyd", "cest")
    // because the display forms are untypeable — apostrophe is not a swipe key
    // (LayoutProjection tier-5 skips them). The overlay mirrors neural's emission logic
    // (paired-first, real-word-guarded replace-vs-variant); the pure decision matrix
    // lives in [ContractionOverlay] and is unit-tested in runPureTests.
    private var contractionManager: ContractionManager? = null
    private var contractionLanguage: String? = null

    /** Lazily builds/reloads the contraction mapping for [language] (decode thread only). */
    private fun contractionsFor(language: String): ContractionManager {
        val existing = contractionManager
        if (existing != null && contractionLanguage == language) return existing
        val cm = existing ?: ContractionManager(context)
        // Mirror production loading (PreferenceUIUpdateHandler / ManagerInitializer):
        // base set (clears), then language-specific, then the extra contractions_en.json
        // entries — earlier loads win on key collisions, so the active language's own
        // mappings take precedence over the English extras.
        cm.loadMappings()
        if (language != "en") cm.loadLanguageContractions(language)
        cm.loadLanguageContractions("en")
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
            onResult(PredictionResult(emptyList(), emptyList()))
            return
        }
        // Snapshot the trace into pure immutable points NOW (PointF is mutable).
        val points = ArrayList<TracePoint>(swipePath.size)
        for (i in swipePath.indices) {
            val p = swipePath[i]
            points.add(TracePoint(p.x, p.y, timestamps.getOrElse(i) { 0L }))
        }
        tasks.cancelAndSubmit {
            try {
                val geometry = geometryFor(keyboard, params, frameWidthPx, frameHeightPx)
                val memo = if (geometry != null) dictionaryFor(language) else null
                val result = if (geometry == null || memo == null) {
                    PredictionResult(emptyList(), emptyList())
                } else {
                    applyContractionDisplay(
                        engineFor().decode(
                            GeometricSwipeRequest(points, frameWidthPx, frameHeightPx, geometry, memo.dictionary)
                        ),
                        language,
                        memo.ordinals
                    )
                }
                if (!Thread.currentThread().isInterrupted) {
                    mainHandler.post { onResult(result) }
                }
            } catch (e: InterruptedException) {
                // Cancelled by a newer swipe — drop silently.
            } catch (e: Exception) {
                Log.e(TAG, "Geometric decode failed", e)
                if (!Thread.currentThread().isInterrupted) {
                    mainHandler.post { onResult(PredictionResult(emptyList(), emptyList())) }
                }
            }
        }
    }

    /**
     * Background warm-up: builds the layout geometry, the merged dictionary, and the
     * engine's Tier-A template index so the first real swipe decodes in warm-path time.
     * Idempotent — the engine's cache makes a repeat warmUp a no-op.
     */
    fun warmUpAsync(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
        language: String,
    ) {
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return
        tasks.cancelAndSubmit {
            try {
                val geometry = geometryFor(keyboard, params, frameWidthPx, frameHeightPx) ?: return@cancelAndSubmit
                val memo = dictionaryFor(language) ?: return@cancelAndSubmit
                val warm = engineFor().warmUp(geometry, memo.dictionary)
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
    ): LayoutGeometry? {
        geometryMemo?.let { memo ->
            if (memo.source.get() === keyboard && memo.params == params &&
                memo.frameWidthPx == frameWidthPx && memo.frameHeightPx == frameHeightPx
            ) {
                return memo.geometry
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
     */
    private fun buildGeometry(
        keyboard: KeyboardData,
        params: KeyboardGeometry.Params,
        frameWidthPx: Float,
        frameHeightPx: Float,
    ): LayoutGeometry? {
        val rects = KeyboardGeometry.computeKeyRects(keyboard, params)
        if (rects.isEmpty()) return null

        val keys = ArrayList<SwipeKey>(rects.size)
        val chars = HashMap<Int, MutableList<Int>>()
        val aliases = HashMap<Int, Int>()
        var letterWidthSum = 0f
        var letterCount = 0

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

        return LayoutGeometry(
            keys = keys,
            chars = chars.mapValues { (_, ids) -> ids.toIntArray() },
            aliases = aliases,
            aspect = frameWidthPx / frameHeightPx,
            meanKeyWidth = letterWidthSum / letterCount,
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

        dictionaryMemo?.let { memo ->
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
            dictionaryMemo = null
            return null
        }

        val merged = mergeUserWords(base, customJson, disabled, lang, version)
        // Lowercase word → ordinal rank, for ContractionOverlay's real-word guard. First
        // occurrence wins (ties can only come from case-variant duplicates).
        val ordinals = HashMap<String, Int>(merged.size * 2)
        for (i in 0 until merged.size) {
            ordinals.putIfAbsent(merged.word(i).lowercase(Locale.ROOT), i)
        }
        val built = DictMemo(merged, ordinals)
        dictionaryMemo = built
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
