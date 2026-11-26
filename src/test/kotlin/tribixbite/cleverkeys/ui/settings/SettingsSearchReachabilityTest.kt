package tribixbite.cleverkeys.ui.settings

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.GENERATED_SEARCH_ENTRIES
import java.io.File

/**
 * Every generated settings-search entry must RESOLVE: after its result expands the section, the
 * control is composed (so it registers a scroll position) or, when a state hides it, one of its
 * gates is on screen to land on instead ([SettingsSearchMatch.settledScrollTarget]).
 *
 * Device finding 2026-11-26: in a freshly opened Settings, "Max Learned Words" opened the Input
 * section and scrolled nowhere (`pos=null gates=[]`), while "Personalized Learning" a few rows
 * above it scrolled. The slider lives in `LearningDataManagerBlock` (LearningDataSection.kt),
 * which InputBehaviorSection calls INSIDE the collapsed "Advanced Prediction Settings" panel and
 * inside `if (wordPredictionEnabled)`. The generator and the panel-id check both read one file at
 * a time, so neither saw that the slider is behind the panel or the switch.
 *
 * This test walks the composition structure itself, a separate implementation from
 * `scripts/generate_settings_search_index.py`: for each control, every `if` / `else` / `when`
 * / `AnimatedVisibility` body that contains it, in its own file and, through the call sites of
 * the composable function it sits in, in its callers' files. Each such body must be handled:
 * - the Advanced Prediction panel (`AnimatedVisibility(visible = wordPredictionAdvancedExpanded)`)
 *   by the result opening that panel (`WORD_PREDICTION_ADVANCED_IDS` in SettingsActivity);
 * - any other condition by a gate ([SettingsSearchMatch.gatesFor]) whose control sits OUTSIDE that
 *   body, so it is on screen while the body is not.
 * The result also must expand the section the control is actually drawn in.
 *
 * Source scan, like [tribixbite.cleverkeys.SettingsSearchCoverageTest]: the controls are
 * Compose functions on `SettingsActivity`, which pure JVM tests cannot compose.
 */
class SettingsSearchReachabilityTest {

    private val activityPath = "src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt"
    private val sectionsDir = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections")

    /** One `if` / `else` / `when` / `AnimatedVisibility` body or one `CollapsibleSettingsSection` call. */
    private data class Region(val file: String, val start: Int, val end: Int, val kind: String, val label: String) {
        operator fun contains(pos: Int) = pos in (start + 1) until end
        override fun toString() = "$kind($label) in $file"
    }

    /** A `fun SettingsActivity.Name(` composable and its body range. */
    private data class Function(val name: String, val bodyStart: Int, val bodyEnd: Int)

    /** A resource-titled SettingsSwitch / SettingsSlider / SettingsDropdown call. */
    private data class Control(val file: String, val pos: Int, val id: String)

    private class Source(val name: String, val text: String) {
        val regions = mutableListOf<Region>()
        val functions = mutableListOf<Function>()
    }

