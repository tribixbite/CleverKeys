package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Drift detection: scans `src/main/kotlin/` for every SharedPreferences
 * read site at test time, then asserts every key found is classified
 * into exactly one of four buckets:
 *
 *   1. `SETTINGS_DEFAULTS` — known compile-time default value
 *   2. `NON_DEFAULTED_KEYS` — literal `null` default (intentional)
 *   3. `SettingsValidation.INTERNAL_KEYS` — migration/runtime marker
 *   4. `SettingsValidation.DEPRECATED_KEYS` — legacy pref name, no read
 *      site in current code; filtered out of import previews so legacy
 *      backups don't surface them as no-op rows
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

    // Covers every pref-access pattern used in the codebase as of v1.4.0.
    // Captures the key name in capture group 1. Patterns:
    //   prefs.getBoolean("k", …)         — direct SharedPreferences read
    //   _prefs.getInt("k", …)            — Config.kt field-style access
    //   protectedPrefs.getBoolean("k",…) — DirectBoot prefs
    //   prefs.getSafeBoolean("k", …)     — Config.kt's typed-default helper
    //   safeGetFloat(_prefs, "k", …)     — module-level safe-get function
    //   get_dip_pref(dm, "k", …)         — dp→px converted reads
    //   prefs.putString("k", …)          — direct write
    //   editor.putBoolean("k", …)        — write via editor (the export
    //                                       reads `prefs.all`, so any write
    //                                       creates a key that ends up in
    //                                       every export)
    //
    // The PUT pattern requires `editor`/`Editor` or `prefs`/`Prefs` as the
    // receiver to avoid Bundle false-positives (Activity savedInstanceState
    // uses identical `.putString("k", …)` syntax against a Bundle, NOT
    // SharedPreferences — those keys aren't preferences).
    private val readPattern = Regex(
        """(?:""" +
            """\w*[Pp]refs\.get(?:Safe)?(?:Boolean|Int|Float|Long|String)\(|""" +
            """safeGet(?:Boolean|Int|Float|Long|String)\(_prefs,|""" +
            """get_dip_pref\([^,]+,|""" +
            """(?:\w*[Pp]refs(?:\.edit\(\))?|\w*[Ee]ditor)\.put(?:Boolean|Int|Float|Long|String)\(""" +
        """)\s*"([a-zA-Z_]+)""""
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
            SettingsValidation.INTERNAL_KEYS +
            SettingsValidation.DEPRECATED_KEYS
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
        val inDeprecated = SettingsValidation.DEPRECATED_KEYS

        assertThat(inDefaults intersect inNoDefault).isEmpty()
        assertThat(inDefaults intersect inInternal).isEmpty()
        assertThat(inDefaults intersect inDeprecated).isEmpty()
        assertThat(inNoDefault intersect inInternal).isEmpty()
        assertThat(inNoDefault intersect inDeprecated).isEmpty()
        assertThat(inInternal intersect inDeprecated).isEmpty()
    }
}
