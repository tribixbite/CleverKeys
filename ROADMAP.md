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

- [ ] **Layout-Agnostic Training**
    - **Current Limitation:** The model is trained primarily on QWERTY spatial data.
    - **Goal:** Support Dvorak, Colemak, Neo2, and other layouts for gesture typing.
    - **Implementation:**
        - Generate synthetic swipe datasets for alternative layouts.
        - Train layout-specific ONNX models or a single multi-head model that takes layout geometry as input.

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
