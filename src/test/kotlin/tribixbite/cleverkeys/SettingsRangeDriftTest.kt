package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.backup.PrefValue
import tribixbite.cleverkeys.backup.SettingsValidation
import java.io.File
// SettingsRanges is in this package (top-level in Config.kt)

/**
 * F-3 / F-4 / F-10 / G-5 (comprehensive audit 2026-09-06): the
 * range-mismatch class. Three places restate a key's accepted range —
 * the settings slider, the import validator, and the Config read-site
 * clamp — and they drifted independently:
 *
 *  - F-4: `longpress_interval` (UI 25..200 vs validator 5..100),
 *    `character_size` (UI 0.5..2.0 vs validator 0.75..1.5),
 *    `custom_border_line_width` (UI 0..10 vs validator 0..5) — the user's
 *    own exported setting came back "skipped: out of range".
 *  - F-3: the space-slider sensitivity slider allowed 0%, which becomes
 *    `slide_step_px = 0f` and a divide-by-zero in Pointers.Sliding
 *    (right-swipe emits repeat −1: the cursor moves LEFT).
 *  - F-10: `clipboard_max_item_size_kb` floors disagreed (UI/state 64 vs
 *    Config 1) — an imported 32 displayed as "64KB" but enforced 32KB.
 *  - G-5: `clipboard_history_limit`'s 0..500 bound never ran for the
 *    CANONICAL string form ("9999"/"abc"/"-5" imported cleanly).
 *
 * The ratchet: [SettingsRanges] is the single source of truth per key,
 * consumed by the slider, the validator, and the Config clamp. This test
 * pins (a) the validator behavior at the previously-lost values and
 * (b) that every consumer site references the shared constant.
 */
class SettingsRangeDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")
    private fun read(rel: String) = File(srcRoot, rel).readText()

    // ── F-4: values the settings UI legitimately produces must import ──

    @Test
    fun longpressInterval_uiMaximum_importsCleanly() {
        assertWithMessage("longpress_interval=150 is UI-selectable (25..200) and must validate")
            .that(SettingsValidation.validate("longpress_interval", PrefValue.IntV(150)))
            .isNull()
    }

    @Test
    fun characterSize_uiMaximum_importsCleanly() {
        assertWithMessage("character_size=1.8 is UI-selectable (0.5..2.0) and must validate")
            .that(SettingsValidation.validate("character_size", PrefValue.FloatV(1.8f)))
            .isNull()
    }

    @Test
    fun customBorderLineWidth_uiMaximum_importsCleanly() {
        assertWithMessage("custom_border_line_width=8 is UI-selectable (0..10) and must validate")
            .that(SettingsValidation.validate("custom_border_line_width", PrefValue.FloatV(8f)))
            .isNull()
    }

    // ── G-5: the canonical STRING form of clipboard_history_limit must
    //         honor the same 0..500 bound the int form already enforces ──

    @Test
    fun clipboardHistoryLimit_stringForm_overRange_isRejected() {
        assertWithMessage("\"9999\" exceeds the deliberate 0..500 bound and must be rejected")
            .that(SettingsValidation.validate("clipboard_history_limit", PrefValue.Str("9999")))
            .isNotNull()
    }

    @Test
    fun clipboardHistoryLimit_stringForm_negative_isRejected() {
        assertThat(SettingsValidation.validate("clipboard_history_limit", PrefValue.Str("-5")))
            .isNotNull()
    }

    @Test
    fun clipboardHistoryLimit_stringForm_nonNumeric_isRejected() {
        assertThat(SettingsValidation.validate("clipboard_history_limit", PrefValue.Str("abc")))
            .isNotNull()
    }

    @Test
    fun clipboardHistoryLimit_stringForm_inRange_isAccepted() {
        for (v in listOf("0", "50", "500")) {
            assertWithMessage("\"$v\" is inside 0..500 and must validate")
                .that(SettingsValidation.validate("clipboard_history_limit", PrefValue.Str(v)))
                .isNull()
        }
    }

    // ── Shared-constant consumption: every restatement of a drifted range
    //    must read SettingsRanges, so the three sites cannot diverge again ──

    private fun assertSiteReferences(rel: String, constant: String) {
        assertWithMessage("$rel must consume SettingsRanges.$constant (shared-range ratchet)")
            .that(read(rel)).contains("SettingsRanges.$constant")
    }

    @Test
    fun sliderSites_consumeSharedRanges() {
        assertSiteReferences("ui/settings/sections/InputBehaviorSection.kt", "LONGPRESS_INTERVAL")
        assertSiteReferences("ui/settings/sections/InputBehaviorSection.kt", "SLIDER_SENSITIVITY_PERCENT")
        assertSiteReferences("ui/settings/sections/AppearanceSection.kt", "CHARACTER_SIZE")
        assertSiteReferences("ui/settings/sections/AppearanceSection.kt", "CUSTOM_BORDER_LINE_WIDTH")
        assertSiteReferences("ui/settings/sections/ClipboardSection.kt", "CLIPBOARD_HISTORY_LIMIT")
        assertSiteReferences("ui/settings/sections/ClipboardSection.kt", "CLIPBOARD_MAX_ITEM_SIZE_KB")
    }

    @Test
    fun validatorSites_consumeSharedRanges() {
        val validation = read("backup/SettingsValidation.kt")
        for (c in listOf(
            "LONGPRESS_INTERVAL", "CHARACTER_SIZE", "CUSTOM_BORDER_LINE_WIDTH",
            "CLIPBOARD_HISTORY_LIMIT",
        )) {
            assertWithMessage("SettingsValidation must consume SettingsRanges.$c")
                .that(validation).contains("SettingsRanges.$c")
        }
    }

    /** F-3: the Config read site must floor slider sensitivity above zero. */
    @Test
    fun configReadSites_consumeSharedRanges() {
        val config = read("Config.kt")
        for (c in listOf(
            "SLIDER_SENSITIVITY_PERCENT",   // F-3: kills the ±Infinity slide_step_px chain
            "CLIPBOARD_HISTORY_LIMIT",      // G-5: read-site clamp for pre-fix imports
            "CLIPBOARD_MAX_ITEM_SIZE_KB",   // F-10: same floor as the UI/state side
            "LONGPRESS_INTERVAL",
            "CHARACTER_SIZE",
        )) {
            assertWithMessage("Config.refresh must clamp through SettingsRanges.$c")
                .that(config).contains("SettingsRanges.$c")
        }
    }

    /** F-10: the settings-state read site must share the Config clamp. */
    @Test
    fun settingsPersistence_maxItemSize_consumesSharedRange() {
        assertWithMessage("SettingsPersistence must clamp clipboard_max_item_size_kb through the shared range")
            .that(read("ui/settings/SettingsPersistence.kt"))
            .contains("SettingsRanges.CLIPBOARD_MAX_ITEM_SIZE_KB")
    }

    // ── Value agreement: the validator's tables must BE the shared constants ──

    @Test
    fun validatorRanges_equalSharedConstants() {
        assertThat(SettingsValidation.intRangeFor("longpress_interval"))
            .isEqualTo(SettingsRanges.LONGPRESS_INTERVAL)
        assertThat(SettingsValidation.intRangeFor("clipboard_history_limit"))
            .isEqualTo(SettingsRanges.CLIPBOARD_HISTORY_LIMIT)
        // Float ranges have no lookup table — pin the boundary behavior instead.
        assertThat(SettingsValidation.validate("character_size", PrefValue.FloatV(SettingsRanges.CHARACTER_SIZE.start))).isNull()
        assertThat(SettingsValidation.validate("character_size", PrefValue.FloatV(SettingsRanges.CHARACTER_SIZE.endInclusive))).isNull()
        assertThat(SettingsValidation.validate("character_size", PrefValue.FloatV(SettingsRanges.CHARACTER_SIZE.endInclusive + 0.01f))).isNotNull()
        assertThat(SettingsValidation.validate("custom_border_line_width", PrefValue.FloatV(SettingsRanges.CUSTOM_BORDER_LINE_WIDTH.endInclusive))).isNull()
        assertThat(SettingsValidation.validate("custom_border_line_width", PrefValue.FloatV(SettingsRanges.CUSTOM_BORDER_LINE_WIDTH.endInclusive + 0.01f))).isNotNull()
    }

    /** The slider floor may never regress to 0 (the F-3 divide-by-zero). */
    @Test
    fun sliderSensitivityFloor_isAboveZero() {
        assertThat(SettingsRanges.SLIDER_SENSITIVITY_PERCENT.first).isAtLeast(1)
    }

    // ── F-8 (maintainer decision 2026-09-08): clipboard_max_media_size_mb is
    //    surfaced, so it joins the shared-range ratchet — the new slider, the
    //    import validator and the Config read-site clamp must all consume ONE
    //    constant instead of restating 1..50 three times ──

    @Test
    fun clipboardMaxMediaSize_allSites_consumeSharedRange() {
        assertSiteReferences("ui/settings/sections/ClipboardSection.kt", "CLIPBOARD_MAX_MEDIA_SIZE_MB")
        assertWithMessage("SettingsValidation must consume SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB")
            .that(read("backup/SettingsValidation.kt"))
            .contains("SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB")
        assertWithMessage("Config.refresh must clamp through SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB")
            .that(read("Config.kt")).contains("SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB")
    }

    @Test
    fun clipboardMaxMediaSize_validatorRange_matchesTheHistoricConfigClamp() {
        // Config.refresh has always enforced coerceIn(1, 50) at the read site;
        // the shared constant (and therefore the validator table and the new
        // slider) must carry exactly that value so no stored value changes
        // meaning. Written against the literal deliberately: it pins the
        // VALUE, not merely that the three sites agree with each other.
        assertWithMessage("clipboard_max_media_size_mb must validate over the historic 1..50 clamp")
            .that(SettingsValidation.intRangeFor("clipboard_max_media_size_mb"))
            .isEqualTo(1..50)
    }

    // ── Audit M8/M9/M11 (2026-10-20): every slider-backed int key's validator range is the
    //    slider's own SettingsRanges constant, or a documented ImportEnvelope that contains it ──

    /** Slider-backed int keys → the SettingsRanges constant the slider uses. */
    private val sliderRanges: Map<String, IntRange> = buildMap {
        put("clipboard_pane_height_percent", SettingsRanges.CLIPBOARD_PANE_HEIGHT_PERCENT)
        put("gif_thumbnail_columns", SettingsRanges.GIF_THUMBNAIL_COLUMNS)
        put("ctc_beam_width", SettingsRanges.CTC_BEAM_WIDTH)
        put("onnx_xnnpack_threads", SettingsRanges.ONNX_XNNPACK_THREADS)
        put("finger_occlusion_offset", SettingsRanges.FINGER_OCCLUSION_OFFSET)
        put("swipe_smoothing_window", SettingsRanges.SWIPE_SMOOTHING_WINDOW)
        put("longpress_timeout", SettingsRanges.LONGPRESS_TIMEOUT)
        put("longpress_interval", SettingsRanges.LONGPRESS_INTERVAL)
        put("short_gesture_min_distance", SettingsRanges.SHORT_GESTURE_MIN_DISTANCE)
        put("short_gesture_max_distance", SettingsRanges.SHORT_GESTURE_MAX_DISTANCE)
        put("custom_border_radius", SettingsRanges.CUSTOM_BORDER_RADIUS)
        put("vibrate_duration", SettingsRanges.VIBRATE_DURATION)
        put("selection_delete_step_dp", SettingsRanges.SELECTION_DELETE_STEP_DP)
        put("clipboard_max_media_size_mb", SettingsRanges.CLIPBOARD_MAX_MEDIA_SIZE_MB)
        put("clipboard_history_limit", SettingsRanges.CLIPBOARD_HISTORY_LIMIT)
        put("circle_sensitivity", SettingsRanges.CIRCLE_SENSITIVITY)
        put("autocorrect_min_word_length", SettingsRanges.AUTOCORRECT_MIN_WORD_LENGTH)
        for (k in KeyboardHeightPrefs.ALL_KEYS) put(k, SettingsRanges.KEYBOARD_HEIGHT)
        for (k in listOf("label_brightness", "keyboard_opacity", "key_opacity", "key_activated_opacity", "suggestion_bar_opacity")) {
            put(k, SettingsRanges.OPACITY_PERCENT)
        }
        for (o in listOf("portrait", "landscape", "portrait_unfolded", "landscape_unfolded")) {
            put("margin_bottom_$o", SettingsRanges.MARGIN_BOTTOM_PERCENT)
            put("margin_left_$o", SettingsRanges.MARGIN_SIDE_PERCENT)
            put("margin_right_$o", SettingsRanges.MARGIN_SIDE_PERCENT)
        }
    }

    /**
     * Keys whose import range is deliberately wider than the slider (M9). Each is the range of
     * the XML settings screen shipped until 2025-12-04 — see [SettingsRanges.ImportEnvelope].
     * Adding a key here is a decision that needs that kind of reason, not a way to pass.
     */
    private val importEnvelopes: Map<String, IntRange> = mapOf(
        "longpress_timeout" to SettingsRanges.ImportEnvelope.LONGPRESS_TIMEOUT,
        "short_gesture_min_distance" to SettingsRanges.ImportEnvelope.SHORT_GESTURE_MIN_DISTANCE,
        "custom_border_radius" to SettingsRanges.ImportEnvelope.CUSTOM_BORDER_RADIUS,
        "vibrate_duration" to SettingsRanges.ImportEnvelope.VIBRATE_DURATION,
    )

    @Test
    fun validatorRange_isTheSliderRange_orADocumentedWiderEnvelope() {
        for ((key, slider) in sliderRanges) {
            val validated = SettingsValidation.intRangeFor(key)
            val envelope = importEnvelopes[key]
            if (envelope == null) {
                assertWithMessage("$key: backup validation must accept exactly the slider's range")
                    .that(validated).isEqualTo(slider)
            } else {
                assertWithMessage("$key: validation must use its ImportEnvelope").that(validated).isEqualTo(envelope)
                assertWithMessage("$key: the envelope must contain every slider value (else F-4: own export rejected)")
                    .that(envelope.first <= slider.first && envelope.last >= slider.last).isTrue()
                assertWithMessage("$key: an envelope equal to the slider is not an envelope — drop it")
                    .that(envelope).isNotEqualTo(slider)
            }
        }
    }

    @Test
    fun importEnvelopes_carryTheHistoricXmlSliderRanges() {
        // Literal on purpose: these are the ranges older builds' users could store
        // (res/xml/settings.xml at ae0ffb00^). Narrowing one would reject their restores.
        assertThat(SettingsRanges.ImportEnvelope.LONGPRESS_TIMEOUT).isEqualTo(50..2000)
        assertThat(SettingsRanges.ImportEnvelope.SHORT_GESTURE_MIN_DISTANCE).isEqualTo(10..95)
        assertThat(SettingsRanges.ImportEnvelope.CUSTOM_BORDER_RADIUS).isEqualTo(0..100)
        assertThat(SettingsRanges.ImportEnvelope.VIBRATE_DURATION).isEqualTo(0..100)
    }

    @Test
    fun movedRanges_keepTheValuesProductionClampedTo() {
        // M8 moved these out of literals; the values must be exactly what shipped.
        assertThat(SettingsRanges.CLIPBOARD_PANE_HEIGHT_PERCENT).isEqualTo(10..50)
        assertThat(SettingsRanges.GIF_THUMBNAIL_COLUMNS).isEqualTo(2..5)
        assertThat(SettingsRanges.CTC_BEAM_WIDTH).isEqualTo(10..300)
        assertThat(SettingsRanges.ONNX_XNNPACK_THREADS).isEqualTo(1..8)
        assertThat(SettingsRanges.FINGER_OCCLUSION_OFFSET).isEqualTo(-25..25)
        assertThat(SettingsRanges.SWIPE_SMOOTHING_WINDOW).isEqualTo(1..7)
        assertThat(SettingsRanges.MARGIN_BOTTOM_PERCENT).isEqualTo(0..30)
        assertThat(SettingsRanges.MARGIN_SIDE_PERCENT).isEqualTo(0..45)
        assertThat(SettingsRanges.LONGPRESS_TIMEOUT).isEqualTo(200..1000)
        assertThat(SettingsRanges.SHORT_GESTURE_MIN_DISTANCE).isEqualTo(10..60)
        assertThat(SettingsRanges.SHORT_GESTURE_MAX_DISTANCE).isEqualTo(50..200)
        assertThat(SettingsRanges.CUSTOM_BORDER_RADIUS).isEqualTo(0..20)
        assertThat(SettingsRanges.VIBRATE_DURATION).isEqualTo(5..100)
    }

    @Test
    fun movedRanges_everyConsumerReadsTheConstant() {
        val sites = mapOf(
            "CLIPBOARD_PANE_HEIGHT_PERCENT" to listOf(
                "Config.kt", "ui/settings/SettingsPersistence.kt", "ui/settings/sections/ClipboardSection.kt",
                "backup/SettingsValidation.kt",
            ),
            "GIF_THUMBNAIL_COLUMNS" to listOf(
                "Config.kt", "ui/settings/SettingsPersistence.kt", "ui/settings/sections/GifPanelSection.kt",
                "backup/SettingsValidation.kt",
            ),
            "CTC_BEAM_WIDTH" to listOf(
                "Config.kt", "activities/CtcSettingsActivity.kt", "swipe/CtcEngineAdapter.kt",
                "backup/SettingsValidation.kt",
            ),
            "ONNX_XNNPACK_THREADS" to listOf(
                "Config.kt", "activities/CtcSettingsActivity.kt", "swipe/CtcEngineAdapter.kt",
                "onnx/ModelLoader.kt", "backup/SettingsValidation.kt",
            ),
            "FINGER_OCCLUSION_OFFSET" to listOf(
                "Config.kt", "ui/settings/SettingsPersistence.kt", "ui/settings/sections/GestureTuningSection.kt",
                "backup/SettingsValidation.kt",
            ),
            "SWIPE_SMOOTHING_WINDOW" to listOf(
                "Config.kt", "ui/settings/SettingsPersistence.kt", "ui/settings/sections/GestureTuningSection.kt",
                "gesture/ImprovedSwipeGestureRecognizer.kt", "backup/SettingsValidation.kt",
            ),
            "MARGIN_BOTTOM_PERCENT" to listOf(
                "Config.kt", "ui/settings/sections/AppearanceSection.kt", "backup/SettingsImportPlanBuilder.kt",
                "backup/SettingsValidation.kt",
            ),
            "MARGIN_SIDE_PERCENT" to listOf(
                "Config.kt", "ui/settings/sections/AppearanceSection.kt", "backup/SettingsImportPlanBuilder.kt",
                "backup/SettingsValidation.kt",
            ),
            "OPACITY_PERCENT" to listOf(
                "ui/settings/sections/AppearanceSection.kt", "ui/settings/sections/InputBehaviorSection.kt",
                "backup/SettingsValidation.kt",
            ),
            "LONGPRESS_TIMEOUT" to listOf("ui/settings/sections/InputBehaviorSection.kt"),
            "SHORT_GESTURE_MIN_DISTANCE" to listOf(
                "ui/settings/sections/GestureTuningSection.kt", "activities/ShortSwipeCalibrationActivity.kt",
            ),
            "SHORT_GESTURE_MAX_DISTANCE" to listOf(
                "ui/settings/sections/GestureTuningSection.kt", "activities/ShortSwipeCalibrationActivity.kt",
                "backup/SettingsValidation.kt",
            ),
            "CUSTOM_BORDER_RADIUS" to listOf("ui/settings/sections/AppearanceSection.kt"),
            "VIBRATE_DURATION" to listOf("ui/settings/sections/AccessibilitySection.kt"),
        )
        for ((constant, files) in sites) for (f in files) assertSiteReferences(f, constant)
    }

    @Test
    fun movedRanges_haveNoLiteralCopyLeft() {
        // The literal clamps/slider ranges M8 replaced. Any reappearance is a second definition.
        val literal = Regex(
            """coerceIn\((10, 50|2, 5|10, 300|1, 8|-25, 25|1, 7|0, 45|0, 30|0, 80)\)|""" +
                """valueRange = (10f\.\.50f|2f\.\.5f|10f\.\.300f|1f\.\.8f|-25f\.\.25f|1f\.\.7f|0f\.\.30f|""" +
                """10f\.\.60f|200f\.\.1000f|5f\.\.100f|0f\.\.20f|0f\.\.100f),"""
        )
        val files = listOf(
            "Config.kt", "ui/settings/SettingsPersistence.kt", "backup/SettingsImportPlanBuilder.kt",
            "ui/settings/sections/ClipboardSection.kt", "ui/settings/sections/GifPanelSection.kt",
            "ui/settings/sections/GestureTuningSection.kt", "ui/settings/sections/AppearanceSection.kt",
            "ui/settings/sections/InputBehaviorSection.kt", "ui/settings/sections/AccessibilitySection.kt",
            "activities/CtcSettingsActivity.kt", "activities/ShortSwipeCalibrationActivity.kt",
            "swipe/CtcEngineAdapter.kt", "gesture/ImprovedSwipeGestureRecognizer.kt", "onnx/ModelLoader.kt",
        )
        val offenders = files.flatMap { f ->
            read(f).lines().mapIndexedNotNull { i, line -> if (literal.containsMatchIn(line)) "$f:${i + 1}: ${line.trim()}" else null }
        }
        assertWithMessage("literal range restated instead of SettingsRanges (audit M8)").that(offenders).isEmpty()
        val validation = read("backup/SettingsValidation.kt")
        assertWithMessage("SettingsValidation must not restate an int range as a literal")
            .that(Regex("""put\("[a-z_]+", -?\d+\.\.\d+\)""").findAll(validation).map { it.value }.toList())
            .isEmpty()
    }

    @Test
    fun keysMissingFromValidation_areNowValidated() {
        // M8: these three were accepted unvalidated (any int), then clamped at read.
        assertThat(SettingsValidation.validate("clipboard_pane_height_percent", PrefValue.IntV(80))).isNotNull()
        assertThat(SettingsValidation.validate("clipboard_pane_height_percent", PrefValue.IntV(30))).isNull()
        assertThat(SettingsValidation.validate("gif_thumbnail_columns", PrefValue.IntV(9))).isNotNull()
        assertThat(SettingsValidation.validate("gif_thumbnail_columns", PrefValue.IntV(3))).isNull()
        assertThat(SettingsValidation.validate("onnx_xnnpack_threads", PrefValue.IntV(0))).isNotNull()
        assertThat(SettingsValidation.validate("onnx_xnnpack_threads", PrefValue.IntV(2))).isNull()
    }

    @Test
    fun intKeyTypeCheck_coversEveryIntRangedKey() {
        // M11: isIntKey was a hand-copied list that had lost ctc_beam_width and
        // finger_occlusion_offset, so a Bool or String for them passed type checking.
        for (key in listOf("ctc_beam_width", "finger_occlusion_offset", "onnx_xnnpack_threads", "gif_thumbnail_columns")) {
            assertWithMessage("$key: Bool must be rejected").that(SettingsValidation.validate(key, PrefValue.Bool(true))).isNotNull()
            assertWithMessage("$key: String must be rejected").that(SettingsValidation.validate(key, PrefValue.Str("100"))).isNotNull()
        }
        // The two STRING-stored numeric keys keep their canonical string form.
        assertThat(SettingsValidation.validate("clipboard_history_limit", PrefValue.Str("50"))).isNull()
        assertThat(SettingsValidation.validate("circle_sensitivity", PrefValue.Str("3"))).isNull()
    }
}
