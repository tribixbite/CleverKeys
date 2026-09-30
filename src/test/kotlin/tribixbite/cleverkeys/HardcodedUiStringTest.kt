package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against user-visible English literals in Kotlin UI code.
 *
 * Why: the 2026-09-29 on-device run (Seeker phone, app locales fa and hu) found English text
 * in otherwise translated screens — setup-card descriptions and "✓ Done" in the launcher,
 * "Imported: <name> (<n> words)" and "None" in the language-pack section, "None" in the
 * keyboard's language-toggle message. Every string resource was translated; these strings
 * were simply never resources. Lint's `HardcodedText` only covers XML layouts and
 * `SetTextI18n` only covers `TextView.setText`, so nothing flagged Compose `Text("…")`,
 * named Compose parameters, toasts, dialogs built in Kotlin, or dropdown option lists.
 *
 * The scan is a pure source read (no Android runtime). It looks for a string literal that
 * reads as prose — its first letter is an upper-case letter followed by a lower-case letter,
 * or a run of capitals followed by a space ("LONG SWIPE …"), or exactly "OK" — in a
 * position whose value is rendered to the user ([sinks]). Identifiers, pref values, MIME
 * types and format patterns start lower-case or are single all-caps tokens ("BALANCED"),
 * so they do not match. Comments are blanked first (newlines kept, so line numbers stay
 * right). Justified exceptions live in [allowed] with a reason.
 *
 * When this fails: move the text to `res/values/strings.xml` and translate it into all 21
 * locales (see docs/specs/testing-strategy.md, "Translation verification"), or — if the
 * literal is genuinely not user-visible or must not be translated — add it to [allowed]
 * with the reason.
 */
class HardcodedUiStringTest {

    private val sourceRoot = KotlinSourceScan.sourceRoot

    /** A literal that reads as user-facing prose. See the class KDoc. */
    private val prose = "\"[^\"\\p{L}\\p{N}_\\\\$\\n]{0,3}(?:\\p{Lu}\\p{Ll}|\\p{Lu}{2,}\\s|OK\")"

    /** Named Compose/View parameters and state fields whose value is shown on screen. */
    private val displayNames = listOf(
        "text", "title", "description", "label", "contentDescription", "message", "question",
        "answer", "hint", "placeholder", "subtitle", "summary", "resultTitle", "resultMessage",
        "feedbackText", "displayName", "confirmLabel", "dismissLabel", "statusText",
        "languagePackImportStatus", "clipboardCustomRulesStatus", "gifImportStatus",
    ).joinToString("|")

    /**
     * One rendering position. [scope] limits a sink to path prefixes when the shape is also
     * common in non-UI code (`when` branches and `append` calls build log/debug text too).
     */
    private data class Sink(val name: String, val regex: Regex, val scope: List<String>? = null)

    private val sinks: List<Sink> by lazy {
        val uiScope = listOf("ui/", "activities/", "customization/", "clipboard/", "prefs/",
            "Keyboard2View.kt", "KeyboardReceiver.kt", "BackupRestoreResultMessages.kt",
            "BackupRestorePreviewDialogs.kt", "WordListFragment.kt")
        listOf(
            Sink("Compose Text()", Regex("\\bText\\(\\s*$prose")),
            // `=(?!=)` keeps `text == "Foo"` comparisons out; `[^\n"]*?` reaches the first
            // literal on the line so `if (expanded) "Collapse" else …` is still seen.
            Sink("displayed parameter", Regex("\\b(?:$displayNames)\\s*=(?!=)[^\\n\"]*?$prose")),
            Sink("Toast", Regex("makeText\\(\\s*[^,()]+,\\s*$prose")),
            Sink("AlertDialog", Regex(
                "\\.(?:setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton)\\(\\s*$prose"
            )),
            Sink("status message", Regex(
                "\\b(?:showSuggestionBarMessage|showToast|headlessToast|Ok)\\(\\s*$prose"
            )),
            Sink("dropdown options", Regex("\\boptions\\s*=\\s*listOf\\(\\s*$prose")),
            Sink("Icon description", Regex("\\bIcon\\(\\s*[^,()]+,\\s*$prose")),
            Sink("text builder", Regex("\\bappend(?:Line)?\\(\\s*$prose"),
                listOf("ui/", "activities/", "BackupRestoreResultMessages.kt")),
            Sink("when-branch label", Regex("->\\s*$prose"), uiScope),
        )
    }

    /**
     * Justified exceptions: `"<path relative to sourceRoot>|<literal without quotes>"` ->
     * reason. A whole file is excluded with the literal `*`. Keep every reason specific.
     */
    private val allowed: Map<String, String> = mapOf(
        // Brand name: CleverKeys is never translated (app_name is translatable="false").
        "activities/LauncherActivity.kt|CleverKeys" to "brand wordmark; app_name is translatable=false",
        // File-format names are proper nouns rendered identically in every locale.
        "clipboard/ClipboardMediaManager.kt|WebP" to "image format name",
        "clipboard/ClipboardMediaManager.kt|WebM" to "video format name",
        // Key-cap abbreviations printed identically on every keyboard (the descriptive extra-key
        // names — Caps Lock, Page Up, Select All, … — are string resources).
        "prefs/ExtraKeysPreference.kt|Esc" to "key-cap abbreviation",
        "prefs/ExtraKeysPreference.kt|Fn" to "key-cap abbreviation",
    )

