#!/data/data/com.termux/files/usr/bin/bash
#
# run-upgrade-test.sh — REAL 1.x → 2.0 over-install test on emulator.wtf.
#
# Builds (each through scripts/gradle-guard.sh, in SEPARATE git worktrees so the
# shared main checkout and its build config are never touched):
#   1. the v1.5.0 tag: debug app + androidTest APK carrying UpgradeSeedV150Test
#   2. the 2.0 commit under test: debug app + androidTest APK carrying
#      UpgradeInstallVerifyTest
#   3. the standalone orchestrator (tools/upgrade-test/orchestrator, library test APK)
# then runs the orchestrator with `ew-cli --library-test`, pushing all APKs with
# --other-files and pulling /sdcard/upgrade-out.
#
# Signing: both debug apps are signed by the committed debug.keystore (unchanged
# since the first commit, so v1.5.0 and 2.0 share the key); the release pair is
# the GitHub v1.5.0 release APK + a local 2.0 release APK, accepted only if
# apksigner reports the same certificate digest for both.
#
# Usage: tools/upgrade-test/run-upgrade-test.sh [build|run|all]   (default: all)
# Env:   BASE_TAG=v1.5.0          1.x tag to upgrade from
#        HEAD_REF=HEAD            2.0 commit under test
#        REL200_APK=<path>        2.0 release x86_64 APK (default: main build output)
#        EW_DEVICES="model=Pixel7,version=34 model=Pixel7,version=36"
#        OUT_DIR=$HOME/ew-output/upgrade-<date>
# Recipe and results: .claude/skills/ew-cli-testing.md "Real 1.x → 2.0 upgrade install".
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
BASE_TAG="${BASE_TAG:-v1.5.0}"
HEAD_REF="${HEAD_REF:-HEAD}"
WT_OLD="${WT_OLD:-$(dirname "$REPO")/cleverkeys-upg150}"
WT_NEW="${WT_NEW:-$(dirname "$REPO")/cleverkeys-upg200}"
STAGE="${STAGE:-$HOME/ew-output/upgrade-stage}"
OUT_DIR="${OUT_DIR:-$HOME/ew-output/upgrade-$(date +%Y%m%d-%H%M)}"
EW_DEVICES="${EW_DEVICES:-model=Pixel7,version=34 model=Pixel7,version=36}"
REL200_APK="${REL200_APK:-$REPO/build/outputs/apk/release/CleverKeys-v2.0.0-x86_64.apk}"
MODE="${1:-all}"

guard() {
    # $1 = worktree; rest = gradle args. The worktree's own copy of gradle-guard
    # (copied from main if the tag predates it) shares the device-wide lock.
    local wt="$1"; shift
    [ -f "$wt/scripts/gradle-guard.sh" ] || cp "$REPO/scripts/gradle-guard.sh" "$wt/scripts/gradle-guard.sh"
    [ -f "$wt/local.properties" ] || cp "$REPO/local.properties" "$wt/local.properties"
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xmx1200m}" GRADLE_GUARD_XMX="${GRADLE_GUARD_XMX:-1536m}" \
        GRADLE_GUARD_METASPACE="${GRADLE_GUARD_METASPACE:-384m}" GRADLE_GUARD_LOCK_WAIT="${GRADLE_GUARD_LOCK_WAIT:-7200}" \
        "$wt/scripts/gradle-guard.sh" "$@"
}

worktree() { # $1 = dir, $2 = ref
    if [ ! -d "$1" ]; then git -C "$REPO" worktree add --detach "$1" "$2"; fi
    local want have
    want="$(git -C "$REPO" rev-parse "$2^{commit}")"
    have="$(git -C "$1" rev-parse HEAD)"
    [ "$want" = "$have" ] || { echo "worktree $1 is at $have, expected $2 ($want)" >&2; exit 1; }
}

cert() { apksigner verify --print-certs "$1" | awk -F': ' '/Signer #1 certificate SHA-256/ {print $2}'; }

build() {
    mkdir -p "$STAGE"
    local t="src/androidTest/kotlin/tribixbite/cleverkeys"
    worktree "$WT_OLD" "$BASE_TAG"
    cp "$HERE"/shared/*.kt "$HERE"/seeder-v1.5.0/*.kt "$WT_OLD/$t/"
    guard "$WT_OLD" assembleDebug assembleDebugAndroidTest
    cp "$WT_OLD"/build/outputs/apk/debug/CleverKeys-v*-x86_64.apk "$STAGE/app150.apk"
    cp "$WT_OLD"/build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk "$STAGE/test150.apk"

    worktree "$WT_NEW" "$HEAD_REF"
    cp "$HERE"/shared/*.kt "$HERE"/verifier/*.kt "$WT_NEW/$t/"
    guard "$WT_NEW" assembleDebug assembleDebugAndroidTest
    cp "$WT_NEW"/build/outputs/apk/debug/CleverKeys-v*-x86_64.apk "$STAGE/app200.apk"
    cp "$WT_NEW"/build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk "$STAGE/test200.apk"

    guard "$REPO" -p "$HERE/orchestrator" assembleDebugAndroidTest
    cp "$HERE"/orchestrator/build/outputs/apk/androidTest/debug/*.apk "$STAGE/orchestrator.apk"

    gh release download "$BASE_TAG" -R tribixbite/CleverKeys -p "CleverKeys-${BASE_TAG}-x86_64.apk" -O "$STAGE/rel150.apk" --clobber
    cp "$REL200_APK" "$STAGE/rel200.apk"
}

check_signers() {
    local d150 d200 r150 r200
    d150="$(cert "$STAGE/app150.apk")"; d200="$(cert "$STAGE/app200.apk")"
    r150="$(cert "$STAGE/rel150.apk")"; r200="$(cert "$STAGE/rel200.apk")"
    echo "debug   1.x $d150"; echo "debug   2.0 $d200"
    echo "release 1.x $r150"; echo "release 2.0 $r200"
    [ "$d150" = "$d200" ] || { echo "debug signers differ — over-install would be refused" >&2; exit 1; }
    [ "$r150" = "$r200" ] || { echo "release signers differ — over-install would be refused" >&2; exit 1; }
    sha256sum "$STAGE"/*.apk
}

run() {
    check_signers
    test -n "${EW_API_TOKEN:-}" || { echo "EW_API_TOKEN not set" >&2; exit 1; }
    mkdir -p "$OUT_DIR"
    local files="" n devs=()
    for n in app150 test150 app200 test200 rel150 rel200; do
        files="${files:+$files,}/sdcard/upgrade/$n.apk=$STAGE/$n.apk"
    done
    for d in $EW_DEVICES; do devs+=(--device "$d"); done
    EW_VERSION=1.3.4 ew-cli \
        --library-test "$STAGE/orchestrator.apk" \
        --other-files "$files" \
        "${devs[@]}" \
        --timeout 40m \
        --side-effects \
        --no-test-cache \
        --directories-to-pull /sdcard/upgrade-out \
        --outputs merged_results_xml,logcat,pulled_dirs \
        --outputs-dir "$OUT_DIR" \
        --display-name "upgrade ${BASE_TAG} -> $(git -C "$REPO" rev-parse --short "$HEAD_REF")"
    echo "outputs: $OUT_DIR"
}

case "$MODE" in
    build) build ;;
    run) run ;;
    all) build; run ;;
    *) echo "usage: $0 [build|run|all]" >&2; exit 2 ;;
esac
