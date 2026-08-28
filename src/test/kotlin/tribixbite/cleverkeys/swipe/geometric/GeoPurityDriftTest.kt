package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.PackagePurityScan

/**
 * NFR-3 purity enforcement: the geometric swipe engine package must contain ZERO
 * `android.*` / `androidx.*` usage — imports OR fully-qualified references — so it
 * runs unmodified under `runPureTests` and can never accidentally load a stubbed
 * Android class on the JVM.
 *
 * The scan is a token regex `\bandroidx?\.` over COMMENT-STRIPPED sources: the
 * codebase's own leak pattern is a fully-qualified `android.util.Log.w(...)` with
 * no import line (`Keyboard2View.kt:1124`), which an import-only scan would miss.
 * Comments are stripped first so that a KDoc mentioning "android" in prose does not
 * false-positive.
 *
 * The scan itself lives in [PackagePurityScan], shared with the CTC engine's
 * equivalent guard ([tribixbite.cleverkeys.swipe.ctc.CtcPurityDriftTest], NFR-1);
 * this class keeps the stripper's own unit test, which it originally owned.
 */
class GeoPurityDriftTest {

    private val engineDir = File("src/main/kotlin/tribixbite/cleverkeys/swipe/geometric")

    /** Matches an `android.` or `androidx.` token at a word boundary. */
    private val androidToken = PackagePurityScan.ANDROID_TOKEN

    @Test
    fun engineSourcesContainNoAndroidTokens() {
        assertWithMessage("engine package dir must exist: ${engineDir.absolutePath}")
            .that(engineDir.isDirectory).isTrue()

        val ktFiles = PackagePurityScan.kotlinSources(engineDir)
        assertWithMessage("engine package must contain Kotlin sources")
            .that(ktFiles).isNotEmpty()

        for (file in ktFiles) {
            val match = PackagePurityScan.firstAndroidToken(file)
            assertWithMessage(
                "PURITY VIOLATION in ${file.name}: found an android/androidx token " +
                    "'${match?.value}' near index ${match?.range?.first}. The engine package " +
                    "must have ZERO android.*/androidx.* usage (imports OR fully-qualified)."
            ).that(match).isNull()
        }
    }

    @Test
    fun stripComments_isStringLiteralAware_noFalseNegativeWindow() {
        // A `/*` INSIDE a string literal must not open a comment — otherwise real code
        // (with an android.* reference) between it and the next `*/` would be silently
        // excluded from the scan (a false-negative window). Same for `//` in a URL.
        val blockInString = "val x = \"/*\"; android.util.Log.w(\"t\", \"m\")"
        assertWithMessage("a /* inside a string must not hide following code")
            .that(androidToken.containsMatchIn(stripComments(blockInString))).isTrue()
        val lineInString = "val u = \"https://x\"; android.util.Log.w(\"t\", \"m\")"
        assertWithMessage("a // inside a string must not truncate the rest of the line")
            .that(androidToken.containsMatchIn(stripComments(lineInString))).isTrue()
        // Real comments are still stripped; a literal "android." in a string is still
        // (correctly) flagged.
        assertWithMessage("real comments must still be stripped")
            .that(androidToken.containsMatchIn(stripComments("/* android.util */ val y = 1 // android.os")))
            .isFalse()
        assertWithMessage("a literal android. string must still be flagged")
            .that(androidToken.containsMatchIn(stripComments("val z = \"android.util.Log\"")))
            .isTrue()
    }

    private fun stripComments(src: String): String = PackagePurityScan.stripComments(src)
}
