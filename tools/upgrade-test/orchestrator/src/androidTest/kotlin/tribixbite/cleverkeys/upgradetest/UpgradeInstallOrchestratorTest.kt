package tribixbite.cleverkeys.upgradetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.concurrent.thread

/**
 * Drives a REAL 1.x → 2.0 over-install on one emulator (emulator.wtf). An
 * instrumentation cannot survive reinstalling its own target, so this is a
 * separate self-instrumenting APK (`ew-cli --library-test`) that runs shell
 * commands through UiAutomation (shell uid): `pm install -r`, `am instrument`
 * of the seeder / verifier test APKs, `run-as` copies, `ime`, `dumpsys`.
 *
 * APKs arrive through `ew-cli --other-files /sdcard/upgrade/<name>.apk=…`:
 *   app150 / test150  v1.5.0 debug app + its androidTest APK with UpgradeSeedV150Test
 *   app200 / test200  2.0 debug app + its androidTest APK with UpgradeInstallVerifyTest
 *   rel150 / rel200   the GitHub v1.5.0 release APK and a 2.0 release APK (same key)
 * Report: /sdcard/upgrade-out/ (pulled with `--directories-to-pull`).
 * Recipe: `.claude/skills/ew-cli-testing.md` "Real 1.x → 2.0 upgrade install".
 */
