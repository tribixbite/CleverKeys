package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Pins the curated instrumented gate in `.github/scripts/emulator-ci.sh`.
 *
 * The release `device-gate` job runs a hand-picked subset of the 90-odd androidTest
 * classes — the ones pinning invariants no pure JVM test can reach (real assets parsed
 * on-device, a real ONNX session, real keyboard geometry, the Keystore). That subset
 * lives in a single shell variable, referenced from nowhere else, which made two silent
 * false-greens possible (CK-150-028):
 *
 *  - a curated class gets renamed, moved to another package, or deleted — `am instrument
 *    -e class …` then matches nothing for it and the gate still reports `OK (…)`;
 *  - the list shrinks by accident (a bad merge on one long line) and nobody notices,
 *    because no test, lint rule, or review checklist reads it.
 *
 * This test closes both: the parsed list must equal [expectedCuratedClasses] exactly, and
 * every entry must resolve to a real `src/androidTest/kotlin` file that declares that class
 * with at least one `@Test`. Changing the gate is therefore a deliberate two-file edit.
 *
 * **When this test fails**, decide which side is wrong and fix that side — do not relax the
 * expectation to match a drifted script. Follows the convention of the other repo-scanning
 * pure tests ([SourceTextHygieneTest], `TestRunnerListDriftTest`): project root as CWD.
 */
class CuratedInstrumentationListTest {

    /**
     * The curated gate, as agreed. Adding a class here without adding it to
     * `emulator-ci.sh` (or vice versa) fails [curatedListMatchesTheCheckedInSet].
     */
    private val expectedCuratedClasses = listOf(
        // Real ONNX session + the bundled per-language CTC assets, on-device.
        "tribixbite.cleverkeys.swipe.CtcMultiLanguageInstrumentedTest",
        // Real keyboard geometry through the geometric decoder.
        "tribixbite.cleverkeys.GeometricSwipeOracleTest",
        // The IME survives the hostile-input paths that used to crash it.
        "tribixbite.cleverkeys.CrashGuardInstrumentedTest",
        // TalkBack virtual-view tree, hover routing and touch paths.
        "tribixbite.cleverkeys.a11y.KeyboardAccessibilityInstrumentedTest",
        // Android Keystore-backed backup passphrase storage (no JVM equivalent).
        "tribixbite.cleverkeys.backup.crypto.BackupPassphraseStoreInstrumentedTest",
        // G3 model parity: bundled encoder vs the frozen `ctc/ctc_golden.json` fixture
        // (ships in the androidTest APK's own assets). Ran nowhere automatic before.
        "tribixbite.cleverkeys.swipe.CtcEmissionModelParityTest",
    )

    private val script = File(".github/scripts/emulator-ci.sh")
    private val androidTestRoot = File("src/androidTest/kotlin")

    /** Extracts the comma-joined `CLASSES="…"` value from the `gate)` case arm. */
    private fun curatedClassesFromScript(): List<String> {
        check(script.isFile) {
            "${script.absolutePath} not found — this test must run with the project root as CWD."
        }
        val text = script.readText()
        val gateArm = Regex("""(?m)^\s*gate\)\s*$""").find(text)
        checkNotNull(gateArm) {
            "`gate)` case arm not found in ${script.path} — the gate may have been renamed."
        }
        val assignment = Regex("""(?m)^\s*CLASSES="([^"\n]*)"\s*$""")
            .find(text.substring(gateArm.range.last))
        checkNotNull(assignment) {
            "No single-line `CLASSES=\"…\"` assignment in the gate) arm of ${script.path}."
        }
        return assignment.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun curatedListMatchesTheCheckedInSet() {
        val actual = curatedClassesFromScript()

        assertWithMessage(
            "Duplicate entries in the emulator-ci.sh curated gate would run a class twice"
        ).that(actual).containsNoDuplicates()

        assertWithMessage(
            "The curated instrumented gate in ${script.path} drifted from the set pinned in " +
                "${javaClass.simpleName}. Update BOTH deliberately, never one to silence the other."
        ).that(actual).containsExactlyElementsIn(expectedCuratedClasses)
    }

    @Test
    fun everyCuratedClassExistsWithAtLeastOneTest() {
        check(androidTestRoot.isDirectory) {
            "${androidTestRoot.absolutePath} not found — this test must run with the project root as CWD."
        }
        val testAnnotation = Regex("""(?m)^\s*@Test\b""")
        val unresolved = ArrayList<String>()
        val withoutTests = ArrayList<String>()

        for (fqcn in curatedClassesFromScript()) {
            val file = File(androidTestRoot, fqcn.replace('.', '/') + ".kt")
            if (!file.isFile) {
                unresolved += "$fqcn (expected ${file.invariantSeparatorsPath})"
                continue
            }
            val text = file.readText()
            val simpleName = fqcn.substringAfterLast('.')
            val declaration = Regex(
                """(?m)^\s*(?:internal |private |open |abstract |sealed )*class $simpleName\b"""
            )
            if (!declaration.containsMatchIn(text)) {
                unresolved += "$fqcn (${file.invariantSeparatorsPath} declares no such class)"
                continue
            }
            if (!testAnnotation.containsMatchIn(text)) withoutTests += fqcn
        }

        assertWithMessage(
            "Curated gate names classes that do not exist — `am instrument -e class` silently " +
                "matches nothing for them and the gate still passes"
        ).that(unresolved).isEmpty()

        assertWithMessage(
            "Curated gate names classes with no @Test — they contribute zero assertions to the gate"
        ).that(withoutTests).isEmpty()
    }
}
