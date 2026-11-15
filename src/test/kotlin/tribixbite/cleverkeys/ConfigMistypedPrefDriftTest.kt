package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Review 2026-11-14 H4, the device side: `Config.refresh()` runs in the IME's `onCreate`
 * (`initGlobalConfig`), so one preference stored with the wrong type (an older import, a
 * hand-edited backup) must never throw there — a raw `getBoolean` on an Int value throws
 * ClassCastException on every bind, a crash loop until the app's data is cleared. Every
 * Boolean/Int read in `refresh()` goes through `safeGetBoolean` / `safeGetInt`, which fall back
 * to the other stored types and then the default. (The import side is pinned by
 * `SettingsImportPlanBuilderTest.wrongTypedValuesForKnownKeysAreSkipped`.)
 */
class ConfigMistypedPrefDriftTest {

    private val config = File("src/main/kotlin/tribixbite/cleverkeys/Config.kt").readLines()

    /** The lines of `fun refresh(` up to the closing brace at its indentation. */
    private fun refreshBody(): List<IndexedValue<String>> {
        val start = config.indexOfFirst { it.trimStart().startsWith("fun refresh(") }
        check(start >= 0) { "Config.refresh not found — re-point this test, do not delete it." }
        val indent = config[start].takeWhile { it == ' ' }
        val end = (start + 1 until config.size).first { config[it] == "$indent}" }
        return config.withIndex().toList().subList(start, end + 1)
    }

    @Test
    fun refreshReadsNoBooleanOrIntWithARawGetter() {
        val raw = Regex("""\b_prefs\.get(Boolean|Int)\(""")
        val offenders = refreshBody().filter { raw.containsMatchIn(it.value) }
            .map { "Config.kt:${it.index + 1}: ${it.value.trim()}" }
        assertWithMessage("raw Boolean/Int reads in Config.refresh (use safeGetBoolean / safeGetInt)")
            .that(offenders).isEmpty()
    }

    /**
     * Follow-up review 2026-11-15: `refresh()` also calls out — `reloadSanitizationSettings()`
     * read five keys raw, and `ExtraKeysPreference.getExtraKeys` every `extra_key_<name>` — so the
     * scan covers the whole of Config.kt, and the extra-keys reader.
     */
    @Test
    fun configReadsNoPreferenceWithARawGetterAnywhere() {
        val raw = Regex("""\b_prefs\.get(Boolean|Int|Float|Long|String)\(""")
        val offenders = config.withIndex().filter { raw.containsMatchIn(it.value) }
            .map { "Config.kt:${it.index + 1}: ${it.value.trim()}" }
        assertWithMessage("raw reads in Config (use the safeGet* helpers)").that(offenders).isEmpty()
    }

    @Test
    fun theExtraKeysReaderIsSafe() {
        val reader = File("src/main/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysPreference.kt").readLines()
        val offenders = reader.withIndex().filter { Regex("""\bprefs\.getBoolean\(""").containsMatchIn(it.value) }
            .map { "ExtraKeysPreference.kt:${it.index + 1}: ${it.value.trim()}" }
        assertWithMessage("raw extra_key_* reads (Config.refresh runs this on every keyboard start)")
            .that(offenders).isEmpty()
    }

    @Test
    fun theServicesCommandTimeReadsAreSafeToo() {
        // The scene toggles are read again when their keyboard command runs.
        val service = File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt").readLines()
        val offenders = service.withIndex().filter {
            Regex("""\.getBoolean\("look_(backdrop_motion|scene_true_black)"""").containsMatchIn(it.value)
        }.map { "CleverKeysService.kt:${it.index + 1}: ${it.value.trim()}" }
        assertWithMessage("raw reads of the scene toggles in the service").that(offenders).isEmpty()
    }
}