@RunWith(AndroidJUnit4::class)
class UpgradeInstallOrchestratorTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.context

    private data class Step(val name: String, val ok: Boolean, val detail: String)

    /** Per-test step log; written to /sdcard/upgrade-out/<test>.txt at the end. */
    private inner class Report(private val test: String) {
        val steps = mutableListOf<Step>()
        val transcript = StringBuilder()

        fun step(name: String, ok: Boolean, detail: String) {
            steps += Step(name, ok, detail)
            Log.i(TAG, "test=$test step=$name result=${if (ok) "PASS" else "FAIL"} detail=${detail.take(500)}")
        }

        fun info(name: String, detail: String) {
            transcript.append("INFO $name: $detail\n")
            Log.i(TAG, "test=$test info=$name detail=${detail.take(500)}")
        }

        fun finish() {
            val text = buildString {
                append("# $test\n")
                for (s in steps) append("${if (s.ok) "PASS" else "FAIL"} ${s.name}: ${s.detail.take(2000)}\n")
                append("\n").append(transcript)
            }
            val local = File(context.getExternalFilesDir(null), "$test.txt")
            local.writeText(text)
            sh("mkdir -p $OUT && cp ${local.absolutePath} $OUT/$test.txt")
            val failed = steps.filter { !it.ok }
            if (failed.isNotEmpty()) {
                fail("${failed.size} step(s) failed: " + failed.joinToString("; ") { "${it.name}: ${it.detail.take(300)}" })
            }
        }
    }

    /**
     * Runs [script] with `sh` as the shell uid, feeding it on stdin so pipes,
     * redirects and quoting work (executeShellCommand alone does not parse them).
     * Returns stdout + stderr.
     */
    private fun sh(script: String): String {
        val fds = instrumentation.uiAutomation.executeShellCommandRwe("sh")
        val (stdout, stdin, stderr) = Triple(fds[0], fds[1], fds[2])
        var err = ""
        val errReader = thread { err = ParcelFileDescriptor.AutoCloseInputStream(stderr).bufferedReader().readText() }
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write((script + "\nexit\n").toByteArray()) }
        val out = ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().readText()
        errReader.join()
        return (out + err).trimEnd()
    }

    /** Polls [probe] until [accept] or [timeoutMs]; returns the last output. */
    private fun waitFor(timeoutMs: Long, probe: () -> String, accept: (String) -> Boolean): Pair<Boolean, String> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = probe()
        while (!accept(last) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1_000)
            last = probe()
        }
        return accept(last) to last
    }

    private fun packageLine(pkg: String): String =
        sh("dumpsys package $pkg | grep -E 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime=' | sort -u | tr -s ' '")

    private fun field(dump: String, key: String): String? =
        Regex("$key=(\\S+(?: \\S+)?)").find(dump)?.groupValues?.get(1)

    private fun install(r: Report, name: String, apk: String, replace: Boolean) {
        val out = sh("pm install ${if (replace) "-r " else ""}-t $TMP/$apk")
        r.step(name, out.contains("Success"), out)
    }

    /** `am instrument` one test of a seeder/verifier APK; JUnit's "OK (1 test)" is the verdict. */
    private fun instrument(r: Report, name: String, testPkg: String, target: String) {
        val out = sh("am instrument -w -r -e class $target $testPkg/androidx.test.runner.AndroidJUnitRunner")
        val ok = out.contains("OK (1 test)")
        val failure = out.lineSequence().filter {
            it.contains("Error in") || it.contains("AssertionError") || it.contains("upgrade check") ||
                it.contains("INSTRUMENTATION_FAILED") || it.contains("Exception")
        }.take(40).joinToString("\n")
        r.step(name, ok, if (ok) "OK (1 test)" else failure.ifEmpty { out.takeLast(3000) })
    }

    private fun imeCurrent(): String =
        sh("settings get secure default_input_method; dumpsys input_method | grep -E 'mCurMethodId=|mCurId=' | head -3")

    /**
     * Android stores an IME id as ComponentName.flattenToShortString(): the release
     * package gets `tribixbite.cleverkeys/.CleverKeysService`, the `.debug` package the
     * full class name. Accept both spellings.
     */
    private fun imeId(pkg: String): String =
        if (SERVICE.startsWith("$pkg.")) "$pkg/" + SERVICE.removePrefix(pkg) else "$pkg/$SERVICE"

    private fun isImeSelected(out: String, pkg: String): Boolean = out.contains(imeId(pkg))

    /**
     * `ime enable` + `ime set` with the short-form id, retried: right after
     * `pm install` the IME list may not know the new package yet ("unrecognized IME ID").
     */
    private fun selectIme(r: Report, name: String, pkg: String) {
        val id = imeId(pkg)
        val (ok, out) = waitFor(20_000, { sh("ime enable $id; ime set $id; settings get secure default_input_method") }) {
            it.lineSequence().lastOrNull()?.trim() == id
        }
        r.step(name, ok, out)
    }

    /**
     * IME selected AND its service bound into a live app process. The system binds
     * the IME only when an editor gains focus, so every poll re-focuses a text field
     * ([ImeProbeActivity]: Home, then bring it back). One focus right after
     * `pm install -r` is not enough: a start-input that races the package update is
     * dropped and nothing retries it until the next focus change (run 3, 2026-10-25).
     */
    private fun checkImeBound(r: Report, name: String, pkg: String) {
        val probe = "${context.packageName}/${ImeProbeActivity::class.java.name}"
        var attempts = 0
        val (ok, out) = waitFor(45_000, {
            attempts++
            sh("input keyevent KEYCODE_HOME; sleep 1; am start -W -n $probe > /dev/null; sleep 3")
            imeCurrent() + "\n" + sh(
                "echo pid=$(pidof $pkg); dumpsys input_method | grep -E 'mBoundToMethod|mInputShown' | head -3; " +
                    "dumpsys activity services $pkg | grep -E 'ServiceRecord|app=ProcessRecord' | head -6"
            )
        }) {
            // Selection is read from secure settings: API 37's dumpsys input_method no
            // longer prints mCurId/mCurMethodId lines (run 5, 2026-10-25).
            isImeSelected(it.lineSequence().first(), pkg) && it.contains("mBoundToMethod=true") &&
                it.contains("app=ProcessRecord") && it.contains("CleverKeysService")
        }
        r.step(name, ok, "focus attempts=$attempts\n$out")
        sh("input keyevent KEYCODE_HOME")
    }

    private fun launch(r: Report, name: String, pkg: String) {
        val out = sh("am start -W -n $pkg/$LAUNCHER")
        r.step(name, out.contains("Status: ok") && !out.contains("Error"), out)
        SystemClock.sleep(2_000)
        sh("input keyevent KEYCODE_HOME")
    }

    /** Crash buffer + AndroidRuntime FATAL lines naming [pkg] as the dying process. */
    private fun checkNoCrash(r: Report, name: String, pkg: String) {
        val crash = sh("logcat -d -b crash")
        val fatal = sh("logcat -d -b main -s AndroidRuntime:E | grep -A3 'FATAL EXCEPTION' | head -60")
        val proc = Regex("Process: ${Regex.escape(pkg)}(,| |$)")
        val hits = (crash + "\n" + fatal).lineSequence().filter { proc.containsMatchIn(it) }.toList()
        r.step(name, hits.isEmpty(), if (hits.isEmpty()) "no crash for $pkg" else (crash.take(3000) + "\n" + fatal.take(2000)))
        r.info("$name.exit_info", sh("dumpsys activity exit-info $pkg | grep -E 'reason=|timestamp=' | head -30"))
    }

    /**
     * Both tests share one emulator, so each starts from no CleverKeys at all. Required,
     * not tidy-up: since 9c799506 (2026-09-07) the manifest declares the fixed-name
     * signature permission `tribixbite.cleverkeys.permission.SET_DEBUG_MODE`, so the
     * debug-key `.debug` 2.0 build is refused (INSTALL_FAILED_DUPLICATE_PERMISSION)
     * while the release-key package is installed, and vice versa.
     */
    private fun cleanSlate() {
        sh("pm uninstall $DEBUG_PKG.test; pm uninstall $DEBUG_PKG; pm uninstall $RELEASE_PKG")
        clearClipboard()
    }

    /**
     * An IME that starts records the CURRENT OS clip (1.x and 2.0 alike; a duplicate is
     * re-stamped to now, #108). A clip left by the other test would then show up as an
     * extra history row and a changed timestamp, so the OS clipboard starts empty.
     */
    private fun clearClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        instrumentation.runOnMainSync { cm.clearPrimaryClip() }
    }

    /**
     * run-as output cannot be redirected straight onto /sdcard (the run-as domain may
     * not write a FUSE file descriptor, which leaves an empty file), so go through
     * /data/local/tmp.
     */
    private fun copyOut(pkg: String, name: String) {
        sh("run-as $pkg cat files/upgrade-test/$name > $TMP/$name; cp $TMP/$name $OUT/$name")
    }

    private fun stage(r: Report, vararg apks: String) {
        val out = sh("mkdir -p $TMP $OUT; for f in ${apks.joinToString(" ")}; do cp $SRC/\$f.apk $TMP/\$f.apk || echo MISSING \$f; done; ls -la $TMP")
        r.step("stage_apks", !out.contains("MISSING"), out)
    }

    /**
     * v1.5.0 debug (seeded by its own code) → `pm install -r` 2.0 debug → verify →
     * full backup → uninstall → fresh 2.0 → import → verify again. Both debug APKs
     * are signed with the committed debug.keystore (same key, package `.debug`).
     */
    @Test
    fun debugBuildUpgradeAndBackupRoundTrip() {
        val r = Report("debugBuildUpgradeAndBackupRoundTrip")
        val pkg = DEBUG_PKG
        val testPkg = "$pkg.test"
        try {
            stage(r, "app150", "test150", "app200", "test200")
            cleanSlate()

            install(r, "install_1x_app", "app150.apk", replace = false)
            install(r, "install_1x_test", "test150.apk", replace = false)
            val before = packageLine(pkg)
            r.step("installed_version_is_1x", field(before, "versionName")?.startsWith("1.") == true, before)
            selectIme(r, "select_ime_1x", pkg)
            checkImeBound(r, "ime_binds_1x", pkg)
            instrument(r, "seed_1x_through_its_own_code", testPkg, "tribixbite.cleverkeys.UpgradeSeedV150Test")
            r.info("seed_log", sh("logcat -d -s UPGRADE_CHECK:I | tail -5"))

            // The real thing: replace the 1.x install, keeping its data.
            install(r, "over_install_2x_app", "app200.apk", replace = true)
            install(r, "over_install_2x_test", "test200.apk", replace = true)
            val after = packageLine(pkg)
            r.step(
                "version_now_2x_same_install",
                field(after, "versionName")?.startsWith("2.") == true &&
                    field(after, "firstInstallTime") == field(before, "firstInstallTime") &&
                    field(after, "lastUpdateTime") != field(before, "lastUpdateTime"),
                "before: $before\nafter: $after"
            )
            val kept = sh("run-as $pkg ls -la files/upgrade-test databases")
            r.step("app_data_kept_across_update", kept.contains("seed_dump.json") && kept.contains("clipboard_history.db"), kept)
            imeCurrent().let { r.step("ime_still_selected_after_update", isImeSelected(it, pkg), it) }
            checkImeBound(r, "ime_binds_2x_after_update", pkg)
            launch(r, "app_starts_2x_after_update", pkg)
            checkNoCrash(r, "no_crash_after_update", pkg)

            instrument(r, "verify_after_over_install", testPkg, "tribixbite.cleverkeys.UpgradeInstallVerifyTest#afterOverInstall")
            copyOut(pkg, "results_afterOverInstall.json")

            // Round trip: carry the backup + seed dump out, reinstall fresh, carry them back in.
            val files = listOf("backup.zip", "seed_dump.json", "upgraded_schema.json")
            val out = sh(files.joinToString("\n") { "run-as $pkg cat files/upgrade-test/$it > $TMP/$it" } + "\nls -la $TMP")
            r.step("backup_copied_out", files.all { f -> Regex("\\s[1-9]\\d* .*$f").containsMatchIn(out) }, out)
            sh("cp $TMP/backup.zip $OUT/backup.zip")
            val un = sh("pm uninstall $pkg")
            r.step("uninstall_app", un.contains("Success"), un)
            val gone = sh("pm path $pkg")
            r.step("app_gone_after_uninstall", gone.isBlank(), gone.ifBlank { "pm path: nothing" })
            install(r, "fresh_install_2x_app", "app200.apk", replace = false)
            val back = sh(
                "run-as $pkg mkdir -p files/upgrade-test\n" +
                    files.joinToString("\n") { "cat $TMP/$it | run-as $pkg sh -c 'cat > files/upgrade-test/$it'" } +
                    "\nrun-as $pkg ls -la files/upgrade-test"
            )
            r.step("backup_copied_in", files.all { back.contains(it) }, back)
            selectIme(r, "select_ime_fresh_2x", pkg)
            checkImeBound(r, "ime_binds_fresh_2x", pkg)
            instrument(r, "verify_after_fresh_install_import", testPkg, "tribixbite.cleverkeys.UpgradeInstallVerifyTest#afterFreshInstallImport")
            copyOut(pkg, "results_afterFreshInstallImport.json")
            launch(r, "app_starts_after_import", pkg)
            checkNoCrash(r, "no_crash_whole_run", pkg)
            r.info("upgrade_check_log", sh("logcat -d -s UPGRADE_CHECK:I | grep -E 'result=(FAIL|INFO)|SUMMARY' | head -80"))
        } catch (t: Throwable) {
            r.step("orchestrator_exception", false, Log.getStackTraceString(t))
        } finally {
            r.finish()
        }
    }

    /**
     * The real signed pair: the GitHub v1.5.0 release APK, then a 2.0 release APK
     * signed with the same release key, installed over it. Release builds are not
     * debuggable and are minified, so nothing can be seeded or read back by
     * instrumentation; this checks that the update is ACCEPTED (same signer,
     * higher versionCode), keeps the install, and that the IME binds and the app
     * starts without a crash. A few clips are copied while the 1.x IME is current
     * so the update migrates a non-empty history (unverified: not readable).
     */
    @Test
    fun releaseSignedOverInstallSmoke() {
        val r = Report("releaseSignedOverInstallSmoke")
        val pkg = RELEASE_PKG
        try {
            stage(r, "rel150", "rel200")
            cleanSlate()
            install(r, "install_github_v150_release", "rel150.apk", replace = false)
            val before = packageLine(pkg)
            r.step("installed_version_is_1_5_0", field(before, "versionName") == "1.5.0", before)
            r.info("signatures_1x", sh("dumpsys package $pkg | grep -iE 'signatures|signingDetails' | head -4"))
            selectIme(r, "select_ime_1x_release", pkg)
            checkImeBound(r, "ime_binds_1x_release", pkg)
            launch(r, "app_starts_1x_release", pkg)
            val cm = context.getSystemService(ClipboardManager::class.java)
            for (i in 1..3) {
                instrumentation.runOnMainSync { cm.setPrimaryClip(ClipData.newPlainText("upgrade", "upgrade-release-clip-$i")) }
                SystemClock.sleep(1_500)
            }
            install(r, "over_install_2x_release", "rel200.apk", replace = true)
            val after = packageLine(pkg)
            r.step(
                "version_now_2x_same_install",
                field(after, "versionName")?.startsWith("2.") == true &&
                    field(after, "firstInstallTime") == field(before, "firstInstallTime"),
                "before: $before\nafter: $after"
            )
            r.info("signatures_2x", sh("dumpsys package $pkg | grep -iE 'signatures|signingDetails' | head -4"))
            imeCurrent().let { r.step("ime_still_selected_after_update", isImeSelected(it, pkg), it) }
            checkImeBound(r, "ime_binds_2x_release", pkg)
            launch(r, "app_starts_2x_release", pkg)
            SystemClock.sleep(3_000)
            checkNoCrash(r, "no_crash_release", pkg)
            clearClipboard()
        } catch (t: Throwable) {
            r.step("orchestrator_exception", false, Log.getStackTraceString(t))
        } finally {
            r.finish()
        }
    }

    companion object {
        private const val TAG = "UPGRADE_ORCH"
        private const val SRC = "/sdcard/upgrade"
        private const val TMP = "/data/local/tmp/upg"
        private const val OUT = "/sdcard/upgrade-out"
        private const val DEBUG_PKG = "tribixbite.cleverkeys.debug"
        private const val RELEASE_PKG = "tribixbite.cleverkeys"
        private const val SERVICE = "tribixbite.cleverkeys.CleverKeysService"
        private const val LAUNCHER = "tribixbite.cleverkeys.LauncherActivity"
    }
}
