<p align="center">
  <img src="assets/raccoon_logo.png" alt="CleverKeys Logo" width="200"/>
</p>

<h1 align="center">CleverKeys</h1>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"/></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android"/></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-1.9.20-purple.svg" alt="Kotlin"/></a>
  <a href="https://github.com/tribixbite/CleverKeys/actions"><img src="https://github.com/tribixbite/CleverKeys/actions/workflows/ci.yml/badge.svg" alt="Build"/></a>
</p>

<p align="center">
  <strong>The only fully open-source, offline gesture keyboard for Android.</strong>
</p>

<p align="center">
  <em>No proprietary libraries. No cloud dependencies. No data collection.<br/>Just fast, accurate swipe typing that runs entirely on your device.</em>
</p>

---

## What Makes CleverKeys Unique

### The Only Truly Open-Source Gesture Keyboard

Every other "open-source" swipe keyboard on Android has a catch:
- **HeliBoard** requires a closed-source gesture library for swipe typing
- **FUTO Keyboard** has proprietary components
- **AnySoftKeyboard** has no gesture typing at all
- **FlorisBoard** gesture typing is experimental/incomplete

CleverKeys is different. Our gesture recognition uses a **fully open-source transformer neural network** trained specifically for swipe typing. The model, training code, and datasets are all publicly available at [CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML).

### Clipper+ : Unlimited Clipboard History

Android restricts clipboard access for security - apps can't read clipboard contents in the background. But keyboards are special. As an Input Method Editor (IME), CleverKeys has legitimate clipboard access, making it the only way to get truly unlimited clipboard history without root.

**Features:**
- Unlimited history (configurable by count or storage size)
- Pin important items
- Search through history
- Persistent across reboots
- Export/import for backup

---

## Core Features

### Gesture Typing
- Transformer-based encoder-decoder model (5.4MB encoder + 7.4MB decoder)
- 73%+ word accuracy on common vocabulary
- Sub-200ms predictions with hardware acceleration (XNNPACK)
- Beam search with configurable width for speed/accuracy balance
- 100% local processing - works in airplane mode

### Tap Typing
- Real-time word predictions
- Context-aware suggestions using bigram model
- Autocorrection with keyboard-layout-aware distance
- Learns your vocabulary over time

### Privacy First
- Zero network permissions
- No analytics or telemetry
- No cloud sync
- All processing on-device
- Open source = auditable

### Extensive Customization
- 100+ keyboard layouts inherited from Unexpected Keyboard
- Material 3 theming with custom colors
- Configurable gesture sensitivity
- One-handed mode
- Terminal mode (Ctrl/Meta keys for Termux users)

---

## Installation

### From APK
Download the latest release from [GitHub Releases](https://github.com/tribixbite/CleverKeys/releases).

### Build from Source
```bash
git clone https://github.com/tribixbite/CleverKeys.git
cd CleverKeys
./gradlew assembleDebug
# APK: build/outputs/apk/debug/tribixbite.cleverkeys.apk
```

### Enable the Keyboard
1. **Settings** → **System** → **Languages & input** → **On-screen keyboard**
2. **Manage keyboards** → Enable **CleverKeys**
3. Open any text app, tap the keyboard icon to switch

---

## The ML Model

CleverKeys uses a custom transformer neural network for gesture recognition:

| Component | Details |
|-----------|---------|
| Architecture | Encoder-Decoder Transformer |
| Encoder | Processes swipe trajectories (x, y, velocity, acceleration, nearest keys) |
| Decoder | Generates word predictions from encoded features |
| Format | ONNX (cross-platform, optimized inference) |
| Runtime | ONNX Runtime 1.20.0 with XNNPACK acceleration |
| Total Size | ~13MB (encoder + decoder) |

Training code, model architecture, and datasets: **[CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML)**

---

## Current Status

- **Build**: Compiles successfully (183 Kotlin files, zero errors)
- **APK Size**: ~52MB (includes ONNX runtime + models)
- **Tested on**: Android 8.0+ devices
- **Languages**: English (primary), additional language support in progress

### What Works
- Swipe/gesture typing
- Tap typing with predictions
- Clipboard history
- All inherited Unexpected Keyboard features
- Settings backup/restore

### In Progress
- Expanding language support
- Model accuracy improvements
- UI polish

---

## Documentation

- **[Quick Start](00_START_HERE_FIRST.md)** - Get typing in 3 minutes
- **[User Manual](USER_MANUAL.md)** - Complete feature guide
- **[FAQ](FAQ.md)** - Common questions answered
- **[Contributing](CONTRIBUTING.md)** - How to help

---

## Building

### Requirements
- Android SDK (API 26+)
- Gradle 8.6+
- Kotlin 1.9.20
- JDK 17

### Commands
```bash
# Debug build
./gradlew assembleDebug

# Run tests
./gradlew test

# Lint check
./gradlew lint
```

### Termux Users
Use the included build script which handles ARM64-specific AAPT2:
```bash
./build-on-termux.sh
```

---

## Contributing

Contributions welcome! Areas where help is needed:
- Model accuracy improvements (see [CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML))
- Additional language support
- UI/UX improvements
- Bug reports and testing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## License

GPL-3.0 - see [LICENSE](LICENSE)

---

## Credits & Acknowledgments

### Unexpected Keyboard

CleverKeys began as a fork of [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) by Jules Aguillon ([@Julow](https://github.com/Julow)). Unexpected Keyboard is an excellent, highly customizable keyboard with support for 100+ layouts, and we owe a huge debt to that project.

Over time, our vision diverged significantly:
- Complete rewrite from Java to Kotlin
- Addition of gesture typing via custom neural network
- Clipboard history system (Clipper+)
- Different architectural decisions (coroutines, ONNX integration)

The projects are now ~900 commits apart. Our maintained fork with the divergence history is at: **[tribixbite/Unexpected-Keyboard](https://github.com/tribixbite/Unexpected-Keyboard)**

If you want a battle-tested, lightweight keyboard without gesture typing, we highly recommend the original Unexpected Keyboard.

### Other Credits
- **ONNX Runtime** - Microsoft's inference engine
- **Material 3** - Google's design system
- **Kotlin Coroutines** - JetBrains' async framework

---

## Support

- **Issues**: [GitHub Issues](https://github.com/tribixbite/CleverKeys/issues)
- **Discussions**: [GitHub Discussions](https://github.com/tribixbite/CleverKeys/discussions)
- **Security**: See [SECURITY.md](SECURITY.md)

---

<p align="center">
  <em>Built with Kotlin, ONNX, and a commitment to privacy.</em><br/>
  <em>Developed in Termux on Android.</em><br/><br/>
  🦝✨
</p>
