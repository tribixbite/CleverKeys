#!/data/data/com.termux/files/usr/bin/bash
#
# Build script for CleverKeys on Termux ARM64.
# Handles JDK/SDK env, Termux-native aapt2, optional ADB install.
#
# Usage:
#   build-on-termux.sh [debug|release] [flags...]
#
# Flags (any order):
#   --clean        Force clean build (default: incremental)
#   --slow         Disable daemon, lowest CPU/IO priority, single worker
#   --low-mem      Constrain JVM memory further (-Xmx768m, single worker)
#   --no-install   Build only; skip ADB install step
#   --help, -h     Show this message
#
# Env overrides (optional):
#   BUILD_TOOLS_VERSION   Default: 34.0.0 (F-Droid pin: apksigner v34)
#   ANDROID_HOME          Default: $HOME/android-sdk
#   JAVA_HOME             Default: $PREFIX/lib/jvm/java-21-openjdk
#   RELEASE_KEYSTORE+pwds When set, builds are treated as DISTRIBUTION builds
#                         (no daemon, no parallel, no cache for byte-determinism)
#

set -uo pipefail

# --- Argument parsing ---------------------------------------------------------
show_help() { sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# \?//'; exit 0; }

BUILD_TYPE=""
CLEAN=0
SLOW=0
LOW_MEM=0
NO_INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --clean)         CLEAN=1 ;;
        --slow)          SLOW=1 ;;
        --low-mem)       LOW_MEM=1 ;;
        --no-install)    NO_INSTALL=1 ;;
        --help|-h)       show_help ;;
        debug|Debug|DEBUG|release|Release|RELEASE) BUILD_TYPE="${arg,,}" ;;
        *) echo "Error: unknown argument '$arg'. Run '$0 --help'." >&2; exit 1 ;;
    esac
done
BUILD_TYPE="${BUILD_TYPE:-release}"

# --- Helpers ------------------------------------------------------------------
say()  { printf '%s\n' "$*"; }
fail() { echo "Error: $*" >&2; exit 1; }

echo "=== CleverKeys Termux Build Script ==="
echo "Building $BUILD_TYPE APK on Termux ARM64"
echo

# --- Configurable constants ---------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk}"

# --- Environment --------------------------------------------------------------
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION:$PATH"
# Reproducibility: deterministic locale/timezone (C.UTF-8 is bionic-safe).
export TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8
# Reproducibility: deterministic file mtimes for dex/zip outputs (AGP respects this).
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$SCRIPT_DIR" log -1 --format=%ct 2>/dev/null || date +%s)}"
# LOCAL_BUILD: enables verbose logging in build.gradle's BuildConfig.
export LOCAL_BUILD=true

