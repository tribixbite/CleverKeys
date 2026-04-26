# CleverKeys Roadmap

This document outlines the planned development path for CleverKeys, focusing on expanding device support, improving neural prediction capabilities, and enhancing customization.

## ✅ Recently Completed (v1.2.x)

### v1.2.9
- [x] **Timestamp Keys** - Insert formatted date/time with custom patterns
- [x] **Pre-defined Timestamp Shortcuts** - 8 ready-to-use timestamp keys

### v1.2.8
- [x] **Quick Settings Tile** - Add keyboard to Android Quick Settings panel
- [x] **Password Manager Clipboard Exclusion** - Privacy for 1Password, Bitwarden, etc.
- [x] **Test Keyboard Field** - Built-in text field in Settings for testing
- [x] **Android 15 Edge-to-Edge Fix** - Navigation bar no longer covers keyboard
- [x] **Monet Theme Crash Fix** - Fixed dynamic color extraction on tablets

### v1.2.4
- [x] **Selection-Delete Mode** - Swipe-hold backspace to select and delete text
- [x] **TrackPoint Navigation** - Joystick-style cursor control on nav keys

### v1.2.0-1.2.3
- [x] **Space Key Repeat on Hold** - Long-press space now repeats like delete key
- [x] **Finger Occlusion Compensation** - Configurable Y-offset setting (0-50%)
- [x] **Per-Key Short Swipe Customization** - 204+ commands assignable to any key
- [x] **Multi-Language Swipe Typing** - 11 languages with accent recovery
- [x] **Language Quick Toggle** - Swap between primary/secondary languages instantly
- [x] **Profile System** - Layout import/export with gesture customizations
- [x] **New Website** - Tailwind dark-mode homepage with demo at `/demo/`
- [x] **User Guide Wiki** - 38-page comprehensive user documentation

## 🚀 Core Features & Stability

- [ ] **Expanded Terminal Support**
    - Replace hardcoded `com.termux` checks with a robust `TerminalUtils` system.
    - Support for broad range of terminal emulators: Termius, JuiceSSH, ConnectBot, Android Virtualization Framework (`com.android.virtualization.terminal`), and others.
    - **Custom Package Config:** Allow users to manually designate apps as "Terminal Mode" to force `Ctrl+W` deletion and raw key cursor movement.

- [x] **Custom Word Handling** *(Completed v1.1.88)*
    - ~~On-the-fly Dictionary Addition~~ → Dictionary Manager with 3-tab UI
    - ~~Custom Dictionary Weighting~~ → Per-language beam search trie with priority

## 🧠 Neural Network & Prediction

- [ ] **Vocabulary Expansion (Fine-tuning)**
    - Address gaps in current vocabulary (e.g., words like "popsicle", "narcissist").
    - Implement a federated-style on-device learning mechanism to fine-tune the ONNX model on user's typing history without data leaving the device.

- [ ] **Layout-Agnostic & Multi-Script Gesture Model** *(target: Q2–Q3 2026)*
    - **Current limitation:** The v1 gesture engine is trained on English + QWERTY. Quality is acceptable for other Latin-script QWERTY languages (es, fr, pt, it, de, nl, id, ms, tl, sw) but degrades on non-QWERTY layouts (AZERTY, QWERTZ, Dvorak, Colemak, Neo2) and on non-Latin scripts (Cyrillic for Russian/Ukrainian/Bulgarian/Serbian, Greek, Arabic, Devanagari, CJK romanization, etc.), where swipe is currently auto-disabled on non-QWERTY row shapes (see [#9](https://github.com/tribixbite/CleverKeys/issues/9)).
    - **Goal:** Two complementary swipe pipelines — keep the trained transformer for QWERTY+Latin (current strength) and add a geometric/template path-matcher for arbitrary layouts and scripts. The geometric engine resembles [Urik](https://github.com/urikdev/Urik) and AnySoftKeyboard's gesture approach: dictionary-driven candidate scoring against the swipe path's nearest-key sequence, no per-layout training required.
    - **Why dual-path:** Geometric matchers ship instantly for any layout (Russian Cyrillic, AZERTY, QWERTZ, Dvorak, Colemak, Arabic, Greek) once a dictionary exists, and they compose with our existing dictionary infrastructure. Trained transformer stays the default for English+QWERTY where its accuracy lead is largest; geometric path takes over when the active layout doesn't match the trained geometry.
    - **Implementation plan:**
        - Build a geometric scorer that walks a swipe trajectory against per-layout key centroids and ranks dictionary words by path-length + nearest-key alignment cost.
        - Add Russian (Cyrillic ЙЦУКЕН) layout + dictionary as the first non-Latin target. Validate with native-speaker swipe sessions.
        - Extend to AZERTY, QWERTZ, Dvorak, Colemak, Neo2 using the same engine — just new layout geometries + existing dictionaries.
        - In parallel, explore extending the trained encoder to consume layout geometry as a conditioning input so the transformer eventually catches up on alt layouts.
        - Ship behind a feature flag; auto-route per layout: transformer for QWERTY-Latin, geometric for everything else.

- [ ] **Next-Word Prediction**
    - Top-of-suggestion-bar word prediction based on the previous word(s), distinct from autocorrect/swipe.
    - Distilled transformer (BERT-style or n-gram blend) optimized for ARM64 CPU; sub-50ms latency budget on mid-range devices.
    - Per-language models, sharing the dictionary infrastructure already in place.

## 🛠 Customization & UI

- [ ] **Smart Profile System**
    - Auto-switch profiles based on active app (e.g., "Code" profile for Termux, "Chat" profile for WhatsApp).

- [ ] **Advanced Theme Engine**
    - Shareable JSON-based themes.
    - Dynamic wallpaper-based theming (Material You/Monet) expansion.

## 🤝 Community & Open Source

- [ ] **Dataset Contribution Pipeline:** Optional, privacy-preserving mechanism for users to donate anonymized swipe trajectories to improve the open-source model.
- [ ] **Documentation:** Comprehensive guides for creating custom layouts and retraining the model.
