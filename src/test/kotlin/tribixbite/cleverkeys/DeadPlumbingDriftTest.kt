package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift guards for the dead/unwired-plumbing findings of the 2026-08-29 second-pass audit
 * (`docs/audit/2026-08-28-archive-verification.md`).
 *
 * All three findings share a failure mode that no compiler warning catches: code that is
 * reachable-by-linker but unreachable-by-execution, kept alive by blanket ProGuard keeps or
 * by a settings screen that writes a preference nothing reads. Deleting it once is not
 * enough — a later refactor can reintroduce the same shape. These scans pin the deletions.
 *
 *  - **ARC-084** — the CGR chain (`storeCGRPredictions` and everything it fed) had zero
 *    callers while CLAUDE.md and ADR-011 assert the project has no CGR. R8 shipped it
 *    anyway because `Keyboard2View`/`CleverKeysService` are covered by `-keep {*;}`.
 *  - **ARC-085** — the "Correction Style" dropdown wrote `swipe_correction_preset`, which
 *    no `Config`, predictor or engine adapter ever read: a control that responded to touch
 *    and changed nothing.
 *  - **ARC-097** — `SuggestionOrigin.forRoutedEngine` had zero production callers while its
 *    own KDoc and three shipped docs named it THE production mechanism; the two dispatch
 *    sites passed enum literals instead.
 *
 * Source-scan convention (project root as CWD) matches [CoreImeHygieneDriftTest] and
 * [LearningWiringDriftTest].
 */
class DeadPlumbingDriftTest {

    private val mainKotlin = File("src/main/kotlin")

