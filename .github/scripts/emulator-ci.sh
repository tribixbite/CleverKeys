#!/usr/bin/env bash
#
# Body for every `reactivecircus/android-emulator-runner` `script:` step in
# .github/workflows/ui-testing.yml.
#
# ## Why this is a file and not inline YAML
#
# The runner splits the `script:` input into LINES and executes each one as a separate
# `sh -c` child. On ubuntu-latest `sh` is dash, which has no `pipefail`, so an inline script
# beginning `set -euo pipefail` dies immediately:
#
#     [command]/usr/bin/sh -c set -euo pipefail
#     /usr/bin/sh: 1: set: Illegal option -o pipefail
#     ##[error]The process '/usr/bin/sh' failed with exit code 2
#
# ...four seconds after the emulator finished booting. That is what kept every emulator job
# in this workflow red from 2026-07-19 to 2026-08-20 — ~32 consecutive failures, every one
# reported as `adb: device offline`, which was a RED HERRING: those lines come from the
# action's own `getprop sys.boot_completed` poll loop *before* boot completes and appear on
# every healthy cold boot too.
#
# Removing the `pipefail` line alone would NOT have fixed it. Under per-line `sh -c`, `PKG=…`
# does not survive to the next line, an `if …; then` on its own line is a syntax error,
# `set +e`/`set -e` are per-line no-ops, and backslash continuations are split apart. The
# whole multi-line style is incompatible with that runner.
#
# **Keep every `script:` in the workflow a SINGLE line invoking this file.**
#
# Env: APK_FILE and TEST_APK_FILE come from GITHUB_ENV; the action spreads process.env into
# each child, along with ANDROID_SERIAL, so bare `adb` targets the right device.
set -euo pipefail

MODE="${1:?usage: emulator-ci.sh gate|capture|a11y}"

# The debug build applies applicationIdSuffix '.debug'; component classes keep their
# original (non-suffixed) fully-qualified names.
PKG=tribixbite.cleverkeys.debug
IME_COMPONENT="$PKG/tribixbite.cleverkeys.CleverKeysService"

echo "Installing APK: ${APK_FILE:?APK_FILE not set}"
adb install "$APK_FILE"

# PackageManager indexes a newly installed IME asynchronously, so `ime enable` immediately
# after `adb install` races it and fails with:
#     Unknown input method <component> cannot be enabled for user #0
# ...which is what this job did on its first real run. Wait for the component to appear in
# the IME list rather than sleeping a guessed interval.
echo "Waiting for the IME to be indexed..."
for _ in $(seq 1 30); do
  if adb shell ime list -a -s 2>/dev/null | grep -q "^$IME_COMPONENT$"; then
    echo "IME indexed."
    break
  fi
  sleep 1
done

echo "Available input methods:"
adb shell ime list -a -s

adb shell ime enable "$IME_COMPONENT"
adb shell ime set "$IME_COMPONENT"

# Hard-assert the IME registered — a silent no-op used to pass here.
adb shell dumpsys input_method | grep -q "$PKG" \
  || { echo "::error::CleverKeys IME not registered"; exit 1; }

# `adb shell` on API 21 predates exit-code propagation and has no `pidof`, so this check is
# vacuous there and real on 29/34. The instrumented verdict below is parsed host-side from
# the output stream, so it is accurate on every API level.
assert_alive() {
  adb shell pidof "$PKG" || { echo "::error::App process died"; exit 1; }
}

run_instrumentation() {
  local classes="$1"
  echo "Installing androidTest APK: ${TEST_APK_FILE:?TEST_APK_FILE not set}"
  adb install -r "$TEST_APK_FILE"
  local runner
  runner=$(adb shell pm list instrumentation | tr -d '\r' \
    | sed -n "s#^instrumentation:\([^ ]*\) (target=$PKG)\$#\1#p" | head -1)
  [ -n "$runner" ] || { echo "::error::no instrumentation registered for $PKG"; exit 1; }
  set +e
  adb shell am instrument -w -e class "$classes" -e disableAnalytics true "$runner" 2>&1 \
    | tee instrumentation.log
  set -e
  if grep -qE "FAILURES!!!|Process crashed|INSTRUMENTATION_ABORTED" instrumentation.log; then
    echo "::error::Instrumented tests failed"
    exit 1
  fi
  # `[1-9][0-9]*`, not `[0-9]+`: `OK (0 tests)` is what the runner prints when the `-e class`
  # filter matches NOTHING (renamed/moved/emptied class), and the old regex accepted it — a
  # false-green release gate (CK-150-028). A real run always reports at least one test.
  grep -qE "^OK \([1-9][0-9]* tests?\)" instrumentation.log \
    || { echo "::error::No OK line with a non-zero test count — the runner ran nothing or did not complete"; exit 1; }
}

case "$MODE" in
  a11y)
    adb shell settings put secure accessibility_enabled 1
    run_instrumentation "tribixbite.cleverkeys.a11y.KeyboardAccessibilityInstrumentedTest"
    echo "Accessibility node, action, hover-off, and touch-path assertions passed"
    ;;

  capture)
    mkdir -p screenshots
    adb shell am start -W -n "$PKG/tribixbite.cleverkeys.SettingsActivity"
    sleep 2
    assert_alive
    adb exec-out screencap -p > screenshots/settings-screen.png || true
    echo "Screenshot capture completed (artifact only; no baseline comparison claimed)"
    ;;

  gate)
    adb shell am start -W -n "$PKG/tribixbite.cleverkeys.SettingsActivity"
    sleep 2
    assert_alive
    # Best-effort screenshot — not a gate.
    adb exec-out screencap -p > settings-screenshot.png || true
    echo "Smoke test completed"

    # ── The instrumented gate ────────────────────────────────────────────────────────
    # A CURATED set, not the whole suite: the full 1,395-test run takes ~30 min on
    # emulator.wtf and would be slower and flakier here, and a gate that times out teaches
    # people to ignore it. These six pin invariants no pure test can reach — real assets
    # parsed on-device, a real ONNX session, real keyboard geometry. Full-suite runs stay a
    # deliberate ew-cli action.
    #
    # This list is PINNED by the pure test `CuratedInstrumentationListTest`: it parses the
    # assignment below and fails if the set drifts from its checked-in expectation or names a
    # class that no longer exists with a @Test. Changing the gate is therefore a deliberate
    # two-file edit (CK-150-028). Keep the value a single-line double-quoted literal.
    CLASSES="tribixbite.cleverkeys.swipe.CtcMultiLanguageInstrumentedTest,tribixbite.cleverkeys.GeometricSwipeOracleTest,tribixbite.cleverkeys.CrashGuardInstrumentedTest,tribixbite.cleverkeys.a11y.KeyboardAccessibilityInstrumentedTest,tribixbite.cleverkeys.backup.crypto.BackupPassphraseStoreInstrumentedTest,tribixbite.cleverkeys.swipe.CtcEmissionModelParityTest"
    run_instrumentation "$CLASSES"
    ;;

  *)
    echo "::error::unknown mode '$MODE' (expected gate|capture|a11y)"
    exit 1
    ;;
esac
