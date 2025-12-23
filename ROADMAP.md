# CleverKeys Roadmap

This document outlines the planned development path for CleverKeys, focusing on expanding device support, improving neural prediction capabilities, and enhancing customization.

## 🚀 Core Features & Stability

- [ ] **Expanded Terminal Support**
    - Replace hardcoded `com.termux` checks with a robust `TerminalUtils` system.
    - Support for broad range of terminal emulators: Termius, JuiceSSH, ConnectBot, Android Virtualization Framework (`com.android.virtualization.terminal`), and others.
    - **Custom Package Config:** Allow users to manually designate apps as "Terminal Mode" to force `Ctrl+W` deletion and raw key cursor movement.

- [ ] **Custom Word Handling**
    - **On-the-fly Dictionary Addition:** Allow users to instantly add swiped words to the dictionary if they aren't recognized.
    - **Custom Dictionary Weighting:** Ensure user-added words (slang, technical jargon) are prioritized in the neural beam search.

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
