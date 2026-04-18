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
    - **Current limitation:** The v1 gesture engine is trained on English + QWERTY. Quality is acceptable for other Latin-script QWERTY languages (es, fr, pt, it, de, nl, id, ms, tl, sw) but degrades on non-QWERTY layouts (AZERTY, QWERTZ, Dvorak, Colemak, Neo2) and on non-Latin scripts (Cyrillic, Greek, Arabic, Devanagari, CJK romanization, etc.), where swipe is currently auto-disabled on non-QWERTY row shapes (see [#9](https://github.com/tribixbite/CleverKeys/issues/9)).
    - **Goal:** A layout-aware gesture decoder that accepts arbitrary keyboard geometries and script-specific vocabularies as inputs, enabling swipe on every supported layout and language without retraining a separate model per layout.
    - **Implementation plan:**
        - Synthesize large-scale swipe datasets for alternative layouts (Dvorak, Colemak, Neo2, AZERTY, QWERTZ) from the existing English corpus via geometric remap.
        - Extend the encoder to consume layout geometry (key centroids + label embeddings) as a conditioning input rather than hard-coded QWERTY coordinates.
        - Train either a single multi-head model or a small family of per-script models; evaluate against held-out native-speaker swipe data per language.
        - Ship behind a feature flag, roll out language-by-language as accuracy clears a threshold.

- [ ] **Context-Aware Predictions**
    - Improve next-word prediction using lightweight transformer models (distilled BERT/GPT) optimized for mobile CPU (ARM64).

## 🛠 Customization & UI

- [ ] **Smart Profile System**
    - Auto-switch profiles based on active app (e.g., "Code" profile for Termux, "Chat" profile for WhatsApp).

- [ ] **Advanced Theme Engine**
    - Shareable JSON-based themes.
    - Dynamic wallpaper-based theming (Material You/Monet) expansion.

## 🤝 Community & Open Source

- [ ] **Dataset Contribution Pipeline:** Optional, privacy-preserving mechanism for users to donate anonymized swipe trajectories to improve the open-source model.
- [ ] **Documentation:** Comprehensive guides for creating custom layouts and retraining the model.
