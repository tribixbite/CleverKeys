# Installation

Getting CleverKeys on your Android device is quick and easy.

## Requirements

- **Android 5.0 (Lollipop)** or higher (minSdk 21)
- Approximately **25 MB** of storage space (includes ONNX neural model)
- No internet connection required - works fully offline

## Installation Methods

### Method 1: GitHub Releases (Recommended)

1. Visit the [CleverKeys Releases](https://github.com/tribixbite/CleverKeys/releases) page
2. Download the APK for your device architecture:
   - `CleverKeys-v1.2.9-arm64-v8a.apk` - Most modern phones (ARM 64-bit)
   - `CleverKeys-v1.2.9-armeabi-v7a.apk` - Older phones (ARM 32-bit)
   - `CleverKeys-v1.2.9-x86_64.apk` - x86 devices / emulators
3. Open the APK file on your device
4. If prompted, allow installation from unknown sources
5. Tap **Install**

> **Tip:** If unsure which APK to download, use `arm64-v8a` — it works on all modern Android phones.

### Method 2: Obtainium (Auto-Updates)

[Obtainium](https://github.com/ImranR98/Obtainium) lets you install CleverKeys directly from GitHub with automatic update notifications:

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases) on your device
2. Open Obtainium and tap **Add App**
3. Enter the URL: `https://github.com/tribixbite/CleverKeys`
4. Obtainium will detect releases automatically
5. Select your preferred APK variant (arm64-v8a for most devices)
6. Tap **Install**

Obtainium will notify you when new versions are available.

### Method 3: F-Droid

CleverKeys is available on F-Droid for users who prefer verified open-source builds:

1. Install [F-Droid](https://f-droid.org/) if you haven't already
2. Search for **CleverKeys** in the F-Droid app
3. Tap **Install**

> **Note:** F-Droid builds may lag behind GitHub releases by a few days as F-Droid builds from source independently.

### Method 4: Build from Source

For developers who want to build from source:

```bash
git clone https://github.com/tribixbite/CleverKeys.git
cd CleverKeys
./gradlew assembleRelease
```

The APKs will be in `build/outputs/apk/release/` (one per ABI: arm64-v8a, armeabi-v7a, x86_64).

## After Installation

After installing CleverKeys, you need to:

1. **Enable the keyboard** in Android Settings
2. **Set as default** keyboard
3. **Configure** your preferences

See [Enabling the Keyboard](./enabling-keyboard.md) for detailed steps.

## Permissions

CleverKeys requests minimal permissions:

| Permission | Android Name | Purpose |
|------------|-------------|---------|
| Vibration | `android.permission.VIBRATE` | Haptic feedback on key presses |
| User Dictionary | `android.permission.READ_USER_DICTIONARY` | Reading system user dictionary for word suggestions |

**No internet permission** — CleverKeys never sends data to any server.
**No storage permission** — Uses app-private storage and Android's Storage Access Framework (SAF) for backup/restore.

## Troubleshooting

### APK won't install

- Check that "Install from unknown sources" is enabled for your file manager
- Ensure you have enough storage space (~25 MB)
- Try downloading the APK again (file may be corrupted)

### App crashes on first launch

- Ensure you're running Android 5.0 or higher
- Try clearing app data: Settings → Apps → CleverKeys → Clear Data

---

**Next:** [First-Time Setup](./first-time-setup.md) | [Basic Typing](./basic-typing.md)
