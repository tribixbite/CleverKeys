# CleverKeys Build & Install Scripts

## Quick Start

### Build and Install (Recommended)
```bash
./build-install.sh
```
Builds the APK and automatically installs it using the best available method.

### Just Build
```bash
./gradlew assembleDebug
# or
./build-on-termux.sh
```

### Just Install
```bash
./install.sh
```
Installs existing APK from `build/outputs/apk/debug/`.

### Test Runtime
```bash
./test-runtime.sh
```
Validates APK and provides testing instructions.

## Installation Methods

The `install.sh` script tries multiple methods automatically:

### Method 1: termux-open (Best)
- Opens Android package installer UI
- User taps "Install" button
- Most reliable method

### Method 2: ADB
- Requires USB debugging or wireless ADB enabled
- Fully automatic installation
- Uninstalls old version first

### Method 3: /sdcard Copy
- Copies APK to Downloads folder
- User opens file manager and taps APK
- Works without special permissions

### Method 4: Termux Storage
- Uses Termux shared storage
- May require `termux-setup-storage`

## Build Scripts

### build-install.sh
Complete build and install workflow:
- Runs `./gradlew assembleDebug`
- Automatically runs `./install.sh`
- One command to go from code to installed app

### build-on-termux.sh
Advanced build script with:
- Termux ARM64 compatibility
- AAPT2 wrapper setup
- Resource generation
- ADB wireless scanning
- Debug/release builds

### install.sh
Smart installer that:
- Detects best installation method
- Falls back through multiple options
- Provides clear instructions if all fail

### test-runtime.sh
Runtime validation:
- Checks APK contents
- Verifies ONNX models
- Provides testing instructions
- Shows logcat monitoring commands

## Usage Examples

### First Time Setup
```bash
# Make scripts executable
chmod +x *.sh

# Build and install
./build-install.sh
```

### Development Workflow
```bash
# Make changes to code...

# Quick rebuild and install
./build-install.sh

# Or just rebuild
./gradlew assembleDebug

# Then install
./install.sh
```

### Manual Installation
```bash
# If automatic install fails
termux-open build/outputs/apk/debug/tribixbite.keyboard2.debug.apk

# Or via ADB from PC
adb install build/outputs/apk/debug/tribixbite.keyboard2.debug.apk
```

### Testing
```bash
# Run validation tests
./test-runtime.sh

# Monitor logs
logcat | grep -E "CleverKeys|OnnxSwipe|Neural"
```

## Troubleshooting

### "APK not found"
```bash
# Build first
./gradlew assembleDebug
```

### "termux-open not available"
```bash
# Install Termux:API
pkg install termux-api

# Or use ADB
pkg install android-tools
```

### "ADB devices not found"
Enable USB debugging:
1. Settings → About phone
2. Tap "Build number" 7 times
3. Settings → Developer options
4. Enable "USB debugging"

Or use wireless ADB:
```bash
# On device
setprop service.adb.tcp.port 5555
stop adbd
start adbd

# From PC
adb connect <device-ip>:5555
```

### "Build failed"
```bash
# Check build log
cat build-debug.log

# Clean and rebuild
./gradlew clean
./gradlew assembleDebug
```

### "Installation failed"
Copy to Downloads manually:
```bash
cp build/outputs/apk/debug/tribixbite.keyboard2.debug.apk /sdcard/Download/
# Open file manager and tap the APK
```

## APK Locations

### Build Output
- Debug: `build/outputs/apk/debug/tribixbite.keyboard2.debug.apk`
- Release: `build/outputs/apk/release/tribixbite.keyboard2.apk`

### Install Copies
- `/sdcard/Download/cleverkeys-debug.apk`
- `~/storage/downloads/cleverkeys-debug.apk`
- `/sdcard/unexpected/debug-kb.apk` (from build-on-termux.sh)

## Package Info

- Package Name: `tribixbite.keyboard2`
- Debug APK: ~48MB (includes ONNX models)
- Min SDK: 24 (Android 7.0)
- Target SDK: 34 (Android 14)

## Post-Installation

1. **Enable Keyboard**:
   - Settings → System → Languages & input
   - Virtual keyboard → Manage keyboards
   - Enable "CleverKeys"

2. **Set as Default**:
   - Tap any text field
   - Tap keyboard icon in navigation bar
   - Select "CleverKeys"

3. **Test Neural Prediction**:
   - Swipe across keyboard
   - Check suggestion bar for predictions
   - Monitor logs: `logcat -s OnnxSwipePredictor`

4. **Configure Settings**:
   - Open CleverKeys launcher activity
   - Tap settings button
   - Adjust neural parameters (beam width, threshold, etc.)
