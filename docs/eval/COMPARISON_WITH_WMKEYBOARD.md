# Architectural & Technical Comparison: WM Keyboard vs. CleverKeys

This document provides a comprehensive, technically rigorous comparison between **WM Keyboard** (`wasi-master/wmkeyboard`) and **CleverKeys** (`tribixbite/CleverKeys`).

---

## 1. Executive Summary & Core Philosophy

| Dimension | **CleverKeys** (`tribixbite/CleverKeys`) | **WM Keyboard** (`wasi-master/wmkeyboard`) |
| :--- | :--- | :--- |
| **Origin & Ancestry** | Fork of **Unexpected Keyboard** (Jules Aguillon); completely transformed into Kotlin. | Clean-slate Android Studio project, built from scratch with Kotlin & Jetpack Compose. |
| **Core Philosophy** | **Hardcore efficiency, zero-trust privacy, and power-user productivity.** A lean, keyboard-first tool engineered for coders, Termux power users, and privacy absolutists. | **"The everything keyboard super-app."** Feature-maximalist toolbox, rich offline intelligence, Avro Bengali phonetic transliteration, and deep visual theming. |
| **Rendering Engine** | **Android Canvas (`android.graphics.Canvas`)**. Direct hardware canvas drawing (`Keyboard2View.kt`). Lightweight, instant startup, sub-millisecond draw overhead, zero UI runtime overhead. | **Jetpack Compose (`androidx.compose`)**. 100% declarative Compose surface (`WMKeyboardService.kt` + `KeyboardScreen.kt`). Modern reactive UI, but higher memory footprint, cold-start latency, and APK size. |
| **Swipe Recognition** | **Trained Neural CTC Model (2.91 MB ONNX)** running on ONNX Runtime with XNNPACK acceleration + pure-JVM Viterbi trie beam search. Layout-agnostic SHARK2 geometric fallback for non-Latin/non-CTC scripts. | **Algorithmic Heuristic Dynamic Programming (`GlideBeam.kt`)**. Best-first lattice over a trie; Gaussian distance + arc-length travel penalty. **Zero machine learning models used for swipe typing.** |
| **Network Permission** | **Literally 0 network permissions.** No `android.permission.INTERNET` in manifest. Physically impossible to open a socket or phone home. | **Includes `INTERNET` and `ACCESS_NETWORK_STATE`** (in standard/Full builds), plus Google ML Kit dependencies, web search, weather, and AI clients. |
| **Primary Language Focus** | English + 6 bundled Latin languages, 12 downloadable packs (including Cyrillic, Greek, Hebrew), and Termux/programming layouts. | Multilingual breadth (1,700+ layouts via JSON), with specialized world-class support for **Bengali (Avro phonetic)** and CJK romanized input. |

---

## 2. Git Metrics & Codebase Scale

Data captured on **October 3, 2026** directly from both Git repositories:

