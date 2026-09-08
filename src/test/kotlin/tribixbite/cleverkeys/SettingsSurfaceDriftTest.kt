package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import java.io.File

/**
 * F-5 / F-6 / F-7 / F-8 / F-9 (comprehensive audit 2026-09-06): the
 * "dead-or-drifted settings surface" class.
 *
 *  - F-5: the renderer implements a trail effect ("sparkle" — the shipped
 *    DEFAULT) that the Swipe Trail dropdown cannot represent, so fresh
 *    installs displayed "Glow" while sparkle rendered, and one touch of
 *    the dropdown made the default permanently unreachable.
 *  - F-6: the "Pin Entry Layout" switch wrote `pin_entry_enabled`, whose
 *    only runtime reader is Config.migrate's one-time seeding — the
 *    switch changed nothing on any install.
 *  - F-7: five dead `cgr_*` keys (neural-era) were seeded into every
 *    settings export from SETTINGS_DEFAULTS.
 *  - F-8: keys with live runtime readers but no writer anywhere — pinned
 *    below so the class cannot grow silently.
 *  - F-9: AutoCorrectionSettingsActivity was manifest-registered but its
 *    only launcher had zero callers (and its ranges had drifted).
 */
class SettingsSurfaceDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")
    private fun read(rel: String) = File(srcRoot, rel).readText()

    private fun allMainFiles(): Sequence<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }

    // ── F-5: dropdown must represent every effect the renderer implements ──

    /** Literals of the `when (…) { "x" -> … }` block that starts at [anchor]. */
    private fun whenBlockLiterals(text: String, anchor: String): Set<String> {
        val start = text.indexOf(anchor)
        check(start >= 0) { "Anchor '$anchor' not found — re-point this test, don't delete it." }
        val open = text.indexOf('{', start)
        check(open > start) { "No block after anchor '$anchor'." }
        var depth = 0
        var end = open
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { end = i; break }
            }
        }
        return Regex(""""([a-z_]+)"\s*->""").findAll(text.substring(open, end))
            .map { it.groupValues[1] }.toSet()
    }

    @Test
    fun swipeTrailDropdown_coversEveryRendererEffect() {
        val renderer = read("Keyboard2View.kt")
        val rendererEffects =
            whenBlockLiterals(renderer, "when (snap.swipe_trail_effect)") +
                Regex("""swipe_trail_effect\s*==\s*"([a-z_]+)"""").findAll(renderer)
                    .map { it.groupValues[1] }
        check(rendererEffects.size >= 4 && "glow" in rendererEffects) {
            "Renderer-effect extraction broke (got $rendererEffects) — fix the scan, not the assert."
        }

        val section = read("ui/settings/sections/SwipeTrailSection.kt")
        val sectionEffects =
            whenBlockLiterals(section, "selectedIndex = when (swipeTrailEffect)")

        assertWithMessage(
            "Every effect the renderer branches on must be selectable in the Swipe Trail dropdown"
        ).that(sectionEffects).containsAtLeastElementsIn(rendererEffects)

        // The shipped default must be representable (the original F-5 failure).
        assertThat(sectionEffects).contains(Defaults.SWIPE_TRAIL_EFFECT)
    }

    // ── F-6: the Pin Entry switch must write the key the runtime reads ──

    @Test
    fun pinEntrySwitch_writesNumberEntryLayout_notTheDeadPref() {
        val section = read("ui/settings/sections/InputBehaviorSection.kt")
        assertWithMessage(
            "pin_entry_enabled's only runtime reader is Config.migrate's one-time seeding — " +
                "the switch must write number_entry_layout directly"
        ).that(section.contains("saveSetting(\"pin_entry_enabled\"")).isFalse()
        assertThat(section).contains("saveSetting(\"number_entry_layout\"")
    }

    // ── F-7: every exported default needs a runtime reader ──

    /**
     * Reverse drift: every key SETTINGS_DEFAULTS seeds into every export must
     * be READ somewhere outside the backup layer and the settings surface —
     * otherwise the export advertises a knob that drives nothing (the
     * cgr_* / ARC-051 / ARC-085 "dead key looks alive" class).
     */
    @Test
    fun everySettingsDefaultsKey_hasARuntimeReader() {
        val surfacePaths = listOf(
            "/backup/", "/ui/settings/",
        )
        val runtimeFiles = allMainFiles()
            .filter { f ->
                val p = f.path.replace('\\', '/')
                surfacePaths.none { p.contains(it) } && f.name != "SettingsActivity.kt"
            }
            .map { it.readText() }
            .toList()

        val unread = SETTINGS_DEFAULTS.keys.filter { key ->
            runtimeFiles.none { it.contains("\"$key\"") }
        }
        assertWithMessage(
            "SETTINGS_DEFAULTS keys with no read site outside backup/ + the settings surface — " +
                "move them to SettingsValidation.DEPRECATED_KEYS or wire a reader"
        ).that(unread).isEmpty()
    }

    // ── F-8: keys with runtime readers but no writer, pinned ──

    /**
     * SETTINGS_DEFAULTS keys that NOTHING in src/main writes. These gate real
     * behavior but are permanently at defaults for anyone who doesn't
     * hand-edit a backup file.
     *
     * Maintainer decision 2026-09-08 (F-8 fork resolved): "surface all except
     * error collection". The four surfaceable keys got real controls —
     * `clipboard_media_enabled` + `clipboard_max_media_size_mb` (Clipboard
     * section), `show_exact_typed_word` (Input Behavior), `scale_numpad_height`
     * (Appearance) — so every SETTINGS_DEFAULTS key except the one pinned
     * below now has a writer. This pin is the ratchet in both directions: a
     * NEW writerless key is the F-8 class growing back, and a key listed here
     * gaining a writer means the pin (and the decision record) must move.
     */
    @Test
    fun writerlessSettingsKeys_arePinnedExactly() {
        val knownWriterless = setOf(
            // DELIBERATELY writerless (maintainer decision 2026-09-08): error
            // collection stays unsurfaced — no Settings control is wanted. The
            // key stays live (not deprecated) so today's runtime behavior is
            // exactly preserved: PrivacyManager.canCollectErrorLogs keeps
            // reading it (default false), and a backup that carries `true`
            // still enables collection on import, exactly as before.
            "privacy_collect_errors",
            // clipboard_pinned_rows left this set 2026-09-07: ClipboardPinView (its
            // only reader) was deleted by D-9, so the key moved to DEPRECATED_KEYS.
        )

        val texts = allMainFiles().map { it.readText() }.toList()
        // Some controls key their write by a `const val` (e.g. saveSetting(
        // PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, …)) — resolve
        // constant names to their literal values so those count as writers.
        val constNames: Map<String, List<String>> = buildMap<String, MutableList<String>> {
            for (t in texts) {
                Regex("""const val (\w+)\s*=\s*"([a-z_0-9]+)"""").findAll(t).forEach { m ->
                    getOrPut(m.groupValues[2]) { mutableListOf() }.add(m.groupValues[1])
                }
            }
        }
        fun hasWriter(key: String): Boolean {
            val constRefs = constNames[key].orEmpty()
            return texts.any { t ->
                t.contains("saveSetting(\"$key\"") ||
                    Regex("""\.put(?:Boolean|Int|Float|Long|String)\(\s*"$key"""").containsMatchIn(t) ||
                    constRefs.any { c ->
                        Regex("""saveSetting\((?:\w+\.)?$c[,)]""").containsMatchIn(t) ||
                            Regex("""\.put(?:Boolean|Int|Float|Long|String)\(\s*(?:\w+\.)?$c[,)]""").containsMatchIn(t)
                    }
            }
        }
        val writerless = SETTINGS_DEFAULTS.keys.filterNot(::hasWriter).toSortedSet()

        assertWithMessage(
            "Writerless SETTINGS_DEFAULTS keys drifted from the documented set — a new key with " +
                "a runtime reader but no control is the F-8 class; surface it or reclassify it"
        ).that(writerless).containsExactlyElementsIn(knownWriterless.toSortedSet())
    }

    // ── D-1: the password-manager exclusion may not promise what it cannot do ──

    /**
     * D-1 (comprehensive audit 2026-09-06; maintainer decision 2026-09-08:
     * "reword", PACKAGE_USAGE_STATS stays undeclared). The old description —
     * "Don't store clipboard from Bitwarden, 1Password, LastPass, KeePass,
     * etc." — promised an unconditional exclusion, but the mechanism is
     * foreground-app detection via UsageStats, which needs the
     * PACKAGE_USAGE_STATS app-op the manifest deliberately never declares:
     * detection returns null on effectively every device and the exclusion
     * fails open. The wording must describe the mechanism that actually
     * exists (best-effort detection, reliable protection = the IS_SENSITIVE
     * setting below it) and must never regress to the promise.
     */
    @Test
    fun passwordManagerExclusion_wordingDoesNotOverpromise() {
        val descRegex = Regex(
            """<string name="clipboard_exclude_password_managers_desc">([^<]*)</string>"""
        )
        val en = descRegex.find(File("res/values/strings.xml").readText())?.groupValues?.get(1)
        checkNotNull(en) {
            "clipboard_exclude_password_managers_desc missing from res/values/strings.xml — " +
                "re-point this pin, don't delete it."
        }
        assertWithMessage(
            "the reworded description must say the exclusion is best-effort (audit D-1)"
        ).that(en.lowercase()).contains("best effort")
        assertWithMessage(
            "the description must not promise unconditional exclusion again"
        ).that(en).doesNotContain("Don\\'t store clipboard from")

        // Cross-locale: the old string named the same app list verbatim in every
        // translation, so the app names are a reliable marker of the pre-reword
        // promise surviving in any locale.
        val localeFiles = File("res").listFiles { f ->
            f.isDirectory && f.name.startsWith("values")
        }!!.mapNotNull { dir -> File(dir, "strings.xml").takeIf { it.isFile } }
        check(localeFiles.size >= 22) { "Locale scan found ${localeFiles.size} strings.xml — expected 22." }
        for (file in localeFiles) {
            val desc = descRegex.find(file.readText())?.groupValues?.get(1) ?: continue
            assertWithMessage(
                "${file.parentFile.name}: the old wording promised per-app exclusion " +
                    "(named app list) — the reword must land in every locale"
            ).that(desc).doesNotContain("Bitwarden")
        }
    }

    // ── F-9: every navigation helper needs a caller ──

    @Test
    fun everySettingsNavigationOpenHelper_hasACaller() {
        val nav = read("ui/settings/SettingsNavigation.kt")
        val helpers = Regex("""fun SettingsActivity\.(open\w+)\(""").findAll(nav)
            .map { it.groupValues[1] }.toList()
        check(helpers.isNotEmpty()) { "Extracted zero open* helpers — the scan is broken." }

        val otherTexts = allMainFiles()
            .filter { it.name != "SettingsNavigation.kt" }
            .map { it.readText() }
            .toList()

        val orphans = helpers.filter { name -> otherTexts.none { it.contains("$name(") } }
        assertWithMessage(
            "SettingsNavigation open* helpers with zero callers — dead screens rot and their " +
                "ranges drift (the F-9 AutoCorrectionSettingsActivity failure); delete or wire them"
        ).that(orphans).isEmpty()
    }
}