# --- Prerequisite checks ------------------------------------------------------
say "Step 1: Checking prerequisites..."
command -v java  >/dev/null 2>&1 || fail "java not found. Install with: pacman -S openjdk-21"
command -v aapt2 >/dev/null 2>&1 || fail "aapt2 not found. Install with: pacman -S aapt2"
[ -d "$ANDROID_HOME" ] || fail "Android SDK not found at $ANDROID_HOME"
[ -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION" ] \
    || fail "build-tools/$BUILD_TOOLS_VERSION missing. Set BUILD_TOOLS_VERSION env var or install via sdkmanager."

# --- Free memory: install trap BEFORE side-effect ------------------------------
if command -v tmx >/dev/null 2>&1; then
    trap 'tmx resume-all 2>/dev/null || true' EXIT INT TERM
    say "Step 2: Suspending other tmx sessions to free memory..."
    tmx suspend-others cleverkeys 2>/dev/null || true
fi

# --- Clean / daemon stop ------------------------------------------------------
say "Step 3: Build setup..."
if [ "$CLEAN" -eq 1 ] || [ ! -d "$SCRIPT_DIR/build" ]; then
    say "  Stopping any stale gradle daemons..."
    ./gradlew --stop >/dev/null 2>&1 || true
    say "  Cleaning previous builds..."
    ./gradlew clean || say "  Warning: clean failed, continuing"
else
    say "  Incremental build (use --clean to force)."
fi

# --- Gradle task & output dir -------------------------------------------------
USER_SUPPLIED_KEYSTORE=0
if [ "$BUILD_TYPE" = "release" ]; then
    say "Step 4: Building Release APK..."
    if [ -n "${RELEASE_KEYSTORE:-}" ] && [ -f "$RELEASE_KEYSTORE" ]; then
        say "  Using release keystore: $RELEASE_KEYSTORE"
        USER_SUPPLIED_KEYSTORE=1
    else
        say "  No RELEASE_KEYSTORE in env; creating throwaway test signing key..."
        if [ ! -f release.keystore ]; then
            keytool -genkey -v -keystore release.keystore -alias release \
                -keyalg RSA -keysize 2048 -validity 10000 \
                -storepass android -keypass android \
                -deststoretype pkcs12 \
                -dname "CN=Test, OU=Test, O=Test, L=Test, S=Test, C=US" \
                || fail "Failed to create test keystore"
        fi
        export RELEASE_KEYSTORE="release.keystore"
        export RELEASE_KEYSTORE_PASSWORD="android"
        export RELEASE_KEY_ALIAS="release"
        export RELEASE_KEY_PASSWORD="android"
        say "  WARNING: built APK is signed with a throwaway test key — do NOT distribute."
    fi
    GRADLE_TASK="assembleRelease"
    APK_DIR="build/outputs/apk/release"
else
    GRADLE_TASK="assembleDebug"
    APK_DIR="build/outputs/apk/debug"
    say "Step 4: Building Debug APK..."
fi

# --- Memory + worker tuning ---------------------------------------------------
# CPU/worker cap is intentional: this device OOMs with concurrent multi-app builds.
JVM_MEM="-Xmx1536m -XX:MaxMetaspaceSize=384m"
WORKERS_MAX=2
if [ "$LOW_MEM" -eq 1 ]; then
    JVM_MEM="-Xmx768m -XX:MaxMetaspaceSize=256m"
    WORKERS_MAX=1
fi

# --- Speed vs. reproducibility -----------------------------------------------
# Distribution build (real keystore in env) → byte-deterministic output, slow.
# Local iteration (debug or test-signed release) → speed flags on.
DAEMON_FLAG=""
SPEED_FLAGS=(--parallel --build-cache)
DAEMON_IDLE=900000  # 15 min — saves ~250 MB resident vs the 3h default
if [ "$USER_SUPPLIED_KEYSTORE" -eq 1 ]; then
    say "  Distribution build: reproducibility flags ON (no daemon, no parallel, no cache)"
    DAEMON_FLAG="--no-daemon"
    SPEED_FLAGS=(--no-parallel --no-build-cache)
fi

NICE_PREFIX=()
if [ "$SLOW" -eq 1 ]; then
    say "  Slow mode: daemon disabled, lowest CPU/IO priority, single worker"
    DAEMON_FLAG="--no-daemon"
    WORKERS_MAX=1
    NICE_PREFIX=(nice -n 19 ionice -c 3)
fi

# --- Gradle invocation --------------------------------------------------------
LOG_FILE="build-${BUILD_TYPE}-$(date +%Y%m%d-%H%M%S).log"
say "  Logging to $LOG_FILE"
say "  This may take a few minutes on first run..."

# pipefail propagates gradle's exit through the tee pipe.
"${NICE_PREFIX[@]}" \
    ./gradlew "$GRADLE_TASK" \
    -Dorg.gradle.jvmargs="$JVM_MEM" \
    -Dorg.gradle.workers.max="$WORKERS_MAX" \
    -Dorg.gradle.daemon.idletimeout="$DAEMON_IDLE" \
    -Pandroid.aapt2FromMavenOverride="/data/data/com.termux/files/usr/bin/aapt2" \
    ${DAEMON_FLAG} \
    "${SPEED_FLAGS[@]}" \
    --warning-mode=none \
    --console=plain \
    2>&1 | tee "$LOG_FILE"
GRADLE_RC=${PIPESTATUS[0]}

if [ "$GRADLE_RC" -ne 0 ]; then
    echo
    echo "=== BUILD FAILED (gradle exit $GRADLE_RC) ==="
    echo "Log: $LOG_FILE"
    echo "Common issues:"
    echo "  1. aapt2 missing (pacman -S aapt2)"
    echo "  2. OOM (re-run with --slow or --low-mem)"
    echo "  3. SDK version drift (verify build-tools/$BUILD_TOOLS_VERSION exists)"
    exit "$GRADLE_RC"
fi

# --- Locate output APK --------------------------------------------------------
APK_FILES=$(find "$APK_DIR" -name '*.apk' 2>/dev/null | head -1)
if [ -z "$APK_FILES" ]; then
    echo
    echo "=== BUILD FAILED ==="
    echo "Gradle reported success but no APK in $APK_DIR. Log: $LOG_FILE"
    exit 1
fi

echo
say "=== BUILD SUCCESSFUL ==="
say "APKs in: $APK_DIR"
ls -lh "$APK_DIR"/*.apk
echo

APK_PATH=$(find "$APK_DIR" -name '*arm64*.apk' 2>/dev/null | head -1)
APK_PATH="${APK_PATH:-$APK_FILES}"

# Convenience copy for debug builds (sideload-friendly path on /sdcard).
if [ "$BUILD_TYPE" = "debug" ]; then
    say "Copying APK to /sdcard/unexpected/ for manual updates..."
    mkdir -p /sdcard/unexpected
    if cp "$APK_PATH" /sdcard/unexpected/debug-kb.apk 2>/dev/null; then
        ls -lh /sdcard/unexpected/debug-kb.apk
    else
        say "  Warning: could not copy to /sdcard/unexpected/"
    fi
fi

if [ "$NO_INSTALL" -eq 1 ]; then
    say "Skipping ADB install (--no-install)."
    exit 0
fi

# --- ADB install --------------------------------------------------------------
get_local_ip() {
    if command -v ip >/dev/null 2>&1; then
        ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -1
    elif command -v ifconfig >/dev/null 2>&1; then
        ifconfig 2>/dev/null | awk '/wlan0/{getline; if(/inet /) print $2}'
    fi
}

connect_adb_wireless() {
    local host="${1:-$(get_local_ip)}"
    local port ports scanned
    if [ -z "$host" ]; then
        say "  Could not determine local IP for wireless ADB scan."
        return 1
    fi
    say "  Scanning for ADB on $host..."

    # Disconnect ONLY existing connections to this same host — never wipe other ADB sessions.
    adb devices 2>/dev/null | awk -v h="$host" '$1 ~ h":" {print $1}' \
        | while read -r addr; do adb disconnect "$addr" >/dev/null 2>&1 || true; done

    ports="5555"
    if command -v nmap >/dev/null 2>&1; then
        scanned=$(nmap -p 30000-50000 --open -oG - "$host" 2>/dev/null \
            | awk -F'Ports: ' '/Ports:/{
                n=split($2,a,/, /);
                for(i=1;i<=n;i++){ if (a[i] ~ /open/){ split(a[i],f,"/"); print f[1] } }
              }')
        ports="$ports $scanned"
    fi

    for port in $ports; do
        printf '  Trying %s:%s... ' "$host" "$port"
        if adb connect "$host:$port" >/dev/null 2>&1; then
            for _ in 1 2 3; do
                sleep 0.5
                if adb devices | grep -q "^$host:$port[[:space:]]*device"; then
                    echo "connected!"
                    return 0
                fi
            done
            echo "no handshake"
            adb disconnect "$host:$port" >/dev/null 2>&1
        else
            echo "no response"
        fi
    done
    return 1
}

# Install package id (with .debug suffix for debug builds, see build.gradle).
PKG_BASE="tribixbite.cleverkeys"
PKG_INSTALL="$PKG_BASE"
[ "$BUILD_TYPE" = "debug" ] && PKG_INSTALL="$PKG_BASE.debug"

ADB_PATH="/data/data/com.termux/files/usr/bin/adb"
if [ ! -f "$ADB_PATH" ]; then
    say "ADB not found (pacman -S android-tools); skipping install."
    exit 0
fi

say "Step 5: Connecting to device..."
if "$ADB_PATH" devices | grep -q '	device$'; then
    say "  Wired ADB device detected."
else
    say "  No wired device; attempting wireless..."
    if ! connect_adb_wireless; then
        say "  Could not establish ADB connection."
        if command -v termux-open >/dev/null 2>&1; then
            say "  Falling back to manual install via termux-open..."
            termux-open "$APK_PATH" 2>/dev/null || say "  Share the APK manually: $APK_PATH"
        else
            say "  Share the APK manually: $APK_PATH"
        fi
        exit 0
    fi
fi

say "Step 6: Installing $PKG_INSTALL..."
# CRITICAL: never run 'adb uninstall' on the prod package id — that would wipe user data.
# Debug builds install at $PKG_BASE.debug (separate applicationId), so they coexist with prod.
#
# Capture stderr so we can decode the common failure modes that aren't obvious
# to a casual reader of Android's INSTALL_FAILED_* codes. Existing app data is
# always safe — `adb install -r` cannot wipe data; it only succeeds or rejects.
INSTALL_OUTPUT=$("$ADB_PATH" install -r "$APK_PATH" 2>&1)
INSTALL_RC=$?
if [ $INSTALL_RC -eq 0 ]; then
    echo
    say "=== APK INSTALLED SUCCESSFULLY ==="
    if [ "$BUILD_TYPE" = "debug" ]; then
        say "Installed as $PKG_INSTALL — separate from your prod $PKG_BASE install."
        say "Enable in: Settings → System → Languages & input → Virtual keyboard"
        say "Look for 'CleverKeys (Debug)'."
    fi
    exit 0
fi

# Install failed — decode the common Android error codes into actionable advice.
echo
say "ADB install failed:"
echo "$INSTALL_OUTPUT" | sed 's/^/    /'
echo
if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match\|INCONSISTENT_CERTIFICATES"; then
    say "DIAGNOSIS: signing key mismatch."
    say "  The installed $PKG_INSTALL is signed with a different key than this build."
    say "  Your existing app data is SAFE — Android rejected the replace, nothing was written."
    say
    if [ "$BUILD_TYPE" = "release" ]; then
        if [ "$USER_SUPPLIED_KEYSTORE" -eq 1 ]; then
            say "  You used your release keystore ($RELEASE_KEYSTORE), but it doesn't match"
            say "  whatever signed the currently-installed APK. Possibilities:"
            say "    1. Installed APK came from F-Droid (different upstream signer)"
            say "    2. Installed APK came from GitHub Releases (different signer)"
            say "    3. Your keystore changed since last install"
            say "  To install this build alongside the existing one, run: $0 debug"
        else
            say "  No RELEASE_KEYSTORE in env — this build is signed with a throwaway test key."
            say "  Set RELEASE_KEYSTORE/RELEASE_KEY_ALIAS/passwords in your shell to match the"
            say "  installed APK's signer, or run: $0 debug  (installs side-by-side)."
        fi
    else
        say "  This is unusual for a debug build (applicationId .debug should be unique)."
        say "  Did someone else's debug APK get installed under this package id?"
    fi
elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_VERSION_DOWNGRADE"; then
    say "DIAGNOSIS: version downgrade."
    say "  Installed APK has a higher versionCode than this build."
    say "  Your data is SAFE — Android refused the downgrade."
    say "  To force-install (data preserved), re-run with: $0 $BUILD_TYPE  after a 'git pull'."
elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_INSUFFICIENT_STORAGE"; then
    say "DIAGNOSIS: device storage full. Free up space and retry."
fi

if command -v termux-open >/dev/null 2>&1; then
    say "Falling back to manual install..."
    termux-open "$APK_PATH" 2>/dev/null || say "  Share the APK manually: $APK_PATH"
else
    say "Share the APK manually: $APK_PATH"
fi
exit 1
