package tribixbite.cleverkeys.ml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ML data model for swipe typing training data.
 * Captures normalized swipe traces with metadata for offline model training.
 *
 * Provenance (WP9 audit n-2, 2026-08-11): every trace also carries the KEYBOARD LAYOUT it was
 * drawn on and the DECODER ENGINE that produced its suggestions. Since the geometric engine
 * shipped, swipes can originate on non-QWERTY layouts (Dvorak, ЙЦУКЕН, AZERTY…) whose key
 * geometry is incompatible with a QWERTY-trained model — untagged, those traces would
 * be indistinguishable from QWERTY ones in an export and would silently poison any
 * corpus built from it. Rows written before tagging (and any import of an older export) read
 * back as [UNKNOWN] rather than failing to load.
 */
class SwipeMLData {
    companion object {
        private const val TAG = "SwipeMLData"

        /** Provenance value for traces recorded before layout/engine tagging existed. */
        const val UNKNOWN = "unknown"

        /** [engine] value for the removed ONNX transformer decoder (QWERTY-only routing).
         *  Retained for legacy DB rows and exports — see the class KDoc. */
        const val ENGINE_NEURAL = "neural"

        /** [engine] value for the geometric SHARK2 decoder (any layout). */
        const val ENGINE_GEOMETRIC = "geometric"

        /** [engine] value for the CTC trie-beam decoder (G5 `ctc` mode, QWERTY-Latin). */
        const val ENGINE_CTC = "ctc"

        /** Blank/missing provenance normalizes to [UNKNOWN] so exports never carry "". */
        private fun normalizeProvenance(value: String?): String =
            value?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }

    /**
     * One key's hit-test cell on the layout the trace was drawn on, in RAW px of the
     * keyboard-view coordinate frame — the SAME frame the raw touch points were sampled in
     * before [addRawPoint] normalized them by screen dimensions. A consumer recovers
     * comparable px via `x_norm * screen_width_px` / `y_norm * screen_height_px`.
     * Essential for occlusion analysis: without per-key boxes a trace cannot be related
     * to the letters the finger covered.
     */
    data class KeyGeom(
        @JvmField val label: String,
        @JvmField val left: Float,
        @JvmField val top: Float,
        @JvmField val right: Float,
        @JvmField val bottom: Float
    )

    /** One entry of the suggestion-bar ranking shown for this swipe (rank = list index). */
    data class RankedCandidate(
        @JvmField val word: String,
        @JvmField val score: Int
    )

    // Data fields matching ML requirements
    val traceId: String
    val targetWord: String
    val timestampUtc: Long
    val screenWidthPx: Int
    val screenHeightPx: Int
    val keyboardHeightPx: Int
    val collectionSource: String // "calibration" or "user_selection"

    /** Layout the trace was drawn on (e.g. "latn_qwerty_us", "cyrl_jcuken_ru") or [UNKNOWN]. */
    val layoutName: String

    /** Decoder that produced the suggestions: [ENGINE_NEURAL], [ENGINE_GEOMETRIC], [ENGINE_CTC] or [UNKNOWN]. */
    val engine: String
    private val tracePoints: MutableList<TracePoint> = mutableListOf()
    private val registeredKeys: MutableList<String> = mutableListOf()
    private var keyboardOffsetY = 0 // Y offset of keyboard from top of screen
    private var lastAbsoluteTimestamp: Long // Track last point's absolute timestamp for delta calculation

    // ── Enrichment (Swipe Playground, 2026-09-03) ────────────────────────────────────────
    // Optional per-trace context needed for a useful decoder/occlusion dataset. All three
    // are absent on legacy rows and older exports; readers must treat null as "not recorded".
    private var keyGeometry: List<KeyGeom>? = null
    private var candidates: List<RankedCandidate>? = null
    private var decodeLatencyMs: Long? = null

