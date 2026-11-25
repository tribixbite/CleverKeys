package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Guards review tooling by rejecting binary NUL bytes in source-controlled text files, and keeps
 * the tag workflow's whitespace preflight green between releases.
 */
class SourceTextHygieneTest {

    /**
     * The release workflow (`.github/workflows/release.yml`, "Source hygiene preflight") runs
     * `git diff --check <previous v-tag>..HEAD` and fails the tag on any whitespace error. The
     * 2026-11-25 pre-tag review found 24 of them (B2): nothing ran the check between releases,
     * and `scripts/prepare-release.sh` compared against the newest tag of any kind (a CI
     * `dev-*` tag), so the local preflight passed where CI would fail. This runs the same check
     * on every pure-suite run (on the working tree, so a fix shows before it is committed). Files kept byte-identical to an outside source (the F-Droid
     * recipe copied from fdroiddata, recorded `.patch` diffs) are exempt in `.gitattributes`
     * with `-whitespace`, which `git diff --check` honours.
     *
     * Skipped where git or the previous release tag is not available (a shallow CI checkout).
     */
    @Test
    fun noWhitespaceErrorsSinceThePreviousRelease() {
        val previousTag = git("describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD")
        assumeTrue("git or a v* tag is not available here", previousTag != null && previousTag.exitCode == 0)
        val tag = previousTag!!.output.trim()
        // The working tree against the tag: what the next commit would make of `$tag..HEAD`.
        val check = git("diff", "--check", tag)!!
        assertWithMessage(
            "git diff --check $tag fails the release workflow's preflight:\n${check.output}"
        ).that(check.exitCode).isEqualTo(0)
    }

    /**
     * `prepare-release.sh` runs before the tag exists, so it describes from HEAD; it must still
     * match only release tags, as the workflow does (CK-150-017, regressed and found again by the
     * 2026-11-25 review).
     */
    @Test
    fun prepareReleaseComparesAgainstThePreviousReleaseTag() {
        val script = File("scripts/prepare-release.sh").readText()
        val describe = script.lines().single { "describe --tags" in it && "PREVIOUS_RELEASE=" in it }
        assertWithMessage("the previous-release lookup must ignore dev-* tags")
            .that(describe).contains("--match 'v*'")
    }

    /** One finished git command: its exit code and its combined output. */
    private data class GitResult(val exitCode: Int, val output: String)

    /** Runs git in the project root (the test CWD); null when git cannot be started. */
    private fun git(vararg args: String): GitResult? = try {
        val process = ProcessBuilder(listOf("git", *args)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            GitResult(process.exitValue(), output)
        }
    } catch (e: java.io.IOException) {
        null
    }

    @Test
    fun trackedTextSourcesContainNoNulBytes() {
        val roots = listOf("src", "res", "docs", ".github", "scripts")
        val extensions = setOf(
            "kt", "kts", "java", "xml", "md", "yml", "yaml", "json", "gradle",
            "properties", "sh", "py", "js", "mjs", "ts", "tsx", "css", "html",
        )
        val offenders = ArrayList<String>()

        for (rootName in roots) {
            val root = File(rootName)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { dir ->
                    dir.name !in setOf("build", "dist", "node_modules", ".git")
                }
                .filter { it.isFile && it.extension.lowercase() in extensions }
                .forEach { file ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var found = false
                        while (!found) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            found = buffer.copyOf(read).any { it == 0.toByte() }
                        }
                        if (found) offenders.add(file.invariantSeparatorsPath)
                    }
                }
        }

        assertWithMessage(
            "Text source files containing NUL bytes become binary to Git and evade review"
        ).that(offenders).isEmpty()
    }
}
