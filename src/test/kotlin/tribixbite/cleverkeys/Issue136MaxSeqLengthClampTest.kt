package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Regression test for issue #136 — "swipe stops working" with
 * ORT_INVALID_ARGUMENT: trajectory_features index 1 got 400 expected 250.
 *
 * Root cause: `neural_user_max_seq_length` could be set up to 400 via the
 * "Max Sequence Length Override" slider in SettingsActivity. When the user
 * dragged it past 250 (the encoder's exported `max_seq_length`), the next
 * swipe built a trajectory tensor of shape [1, 400, 6] and ORT rejected it.
 *
 * Fix contract:
 *   1. The unsafe slider UI must NOT exist in SettingsActivity.
 *   2. The SearchableSetting registry entry that pointed to it must be gone.
 *   3. SwipePredictorOrchestrator MUST clamp any stored override to
 *      MODEL_MAX_SEQUENCE_LENGTH (250). Existing user prefs from older
 *      builds keep working — they just stop crashing.
 *
 * Why source-pattern: SwipePredictorOrchestrator's clamp executes inside
 * setConfig() which requires a real Android Config + ONNX runtime — that
 * belongs in an instrumented test. This pure-JVM test pins the source-level
 * contract so the regression can't sneak back in.
 */
class Issue136MaxSeqLengthClampTest {

    private val mainSrcDir = File("src/main/kotlin/tribixbite/cleverkeys")

    private fun readSource(relative: String): String {
        val file = File(mainSrcDir, relative)
        require(file.exists()) { "Source file not found: ${file.absolutePath}" }
        return file.readText()
    }

    @Test
    fun orchestrator_definesModelMaxSequenceLengthConstant() {
        val src = readSource("onnx/SwipePredictorOrchestrator.kt")
        assertThat(src).contains("MODEL_MAX_SEQUENCE_LENGTH = 250")
    }

    @Test
    fun orchestrator_clampsUserOverrideToModelMax() {
        val src = readSource("onnx/SwipePredictorOrchestrator.kt")

        // The override path must coerce against MODEL_MAX_SEQUENCE_LENGTH so
        // values stored by old builds (up to 400) can't crash the encoder.
        assertThat(src).contains("neural_user_max_seq_length")
        assertThat(src).contains("coerceAtMost(MODEL_MAX_SEQUENCE_LENGTH)")
    }

    @Test
    fun settingsActivity_hidesUnsafeOverrideSlider() {
        val src = readSource("SettingsActivity.kt")

        // The unsafe-range slider must be gone. We pin both the slider's
        // unique value range and the saveSetting call that wired it to the
        // pref key — either coming back means the regression is back.
        assertThat(src).doesNotContain("valueRange = 0f..400f")
        assertThat(src).doesNotContain(
            "saveSetting(\"neural_user_max_seq_length\", neuralUserMaxSeqLength)"
        )
    }

    @Test
    fun settingsActivity_dropsMaxSeqLengthSearchEntry() {
        val src = readSource("SettingsActivity.kt")

        // The SearchableSetting entry that linked search → the deleted slider
        // would route users to a control that no longer exists. Make sure
        // it's been removed.
        assertThat(src).doesNotContain("settingId = \"max_seq_length\"")
    }

    @Test
    fun configFieldStillExists_forBackupRestoreCompatibility() {
        // We deliberately keep the Config field + safeGetInt path so existing
        // backup ZIPs and SharedPreferences stores continue to round-trip
        // cleanly. Only the UI is hidden + the runtime usage is clamped.
        val configSrc = readSource("Config.kt")
        assertThat(configSrc).contains("neural_user_max_seq_length")
    }
}
