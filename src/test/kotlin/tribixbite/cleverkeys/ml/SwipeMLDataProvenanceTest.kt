package tribixbite.cleverkeys.ml

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * Pure-JVM pins for the ML-trace PROVENANCE fields (WP9 audit n-2, 2026-08-11):
 * [SwipeMLData.layoutName] + [SwipeMLData.engine].
 *
 * Why they exist: since the geometric engine shipped, a swipe can be drawn on a non-QWERTY
 * layout (Dvorak, ЙЦУКЕН, AZERTY) and decoded by SHARK2 instead of a QWERTY-trained
 * model. Untagged, those traces are indistinguishable from QWERTY ones in an export and
 * would silently contaminate any training corpus built from it.
 *
 * [SwipeMLData] is pure (org.json + java.util only), so this runs in `runPureTests`.
 */
class SwipeMLDataProvenanceTest {

    private fun sample(
        layoutName: String = "cyrl_jcuken_ru",
        engine: String = SwipeMLData.ENGINE_GEOMETRIC,
    ): SwipeMLData = SwipeMLData(
        "привет", "user_selection", 1080, 1920, 480, layoutName, engine
    ).apply {
        addRawPoint(100f, 1500f, 1_000L)
        addRawPoint(200f, 1520f, 1_020L)
        addRegisteredKey("п")
        addRegisteredKey("р")
    }

    @Test
    fun provenanceIsSerializedIntoMetadata() {
        val metadata = sample().toJSON().getJSONObject("metadata")

        assertThat(metadata.getString("layout_name")).isEqualTo("cyrl_jcuken_ru")
        assertThat(metadata.getString("engine")).isEqualTo("geometric")
    }

    @Test
    fun provenanceSurvivesJsonRoundTrip() {
        val original = sample()
        val restored = SwipeMLData(original.toJSON())

        assertThat(restored.layoutName).isEqualTo(original.layoutName)
        assertThat(restored.engine).isEqualTo(original.engine)
        // Existing fields must be untouched by the schema addition.
        assertThat(restored.traceId).isEqualTo(original.traceId)
        assertThat(restored.targetWord).isEqualTo(original.targetWord)
        assertThat(restored.collectionSource).isEqualTo(original.collectionSource)
        assertThat(restored.getTracePoints()).hasSize(original.getTracePoints().size)
        assertThat(restored.getRegisteredKeys()).isEqualTo(original.getRegisteredKeys())
    }

    /**
     * BACKWARDS COMPATIBILITY: rows written before tagging (and imports of older exports) have
     * neither key. They must load — as [SwipeMLData.UNKNOWN], never as a crash or a lie.
     */
    @Test
    fun legacyRowWithoutProvenanceLoadsAsUnknown() {
        val legacy = JSONObject(
            """
            {
              "trace_id": "legacy-trace-1",
              "target_word": "hello",
              "metadata": {
                "timestamp_utc": 1700000000000,
                "screen_width_px": 1080,
                "screen_height_px": 1920,
                "keyboard_height_px": 480,
                "keyboard_offset_y": 0,
                "collection_source": "user_selection"
              },
              "trace_points": [
                {"x": 0.1, "y": 0.8, "t_delta_ms": 0},
                {"x": 0.2, "y": 0.8, "t_delta_ms": 20}
              ],
              "registered_keys": ["h", "e"]
            }
            """.trimIndent()
        )

        val restored = SwipeMLData(legacy)

        assertThat(restored.layoutName).isEqualTo(SwipeMLData.UNKNOWN)
        assertThat(restored.engine).isEqualTo(SwipeMLData.UNKNOWN)
        assertThat(restored.targetWord).isEqualTo("hello")
        assertThat(restored.getTracePoints()).hasSize(2)
    }

    /** Blank/absent provenance normalizes to `unknown` so an export never carries "". */
    @Test
    fun blankProvenanceNormalizesToUnknown() {
        val blank = SwipeMLData("hi", "user_selection", 1080, 1920, 480, "", "  ")

        assertThat(blank.layoutName).isEqualTo(SwipeMLData.UNKNOWN)
        assertThat(blank.engine).isEqualTo(SwipeMLData.UNKNOWN)

        val defaulted = SwipeMLData("hi", "user_selection", 1080, 1920, 480)
        assertThat(defaulted.layoutName).isEqualTo(SwipeMLData.UNKNOWN)
        assertThat(defaulted.engine).isEqualTo(SwipeMLData.UNKNOWN)
    }

    /**
     * The engine tags are distinct and stable — exports filter on these strings.
     * ENGINE_NEURAL is retained after the 2026-08-18 engine removal because rows written by
     * earlier versions still carry it; nothing writes it any more.
     */
    @Test
    fun engineTagsAreStableAndDistinct() {
        assertThat(SwipeMLData.ENGINE_NEURAL).isEqualTo("neural")
        assertThat(SwipeMLData.ENGINE_GEOMETRIC).isEqualTo("geometric")
        assertThat(SwipeMLData.ENGINE_CTC).isEqualTo("ctc")
        assertThat(SwipeMLData.UNKNOWN).isEqualTo("unknown")

        val ctc = SwipeMLData("hi", "user_selection", 1080, 1920, 480, "latn_qwerty_us", SwipeMLData.ENGINE_CTC)
        val geo = SwipeMLData("hi", "user_selection", 1080, 1920, 480, "latn_dvorak", SwipeMLData.ENGINE_GEOMETRIC)
        assertThat(ctc.engine).isNotEqualTo(geo.engine)
        assertThat(ctc.layoutName).isNotEqualTo(geo.layoutName)
    }
}
