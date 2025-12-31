# Dictionary and Multi-Language System Specification

## Feature Overview
**Feature Name**: Dictionary and Multi-Language System
**Priority**: P1 (High)
**Status**: Proposal / In Development
**Target Version**: v1.2.0

### Summary
This specification defines the architecture for managing dictionaries, handling multiple languages, and supporting dynamic language switching in CleverKeys. It unifies static dictionaries, user-defined words, and neural prediction models into a cohesive "Language Pack" system.

### Motivation
To support a global user base, CleverKeys must seamlessly handle multiple languages. The current system relies on assets baked into the APK. A more flexible system is needed to allow users to install, manage, and switch between languages without requiring app updates.

---

## 1. System Architecture

### 1.1 The Language Pack Concept
A "Language Pack" is a self-contained unit that provides support for a specific language locale (e.g., `en`, `fr`, `es-rMX`).

**Components of a Language Pack:**
1.  **Main Dictionary (`.bin` or `.json`)**: The static vocabulary list with frequencies.
    *   *Path*: `dictionaries/{lang}_enhanced.bin`
2.  **Neural Models (`.onnx`)**: Models for swipe prediction (Encoder + Decoder).
    *   *Path*: `models/swipe_encoder_{lang}.onnx` & `models/swipe_decoder_{lang}.onnx`
3.  **Keyboard Layouts (`.xml`)**: Standard layouts for the language (e.g., QWERTY, AZERTY).
    *   *Path*: `layouts/{lang}_*.xml`
4.  **Metadata**: Information about the pack (Version, Name, Author).

### 1.2 Dictionary Layers
The prediction engine utilizes a tiered dictionary system to resolve word candidates.

*   **Layer 1: Main Dictionary (Read-Only)**
    *   Source: Language Pack asset.
    *   Content: tens of thousands of common words with frequency data.
    *   Management: Loaded via `MainDictionarySource`.

*   **Layer 2: User Dictionary (Read-Write)**
    *   Source: Android System `UserDictionary` content provider.
    *   Content: Words learned by other apps or added globally by the user.
    *   Management: Loaded via `UserDictionarySource`.

*   **Layer 3: Custom Dictionary (Read-Write)**
    *   Source: App-internal `SharedPreferences` (`user_dictionary`).
    *   Content: Words explicitly added by the user within CleverKeys or learned from typing.
    *   Management: Loaded via `CustomDictionarySource`.

*   **Layer 4: Disabled Words (Read-Write)**
    *   Source: App-internal `SharedPreferences`.
    *   Content: Words from the Main Dictionary that the user has explicitly blocked (e.g., offensive words or annoying auto-corrects).
    *   Management: Loaded via `DisabledDictionarySource`.

**Resolution Logic:**
1.  Candidates are gathered from Layers 1, 2, and 3.
2.  Any candidate present in Layer 4 is filtered out.
3.  Candidates are scored based on frequency, source priority (Custom > User > Main), and neural probability.

---

## 2. Multi-Language Switching

### 2.1 Manual Switching
*   **Trigger**: User taps the "Globe" key or long-presses Spacebar.
*   **Action**: Cycles through the list of *Active Languages* enabled in Settings.
*   **Outcome**:
    *   The `MainDictionarySource` is hot-swapped to the new language.
    *   The `MultiLanguageManager` loads the corresponding ONNX models.
    *   The keyboard layout is updated (if a specific layout is linked to the language).

### 2.2 Auto-Switching (Polyglot Mode)
*   **Trigger**: `MultiLanguageManager` detects high probability of a different language based on `recentWords` context.
*   **Action**: Seamlessly switches the active dictionary and models without user intervention.
*   **Constraint**: Only switches between *Active Languages* to prevent false positives from unsupported languages.

---

## 3. Import and Management Workflows

### 3.1 Installing New Languages
*   **Mechanism**: "Language Store" in Settings.
*   **Source**:
    *   *Bundled*: Common languages included in APK assets.
    *   *Downloadable*: Hosted on GitHub Releases or a dedicated CDN.
*   **Process**:
    1.  User selects language.
    2.  App downloads ZIP bundle.
    3.  Files are extracted to app-private storage (`files/languages/{lang}/`).
    4.  `DictionaryManager` registers the new language availability.

### 3.2 Dictionary Import/Export
*   **Scope**: Custom Dictionary (Layer 3) and Disabled Words (Layer 4).
*   **Format**: JSON.
*   **Import Logic**:
    *   Read JSON.
    *   For each word: Check against existing Custom Dictionary.
    *   If new, add to `SharedPreferences`.
    *   *Crucial*: Do NOT write to Android System `UserDictionary` during bulk import to avoid pollution and permission issues.

### 3.3 Custom Word Management
*   **UI**: `DictionaryManagerActivity` (Jetpack Compose).
*   **Tabs**:
    *   *Custom*: View/Edit/Delete words in Layer 3.
    *   *User*: View words in Layer 2 (Read-only or System Intent to edit).
    *   *Disabled*: View/Re-enable words in Layer 4.
*   **Interaction**: Swipe-to-delete, Undo support, Search filter.

---

## 4. Implementation Details

### 4.1 Data Structures
```kotlin
// Representation of a word in the aggregation pipeline
data class DictionaryWord(
    val word: String,
    val frequency: Int,
    val source: WordSource, // MAIN, USER, CUSTOM
    var enabled: Boolean = true
)
```

### 4.2 Key Classes
*   **`DictionaryManager`**: Singleton. Holds references to active `WordPredictor` instances. Handles language lifecycle.
*   **`MultiLanguageManager`**: Manages ONNX sessions. Handles auto-detection logic.
*   **`BackupRestoreManager`**: Handles JSON serialization for Import/Export.

### 4.3 Performance Considerations
*   **Binary Dictionaries**: Use `.bin` format with pre-calculated prefix trees for Main Dictionary to ensure instant load times (<50ms).
*   **Async Loading**: Language switching must happen on background threads (`Dispatchers.IO`).
*   **Memory Management**: Inactive language models (ONNX sessions) should be unloaded after a timeout to conserve RAM.

---

## 5. Future Roadmap
*   **v1.3**: Cloud sync for Custom Dictionaries.
*   **v1.4**: "Hybrid" dictionary mode allowing simultaneous suggestions from two languages without full switching.
*   **v1.5**: User-generated Language Packs tool.
