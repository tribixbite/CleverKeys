# To-Do List for Feature Parity with Unexpected-Keyboard

This document lists the outstanding work required to achieve full feature parity with `Julow/Unexpected-Keyboard`.

## Core Keyboard Features

- [ ] **Implement Short Swipes for Symbols:**
    - Investigate the current swipe implementation to determine if it supports symbol swipes or only word prediction.
    - If necessary, implement the logic to handle short swipes on keys to output alternative symbols, as per Unexpected-Keyboard's core feature.
    - This includes handling swipes in different directions (e.g., up, down, left, right, and diagonals).

- [ ] **Implement Spacebar Cursor Navigation:**
    - Add a gesture detector to the spacebar to detect left and right swipes.
    - On swipe, move the cursor in the current text field accordingly.

- [ ] **Implement Dead Keys:**
    - Add support for dead keys to allow typing accented characters.
    - This will likely involve modifying the key processing logic to handle dead key states.

## Programmer-Focused Features

- [ ] **Add Special Keys:**
    - Add `Tab`, `Esc`, and arrow keys to the keyboard layout.
    - Ensure these keys function correctly in terminal emulators and other relevant applications.

- [ ] **Implement Ctrl-Key Combinations:**
    - Add support for `Ctrl` key combinations (e.g., `Ctrl-C`, `Ctrl-V`, `Ctrl-Z`).
    - This will require handling the `Ctrl` modifier state and dispatching the correct key events.

## Settings and Customization

- [ ] **Complete Settings Implementation:**
    - **Themes:** Implement the theme switching logic for the "Theme" spinner in `SettingsActivity`. This should include "Light", "Dark", and "Black" (OLED) themes.
    - **Keyboard Height:** Implement the logic for the "Keyboard Height" slider in `SettingsActivity`.
    - **Vibration:** Implement the logic for the "Enable Vibration" checkbox in `SettingsActivity`.
    - **Debug Information:** Implement the logic for the "Show Debug Information" checkbox in `SettingsActivity`.

- [ ] **Implement Settings Access via Swipe:**
    - Add a gesture to open the settings activity by swiping a key (e.g., left-down corner swipe).

- [ ] **Enhance Layout Customization:**
    - **XML Layout Support:** Ensure that the `CustomLayoutEditor` can import, parse, and apply keyboard layouts from `Unexpected-Keyboard`'s XML format.
    - **Layout Editor Functionality:** Flesh out the `CustomLayoutEditor` to be a fully functional layout editor, allowing users to create and modify layouts graphically.

## Code Cleanup and Refactoring

- [ ] **Remove Redundant Settings:**
    - The `CleverKeysSettings.kt` file appears to be a debug or developer-focused settings page. Evaluate if its functionality can be merged into the main `SettingsActivity` or a separate debug menu, and remove the file if it's redundant.

- [ ] **Kotlin-ize the codebase:**
    - The project is a mix of Java and Kotlin. Migrate the remaining Java code to Kotlin to have a consistent codebase.

- [ ] **Full Privacy Audit:**
    - Although the code doesn't show obvious network access, a full audit should be performed to ensure that the keyboard is fully privacy-focused and makes no network requests, in line with `Unexpected-Keyboard`'s philosophy.
