#!/data/data/com.termux/files/usr/bin/bash

# Complete build script for Unexpected Keyboard on Termux ARM64
# This script handles all the compatibility issues
# Usage: ./build-on-termux.sh [debug|release]

BUILD_TYPE="${1:-debug}"
BUILD_TYPE_LOWER=$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')

echo "=== Unexpected Keyboard Termux Build Script ==="
echo "Building $BUILD_TYPE_LOWER APK on Termux ARM64"
echo

# Validate build type
if [[ "$BUILD_TYPE_LOWER" != "debug" && "$BUILD_TYPE_LOWER" != "release" ]]; then
    echo "Error: Invalid build type. Use 'debug' or 'release'"
    echo "Usage: $0 [debug|release]"
    exit 1
fi

# 1. Set up environment
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"

echo "Step 1: Checking prerequisites..."

# Check Java
if ! java -version &>/dev/null; then
    echo "Error: Java not found. Install with: pkg install openjdk-17"
    exit 1
fi

# Check Gradle
if ! gradle -v &>/dev/null; then
    echo "Error: Gradle not found. Install with: pkg install gradle"
    exit 1
fi

# Check Android SDK
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: Android SDK not found at $ANDROID_HOME"
    echo "Please install Android SDK first"
    exit 1
fi

# Check qemu-x86_64 for AAPT2 wrapper
if ! command -v qemu-x86_64 &>/dev/null; then
    echo "Error: qemu-x86_64 not found. Install with: pkg install qemu-user-x86-64"
    exit 1
fi

echo "Step 2: Preparing layout resources..."

