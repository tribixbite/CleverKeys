<div align="center">
</br>
<img src="./res/mipmap-xxxhdpi/ic_launcher.png" width="200" />

# CleverKeys

<img src="./assets/banner/social_preview.jpg" width="100%" />

</div>

</br>

<p align="center">
  <img alt="API" src="https://img.shields.io/badge/Api%2026+-50f270?logo=android&logoColor=black&style=for-the-badge"/></a>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-a503fc?logo=kotlin&logoColor=white&style=for-the-badge"/></a>
  <img alt="ONNX Runtime" src="https://img.shields.io/badge/ONNX-005CED?logo=onnx&logoColor=white&style=for-the-badge"/></a>
  <img alt="Material 3" src="https://custom-icon-badges.demolab.com/badge/material%20you-lightblue?style=for-the-badge&logoColor=333&logo=material-you"/></a>
  </br>
  </br>

<a href="https://github.com/tribixbite/CleverKeys/releases">
  <img src="https://img.shields.io/github/downloads/tribixbite/CleverKeys/total?color=ff9500&style=for-the-badge&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHRpdGxlPmRvd25sb2FkPC90aXRsZT48cGF0aCBkPSJNNSwyMEgxOVYxOEg1TTE5LDlIMTVWM0g5VjlINUwxMiwxNkwxOSw5WiIgZmlsbD0id2hpdGUiIC8+PC9zdmc+&labelColor=a6660d"/>
</a>

<a href="https://github.com/tribixbite/CleverKeys/stargazers">
  <img src="https://img.shields.io/github/stars/tribixbite/CleverKeys?color=ffff00&style=for-the-badge&labelColor=a1a116&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHRpdGxlPnN0YXI8L3RpdGxlPjxwYXRoIGQ9Ik0xMiwxNy4yN0wxOC4xOCwyMUwxNi41NCwxMy45N0wyMiw5LjI0TDE0LjgxLDguNjJMMTIsMkw5LjE5LDguNjJMMiw5LjI0TDcuNDUsMTMuOTdMNS44MiwyMUwxMiwxNy4yN1oiIGZpbGw9IndoaXRlIiAvPjwvc3ZnPg=="/>
</a>

</br>

<a href="https://github.com/tribixbite/CleverKeys/releases/latest">
  <img src="https://img.shields.io/github/v/release/tribixbite/CleverKeys?color=9b59b6&include_prereleases&logo=github&style=for-the-badge&labelColor=6b21a8"/>
</a>
<a href="https://f-droid.org/packages/tribixbite.cleverkeys">
  <img src="https://img.shields.io/f-droid/v/tribixbite.cleverkeys?style=for-the-badge&logo=f-droid&logoColor=white&color=1976D2&labelColor=0D47A1"/>
</a>

</br>
</br>

</p>

<div align="center">

# 🗺️ Project Overview

CleverKeys is the only fully open-source neural network gesture keyboard for Android. Featuring an AI-powered swipe engine that runs entirely on-device, unlimited clipboard history, and a powerful theme engine with DIY customization. No proprietary libraries. No cloud dependencies. No data collection.

</div>

<p align="middle">
    <img src="./docs/banners/feature_banner.png" width="99%" />
</p>

## What Makes CleverKeys Unique

### Open-Source Gesture Keyboard Comparison