    /** Index just past the bracket closing the one at [open] (string contents are not special:
     *  the settings sources keep no unbalanced brackets inside literals, as the generator assumes). */
    private fun close(text: String, open: Int, o: Char, c: Char): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                o -> depth++
                c -> if (--depth == 0) return i + 1
            }
        }
        return text.length
    }

    /** First non-whitespace index at or after [from]. */
    private fun skipSpace(text: String, from: Int): Int {
        var j = from
        while (j < text.length && text[j].isWhitespace()) j++
        return j
    }

    private val sources: List<Source> by lazy {
        val files = listOf(File(activityPath)) +
            (sectionsDir.listFiles { f -> f.extension == "kt" }?.sortedBy { it.name } ?: emptyList())
        files.map { f -> parse(Source(f.name, f.readText())) }
    }

    private fun parse(src: Source): Source {
        val t = src.text
        // if (cond) { … } [else { … }]
        for (m in Regex("""\bif\s*\(""").findAll(t)) {
            val paren = m.range.last
            val condEnd = close(t, paren, '(', ')')
            val cond = t.substring(paren + 1, condEnd - 1).trim().replace(Regex("\\s+"), " ")
            val brace = skipSpace(t, condEnd)
            if (brace >= t.length || t[brace] != '{') continue
            val bodyEnd = close(t, brace, '{', '}')
            src.regions += Region(src.name, brace, bodyEnd, "if", cond)
            val afterBody = skipSpace(t, bodyEnd)
            if (t.startsWith("else", afterBody)) {
                val elseBrace = skipSpace(t, afterBody + 4)
                if (elseBrace < t.length && t[elseBrace] == '{') {
                    src.regions += Region(src.name, elseBrace, close(t, elseBrace, '{', '}'), "else", "!($cond)")
                }
            }
        }
        // when (subject) { … } — a branch-chosen composable is hidden while another branch is taken.
        for (m in Regex("""\bwhen\s*(\(|\{)""").findAll(t)) {
            val open = m.range.last
            val (label, brace) = if (t[open] == '(') {
                val end = close(t, open, '(', ')')
                t.substring(open + 1, end - 1).trim() to skipSpace(t, end)
            } else {
                "" to open
            }
            if (brace >= t.length || t[brace] != '{') continue
            src.regions += Region(src.name, brace, close(t, brace, '{', '}'), "when", label)
        }
        // AnimatedVisibility(visible = X, …) { … }
        for (m in Regex("""\bAnimatedVisibility\s*\(""").findAll(t)) {
            val paren = m.range.last
            val argsEnd = close(t, paren, '(', ')')
            val visible = Regex("""visible\s*=\s*([^,\n)]+)""").find(t.substring(paren, argsEnd))
                ?.groupValues?.get(1)?.trim() ?: "?"
            val brace = skipSpace(t, argsEnd)
            if (brace >= t.length || t[brace] != '{') continue
            src.regions += Region(src.name, brace, close(t, brace, '{', '}'), "visible", visible)
        }
        // CollapsibleSettingsSection(expanded = xSectionExpanded, …) [{ … }]: the section a control
        // is in, its content passed in the arguments or as a trailing lambda.
        for (m in Regex("""\bCollapsibleSettingsSection\s*\(""").findAll(t)) {
            val paren = m.range.last
            val argsEnd = close(t, paren, '(', ')')
            val expanded = Regex("""expanded\s*=\s*(\w+)""").find(t.substring(paren, argsEnd))?.groupValues?.get(1)
                ?: continue
            val trailing = skipSpace(t, argsEnd)
            val end = if (trailing < t.length && t[trailing] == '{') close(t, trailing, '{', '}') else argsEnd
            src.regions += Region(src.name, paren, end, "section", expanded.replace(Regex("(Section)?Expanded$"), ""))
        }
        for (m in Regex("""fun SettingsActivity\.([A-Z]\w*)\s*\(""").findAll(t)) {
            val paramsEnd = close(t, m.range.last, '(', ')')
            val brace = t.indexOf('{', paramsEnd)
            if (brace < 0) continue
            src.functions += Function(m.groupValues[1], brace, close(t, brace, '{', '}'))
        }
        return src
    }

    private val controls: List<Control> by lazy {
        sources.flatMap { src ->
            Regex("""\b(SettingsSwitch|SettingsSlider|SettingsDropdown)\s*\(""").findAll(src.text).mapNotNull { m ->
                val args = src.text.substring(m.range.last, close(src.text, m.range.last, '(', ')'))
                Regex("""\btitle\s*=\s*stringResource\(\s*R\.string\.(\w+)\s*\)""").find(args)
                    ?.let { Control(src.name, m.range.first, it.groupValues[1]) }
            }.toList()
        }
    }

    /**
     * The regions that contain the point [pos] of [file] at composition time: those around it in
     * its file, plus, when it sits in a composable called from elsewhere in these sources, the
     * regions around each call site (transitively).
     */
    private fun effectiveRegions(file: String, pos: Int, visiting: Set<String> = emptySet()): List<Region> {
        val src = sources.single { it.name == file }
        val here = src.regions.filter { pos in it }
        val fn = src.functions.filter { pos > it.bodyStart && pos < it.bodyEnd }.minByOrNull { it.bodyEnd - it.bodyStart }
            ?: return here
        if (fn.name in visiting) return here
        // A call is `Name(` not preceded by a dot, which also skips the `fun SettingsActivity.Name(`
        // definition itself.
        val callers = sources.flatMap { s ->
            Regex("""(?<![\w.])${fn.name}\s*\(""").findAll(s.text).map { s.name to it.range.first }
        }
        return here + callers.flatMap { (f, p) -> effectiveRegions(f, p, visiting + fn.name) }
    }

    /** `WORD_PREDICTION_ADVANCED_IDS`: the entries whose result also opens the Advanced panel. */
    private val advancedPanelIds: Set<String> by lazy {
        val body = File(activityPath).readText()
            .substringAfter("WORD_PREDICTION_ADVANCED_IDS = setOf(", "")
            .substringBefore(")")
        Regex("\"([a-z0-9_]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
            .also { check(it.isNotEmpty()) { "WORD_PREDICTION_ADVANCED_IDS not found — renamed?" } }
    }

    /** Why the entry [id] does not resolve, one line per unhandled region; empty when it does. */
    private fun problems(id: String, sectionKey: String, generatedGates: List<String>): List<String> {
        val own = controls.filter { it.id == id }
        if (own.isEmpty()) return listOf("$id: no control has this title")
        val gates = SettingsSearchMatch.gatesFor(id, generatedGates)
        // An entry resolves when one of its controls does (a title used twice registers both).
        val perControl = own.map { control ->
            val regions = effectiveRegions(control.file, control.pos)
            val out = mutableListOf<String>()
            val sections = regions.filter { it.kind == "section" }.map { it.label }.toSet()
            if (sections.isNotEmpty() && sectionKey !in sections) {
                out += "$id: result expands '$sectionKey' but the control is drawn in $sections"
            }
            for (region in regions.filter { it.kind != "section" }) {
                val handled = if (region.kind == "visible" && region.label == "wordPredictionAdvancedExpanded") {
                    id in advancedPanelIds
                } else {
                    gates.any { gate ->
                        gate != id && controls.any { gc ->
                            gc.id == gate && effectiveRegions(gc.file, gc.pos).none { it == region }
                        }
                    }
                }
                if (!handled) out += "$id: hidden by $region; gates=$gates"
            }
            out
        }
        return perControl.minByOrNull { it.size } ?: emptyList()
    }

    @Test
    fun theWalkSeesTheStructureItChecks() {
        check(controls.size > 100) { "only ${controls.size} controls found — the scanner is broken" }
        // The nested block that started this: LearningDataManagerBlock's slider is inside the
        // Advanced panel and the word-prediction switch's `if`, reached through its call site.
        val slider = controls.single { it.id == "input_personalization_max_words_title" }
        val labels = effectiveRegions(slider.file, slider.pos).map { "${it.kind}(${it.label})" }
        assertWithMessage("regions around Max Learned Words").that(labels)
            .containsAtLeast("if(wordPredictionEnabled)", "visible(wordPredictionAdvancedExpanded)", "section(input)")
    }

    @Test
    fun everyIndexEntryResolvesToItsControlOrAGate() {
        check(GENERATED_SEARCH_ENTRIES.isNotEmpty()) { "GENERATED_SEARCH_ENTRIES empty — generator did not run" }
        val unresolved = GENERATED_SEARCH_ENTRIES.flatMap { problems(it.settingId, it.sectionKey, it.gateIds) }
        assertWithMessage("search entries whose result can land nowhere")
            .that(unresolved).isEmpty()
    }
}