    private fun kotlinSources(): List<File> {
        check(mainKotlin.isDirectory) {
            "Source dir not found at ${mainKotlin.absolutePath} — drift test must run with project root as CWD."
        }
        return mainKotlin.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun source(relative: String): String {
        val f = File(mainKotlin, relative)
        assertWithMessage("expected source file to exist: ${f.path} (run from project root)")
            .that(f.isFile).isTrue()
        return f.readText()
    }

    /**
     * True for a line that is entirely a comment (`//`, a KDoc/block-comment continuation, or
     * a block opener). Deleting dead plumbing leaves TOMBSTONE comments behind on purpose —
     * they are the record of why the code is not coming back and they necessarily name the
     * deleted symbols. The invariant these scans enforce is about CODE, so comment-only lines
     * are not violations; a real re-introduction is always an executable line.
     */
    private fun isCommentOnly(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    /**
     * Reports every `<path>:<line>` in main Kotlin sources whose CODE (comment-only lines
     * excluded, see [isCommentOnly]) matches [pattern], skipping files whose repo-relative
     * path is in [allowedFiles].
     */
    private fun occurrences(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> {
        val hits = mutableListOf<String>()
        kotlinSources().forEach { file ->
            val relative = file.path.removePrefix(mainKotlin.path).trimStart('/')
            if (relative in allowedFiles) return@forEach
            file.readLines().forEachIndexed { index, line ->
                if (!isCommentOnly(line) && pattern.containsMatchIn(line)) {
                    hits += "$relative:${index + 1}: ${line.trim()}"
                }
            }
        }
        return hits
    }

    // ---------------------------------------------------------------- ARC-084

    @Test
    fun `ARC-084 - the dead CGR prediction chain is gone`() {
        // Every symbol of the chain: the store/clear entry points, the backing state, the
        // two getters they fed, the KeyboardDimensionsHelper consumers and the two
        // CleverKeysService hatches that re-exported them.
        val cgrChain = Regex(
            "storeCGRPredictions|clearCGRPredictions|getCGRPredictions|areCGRPredictionsFinal|" +
                "updateCGRPredictions|checkCGRPredictions|_cgrPredictions|_cgrFinalPredictions"
        )
        val hits = occurrences(cgrChain)
        assertWithMessage(
            "ARC-084: the CGR prediction chain has no callers and no producer — " +
                "`_cgrPredictions` can never be non-empty, so every consumer is dead code that " +
                "R8 nonetheless ships (blanket `-keep {*;}`). CLAUDE.md and ADR-011 both state " +
                "the project has no CGR. Do not reintroduce it; the swipe pipeline delivers " +
                "predictions through InputCoordinator.handlePredictionResults.\nFound:\n" +
                hits.joinToString("\n")
        ).that(hits).isEmpty()
    }

    // ---------------------------------------------------------------- ARC-099

    @Test
    fun `ARC-099 - the dead legacy swipe-prediction chain is gone`() {
        // Same shape as ARC-084, found during that deletion: three KeyboardDimensionsHelper
        // "legacy" methods that pushed a caller-supplied list straight into the suggestion
        // bar, plus three CleverKeysService pass-throughs that re-exported them. Nothing
        // called the pass-throughs and nothing called the helpers directly, so no list could
        // ever reach the bar through this path — the live route is
        // InputCoordinator.handlePredictionResults. R8 shipped it regardless: both classes
        // are covered by blanket `-keep class ... { *; }` rules (proguard-rules.pro:39, :91),
        // which is exactly why no build-time signal ever flagged it.
        val legacyChain = Regex(
            "\\b(updateSwipePredictions|completeSwipePredictions|clearSwipePredictions)\\b"
        )
        val hits = occurrences(legacyChain)
        assertWithMessage(
            "ARC-099: the legacy swipe-prediction chain has zero callers — the three " +
                "KeyboardDimensionsHelper methods were reachable only through three " +
                "CleverKeysService pass-throughs that nothing called. Do not reintroduce it; " +
                "swipe predictions reach the suggestion bar through " +
                "InputCoordinator.handlePredictionResults.\nFound:\n" + hits.joinToString("\n")
        ).that(hits).isEmpty()
    }

    // ---------------------------------------------------------------- ARC-085

    @Test
    fun `ARC-085 - the dead swipe_correction_preset control is gone`() {
        // The key survives in exactly ONE place: SettingsValidation.DEPRECATED_KEYS. That
        // list is what stops a v1.5.x backup (which exported the key, because it was in
        // SETTINGS_DEFAULTS) from showing a meaningless import-preview row and writing a
        // dead key back into prefs. Anything BEYOND the tombstone is live plumbing again.
        val tombstone = "tribixbite/cleverkeys/backup/SettingsValidation.kt"
        val prefHits = occurrences(Regex("swipe_correction_preset"), allowedFiles = setOf(tombstone))
        assertWithMessage(
            "ARC-085: `swipe_correction_preset` is read by nothing — no Config field, no " +
                "predictor, no engine adapter. The \"Correction Style\" dropdown that wrote it " +
                "responded to touch and changed nothing. Only the " +
                "SettingsValidation.DEPRECATED_KEYS tombstone may name it.\nFound:\n" +
                prefHits.joinToString("\n")
        ).that(prefHits).isEmpty()

        // The Compose state field is what makes a dead key look alive (same reasoning the
        // ARC-051 comment in SettingsActivity records for the six keys deleted before it).
        val fieldHits = occurrences(Regex("\\bswipeCorrectionPreset\\b"))
        assertWithMessage(
            "ARC-085: the `swipeCorrectionPreset` Compose state field must go with the " +
                "dropdown — a backing field loaded on every settings open and rewritten on " +
                "every preference change is exactly what disguises a dead pref as a live one." +
                "\nFound:\n" + fieldHits.joinToString("\n")
        ).that(fieldHits).isEmpty()
    }

    @Test
    fun `ARC-085 - the Correction Style strings are gone from every locale`() {
        // Left behind, these are unused-translation lint warnings in 21 locales plus the
        // default. res/ is scanned directly (the strings never had a Kotlin reference once
        // the dropdown went).
        val resDir = File("res")
        check(resDir.isDirectory) {
            "res/ not found at ${resDir.absolutePath} — drift test must run with project root as CWD."
        }
        val styleStrings = Regex("""<string name="autocorrect_style_(title|desc)"""")
        val hits = resDir.walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (styleStrings.containsMatchIn(line)) "${file.path}:${index + 1}" else null
                }
            }
            .toList()
        assertWithMessage(
            "ARC-085: `autocorrect_style_title` / `autocorrect_style_desc` labelled the deleted " +
                "\"Correction Style\" dropdown. Every locale copy must go too, or lint reports " +
                "unused translations.\nFound:\n" + hits.joinToString("\n")
        ).that(hits).isEmpty()
    }

    // ---------------------------------------------------------------- ARC-097

    @Test
    fun `ARC-097 - both routed-engine dispatches derive their origin from forRoutedEngine`() {
        // The documented contract (SuggestionProvenance KDoc, docs/ARCHITECTURE_MASTER.md,
        // docs/specs/ctc-swipe-engine.md, docs/wiki/specs/typing/swipe-typing-spec.md) is
        // that the origin attached to a swipe suggestion is the ROUTED engine's, derived via
        // SuggestionOrigin.forRoutedEngine. SuggestionProvenanceTest pins what that function
        // ANSWERS for each engine; this pins that production actually asks it. Passing the
        // enum literal instead produces the same tag today but leaves the mapping duplicated
        // in three places, so a future engine (or a remap) silently diverges — which is the
        // whole reason the helper exists.
        val coordinator = source("tribixbite/cleverkeys/InputCoordinator.kt")

        assertWithMessage(
            "ARC-097: the geometric decode callback must tag its results with " +
                "SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.GEOMETRIC)."
        ).that(coordinator)
            .contains("SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.GEOMETRIC)")

        assertWithMessage(
            "ARC-097: the CTC decode callback must tag its results with " +
                "SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.CTC)."
        ).that(coordinator)
            .contains("SuggestionOrigin.forRoutedEngine(SwipeEngineRouter.Engine.CTC)")

        // No dispatch site may fall back to a bare literal — that is the bypass this finding
        // is about. (SuggestionProvenance.kt itself and the bar's colour table legitimately
        // name the enum constants; only the dispatcher is constrained.)
        val bareLiteral = Regex("""SuggestionOrigin\.(GEOMETRIC|CTC)\b""")
        val literalHits = coordinator.lines().mapIndexedNotNull { index, line ->
            if (!isCommentOnly(line) && bareLiteral.containsMatchIn(line)) {
                "InputCoordinator.kt:${index + 1}: ${line.trim()}"
            } else null
        }
        assertWithMessage(
            "ARC-097: InputCoordinator must not pass a bare SuggestionOrigin enum literal as a " +
                "swipe origin — route it through forRoutedEngine so the engine→origin mapping " +
                "has exactly one implementation.\nFound:\n" + literalHits.joinToString("\n")
        ).that(literalHits).isEmpty()
    }
}