    // Constructor for new swipe data
    @JvmOverloads
    constructor(
        targetWord: String,
        collectionSource: String,
        screenWidth: Int,
        screenHeight: Int,
        keyboardHeight: Int,
        layoutName: String = UNKNOWN,
        engine: String = UNKNOWN
    ) {
        this.traceId = UUID.randomUUID().toString()
        this.targetWord = targetWord.lowercase()
        this.timestampUtc = System.currentTimeMillis()
        this.screenWidthPx = screenWidth
        this.screenHeightPx = screenHeight
        this.keyboardHeightPx = keyboardHeight
        this.collectionSource = collectionSource
        this.layoutName = normalizeProvenance(layoutName)
        this.engine = normalizeProvenance(engine)
        // The keyboard view is bottom-aligned, so its screen-frame origin is
        // screen_height − keyboard_height (device-confirmed 2026-09-03: 1525 on the Pixel).
        // Derived here because NO capture site ever called setKeyboardDimensions — every
        // exported row misreported keyboard_offset_y: 0. setKeyboardDimensions still
        // overrides for a caller that has a directly measured origin.
        this.keyboardOffsetY = (screenHeight - keyboardHeight).coerceAtLeast(0)
        this.lastAbsoluteTimestamp = timestampUtc // Initialize to start time
    }

    // Constructor from JSON (for loading stored data)
    @Throws(JSONException::class)
    constructor(json: JSONObject) {
        this.traceId = json.getString("trace_id")
        this.targetWord = json.getString("target_word")

        val metadata = json.getJSONObject("metadata")
        this.timestampUtc = metadata.getLong("timestamp_utc")
        this.screenWidthPx = metadata.getInt("screen_width_px")
        this.screenHeightPx = metadata.getInt("screen_height_px")
        this.keyboardHeightPx = metadata.getInt("keyboard_height_px")
        this.collectionSource = metadata.getString("collection_source")
        // Backwards compatibility: rows stored before provenance tagging have neither key.
        // optString's own default only covers a MISSING key, so normalize explicit nulls /
        // empty strings too — an untagged row must read UNKNOWN, never "" (audit n-2).
        this.layoutName = normalizeProvenance(metadata.optString("layout_name", UNKNOWN))
        this.engine = normalizeProvenance(metadata.optString("engine", UNKNOWN))

        // Load trace points
        val pointsArray = json.getJSONArray("trace_points")
        for (i in 0 until pointsArray.length()) {
            val point = pointsArray.getJSONObject(i)
            tracePoints.add(
                TracePoint(
                    point.getDouble("x").toFloat(),
                    point.getDouble("y").toFloat(),
                    point.getLong("t_delta_ms")
                )
            )
        }

        // Load registered keys
        val keysArray = json.getJSONArray("registered_keys")
        for (i in 0 until keysArray.length()) {
            registeredKeys.add(keysArray.getString(i))
        }

        // Enrichment fields (Swipe Playground): all optional — rows recorded before the
        // playground existed simply have none, and load with null (never a crash).
        json.optJSONArray("key_geometry")?.let { geomArray ->
            val geoms = ArrayList<KeyGeom>(geomArray.length())
            for (i in 0 until geomArray.length()) {
                val g = geomArray.getJSONObject(i)
                geoms.add(
                    KeyGeom(
                        g.getString("k"),
                        g.getDouble("l").toFloat(),
                        g.getDouble("t").toFloat(),
                        g.getDouble("r").toFloat(),
                        g.getDouble("b").toFloat()
                    )
                )
            }
            keyGeometry = geoms
        }
        json.optJSONArray("candidates")?.let { candArray ->
            val cands = ArrayList<RankedCandidate>(candArray.length())
            for (i in 0 until candArray.length()) {
                val c = candArray.getJSONObject(i)
                cands.add(RankedCandidate(c.getString("word"), c.getInt("score")))
            }
            candidates = cands
        }
        if (json.has("decode_latency_ms")) {
            decodeLatencyMs = json.getLong("decode_latency_ms")
        }
        keyboardOffsetY = metadata.optInt("keyboard_offset_y", 0)

        // Reconstruct last absolute timestamp from deltas
        this.lastAbsoluteTimestamp = timestampUtc
        for (point in tracePoints) {
            lastAbsoluteTimestamp += point.tDeltaMs
        }
    }

