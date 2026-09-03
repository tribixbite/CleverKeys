package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Drift detection for the hand-maintained test-runner lists in `build.gradle`.
 *
 * ARM64 Termux can't use Gradle's `testDebugUnitTest` (disabled), so tests are
 * executed by two custom [JavaExec] tasks that pass an EXPLICIT list of
 * fully-qualified class names to JUnitCore:
 *
 *   - `runPureTests`  → `def pureTestClasses = [ … ]`
 *   - `runMockTests`  → `def mockTestClasses = [ … ]`
 *
 * Because those lists are literal arrays, a newly-added `*Test` class is
 * silently NEVER RUN until someone remembers to register it. This test scans
 * `src/test/` for every `*Test` class and asserts each is either registered in
 * one of the two runner lists or explicitly acknowledged as unrunnable.
 *
 * **When this test fails**, fix the underlying registration — do not suppress
 * it by padding [knownUnrunnable]. The legitimate actions are:
 *
 *   - Add the class to `pureTestClasses` (pure JVM, no android.jar) or
 *     `mockTestClasses` (needs android.jar stubs) in `build.gradle`.
 *   - If the class genuinely cannot run under either runner (e.g. it needs a
 *     real Android runtime / Robolectric), add it to [knownUnrunnable] with a
 *     comment explaining why.
 *
 * Runs with the project root as CWD (same convention as
 * `backup/SettingsDefaultsDriftTest`).
 */
class TestRunnerListDriftTest {

    // Classes that exist under src/test but cannot be executed by either
    // JUnitCore runner (they require a real Android runtime / instrumentation
    // and only compile as reference material). Fully-qualified.
    private val knownUnrunnable = setOf(
        // ComposeKeyTest was deleted 2026-09-03: its cases run for real as
        // androidTest ComposeKeySequenceInstrumentedTest (ew-cli green 0c570be1).
        "tribixbite.cleverkeys.IntegrationTest",
    )

    // Matches an array literal element: a single-quoted FQCN in build.gradle.
    private val classLiteral = Regex("""'([\w.]+)'""")

    // Locates `def <name> = [ … ]` and captures everything up to the closing
    // bracket. DOT_MATCHES_ALL so the multi-line array body is captured.
    private fun runnerArray(name: String): Set<String> {
        val gradle = File("build.gradle")
        check(gradle.exists()) {
            "build.gradle not found at ${gradle.absolutePath} — drift test must run with project root as CWD."
        }
        val text = gradle.readText()
        val block = Regex("""def\s+$name\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
        check(block != null) { "Runner array `def $name = [` not found in build.gradle." }
        return classLiteral.findAll(block.groupValues[1])
            .map { it.groupValues[1] }
            .toSortedSet()
    }

    // Walks src/test and returns the set of fully-qualified `*Test` class names.
    private fun scanTestClasses(): Set<String> {
        val testRoot = File("src/test")
        check(testRoot.exists()) {
            "Source dir not found at ${testRoot.absolutePath} — drift test must run with project root as CWD."
        }
        val packagePattern = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
        val classPattern = Regex(
            """^\s*(?:internal |private |open )?class (\w+Test)\b""",
            RegexOption.MULTILINE,
        )

        val found = sortedSetOf<String>()
        testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val pkg = packagePattern.find(text)?.groupValues?.get(1)
                classPattern.findAll(text).forEach { match ->
                    val simpleName = match.groupValues[1]
                    found += if (pkg != null) "$pkg.$simpleName" else simpleName
                }
            }

        check(found.size > 50) {
            "scanTestClasses found only ${found.size} classes — regex is broken, not a real pass."
        }
        return found
    }

    @Test
    fun everyTestClassIsRegisteredInARunnerList() {
        val all = scanTestClasses()
        val pure = runnerArray("pureTestClasses")
        val mock = runnerArray("mockTestClasses")

        val unregistered = all - (pure + mock + knownUnrunnable)

        assertThat(unregistered).isEmpty()
    }

    @Test
    fun runnerListsContainNoStaleOrDuplicateEntries() {
        val onDisk = scanTestClasses()
        val pure = runnerArray("pureTestClasses")
        val mock = runnerArray("mockTestClasses")

        // A class in BOTH runner lists would run twice (and android.jar
        // ordering differs) — almost certainly a copy-paste error.
        assertThat(pure intersect mock).isEmpty()

        // Every registered class must actually exist on disk (no stale entries
        // left behind after a rename/delete).
        assertThat(pure - onDisk).isEmpty()
        assertThat(mock - onDisk).isEmpty()

        // knownUnrunnable must reference real files, and must not overlap the
        // runner lists (an entry can't be both "can't run" and "registered").
        assertThat(knownUnrunnable - onDisk).isEmpty()
        assertThat(knownUnrunnable intersect (pure + mock)).isEmpty()
    }
}
