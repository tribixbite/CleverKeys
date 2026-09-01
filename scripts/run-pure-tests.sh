#!/data/data/com.termux/files/usr/bin/bash
# Run the pure-JVM test suite (no Robolectric, no emulator).
#
# Usage: ./scripts/run-pure-tests.sh [TestClassName]
# Example: ./scripts/run-pure-tests.sh AccentNormalizerTest
#          ./scripts/run-pure-tests.sh   # runs the whole pure suite
#
# This is a thin wrapper over the `runPureTests` Gradle task. That task owns the
# ONE list of pure test classes (`pureTestClasses` in build.gradle) — this script
# deliberately does NOT carry a second copy. It used to, and that copy silently
# rotted: by 2026-08 seven of its eleven entries named classes that no longer
# existed, so the script "passed" while running a third of the suite.
#
# The old proot-distro Ubuntu route is gone too. It existed because the
# `onnxruntime` JAR bundles a GLIBC-linked .so that cannot load in a bionic JVM;
# `runPureTests` now points `onnxruntime.native.path` at the bionic arm64-v8a
# natives from the onnxruntime-android AAR, so the tests run natively on device.
#
# All Gradle goes through scripts/gradle-guard.sh (device-wide build singleton).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -n "$1" ]; then
    exec "$SCRIPT_DIR/gradle-guard.sh" runPureTests -PtestClass="$1"
else
    exec "$SCRIPT_DIR/gradle-guard.sh" runPureTests
fi
