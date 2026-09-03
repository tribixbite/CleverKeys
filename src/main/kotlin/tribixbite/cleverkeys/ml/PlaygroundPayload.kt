package tribixbite.cleverkeys.ml

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-JVM payload format for the Swipe Playground live panel (2026-09-03).
 *
 * One payload describes ONE decoded swipe: the committed word, the suggestion-bar
 * ranking with scores, the decoder engine/layout provenance, latency, and whether/where
 * the trace was persisted. Built IME-side by [PlaygroundTraceRecorder], broadcast to
 * `SwipeDebugActivity`, which renders it via [formatCandidates]/[formatLogBlock].
 *
 * Kept free of Android imports (org.json only) so the format and the ranking
 * formatting are pinned by pure tests (`PlaygroundPayloadTest`) on the JVM.
 */
object PlaygroundPayload {

    /** Where the trace row landed: the playground store, the global ML collection, or nowhere. */
    const val STORED_PLAYGROUND = "playground"
    const val STORED_GLOBAL = "user_selection"
    const val STORED_NONE = "none"

    /**
     * Build the wire payload for one decoded swipe.
     *
     * @param data the captured trace (null when the coordinator had no capture — the
     *   payload still renders the ranking so the panel never goes blank mid-session)
     * @param committedWord the word actually committed to the editor (post-autocorrect)
     * @param engineWordCount how many leading entries of the ranking came from the
     *   decoder (entries past it are augmentations, e.g. possessive forms)
     * @param storedAs one of [STORED_PLAYGROUND]/[STORED_GLOBAL]/[STORED_NONE]
     */
    fun build(
        data: SwipeMLData?,
        committedWord: String,
        engineWordCount: Int,
        storedAs: String
    ): JSONObject = JSONObject().apply {
        put("committed_word", committedWord)
        put("engine", data?.engine ?: SwipeMLData.UNKNOWN)
        put("layout", data?.layoutName ?: SwipeMLData.UNKNOWN)
        put("engine_word_count", engineWordCount)
        put("stored_as", storedAs)
        data?.getDecodeLatencyMs()?.let { put("latency_ms", it) }
        put("point_count", data?.getTracePoints()?.size ?: 0)
        put("key_geometry_count", data?.getKeyGeometry()?.size ?: 0)
        val candArray = JSONArray()
        data?.getCandidates()?.forEach { c ->
            candArray.put(JSONObject().apply { put("word", c.word); put("score", c.score) })
        }
        put("candidates", candArray)
    }

    /**
     * The ranked candidate list as displayed in the playground panel, one line per
     * candidate: `rank. word  (score)`, engine entries plain, augmented entries marked.
     * Empty ranking renders a single explicit line rather than an empty string.
     */
    fun formatCandidates(payload: JSONObject): String {
        val candidates = payload.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return "(no candidates)"
        val engineWordCount = payload.optInt("engine_word_count", candidates.length())
        val sb = StringBuilder()
        for (i in 0 until candidates.length()) {
            val c = candidates.getJSONObject(i)
            if (i > 0) sb.append('\n')
            sb.append(i + 1).append(". ").append(c.getString("word"))
                .append("  (").append(c.getInt("score")).append(')')
            if (i >= engineWordCount) sb.append("  [augmented]")
        }
        return sb.toString()
    }

    /** Single-line summary: engine, latency, capture size, persistence destination. */
    fun formatMeta(payload: JSONObject): String {
        val sb = StringBuilder()
        sb.append("engine=").append(payload.optString("engine", SwipeMLData.UNKNOWN))
        sb.append("  layout=").append(payload.optString("layout", SwipeMLData.UNKNOWN))
        if (payload.has("latency_ms")) sb.append("  ").append(payload.getLong("latency_ms")).append(" ms")
        sb.append("  pts=").append(payload.optInt("point_count", 0))
        sb.append("  keys=").append(payload.optInt("key_geometry_count", 0))
        sb.append(
            when (payload.optString("stored_as", STORED_NONE)) {
                STORED_PLAYGROUND -> "  [recorded: playground]"
                STORED_GLOBAL -> "  [recorded: global ML store]"
                else -> "  [not recorded]"
            }
        )
        return sb.toString()
    }

    /** Full text block appended to the scrolling debug log for one swipe. */
    fun formatLogBlock(payload: JSONObject): String {
        val sb = StringBuilder()
        sb.append("── SWIPE → \"")
            .append(payload.optString("committed_word", "?"))
            .append("\" ──\n")
        sb.append(formatMeta(payload)).append('\n')
        sb.append(formatCandidates(payload)).append('\n')
        return sb.toString()
    }
}