    /**
     * Add a raw trace point (will be normalized)
     */
    fun addRawPoint(rawX: Float, rawY: Float, timestamp: Long) {
        // Normalize coordinates to [0, 1] range
        val normalizedX = rawX / screenWidthPx
        val normalizedY = rawY / screenHeightPx

        // Calculate time delta from last absolute timestamp
        val deltaMs = timestamp - lastAbsoluteTimestamp

        // Update last absolute timestamp for next point
        lastAbsoluteTimestamp = timestamp

        tracePoints.add(TracePoint(normalizedX, normalizedY, deltaMs))
    }

    /**
     * Add a registered key from the swipe path
     */
    fun addRegisteredKey(key: String) {
        // Avoid consecutive duplicates
        if (registeredKeys.isEmpty() || registeredKeys.last() != key) {
            registeredKeys.add(key.lowercase())
        }
    }

    /**
     * Set keyboard dimensions for accurate position tracking
     */
    fun setKeyboardDimensions(screenWidth: Int, keyboardHeight: Int, keyboardOffsetY: Int) {
        this.keyboardOffsetY = keyboardOffsetY
        // Note: screenWidth and keyboardHeight are already set in constructor
        // This method mainly records the Y offset for position normalization
    }

    /**
     * Screen-frame Y origin of the keyboard view in px. Derived at construction as
     * `screen_height − keyboard_height`; 0 on legacy rows recorded while the
     * keyboard_offset_y misreport shipped (or on pre-offset imports) — readers must treat
     * 0 as "not recorded", consistent with the other optional metadata.
     */
    fun getKeyboardOffsetY(): Int = keyboardOffsetY

    /**
     * Convert to JSON for storage and export
     */
    @Throws(JSONException::class)
    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("trace_id", traceId)
        json.put("target_word", targetWord)

        // Metadata
        val metadata = JSONObject().apply {
            put("timestamp_utc", timestampUtc)
            put("screen_width_px", screenWidthPx)
            put("screen_height_px", screenHeightPx)
            put("keyboard_height_px", keyboardHeightPx)
            put("keyboard_offset_y", keyboardOffsetY)
            put("collection_source", collectionSource)
            // Provenance (audit n-2): which layout the trace was drawn on and which decoder
            // produced its suggestions. Consumers of an export MUST filter on these before
            // training — a geometric/non-QWERTY trace is not QWERTY-transformer training data.
            put("layout_name", layoutName)
            put("engine", engine)
        }
        json.put("metadata", metadata)

        // Trace points
        val pointsArray = JSONArray()
        for (point in tracePoints) {
            val p = JSONObject().apply {
                put("x", point.x)
                put("y", point.y)
                put("t_delta_ms", point.tDeltaMs)
            }
            pointsArray.put(p)
        }
        json.put("trace_points", pointsArray)

        // Registered keys
        val keysArray = JSONArray()
        for (key in registeredKeys) {
            keysArray.put(key)
        }
        json.put("registered_keys", keysArray)

        // Enrichment (Swipe Playground): key hit-test boxes of the layout the trace was
        // drawn on (raw px, keyboard-view frame — see [KeyGeom]), the suggestion-bar
        // ranking with scores, and swipe-end→results latency. Omitted entirely when not
        // recorded so legacy consumers see no shape change.
        keyGeometry?.let { geoms ->
            val geomArray = JSONArray()
            for (g in geoms) {
                geomArray.put(
                    JSONObject().apply {
                        put("k", g.label)
                        put("l", g.left.toDouble())
                        put("t", g.top.toDouble())
                        put("r", g.right.toDouble())
                        put("b", g.bottom.toDouble())
                    }
                )
            }
            json.put("key_geometry", geomArray)
        }
        candidates?.let { cands ->
            val candArray = JSONArray()
            for (c in cands) {
                candArray.put(JSONObject().apply { put("word", c.word); put("score", c.score) })
            }
            json.put("candidates", candArray)
        }
        decodeLatencyMs?.let { json.put("decode_latency_ms", it) }