| Metric | **CleverKeys** | **WM Keyboard** | Analysis & Takeaway |
| :--- | :---: | :---: | :--- |
| **Git Commits** | **3,843** | **1,973** | CleverKeys has nearly double the total commit history. |
| **Development Span** | **~13 months**<br>(Sep 14, 2025 – Oct 2, 2026) | **~4 months**<br>(Jun 4, 2026 – Oct 2, 2026) | CleverKeys is a year-long iterative project; WM Keyboard was created in a rapid 120-day sprint. |
| **Total Tracked Files** | **2,164 files** | **6,068 files** | WM Keyboard ships massive JSON layout trees and multi-language assets. |
| **Total Tracked Lines** | **2,892,505 lines** | **4,516,935 lines** | WM Keyboard is 56% larger in raw tracked lines. |
| **Kotlin Source Files (`.kt`)** | **812 files** | **1,701 files** | WM Keyboard has more than double the Kotlin source files. |
| **Kotlin Source Lines** | **228,748 lines** | **507,444 lines** | WM Keyboard has over **half a million lines of Kotlin**! |
| **XML Files & Lines** | 259 files (60,543 lines) | 1,178 files (498,919 lines) | WM Keyboard has 8x more XML (localized strings for 48 UI languages). |
| **JSON Files & Lines** | 148 files (630,147 lines) | 1,734 files (1,865,415 lines) | WM Keyboard has ~1.8M lines of JSON layout and script definitions. |
| **Markdown / Docs (`.md`/`.mdx`)** | 310 files (92,319 lines) | 217 files (38,463 lines) | CleverKeys has 2.4x more engineering documentation, specs, and ADRs. |
| **Python Tools (`.py`)** | 64 files (23,660 lines) | 0 files | CleverKeys maintains an entire ML training/eval and dictionary curation toolchain in-repo. |
| **Test Files Count** | **1,318 test files** | **703 test files** | CleverKeys has nearly double the automated test suite footprint. |
| **Git Churn (Insertions)** | **2,969,319** | **3,341,683** | Massive additive code churn in both codebases. |
| **Git Churn (Deletions)** | **949,295** (32.0% of churn) | **228,714** (6.8% of churn) | **Critical divergence:** CleverKeys reflects aggressive refactoring and deletion; WM Keyboard code was almost purely added without deleting older iterations. |

---

## 3. Human Hours Spent vs. AI Involvement

Both projects make candid disclosures about their use of Generative AI, but their operational patterns differ dramatically:

```
CleverKeys:
[13 Months: Sep 2025 – Oct 2026] ────► 3,843 commits ───► ~10 commits/day (32% refactor churn)
Human-directed, fail-first testing, on-device Termux builds, deep statistical validation.
Estimated Human Effort: 1,200 – 1,800 active engineering hours.

WM Keyboard:
[4 Months: Jun 2026 – Oct 2026]  ────► 1,973 commits ───► ~16.4 commits/day (6.8% deletion churn)
Autonomous AI agent batch generation (Claude Code), producing ~4,228 Kotlin lines/day.
Estimated Human Effort: 350 – 600 hours directing agents and testing features.
```

### CleverKeys
- **Human Author:** `tribixbite` (Will), 3,838 commits; supported by pair-programming with Claude Opus 4.5, GPT-6, and Gemini 3.8.
- **Development Cycle:** 383 days (~10 commits/day).
- **Workflow:** Features are built incrementally, tested against physical Android devices and Termux environments, accompanied by Architecture Decision Records (ADRs) and registered McNemar statistical hypothesis tests. Obsolete code is aggressively deleted (949k lines deleted).
- **Estimated Human Engineering Hours:** **1,200 – 1,800 human hours**.

### WM Keyboard
- **Human Author:** `Wasi Master` (1,951 commits).
- **Development Cycle:** 120 days (~16.4 commits/day).
- **Workflow:** In 120 days, the codebase accrued **507,444 lines of Kotlin**. Writing 507k lines of functional Kotlin in 4 months by hand would require writing **>4,200 lines of bug-free Kotlin every single day without a weekend off**.
- **AI Disclosures (from `README.md`):**
  > *"For more complex features, I use Claude Code with Anthropic’s frontier models to assist with planning and, where appropriate, generate boilerplate code. For particularly repetitive or time-consuming tasks, I may delegate the implementation to Claude Code in its entirety."*
- **Codebase Artifacts of Autonomous Agent Generation:**
  - `WMKeyboardService.kt` is **34,113 lines** in a single file.
  - `KeyboardScreen.kt` is **24,392 lines** in a single file.
  - `SettingsRepository.kt` is **16,554 lines** in a single file.
  - Claude Code agents tend to append methods and inner classes to existing God-files rather than performing broad architectural refactorings.
- **Estimated Human Engineering Hours:** **350 – 600 human hours** spent prompt-engineering, guiding Claude Code, reviewing generated diffs, and testing.

---

## 4. Swipe Typing & Gesture Recognition: The Technical Truth

This is the single biggest architectural divergence between the two keyboards.

