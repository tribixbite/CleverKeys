package tribixbite.cleverkeys

import android.content.res.Configuration
import android.util.Log
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * A real rotation in an instrumented test: auto-rotate off, then `user_rotation` 1 (landscape),
 * the recipe that rotates the emulator.wtf Pixel 7 images (CLAUDE.md "Keyboard hidden on
 * rotation"). [restore] puts both settings back.
 *
 * Test-gap audit 2026-11-28, item 7: a rotation that did not happen used to be an assumption, so
 * a regression that stopped the rotation (or a device that ignores it) passed silently as a skip.
 * Now a device that ACCEPTED `user_rotation` 1 but did not turn FAILS the test. A skip is kept only
 * where the device genuinely cannot run it (it does not start in portrait, or it does not keep the
 * setting), and is logged at error level so it stands out in logcat and the run's output.
 *
 * @param shell runs a shell command as the instrumentation and returns its trimmed output
 * @param orientation the keyboard service's current configuration orientation
 * @param within polls a condition until it holds or the timeout passes
 */
internal class UserRotation(
    private val shell: (String) -> String,
    private val orientation: () -> Int,
    private val within: (Long, () -> Boolean) -> Boolean,
    private val tag: String,
) {
    private var originalAutoRotate = ""
    private var originalUserRotation = ""

    /** True once [toLandscape] changed the settings; [restore] then puts them back. */
    var changed = false
        private set

    /** Skips (loudly) unless the device is in portrait now: the rotation needs it to start from. */
    fun requirePortrait() {
        val portrait = orientation() == Configuration.ORIENTATION_PORTRAIT
        if (!portrait) Log.e(tag, "SKIPPED: the rotation case needs portrait to start from (orientation=${orientation()})")
        assumeTrue("rotation needs portrait to start from (orientation=${orientation()})", portrait)
    }

    /**
     * Rotates to landscape. Fails when the device kept `user_rotation` 1 with auto-rotate off but
     * the orientation did not change within [timeoutMs]; skips, logged at error level, when the
     * device did not keep the settings (an image that cannot rotate this way).
     */
    fun toLandscape(timeoutMs: Long = 10_000L, describe: () -> String = { "" }) {
        if (!changed) {
            originalAutoRotate = shell("settings get system accelerometer_rotation")
            originalUserRotation = shell("settings get system user_rotation")
        }
        changed = true
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 1")
        val turned = within(timeoutMs) { orientation() == Configuration.ORIENTATION_LANDSCAPE }
        if (turned) return
        val accepted = shell("settings get system user_rotation") == "1" &&
            shell("settings get system accelerometer_rotation") == "0"
        if (!accepted) {
            Log.e(tag, "SKIPPED: this device does not keep user_rotation=1 / accelerometer_rotation=0, " +
                "so it cannot rotate for this test (${describe()})")
            assumeTrue("this device does not keep user_rotation=1 (${describe()})", false)
        }
        assertTrue("user_rotation=1 was accepted but the orientation did not change (${describe()})", turned)
    }

    /** Puts the rotation settings back and waits for portrait again; a no-op if nothing changed. */
    fun restore() {
        if (!changed) return
        restoreSetting("user_rotation", originalUserRotation)
        restoreSetting("accelerometer_rotation", originalAutoRotate)
        within(10_000L) { orientation() == Configuration.ORIENTATION_PORTRAIT }
        changed = false
    }

    private fun restoreSetting(key: String, value: String) {
        if (value == "null" || value.isBlank()) shell("settings delete system $key")
        else shell("settings put system $key $value")
    }
}
