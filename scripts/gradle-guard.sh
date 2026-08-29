#!/data/data/com.termux/files/usr/bin/bash
#
# gradle-guard.sh — the ONE Gradle entry point for this device.
#
# EVERY Gradle invocation (build scripts, test scripts, retry loops, background
# monitors, ad-hoc `sh gradlew ...` calls) MUST go through this wrapper.
# Written after the 2026-08-29 phone-wide incident: ~21 concurrent background
# monitors each launched `sh gradlew`, stacking 8+ Gradle/Kotlin daemon JVMs
# (one at 2.1GB RSS), pushing 12GB into swap and load average to 40.
#
# Invariants enforced (do not weaken):
#   1. Device-wide singleton — flock on $HOME/.cache/cleverkeys-build.lock.
#      A queued build WAITS on the lock (up to GRADLE_GUARD_LOCK_WAIT s);
#      it never runs concurrently with another build, not even a "just check".
#   2. No daemon accumulation — --no-daemon, Kotlin compiled in-process, and
#      an EXIT trap that kills leaked build JVMs on success AND failure.
#   3. Bounded memory — -Xmx1024m / -XX:MaxMetaspaceSize=256m / SerialGC,
#      single worker, and a hard abort (exit 76) when MemAvailable is below
#      the 1.5GB floor. A retry under memory pressure only deepens the thrash,
#      so the memory abort is never retried.
#   4. Bounded retries — GRADLE_GUARD_RETRIES (hard-capped at 3) retries ONLY
#      environmental failures (OOM, daemon death, network timeouts) with
#      exponential backoff 60s -> 300s -> 900s. Compile/test errors never
#      retry. Run ONE monitor per build, never one per question about it, and
#      never leave a monitor loop running after its build finishes.
#
# Usage:  scripts/gradle-guard.sh <gradle tasks/flags...>
# Env:    GRADLE_GUARD_XMX=1024m          build-JVM heap cap
#         GRADLE_GUARD_METASPACE=256m     metaspace cap
#         GRADLE_GUARD_RETRIES=0          environmental retries (max 3)
#         GRADLE_GUARD_MIN_MEM_KB=1500000 MemAvailable floor
#         GRADLE_GUARD_LOCK_WAIT=7200     seconds to wait for the lock
# Exit:   75 = lock timeout, 76 = low memory, otherwise gradle's exit code.
# Log:    every attempt's full output also lands in
#         $HOME/.cache/cleverkeys-build-last.log

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

XMX="${GRADLE_GUARD_XMX:-1024m}"
METASPACE="${GRADLE_GUARD_METASPACE:-256m}"
RETRIES="${GRADLE_GUARD_RETRIES:-0}"
[ "$RETRIES" -gt 3 ] && RETRIES=3
MIN_MEM_KB="${GRADLE_GUARD_MIN_MEM_KB:-1500000}"
LOCK_WAIT="${GRADLE_GUARD_LOCK_WAIT:-7200}"
LOCK="$HOME/.cache/cleverkeys-build.lock"
LAST_LOG="$HOME/.cache/cleverkeys-build-last.log"

mem_available_kb() { awk '/^MemAvailable:/ {print $2}' /proc/meminfo; }

# Kill leaked build JVMs. Only ever called while we hold the exclusive lock,
# so anything matching is by definition a leak from a previous build.
# The pattern lives in this FILE, not a command line, so pkill cannot
# self-match. (Running this same pkill via `bash -c '...'` WOULD self-match
# and kill the invoking shell — do not inline it elsewhere.)
sweep_leaked_jvms() {
    pkill -9 -f 'GradleDaemon|KotlinCompileDaemon|K2JVMCompiler|GradleWrapperMain' 2>/dev/null
    return 0
}

# --- Invariant 1: device-wide singleton ----------------------------------------
# Poll with flock -n instead of flock -w: on Termux util-linux 2.42.1 the fd
# form's -w timeout is silently IGNORED (blocks until the lock frees), while
# -n works correctly. Verified 2026-08-29.
mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
lock_deadline=$(( $(date +%s) + LOCK_WAIT ))
until flock -n 9; do
    if [ "$(date +%s)" -ge "$lock_deadline" ]; then
        echo "gradle-guard: another build has held $LOCK for ${LOCK_WAIT}s — refusing to start a parallel build (exit 75)." >&2
        exit 75
    fi
    sleep 5
done

# --- Invariant 2: guaranteed JVM cleanup, success or failure -------------------
trap 'sweep_leaked_jvms' EXIT
# We hold the exclusive lock, so any build JVM alive right now is a leak
# from an earlier crashed/killed run. Clear it before measuring memory.
sweep_leaked_jvms

cd "$REPO_DIR" || exit 1

attempt=0
backoffs=(60 300 900)
while :; do
    # --- Invariant 3: bounded memory, abort (not retry) under pressure --------
    avail="$(mem_available_kb)"
    if [ "${avail:-0}" -lt "$MIN_MEM_KB" ]; then
        echo "gradle-guard: MemAvailable ${avail}kB < ${MIN_MEM_KB}kB floor — aborting WITHOUT retry (exit 76). Free memory first; retrying under pressure deepens the thrash." >&2
        exit 76
    fi

    # `sh gradlew` (not ./gradlew): Claude Code strips LD_PRELOAD, breaking the
    # /bin/sh shebang rewrite — see CLAUDE.md termux notes.
    # -Dorg.gradle.jvmargs on the CLI REPLACES gradle.properties' value, so the
    # reproducibility flags (encoding/timezone/locale) are re-included here.
    sh "$REPO_DIR/gradlew" "$@" \
        --no-daemon \
        --max-workers=1 \
        -Dkotlin.compiler.execution.strategy=in-process \
        -Dorg.gradle.jvmargs="-Xmx$XMX -XX:MaxMetaspaceSize=$METASPACE -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Duser.language=en -Duser.country=US" \
        2>&1 | tee "$LAST_LOG"
    rc=${PIPESTATUS[0]}
    [ "$rc" -eq 0 ] && exit 0

    # --- Invariant 4: bounded retries, environmental failures only ------------
    if [ "$attempt" -ge "$RETRIES" ]; then
        exit "$rc"
    fi
    # awk, not grep: `grep` is a login-profile function that injects -G on this
    # device (see ~/.claude/CLAUDE.md).
    if ! awk '/OutOfMemoryError|GC overhead limit|unable to create native thread|daemon disappeared|Timeout waiting to lock|Read timed out|Connection reset|Connection refused|Could not HEAD|Could not GET/ {found=1} END {exit !found}' "$LAST_LOG"; then
        echo "gradle-guard: failure is not environmental (compile/test error) — not retrying (exit $rc)." >&2
        exit "$rc"
    fi
    delay="${backoffs[$attempt]}"
    attempt=$((attempt + 1))
    sweep_leaked_jvms
    echo "gradle-guard: environmental failure (exit $rc); retry $attempt/$RETRIES after ${delay}s backoff..." >&2
    sleep "$delay"
done
