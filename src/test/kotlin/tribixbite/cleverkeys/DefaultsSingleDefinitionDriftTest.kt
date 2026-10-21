package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Audit M3/M4/M5/M10/L12 (2026-10-20): ONE definition per setting default.
 *
 * `Defaults` is the value `Config.refresh` reads, so it is what users get. Two other places
 * used to carry their own copy of the same default and had drifted from it:
 *
 *  - **M4** — `Config` field initialisers (`swipe_trail_effect = "glow"` vs `Defaults` "sparkle",
 *    `slider_speed_max = 4.0f` vs 6.0, about twenty `false` vs `true` toggles). They seed the
 *    pre-refresh snapshot and read as documentation of the default, so a wrong one misleads.
 *  - **M5** — `SettingsActivity` `mutableStateOf(<literal>)` initial values (the same stale set,
 *    from before `Defaults` existed).
 *
 * The rule pinned here: wherever a field or settings state is loaded from a preference whose
 * fallback is a `Defaults` constant, its initial value must reference one of the constants on
 * that read. Derived fields (pixels, 0–255 opacities, scaled sizes) are listed by name with the
 * reason they start at zero.
 *
 * Source scans, because `Config` cannot be constructed off-device and an Objenesis instance
 * skips field initialisers altogether.
 */
class DefaultsSingleDefinitionDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")
    private fun read(rel: String) = File(srcRoot, rel).readText()

    private val defaultsToken = Regex("""\bDefaults\.([A-Z][A-Z0-9_]*)""")

    /**
     * The statement assigning [name] that starts on [lines]`[start]`: the line plus continuation
     * lines until parentheses balance (`swipe_context_rescoring = _prefs.getBoolean(\n …)`).
     */
    private fun statementAt(lines: List<String>, start: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var i = start
        do {
            val line = lines[i]
            sb.append(line).append('\n')
            depth += line.count { it == '(' } - line.count { it == ')' }
            i++
        } while (depth > 0 && i < lines.size)
        return sb.toString()
    }

    /** Body of `fun <name>(` in [src], by brace matching from the declaration. */
    private fun functionBody(src: String, name: String): String {
        // Plain or extension (`fun SettingsActivity.loadCurrentSettings(`).
        val start = Regex("""\bfun (?:\w+\.)?$name\(""").find(src)?.range?.first ?: -1
        check(start >= 0) { "fun $name( not found" }
        val open = src.indexOf('{', start)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
        }
        error("unbalanced braces in $name")
    }

    /** `name = …` statements in [body] → the `Defaults` constants each one names. */
    private fun defaultsReadBy(body: String, assignee: Regex): Map<String, Set<String>> {
        val lines = body.lines()
        val out = LinkedHashMap<String, Set<String>>()
        lines.forEachIndexed { i, line ->
            val m = assignee.find(line) ?: return@forEachIndexed
            val tokens = defaultsToken.findAll(statementAt(lines, i)).map { it.groupValues[1] }.toSet()
            if (tokens.isNotEmpty()) out.putIfAbsent(m.groupValues[1], tokens)
        }
        return out
    }

    // ── M4: Config field initialisers ───────────────────────────────────────

    /**
     * Fields whose refresh() value is COMPUTED from the preference rather than being it, so
     * they start at zero and refresh() fills them. Each entry names the transformation.
     */
    private val configDerived = mapOf(
        "labelBrightness" to "percent × 255 / 100",
        "keyboardOpacity" to "percent × 255 / 100",
        "keyOpacity" to "percent × 255 / 100",
        "keyActivatedOpacity" to "percent × 255 / 100",
        "customBorderRadius" to "percent / 100 (fraction of key width)",
        "customBorderLineWidth" to "dp → px",
        "key_vertical_margin" to "dp → px / 100",
        "key_horizontal_margin" to "dp → px / 100",
        "characterSize" to "× orientation scale",
        "margin_bottom" to "percent of screen height → px",
        "margin_left" to "percent of screen width → px",
        "margin_right" to "percent of screen width → px",
        "selection_delete_step_px" to "dp → px",
        "inverse_numpad" to "string == \"low_first\"",
        "selected_number_layout" to "NumberLayout.of_string(…)",
        "custom_terminal_packages" to "parsed package list",
    )

    @Test
    fun configFieldInitialisers_referenceTheDefaultTheirRefreshReads() {
        val src = read("Config.kt")
        val classBody = src.substring(src.indexOf("class Config private constructor("))
        val fieldDecl = Regex("""^\s*(?:@Volatile\s+)?(?:@JvmField\s+|private\s+)?var\s+(\w+)\s*(?::\s*[^=]+)?=\s*(.+)$""")
        val initialisers = classBody.lines()
            .takeWhile { !it.trimStart().startsWith("init {") }
            .mapNotNull { fieldDecl.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }

        val reads = defaultsReadBy(functionBody(src, "refresh"), Regex("""^\s*(\w+)\s*=\s"""))
            .toMutableMap()
            .apply { putAll(defaultsReadBy(functionBody(src, "reloadSanitizationSettings"), Regex("""^\s*(\w+)\s*=\s"""))) }

        assertWithMessage("scan found no refresh() reads — the pattern is broken, not a pass")
            .that(reads.size).isAtLeast(80)

        val checked = mutableListOf<String>()
        for ((field, constants) in reads) {
            val init = initialisers[field] ?: continue  // a local val or a non-field target
            if (field in configDerived) continue
            checked += field
            val initTokens = defaultsToken.findAll(init).map { it.groupValues[1] }.toSet()
            assertWithMessage(
                "Config.$field is read from Defaults.${constants.joinToString("/")} in refresh() " +
                    "but initialised to `$init` — initialise it to that constant (audit M4)"
            ).that(initTokens.intersect(constants)).isNotEmpty()
        }
        assertWithMessage("too few Config fields checked — scan regression").that(checked.size).isAtLeast(80)

        for (name in configDerived.keys) {
            assertWithMessage("configDerived lists `$name`, which is no longer a Config field")
                .that(initialisers).containsKey(name)
        }
    }

    // ── M5: SettingsActivity initial Compose state ──────────────────────────

    @Test
    fun settingsActivityInitialState_referencesTheDefaultItsLoaderReads() {
        val activity = read("activities/SettingsActivity.kt")
        val stateDecl = Regex("""^\s*internal var (\w+) by mutable(?:Int|Float|Long)?StateOf(?:<[^>]+>)?\((.*)\)""")
        val initial = activity.lines()
            .mapNotNull { stateDecl.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }

        val loader = functionBody(read("ui/settings/SettingsPersistence.kt"), "loadCurrentSettings")
        val reads = defaultsReadBy(loader, Regex("""^\s*(\w+)\s*=\s"""))
        assertWithMessage("scan found no loader reads — the pattern is broken, not a pass")
            .that(reads.size).isAtLeast(100)

        var checked = 0
        for ((state, constants) in reads) {
            val init = initial[state] ?: continue
            checked++
            val initTokens = defaultsToken.findAll(init).map { it.groupValues[1] }.toSet()
            assertWithMessage(
                "SettingsActivity.$state loads from Defaults.${constants.joinToString("/")} but starts " +
                    "as `$init` — start it from that constant (audit M5)"
            ).that(initTokens.intersect(constants)).isNotEmpty()
        }
        assertWithMessage("too few states checked — scan regression").that(checked).isAtLeast(100)
    }

    // ── M3 / M10 / L12: no literal copy of a default that has a constant ─────

    /**
     * Read sites that used to restate these defaults as literals. Each `getX("key", <literal>)`
     * call is a second definition the next change has to find.
     */
    @Test
    fun clipboardAndLanguageReadSites_useTheDefaultsConstants() {
        val keys = listOf(
            "clipboard_text_only", "clipboard_pinned_enabled", "clipboard_todo_enabled",
            "clipboard_swipe_delete_enabled",
            "clipboard_sanitize_links_enabled", "clipboard_embed_enrich_enabled",
            "clipboard_custom_rules_enabled", "clipboard_sanitize_system_clipboard",
            "pref_primary_language", "pref_secondary_language",
            "pref_primary_language_alt", "pref_secondary_language_alt", "pref_enable_multilang",
            "slider_sensitivity",
        )
        // A preference read (`getBoolean`, `getSafeString`, …) or a reset-map `put` whose
        // fallback/value is a bare literal.
        val literalDefault = Regex(
            """\b(?:get\w*|safeGet\w*|put)\(\s*(?:_?prefs\s*,\s*)?"(${keys.joinToString("|")})"\s*,\s*(true|false|"[^"]*")\s*\)"""
        )
        val offenders = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    literalDefault.find(line)?.let { "${f.relativeTo(srcRoot)}:${i + 1}: ${line.trim()}" }
                }
            }
            .toList()
        assertWithMessage("literal defaults for keys that have a Defaults constant (audit M3/M10)")
            .that(offenders).isEmpty()
    }

    @Test
    fun refresh_readsTheSanitizationTogglesThroughTheReloadFunction() {
        // M3: refresh() and the mid-session reload used to carry two copies of these five reads.
        val refresh = functionBody(read("Config.kt"), "refresh")
        assertWithMessage("refresh() must delegate the Chunk-3 reads to reloadSanitizationSettings()")
            .that(refresh).contains("reloadSanitizationSettings()")
        assertWithMessage("refresh() must not read a sanitization key itself")
            .that(refresh).doesNotContain("\"clipboard_sanitize_links_enabled\"")
    }

    @Test
    fun theNewConstantsCarryTheValuesProductionShipped() {
        // Pinned against the literals the read sites used before the constants existed.
        assertWithMessage("text-only").that(Defaults.CLIPBOARD_TEXT_ONLY).isFalse()
        assertWithMessage("pinned tab").that(Defaults.CLIPBOARD_PINNED_ENABLED).isTrue()
        assertWithMessage("todo tab").that(Defaults.CLIPBOARD_TODO_ENABLED).isTrue()
        assertWithMessage("sanitize links").that(Defaults.CLIPBOARD_SANITIZE_LINKS_ENABLED).isFalse()
        assertWithMessage("embed enrich").that(Defaults.CLIPBOARD_EMBED_ENRICH_ENABLED).isFalse()
        assertWithMessage("custom rules").that(Defaults.CLIPBOARD_CUSTOM_RULES_ENABLED).isFalse()
        assertWithMessage("system clipboard").that(Defaults.CLIPBOARD_SANITIZE_SYSTEM_CLIPBOARD).isTrue()
        assertWithMessage("secondary").that(Defaults.SECONDARY_LANGUAGE).isEqualTo("none")
        assertWithMessage("primary alt").that(Defaults.PRIMARY_LANGUAGE_ALT).isEqualTo("es")
        assertWithMessage("secondary alt").that(Defaults.SECONDARY_LANGUAGE_ALT).isEqualTo("none")
        assertWithMessage("slider fallback = the string default")
            .that(Defaults.SLIDER_SENSITIVITY_FALLBACK).isEqualTo(Defaults.SLIDER_SENSITIVITY.toFloat())
        assertWithMessage("label factor").that(Defaults.LABEL_TEXT_SIZE_FACTOR).isEqualTo(0.33f)
    }
}