| Feature | CleverKeys | HeliBoard | FUTO | FlorisBoard | AnySoftKeyboard |
|---------|:----------:|:---------:|:----:|:-----------:|:---------------:|
| **Gesture Typing in Termux** | ✅ | ⚠️ Corrupted⁴ | ❌ Broken | ⚠️ Disabled | ⚠️ Experimental |
| **Gesture/Swipe Typing** | ✅ Stable | ✅ Stable | ⚠️ Alpha | ⚠️ Disabled⁵ | ⚠️ Experimental |
| **Multi-Language Swipe**⁹ | ✅ 11 languages | ⚠️ Single | ⚠️ English only | ❌ | ❌ |
| **Gesture Engine Open Source** | ✅ | ❌¹ | ✅² | ✅ | ✅ |
| **ML Training Code Public** | ✅ | N/A | ✅⁶ | N/A³ | N/A³ |
| **Model Size** | 13MB | Proprietary | 62MB | N/A | N/A |
| **APK Size** | ~52MB | ~45MB | ~200MB | ~15MB | ~25MB |
| **Clipboard History** | ✅ Unlimited | ⚠️ 10min default⁷ | ✅ 25 items | ✅ 20-100 items | ❌ 15s hint only |
| **Short-Swipe Actions**⁸ | ✅ 208 gestures | ⚠️ Limited | ❌ | ⚠️ Limited | ⚠️ Limited |
| **Per-Key Customization** | ✅ 204+ commands | ❌ | ❌ | ❌ | ❌ |
| **Custom Layouts** | ✅ XML (8 sublabels) | ✅ JSON | ✅ YAML | ⚠️ JSON (no editor) | ⚠️ Addon packs only |
| **DIY Theme Creator** | ✅ | ✅ | ❌ (16 built-in) | ✅ Snygg v2 | ✅ |
| **Word Suggestions** | ✅ | ✅ | ✅ | ❌ (v0.6 planned) | ✅ |
| **No Internet Access** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **License** | GPL-3.0 | GPL-3.0 | Source-First² | Apache-2.0 | Apache-2.0 |

<details>
<summary><b>Footnotes & Sources</b></summary>

¹ HeliBoard requires Google's proprietary `libjni_latinimegoogle.so` library for gesture typing ([source](https://github.com/Helium314/HeliBoard#gesture-typing))