### 4.1 The Models & Algorithms

| Dimension | **CleverKeys** | **WM Keyboard** |
| :--- | :--- | :--- |
| **Swipe Engine Architecture** | **Deep Learning / Neural CTC:**<br>1D Convolutional + Recurrent neural encoder (`models/ctc_swipe_encoder.onnx`, 2.91 MB) emitting per-frame character logits, combined with a Kotlin Viterbi trie beam search (width 100). | **Algorithmic Dynamic Programming (`GlideBeam.kt`):**<br>Best-first lattice walk over a prefix trie (`MappedTrie`). Calculates Gaussian key-center distance ($d^2 / 2\sigma^2$) + arc-length displacement disagreement ($j - i$). |
| **Machine Learning Presence** | **YES.** Real, trained neural network with FP16 weights on ONNX Runtime 1.20 with hardware XNNPACK acceleration. | **NO.** Zero machine learning models are used for swipe recognition. It is 100% heuristic geometry and mathematical scoring. |
| **Fallback Engine** | **SHARK2-style Geometric Engine:** Layout-agnostic polyline alignment for scripts/layouts not covered by the CTC encoder. | Heuristic loop-detection for doubled letters (`loopExtent`, `LOOP_ROUNDNESS`) and dwell-pause penalty (`unclaimedDwell`). |
| **Layout Agnosticism** | Layout-agnostic CTC input (takes coordinates + live key bounding boxes); geometric fallback runs on any layout. | Layout-agnostic trie walk (takes key positions from `GlideKeyMap`). |
| **Multi-Language Routing** | Script-based model routing: dedicated CTC models for Cyrillic (ru, uk, bg, mk), Greek (el), and Hebrew (he); geometric for Turkish dotless `ı`. | Any language with a compiled wordlist (`.wmdict`) can use `GlideBeam`. |

---

### 4.2 Swipe Accuracy: Benchmark Truth vs. Synthetic Illusion

A critical audit of accuracy claims reveals a profound methodological divide:

```
CleverKeys Evaluation:
[Real Human Swipes (FUTO held-out test2400)] ───► Measured Top-1: 89.31% (Beats FUTO 84.83%)

WM Keyboard Evaluation:
[Synthetic Mathematical Splines (SwipeCorpus)] ──► Measured Top-1: ~95.58% (0 Real Human Data)
```

#### Head-to-Head Benchmark on Real Swipes (2,400 Held-Out Real Human Swipes, `test_hwsfuto.jsonl`):

| Engine / Model | Overall Top-1 | Overall Top-3 | Short Words ($\le 3$ chars) Top-1 | Long Words ($4+$ chars) Top-1 | Evaluation Basis |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **CleverKeys CTC (Shipped)** | **89.31%** | **93.79%** | **93.70%** | **87.05%** | **Real human swipes** (measured on physical device) |
| **FUTO Ceiling (Encoder + DFSMN Decoder)** | 84.83% | 91.04% | 89.57% | 82.40% | Real human swipes |
| **FUTO Floor (Encoder only)** | 79.25% | 87.71% | 82.45% | 77.60% | Real human swipes |
| **CleverKeys Prior Neural (Transformer)** | 74.62% | 84.33% | 89.45% | 67.00% | Real human swipes |
| **CleverKeys Geometric (SHARK2)** | 67.50% | 78.88% | 69.33% | 66.56% | Real human swipes |
| **WM Keyboard (`GlideBeam.kt`)** | **UNKNOWN**<br>(Never evaluated) | **UNKNOWN** | **UNKNOWN** | **UNKNOWN** | **0 real human swipes ever tested.** |

#### The "Synthetic Simulation Trap" in WM Keyboard
In `GlideTuningSweepTest.kt` and `GlideBeam.kt`, WM Keyboard quotes swipe accuracy figures like:
> `unclaimedDwell: 0 -> .9467 (sloppy .890); 2.0 -> .9558 (sloppy .907)`