    private data class Hit(val path: String, val line: Int, val sink: String, val literal: String)

    private fun stripComments(src: String) = KotlinSourceScan.stripComments(src)

    private fun literalAt(src: String, matchEnd: Int, matchValue: String): String {
        // The literal starts at the last quote the sink regex consumed as its opening quote.
        val proseStart = Regex("$prose$").find(matchValue)?.range?.first ?: 0
        val start = matchEnd - matchValue.length + proseStart + 1
        val end = src.indexOfAny(charArrayOf('"', '\n'), start).let { if (it < 0) src.length else it }
        return src.substring(start, end)
    }

    internal fun scan(relPath: String, source: String): List<Pair<String, String>> {
        val stripped = stripComments(source)
        val out = mutableListOf<Pair<String, String>>()
        for (sink in sinks) {
            if (sink.scope != null && sink.scope.none { relPath.startsWith(it) }) continue
            for (m in sink.regex.findAll(stripped)) {
                val line = stripped.substring(0, m.range.last + 1).count { it == '\n' } + 1
                out += "${sink.name}@$line" to literalAt(stripped, m.range.last + 1, m.value)
            }
        }
        return out
    }

    private fun isAllowed(relPath: String, literal: String): Boolean {
        if (allowed.containsKey("$relPath|*")) return true
        if (allowed.containsKey("$relPath|$literal")) return true
        return false
    }

    @Test fun noHardcodedUserVisibleLiterals() {
        assertTrue("run with the project root as CWD", sourceRoot.isDirectory)
        val hits = mutableListOf<Hit>()
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.relativeTo(sourceRoot).invariantSeparatorsPath
            val source = file.readText()
            for ((where, literal) in scan(rel, source)) {
                val (sink, line) = where.split("@").let { it[0] to it[1].toInt() }
                if (!isAllowed(rel, literal)) hits += Hit(rel, line, sink, literal)
            }
        }
        val distinct = hits.distinctBy { Triple(it.path, it.line, it.literal) }
            .sortedWith(compareBy({ it.path }, { it.line }))
        assertTrue(
            "${distinct.size} hardcoded user-visible literal(s) in " +
                "${distinct.map { it.path }.toSet().size} file(s). Move each to a string " +
                "resource translated into all 21 locales, or allowlist it in " +
                "HardcodedUiStringTest.allowed with a reason:\n" +
                distinct.joinToString("\n") { "${it.path}:${it.line} [${it.sink}] \"${it.literal.take(80)}\"" },
            distinct.isEmpty()
        )
    }

    /**
     * Activity/service labels in the manifest are user-visible too — the Recents title of each
     * settings screen and the keyboard's name in the system's keyboard list — and lint's
     * `HardcodedText` does not cover the manifest.
     */
    @Test fun manifestLabelsAreStringResources() {
        val manifest = java.io.File("AndroidManifest.xml").readText()
        val literal = Regex("android:label=\"([^\"@][^\"]*)\"")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("hardcoded android:label value(s) in AndroidManifest.xml: $literal", literal.isEmpty())
    }

    /** Pins the matcher on synthetic sources so a regex edit cannot silently widen/narrow it. */
    @Test fun scannerFlagsProseAndIgnoresIdentifiers() {
        fun literals(src: String, path: String = "ui/X.kt") = scan(path, src).map { it.second }

        assertEquals(listOf("Configure keys"), literals("Text(\"Configure keys\")"))
        assertEquals(listOf("Collapse"),
            literals("contentDescription = if (expanded) \"Collapse\" else \"Expand\""))
        assertEquals(listOf("✓ Done"), literals("text = if (done) \"✓ Done\" else d"))
        assertEquals(listOf("Import failed"),
            literals("Toast.makeText(\n    this,\n    \"Import failed\", Toast.LENGTH_SHORT)"))
        assertEquals(listOf("OK"), literals(".setPositiveButton(\"OK\", null)"))
        assertEquals(listOf("LONG SWIPE → word"), literals("feedbackText = \"LONG SWIPE → word\""))
        assertEquals(listOf("Sparkle"), literals("options = listOf(\"Sparkle\", \"Glow\")"))
        assertEquals(listOf("None"), literals("\"none\" -> \"None\""))

        // Not prose: identifiers, pref values, patterns, MIME types, comparisons, comments.
        assertEquals(emptyList<String>(), literals("placeholder = { Text(\"com.example.app\") }"))
        assertEquals(emptyList<String>(), literals("\"x\" -> \"BALANCED\""))
        assertEquals(emptyList<String>(), literals("if (text == \"Foo\") return"))
        assertEquals(emptyList<String>(), literals("launcher.launch(\"image/*\")\nval t = 1"))
        assertEquals(emptyList<String>(), literals("// Text(\"Commented out\")"))
        assertEquals(emptyList<String>(), literals("/**\n * Text(\"In KDoc\")\n */\nval a = 1"))
        // when-branch labels are only checked in UI scope (log builders elsewhere use `->`).
        assertEquals(emptyList<String>(), literals("\"a\" -> \"Some label\"", path = "swipe/Engine.kt"))
        // Comment stripping keeps line numbers exact.
        val src = "/**\n * doc\n */\nval x = 1\nText(\"Hello there\")"
        assertEquals("Compose Text()@5", scan("ui/X.kt", src).single().first)
    }
}
