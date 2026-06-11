package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift detection: gesture-distance prefs carry UNITS — `short_gesture_*` are
 * PERCENT of the key diagonal, `swipe_*` distances are PIXELS, durations are MS.
 * A raw `prefs.getInt("short_gesture_min_distance")` hands the caller a unit-less
 * number, which is exactly how ShortSwipeCalibrationActivity ended up treating the
 * % thresholds as raw px (~2x off, fixed 2026-06-11).
 *
 * Rule: gesture pref KEYS may only be READ raw in the canonical layers —
 * Config.kt (the typed surface every consumer must use), SettingsActivity.kt
 * (display/persist layer), and backup/ (export/import operates on raw prefs by
 * design). Everything else must consume Config's fields, which carry the unit
 * in their declaration. Writes (putInt etc.) are allowed anywhere: prefs are the
 * persistence mechanism and writes are unit-typed at the call site by the value
 * being written.
 */
class GesturePrefAccessDriftTest {

    private val gesturePrefKeys = listOf(
        "short_gesture_min_distance",  // % of key diagonal
        "short_gesture_max_distance",  // % of key diagonal (the short/long boundary)
        "swipe_min_distance",          // px (path length)
        "swipe_min_key_distance",      // px
        "swipe_noise_threshold",       // px
        "swipe_high_velocity_threshold", // px/sec
        "swipe_min_dwell_time",        // ms
        "tap_duration_threshold",      // ms
        "swipe_dist",                  // device-scaled units -> swipe_dist_px
        "swipe_smoothing_window"       // points
    )

    /** Files allowed to read these prefs raw. */
    private val allowedFiles = setOf(
        "Config.kt",                // the canonical typed surface
        "SettingsActivity.kt",      // display/persist layer (reads to populate sliders)
        "NeuralSettingsActivity.kt" // same display/persist role for the neural panel
    )

    private val rawReadPattern = Regex(
        """(?:\.get(?:Int|Float|Long|Boolean|String)|safeGet(?:Int|Float|Long|Boolean|String)|getSafeString|getSafeBoolean|get_dip_pref)\s*\([^)]*"(${gesturePrefKeys.joinToString("|")})""""
    )

    @Test
    fun gesturePrefsAreOnlyReadRawInCanonicalLayers() {
        val mainKotlin = File("src/main/kotlin")
        assertWithMessage("test must run from the project root").that(mainKotlin.isDirectory).isTrue()

        val violations = mutableListOf<String>()
        mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowedFiles }
            .filter { !it.path.contains("/backup/") }
            .forEach { file ->
                file.readText().lines().forEachIndexed { idx, line ->
                    if (rawReadPattern.containsMatchIn(line)) {
                        violations.add("${file.path}:${idx + 1}: ${line.trim()}")
                    }
                }
            }

        assertWithMessage(
            "Gesture prefs carry units (% of key diagonal / px / ms) and must be read " +
                "through Config's typed fields, not raw SharedPreferences. Violations:\n" +
                violations.joinToString("\n")
        ).that(violations).isEmpty()
    }
}
