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
}
