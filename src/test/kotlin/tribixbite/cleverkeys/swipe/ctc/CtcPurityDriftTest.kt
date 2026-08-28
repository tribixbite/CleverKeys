package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.PackagePurityScan

/**
 * NFR-1 purity enforcement for the CTC swipe engine (`docs/specs/ctc-swipe-engine.md`:
 * "the core never touches Android or SharedPreferences — pure JVM, testable via
 * `runPureTests` (matches `swipe/geometric/` NFR-3)").
 *
 * `swipe/ctc/` is pure TODAY, and 13 pure test classes decode through it on every
 * `runPureTests` — but nothing ENFORCED the property, so the first `android.util.Log`
 * added for debugging would have converted those 13 classes into a
 * `NoClassDefFoundError` at once, with no guard naming the cause. This is the
 * geometric package's [tribixbite.cleverkeys.swipe.geometric.GeoPurityDriftTest]
 * scoped to `swipe/ctc/` (ARC-024).
 *
 * Scope is the ENGINE PACKAGE ONLY. `swipe/CtcEngineAdapter.kt` sits deliberately
 * outside it and IS Android-bound (assets, `SharedPreferences`, ONNX session loading) —
 * that split is the contract, not an oversight.
 *
 * The scan is a token regex `\bandroidx?\.` over COMMENT-STRIPPED sources rather than an
 * import-line check, because the codebase's own leak pattern is a fully-qualified
 * `android.util.Log.w(...)` with NO import line (`Keyboard2View.kt:1124`). See
 * [PackagePurityScan] for the shared string-literal-aware stripper.
 */
class CtcPurityDriftTest {

    private val engineDir = File("src/main/kotlin/tribixbite/cleverkeys/swipe/ctc")

    /**
     * Floor on the package's file count. A rename/move that emptied the directory would
     * otherwise make the token scan vacuously green. 16 files at the time of writing.
     */
    private val minimumSourceFiles = 12

    @Test
    fun ctcEngineSourcesContainNoAndroidTokens() {
        assertWithMessage("CTC engine package dir must exist: ${engineDir.absolutePath}")
            .that(engineDir.isDirectory).isTrue()

        val ktFiles = PackagePurityScan.kotlinSources(engineDir)
        assertWithMessage(
            "CTC engine package looks empty or moved (${ktFiles.size} Kotlin files) — a " +
                "vacuous pass would hide a real purity break. Update minimumSourceFiles " +
                "deliberately if the package legitimately shrank."
        ).that(ktFiles.size).isAtLeast(minimumSourceFiles)

        for (file in ktFiles) {
            val match = PackagePurityScan.firstAndroidToken(file)
            assertWithMessage(
                "PURITY VIOLATION in ${file.name}: found an android/androidx token " +
                    "'${match?.value}' near index ${match?.range?.first}. NFR-1: the CTC " +
                    "engine package must have ZERO android.*/androidx.* usage (imports OR " +
                    "fully-qualified) — every pure CTC test decodes through it under " +
                    "runPureTests, where an Android class cannot load. Android-bound work " +
                    "belongs in swipe/CtcEngineAdapter.kt, outside this package."
            ).that(match).isNull()
        }
    }

    @Test
    fun ctcEngineDeclaresNoAndroidImports() {
        // Belt-and-braces on the token scan: an explicit import line is the OTHER half of
        // the leak surface, and naming it separately makes a failure self-diagnosing.
        for (file in PackagePurityScan.kotlinSources(engineDir)) {
            val offending = file.readLines()
                .map { it.trim() }
                .filter { it.startsWith("import android.") || it.startsWith("import androidx.") }
            assertWithMessage("PURITY VIOLATION: ${file.name} imports Android classes: $offending")
                .that(offending).isEmpty()
        }
    }
}