# Ensure layout files are copied (gradle task sometimes doesn't run)
if [ ! -d "build/generated-resources/xml" ] || [ -z "$(ls -A build/generated-resources/xml 2>/dev/null)" ]; then
    echo "Copying layout definitions..."
    mkdir -p build/generated-resources/xml
    cp srcs/layouts/*.xml build/generated-resources/xml/ 2>/dev/null || true
fi

echo "Step 3: Cleaning previous builds..."
./gradlew clean || {
    echo "Warning: Clean failed, continuing anyway..."
}

# Re-copy layouts after clean
mkdir -p build/generated-resources/xml
cp srcs/layouts/*.xml build/generated-resources/xml/ 2>/dev/null || true

# Determine gradle task and output path
if [ "$BUILD_TYPE_LOWER" = "release" ]; then
    echo "Step 4: Building Release APK..."
    echo "Note: Release builds require signing configuration."
    echo "Creating a test signing key for release build..."
    
    # Create a test keystore for release builds if not present
    if [ ! -f "release.keystore" ]; then
        keytool -genkey -v -keystore release.keystore -alias release \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass android -keypass android \
            -dname "CN=Test, OU=Test, O=Test, L=Test, S=Test, C=US" 2>/dev/null || {
            echo "Warning: Could not create release keystore"
        }
    fi
    
    # Set environment variables for release signing
    export RELEASE_KEYSTORE="release.keystore"
    export RELEASE_KEYSTORE_PASSWORD="android"
    export RELEASE_KEY_ALIAS="release"
    export RELEASE_KEY_PASSWORD="android"
    
    GRADLE_TASK="assembleRelease"
    APK_PATH="build/outputs/apk/release/juloo.keyboard2.apk"
else
    GRADLE_TASK="assembleDebug"
    APK_PATH="build/outputs/apk/debug/juloo.keyboard2.debug.apk"
    echo "Step 4: Building Debug APK..."
fi

echo "This may take a few minutes on first run..."

# Build with Termux-specific configuration (optimized for speed)
./gradlew $GRADLE_TASK \
    -Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m" \
    -Pandroid.aapt2FromMavenOverride="/data/data/com.termux/files/home/git/Unexpected-Keyboard/tools/aapt2-arm64/aapt2" \
    --no-daemon \
    --warning-mode=none \
    --console=plain \
    --parallel \
    --build-cache \
    2>&1 | tee build-${BUILD_TYPE_LOWER}.log

# Check build result
if [ -f "$APK_PATH" ]; then
    echo
    echo "=== BUILD SUCCESSFUL! ==="
    echo "APK created at: $APK_PATH"
    echo
    ls -lh "$APK_PATH"
    echo
    
    # Copy to /sdcard/unexpected/ for easy updates
    if [ "$BUILD_TYPE_LOWER" = "debug" ]; then
        echo "Copying APK to /sdcard/unexpected/ for updates..."
        mkdir -p /sdcard/unexpected
        cp "$APK_PATH" /sdcard/unexpected/debug-kb.apk
        if [ -f "/sdcard/unexpected/debug-kb.apk" ]; then
            echo "APK copied to: /sdcard/unexpected/debug-kb.apk"
            ls -lh /sdcard/unexpected/debug-kb.apk
        else
            echo "Warning: Failed to copy APK to /sdcard/unexpected/"
        fi
    fi
    
    # Try ADB wireless connection
    echo
    echo "Step 5: Attempting ADB wireless connection..."
    
    # Function to find and connect to ADB wireless
    connect_adb_wireless() {
        # Save shell's errexit state
        case $- in *e*) was_e=1;; esac
        set +e
        
        # Get host IP from wlan0 or use provided host
        if [ -n "$1" ]; then
            HOST="$1"
        else
            # Try to get wlan0 IP
            HOST=$(ifconfig 2>/dev/null | awk '/wlan0/{getline; if(/inet /) print $2}')
            
            # Fallback to any non-loopback interface
            if [ -z "$HOST" ]; then
                HOST=$(ifconfig 2>/dev/null | awk '/inet / && !/127.0.0.1/{print $2; exit}')
            fi
        fi
        
        if [ -z "$HOST" ]; then
            echo "Could not determine network IP address"
            echo "You may need to provide the device IP manually"
            [ -n "$was_e" ] && set -e
            return 1
        fi
        
        echo "Scanning for ADB on host: $HOST"
        
        # Disconnect any existing connections
        adb disconnect -a >/dev/null 2>&1
        
        # Try standard port first, then scan for open ports
        PORTS="5555"
        
        # Check if nmap is available for port scanning
        if command -v nmap &>/dev/null; then
            echo "Scanning ports 30000-50000 for ADB..."
            SCANNED_PORTS=$(nmap -p 30000-50000 --open -oG - "$HOST" 2>/dev/null | \
                awk -F"Ports: " '/Ports:/{
                    n=split($2,a,/, /); 
                    for(i=1;i<=n;i++){ 
                        if (a[i] ~ /open/){ 
                            split(a[i],f,"/"); 
                            print f[1] 
                        } 
                    }
                }')
            PORTS="$PORTS $SCANNED_PORTS"
        fi
        
        # Try to connect to each port
        for port in $PORTS; do
            echo -n "Trying $HOST:$port... "
            
            if adb connect "$HOST:$port" >/dev/null 2>&1; then
                # Wait and verify connection
                for i in 1 2 3; do
                    sleep 0.5
                    if adb devices | grep -q "^$HOST:$port[[:space:]]*device"; then
                        echo "connected!"
                        [ -n "$was_e" ] && set -e
                        return 0
                    fi
                done
                echo "failed to verify"
                adb disconnect "$HOST:$port" >/dev/null 2>&1
            else
                echo "no response"
            fi
        done
        
        echo "No working ADB port found on $HOST"
        [ -n "$was_e" ] && set -e
        return 1
    }
    
    # Try to connect and install via ADB
    ADB_PATH="/data/data/com.termux/files/usr/bin/adb"
    if [ -f "$ADB_PATH" ]; then
        # Check if ADB device is already connected
        if "$ADB_PATH" devices | grep -q "device$"; then
            echo "ADB device detected, installing directly..."
            if "$ADB_PATH" install "$APK_PATH"; then
                echo
                echo "=== APK INSTALLED SUCCESSFULLY! ==="
                echo "Direct ADB install worked!"
                exit 0
            fi
        fi
        echo "No ADB device connected, scanning for wireless connections..."
        if connect_adb_wireless; then
            echo "Installing APK via ADB..."
            
            # Uninstall old version if it's a debug build
            if [ "$BUILD_TYPE_LOWER" = "debug" ]; then
                echo "Uninstalling previous debug version..."
                adb uninstall juloo.keyboard2.debug 2>/dev/null || true
            fi
            
            # Install the new APK
            if adb install -r "$APK_PATH"; then
                echo
                echo "=== APK INSTALLED SUCCESSFULLY! ==="
                echo "The keyboard has been installed on your device."
                echo
                echo "To enable it:"
                echo "  1. Go to Settings → System → Languages & input → Virtual keyboard"
                echo "  2. Enable 'Unexpected Keyboard'"
                echo "  3. Switch to it using the keyboard selector"
            else
                echo "ADB install failed, falling back to manual installation"
            fi
        else
            echo "Could not establish ADB connection"
        fi
    else
        echo "ADB not found. Install with: pkg install android-tools"
    fi
    
    # Fallback options if ADB fails
    if command -v termux-open &>/dev/null; then
        # Fallback to termux-open if available
        echo "Opening APK for installation..."
        termux-open "$APK_PATH" 2>/dev/null || {
            echo "To install manually, share the APK file to your file manager"
        }
    else
        # Manual instructions as last resort
        echo "To install on device:"
        echo "  1. Share the APK to your file manager"
        echo "  2. Open the APK file to install"
    fi
    
    if [ "$BUILD_TYPE_LOWER" = "release" ]; then
        echo
        echo "Note: Release APK is unsigned. You need to sign it before distribution."
        echo "For testing, you can use debug build instead."
    fi
else
    echo
    echo "=== BUILD FAILED ==="
    echo "Check build-${BUILD_TYPE_LOWER}.log for details"
    echo
    echo "Common issues:"
    echo "1. AAPT2 compatibility - ensure qemu-x86_64 is installed"
    echo "2. Memory issues - try closing other apps"
    echo "3. Missing layouts - check if srcs/layouts/*.xml exist"
    echo "4. SDK version mismatch - check Android SDK installation"
    exit 1
fi