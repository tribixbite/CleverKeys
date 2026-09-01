package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ARC-072 slice 3 — the composition root replaces the `*Initializer` factory files.
 *
 * The six `*Initializer` classes (Manager/Prediction/Propagator/Receiver/SubtypeLayout/
 * SuggestionBar, ~841 lines) were pure construction spread over six files: each a
 * `create()` + `initialize()` that newed up objects and returned a result holder. That is
 * exactly what a hand-written composition root does, so they collapsed into
 * `wiring/KeyboardComponentGraph.kt` (dir-only grouping — the file keeps
 * `package tribixbite.cleverkeys`, per the R4 convention). The Bridges are KEPT (genuine
 * delegation seams, `CleverKeysService` forwards through them) and live in `wiring/` too.
 *
 * This test pins the end state so the pattern cannot quietly re-grow:
 *  - no `*Initializer.kt` file may exist under src/main,
 *  - the graph file must exist at its pinned path,
 *  - `CleverKeysService` must not call `<X>Initializer.create(...)` factories.
 *
 * Runs with the project root as CWD (same convention as the other drift tests).
 */
class WiringCompositionRootDriftTest {

    private val mainKotlin = File("src/main/kotlin")

    private fun requireProjectRoot() {
        check(mainKotlin.isDirectory) {
            "Source dir not found at ${mainKotlin.absolutePath} — drift test must run with project root as CWD."
        }
    }

    @Test
    fun noInitializerFilesRemainUnderSrcMain() {
        requireProjectRoot()
        val initializerFiles = mainKotlin.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Initializer.kt") }
            .map { it.path }
            .toList()
        assertWithMessage(
            "ARC-072 slice 3 retired the *Initializer factory-file pattern into " +
                "wiring/KeyboardComponentGraph.kt. New construction belongs in the graph, " +
                "not in a fresh Initializer file."
        ).that(initializerFiles).isEmpty()
    }

    @Test
    fun compositionRootExistsAtItsPinnedPath() {
        requireProjectRoot()
        val graph = File(mainKotlin, "tribixbite/cleverkeys/wiring/KeyboardComponentGraph.kt")
        assertWithMessage(
            "wiring/KeyboardComponentGraph.kt is the single composition root that absorbed " +
                "the six *Initializer files (ARC-072 slice 3). It must exist at this path " +
                "(dir-only grouping; package stays tribixbite.cleverkeys)."
        ).that(graph.isFile).isTrue()
    }

    @Test
    fun serviceDoesNotCallInitializerFactories() {
        requireProjectRoot()
        val service = File(mainKotlin, "tribixbite/cleverkeys/CleverKeysService.kt")
        assertWithMessage("CleverKeysService.kt must exist").that(service.isFile).isTrue()
        val offending = Regex("""\w*Initializer\s*\.\s*create\(""")
            .findAll(service.readText())
            .map { it.value }
            .toList()
        assertWithMessage(
            "CleverKeysService must wire through KeyboardComponentGraph reads, not " +
                "Initializer factory calls (onCreate is graph construction + reads)."
        ).that(offending).isEmpty()
    }
}
