package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Drift guards for the dead/unwired-plumbing findings of the 2026-08-29 second-pass audit
 * (`docs/audit/2026-08-28-archive-verification.md`).
 *
 * The finding below has a failure mode that no compiler warning catches: code that is
 * reachable-by-linker but unreachable-by-execution, kept alive by blanket ProGuard keeps or
 * by a settings screen that writes a preference nothing reads. Deleting it once is not
 * enough — a later refactor can reintroduce the same shape. These scans pin the deletions.
 *
 *  - **ARC-084** — the CGR chain (`storeCGRPredictions` and everything it fed) had zero
 *    callers while CLAUDE.md and ADR-011 assert the project has no CGR. R8 shipped it
 *    anyway because `Keyboard2View`/`CleverKeysService` are covered by `-keep {*;}`.
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

}
