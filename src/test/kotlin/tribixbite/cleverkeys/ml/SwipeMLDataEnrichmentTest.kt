package tribixbite.cleverkeys.ml

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * Pure-JVM pins for the Swipe Playground trace enrichment (2026-09-03):
 * [SwipeMLData]'s key geometry, candidate ranking and decode latency, plus [SwipeMLData.copyWith]
 * (the selection-time copy the playground persists).
 *
 * Why they exist: the `finger_occlusion_offset` A/B needs real-device traces that carry the
 * layout's per-key boxes AND the ranking the decoder produced — without a serialization pin,
 * a silent JSON-shape drift would strand every previously donated trace.
 *
 * [SwipeMLData] is pure (org.json + java.util only), so this runs in `runPureTests`.
 */
class SwipeMLDataEnrichmentTest {

    private fun sample(): SwipeMLData = SwipeMLData(
        "hello", "playground", 1080, 1920, 480, "latn_qwerty_us", SwipeMLData.ENGINE_CTC
    ).apply {
        addRawPoint(100f, 1500f, 1_000L)
        addRawPoint(200f, 1520f, 1_020L)
        addRawPoint(300f, 1540f, 1_060L)
        addRegisteredKey("h")
        addRegisteredKey("l")
        addRegisteredKey("o")
        setKeyGeometry(
            listOf(
                SwipeMLData.KeyGeom("q", 0f, 10f, 108f, 130f),
                SwipeMLData.KeyGeom("w", 108f, 10f, 216f, 130f),
            )
        )
        setCandidates(listOf("hello", "hells", "hello's"), listOf(950, 400, 120))
        setDecodeLatencyMs(87L)
    }

    @Test
    fun enrichmentIsSerialized() {
        val json = sample().toJSON()

        val geom = json.getJSONArray("key_geometry")
        assertThat(geom.length()).isEqualTo(2)
        val q = geom.getJSONObject(0)
        assertThat(q.getString("k")).isEqualTo("q")
        assertThat(q.getDouble("l")).isEqualTo(0.0)
        assertThat(q.getDouble("r")).isWithin(1e-4).of(108.0)

        val cands = json.getJSONArray("candidates")
        assertThat(cands.length()).isEqualTo(3)
        assertThat(cands.getJSONObject(0).getString("word")).isEqualTo("hello")
        assertThat(cands.getJSONObject(0).getInt("score")).isEqualTo(950)
        assertThat(cands.getJSONObject(2).getString("word")).isEqualTo("hello's")

        assertThat(json.getLong("decode_latency_ms")).isEqualTo(87L)
    }

    @Test
    fun enrichmentSurvivesJsonRoundTrip() {
        val original = sample()
        val restored = SwipeMLData(original.toJSON())

        assertThat(restored.getKeyGeometry()).isEqualTo(original.getKeyGeometry())
        assertThat(restored.getCandidates()).isEqualTo(original.getCandidates())
        assertThat(restored.getDecodeLatencyMs()).isEqualTo(87L)
        // Pre-existing fields untouched by the additions.
        assertThat(restored.targetWord).isEqualTo("hello")
        assertThat(restored.getTracePoints()).isEqualTo(original.getTracePoints())
        assertThat(restored.getRegisteredKeys()).isEqualTo(original.getRegisteredKeys())
    }

    /**
     * BACKWARDS COMPATIBILITY: rows recorded before the playground existed (and imports of
     * older exports) have none of the enrichment keys. They must load with null enrichment —
     * never a crash, never fabricated empty lists pretending geometry was captured.
     */
    @Test
    fun legacyRowWithoutEnrichmentLoadsAsNull() {
        val legacy = SwipeMLData(
            "cat", "user_selection", 1080, 1920, 480
        ).apply {
            addRawPoint(1f, 2f, 100L)
            addRawPoint(3f, 4f, 120L)
            addRegisteredKey("c")
            addRegisteredKey("t")
        }
        // Serialize WITHOUT enrichment, then strip nothing — the JSON simply lacks the keys.
        val json = legacy.toJSON()
        assertThat(json.has("key_geometry")).isFalse()
        assertThat(json.has("candidates")).isFalse()
        assertThat(json.has("decode_latency_ms")).isFalse()

        val restored = SwipeMLData(JSONObject(json.toString()))
        assertThat(restored.getKeyGeometry()).isNull()
        assertThat(restored.getCandidates()).isNull()
        assertThat(restored.getDecodeLatencyMs()).isNull()
    }