² FUTO uses "Source First License 1.1" — source-available but restricts commercial use ([source](https://keyboard.futo.org/))

³ FlorisBoard and AnySoftKeyboard use algorithmic approaches (corner matching, template matching) — no ML training involved

⁴ HeliBoard has a [known bug](https://github.com/termux/termux-app/issues/3941) where swipe typing in Termux produces duplicated/corrupted output

⁵ FlorisBoard glide typing is disabled by default and labeled "alpha quality" — must be enabled manually in advanced settings

⁶ FUTO swipe training dataset is public on [HuggingFace](https://huggingface.co/datasets/futo-org/swipe.futo.org) with training code in their repo

⁷ HeliBoard clipboard retention defaults to 10 minutes but is configurable; items are not persisted across restarts by default

⁸ Short-swipe actions: 8 directions × 26 keys = 208 one-touch gestures for navigation (arrows, home/end), editing (select all, undo, cut/copy/paste), function keys (F1-F12), and more — no long-press menus needed

⁹ Multi-language swipe typing: CleverKeys uses the same neural network model for all languages — dictionaries provide word candidates, not language-specific models. 6 languages bundled (en, es, fr, pt, it, de), 5 downloadable (nl, id, ms, tl, sw). Primary + secondary language support with automatic best-match selection.

</details>

### Why CleverKeys?

CleverKeys uses a custom **transformer neural network** (encoder-decoder architecture) trained specifically for swipe typing. Unlike algorithmic approaches, neural models learn complex patterns from real swipe data. The model architecture, training code, and datasets are all publicly available at [CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML) — making it fully reproducible and auditable.

**Key differentiators:**
- **Only keyboard with public ML training pipeline** — verify exactly how the model was trained
- **ONNX format** — cross-platform, hardware-accelerated inference via XNNPACK
- **Sub-200ms predictions** — optimized for mobile with beam search decoding
- **208 customizable short-swipe gestures** — 8 directions × 26 letter keys

### Unlimited Clipboard History
Android restricts clipboard access for security - apps can't read clipboard contents in the background. But keyboards are special. As an Input Method Editor (IME), CleverKeys has legitimate clipboard access, making it the only way to get truly unlimited clipboard history without root.

### 📱 Reliable Swipe Typing in Termux
CleverKeys is the **only open-source keyboard with reliable swipe/gesture typing in Termux**. Other keyboards either disable gestures in terminals (FlorisBoard), produce corrupted output (HeliBoard), or simply don't work (FUTO). Commercial keyboards like Gboard and SwiftKey also disable swipe in Termux. CleverKeys works everywhere — including terminal emulators, SSH sessions, and developer tools.

<div align="center">

# 📲 Install Options

<p align="middle">
    <a href="https://f-droid.org/packages/tribixbite.cleverkeys"><img alt="F-Droid" src="https://img.shields.io/badge/F--Droid-1976D2?style=for-the-badge&logo=f-droid&logoColor=white" height="60"/></a>
    <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22tribixbite.cleverkeys%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Ftribixbite%2FCleverKeys%22%2C%22author%22%3A%22tribixbite%22%2C%22name%22%3A%22CleverKeys%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Afalse%2C%5C%22dontSortReleasesList%5C%22%3Afalse%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Atrue%2C%5C%22releaseDateAsVersion%5C%22%3Afalse%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22appName%5C%22%3A%5C%22CleverKeys%5C%22%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22Neural%20network%20gesture%20keyboard%20for%20Android%5C%22%7D%22%7D"><img alt="Obtainium" src="https://img.shields.io/badge/Obtainium*-0D47A1?style=for-the-badge&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAACXBIWXMAAAsTAAALEwEAmpwYAAADHklEQVR4nO2ZzWsTQRjGf9GDiOLRkxcPgiehoHjxIHgQPPlx8SJ40NqD4EXwIPgH+Ad4EAQFPQiiB0FRET0IYgtFba3VNmljk93ZnZ3ZeWeyE9I0aZKd2drsh0+ysMO+z/vOzM7uzgYCBAi0fRUHpgE/cFygJrAMfAOuA4teBxDx9Z/5Qgf4ARwCfgHXgL/9CmLQw/OeFR6Ao8AqcBlY6zeCdoIoB5YB94EIcAX4028gmj2AWsBH4CmwF7gIbOy3ALT6Au8ALIDpwJLXIBptFqAK/AbmgCngKrDR6wAyXb7QB8BTYD9wDtjidQCZLl/oI+AZcAC4DGz2OoBMly/0CfAcOABcALZ5HUCmyxf6BHgJHATOA9u9DuD8s+X5wfcJ8Bo4BJwFdngdwP5ny/MX3ifAW+AwcArY7XUA588tz1+4jwE/gaPACeBwvwFYAbR7fgPtBN4Dh4HjwFGvA5gAbPf5GYo7Ae+AI8DxfgMwAFi+0M/QnQJ4DxwBjvUbgAHA8oW+g64dwAfgGHC83wAMAJYv9CN0JwE+AseBE/0GYACwfKGfoTsD8Ak4AZzoNwADgOUL/QLdKYCvwAngRL8BGAAsX+gX6M4CfAFOAif7DcAAYPlCv0N3DuAbcAI42W8ABgDLF/odunMA34GTwMl+AzAAWL7Qn9CdB/gBnAJO9RuAAcDyhf6C7gLAT+AUcKrfAAwAli/0N3QXAX4Bp4HT/QZgALB8ob+huwjwGzgNnO43AAOA5Qv9A90lgD/AaeB0vwEYACxf6F/oLgP8BU4Dp/sNwABg+UL/QXcZ4B9wGjjTbwAGAMsX+h+6KwD/gTPAmX4DMAAYX+hOAqwAZ4Ez/QZgfKE7BbAKnAXO9BuA8YXuNMA6cBY4028AxhfaU4DrAeeAc14HML7Q/Q9o7E8DngecAc55HcD4QjcBeA44B5z3OoDxhe4k4CLwHDgHnPc6gPGF7mTgJeA5cB644HUA4wvdKcArwAvgPHDB6wDGF7pTgdeAF8AF4KLXAYwvdKcBbwAvgQvARa8DGF/oTgfeAl4BF4BLXgcIEGB79Q8cPxXm8tdVtgAAAABJRU5ErkJggg==&logoColor=white" height="60"/></a>
    <a href="https://github.com/tribixbite/CleverKeys/releases/latest"><img alt="GitHub" src="https://img.shields.io/badge/GitHub_APK-238636?style=for-the-badge&logo=github&logoColor=white" height="60"/></a>
</p>

<sub>*Recommended — Obtainium pulls directly from GitHub releases, avoiding F-Droid's typical 24-48 hour update delay.</sub>

# 🎨 Extensive Theme Engine

</div>

CleverKeys features one of the most powerful keyboard theme engines available:

### Built-in Themes (18+)
- **CleverKeys Dark** — Deep purple with silver accents (default)
- **CleverKeys Light** — Silver keys with purple accents
- **Neon Cyan/Magenta** — Vibrant cyberpunk aesthetic
- **ePaper / ePaper Black** — High contrast e-ink styles
- **Jungle** — Tropical teal theme
- **Everforest Light** — Soft green nature theme
- **Monet (Auto)** — Material You dynamic colors
- **Dark / Light / Black / Alt Black** — Classic options
- And many more...

### DIY Theme Creator
Build your own themes on-the-fly with full control over:
- **Key Colors** — Default, Activated, Locked, Modifier, Special
- **Label Colors** — Primary, Sub-label, Secondary labels
- **Border Colors** — Full border customization
- **Trail Effects** — Swipe trail color and style

<p align="center">
    <img src="./docs/screenshots/theme-manager.png" width="24%" />
    <img src="./docs/screenshots/theme-dark-selected.png" width="24%" />
    <img src="./docs/screenshots/Screenshot_20251205_063059_CleverKeys (Debug).jpg" width="24%" />
    <img src="./docs/screenshots/Screenshot_20251205_032954_Samsung Notes.jpg" width="24%" />
</p>

<div align="center">

# ✨ Features

</div>

### 🧠 Neural Network Gesture Recognition
- **Transformer-based encoder-decoder model** (5.4MB encoder + 7.4MB decoder)
- **Sub-200ms predictions** with hardware acceleration (XNNPACK)
- **100% local processing** — works in airplane mode
- **Full inference control** — beam width, length normalization, pruning, early stopping

### 📋 Unlimited Clipboard History
As an IME, CleverKeys has legitimate clipboard access that other apps don't:
- **Unlimited history** (configurable by count or storage size)
- **Pin important items**
- **Search through history**
- **Persistent across reboots**
- **Export/Import for backup**

### 🔒 Privacy First
- **Zero network permissions** — literally cannot phone home
- **No analytics or telemetry**
- **No cloud sync**
- **All processing on-device**
- **Open source = auditable**

### ⚙️ Full Prediction Control
Unlike black-box keyboards, CleverKeys exposes all parameters:
- Beam search tuning (width, length normalization, pruning)
- Autocorrect settings (thresholds, style presets)
- Dictionary control (custom words, frequency adjustments)
- Gesture sensitivity and thresholds

### 🎯 Per-Key Short Swipe Customization
Assign custom actions to any key's 8 swipe directions:
- **204+ built-in commands** — navigation, editing, clipboard, function keys, special characters
- **Custom text macros** — email addresses, signatures, code snippets, emoji sequences
- **System actions** — keyboard settings, voice typing, language switch, numeric pad
- **Visual feedback** — custom icons displayed as sublabels on keys
- **Import/Export** — share your customizations as JSON profiles

### ⌨️ 100+ Keyboard Layouts
Inherited from Unexpected Keyboard:
- QWERTY, AZERTY, QWERTZ, Dvorak, Colemak
- Programming layouts with special characters
- International layouts for 30+ languages
- Customizable bottom row and modifiers

### 🛠️ Power User Features
- **Terminal mode** — Ctrl/Meta/Fn keys for Termux users
- **Short swipe gestures** — Swipe keys for symbols, navigation, clipboard, and function keys
- **Selection-Delete mode** — Hold backspace and swipe to select text, then delete on release
- **TrackPoint navigation** — Joystick-style cursor control on nav keys (hold to activate)
- **Timestamp keys** — Insert formatted date/time with custom patterns (8 pre-defined shortcuts)
- **Quick Settings tile** — Switch keyboards from Android Quick Settings
- **Password manager privacy** — Exclude clipboard from password managers (1Password, Bitwarden, etc.)
- **Backup & Restore** — Export all settings and custom data as profiles

<div align="center">

# 🌍 Multi-Language Swipe Typing

</div>

CleverKeys supports swipe typing in **11 languages** with intelligent multi-language features:

### Bundled Languages (6)
Included in the APK — no additional download required:

| Language | Code | Dictionary Size |
|----------|:----:|---------------:|
| English | en | 52,000 words |
| Spanish | es | 50,000 words |
| French | fr | 25,000 words |
| Portuguese | pt | 25,000 words |
| Italian | it | 25,000 words |
| German | de | 25,000 words |

### Downloadable Language Packs (5)
Available via **Settings → Languages → Download Language Packs**:

| Language | Code | Dictionary Size | Source |
|----------|:----:|---------------:|--------|
| Dutch | nl | 20,000 words | wordfreq |
| Indonesian | id | 20,000 words | wordfreq |
| Malay | ms | 20,000 words | wordfreq |
| Tagalog | tl | 20,000 words | wordfreq |
| Swahili | sw | 20,000 words | Wikipedia corpus |

### Multi-Language Features

**Primary + Secondary Language**
- Set your primary language for main typing
- Add a secondary language for automatic detection
- Neural network evaluates both dictionaries simultaneously
- Best prediction selected automatically per swipe

**Smart Accent & Punctuation Insertion**
- Automatic accent marks for languages that need them (café, naïve, señor)
- Language-aware punctuation (Spanish ¿¡, French « », German „")
- Proper handling of contractions across languages

**Per-Language Dictionary Manager**
- Separate tabs for each active language
- View word frequencies and dictionary statistics
- Add custom words per language
- Import/export language-specific word lists

### Creating Custom Language Packs

You can create dictionaries for any language using the included Python scripts:

```bash
# Navigate to scripts directory
cd scripts/

# Install prerequisite
pip install wordfreq

# Option 1: Two-step build from wordfreq (any language wordfreq supports)
python get_wordlist.py --lang fr --output fr_words.txt --count 50000
python build_langpack.py --lang fr --name "French" --input fr_words.txt --use-wordfreq --output langpack-fr.zip

# Option 2: Build from pre-existing binary dictionary (.bin file)
python build_langpack.py --lang sv --name "Swedish" --dict ../src/main/assets/dictionaries/sv_enhanced.bin --output langpack-sv.zip

# Option 3: Build from custom word frequency CSV (format: word,frequency per line)
python build_dictionary.py --input my_words.csv --output my_lang.bin
python build_langpack.py --lang xx --name "MyLang" --dict my_lang.bin --output langpack-xx.zip

# Option 4: Batch build all bundled languages (en, es, fr, de, it, pt, nl, id, ms, tl, sw)
python build_all_languages.py
```

**Script Details:**
- `build_langpack.py` — Creates complete .zip language packs from wordfreq
- `build_dictionary.py` — Builds binary dictionary from CSV word lists
- `build_all_languages.py` — Batch builds all supported languages
- `get_wordlist.py` — Extracts top N words from wordfreq for a language

Language packs are simple .zip files containing:
- `{lang}_enhanced.bin` — Binary dictionary with frequency data
- `{lang}_enhanced.json` — Human-readable word list with frequencies
- `manifest.json` — Metadata (language code, version, word count)

**Pre-built Language Packs:**
Available in [`scripts/dictionaries/`](./scripts/dictionaries/) for testing, or download directly from the app.

<div align="center">

#

<img src="./docs/banners/social_preview.png" width="99%" />

# 📖 Documentation

</div>

- **[User Guide Wiki](https://tribixbite.github.io/CleverKeys/wiki/)** — 38-page comprehensive documentation
- **[Feature Specifications](./docs/specs/)** — Technical documentation for developers
- **[Changelog](./CHANGELOG.md)** — Version history and release notes
- **[Roadmap](./ROADMAP.md)** — Planned features and development path

<div align="center">

# 📚 Tech Stack & Open-source Libraries

</div>

- Minimum SDK level 26 (Android 8.0+)
- [Kotlin](https://kotlinlang.org/) based — 100% Kotlin, zero Java
- [ONNX Runtime](https://onnxruntime.ai/) — Microsoft's cross-platform inference engine
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Asynchronous processing
- [Flow](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/) — Reactive data streams
- [Material 3](https://m3.material.io/) — Google's latest design system

<div align="center">

# 🔧 Building from Source

</div>

### Requirements
- Android SDK (API 26+)
- Gradle 8.6+
- Kotlin 1.9.20
- JDK 17

### Commands
```bash
# Clone repository
git clone https://github.com/tribixbite/CleverKeys.git
cd CleverKeys

# Debug build
./gradlew assembleDebug

# Run tests
./gradlew test

# APK location
# build/outputs/apk/debug/cleverkeys.apk
```

### Termux Users
Use the included build script which handles ARM64-specific AAPT2:
```bash
./build-on-termux.sh
```

<div align="center">

# 🧠 The ML Model

</div>

CleverKeys uses a custom transformer neural network model for gesture recognition:

| Component | Details |
|-----------|---------|
| Architecture | Encoder-Decoder Transformer |
| Encoder | Processes swipe trajectories (x, y, velocity, acceleration, nearest keys) |
| Decoder | Generates word predictions from encoded features |
| Format | ONNX (cross-platform, optimized inference) |
| Runtime | ONNX Runtime 1.20.0 with XNNPACK acceleration |
| Total Size | ~13MB (encoder + decoder) |

Training code, model architecture, and datasets: **[CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML)**

<div align="center">

# ☕ Buy me a coffee

This application is completely free and open source. If you want to support continued development, you can send a donation via the methods below:

| </br> ![Solana](https://img.shields.io/badge/Solana-9945FF?style=for-the-badge&logo=solana&logoColor=white) <br/> <br/> `triQem2gDXHXweNceTKWGfDfN6AnpCHmjR745LXcbix` <br/> <br/> |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|

</div>

<div align="center">

# ❤️ Credits & Acknowledgments

</div>

### Unexpected Keyboard
CleverKeys began as a fork of [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) by Jules Aguillon ([@Julow](https://github.com/Julow)). The original is an excellent, highly customizable keyboard with support for 100+ layouts.

Our projects have since diverged significantly (~900 commits apart):
- Complete rewrite from Java to Kotlin
- Addition of gesture typing via custom neural network model
- Clipboard history system
- Different architecture (coroutines, ONNX integration)

If you want a lightweight keyboard without gesture typing, we recommend the original Unexpected Keyboard.

### Neural Network Model Research
- **[Neural Swipe Typing](https://github.com/proshian/neural-swipe-typing)** by [@proshian](https://github.com/proshian) — Initial architecture inspiration
- **[How We Swipe](https://github.com/nicosio2/how-we-swipe)** — Research paper and dataset on swipe behavior
- **[FUTO Swipe Dataset](https://huggingface.co/datasets/nicosio2/FUTO-swipe-dataset)** — Hugging Face dataset

<div align="center">

# ⭐ Star History

<a href="https://star-history.com/#tribixbite/CleverKeys&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tribixbite/CleverKeys&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tribixbite/CleverKeys&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=tribixbite/CleverKeys&type=Date" />
 </picture>
</a>

# 📢 Support

</div>

- **Issues**: [GitHub Issues](https://github.com/tribixbite/CleverKeys/issues)
- **Discussions**: [GitHub Discussions](https://github.com/tribixbite/CleverKeys/discussions)
- **Security**: See [SECURITY.md](SECURITY.md)

<div align="center">

# ⚖️ License

```
CleverKeys - Neural Gesture Keyboard for Android
Copyright (C) 2024-2026 tribixbite

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

</br>
</br>

<em>Built with Kotlin, ONNX, and a commitment to privacy.</em><br/>
<em>Developed in Termux on Android.</em><br/><br/>
🦝✨

</div>