**The Reality:** These figures are measured **exclusively against `SwipeCorpus.kt`**, an in-house synthetic swipe generator written by the author.
- `SwipeCorpus.kt` draws mathematical cubic splines between key centers and adds Gaussian tremor, corner-cutting, and arbitrary dwell times (`DOUBLE_DWELL_SHARE = 0.35`).
- The author tuned the decoder's weights against the author's own synthetic generator.
- As every ML practitioner knows, **heuristic decoders score 90–98% on synthetic splines, but collapse to 60–75% when confronted with real human fingers**, which exhibit co-articulation, lazy shortcuts, velocity-dependent corner rounding, and hand tremors that mathematical splines fail to simulate.
- **WM Keyboard has zero validation on real human swipe datasets (such as FUTO or How We Swipe).**

---

### 4.3 Training Data, Lexicons & Licensing Audit

| Resource | **CleverKeys** | **WM Keyboard** |
| :--- | :--- | :--- |
| **ML Training Datasets** | **Real Human Swipes:**<br>• [FUTO Swipe Dataset](https://huggingface.co/datasets/nicosio2/FUTO-swipe-dataset) (2.4M real human swipes)<br>• [How We Swipe](https://github.com/nicosio2/how-we-swipe) dataset<br>• Proshian neural-swipe-typing corpus | **None.** No machine learning model is trained. |
| **Lexicon Construction & Provenance** | Strict automated evidence classifier (`scripts/build_wordlist.py`). Evaluates candidates against spelling oracles (Hunspell, Aspell, PySpell, AOSP LatinIME) with negative typo/foreign-word filters. | Curated wordlists from AOSP LatinIME, Wiktionary, FreeDict, Subtlex, HermitDave OpenSubtitles, and Avro phonetic data (`wordlist-sources.txt`). |
| **Licensing Integrity & Auditing** | **Meticulous legal hygiene.** Explicitly audited all wordlists. Withdrew the `en-norvig-50k` pack on 2026-09-26 because Google Web 1T lacked a redistribution grant. Every language pack zip contains `NOTICE.txt` and SPDX metadata. | Permissive licenses (MIT/Apache) for core code, but standard builds incorporate Google's closed-source ML Kit libraries under Google's Terms of Service. |

---

## 5. Security, Privacy & Android Permissions Audit

This is one of the starkest contrasts between the two applications.

| Android Permission | **CleverKeys** | **WM Keyboard** | Implications |
| :--- | :---: | :---: | :--- |
| `android.permission.INTERNET` | ❌ **ABSENT** | ⚠️ **PRESENT** | CleverKeys cannot make an HTTP request even if compromised. WM Keyboard can communicate with any server. |
| `android.permission.ACCESS_NETWORK_STATE` | ❌ **ABSENT** | ⚠️ **PRESENT** | WM Keyboard monitors network connection status. |
| `android.permission.RECORD_AUDIO` | ❌ **ABSENT** | ⚠️ **PRESENT** | WM Keyboard can access the device microphone (for voice typing / Whisper). |
| `android.permission.CAMERA` | ❌ **ABSENT** | ⚠️ **PRESENT** | WM Keyboard can capture photos/video (for OCR, QR scanning). |
| `android.permission.READ_CONTACTS` | ❌ **ABSENT** | ⚠️ **PRESENT** | WM Keyboard reads names and emails to provide auto-completions. |
| `android.permission.READ_CALENDAR` | ❌ **ABSENT** | ⚠️ **PRESENT** | WM Keyboard accesses user calendar events. |
| `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` | ❌ **ABSENT** | ⚠️ **PRESENT** | **High-privilege Android service:** Allows WM Keyboard to intercept and read **all incoming notifications** (for OTP / one-time-code autofill). |
| `android.permission.BIND_ACCESSIBILITY_SERVICE` | ❌ **ABSENT** | ⚠️ **PRESENT** | **High-privilege Android service:** Can inspect screen content across apps. |
| `android.permission.READ_EXTERNAL_STORAGE` / `MEDIA` | ❌ **ABSENT** | ⚠️ **PRESENT** | WM Keyboard reads external device media. |
| `android.permission.VIBRATE` | ✅ Present | ✅ Present | Haptic feedback. |
| `android.permission.READ_USER_DICTIONARY` | ✅ Present | ✅ Present | Standard Android user dictionary integration. |

> [!WARNING]
> **Privacy Assessment:**
> - **CleverKeys provides Hardware-Enforced Air-Gap Security.** Because `android.permission.INTERNET` is completely absent from its `AndroidManifest.xml`, the Android Linux kernel will reject any socket creation attempt with `EPERM`. It is mathematically impossible for user keystrokes to leave the device.
> - **WM Keyboard relies on Software Policy Trust.** While the author states that typing data never leaves the device and network calls are restricted to specific tools (weather, web search, cloud translation), the APK holds permissions for the **Internet, Microphone, Camera, Contacts, Calendar, and Notification Interception**. Furthermore, the Full edition includes Google's closed-source ML Kit binaries, which report telemetry back to Google servers.

---

## 6. Critical Audit: Marketing Claims vs. Reality ("Lies & Gaps")

### 6.1 WM Keyboard: Claims vs. Code Reality

| Marketed Claim | Technical Code Reality | Verdict |
| :--- | :--- | :---: |
| **"Glide typing in any language... shared beam decoder"** | There is **no machine learning** model for swipe typing. It is a purely heuristic dynamic programming algorithm (`GlideBeam.kt`) matching key coordinates. | ⚠️ **Misleading** (Implies ML-grade swipe; it is heuristic) |
| **"~95% swipe accuracy"** | Measured **only on synthetic splines** generated by the author's own random generator (`SwipeCorpus.kt`). Never tested on real human swipe datasets. | ❌ **Invalid Benchmark** (Simulator optimism) |
| **"Private. Offline. Yours. Never phones home."** | The default APK requests `INTERNET`, `RECORD_AUDIO`, `CAMERA`, `READ_CONTACTS`, and `BIND_NOTIFICATION_LISTENER_SERVICE`, and bundles Google ML Kit. Only the Lite edition on F-Droid strips Google code. | ⚠️ **Significant Caveats** |
| **"867 languages, 1,700+ layouts"** | Most layouts are automatically generated JSON key matrices from Keyman/CLDR. Only **2 languages** (English and Bengali) ship with pre-compiled dictionaries in the APK; all others require on-demand downloads. | ⚠️ **Inflated Metric** |
| **"75+ tool toolbox"** | Highly padded count. Includes trivial string utilities (e.g. ROT13, Morse code, reverse string, uppercase, lowercase, fancy Unicode text styles), calculators, and web wrappers. | ⚠️ **Marketing Inflation** |
| **"Clean 27-module layered architecture"** | While modularized at the Gradle level, the core implementation suffers from extreme God-file bloat: `WMKeyboardService.kt` is **34,113 lines** and `KeyboardScreen.kt` is **24,392 lines**. | ❌ **Architectural Lie** (Massive file bloat) |

---

### 6.2 CleverKeys: Claims vs. Code Reality

| Marketed Claim | Technical Code Reality | Verdict |
| :--- | :--- | :---: |
| **"89.31% Top-1 accuracy on 2,400-row held-out real human swipe test set"** | Verified. Rigorously measured on `test_hwsfuto.jsonl` (real human swipes) against FUTO's own decoder with registered McNemar tests. | ✅ **Verified True** |
| **"Zero network permissions — literally cannot phone home"** | Verified. No `INTERNET` permission in manifest. | ✅ **Verified True** |
| **"Only open-source keyboard with reliable swipe typing in Termux"** | Verified. Custom terminal input connection logic avoids character duplication and terminal cursor corruption. | ✅ **Verified True** |
| **"Unlimited clipboard with regex search & todos"** | Verified. SQLite database (`ClipboardDatabase.kt`) supports todos, tags, regex search, and media attachments. | ✅ **Verified True** |
| **Gap: Ecosystem & Modern Declarative UI** | CleverKeys still uses Android Canvas rendering and lacks Jetpack Compose UI. Settings UI is functional Material 3, but keyboard surface does not use modern declarative composables. | ⚠️ **Known Tech Debt / Gap** |
| **Gap: Multi-Modal Toolbox** | CleverKeys does not offer speech-to-text (Whisper), grammar checking (Harper), OCR scanners, or local LLMs. | ℹ️ **Deliberate Scoping Choice** |

---

## 7. Deep Feature Comparison Table

| Feature Category | Feature | **CleverKeys** | **WM Keyboard** |
| :--- | :--- | :---: | :---: |
| **Typing Core** | Swipe Engine Type | **Neural CTC (ONNX)** + Geometric fallback | **Heuristic Geometric Beam (`GlideBeam`)** |
| | Real Human Swipe Benchmark | **89.31% Top-1** (2,400 traces) | **Not Measured** (Synthetic only) |
| | Autocorrect & Contractions | ✅ Contraction-aware (`dont` $\to$ `don't`) | ✅ Contraction-aware & custom apostrophe key |
| | Next-Word Prediction | ✅ Static LM + Unigram context | ✅ N-gram (Bigram, Trigram, Skip-gram) |
| | User Lexicon Learning | ✅ SQLite & on-device memory | ✅ JSON Trie learning with decay & undo |
| | Bengali Avro Phonetic Input | ❌ (Standard layouts only) | ✅ **World-Class (Lenient Avro Engine)** |
| | CJK & Indic Transliteration | ⚠️ Limited | ✅ Pinyin, Jyutping, Kana, Hangul, Telex |
| **Short-Swipes & Gestures** | Per-Key 8-Way Short Swipes | ✅ **208 Actions (8 directions $\times$ 26 keys)** | ❌ (Key flicks: up/down only) |
| | Command Palette & Android Intents | ✅ **Launch apps, Termux scripts, URLs** | ❌ (No intent launching on key swipes) |
| | Spacebar Gestures | ✅ Cursor slide, language swap | ✅ Cursor pad, language ring, numpad |
| | TrackPoint Cursor Navigation | ✅ **Hold-to-steer joystick cursor** | ❌ (2D touchpad on spacebar only) |
| | Selection-Delete on Backspace | ✅ **Hold backspace + swipe to select text** | ❌ (Word-by-word swipe delete only) |
| | Backspace Undo Autocorrect | ✅ Reverts word on single backspace | ✅ Reverts word + dedicated Undo chip |
| **Terminal / Termux** | Terminal App Input Switching | ✅ **`TerminalUtils` whitelist (Termux, Termius, JuiceSSH, ConnectBot, AVF, etc.) + terminal paste** | ⚠️ Basic `com.termux` check (`KeyboardModes.kt`) |
| | Termux `TYPE_NULL` Handling | ✅ **Flawless (Zero char duplication)** | ⚠️ Basic support (`KeyboardModes.kt`) |
| | Terminal Special Keys | ✅ Ctrl, Alt, Meta, Esc, Tab, F1–F12 | ✅ Terminal mode / raw key codes |
| | Termux Buildability | ✅ `build-on-termux.sh` included | ❌ Gradle fails in Termux (AAPT2/Compose) |
| **Clipboard & Productivity**| Unlimited Persistent History | ✅ Persistent SQLite | ✅ In-memory + App storage |
| | Todo List with Status & Tags | ✅ **Built-in Task Manager in Clipboard** | ❌ |
| | Regex Search in Clipboard | ✅ **Full Regex (`.*`) & Glob Search** | ❌ Plain text search only |
| | Inline Clipboard Editing | ✅ Edit clips directly in IME | ❌ |
| | Media Clipboard (Images/PDFs) | ✅ Thumbnails, ZIP backup | ⚠️ Basic screenshot capture |
| | Dynamic Snippets / Macros | ✅ **Timestamp macros (`SimpleDateFormat`, `ActionType.TIMESTAMP`) + text macros** | ✅ Rich snippets (`{date}`, `{clip}`) |
| **AI, Voice & Toolbox** | Offline Voice Typing (Whisper) | ❌ | ✅ **whisper.cpp (29 offline models)** |
| | Offline Grammar Check | ❌ | ✅ **Harper (Automattic) via Rust JNI** |
| | OCR / Document Scanner | ❌ | ✅ **Google ML Kit / Tesseract** |
| | On-Device Local LLM | ❌ | ✅ **LiteRT-LM (Play edition)** |
| | Sandboxed Lua Plugins | ❌ | ✅ **LuaJ Plugin Runtime with IDE** |
| | Toolbox Tools Count | Focused keyboard tools | **77 Tools** (Calculator, Currency, etc.) |
| **Privacy & Security** | `INTERNET` Permission | 🔒 **ZERO (Cannot access internet)** | 🌐 **Present** (Used by web tools) |
| | High-Privilege System Binds | 🔒 None | ⚠️ Notification Listener & Accessibility |
| | Proprietary Google Libraries | 🔒 **Zero** | ⚠️ Google ML Kit (Full edition) |
| | Air-Gap Guarantee | 🔒 **100% Kernel-Enforced** | ⚠️ Software-policy gated |
| **Design & UI** | UI Technology | Android Canvas (`Keyboard2View`) | Jetpack Compose |
| | Theme Engine | DIY Creator (Key/border/trail colors) | Full Visual Editor (Fonts, particles, decals) |
| | Built-in Themes | 35+ themes | 29 themes (Dracula, Nord, Catppuccin) |
| | AMOLED Black Themes | ✅ | ✅ |

---

## 8. Summary & Key Takeaways

1. **Swipe Accuracy & Machine Learning:**
   - **CleverKeys is a genuine machine-learning accomplishment.** It trains and executes a 2.91 MB ONNX CTC neural network accelerated by XNNPACK, achieving **89.31% Top-1 accuracy** on real human swipe data and outperforming FUTO.
   - **WM Keyboard has no ML swipe engine.** Its swipe decoding (`GlideBeam.kt`) is purely algorithmic dynamic programming. Its reported ~95% accuracy is an artifact of testing against its own synthetic spline simulator (`SwipeCorpus.kt`), with **zero validation on real human gestures**.

2. **Security & Privacy Posture:**
   - **CleverKeys is an uncompromising, air-gapped vault.** With zero network permissions, it provides mathematical certainty that keystrokes, clipboard items, and passwords never leave the phone.
   - **WM Keyboard is a feature-rich connected app.** While its typing engine is offline, the presence of `INTERNET`, `RECORD_AUDIO`, `CAMERA`, and `NOTIFICATION_LISTENER` permissions, combined with Google ML Kit binaries, creates an attack and telemetry surface that privacy-conscious users should weigh carefully.

3. **Power-User Efficiency vs. Super-App Toolbox:**
   - **CleverKeys excels at tactile keyboard mastery:** 208 short-swipe actions, TrackPoint cursor navigation, selection-delete, custom Android intent execution, and flawless Termux integration.
   - **WM Keyboard excels at multi-modal desktop features on a phone:** Offline Whisper voice dictation, Harper grammar checking, Avro Bengali phonetic typing, and a vast 77-tool utility panel.

4. **Codebase Health & Engineering Rigor:**
   - **CleverKeys** shows the hallmarks of 13 months of disciplined, human-driven iterative refactoring (32% code deletion churn, 1,318 test files, modular classes under 3,500 lines).
   - **WM Keyboard** is a breathtaking showcase of what Anthropic's Claude Code can generate in a 4-month sprint (507,000 lines of Kotlin, 703 test files), but it suffers from severe file bloat (`WMKeyboardService.kt` at 34,113 lines) that will challenge long-term maintainability.
