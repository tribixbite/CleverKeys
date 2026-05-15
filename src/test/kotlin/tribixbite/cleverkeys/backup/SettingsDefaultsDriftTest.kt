package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Drift detection: scans `src/main/kotlin/` for every SharedPreferences
 * read site at test time, then asserts every key found is classified
 * into exactly one of three buckets:
 *
 *   1. `SETTINGS_DEFAULTS` — known compile-time default value
 *   2. `NON_DEFAULTED_KEYS` — literal `null` default (intentional)
 *   3. `SettingsValidation.INTERNAL_KEYS` — migration/runtime marker
 *
 * **Why scan source instead of using reflection or codegen?**
 *
 * - Reflection on `SharedPreferences` would see only methods, not call
 *   sites, and definitely wouldn't extract literal-default values.
 * - Codegen (script generates `SettingsDefaults.kt` from Config.kt) needs
 *   complex AST handling to dispatch literal-default reads in
 *   SettingsActivity correctly. Marginal value over a regex scan.
 * - A regex scan of the actual call sites is robust, ~30 LOC, and runs
 *   in milliseconds. Anyone reviewing a PR that adds a pref read will
 *   see this test fail until they classify the key.
 *
 * **When this test fails**, fix the underlying classification — do not
 * suppress the failure by adding the key to a whitelist. The legitimate
 * actions are:
 *
 * - Add to `SETTINGS_DEFAULTS` with the same default value the read site
 *   passes (most common — Bool/Int/Float/Str defaults).
 * - Add to `NON_DEFAULTED_KEYS` if the read site uses `null` as default.
 * - Add to `SettingsValidation.INTERNAL_KEYS` if the key is a migration
 *   version marker or device-bound runtime state (filtered from export
 *   AND import).
 */
class SettingsDefaultsDriftTest {

    // Regex finds: prefs.getX("key", ...) / _prefs.getX("key", ...) /
    // safeGetX(_prefs, "key", ...) — covering every read pattern used in
    // the codebase as of v1.4.0. Captures the key in group 2.
    private val readPattern = Regex(
        """(?:\w*_?[Pp]refs\.get(?:Boolean|Int|Float|Long|String)|safeGet(?:Boolean|Int|Float|Long|String)\(_prefs,)\s*"([a-zA-Z_]+)""""
    )

    @Test
    fun everyPrefReadKeyIsClassified() {
        val mainKotlin = File("src/main/kotlin")
        check(mainKotlin.exists()) {
            "Source dir not found at ${mainKotlin.absolutePath} — drift test must run with project root as CWD."
        }

        val foundKeys = sortedSetOf<String>()
        mainKotlin.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                readPattern.findAll(text).forEach { match ->
                    foundKeys += match.groupValues[1]
                }
            }

        check(foundKeys.isNotEmpty()) {
            "Regex matched zero keys — pattern is broken, not a real pass."
        }

        val classified = SETTINGS_DEFAULTS.keys +
            NON_DEFAULTED_KEYS +
            SettingsValidation.INTERNAL_KEYS
        val unclassified = foundKeys - classified

        assertThat(unclassified).isEmpty()
    }

    @Test
    fun classificationBucketsAreDisjoint() {
        // A key in two buckets at once is ambiguous and almost certainly a
        // copy-paste error. Catch it before it ships.
        val inDefaults = SETTINGS_DEFAULTS.keys
        val inNoDefault = NON_DEFAULTED_KEYS
        val inInternal = SettingsValidation.INTERNAL_KEYS

        val overlapDefaultsNoDefault = inDefaults intersect inNoDefault
        val overlapDefaultsInternal = inDefaults intersect inInternal
        val overlapNoDefaultInternal = inNoDefault intersect inInternal

        assertThat(overlapDefaultsNoDefault).isEmpty()
        assertThat(overlapDefaultsInternal).isEmpty()
        assertThat(overlapNoDefaultInternal).isEmpty()
    }
}
