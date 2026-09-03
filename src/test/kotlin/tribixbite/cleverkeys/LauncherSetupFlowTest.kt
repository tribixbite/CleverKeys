package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pins two launcher-screen claims that had nothing defending them:
 *
 *  - **v1.2.8 "Splash animation pauses when the keyboard opens"** — the sparkle background
 *    is an unbounded `withFrameMillis` loop; leaving it running while the IME is up is what
 *    made typing in the launcher's test field lag. [splashAnimationPausesWhileTheImeIsUp]
 *    pins the whole causal chain: IME inset -> `isKeyboardVisible` -> `isPaused` -> the
 *    frame loop living ONLY in the not-paused branch.
 *  - **v1.2.9 "Third setup step guides per-key calibration"** — the numbered setup card that
 *    takes a new user to short-swipe calibration, its completion memory, and the fact that
 *    the Activity it opens is actually declared in the manifest
 *    ([thirdSetupStepOpensPerKeyCalibration], [setupStepsAreNumberedInOrder]).
 *
 * Both are `@Composable` bodies, which no JVM tier can invoke (Compose UI tests require an
 * instrumented host), so this scans the composition source. That still catches every way
 * the feature can be removed or inverted — a deleted `isPaused` argument, a frame loop moved
 * out of the guard, a renumbered or unwired third card, a calibration screen dropped from
 * the manifest — which is exactly what these release rows promise stays true.
 *
 * Pure tier: file scanning only. Run with
 * `scripts/gradle-guard.sh runPureTests -PtestClass=LauncherSetupFlowTest`.
 */
class LauncherSetupFlowTest {

    private fun read(path: String): String {
        val file = File(path)
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        return file.readText()
    }

    private val launcher by lazy {
        read("src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt")
    }

    // =========================================================================
    // v1.2.8 — the splash animation pauses for the keyboard
    // =========================================================================

    @Test
    fun splashAnimationPausesWhileTheImeIsUp() {
        // 1. Keyboard visibility comes from the IME inset, not a focus listener: the inset is
        //    non-zero for the whole time the keyboard occupies screen space.
        assertWithMessage("keyboard visibility must be derived from the IME window inset")
            .that(launcher).contains("WindowInsets.ime.getBottom(density)")
        assertWithMessage("any non-zero IME inset counts as 'keyboard visible'")
            .that(
                Regex("""val\s+isKeyboardVisible\s*=\s*imeBottom\s*>\s*0""").containsMatchIn(launcher)
            ).isTrue()

        // 2. That flag must reach the animation.
        assertWithMessage("the background animation must be told when the keyboard is up")
            .that(
                Regex("""SparkleMagicBackground\(\s*\n?\s*isPaused\s*=\s*isKeyboardVisible""")
                    .containsMatchIn(launcher)
            ).isTrue()
        assertWithMessage("a second, unpaused background would defeat the pause entirely")
            .that(Regex("""(?<!fun )SparkleMagicBackground\(""").findAll(launcher).count()).isEqualTo(1)

        // 3. And the frame loop must sit inside the not-paused branch of an effect keyed on
        //    isPaused — that is what actually stops the per-frame work.
        val effect = Regex("""LaunchedEffect\(isPaused\)\s*\{([\s\S]*?)\n    }""").find(launcher)
            ?.groupValues?.get(1)
            ?: throw AssertionError("SparkleMagicBackground no longer keys its effect on isPaused")
        val pausedBranch = effect.substringAfter("if (isPaused) {").substringBefore("} else {")
        assertWithMessage("the paused branch must not drive frames")
            .that(pausedBranch).doesNotContain("withFrameMillis")
        assertWithMessage("the frame loop must exist in the resumed branch")
            .that(effect.substringAfter("} else {")).contains("withFrameMillis")
        assertWithMessage("only the resumed branch may spawn animation work")
            .that(Regex("""withFrameMillis""").findAll(effect).count()).isEqualTo(1)
    }

    // =========================================================================
    // v1.2.9 — the third setup step
    // =========================================================================

    /**
     * The three `SetupCard(...)` call bodies, in composition order. Anchored on the
     * `number = "…"` first argument so the composable's own declaration (whose first
     * parameter is `number: String`) is not picked up as a fourth card.
     */
    private val setupCards: List<String> by lazy {
        Regex("""SetupCard\(\s*(\n\s+number = "[\s\S]*?)\n {16}\)""")
            .findAll(launcher).map { it.groupValues[1] }.toList()
    }

    @Test
    fun setupStepsAreNumberedInOrder() {
        assertWithMessage("the launcher must still guide the user through three setup steps")
            .that(setupCards).hasSize(3)
        setupCards.forEachIndexed { index, card ->
            assertWithMessage("setup card ${index + 1} must carry its own number")
                .that(card).contains("number = \"${index + 1}\"")
        }
        assertWithMessage("step 1 enables the keyboard in system settings")
            .that(setupCards[0]).contains("R.string.launcher_step_enable")
        assertWithMessage("step 2 selects it as the input method")
            .that(setupCards[1]).contains("R.string.launcher_step_select")
    }

    @Test
    fun thirdSetupStepOpensPerKeyCalibration() {
        val third = setupCards[2]
        assertWithMessage("the third step is the per-key calibration step")
            .that(third).contains("R.string.launcher_step_calibrate")
        assertWithMessage("the third step must describe what calibration does")
            .that(third).contains("description = \"Configure up to 8 subkey actions per key\"")
        assertWithMessage("tapping the third step must open calibration")
            .that(third).contains("onCalibrateGestures()")

        // Completion memory: the card ticks once visited, and that flag is persisted, so the
        // checklist does not reset every time the launcher is reopened.
        assertWithMessage("the third step's tick must come from the persisted visit flag")
            .that(third).contains("isCompleted = hasVisitedCalibration")
        assertWithMessage("the visit flag must be persisted, not just remembered")
            .that(launcher).contains("prefs.edit().putBoolean(\"has_visited_calibration\", true).apply()")
        assertWithMessage("the flag must be read back from the same launcher preference file")
            .that(launcher).contains("getSharedPreferences(\"cleverkeys_launcher\", Context.MODE_PRIVATE)")
        assertWithMessage("the flag must seed the initial checklist state")
            .that(launcher).contains("prefs.getBoolean(\"has_visited_calibration\", false)")

        // The callback must reach a real screen: LauncherActivity wires it to the calibration
        // Activity, and that Activity must be declared or the intent resolves to nothing.
        assertWithMessage("onCalibrateGestures must be wired to the launcher's own handler")
            .that(launcher).contains("onCalibrateGestures = { launchGestureCalibration() }")
        assertWithMessage("the handler must start the short-swipe calibration screen")
            .that(
                Regex("""fun launchGestureCalibration\(\)[\s\S]{0,200}?startActivity\(Intent\(this,\s*ShortSwipeCalibrationActivity::class\.java\)\)""")
                    .containsMatchIn(launcher)
            ).isTrue()

        val manifest = read("AndroidManifest.xml")
        assertWithMessage("an undeclared Activity makes the third step a dead end")
            .that(manifest).contains("android:name=\"tribixbite.cleverkeys.ShortSwipeCalibrationActivity\"")

        val strings = read("res/values/strings.xml")
        val title = Regex("""<string name="launcher_step_calibrate"[^>]*>([^<]*)</string>""")
            .find(strings)?.groupValues?.get(1)
        assertWithMessage("the third step needs a title string")
            .that(title).isNotNull()
        assertWithMessage("the third step's title must name calibration")
            .that(title!!.lowercase()).contains("calibrate")
    }
}
