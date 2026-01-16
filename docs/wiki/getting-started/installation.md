# Installation

Getting CleverKeys on your Android device is quick and easy.

## Requirements

- **Android 8.0 (Oreo)** or higher
- **50 MB** of storage space
- No internet connection required after installation

## Installation Methods

### Method 1: GitHub Releases (Recommended)

1. Visit the [CleverKeys Releases](https://github.com/tribixbite/CleverKeys/releases) page
2. Download the latest `cleverkeys-vX.X.X.apk` file
3. Open the APK file on your device
4. If prompted, allow installation from unknown sources
5. Tap **Install**

### Method 2: Build from Source

For developers who want to build from source:

```bash
git clone https://github.com/tribixbite/CleverKeys.git
cd CleverKeys
./gradlew assembleRelease
```

The APK will be in `app/build/outputs/apk/release/`.

## After Installation

After installing CleverKeys, you need to:

1. **Enable the keyboard** in Android Settings
2. **Set as default** keyboard
3. **Configure** your preferences

See [Enabling the Keyboard](./enabling-keyboard.md) for detailed steps.

## Permissions

CleverKeys requests minimal permissions:

| Permission | Purpose |
|------------|---------|
| Vibration | Haptic feedback on key presses |
| Storage | Saving user dictionary and preferences |

**No internet permission** - CleverKeys never sends data to any server.

## Troubleshooting

### APK won't install

- Check that "Install from unknown sources" is enabled for your file manager
- Ensure you have enough storage space (50 MB)
- Try downloading the APK again (file may be corrupted)

### App crashes on first launch

- Ensure you're running Android 8.0 or higher
- Try clearing app data: Settings → Apps → CleverKeys → Clear Data

## Next Steps

- [First-Time Setup](./first-time-setup.md) - Complete your keyboard setup
- [Basic Typing](./basic-typing.md) - Learn the fundamentals