    /** A misaligned ranking is rejected outright — worse to zip short than to store none. */
    @Test
    fun mismatchedCandidateListsAreRejected() {
        val data = sample()
        data.setCandidates(listOf("one", "two"), listOf(10)) // lengths differ
        // The previous (valid) ranking must remain.
        assertThat(data.getCandidates()).hasSize(3)
    }

    /**
     * `keyboard_offset_y` misreport (device-confirmed 2026-09-03): every exported row carried
     * `keyboard_offset_y: 0` because no capture site ever called `setKeyboardDimensions`. The
     * keyboard is bottom-aligned, so its true screen-frame origin is
     * `screen_height − keyboard_height` (1525 on the Pixel) — now derived at construction.
     */
    @Test
    fun keyboardOffsetIsDerivedAndSurvivesJsonRoundTrip() {
        val data = sample()
        // 1920 screen − 480 keyboard = 1440 origin.
        assertThat(data.getKeyboardOffsetY()).isEqualTo(1440)

        val json = data.toJSON()
        assertThat(json.getJSONObject("metadata").getInt("keyboard_offset_y")).isEqualTo(1440)

        val restored = SwipeMLData(JSONObject(json.toString()))
        assertThat(restored.getKeyboardOffsetY()).isEqualTo(1440)
        // The reload preserves the RECORDED value; it does not re-derive.
        assertThat(restored.toJSON().getJSONObject("metadata").getInt("keyboard_offset_y"))
            .isEqualTo(1440)
    }

    /** Legacy rows: a missing key or a recorded 0 must read back as 0, never crash. */
    @Test
    fun legacyRowKeyboardOffsetReadsAsZero() {
        val json = sample().toJSON()

        // Missing key entirely (pre-offset exports).
        json.getJSONObject("metadata").remove("keyboard_offset_y")
        assertThat(SwipeMLData(JSONObject(json.toString())).getKeyboardOffsetY()).isEqualTo(0)

        // Explicit 0 (every row captured while the bug shipped).
        json.getJSONObject("metadata").put("keyboard_offset_y", 0)
        assertThat(SwipeMLData(JSONObject(json.toString())).getKeyboardOffsetY()).isEqualTo(0)
    }

    @Test
    fun copyWithCarriesEverythingUnderNewIdentity() {
        val original = sample()
        val copy = original.copyWith("world", "playground")

        assertThat(copy.traceId).isNotEqualTo(original.traceId)
        assertThat(copy.targetWord).isEqualTo("world")
        assertThat(copy.collectionSource).isEqualTo("playground")
        // Provenance + dimensions carried over.
        assertThat(copy.layoutName).isEqualTo("latn_qwerty_us")
        assertThat(copy.engine).isEqualTo(SwipeMLData.ENGINE_CTC)
        assertThat(copy.screenWidthPx).isEqualTo(1080)
        assertThat(copy.keyboardHeightPx).isEqualTo(480)
        assertThat(copy.getKeyboardOffsetY()).isEqualTo(1440)
        // Points copied VERBATIM (no denormalize/renormalize round-trip drift).
        assertThat(copy.getTracePoints()).isEqualTo(original.getTracePoints())
        assertThat(copy.getRegisteredKeys()).isEqualTo(original.getRegisteredKeys())
        // Enrichment carried over.
        assertThat(copy.getKeyGeometry()).isEqualTo(original.getKeyGeometry())
        assertThat(copy.getCandidates()).isEqualTo(original.getCandidates())
        assertThat(copy.getDecodeLatencyMs()).isEqualTo(original.getDecodeLatencyMs())
        // And the copy is storable.
        assertThat(copy.isValid()).isTrue()
    }
}