        return json
    }

    /**
     * Validate data quality before storage
     */
    fun isValid(): Boolean {
        // Must have at least 2 points for a valid swipe
        if (tracePoints.size < 2) return false

        // Must have at least 2 registered keys
        if (registeredKeys.size < 2) return false

        // Target word must not be empty
        if (targetWord.isEmpty()) return false

        // Check for reasonable normalized values
        for (point in tracePoints) {
            if (point.x !in 0f..1f || point.y !in 0f..1f) return false
        }

        return true
    }

    /**
     * Calculate statistics for this swipe
     */
    fun calculateStatistics(): SwipeStatistics? {
        if (tracePoints.size < 2) return null

        var totalDistance = 0f
        var totalTime = 0L

        for (i in 1 until tracePoints.size) {
            val prev = tracePoints[i - 1]
            val curr = tracePoints[i]

            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            totalDistance += sqrt(dx * dx + dy * dy)
            totalTime += curr.tDeltaMs
        }

        // Calculate straightness ratio
        val start = tracePoints[0]
        val end = tracePoints[tracePoints.size - 1]
        val directDistance = sqrt((end.x - start.x).pow(2) + (end.y - start.y).pow(2))
        val straightnessRatio = if (totalDistance > 0) directDistance / totalDistance else 0f

        return SwipeStatistics(
            tracePoints.size,
            totalDistance,
            totalTime,
            straightnessRatio,
            registeredKeys.size
        )
    }

    // Getters with defensive copies
    fun getTracePoints(): List<TracePoint> = tracePoints.toList()
    fun getRegisteredKeys(): List<String> = registeredKeys.toList()

    // ── Enrichment accessors (Swipe Playground) ──────────────────────────────────────────

    /** Attach the active layout's per-key hit-test boxes (captured at swipe time). */
    fun setKeyGeometry(geometry: List<KeyGeom>) {
        keyGeometry = geometry.toList()
    }

    fun getKeyGeometry(): List<KeyGeom>? = keyGeometry?.toList()

    /**
     * Attach the suggestion-bar ranking (parallel word/score lists, rank = index).
     * Mismatched lengths are rejected rather than silently zipped short — a
     * misaligned ranking is worse for analysis than none.
     */
    fun setCandidates(words: List<String>, scores: List<Int>) {
        if (words.size != scores.size) return
        candidates = words.indices.map { RankedCandidate(words[it], scores[it]) }
    }

    fun getCandidates(): List<RankedCandidate>? = candidates?.toList()

    /** Attach the swipe-end → results-displayed latency (decode + routing), ms. */
    fun setDecodeLatencyMs(latencyMs: Long) {
        decodeLatencyMs = latencyMs
    }

    fun getDecodeLatencyMs(): Long? = decodeLatencyMs

    /**
     * Copy carrying EVERYTHING — points (normalized values verbatim, no re-normalization
     * round-trip), registered keys, provenance, and the enrichment fields — under a new
     * trace id, target word and collection source. Used when the word a trace belongs to
     * is only known at selection/commit time (the capture-time object was created with an
     * empty target word).
     */
    fun copyWith(targetWord: String, collectionSource: String): SwipeMLData {
        val copy = SwipeMLData(
            targetWord, collectionSource,
            screenWidthPx, screenHeightPx, keyboardHeightPx,
            layoutName, engine
        )
        copy.tracePoints.addAll(tracePoints)
        copy.registeredKeys.addAll(registeredKeys)
        copy.keyboardOffsetY = keyboardOffsetY
        copy.keyGeometry = keyGeometry
        copy.candidates = candidates
        copy.decodeLatencyMs = decodeLatencyMs
        return copy
    }

    /**
     * Inner class for normalized trace points
     */
    data class TracePoint(
        @JvmField val x: Float,       // Normalized [0, 1]
        @JvmField val y: Float,       // Normalized [0, 1]
        @JvmField val tDeltaMs: Long  // Time delta from previous point
    )

    /**
     * Statistics for analysis
     */
    data class SwipeStatistics(
        @JvmField val pointCount: Int,
        @JvmField val totalDistance: Float,
        @JvmField val totalTimeMs: Long,
        @JvmField val straightnessRatio: Float,
        @JvmField val keyCount: Int
    )
}
